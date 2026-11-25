package room

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"io"
	"log"
	"math/big"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"baychat/server/internal/config"
	"github.com/gorilla/websocket"
)

const (
	writeWait              = 10 * time.Second
	pongWait               = 45 * time.Second
	pingPeriod             = 20 * time.Second
	maxWSMessageSize       = 512 * 1024 // 512KB（信令、自定义头像及聊天缓存消息足够）
	ephemeralIdleGrace     = 12 * time.Second
	ephemeralInitialWindow = 45 * time.Second
	burnedCodeRetention    = 24 * time.Hour
)

// PeerProfile 表示房间内成员的自定义昵称与头像信息
type PeerProfile struct {
	PeerID   string `json:"peerId"`
	ClientID string `json:"clientId,omitempty"` // 稳定设备/浏览器标识，用于持久化屏蔽列表
	Name     string `json:"name"`
	Avatar   string `json:"avatar"` // 支持预设头像标识或轻量 base64 DataURL
	JoinedAt int64  `json:"joinedAt"`
}

// RoomSummary 是对外返回的房间公开摘要（不含密码原文或哈希）
type RoomSummary struct {
	Code          string      `json:"code"`
	Mode          string      `json:"mode"` // fixed | ephemeral
	HasPassword   bool        `json:"hasPassword"`
	HasOwner      bool        `json:"hasOwner"`             // 是否绑定房主令牌（新房间均为 true）
	OwnerToken    string      `json:"ownerToken,omitempty"` // 仅在创建房间时返回一次，用于房主管理鉴权
	AllowRandom   bool        `json:"allowRandom"`          // 是否允许陌生人随机进入（仅无密码房可能为 true）
	Topic         string      `json:"topic,omitempty"`      // 房间主题标签
	MaxPeers      int         `json:"maxPeers"`
	PeerCount     int         `json:"peerCount"`
	RetentionDays int         `json:"retentionDays"`
	MaxFileSizeMB int         `json:"maxFileSizeMB"` // 单附件发送大小上限（MB），0 表示不限制
	Stats         RoomStats   `json:"stats"`         // 基地成长火花与互动统计
	CreatedAt     int64       `json:"createdAt"`
	LastActiveAt  int64       `json:"lastActiveAt"`
	History       HistoryMeta `json:"history"`
}

// CreateRoomRequest 创建房间请求体
type CreateRoomRequest struct {
	Mode          string `json:"mode"`                    // fixed | ephemeral
	Code          string `json:"code,omitempty"`          // 仅 fixed 模式可自定义；留空自动生成
	Password      string `json:"password,omitempty"`      // 可选访问密码
	AllowRandom   *bool  `json:"allowRandom,omitempty"`   // 是否允许陌生人随机串门（无密码时默认 true；有密码时强制 false）
	Topic         string `json:"topic,omitempty"`         // 可选房间主题标签（最多 24 字）
	MaxPeers      int    `json:"maxPeers,omitempty"`      // 2~MAX_ROOM_PEERS
	RetentionDays *int   `json:"retentionDays,omitempty"` // 0~7 天，未传时默认 3 天
	MaxFileSizeMB *int   `json:"maxFileSizeMB,omitempty"` // 单附件大小上限（MB），0 表示不限制
}

type peerConn struct {
	id      string
	isOwner bool
	room    *Room
	profile PeerProfile
	conn    *websocket.Conn
	send    chan []byte
	sendMu  sync.Mutex
	closed  bool
}

// enqueue 线程安全地向客户端发送队列投递消息，彻底杜绝 send on closed channel panic
func (p *peerConn) enqueue(raw []byte) bool {
	p.sendMu.Lock()
	defer p.sendMu.Unlock()
	if p.closed {
		return false
	}
	select {
	case p.send <- raw:
		return true
	default:
		return false
	}
}

func (p *peerConn) closeSend() {
	p.sendMu.Lock()
	defer p.sendMu.Unlock()
	if p.closed {
		return
	}
	p.closed = true
	close(p.send)
}

// Room 表示一个运行中的房间实例
type Room struct {
	Code           string
	Mode           string
	PasswordHash   string
	OwnerTokenHash string
	AllowRandom    bool
	Topic          string
	MaxPeers       int
	RetentionDays  int
	MaxFileSizeMB  int
	Stats          RoomStats
	CreatedAt      int64
	LastActiveAt   int64
	EverHadPeer    bool
	peers          map[string]*peerConn
	emptyBurnTimer *time.Timer
}

// SignalEnvelope 为 WebSocket 双向通信统一协议信封
type SignalEnvelope struct {
	Type          string          `json:"type"`
	RoomCode      string          `json:"roomCode,omitempty"`
	PeerID        string          `json:"peerId,omitempty"`
	TargetPeerID  string          `json:"targetPeerId,omitempty"`
	FromPeerID    string          `json:"fromPeerId,omitempty"`
	Password      string          `json:"password,omitempty"`
	OwnerToken    string          `json:"ownerToken,omitempty"`
	IsOwner       *bool           `json:"isOwner,omitempty"`
	HasPassword   *bool           `json:"hasPassword,omitempty"`
	Profile       *PeerProfile    `json:"profile,omitempty"`
	Peers         []PeerProfile   `json:"peers,omitempty"`
	Room          *RoomSummary    `json:"room,omitempty"`
	Payload       json.RawMessage `json:"payload,omitempty"`
	Message       *CachedMessage  `json:"message,omitempty"`
	Messages      []CachedMessage `json:"messages,omitempty"`
	MessageID     string          `json:"messageId,omitempty"`
	History       *HistoryMeta    `json:"history,omitempty"`
	Stats         *RoomStats      `json:"stats,omitempty"`
	ActivityKind  string          `json:"activityKind,omitempty"`
	ActivityBytes int64           `json:"activityBytes,omitempty"`
	RetentionDays *int            `json:"retentionDays,omitempty"`
	MaxFileSizeMB *int            `json:"maxFileSizeMB,omitempty"`
	AllowRandom   *bool           `json:"allowRandom,omitempty"`
	Topic         *string         `json:"topic,omitempty"`
	Reason        string          `json:"reason,omitempty"`
	ErrorCode     string          `json:"errorCode,omitempty"`
	Error         string          `json:"error,omitempty"`
	ActorName     string          `json:"actorName,omitempty"`
}

// Hub 统一协调房间生命周期、WebSocket 信令路由以及服务器聊天缓存
type Hub struct {
	mu           sync.RWMutex
	cfg          *config.Config
	fixedStore   *FixedStore
	historyStore *HistoryStore
	rooms        map[string]*Room
	burnedCodes  map[string]time.Time // 已销毁的一次性房间码记录，避免短时间误重用
	upgrader     websocket.Upgrader
	nowFunc      func() time.Time
	stopCh       chan struct{}
}

// NewHub 创建房间与信令管理中心
func NewHub(cfg *config.Config) (*Hub, error) {
	fixedStore, err := NewFixedStore(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	historyStore, err := NewHistoryStore(cfg.DataDir, cfg.HistoryMaxMessages, cfg.HistoryMaxMediaBytes)
	if err != nil {
		return nil, err
	}

	h := &Hub{
		cfg:          cfg,
		fixedStore:   fixedStore,
		historyStore: historyStore,
		rooms:        make(map[string]*Room),
		burnedCodes:  make(map[string]time.Time),
		nowFunc:      time.Now,
		stopCh:       make(chan struct{}),
		upgrader: websocket.Upgrader{
			ReadBufferSize:  4096,
			WriteBufferSize: 4096,
			CheckOrigin: func(r *http.Request) bool {
				if cfg.AllowedOrigin == "" {
					return true
				}
				return strings.EqualFold(r.Header.Get("Origin"), cfg.AllowedOrigin)
			},
		},
	}

	// 恢复磁盘中所有持久化固定房间
	fixedRecords, err := fixedStore.LoadAll()
	if err != nil {
		return nil, err
	}
	for code, rec := range fixedRecords {
		maxFileMB := cfg.DefaultRoomMaxFileMB
		if rec.MaxFileSizeMB != nil {
			maxFileMB = *rec.MaxFileSizeMB
		}
		if cfg.MaxAttachmentMB > 0 && (maxFileMB == 0 || maxFileMB > cfg.MaxAttachmentMB) {
			maxFileMB = cfg.MaxAttachmentMB
		}
		allowRandom := rec.PasswordHash == "" && (rec.AllowRandom == nil || *rec.AllowRandom)
		h.rooms[code] = &Room{
			Code:           rec.Code,
			Mode:           ModeFixed,
			PasswordHash:   rec.PasswordHash,
			OwnerTokenHash: rec.OwnerTokenHash,
			AllowRandom:    allowRandom,
			Topic:          truncateRunes(strings.TrimSpace(rec.Topic), 24),
			MaxPeers:       clampPeerLimit(rec.MaxPeers, cfg.MaxRoomPeers),
			RetentionDays:  rec.RetentionDays,
			MaxFileSizeMB:  maxFileMB,
			Stats:          rec.Stats,
			CreatedAt:      rec.CreatedAt,
			LastActiveAt:   rec.LastActiveAt,
			peers:          make(map[string]*peerConn),
		}
	}

	// 启动时清理长期无人活跃的常驻房间
	h.PurgeIdleFixedRooms(h.now())

	go h.maintenanceLoop()
	return h, nil
}

// SetNowFunc 仅供单元测试注入可控时钟
func (h *Hub) SetNowFunc(fn func() time.Time) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.nowFunc = fn
}

func (h *Hub) now() time.Time {
	if h.nowFunc != nil {
		return h.nowFunc()
	}
	return time.Now()
}

// Close 停止后台巡检任务
func (h *Hub) Close() {
	select {
	case <-h.stopCh:
	default:
		close(h.stopCh)
	}
}

// History 返回底层聊天缓存管理器（供测试或媒体上传接口使用）
func (h *Hub) History() *HistoryStore {
	return h.historyStore
}

func (h *Hub) maintenanceLoop() {
	ticker := time.NewTicker(1 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-h.stopCh:
			return
		case <-ticker.C:
			h.historyStore.PurgeExpired()
			h.purgeOldBurnedCodes()
			h.PurgeIdleFixedRooms(h.now())
		}
	}
}

// PurgeIdleFixedRooms 回收无人在线且超过指定空闲天数的常驻房间
func (h *Hub) PurgeIdleFixedRooms(now time.Time) int {
	idleDays := h.cfg.FixedRoomIdleDays
	if idleDays <= 0 {
		idleDays = 60
	}
	cutoffMs := now.UnixMilli() - int64(idleDays)*int64(24*time.Hour/time.Millisecond)

	h.mu.Lock()
	defer h.mu.Unlock()

	var toRemove []*Room
	for _, rm := range h.rooms {
		if rm.Mode != ModeFixed || len(rm.peers) > 0 {
			continue
		}
		if rm.LastActiveAt > 0 && rm.LastActiveAt < cutoffMs {
			toRemove = append(toRemove, rm)
		}
	}
	if len(toRemove) == 0 {
		return 0
	}
	for _, rm := range toRemove {
		delete(h.rooms, rm.Code)
		h.historyStore.ClearRoom(rm.Code, 0)
	}
	_ = h.persistFixedRoomsLocked()
	return len(toRemove)
}

func (h *Hub) purgeOldBurnedCodes() {
	h.mu.Lock()
	defer h.mu.Unlock()
	cutoff := h.now().Add(-burnedCodeRetention)
	for code, burnedAt := range h.burnedCodes {
		if burnedAt.Before(cutoff) {
			delete(h.burnedCodes, code)
		}
	}
}

func clampPeerLimit(requested, maxAllowed int) int {
	if maxAllowed < 2 {
		maxAllowed = 6
	}
	if requested < 2 {
		return maxAllowed
	}
	if requested > maxAllowed {
		return maxAllowed
	}
	return requested
}

// CreateRoom 创建固定房间或一次性房间
func (h *Hub) CreateRoom(req CreateRoomRequest) (*RoomSummary, error) {
	mode := strings.ToLower(strings.TrimSpace(req.Mode))
	if mode == "" {
		mode = ModeEphemeral
	}
	if mode != ModeFixed && mode != ModeEphemeral {
		return nil, errors.New("房间模式必须为 fixed 或 ephemeral")
	}

	// 聊天记录缓存默认保留 3 天（固定房间）或 1 天（一次性房间，销毁时自动清空），上限严格不超过 7 天
	retentionDays := 3
	if mode == ModeEphemeral {
		retentionDays = 1
	}
	if req.RetentionDays != nil {
		retentionDays = *req.RetentionDays
	}
	if err := ValidateRetentionDays(retentionDays); err != nil {
		return nil, err
	}

	maxFileSizeMB := h.cfg.DefaultRoomMaxFileMB
	if req.MaxFileSizeMB != nil {
		maxFileSizeMB = *req.MaxFileSizeMB
	}
	if err := ValidateMaxFileSizeMB(maxFileSizeMB, h.cfg.MaxAttachmentMB); err != nil {
		return nil, err
	}

	maxPeers := clampPeerLimit(req.MaxPeers, h.cfg.MaxRoomPeers)

	h.mu.Lock()
	defer h.mu.Unlock()

	now := h.now()
	nowMs := now.UnixMilli()
	initStats := RoomStats{}
	initStats.TouchActivity(now)

	var code string
	if mode == ModeFixed {
		if strings.TrimSpace(req.Code) != "" {
			code = NormalizeCode(req.Code)
			if err := ValidateCode(code); err != nil {
				return nil, err
			}
			// 6 位纯数字保留给临时房间，常驻房间禁止使用
			if IsReservedSixDigitCode(code) {
				return nil, ErrCodeReserved
			}
			if _, exists := h.rooms[code]; exists {
				return nil, ErrRoomExists
			}
		} else {
			code = h.allocateUniqueFixedCodeLocked()
		}
	} else {
		code = h.allocateUniqueCodeLocked()
	}

	passwordHash := HashPassword(req.Password)
	ownerToken := GenerateOwnerToken()
	ownerTokenHash := HashOwnerToken(ownerToken)
	// 无密码房间默认允许陌生人随机串门，用户可手动关闭；有密码房间严格禁止随机进入
	allowRandom := passwordHash == ""
	if passwordHash == "" && req.AllowRandom != nil {
		allowRandom = *req.AllowRandom
	}
	topic := truncateRunes(strings.TrimSpace(req.Topic), 24)

	rm := &Room{
		Code:           code,
		Mode:           mode,
		PasswordHash:   passwordHash,
		OwnerTokenHash: ownerTokenHash,
		AllowRandom:    allowRandom,
		Topic:          topic,
		MaxPeers:       maxPeers,
		RetentionDays:  retentionDays,
		MaxFileSizeMB:  maxFileSizeMB,
		Stats:          initStats,
		CreatedAt:      nowMs,
		LastActiveAt:   nowMs,
		peers:          make(map[string]*peerConn),
	}

	// 一次性房间若创建后长时间无人连入，自动回收
	if mode == ModeEphemeral {
		rm.emptyBurnTimer = time.AfterFunc(ephemeralInitialWindow, func() {
			h.burnEphemeralIfEmpty(code)
		})
	}

	h.rooms[code] = rm
	delete(h.burnedCodes, code)

	if mode == ModeFixed {
		if err := h.persistFixedRoomsLocked(); err != nil {
			delete(h.rooms, code)
			return nil, err
		}
	}

	summary := h.buildRoomSummaryLocked(rm)
	summary.OwnerToken = ownerToken
	return summary, nil
}

func (h *Hub) allocateUniqueFixedCodeLocked() string {
	for i := 0; i < 64; i++ {
		candidate := "bay-" + randomHex(3)
		if _, exists := h.rooms[candidate]; exists {
			continue
		}
		if _, burned := h.burnedCodes[candidate]; burned {
			continue
		}
		return candidate
	}
	return "bay-" + randomHex(4)
}

func (h *Hub) allocateUniqueCodeLocked() string {
	for i := 0; i < 64; i++ {
		candidate := GenerateSixDigitCode()
		if _, exists := h.rooms[candidate]; exists {
			continue
		}
		if _, burned := h.burnedCodes[candidate]; burned {
			continue
		}
		return candidate
	}
	return randomHex(4)
}

func (h *Hub) persistFixedRoomsLocked() error {
	records := make(map[string]*FixedRoomRecord)
	for code, rm := range h.rooms {
		if rm.Mode != ModeFixed {
			continue
		}
		maxFileMB := rm.MaxFileSizeMB
		allowRandom := rm.PasswordHash == "" && rm.AllowRandom
		records[code] = &FixedRoomRecord{
			Code:           rm.Code,
			Mode:           ModeFixed,
			PasswordHash:   rm.PasswordHash,
			OwnerTokenHash: rm.OwnerTokenHash,
			MaxPeers:       rm.MaxPeers,
			RetentionDays:  rm.RetentionDays,
			MaxFileSizeMB:  &maxFileMB,
			AllowRandom:    &allowRandom,
			Topic:          rm.Topic,
			Stats:          rm.Stats,
			CreatedAt:      rm.CreatedAt,
			LastActiveAt:   rm.LastActiveAt,
		}
	}
	return h.fixedStore.SaveAll(records)
}

func (h *Hub) buildRoomSummaryLocked(rm *Room) *RoomSummary {
	_, histMeta := h.historyStore.GetHistory(rm.Code, rm.RetentionDays)
	histMeta.RetentionDays = rm.RetentionDays
	return &RoomSummary{
		Code:          rm.Code,
		Mode:          rm.Mode,
		HasPassword:   rm.PasswordHash != "",
		HasOwner:      rm.OwnerTokenHash != "",
		AllowRandom:   rm.PasswordHash == "" && rm.AllowRandom,
		Topic:         rm.Topic,
		MaxPeers:      rm.MaxPeers,
		PeerCount:     len(rm.peers),
		RetentionDays: rm.RetentionDays,
		MaxFileSizeMB: rm.MaxFileSizeMB,
		Stats:         rm.Stats,
		CreatedAt:     rm.CreatedAt,
		LastActiveAt:  rm.LastActiveAt,
		History:       histMeta,
	}
}

// ListPublicRooms 返回当前可随机偶遇的免密公开房间列表（严格过滤无密码、开启允许随机且未满员的房间）
func (h *Hub) ListPublicRooms() []RoomSummary {
	h.mu.RLock()
	defer h.mu.RUnlock()

	active := make([]RoomSummary, 0)
	waitingEphemeral := make([]RoomSummary, 0)
	for _, rm := range h.rooms {
		if rm.PasswordHash != "" || !rm.AllowRandom || len(rm.peers) >= rm.MaxPeers {
			continue
		}
		if len(rm.peers) > 0 {
			active = append(active, *h.buildRoomSummaryLocked(rm))
		} else if rm.Mode == ModeEphemeral {
			waitingEphemeral = append(waitingEphemeral, *h.buildRoomSummaryLocked(rm))
		}
	}

	sort.Slice(active, func(i, j int) bool {
		if active[i].PeerCount != active[j].PeerCount {
			return active[i].PeerCount > active[j].PeerCount
		}
		return active[i].LastActiveAt > active[j].LastActiveAt
	})
	sort.Slice(waitingEphemeral, func(i, j int) bool {
		return waitingEphemeral[i].CreatedAt > waitingEphemeral[j].CreatedAt
	})

	combined := append(active, waitingEphemeral...)
	if len(combined) > 30 {
		combined = combined[:30]
	}
	return combined
}

// PickRandomPublicRoom 随机抽取一间可进入的免密公开房间（支持 excludeCode 实现房内「换一间」）
func (h *Hub) PickRandomPublicRoom(excludeCode string) (*RoomSummary, error) {
	exclude := NormalizeCode(excludeCode)

	h.mu.RLock()
	defer h.mu.RUnlock()

	tier1 := make([]*Room, 0) // 优先：已有伙伴在线的免密公开房
	tier2 := make([]*Room, 0) // 兜底：刚创建等待加入的一次性免密公开房
	for _, rm := range h.rooms {
		if rm.PasswordHash != "" || !rm.AllowRandom || len(rm.peers) >= rm.MaxPeers {
			continue
		}
		if exclude != "" && rm.Code == exclude {
			continue
		}
		if len(rm.peers) > 0 {
			tier1 = append(tier1, rm)
		} else if rm.Mode == ModeEphemeral {
			tier2 = append(tier2, rm)
		}
	}

	pool := tier1
	if len(pool) == 0 {
		pool = tier2
	}
	if len(pool) == 0 {
		return nil, ErrNoPublicRoom
	}

	idx := 0
	if len(pool) > 1 {
		if n, err := rand.Int(rand.Reader, big.NewInt(int64(len(pool)))); err == nil {
			idx = int(n.Int64())
		}
	}
	return h.buildRoomSummaryLocked(pool[idx]), nil
}

// authorizeOwnerOrLegacyLocked 校验房主令牌（新房间强制校验 ownerToken；老房间无 ownerTokenHash 时回退校验房间密码）
func authorizeOwnerOrLegacyLocked(rm *Room, ownerToken, password string) error {
	if rm.OwnerTokenHash != "" {
		if !VerifyOwnerToken(rm.OwnerTokenHash, ownerToken) {
			return ErrNotOwner
		}
		return nil
	}
	if !VerifyPassword(rm.PasswordHash, password) {
		return ErrWrongPassword
	}
	return nil
}

// UpdateAllowRandom 动态开启或关闭房间的「允许陌生人随机串门」开关（仅免密房支持开启）
func (h *Hub) UpdateAllowRandom(rawCode, ownerToken, password string, allowRandom bool, actorName string) (bool, error) {
	code := NormalizeCode(rawCode)

	h.mu.Lock()
	rm, ok := h.rooms[code]
	if !ok {
		h.mu.Unlock()
		if _, burned := h.burnedCodes[code]; burned {
			return false, ErrRoomExpired
		}
		return false, ErrRoomNotFound
	}
	if err := authorizeOwnerOrLegacyLocked(rm, ownerToken, password); err != nil {
		h.mu.Unlock()
		return false, err
	}

	// 有密码房间严格禁止被随机进入
	effective := rm.PasswordHash == "" && allowRandom
	rm.AllowRandom = effective
	rm.LastActiveAt = h.now().UnixMilli()
	if rm.Mode == ModeFixed {
		_ = h.persistFixedRoomsLocked()
	}

	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	h.mu.Unlock()

	val := effective
	h.broadcastToPeers(peers, SignalEnvelope{
		Type:        "room_settings_updated",
		RoomCode:    code,
		AllowRandom: &val,
		ActorName:   actorName,
	})
	return effective, nil
}

// UpdateRoomProfile 由房主更新房间密码与主题（设置密码后强制关闭 allowRandom 并广播）
func (h *Hub) UpdateRoomProfile(rawCode, ownerToken, password string, newPassword *string, newTopic *string, actorName string) (*RoomSummary, error) {
	code := NormalizeCode(rawCode)

	h.mu.Lock()
	rm, ok := h.rooms[code]
	if !ok {
		h.mu.Unlock()
		if _, burned := h.burnedCodes[code]; burned {
			return nil, ErrRoomExpired
		}
		return nil, ErrRoomNotFound
	}
	if err := authorizeOwnerOrLegacyLocked(rm, ownerToken, password); err != nil {
		h.mu.Unlock()
		return nil, err
	}

	if newPassword != nil {
		trimmed := strings.TrimSpace(*newPassword)
		if trimmed == "" {
			rm.PasswordHash = ""
		} else {
			rm.PasswordHash = HashPassword(trimmed)
			rm.AllowRandom = false
		}
	}
	if newTopic != nil {
		rm.Topic = truncateRunes(strings.TrimSpace(*newTopic), 24)
	}
	rm.LastActiveAt = h.now().UnixMilli()
	if rm.Mode == ModeFixed {
		_ = h.persistFixedRoomsLocked()
	}

	summary := h.buildRoomSummaryLocked(rm)
	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	hasPwd := rm.PasswordHash != ""
	allowRand := rm.PasswordHash == "" && rm.AllowRandom
	topicCopy := rm.Topic
	h.mu.Unlock()

	h.broadcastToPeers(peers, SignalEnvelope{
		Type:        "room_settings_updated",
		RoomCode:    code,
		AllowRandom: &allowRand,
		HasPassword: &hasPwd,
		Topic:       &topicCopy,
		ActorName:   actorName,
	})
	return summary, nil
}

// GetRoomInfo 查询房间摘要信息（区分不存在与一次性房间已销毁）
func (h *Hub) GetRoomInfo(rawCode string) (*RoomSummary, error) {
	code := NormalizeCode(rawCode)
	h.mu.RLock()
	defer h.mu.RUnlock()

	if rm, ok := h.rooms[code]; ok {
		return h.buildRoomSummaryLocked(rm), nil
	}
	if _, burned := h.burnedCodes[code]; burned {
		return nil, ErrRoomExpired
	}
	return nil, ErrRoomNotFound
}

// VerifyAccess 校验房间存在性及可选密码
func (h *Hub) VerifyAccess(rawCode, password string) (*RoomSummary, error) {
	code := NormalizeCode(rawCode)
	h.mu.RLock()
	defer h.mu.RUnlock()

	rm, ok := h.rooms[code]
	if !ok {
		if _, burned := h.burnedCodes[code]; burned {
			return nil, ErrRoomExpired
		}
		return nil, ErrRoomNotFound
	}
	if !VerifyPassword(rm.PasswordHash, password) {
		return nil, ErrWrongPassword
	}
	return h.buildRoomSummaryLocked(rm), nil
}

// DeleteRoom 销毁房间（一次性房间直接作废，固定房间需校验房主或旧版密码后从持久化删除；同时立即清空服务器聊天缓存）
func (h *Hub) DeleteRoom(rawCode, ownerToken, password, actorName string) error {
	code := NormalizeCode(rawCode)
	h.mu.Lock()
	rm, ok := h.rooms[code]
	if !ok {
		h.mu.Unlock()
		if _, burned := h.burnedCodes[code]; burned {
			return ErrRoomExpired
		}
		return ErrRoomNotFound
	}
	if err := authorizeOwnerOrLegacyLocked(rm, ownerToken, password); err != nil {
		h.mu.Unlock()
		return err
	}

	reason := "房间已被销毁，聊天记录已立即清空"
	if rm.Mode == ModeEphemeral {
		reason = "聊天已结束，该一次性房间码已销毁失效"
	}
	if actorName != "" {
		reason = actorName + " 结束了聊天并销毁了房间"
	}
	peersToNotify := h.destroyRoomLocked(rm)
	h.mu.Unlock()

	h.notifyDestroyedPeers(peersToNotify, code, reason)
	return nil
}

// UpdateRetention 更新房间聊天记录服务器缓存天数（0~7 天），并实时广播给所有成员
func (h *Hub) UpdateRetention(rawCode, ownerToken, password string, retentionDays int, actorName string) (HistoryMeta, bool, error) {
	if err := ValidateRetentionDays(retentionDays); err != nil {
		return HistoryMeta{}, false, err
	}
	code := NormalizeCode(rawCode)

	h.mu.Lock()
	rm, ok := h.rooms[code]
	if !ok {
		h.mu.Unlock()
		if _, burned := h.burnedCodes[code]; burned {
			return HistoryMeta{}, false, ErrRoomExpired
		}
		return HistoryMeta{}, false, ErrRoomNotFound
	}
	if err := authorizeOwnerOrLegacyLocked(rm, ownerToken, password); err != nil {
		h.mu.Unlock()
		return HistoryMeta{}, false, err
	}

	rm.RetentionDays = retentionDays
	rm.LastActiveAt = h.now().UnixMilli()
	if rm.Mode == ModeFixed {
		_ = h.persistFixedRoomsLocked()
	}

	meta, cleared, err := h.historyStore.SetRetention(code, retentionDays)
	if err != nil {
		h.mu.Unlock()
		return HistoryMeta{}, false, err
	}
	meta.RetentionDays = retentionDays

	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	h.mu.Unlock()

	// 广播保留策略变更（若切换为 0 立即销毁，也同步触发清空事件）
	rd := retentionDays
	h.broadcastToPeers(peers, SignalEnvelope{
		Type:          "retention_updated",
		RoomCode:      code,
		RetentionDays: &rd,
		History:       &meta,
		ActorName:     actorName,
	})
	if cleared {
		h.broadcastToPeers(peers, SignalEnvelope{
			Type:      "history_cleared",
			RoomCode:  code,
			History:   &meta,
			ActorName: actorName,
			Reason:    "聊天记录已立即删除",
		})
	}
	return meta, cleared, nil
}

// UpdateMaxFileSize 动态修改房间单文件附件大小上限（MB，0 表示不限制），并实时广播全员
func (h *Hub) UpdateMaxFileSize(rawCode, ownerToken, password string, maxFileSizeMB int, actorName string) (int, error) {
	if err := ValidateMaxFileSizeMB(maxFileSizeMB, h.cfg.MaxAttachmentMB); err != nil {
		return 0, err
	}
	code := NormalizeCode(rawCode)

	h.mu.Lock()
	rm, ok := h.rooms[code]
	if !ok {
		h.mu.Unlock()
		if _, burned := h.burnedCodes[code]; burned {
			return 0, ErrRoomExpired
		}
		return 0, ErrRoomNotFound
	}
	if err := authorizeOwnerOrLegacyLocked(rm, ownerToken, password); err != nil {
		h.mu.Unlock()
		return 0, err
	}

	rm.MaxFileSizeMB = maxFileSizeMB
	rm.LastActiveAt = h.now().UnixMilli()
	if rm.Mode == ModeFixed {
		_ = h.persistFixedRoomsLocked()
	}

	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	h.mu.Unlock()

	val := maxFileSizeMB
	h.broadcastToPeers(peers, SignalEnvelope{
		Type:          "room_settings_updated",
		RoomCode:      code,
		MaxFileSizeMB: &val,
		ActorName:     actorName,
	})
	return maxFileSizeMB, nil
}

// ClearHistory 立即删除指定房间的全部服务器缓存聊天记录与缓存图片，并实时广播清空房间内所有在线终端
func (h *Hub) ClearHistory(rawCode, ownerToken, password, actorName string) (HistoryMeta, error) {
	code := NormalizeCode(rawCode)

	h.mu.Lock()
	rm, ok := h.rooms[code]
	if !ok {
		h.mu.Unlock()
		if _, burned := h.burnedCodes[code]; burned {
			return HistoryMeta{}, ErrRoomExpired
		}
		return HistoryMeta{}, ErrRoomNotFound
	}
	if err := authorizeOwnerOrLegacyLocked(rm, ownerToken, password); err != nil {
		h.mu.Unlock()
		return HistoryMeta{}, err
	}

	meta := h.historyStore.ClearRoom(code, rm.RetentionDays)
	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	h.mu.Unlock()

	h.broadcastToPeers(peers, SignalEnvelope{
		Type:      "history_cleared",
		RoomCode:  code,
		History:   &meta,
		ActorName: actorName,
		Reason:    "聊天记录已从服务器缓存立即删除",
	})
	return meta, nil
}

// GetHistory 查询房间缓存聊天记录（需校验房间密码）
func (h *Hub) GetHistory(rawCode, password string) ([]CachedMessage, HistoryMeta, error) {
	code := NormalizeCode(rawCode)
	h.mu.RLock()
	rm, ok := h.rooms[code]
	if !ok {
		_, burned := h.burnedCodes[code]
		h.mu.RUnlock()
		if burned {
			return nil, HistoryMeta{}, ErrRoomExpired
		}
		return nil, HistoryMeta{}, ErrRoomNotFound
	}
	if !VerifyPassword(rm.PasswordHash, password) {
		h.mu.RUnlock()
		return nil, HistoryMeta{}, ErrWrongPassword
	}
	retentionDays := rm.RetentionDays
	h.mu.RUnlock()

	msgs, meta := h.historyStore.GetHistory(code, retentionDays)
	meta.RetentionDays = retentionDays
	return msgs, meta, nil
}

// SaveRoomMedia 保存聊天图片/语音到房间服务器缓存
func (h *Hub) SaveRoomMedia(rawCode, password, mimeType string, content []byte) (string, string, error) {
	code := NormalizeCode(rawCode)
	h.mu.RLock()
	rm, ok := h.rooms[code]
	if !ok {
		_, burned := h.burnedCodes[code]
		h.mu.RUnlock()
		if burned {
			return "", "", ErrRoomExpired
		}
		return "", "", ErrRoomNotFound
	}
	if !VerifyPassword(rm.PasswordHash, password) {
		h.mu.RUnlock()
		return "", "", ErrWrongPassword
	}
	retentionDays := rm.RetentionDays
	h.mu.RUnlock()

	return h.historyStore.SaveMedia(code, retentionDays, mimeType, content)
}

// LoadRoomMedia 加载房间缓存的聊天图片
func (h *Hub) LoadRoomMedia(rawCode, password, mediaID string) ([]byte, string, error) {
	code := NormalizeCode(rawCode)
	h.mu.RLock()
	rm, ok := h.rooms[code]
	if !ok {
		_, burned := h.burnedCodes[code]
		h.mu.RUnlock()
		if burned {
			return nil, "", ErrRoomExpired
		}
		return nil, "", ErrRoomNotFound
	}
	if !VerifyPassword(rm.PasswordHash, password) {
		h.mu.RUnlock()
		return nil, "", ErrWrongPassword
	}
	h.mu.RUnlock()

	return h.historyStore.LoadMedia(code, mediaID)
}

// SaveRoomFile 流式保存普通历史附件至房间限时缓存（受 HistoryMaxFileBytes 限制）
func (h *Hub) SaveRoomFile(rawCode, password, fileName, mimeType string, body io.Reader) (string, CachedFileMeta, error) {
	code := NormalizeCode(rawCode)
	h.mu.RLock()
	rm, ok := h.rooms[code]
	if !ok {
		_, burned := h.burnedCodes[code]
		h.mu.RUnlock()
		if burned {
			return "", CachedFileMeta{}, ErrRoomExpired
		}
		return "", CachedFileMeta{}, ErrRoomNotFound
	}
	if !VerifyPassword(rm.PasswordHash, password) {
		h.mu.RUnlock()
		return "", CachedFileMeta{}, ErrWrongPassword
	}
	retentionDays := rm.RetentionDays
	h.mu.RUnlock()

	return h.historyStore.SaveCachedFile(code, retentionDays, fileName, mimeType, body, h.cfg.HistoryMaxFileBytes)
}

// OpenRoomFile 打开房间限时缓存的历史附件以支持断点下载
func (h *Hub) OpenRoomFile(rawCode, password, fileID string) (*os.File, CachedFileMeta, time.Time, error) {
	code := NormalizeCode(rawCode)
	h.mu.RLock()
	rm, ok := h.rooms[code]
	if !ok {
		_, burned := h.burnedCodes[code]
		h.mu.RUnlock()
		if burned {
			return nil, CachedFileMeta{}, time.Time{}, ErrRoomExpired
		}
		return nil, CachedFileMeta{}, time.Time{}, ErrRoomNotFound
	}
	if !VerifyPassword(rm.PasswordHash, password) {
		h.mu.RUnlock()
		return nil, CachedFileMeta{}, time.Time{}, ErrWrongPassword
	}
	h.mu.RUnlock()

	return h.historyStore.OpenCachedFile(code, fileID)
}

func (h *Hub) destroyRoomLocked(rm *Room) []*peerConn {
	if rm.emptyBurnTimer != nil {
		rm.emptyBurnTimer.Stop()
		rm.emptyBurnTimer = nil
	}
	delete(h.rooms, rm.Code)
	if rm.Mode == ModeEphemeral {
		h.burnedCodes[rm.Code] = time.Now()
	} else {
		_ = h.persistFixedRoomsLocked()
	}
	// 销毁房间时立即物理清除其服务器缓存的聊天记录与图片
	h.historyStore.ClearRoom(rm.Code, 0)

	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	rm.peers = make(map[string]*peerConn)
	return peers
}

func (h *Hub) notifyDestroyedPeers(peers []*peerConn, code, reason string) {
	env := SignalEnvelope{
		Type:     "room_destroyed",
		RoomCode: code,
		Reason:   reason,
	}
	raw, _ := json.Marshal(env)
	for _, p := range peers {
		p.enqueue(raw)
		p.closeSend()
	}
}

func (h *Hub) burnEphemeralIfEmpty(code string) {
	h.mu.Lock()
	defer h.mu.Unlock()

	rm, ok := h.rooms[code]
	if !ok || rm.Mode != ModeEphemeral {
		return
	}
	if len(rm.peers) > 0 {
		return
	}
	h.destroyRoomLocked(rm)
}

// ServeWS 处理客户端 WebSocket 信令连接
func (h *Hub) ServeWS(w http.ResponseWriter, r *http.Request) {
	conn, err := h.upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket 升级失败: %v", err)
		return
	}

	conn.SetReadLimit(maxWSMessageSize)
	_ = conn.SetReadDeadline(time.Now().Add(pongWait))
	conn.SetPongHandler(func(string) error {
		_ = conn.SetReadDeadline(time.Now().Add(pongWait))
		return nil
	})

	// 首条消息必须为 join
	_, firstRaw, err := conn.ReadMessage()
	if err != nil {
		_ = conn.Close()
		return
	}

	var joinReq SignalEnvelope
	if err := json.Unmarshal(firstRaw, &joinReq); err != nil || joinReq.Type != "join" {
		_ = sendDirectError(conn, "INVALID_JOIN", "首条信令必须为有效的 join 请求")
		_ = conn.Close()
		return
	}

	peer, joinedMsg, others, errCode, errMsg := h.attachPeer(conn, joinReq)
	if errMsg != "" {
		_ = sendDirectError(conn, errCode, errMsg)
		_ = conn.Close()
		return
	}

	// 启动写协程并发送 joined 确认（含房间信息、当前成员列表、服务器缓存的历史聊天记录）
	go h.writePump(peer)
	h.sendEnvelope(peer, joinedMsg)

	// 通知房间内已有成员有新成员加入（使用独立值拷贝，杜绝并发指针竞态）
	joinedProfile := *joinedMsg.Profile
	h.broadcastToPeers(others, SignalEnvelope{
		Type:     "peer_joined",
		RoomCode: joinedMsg.RoomCode,
		Profile:  &joinedProfile,
	})

	// 阻塞读取后续信令消息
	h.readPump(peer)
}

func sendDirectError(conn *websocket.Conn, code, msg string) error {
	raw, _ := json.Marshal(SignalEnvelope{
		Type:      "error",
		ErrorCode: code,
		Error:     msg,
	})
	_ = conn.SetWriteDeadline(time.Now().Add(writeWait))
	return conn.WriteMessage(websocket.TextMessage, raw)
}

func (h *Hub) attachPeer(conn *websocket.Conn, req SignalEnvelope) (*peerConn, SignalEnvelope, []*peerConn, string, string) {
	code := NormalizeCode(req.RoomCode)

	h.mu.Lock()
	defer h.mu.Unlock()

	rm, ok := h.rooms[code]
	if !ok {
		if _, burned := h.burnedCodes[code]; burned {
			return nil, SignalEnvelope{}, nil, "ROOM_EXPIRED", ErrRoomExpired.Error()
		}
		return nil, SignalEnvelope{}, nil, "ROOM_NOT_FOUND", ErrRoomNotFound.Error()
	}

	if !VerifyPassword(rm.PasswordHash, req.Password) {
		return nil, SignalEnvelope{}, nil, "WRONG_PASSWORD", ErrWrongPassword.Error()
	}

	peerID := strings.TrimSpace(req.PeerID)
	if peerID == "" || len(peerID) > 64 {
		peerID = "p_" + randomHex(8)
	}

	// 若同一 peerID 重连，先替换旧连接
	if old, exists := rm.peers[peerID]; exists {
		delete(rm.peers, peerID)
		old.closeSend()
	} else if len(rm.peers) >= rm.MaxPeers {
		return nil, SignalEnvelope{}, nil, "ROOM_FULL", ErrRoomFull.Error()
	}

	name := "访客"
	avatar := ""
	clientID := ""
	if req.Profile != nil {
		if n := strings.TrimSpace(req.Profile.Name); n != "" {
			name = truncateRunes(n, 24)
		}
		avatar = sanitizeAvatar(req.Profile.Avatar)
		clientID = truncateRunes(strings.TrimSpace(req.Profile.ClientID), 64)
	}

	nowMs := h.now().UnixMilli()
	profile := PeerProfile{
		PeerID:   peerID,
		ClientID: clientID,
		Name:     name,
		Avatar:   avatar,
		JoinedAt: nowMs,
	}

	isOwner := rm.OwnerTokenHash != "" && VerifyOwnerToken(rm.OwnerTokenHash, req.OwnerToken)

	peer := &peerConn{
		id:      peerID,
		isOwner: isOwner,
		room:    rm,
		profile: profile,
		conn:    conn,
		send:    make(chan []byte, 64),
	}

	// 取消一次性房间无人时的延迟销毁定时器
	if rm.emptyBurnTimer != nil {
		rm.emptyBurnTimer.Stop()
		rm.emptyBurnTimer = nil
	}

	others := make([]*peerConn, 0, len(rm.peers))
	existingProfiles := make([]PeerProfile, 0, len(rm.peers))
	for _, p := range rm.peers {
		others = append(others, p)
		existingProfiles = append(existingProfiles, p.profile)
	}

	rm.peers[peerID] = peer
	rm.EverHadPeer = true
	rm.LastActiveAt = nowMs

	if rm.Mode == ModeFixed {
		_ = h.persistFixedRoomsLocked()
	}

	historyMsgs, histMeta := h.historyStore.GetHistory(rm.Code, rm.RetentionDays)
	histMeta.RetentionDays = rm.RetentionDays
	summary := h.buildRoomSummaryLocked(rm)

	joined := SignalEnvelope{
		Type:     "joined",
		RoomCode: rm.Code,
		PeerID:   peerID,
		IsOwner:  &isOwner,
		Profile:  &profile,
		Peers:    existingProfiles,
		Room:     summary,
		Messages: historyMsgs,
		History:  &histMeta,
	}

	return peer, joined, others, "", ""
}

func (h *Hub) readPump(peer *peerConn) {
	explicitLeave := false
	defer func() {
		h.detachPeer(peer, explicitLeave)
		_ = peer.conn.Close()
	}()

	for {
		_, raw, err := peer.conn.ReadMessage()
		if err != nil {
			return
		}
		var env SignalEnvelope
		if err := json.Unmarshal(raw, &env); err != nil {
			continue
		}

		switch env.Type {
		case "ping":
			h.sendEnvelope(peer, SignalEnvelope{Type: "pong"})

		case "offer", "answer", "ice-candidate":
			h.forwardSignal(peer, env)

		case "profile_update":
			h.handleProfileUpdate(peer, env)

		case "cache_message":
			h.handleCacheMessage(peer, env)

		case "update_retention":
			if env.RetentionDays != nil {
				h.updateRetentionInternal(peer, *env.RetentionDays)
			}

		case "update_max_file_size":
			if env.MaxFileSizeMB != nil {
				h.updateMaxFileSizeInternal(peer, *env.MaxFileSizeMB)
			}

		case "update_allow_random":
			if env.AllowRandom != nil {
				h.updateAllowRandomInternal(peer, *env.AllowRandom)
			}

		case "recall_message":
			if env.MessageID != "" {
				h.recallMessageInternal(peer, env.MessageID)
			}

		case "record_activity":
			if env.ActivityKind != "" {
				h.recordActivityInternal(peer, env.ActivityKind, env.ActivityBytes)
			}

		case "clear_history":
			h.clearHistoryInternal(peer)

		case "leave_room":
			explicitLeave = true
			return

		case "destroy_room":
			if h.destroyRoomInternal(peer) {
				return
			}
		}
	}
}

func (h *Hub) writePump(peer *peerConn) {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		_ = peer.conn.Close()
	}()

	for {
		select {
		case msg, ok := <-peer.send:
			_ = peer.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if !ok {
				_ = peer.conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			if err := peer.conn.WriteMessage(websocket.TextMessage, msg); err != nil {
				return
			}
		case <-ticker.C:
			_ = peer.conn.SetWriteDeadline(time.Now().Add(writeWait))
			if err := peer.conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

func (h *Hub) forwardSignal(sender *peerConn, env SignalEnvelope) {
	if env.TargetPeerID == "" {
		return
	}
	h.mu.RLock()
	rm, ok := h.rooms[sender.room.Code]
	if !ok {
		h.mu.RUnlock()
		return
	}
	target, exists := rm.peers[env.TargetPeerID]
	h.mu.RUnlock()
	if !exists {
		return
	}

	h.sendEnvelope(target, SignalEnvelope{
		Type:       env.Type,
		RoomCode:   rm.Code,
		FromPeerID: sender.id,
		Payload:    env.Payload,
	})
}

func (h *Hub) handleProfileUpdate(sender *peerConn, env SignalEnvelope) {
	if env.Profile == nil {
		return
	}
	h.mu.Lock()
	rm, ok := h.rooms[sender.room.Code]
	if !ok {
		h.mu.Unlock()
		return
	}
	if n := strings.TrimSpace(env.Profile.Name); n != "" {
		sender.profile.Name = truncateRunes(n, 24)
	}
	if env.Profile.Avatar != "" {
		if safeAvatar := sanitizeAvatar(env.Profile.Avatar); safeAvatar != "" {
			sender.profile.Avatar = safeAvatar
		}
	}
	if cid := strings.TrimSpace(env.Profile.ClientID); cid != "" {
		sender.profile.ClientID = truncateRunes(cid, 64)
	}
	updated := sender.profile
	others := make([]*peerConn, 0, len(rm.peers))
	for id, p := range rm.peers {
		if id != sender.id {
			others = append(others, p)
		}
	}
	h.mu.Unlock()

	h.broadcastToPeers(others, SignalEnvelope{
		Type:     "peer_updated",
		RoomCode: rm.Code,
		Profile:  &updated,
	})
}

func (h *Hub) handleCacheMessage(sender *peerConn, env SignalEnvelope) {
	if env.Message == nil {
		return
	}
	msg := *env.Message
	switch msg.Kind {
	case "text", "image", "audio", "file_notice":
	default:
		return
	}
	msg.Text = truncateRunes(msg.Text, 20000)
	msg.Caption = truncateRunes(msg.Caption, 300)
	msg.FileName = truncateRunes(msg.FileName, 260)
	msg.ReplyToID = truncateRunes(strings.TrimSpace(msg.ReplyToID), 80)
	msg.ReplyToSender = truncateRunes(strings.TrimSpace(msg.ReplyToSender), 32)
	msg.ReplyToPreview = truncateRunes(strings.TrimSpace(msg.ReplyToPreview), 160)
	if msg.MediaID != "" && !safeMediaIDRegex.MatchString(msg.MediaID) {
		msg.MediaID = ""
	}

	h.mu.Lock()
	rm, ok := h.rooms[sender.room.Code]
	if !ok {
		h.mu.Unlock()
		return
	}
	// 在锁内安全读取 sender.profile 快照，避免与 handleProfileUpdate 并发竞争
	msg.SenderID = sender.id
	msg.SenderName = sender.profile.Name
	msg.SenderAvatar = sender.profile.Avatar
	retentionDays := rm.RetentionDays
	rm.LastActiveAt = time.Now().UnixMilli()
	allPeers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		allPeers = append(allPeers, p)
	}
	h.mu.Unlock()

	meta, saved, err := h.historyStore.AppendMessage(rm.Code, retentionDays, msg)
	if err != nil {
		return
	}
	meta.RetentionDays = retentionDays

	// 向房间全员广播最新的缓存元数据（如新的自动过期倒计时），
	// 同时附带消息内容用于 P2P 尚未就绪时的兜底同步（客户端会按 message.id 自动去重）
	out := SignalEnvelope{
		Type:     "message_cached",
		RoomCode: rm.Code,
		History:  &meta,
	}
	if saved {
		out.Message = &msg
	}
	h.broadcastToPeers(allPeers, out)
}

func (h *Hub) updateRetentionInternal(sender *peerConn, retentionDays int) {
	if err := ValidateRetentionDays(retentionDays); err != nil {
		return
	}
	h.mu.Lock()
	rm, ok := h.rooms[sender.room.Code]
	if !ok {
		h.mu.Unlock()
		return
	}
	if rm.OwnerTokenHash != "" && !sender.isOwner {
		h.mu.Unlock()
		h.sendEnvelope(sender, SignalEnvelope{
			Type:      "error",
			ErrorCode: "NOT_OWNER",
			Error:     ErrNotOwner.Error(),
		})
		return
	}
	actorName := sender.profile.Name
	rm.RetentionDays = retentionDays
	rm.LastActiveAt = h.now().UnixMilli()
	if rm.Mode == ModeFixed {
		_ = h.persistFixedRoomsLocked()
	}
	meta, cleared, err := h.historyStore.SetRetention(rm.Code, retentionDays)
	if err != nil {
		h.mu.Unlock()
		return
	}
	meta.RetentionDays = retentionDays
	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	h.mu.Unlock()

	rd := retentionDays
	h.broadcastToPeers(peers, SignalEnvelope{
		Type:          "retention_updated",
		RoomCode:      rm.Code,
		RetentionDays: &rd,
		History:       &meta,
		ActorName:     actorName,
	})
	if cleared {
		h.broadcastToPeers(peers, SignalEnvelope{
			Type:      "history_cleared",
			RoomCode:  rm.Code,
			History:   &meta,
			ActorName: actorName,
			Reason:    "聊天记录已立即删除",
		})
	}
}

func (h *Hub) updateMaxFileSizeInternal(sender *peerConn, maxFileSizeMB int) {
	if err := ValidateMaxFileSizeMB(maxFileSizeMB, h.cfg.MaxAttachmentMB); err != nil {
		h.sendEnvelope(sender, SignalEnvelope{
			Type:      "error",
			ErrorCode: "INVALID_MAX_FILE_SIZE",
			Error:     err.Error(),
		})
		return
	}
	h.mu.Lock()
	rm, ok := h.rooms[sender.room.Code]
	if !ok {
		h.mu.Unlock()
		return
	}
	if rm.OwnerTokenHash != "" && !sender.isOwner {
		h.mu.Unlock()
		h.sendEnvelope(sender, SignalEnvelope{
			Type:      "error",
			ErrorCode: "NOT_OWNER",
			Error:     ErrNotOwner.Error(),
		})
		return
	}
	actorName := sender.profile.Name
	rm.MaxFileSizeMB = maxFileSizeMB
	rm.LastActiveAt = h.now().UnixMilli()
	if rm.Mode == ModeFixed {
		_ = h.persistFixedRoomsLocked()
	}
	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	h.mu.Unlock()

	val := maxFileSizeMB
	h.broadcastToPeers(peers, SignalEnvelope{
		Type:          "room_settings_updated",
		RoomCode:      rm.Code,
		MaxFileSizeMB: &val,
		ActorName:     actorName,
	})
}

func (h *Hub) updateAllowRandomInternal(sender *peerConn, allowRandom bool) {
	h.mu.Lock()
	rm, ok := h.rooms[sender.room.Code]
	if !ok {
		h.mu.Unlock()
		return
	}
	if rm.OwnerTokenHash != "" && !sender.isOwner {
		h.mu.Unlock()
		h.sendEnvelope(sender, SignalEnvelope{
			Type:      "error",
			ErrorCode: "NOT_OWNER",
			Error:     ErrNotOwner.Error(),
		})
		return
	}
	actorName := sender.profile.Name
	effective := rm.PasswordHash == "" && allowRandom
	rm.AllowRandom = effective
	rm.LastActiveAt = h.now().UnixMilli()
	if rm.Mode == ModeFixed {
		_ = h.persistFixedRoomsLocked()
	}
	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	h.mu.Unlock()

	val := effective
	h.broadcastToPeers(peers, SignalEnvelope{
		Type:        "room_settings_updated",
		RoomCode:    rm.Code,
		AllowRandom: &val,
		ActorName:   actorName,
	})
}

func (h *Hub) recallMessageInternal(sender *peerConn, msgID string) {
	msgID = truncateRunes(strings.TrimSpace(msgID), 80)
	if msgID == "" {
		return
	}
	h.mu.Lock()
	rm, ok := h.rooms[sender.room.Code]
	if !ok {
		h.mu.Unlock()
		return
	}
	actorName := sender.profile.Name
	retentionDays := rm.RetentionDays
	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	h.mu.Unlock()

	// 校验归属权：若消息在服务器缓存中属于他人，严禁广播撤回事件；阅后即焚（无缓存）时仍广播并由各终端二次校验发送者
	meta, _, forbidden := h.historyStore.DeleteMessage(rm.Code, msgID, sender.id, retentionDays)
	if forbidden {
		return
	}
	meta.RetentionDays = retentionDays

	h.broadcastToPeers(peers, SignalEnvelope{
		Type:       "message_recalled",
		RoomCode:   rm.Code,
		FromPeerID: sender.id,
		MessageID:  msgID,
		ActorName:  actorName,
		History:    &meta,
	})
}

// RecordRoomActivity 累加房间成长统计（消息/文件/番茄钟）并刷新连续火花天数
func (h *Hub) RecordRoomActivity(rawCode, kind string, bytes int64, now time.Time) (RoomStats, []*peerConn, bool) {
	code := NormalizeCode(rawCode)
	h.mu.Lock()
	rm, ok := h.rooms[code]
	if !ok {
		h.mu.Unlock()
		return RoomStats{}, nil, false
	}

	rm.Stats.TouchActivity(now)
	switch kind {
	case "message":
		rm.Stats.TotalMessages++
	case "file":
		rm.Stats.TotalFiles++
		if bytes > 0 {
			rm.Stats.TotalBytes += bytes
		}
	case "pomodoro":
		rm.Stats.TotalPomodoros++
	default:
		h.mu.Unlock()
		return RoomStats{}, nil, false
	}
	rm.LastActiveAt = now.UnixMilli()
	if rm.Mode == ModeFixed {
		_ = h.persistFixedRoomsLocked()
	}
	snapshot := rm.Stats
	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	h.mu.Unlock()
	return snapshot, peers, true
}

func (h *Hub) recordActivityInternal(sender *peerConn, kind string, bytes int64) {
	if bytes < 0 || bytes > 100*1024*1024*1024 {
		bytes = 0
	}
	stats, peers, ok := h.RecordRoomActivity(sender.room.Code, kind, bytes, time.Now())
	if !ok {
		return
	}
	h.broadcastToPeers(peers, SignalEnvelope{
		Type:     "room_stats_updated",
		RoomCode: sender.room.Code,
		Stats:    &stats,
	})
}

func (h *Hub) clearHistoryInternal(sender *peerConn) {
	h.mu.Lock()
	rm, ok := h.rooms[sender.room.Code]
	if !ok {
		h.mu.Unlock()
		return
	}
	if rm.OwnerTokenHash != "" && !sender.isOwner {
		h.mu.Unlock()
		h.sendEnvelope(sender, SignalEnvelope{
			Type:      "error",
			ErrorCode: "NOT_OWNER",
			Error:     ErrNotOwner.Error(),
		})
		return
	}
	actorName := sender.profile.Name
	meta := h.historyStore.ClearRoom(rm.Code, rm.RetentionDays)
	peers := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		peers = append(peers, p)
	}
	h.mu.Unlock()

	h.broadcastToPeers(peers, SignalEnvelope{
		Type:      "history_cleared",
		RoomCode:  rm.Code,
		History:   &meta,
		ActorName: actorName,
		Reason:    "聊天记录已从服务器缓存立即删除",
	})
}

func (h *Hub) destroyRoomInternal(sender *peerConn) bool {
	h.mu.Lock()
	rm, ok := h.rooms[sender.room.Code]
	if !ok {
		h.mu.Unlock()
		return false
	}
	if rm.OwnerTokenHash != "" && !sender.isOwner {
		h.mu.Unlock()
		h.sendEnvelope(sender, SignalEnvelope{
			Type:      "error",
			ErrorCode: "NOT_OWNER",
			Error:     ErrNotOwner.Error(),
		})
		return false
	}
	reason := sender.profile.Name + " 结束了聊天并销毁了房间，该房间码已失效"
	if rm.Mode == ModeFixed {
		reason = sender.profile.Name + " 已删除该固定房间及服务器缓存记录"
	}
	peers := h.destroyRoomLocked(rm)
	h.mu.Unlock()

	h.notifyDestroyedPeers(peers, rm.Code, reason)
	return true
}

func (h *Hub) detachPeer(peer *peerConn, explicitLeave bool) {
	h.mu.Lock()
	rm, ok := h.rooms[peer.room.Code]
	if !ok {
		h.mu.Unlock()
		peer.closeSend()
		return
	}

	current, exists := rm.peers[peer.id]
	if !exists || current != peer {
		h.mu.Unlock()
		peer.closeSend()
		return
	}

	delete(rm.peers, peer.id)
	peer.closeSend()

	others := make([]*peerConn, 0, len(rm.peers))
	for _, p := range rm.peers {
		others = append(others, p)
	}

	// 一次性房间在所有成员离开后自动作废：
	// - 若最后一人主动点击“离开房间”，立刻销毁并作废房间码；
	// - 若属于意外断网或刷新页面，给予 12 秒宽限期重连，超时即刻销毁。
	if rm.Mode == ModeEphemeral && len(rm.peers) == 0 {
		if explicitLeave {
			h.destroyRoomLocked(rm)
		} else {
			code := rm.Code
			if rm.emptyBurnTimer != nil {
				rm.emptyBurnTimer.Stop()
			}
			rm.emptyBurnTimer = time.AfterFunc(ephemeralIdleGrace, func() {
				h.burnEphemeralIfEmpty(code)
			})
		}
	}
	h.mu.Unlock()

	if len(others) > 0 {
		h.broadcastToPeers(others, SignalEnvelope{
			Type:     "peer_left",
			RoomCode: rm.Code,
			PeerID:   peer.id,
		})
	}
}

func (h *Hub) sendEnvelope(peer *peerConn, env SignalEnvelope) {
	raw, err := json.Marshal(env)
	if err != nil {
		return
	}
	peer.enqueue(raw)
}

func (h *Hub) broadcastToPeers(peers []*peerConn, env SignalEnvelope) {
	if len(peers) == 0 {
		return
	}
	raw, err := json.Marshal(env)
	if err != nil {
		return
	}
	for _, p := range peers {
		p.enqueue(raw)
	}
}

func truncateRunes(s string, maxLen int) string {
	r := []rune(s)
	if len(r) <= maxLen {
		return s
	}
	return string(r[:maxLen])
}

// sanitizeAvatar 严格校验并过滤用户头像字符串，仅允许安全预设标识或 <= 64KB 的位图 DataURL
func sanitizeAvatar(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" || len(s) > 65536 {
		return ""
	}
	if strings.HasPrefix(s, "preset:") {
		if len(s) <= 40 {
			return s
		}
		return ""
	}
	lower := strings.ToLower(s)
	if strings.HasPrefix(lower, "data:image/webp;base64,") ||
		strings.HasPrefix(lower, "data:image/png;base64,") ||
		strings.HasPrefix(lower, "data:image/jpeg;base64,") ||
		strings.HasPrefix(lower, "data:image/gif;base64,") {
		return s
	}
	return ""
}
