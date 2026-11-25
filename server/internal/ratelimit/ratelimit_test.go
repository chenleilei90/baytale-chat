package ratelimit

import (
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

func TestClientIPExtraction(t *testing.T) {
	// 1. 优先取 X-Real-IP
	r1 := httptest.NewRequest("GET", "/", nil)
	r1.Header.Set("X-Real-IP", "203.0.113.10")
	r1.Header.Set("X-Forwarded-For", "198.51.100.1, 10.0.0.1")
	r1.RemoteAddr = "127.0.0.1:54321"
	if got := ClientIP(r1); got != "203.0.113.10" {
		t.Fatalf("预期 X-Real-IP 优先，实际: %q", got)
	}

	// 2. 其次取 X-Forwarded-For 第一个值
	r2 := httptest.NewRequest("GET", "/", nil)
	r2.Header.Set("X-Forwarded-For", "198.51.100.2, 10.0.0.2")
	r2.RemoteAddr = "127.0.0.1:54321"
	if got := ClientIP(r2); got != "198.51.100.2" {
		t.Fatalf("预期取 X-Forwarded-For 首项，实际: %q", got)
	}

	// 3. 最后取 RemoteAddr host 部分
	r3 := httptest.NewRequest("GET", "/", nil)
	r3.RemoteAddr = "192.168.1.99:48888"
	if got := ClientIP(r3); got != "192.168.1.99" {
		t.Fatalf("预期取 RemoteAddr host，实际: %q", got)
	}
}

func TestTokenBucketAllowRefillAndPenalize(t *testing.T) {
	now := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	lim := New(map[string]Rule{
		"GET /api/pickups/{code}": {Capacity: 10, Window: 10 * time.Second},
	})
	lim.SetNowFunc(func() time.Time { return now })

	// 连续两次不存在的查询：每次消耗 1 + 额外惩罚 4 = 5 个令牌，2 次即耗尽 10 个令牌
	for i := 0; i < 2; i++ {
		if !lim.Allow("GET /api/pickups/{code}", "1.2.3.4") {
			t.Fatalf("第 %d 次请求应被放行", i+1)
		}
		lim.Penalize("GET /api/pickups/{code}", "1.2.3.4", 4)
	}
	if lim.Allow("GET /api/pickups/{code}", "1.2.3.4") {
		t.Fatalf("令牌耗尽后同 IP 同路由应被限流拒绝")
	}

	// 不同 IP 互不影响
	if !lim.Allow("GET /api/pickups/{code}", "5.6.7.8") {
		t.Fatalf("不同 IP 应有独立配额")
	}

	// 时间推进 2 秒，应补充 2 个令牌
	now = now.Add(2 * time.Second)
	if !lim.Allow("GET /api/pickups/{code}", "1.2.3.4") {
		t.Fatalf("时间补充令牌后应恢复放行")
	}

	// 测试空闲清理
	now = now.Add(2 * time.Hour)
	if removed := lim.PurgeIdle(time.Hour); removed != 2 {
		t.Fatalf("预期清理 2 个空闲桶，实际清理 %d 个", removed)
	}
}

func TestLimiterConcurrency(t *testing.T) {
	lim := New(map[string]Rule{
		"POST /api/rooms": {Capacity: 30, Window: 10 * time.Minute},
	})
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				lim.Allow("POST /api/rooms", "10.0.0.1")
				if j%5 == 0 {
					lim.Penalize("POST /api/rooms", "10.0.0.1", 1)
				}
			}
		}(i)
	}
	wg.Wait()
}
