package ratelimit

import (
	"crypto/sha256"
	"encoding/hex"
	"net"
	"net/http"
	"sync"
	"time"

	"notice-server/logger"
)

// Config 限流配置
type Config struct {
	MaxFailures int           // 每 IP 最大失败次数，默认 5
	BlockTime   time.Duration // 封禁时间，默认 15 分钟
	WindowTime  time.Duration // 统计窗口时间，默认 5 分钟

	// 防换 IP 暴力破解（0 表示关闭）
	GlobalMaxPerMinute    int           // 每分钟全局失败次数上限，超过则短时拒绝所有认证
	GlobalBlockTime       time.Duration  // 全局封禁冷却时间
	CredentialMaxFailures int           // 同一错误凭证被尝试次数上限，超过则临时封禁该凭证
}

// Limiter 认证限流器：按 IP、按错误凭证、全局三种维度
type Limiter struct {
	config   Config
	failures map[string]*failureRecord   // key: IP 或 credentialHash
	global   struct {
		count         int
		windowStart   time.Time
		blockedUntil  time.Time
	}
	mu       sync.RWMutex
}

type failureRecord struct {
	count     int
	firstFail time.Time
	blockedAt time.Time
}

// credentialKey 对尝试过的错误凭证做哈希，避免存储明文
func credentialKey(attempted string) string {
	if attempted == "" {
		return ""
	}
	h := sha256.Sum256([]byte(attempted))
	return "cred:" + hex.EncodeToString(h[:])
}

// New 创建新的限流器
func New(cfg Config) *Limiter {
	if cfg.MaxFailures <= 0 {
		cfg.MaxFailures = 5
	}
	if cfg.BlockTime <= 0 {
		cfg.BlockTime = 15 * time.Minute
	}
	if cfg.WindowTime <= 0 {
		cfg.WindowTime = 5 * time.Minute
	}
	if cfg.GlobalBlockTime <= 0 && cfg.GlobalMaxPerMinute > 0 {
		cfg.GlobalBlockTime = time.Minute
	}

	l := &Limiter{
		config:   cfg,
		failures: make(map[string]*failureRecord),
	}

	go l.cleanup()
	return l
}

// IsBlocked 检查是否应拒绝认证：按 IP、按尝试的凭证、全局任一触发即拒绝。
// attemptedCredential 为本次尝试的 token/密码（错误时传入，用于按凭证封禁）；为空则只检查 IP 与全局。
func (l *Limiter) IsBlocked(ip string, attemptedCredential string) bool {
	l.mu.RLock()
	defer l.mu.RUnlock()

	now := time.Now()

	// 1. 全局封禁：每分钟失败次数超限后短时拒绝所有认证
	if l.config.GlobalMaxPerMinute > 0 && now.Before(l.global.blockedUntil) {
		return true
	}

	// 2. 按 IP 封禁
	if ip != "" {
		if rec, ok := l.failures[ip]; ok && !rec.blockedAt.IsZero() && now.Sub(rec.blockedAt) < l.config.BlockTime {
			return true
		}
	}

	// 3. 按错误凭证封禁：同一错误 token 被多 IP 尝试过多则封禁该凭证
	if key := credentialKey(attemptedCredential); key != "" && l.config.CredentialMaxFailures > 0 {
		if rec, ok := l.failures[key]; ok && !rec.blockedAt.IsZero() && now.Sub(rec.blockedAt) < l.config.BlockTime {
			return true
		}
	}

	return false
}

// recordFailureLocked 对单个 key（IP 或 credentialKey）做失败计数与封禁，返回是否新进入封禁。
func (l *Limiter) recordFailureLocked(now time.Time, key string, maxFailures int, logLabel string) bool {
	record, exists := l.failures[key]
	if !exists {
		l.failures[key] = &failureRecord{count: 1, firstFail: now}
		return false
	}
	if !record.blockedAt.IsZero() && now.Sub(record.blockedAt) < l.config.BlockTime {
		return true
	}
	if now.Sub(record.firstFail) > l.config.WindowTime {
		record.count = 1
		record.firstFail = now
		record.blockedAt = time.Time{}
		return false
	}
	record.count++
	if record.count < maxFailures {
		logger.Debug("认证失败记录", "key", logLabel, "failures", record.count, "remaining", maxFailures-record.count)
		return false
	}
	record.blockedAt = now
	logger.Warn("已被封禁", "key", logLabel, "failures", record.count, "block_duration", l.config.BlockTime.String())
	return true
}

// RecordFailure 记录认证失败。attemptedCredential 为本次尝试的错误 token/密码，用于按凭证限流；可为空。
func (l *Limiter) RecordFailure(ip string, attemptedCredential string) {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()

	// 1. 按 IP 计数并可能封禁
	if ip != "" {
		l.recordFailureLocked(now, ip, l.config.MaxFailures, ip)
	}

	// 2. 全局计数：每分钟超限则设置全局封禁
	if l.config.GlobalMaxPerMinute > 0 {
		if now.Sub(l.global.windowStart) > time.Minute {
			l.global.count = 0
			l.global.windowStart = now
		}
		l.global.count++
		if l.global.count >= l.config.GlobalMaxPerMinute {
			l.global.blockedUntil = now.Add(l.config.GlobalBlockTime)
			logger.Warn("全局认证失败过多，临时拒绝所有认证",
				"failures", l.global.count,
				"block_duration", l.config.GlobalBlockTime.String(),
			)
		}
	}

	// 3. 按错误凭证计数并可能封禁
	if key := credentialKey(attemptedCredential); key != "" && l.config.CredentialMaxFailures > 0 {
		l.recordFailureLocked(now, key, l.config.CredentialMaxFailures, "credential")
	}
}

// RecordSuccess 记录认证成功，清除失败记录
func (l *Limiter) RecordSuccess(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.failures, ip)
}

// GetBlockedIPs 获取所有被封禁的 IP（用于调试，不含按凭证封禁的 key）
func (l *Limiter) GetBlockedIPs() []string {
	l.mu.RLock()
	defer l.mu.RUnlock()

	var blocked []string
	now := time.Now()
	for key, record := range l.failures {
		if len(key) > 0 && key[:5] != "cred:" && !record.blockedAt.IsZero() && now.Sub(record.blockedAt) < l.config.BlockTime {
			blocked = append(blocked, key)
		}
	}
	return blocked
}

// cleanup 定期清理过期记录（含 IP 与 credential key）
func (l *Limiter) cleanup() {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()
	for range ticker.C {
		l.mu.Lock()
		now := time.Now()
		for key, record := range l.failures {
			if !record.blockedAt.IsZero() && now.Sub(record.blockedAt) > l.config.BlockTime {
				delete(l.failures, key)
				continue
			}
			if now.Sub(record.firstFail) > l.config.WindowTime*2 {
				delete(l.failures, key)
			}
		}
		l.mu.Unlock()
	}
}

// GetClientIP 从请求中获取客户端 IP
func GetClientIP(r *http.Request) string {
	// 优先使用 X-Forwarded-For（代理场景）
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		// 取第一个 IP
		if idx := len(xff); idx > 0 {
			for i, c := range xff {
				if c == ',' {
					return xff[:i]
				}
			}
			return xff
		}
	}

	// 其次使用 X-Real-IP
	if xri := r.Header.Get("X-Real-IP"); xri != "" {
		return xri
	}

	// 最后使用 RemoteAddr
	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return ip
}
