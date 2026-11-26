package push

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	defaultEndpoint       = "https://api.jpush.cn/v3/push"
	registrationExpireSec = int64(30 * 24 * 3600)
)

// DeviceRegistration 描述某个房间内客户端上报的系统级推送设备凭据
type DeviceRegistration struct {
	RoomCode       string `json:"roomCode"`
	ClientID       string `json:"clientId"`
	PeerID         string `json:"peerId,omitempty"`
	RegistrationID string `json:"registrationId"`
	Platform       string `json:"platform,omitempty"` // android | ios
	Brand          string `json:"brand,omitempty"`    // 手机品牌标识（用于统计与诊断）
	UpdatedAt      int64  `json:"updatedAt"`        // Unix 秒级时间戳
}

// Service 管理房间推送设备注册表，并在新消息落库时通过极光 JPush v3 向离线成员下发系统级通知（含全厂商通道策略）
type Service struct {
	dataFile     string
	appKey       string
	masterSecret string
	endpoint     string
	httpClient   *http.Client

	mu    sync.RWMutex
	rooms map[string]map[string]DeviceRegistration // roomCode -> clientId -> registration
}

// NewService 初始化推送服务并从磁盘加载设备注册表
func NewService(dataDir, appKey, masterSecret, endpoint string) *Service {
	cleanEndpoint := strings.TrimSpace(endpoint)
	if cleanEndpoint == "" {
		cleanEndpoint = defaultEndpoint
	}
	var dataFile string
	if strings.TrimSpace(dataDir) != "" {
		dataFile = filepath.Join(dataDir, "push_devices.json")
	}
	svc := &Service{
		dataFile:     dataFile,
		appKey:       strings.TrimSpace(appKey),
		masterSecret: strings.TrimSpace(masterSecret),
		endpoint:     cleanEndpoint,
		httpClient:   &http.Client{Timeout: 10 * time.Second},
		rooms:        make(map[string]map[string]DeviceRegistration),
	}
	svc.loadFromDisk()
	return svc
}

// Enabled 返回服务端是否已完整配置 JPush AppKey 与 MasterSecret
func (s *Service) Enabled() bool {
	return s.appKey != "" && s.masterSecret != ""
}

// Register 注册或刷新房间成员的系统级推送 RegistrationID
func (s *Service) Register(reg DeviceRegistration) error {
	reg.RoomCode = strings.TrimSpace(reg.RoomCode)
	reg.ClientID = strings.TrimSpace(reg.ClientID)
	reg.PeerID = strings.TrimSpace(reg.PeerID)
	reg.RegistrationID = strings.TrimSpace(reg.RegistrationID)
	reg.Platform = strings.ToLower(strings.TrimSpace(reg.Platform))
	if reg.Platform == "" {
		reg.Platform = "android"
	}
	reg.Brand = strings.TrimSpace(reg.Brand)
	if reg.RoomCode == "" || reg.ClientID == "" || reg.RegistrationID == "" {
		return errors.New("roomCode、clientId 与 registrationId 均不能为空")
	}
	reg.UpdatedAt = time.Now().Unix()

	s.mu.Lock()
	defer s.mu.Unlock()

	if s.rooms[reg.RoomCode] == nil {
		s.rooms[reg.RoomCode] = make(map[string]DeviceRegistration)
	}
	s.rooms[reg.RoomCode][reg.ClientID] = reg
	s.pruneExpiredLocked(reg.UpdatedAt)
	return s.saveToDiskLocked()
}

// Unregister 注销客户端在指定房间内的推送登记（用户主动退房/解散房间时调用）
func (s *Service) Unregister(roomCode, clientID string) error {
	roomCode = strings.TrimSpace(roomCode)
	clientID = strings.TrimSpace(clientID)
	if roomCode == "" || clientID == "" {
		return nil
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	peers, ok := s.rooms[roomCode]
	if !ok {
		return nil
	}
	delete(peers, clientID)
	if len(peers) == 0 {
		delete(s.rooms, roomCode)
	}
	return s.saveToDiskLocked()
}

// NotifyOfflineRoomPeers 向当前未在线（切后台被杀/断连）的同房成员发送极光系统级离线推送（覆盖华米OV荣与 FCM）
func (s *Service) NotifyOfflineRoomPeers(
	roomCode, senderPeerID, senderName, preview string,
	onlinePeerIDs map[string]bool,
) {
	if !s.Enabled() {
		return
	}
	roomCode = strings.TrimSpace(roomCode)
	if roomCode == "" {
		return
	}

	nowSec := time.Now().Unix()
	s.mu.RLock()
	roomPeers := s.rooms[roomCode]
	regIDs := make([]string, 0, len(roomPeers))
	seenReg := make(map[string]struct{}, len(roomPeers))
	for _, reg := range roomPeers {
		if reg.RegistrationID == "" {
			continue
		}
		if reg.UpdatedAt > 0 && nowSec-reg.UpdatedAt > registrationExpireSec {
			continue
		}
		// 优先用 peerId 判断发送者/在线状态，未填 peerId 时回退到 clientId
		targetIdentity := reg.PeerID
		if targetIdentity == "" {
			targetIdentity = reg.ClientID
		}
		if senderPeerID != "" && targetIdentity == senderPeerID {
			continue
		}
		if onlinePeerIDs != nil && onlinePeerIDs[targetIdentity] {
			continue
		}
		if _, dup := seenReg[reg.RegistrationID]; dup {
			continue
		}
		seenReg[reg.RegistrationID] = struct{}{}
		regIDs = append(regIDs, reg.RegistrationID)
	}
	s.mu.RUnlock()

	if len(regIDs) == 0 {
		return
	}

	title := strings.TrimSpace(senderName)
	if title == "" {
		title = "BayChat 新消息"
	}
	alert := truncateRunes(strings.TrimSpace(preview), 80)
	if alert == "" {
		alert = "收到一条新消息"
	}

	intentURL := "intent:#Intent;action=android.intent.action.VIEW;data=baychat://room/" + roomCode + ";end"
	// third_party_channel 配置全主流手机厂商通道优先级为 secondary_push（在线走极光通道，杀后台走小米/华为/OPPO/vivo/荣耀/FCM 系统底层）
	payload := map[string]any{
		"platform": "android",
		"audience": map[string]any{
			"registration_id": regIDs,
		},
		"notification": map[string]any{
			"android": map[string]any{
				"alert":                   alert,
				"title":                   title,
				"notification_channel_id": "baychat_incoming",
				"intent": map[string]any{
					"url": intentURL,
				},
				"extras": map[string]any{
					"roomCode": roomCode,
				},
			},
		},
		"options": map[string]any{
			"time_to_live": 86400,
			"third_party_channel": map[string]any{
				"xiaomi": map[string]any{"distribution": "secondary_push"},
				"huawei": map[string]any{"distribution": "secondary_push"},
				"oppo":   map[string]any{"distribution": "secondary_push"},
				"vivo":   map[string]any{"distribution": "secondary_push"},
				"honor":  map[string]any{"distribution": "secondary_push"},
				"fcm":    map[string]any{"distribution": "secondary_push"},
			},
		},
	}

	body, err := json.Marshal(payload)
	if err != nil {
		log.Printf("构建 JPush 请求 JSON 失败: %v", err)
		return
	}

	req, err := http.NewRequest(http.MethodPost, s.endpoint, bytes.NewReader(body))
	if err != nil {
		log.Printf("创建 JPush HTTP 请求失败: %v", err)
		return
	}
	creds := base64.StdEncoding.EncodeToString([]byte(s.appKey + ":" + s.masterSecret))
	req.Header.Set("Authorization", "Basic "+creds)
	req.Header.Set("Content-Type", "application/json; charset=utf-8")

	resp, err := s.httpClient.Do(req)
	if err != nil {
		log.Printf("调用 JPush 推送失败 (room=%s): %v", roomCode, err)
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		respBytes, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		log.Printf("JPush 推送返回非 2xx (room=%s status=%d): %s", roomCode, resp.StatusCode, string(respBytes))
	}
}

func (s *Service) pruneExpiredLocked(nowSec int64) {
	for code, peers := range s.rooms {
		for cid, reg := range peers {
			if reg.UpdatedAt > 0 && nowSec-reg.UpdatedAt > registrationExpireSec {
				delete(peers, cid)
			}
		}
		if len(peers) == 0 {
			delete(s.rooms, code)
		}
	}
}

func (s *Service) loadFromDisk() {
	if s.dataFile == "" {
		return
	}
	raw, err := os.ReadFile(s.dataFile)
	if err != nil {
		return
	}
	var snapshot map[string]map[string]DeviceRegistration
	if err := json.Unmarshal(raw, &snapshot); err != nil {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if snapshot != nil {
		s.rooms = snapshot
	}
	s.pruneExpiredLocked(time.Now().Unix())
}

func (s *Service) saveToDiskLocked() error {
	if s.dataFile == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(s.dataFile), 0o755); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(s.rooms, "", "  ")
	if err != nil {
		return err
	}
	tmpPath := s.dataFile + ".tmp"
	if err := os.WriteFile(tmpPath, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmpPath, s.dataFile)
}

func truncateRunes(s string, maxLen int) string {
	if maxLen <= 0 {
		return ""
	}
	runes := []rune(s)
	if len(runes) <= maxLen {
		return s
	}
	return string(runes[:maxLen-1]) + "…"
}
