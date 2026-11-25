package config

import (
	"os"
	"strconv"
	"strings"
)

// Config 保存服务端运行所需的全部环境变量配置
type Config struct {
	Port                 string // HTTP 监听端口，默认 8080
	DataDir              string // 固定房间与聊天缓存持久化目录，默认 ./data
	StaticDir            string // 可选外部前端静态目录（留空则使用二进制内嵌 dist）
	Domain               string // 对外展示的站点域名，例如 chat.baytale.com
	TurnHost             string // STUN/TURN 服务域名或 IP
	TurnPort             int    // TURN UDP/TCP 端口，默认 3478
	TurnTLSPort          int    // TURN TLS 端口，默认 5349（设为 0 表示未启用 TLS TURN）
	TurnSecret           string // coturn use-auth-secret 共享密钥
	TurnTTLSeconds       int64  // TURN 临时凭证有效期（秒），默认 12 小时
	MaxRoomPeers         int    // 单房间最大成员数上限，默认 6
	AllowedOrigin        string // 可选限制的 Origin（留空允许任意来源）
	HistoryMaxMessages   int    // 单房间最多缓存的历史消息条数，默认 500
	HistoryMaxMediaBytes int64  // 单张缓存图片/语音上限（字节），默认 10MB，最高 100MB
	HistoryMaxFileBytes  int64  // 单个历史附件服务器限时缓存上限（字节），默认 100MB，最高 500MB
	MaxAttachmentMB      int    // 服务端允许的单附件最高上限（MB），0 表示不限制
	DefaultRoomMaxFileMB int    // 新建房间默认单附件上限（MB），0 表示不限制，默认 500MB
}

// Load 从环境变量加载服务端配置并提供合理默认值
func Load() *Config {
	maxAttachmentMB := clampInt(getEnvInt("MAX_ATTACHMENT_MB", 0), 0, 102400)
	defaultRoomMaxFileMB := clampInt(getEnvInt("DEFAULT_ROOM_MAX_FILE_MB", 500), 0, 102400)
	if maxAttachmentMB > 0 && (defaultRoomMaxFileMB == 0 || defaultRoomMaxFileMB > maxAttachmentMB) {
		defaultRoomMaxFileMB = maxAttachmentMB
	}
	return &Config{
		Port:                 getEnv("PORT", "8080"),
		DataDir:              getEnv("DATA_DIR", "./data"),
		StaticDir:            strings.TrimSpace(os.Getenv("STATIC_DIR")),
		Domain:               getEnv("DOMAIN", "chat.baytale.com"),
		TurnHost:             strings.TrimSpace(getEnv("TURN_HOST", "")),
		TurnPort:             getEnvInt("TURN_PORT", 3478),
		TurnTLSPort:          getEnvInt("TURN_TLS_PORT", 0),
		TurnSecret:           strings.TrimSpace(os.Getenv("TURN_SECRET")),
		TurnTTLSeconds:       int64(getEnvInt("TURN_TTL_SECONDS", 43200)),
		MaxRoomPeers:         clampInt(getEnvInt("MAX_ROOM_PEERS", 6), 2, 12),
		AllowedOrigin:        strings.TrimSpace(os.Getenv("ALLOWED_ORIGIN")),
		HistoryMaxMessages:   clampInt(getEnvInt("HISTORY_MAX_MESSAGES", 500), 50, 5000),
		HistoryMaxMediaBytes: int64(clampInt(getEnvInt("HISTORY_MAX_MEDIA_MB", 10), 1, 100)) * 1024 * 1024,
		HistoryMaxFileBytes:  int64(clampInt(getEnvInt("HISTORY_MAX_FILE_MB", 100), 1, 500)) * 1024 * 1024,
		MaxAttachmentMB:      maxAttachmentMB,
		DefaultRoomMaxFileMB: defaultRoomMaxFileMB,
	}
}

func getEnv(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return fallback
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return fallback
	}
	return n
}

func clampInt(val, minVal, maxVal int) int {
	if val < minVal {
		return minVal
	}
	if val > maxVal {
		return maxVal
	}
	return val
}
