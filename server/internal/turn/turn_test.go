package turn

import (
	"strings"
	"testing"
	"time"

	"baychat/server/internal/config"
)

func TestGenerateCredentialsAndBuildICEServers(t *testing.T) {
	now := time.Unix(1700000000, 0)
	username, credential := GenerateCredentials("super-secret", "peer-1", 3600, now)
	if !strings.HasPrefix(username, "1700003600:peer-1") {
		t.Fatalf("预期用户名包含过期时间戳与客户端 ID，实际得到: %s", username)
	}
	if credential == "" {
		t.Fatalf("预期生成非空 HMAC 凭证")
	}

	cfg := &config.Config{
		TurnHost:       "chat.baytale.com",
		TurnPort:       3478,
		TurnTLSPort:    5349,
		TurnSecret:     "super-secret",
		TurnTTLSeconds: 3600,
	}
	servers, hasTurn := BuildICEServers(cfg, "peer-1", now)
	if !hasTurn {
		t.Fatalf("预期 hasTurn 为 true")
	}
	if len(servers) < 3 {
		t.Fatalf("预期至少包含自建 STUN、公共 STUN 与自建 TURN 三组配置，实际: %d", len(servers))
	}
	last := servers[len(servers)-1]
	if last.Username != username || last.Credential != credential {
		t.Fatalf("TURN 凭证不匹配")
	}
}
