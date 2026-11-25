package ratelimit

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// Rule 定义单条路由的令牌桶限流规则
type Rule struct {
	Capacity int           // 桶容量（突发允许的最大请求数）
	Window   time.Duration // 补充满整个桶的时间窗口
}

type bucketKey struct {
	route string
	ip    string
}

type tokenBucket struct {
	tokens     float64
	lastRefill time.Time
	lastSeen   time.Time
}

// Limiter 是基于「路由 + IP」的内存令牌桶限流器
type Limiter struct {
	mu      sync.Mutex
	rules   map[string]Rule
	buckets map[bucketKey]*tokenBucket
	nowFunc func() time.Time
}

// New 创建限流器实例并注册各路由限流规则
func New(rules map[string]Rule) *Limiter {
	copied := make(map[string]Rule, len(rules))
	for k, v := range rules {
		copied[k] = v
	}
	return &Limiter{
		rules:   copied,
		buckets: make(map[bucketKey]*tokenBucket),
		nowFunc: time.Now,
	}
}

// SetNowFunc 仅供单元测试注入可控时钟
func (l *Limiter) SetNowFunc(fn func() time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.nowFunc = fn
}

func (l *Limiter) now() time.Time {
	if l.nowFunc != nil {
		return l.nowFunc()
	}
	return time.Now()
}

// ClientIP 按 X-Real-IP -> X-Forwarded-For 首项 -> RemoteAddr host 的顺序提取客户端真实 IP
func ClientIP(r *http.Request) string {
	if r == nil {
		return ""
	}
	if realIP := strings.TrimSpace(r.Header.Get("X-Real-IP")); realIP != "" {
		return realIP
	}
	if xff := strings.TrimSpace(r.Header.Get("X-Forwarded-For")); xff != "" {
		first := xff
		if idx := strings.IndexByte(xff, ','); idx >= 0 {
			first = xff[:idx]
		}
		if trimmed := strings.TrimSpace(first); trimmed != "" {
			return trimmed
		}
	}
	remote := strings.TrimSpace(r.RemoteAddr)
	if remote == "" {
		return ""
	}
	if host, _, err := net.SplitHostPort(remote); err == nil {
		return strings.TrimSpace(host)
	}
	return remote
}

// Allow 判断指定路由与 IP 是否允许消费 1 个令牌
func (l *Limiter) Allow(route, ip string) bool {
	return l.AllowN(route, ip, 1)
}

// AllowN 判断指定路由与 IP 是否允许消费 n 个令牌
func (l *Limiter) AllowN(route, ip string, n float64) bool {
	if n <= 0 {
		return true
	}
	l.mu.Lock()
	defer l.mu.Unlock()

	rule, ok := l.rules[route]
	if !ok || rule.Capacity <= 0 || rule.Window <= 0 {
		return true
	}
	now := l.now()
	key := bucketKey{route: route, ip: ip}
	b := l.getOrInitLocked(key, rule, now)
	l.refillLocked(b, rule, now)
	b.lastSeen = now

	if b.tokens < n {
		return false
	}
	b.tokens -= n
	return true
}

// Penalize 额外扣除指定路由与 IP 的令牌数（用于不存在取件码防枚举等场景，最低扣至 0）
func (l *Limiter) Penalize(route, ip string, extra float64) {
	if extra <= 0 {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()

	rule, ok := l.rules[route]
	if !ok || rule.Capacity <= 0 || rule.Window <= 0 {
		return
	}
	now := l.now()
	key := bucketKey{route: route, ip: ip}
	b := l.getOrInitLocked(key, rule, now)
	l.refillLocked(b, rule, now)
	b.lastSeen = now
	b.tokens -= extra
	if b.tokens < 0 {
		b.tokens = 0
	}
}

func (l *Limiter) getOrInitLocked(key bucketKey, rule Rule, now time.Time) *tokenBucket {
	b, exists := l.buckets[key]
	if !exists {
		b = &tokenBucket{
			tokens:     float64(rule.Capacity),
			lastRefill: now,
			lastSeen:   now,
		}
		l.buckets[key] = b
	}
	return b
}

func (l *Limiter) refillLocked(b *tokenBucket, rule Rule, now time.Time) {
	elapsed := now.Sub(b.lastRefill)
	if elapsed <= 0 {
		return
	}
	rate := float64(rule.Capacity) / rule.Window.Seconds()
	b.tokens += elapsed.Seconds() * rate
	if b.tokens > float64(rule.Capacity) {
		b.tokens = float64(rule.Capacity)
	}
	b.lastRefill = now
}

// PurgeIdle 清理长期不活跃的令牌桶以防止内存无限增长
func (l *Limiter) PurgeIdle(maxIdle time.Duration) int {
	l.mu.Lock()
	defer l.mu.Unlock()
	cutoff := l.now().Add(-maxIdle)
	removed := 0
	for k, b := range l.buckets {
		if b.lastSeen.Before(cutoff) {
			delete(l.buckets, k)
			removed++
		}
	}
	return removed
}
