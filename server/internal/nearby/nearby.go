package nearby

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"baychat/server/internal/room"
)

const (
	DeviceTTL        = 45 * time.Second
	InviteTTL        = 60 * time.Second
	MaxDevicesReturn = 12
)

var (
	ErrInvalidRequest    = errors.New("附近设备请求参数无效")
	ErrDeviceOffline     = errors.New("对方设备已离线或不在同一局域网下")
	ErrCellularBlocked   = errors.New("移动蜂窝网络下不支持同 IP 附近设备发现")
	ErrInviteNotFound    = errors.New("附近邀请不存在或已失效")
	ErrInviteExpired     = errors.New("附近邀请已超时失效")
	ErrInviteNotTarget   = errors.New("无权响应该附近邀请")
	ErrInviteAlreadyDone = errors.New("该附近邀请已处理")
)

// RoomCreator 抽象房间创建能力，便于解耦测试与 Hub 集成
type RoomCreator interface {
	CreateRoom(req room.CreateRoomRequest) (*room.RoomSummary, error)
}

// HeartbeatRequest 心跳上报请求体
type HeartbeatRequest struct {
	ClientID string `json:"clientId"`
	Name     string `json:"name"`
	Avatar   string `json:"avatar"`
	Device   string `json:"device"`  // android | web
	Network  string `json:"network"` // wifi | cellular | unknown
}

// DeviceInfo 发现到的附近在线设备摘要
type DeviceInfo struct {
	ClientID string `json:"clientId"`
	Name     string `json:"name"`
	Avatar   string `json:"avatar"`
	Device   string `json:"device"`
}

// InviteNotice 下发给被邀请方的待处理邀请通知
type InviteNotice struct {
	InviteID     string `json:"inviteId"`
	FromClientID string `json:"fromClientId"`
	FromName     string `json:"fromName"`
	FromAvatar   string `json:"fromAvatar"`
	RoomCode     string `json:"roomCode"`
	ExpiresAt    int64  `json:"expiresAt"`
}

// HeartbeatResponse 心跳响应（在线附近设备列表与待处理邀请）
type HeartbeatResponse struct {
	Devices []DeviceInfo   `json:"devices"`
	Invites []InviteNotice `json:"invites"`
}

// InviteRequest 发起附近传输邀请请求体
type InviteRequest struct {
	FromClientID string `json:"fromClientId"`
	ToClientID   string `json:"toClientId"`
	FromName     string `json:"fromName"`
	FromAvatar   string `json:"fromAvatar"`
}

// InviteResponse 发起附近邀请响应
type InviteResponse struct {
	InviteID   string            `json:"inviteId"`
	Room       *room.RoomSummary `json:"room"`
	OwnerToken string            `json:"ownerToken"`
}

// RespondRequest 接收方响应附近邀请请求体
type RespondRequest struct {
	ClientID string `json:"clientId"`
	Accept   bool   `json:"accept"`
}

// RespondResponse 接收方响应附近邀请结果
type RespondResponse struct {
	Status   string `json:"status"`
	RoomCode string `json:"roomCode,omitempty"`
}

type deviceEntry struct {
	clientID string
	ip       string
	name     string
	avatar   string
	device   string
	network  string
	lastSeen time.Time
}

type inviteEntry struct {
	inviteID     string
	fromClientID string
	toClientID   string
	fromName     string
	fromAvatar   string
	roomCode     string
	status       string // pending | accepted | declined | expired
	createdAt    time.Time
	expiresAt    time.Time
}

// Service 管理基于同公网 IP 的附近设备心跳发现与快捷建房邀请状态机
type Service struct {
	mu          sync.Mutex
	roomCreator RoomCreator
	devices     map[string]*deviceEntry
	invites     map[string]*inviteEntry
	nowFunc     func() time.Time
	stopCh      chan struct{}
}

// NewService 创建附近设备发现服务并启动后台过期清理协程
func NewService(roomCreator RoomCreator) *Service {
	s := &Service{
		roomCreator: roomCreator,
		devices:     make(map[string]*deviceEntry),
		invites:     make(map[string]*inviteEntry),
		nowFunc:     time.Now,
		stopCh:      make(chan struct{}),
	}
	go s.cleanupLoop()
	return s
}

// Close 停止后台清理任务
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
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.stopCh:
			return
		case <-ticker.C:
			s.PurgeStale()
		}
	}
}

// PurgeStale 清理长期离线设备与过期邀请
func (s *Service) PurgeStale() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	devCutoff := now.Add(-2 * DeviceTTL)
	for id, dev := range s.devices {
		if dev.lastSeen.Before(devCutoff) {
			delete(s.devices, id)
		}
	}
	invCutoff := now.Add(-5 * InviteTTL)
	for id, inv := range s.invites {
		if inv.status == "pending" && !now.Before(inv.expiresAt) {
			inv.status = "expired"
		}
		if inv.expiresAt.Before(invCutoff) {
			delete(s.invites, id)
		}
	}
}

// Heartbeat 上报设备活跃状态并返回同 IP 附近设备及待处理邀请
func (s *Service) Heartbeat(req HeartbeatRequest, ip string) (*HeartbeatResponse, error) {
	clientID := truncateRunes(strings.TrimSpace(req.ClientID), 64)
	if clientID == "" {
		return nil, fmt.Errorf("%w：缺少 clientId", ErrInvalidRequest)
	}
	name := truncateRunes(strings.TrimSpace(req.Name), 24)
	if name == "" {
		name = "附近伙伴"
	}
	avatar := sanitizeAvatar(req.Avatar)
	device := normalizeDevice(req.Device)
	network := normalizeNetwork(req.Network)

	s.mu.Lock()
	defer s.mu.Unlock()

	now := s.now()
	s.devices[clientID] = &deviceEntry{
		clientID: clientID,
		ip:       ip,
		name:     name,
		avatar:   avatar,
		device:   device,
		network:  network,
		lastSeen: now,
	}

	resp := &HeartbeatResponse{
		Devices: make([]DeviceInfo, 0),
		Invites: make([]InviteNotice, 0),
	}

	// 蜂窝网络下运营商 CGNAT 会共享公网 IP，不参与附近匹配
	if network == "cellular" || strings.TrimSpace(ip) == "" {
		return resp, nil
	}

	cutoff := now.Add(-DeviceTTL)
	candidates := make([]*deviceEntry, 0)
	for _, dev := range s.devices {
		if dev.clientID == clientID || dev.ip != ip {
			continue
		}
		if dev.network == "cellular" {
			continue
		}
		if dev.lastSeen.Before(cutoff) {
			continue
		}
		candidates = append(candidates, dev)
	}
	sort.Slice(candidates, func(i, j int) bool {
		return candidates[i].lastSeen.After(candidates[j].lastSeen)
	})
	if len(candidates) > MaxDevicesReturn {
		candidates = candidates[:MaxDevicesReturn]
	}
	for _, dev := range candidates {
		resp.Devices = append(resp.Devices, DeviceInfo{
			ClientID: dev.clientID,
			Name:     dev.name,
			Avatar:   dev.avatar,
			Device:   dev.device,
		})
	}

	for _, inv := range s.invites {
		if inv.toClientID != clientID {
			continue
		}
		if inv.status == "pending" && !now.Before(inv.expiresAt) {
			inv.status = "expired"
		}
		if inv.status == "pending" {
			resp.Invites = append(resp.Invites, InviteNotice{
				InviteID:     inv.inviteID,
				FromClientID: inv.fromClientID,
				FromName:     inv.fromName,
				FromAvatar:   inv.fromAvatar,
				RoomCode:     inv.roomCode,
				ExpiresAt:    inv.expiresAt.UnixMilli(),
			})
		}
	}
	sort.Slice(resp.Invites, func(i, j int) bool {
		return resp.Invites[i].ExpiresAt > resp.Invites[j].ExpiresAt
	})

	return resp, nil
}

// CreateInvite 校验双方在线且同 IP 非蜂窝后，自动创建私密临时房并生成 60 秒有效邀请
func (s *Service) CreateInvite(req InviteRequest, ip string) (*InviteResponse, error) {
	fromID := truncateRunes(strings.TrimSpace(req.FromClientID), 64)
	toID := truncateRunes(strings.TrimSpace(req.ToClientID), 64)
	if fromID == "" || toID == "" || fromID == toID {
		return nil, fmt.Errorf("%w：邀请双方标识无效", ErrInvalidRequest)
	}

	s.mu.Lock()
	now := s.now()
	cutoff := now.Add(-DeviceTTL)
	fromDev, okFrom := s.devices[fromID]
	toDev, okTo := s.devices[toID]
	if !okFrom || !okTo || fromDev.lastSeen.Before(cutoff) || toDev.lastSeen.Before(cutoff) {
		s.mu.Unlock()
		return nil, ErrDeviceOffline
	}
	if fromDev.network == "cellular" || toDev.network == "cellular" {
		s.mu.Unlock()
		return nil, ErrCellularBlocked
	}
	if strings.TrimSpace(ip) == "" || fromDev.ip != ip || toDev.ip != ip {
		s.mu.Unlock()
		return nil, ErrDeviceOffline
	}
	fromName := truncateRunes(strings.TrimSpace(req.FromName), 24)
	if fromName == "" {
		fromName = fromDev.name
	}
	fromAvatar := sanitizeAvatar(req.FromAvatar)
	if fromAvatar == "" {
		fromAvatar = fromDev.avatar
	}
	s.mu.Unlock()

	allowRandom := false
	retention := 1
	summary, err := s.roomCreator.CreateRoom(room.CreateRoomRequest{
		Mode:          room.ModeEphemeral,
		AllowRandom:   &allowRandom,
		MaxPeers:      2,
		RetentionDays: &retention,
	})
	if err != nil {
		return nil, err
	}

	inviteID := "inv_" + randomHex(10)
	s.mu.Lock()
	now = s.now()
	s.invites[inviteID] = &inviteEntry{
		inviteID:     inviteID,
		fromClientID: fromID,
		toClientID:   toID,
		fromName:     fromName,
		fromAvatar:   fromAvatar,
		roomCode:     summary.Code,
		status:       "pending",
		createdAt:    now,
		expiresAt:    now.Add(InviteTTL),
	}
	s.mu.Unlock()

	return &InviteResponse{
		InviteID:   inviteID,
		Room:       summary,
		OwnerToken: summary.OwnerToken,
	}, nil
}

// RespondInvite 处理接收方接受或拒绝附近邀请
func (s *Service) RespondInvite(inviteID string, req RespondRequest) (*RespondResponse, error) {
	clientID := truncateRunes(strings.TrimSpace(req.ClientID), 64)
	if clientID == "" {
		return nil, fmt.Errorf("%w：缺少 clientId", ErrInvalidRequest)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	inv, ok := s.invites[strings.TrimSpace(inviteID)]
	if !ok {
		return nil, ErrInviteNotFound
	}
	if inv.toClientID != clientID {
		return nil, ErrInviteNotTarget
	}
	now := s.now()
	if inv.status == "pending" && !now.Before(inv.expiresAt) {
		inv.status = "expired"
	}
	if inv.status == "expired" {
		return nil, ErrInviteExpired
	}
	if inv.status != "pending" {
		return nil, ErrInviteAlreadyDone
	}

	if req.Accept {
		inv.status = "accepted"
		return &RespondResponse{
			Status:   "accepted",
			RoomCode: inv.roomCode,
		}, nil
	}
	inv.status = "declined"
	return &RespondResponse{
		Status: "declined",
	}, nil
}

// GetInviteStatus 查询邀请当前状态（供发起方在房间内每 3 秒轮询）
func (s *Service) GetInviteStatus(inviteID string) (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	inv, ok := s.invites[strings.TrimSpace(inviteID)]
	if !ok {
		return "", ErrInviteNotFound
	}
	if inv.status == "pending" && !s.now().Before(inv.expiresAt) {
		inv.status = "expired"
	}
	return inv.status, nil
}

func normalizeDevice(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "android":
		return "android"
	default:
		return "web"
	}
}

func normalizeNetwork(raw string) string {
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "wifi":
		return "wifi"
	case "cellular":
		return "cellular"
	default:
		return "unknown"
	}
}

func truncateRunes(s string, maxLen int) string {
	r := []rune(s)
	if len(r) <= maxLen {
		return s
	}
	return string(r[:maxLen])
}

func sanitizeAvatar(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" || len(s) > 65536 {
		return ""
	}
	if strings.HasPrefix(s, "preset:") && len(s) <= 40 {
		return s
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

func randomHex(nBytes int) string {
	buf := make([]byte, nBytes)
	if _, err := rand.Read(buf); err != nil {
		return fmt.Sprintf("%016x", time.Now().UnixNano())
	}
	return hex.EncodeToString(buf)
}
