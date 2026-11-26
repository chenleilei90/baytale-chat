package plane

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"baychat/server/internal/room"
)

const (
	// PlaneTTL 纸飞机公开卡片存活时长（24 小时）
	PlaneTTL = 24 * time.Hour
	// CreateCooldown 同一客户端连续放飞的冷却时间（60 秒）
	CreateCooldown = 60 * time.Second
	// MaxPlanesPerSender 单客户端同时活跃的最大纸飞机数量
	MaxPlanesPerSender = 3
	// MaxGlobalPlanes 广场同时存在的最大纸飞机数量
	MaxGlobalPlanes = 500
	// MaxMessageRunes 留言最大字符数
	MaxMessageRunes = 120
	// MaxFiles 单架纸飞机最多附带的文件元数据条数
	MaxFiles = 5
	// MaxFileNameRunes 文件名最大字符数
	MaxFileNameRunes = 80
	// MaxSenderNameRunes 放飞者昵称最大字符数
	MaxSenderNameRunes = 32
	// ReportThreshold 触发自动下架的不同举报人数阈值
	ReportThreshold = 3
	// DefaultListLimit 列表默认返回数量
	DefaultListLimit = 24
	// MaxListLimit 列表单次最大返回数量
	MaxListLimit = 50
)

var (
	ErrPlaneNotFound         = errors.New("纸飞机不存在或已落地")
	ErrPlaneExpired          = errors.New("纸飞机已超过 24 小时或关联房间已销毁")
	ErrInvalidPlane          = errors.New("纸飞机至少需要包含 1 条留言或 1 个文件信息")
	ErrPlaneMessageTooLong   = errors.New("纸飞机留言最多 120 个字")
	ErrPlaneTooManyFiles     = errors.New("单架纸飞机最多附带 5 个文件信息")
	ErrPlaneCooldown         = errors.New("放飞过于频繁，请 60 秒后再试")
	ErrPlaneLimitSender      = errors.New("同一设备同时最多放飞 3 架纸飞机")
	ErrPlaneLimitGlobal      = errors.New("广场纸飞机数量已达上限，请稍后再试")
	ErrPlaneEntryClosed      = errors.New("放飞者未开放通过纸飞机进入房间")
	ErrPlaneEntryExpired     = errors.New("房主已修改房间密码，请手动输入密码进入")
	ErrPlaneOwnerRequired    = errors.New("加密房间仅房主可开启「允许通过纸飞机进入房间」")
	ErrPlanePasswordRequired = errors.New("请提供正确的房间密码后再开启免密纸飞机入口")
	ErrPlaneSelfReport       = errors.New("不能举报自己放飞的纸飞机")
	ErrPlaneAlreadyReported  = errors.New("你已举报过这架纸飞机")
)

// RoomProvider 抽象纸飞机依赖的房间校验接口，便于单元测试解耦
type RoomProvider interface {
	GetRoomInfo(rawCode string) (*room.RoomSummary, error)
	VerifyAccess(rawCode, password string) (*room.RoomSummary, error)
	VerifyOwner(rawCode, ownerToken, password string) (*room.RoomSummary, error)
}

// FileMeta 纸飞机内携带的公开文件元数据（不保存文件本体）
type FileMeta struct {
	Name string `json:"name"`
	Size int64  `json:"size"`
	Kind string `json:"kind"` // image | video | audio | archive | doc | apk | file
}

// Plane 公开广场可见的纸飞机卡片数据
type Plane struct {
	ID           string     `json:"id"`
	RoomCode     string     `json:"roomCode"`
	SenderName   string     `json:"senderName"`
	SenderAvatar string     `json:"senderAvatar"`
	SenderID     string     `json:"-"` // 仅内存防刷与防自举，永不序列化下发
	Message      string     `json:"message"`
	Files        []FileMeta `json:"files"`
	HasPassword  bool       `json:"hasPassword"`
	AllowEntry   bool       `json:"allowEntry"`
	CreatedAt    int64      `json:"createdAt"`
	ExpiresAt    int64      `json:"expiresAt"`

	// 仅在内存保存房主显式授权的明文口令（无 json tag，永不序列化或落盘）
	entryPassword string
	reporters     map[string]struct{}
}

// CreateRequest 放飞纸飞机请求参数
type CreateRequest struct {
	ClientID     string     `json:"clientId"`
	RoomCode     string     `json:"roomCode"`
	SenderName   string     `json:"senderName"`
	SenderAvatar string     `json:"senderAvatar"`
	Message      string     `json:"message"`
	Files        []FileMeta `json:"files"`
	AllowEntry   *bool      `json:"allowEntry,omitempty"` // 缺省视为 true
	OwnerToken   string     `json:"ownerToken,omitempty"`
	Password     string     `json:"password,omitempty"`
}

// JoinResult 点击纸飞机「加入房间」后服务端返回的结果
type JoinResult struct {
	RoomCode    string `json:"roomCode"`
	HasPassword bool   `json:"hasPassword"`
	Password    string `json:"password,omitempty"`
}

// ReportRequest 举报纸飞机请求参数
type ReportRequest struct {
	ClientID string `json:"clientId"`
}

// ReportResult 举报处理结果
type ReportResult struct {
	Deleted     bool `json:"deleted"`
	ReportCount int  `json:"reportCount"`
}

// Service 管理纸飞机生命周期、权限校验、防刷与举报清理
type Service struct {
	mu          sync.Mutex
	rooms       RoomProvider
	planes      map[string]*Plane
	lastCreated map[string]time.Time
	nowFunc     func() time.Time
	stopCh      chan struct{}
}

// NewService 创建纸飞机服务并启动后台过期清理协程
func NewService(rooms RoomProvider) *Service {
	s := &Service{
		rooms:       rooms,
		planes:      make(map[string]*Plane),
		lastCreated: make(map[string]time.Time),
		nowFunc:     time.Now,
		stopCh:      make(chan struct{}),
	}
	go s.cleanupLoop()
	return s
}

// Close 停止后台清理协程
func (s *Service) Close() {
	select {
	case <-s.stopCh:
	default:
		close(s.stopCh)
	}
}

// SetNowFunc 仅供单元测试注入可控时钟
func (s *Service) SetNowFunc(fn func() time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.nowFunc = fn
}

func (s *Service) now() time.Time {
	if s.nowFunc != nil {
		return s.nowFunc()
	}
	return time.Now()
}

func (s *Service) cleanupLoop() {
	ticker := time.NewTicker(time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-s.stopCh:
			return
		case <-ticker.C:
			s.PurgeExpired()
		}
	}
}

// PurgeExpired 清理过期或所属房间已销毁的纸飞机
func (s *Service) PurgeExpired() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	s.purgeExpiredLocked(now)
}

func (s *Service) purgeExpiredLocked(now time.Time) {
	nowMs := now.UnixMilli()
	for id, p := range s.planes {
		if nowMs >= p.ExpiresAt {
			delete(s.planes, id)
		}
	}
	cooldownCutoff := now.Add(-2 * CreateCooldown)
	for cid, ts := range s.lastCreated {
		if ts.Before(cooldownCutoff) {
			delete(s.lastCreated, cid)
		}
	}
}

// Create 校验并放飞一架新纸飞机
func (s *Service) Create(req CreateRequest) (*Plane, error) {
	clientID := strings.TrimSpace(req.ClientID)
	if clientID == "" || len(clientID) > 64 {
		return nil, errors.New("缺少有效的客户端标识")
	}

	roomCode := room.NormalizeCode(req.RoomCode)
	if roomCode == "" {
		return nil, room.ErrInvalidRoomCode
	}

	msg := strings.TrimSpace(req.Message)
	if utf8.RuneCountInString(msg) > MaxMessageRunes {
		return nil, ErrPlaneMessageTooLong
	}

	if len(req.Files) > MaxFiles {
		return nil, ErrPlaneTooManyFiles
	}
	files := sanitizeFiles(req.Files)
	if msg == "" && len(files) == 0 {
		return nil, ErrInvalidPlane
	}

	summary, err := s.rooms.GetRoomInfo(roomCode)
	if err != nil {
		return nil, err
	}

	allowEntry := true
	if req.AllowEntry != nil {
		allowEntry = *req.AllowEntry
	}

	var entryPassword string
	if summary.HasPassword && allowEntry {
		// 加密房间仅允许房主开启免密纸飞机入口，并核对明文口令保存在内存
		if _, err := s.rooms.VerifyOwner(roomCode, strings.TrimSpace(req.OwnerToken), req.Password); err != nil {
			if errors.Is(err, room.ErrNotOwner) || errors.Is(err, room.ErrWrongPassword) {
				return nil, ErrPlaneOwnerRequired
			}
			return nil, err
		}
		if req.Password == "" {
			return nil, ErrPlanePasswordRequired
		}
		if _, err := s.rooms.VerifyAccess(roomCode, req.Password); err != nil {
			if errors.Is(err, room.ErrWrongPassword) {
				return nil, ErrPlanePasswordRequired
			}
			return nil, err
		}
		entryPassword = req.Password
	}

	senderName := truncateRunes(strings.TrimSpace(req.SenderName), MaxSenderNameRunes)
	if senderName == "" {
		senderName = "匿名旅人"
	}
	senderAvatar := truncateRunes(strings.TrimSpace(req.SenderAvatar), 32)

	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	s.purgeExpiredLocked(now)

	if lastAt, ok := s.lastCreated[clientID]; ok && now.Sub(lastAt) < CreateCooldown {
		return nil, ErrPlaneCooldown
	}

	senderActive := 0
	for _, p := range s.planes {
		if p.SenderID == clientID {
			senderActive++
		}
	}
	if senderActive >= MaxPlanesPerSender {
		return nil, ErrPlaneLimitSender
	}
	if len(s.planes) >= MaxGlobalPlanes {
		return nil, ErrPlaneLimitGlobal
	}

	id := "pln_" + randomHex(6)
	nowMs := now.UnixMilli()
	expMs := now.Add(PlaneTTL).UnixMilli()
	stored := &Plane{
		ID:            id,
		RoomCode:      roomCode,
		SenderName:    senderName,
		SenderAvatar:  senderAvatar,
		SenderID:      clientID,
		Message:       msg,
		Files:         files,
		HasPassword:   summary.HasPassword,
		AllowEntry:    allowEntry,
		CreatedAt:     nowMs,
		ExpiresAt:     expMs,
		entryPassword: entryPassword,
		reporters:     make(map[string]struct{}),
	}
	s.planes[id] = stored
	s.lastCreated[clientID] = now

	return clonePublicPlane(stored, summary.HasPassword), nil
}

// List 按时间倒序返回当前有效纸飞机，同时自动清理对应房间已销毁的条目
func (s *Service) List(limit int) []Plane {
	if limit <= 0 {
		limit = DefaultListLimit
	} else if limit > MaxListLimit {
		limit = MaxListLimit
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	s.purgeExpiredLocked(now)

	candidates := make([]*Plane, 0, len(s.planes))
	for _, p := range s.planes {
		candidates = append(candidates, p)
	}
	sort.Slice(candidates, func(i, j int) bool {
		if candidates[i].CreatedAt == candidates[j].CreatedAt {
			return candidates[i].ID > candidates[j].ID
		}
		return candidates[i].CreatedAt > candidates[j].CreatedAt
	})

	result := make([]Plane, 0, minInt(limit, len(candidates)))
	for _, p := range candidates {
		summary, err := s.rooms.GetRoomInfo(p.RoomCode)
		if err != nil {
			delete(s.planes, p.ID)
			continue
		}
		p.HasPassword = summary.HasPassword
		result = append(result, *clonePublicPlane(p, summary.HasPassword))
		if len(result) >= limit {
			break
		}
	}
	return result
}

// Get 获取单架纸飞机公开详情
func (s *Service) Get(id string) (*Plane, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return nil, ErrPlaneNotFound
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	s.purgeExpiredLocked(now)

	p, ok := s.planes[id]
	if !ok {
		return nil, ErrPlaneNotFound
	}
	summary, err := s.rooms.GetRoomInfo(p.RoomCode)
	if err != nil {
		delete(s.planes, p.ID)
		return nil, ErrPlaneExpired
	}
	p.HasPassword = summary.HasPassword
	return clonePublicPlane(p, summary.HasPassword), nil
}

// Join 校验纸飞机直通加入权限并返回房间入场信息（含加密房免密口令）
func (s *Service) Join(id string) (*JoinResult, error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return nil, ErrPlaneNotFound
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	s.purgeExpiredLocked(now)

	p, ok := s.planes[id]
	if !ok {
		return nil, ErrPlaneNotFound
	}
	summary, err := s.rooms.GetRoomInfo(p.RoomCode)
	if err != nil {
		delete(s.planes, p.ID)
		return nil, ErrPlaneExpired
	}
	p.HasPassword = summary.HasPassword

	if !p.AllowEntry {
		return nil, ErrPlaneEntryClosed
	}

	if !summary.HasPassword {
		return &JoinResult{
			RoomCode:    p.RoomCode,
			HasPassword: false,
		}, nil
	}

	if p.entryPassword == "" {
		p.AllowEntry = false
		return nil, ErrPlaneEntryExpired
	}
	if _, err := s.rooms.VerifyAccess(p.RoomCode, p.entryPassword); err != nil {
		if errors.Is(err, room.ErrWrongPassword) {
			// 房主改密后立即吊销旧纸飞机的免密通行资格
			p.AllowEntry = false
			p.entryPassword = ""
			return nil, ErrPlaneEntryExpired
		}
		delete(s.planes, p.ID)
		return nil, ErrPlaneExpired
	}

	return &JoinResult{
		RoomCode:    p.RoomCode,
		HasPassword: true,
		Password:    p.entryPassword,
	}, nil
}

// Report 举报不当纸飞机，累计达到 3 个独立客户端后自动删除
func (s *Service) Report(id, reporterID string) (ReportResult, error) {
	id = strings.TrimSpace(id)
	reporterID = strings.TrimSpace(reporterID)
	if id == "" {
		return ReportResult{}, ErrPlaneNotFound
	}
	if reporterID == "" || len(reporterID) > 64 {
		return ReportResult{}, errors.New("缺少有效的客户端标识")
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	s.purgeExpiredLocked(now)

	p, ok := s.planes[id]
	if !ok {
		return ReportResult{}, ErrPlaneNotFound
	}
	if p.SenderID == reporterID {
		return ReportResult{}, ErrPlaneSelfReport
	}
	if _, exists := p.reporters[reporterID]; exists {
		return ReportResult{}, ErrPlaneAlreadyReported
	}
	p.reporters[reporterID] = struct{}{}
	count := len(p.reporters)
	if count >= ReportThreshold {
		delete(s.planes, id)
		return ReportResult{Deleted: true, ReportCount: count}, nil
	}
	return ReportResult{Deleted: false, ReportCount: count}, nil
}

func clonePublicPlane(p *Plane, hasPassword bool) *Plane {
	filesCopy := make([]FileMeta, len(p.Files))
	copy(filesCopy, p.Files)
	return &Plane{
		ID:           p.ID,
		RoomCode:     p.RoomCode,
		SenderName:   p.SenderName,
		SenderAvatar: p.SenderAvatar,
		Message:      p.Message,
		Files:        filesCopy,
		HasPassword:  hasPassword,
		AllowEntry:   p.AllowEntry,
		CreatedAt:    p.CreatedAt,
		ExpiresAt:    p.ExpiresAt,
	}
}

func sanitizeFiles(raw []FileMeta) []FileMeta {
	if len(raw) == 0 {
		return []FileMeta{}
	}
	out := make([]FileMeta, 0, minInt(len(raw), MaxFiles))
	for _, item := range raw {
		name := truncateRunes(strings.TrimSpace(item.Name), MaxFileNameRunes)
		if name == "" {
			continue
		}
		size := item.Size
		if size < 0 {
			size = 0
		}
		out = append(out, FileMeta{
			Name: name,
			Size: size,
			Kind: normalizeKind(item.Kind),
		})
		if len(out) >= MaxFiles {
			break
		}
	}
	return out
}

func normalizeKind(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "image", "video", "audio", "archive", "doc", "apk", "file":
		return strings.ToLower(strings.TrimSpace(raw))
	default:
		return "file"
	}
}

func truncateRunes(s string, maxRunes int) string {
	if maxRunes <= 0 || s == "" {
		return ""
	}
	if utf8.RuneCountInString(s) <= maxRunes {
		return s
	}
	runes := []rune(s)
	return string(runes[:maxRunes])
}

func randomHex(numBytes int) string {
	b := make([]byte, numBytes)
	if _, err := rand.Read(b); err != nil {
		return hex.EncodeToString([]byte(time.Now().Format("150405.000")))
	}
	return hex.EncodeToString(b)
}

func minInt(a, b int) int {
	if a < b {
		return a
	}
	return b
}
