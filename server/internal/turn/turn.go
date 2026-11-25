package turn

import (
	"crypto/hmac"
	"crypto/sha1"
	"encoding/base64"
	"fmt"
	"time"

	"baychat/server/internal/config"
)

// ICEServer 对应浏览器 WebRTC RTCIceServer 结构
type ICEServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

// BuildICEServers 根据服务端配置组装 STUN 与动态临时 TURN 凭证列表
func BuildICEServers(cfg *config.Config, clientID string, now time.Time) ([]ICEServer, bool) {
	servers := []ICEServer{
		{
			URLs: []string{
				"stun:stun.cloudflare.com:3478",
				"stun:stun.l.google.com:19302",
				"stun:stun1.l.google.com:19302",
			},
		},
	}

	if cfg.TurnHost == "" {
		return servers, false
	}

	// 自建服务器 STUN 地址优先置顶，加快国内公网打洞速度
	ownStun := ICEServer{
		URLs: []string{
			fmt.Sprintf("stun:%s:%d", cfg.TurnHost, cfg.TurnPort),
		},
	}
	servers = append([]ICEServer{ownStun}, servers...)

	if cfg.TurnSecret == "" {
		return servers, false
	}

	username, credential := GenerateCredentials(cfg.TurnSecret, clientID, cfg.TurnTTLSeconds, now)
	turnURLs := []string{
		fmt.Sprintf("turn:%s:%d?transport=udp", cfg.TurnHost, cfg.TurnPort),
		fmt.Sprintf("turn:%s:%d?transport=tcp", cfg.TurnHost, cfg.TurnPort),
	}
	if cfg.TurnTLSPort > 0 {
		turnURLs = append(turnURLs, fmt.Sprintf("turns:%s:%d?transport=tcp", cfg.TurnHost, cfg.TurnTLSPort))
	}

	servers = append(servers, ICEServer{
		URLs:       turnURLs,
		Username:   username,
		Credential: credential,
	})
	return servers, true
}

// GenerateCredentials 按照 coturn TURN REST API 规范签发 HMAC-SHA1 动态临时凭证
// 防止公网中转服务密钥写死在前端后被第三方盗刷带宽
func GenerateCredentials(secret, clientID string, ttlSeconds int64, now time.Time) (string, string) {
	if ttlSeconds <= 0 {
		ttlSeconds = 43200
	}
	if clientID == "" {
		clientID = "anon"
	}
	expiry := now.Unix() + ttlSeconds
	username := fmt.Sprintf("%d:%s", expiry, clientID)
	mac := hmac.New(sha1.New, []byte(secret))
	_, _ = mac.Write([]byte(username))
	credential := base64.StdEncoding.EncodeToString(mac.Sum(nil))
	return username, credential
}
