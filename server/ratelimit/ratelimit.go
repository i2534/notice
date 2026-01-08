package ratelimit

import (
	"net"
	"net/http"
	"sync"
	"time"

	"notice-server/logger"
)

// Config 限流配置
type Config struct {
	MaxFailures int           // 最大失败次数，默认 5
	BlockTime   time.Duration // 封禁时间，默认 15 分钟
	WindowTime  time.Duration // 统计窗口时间，默认 5 分钟
}

// Limiter IP 限流器
type Limiter struct {
	config   Config
	failures map[string]*failureRecord
	mu       sync.RWMutex
}

type failureRecord struct {
	count     int
	firstFail time.Time
	blockedAt time.Time
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

	l := &Limiter{
		config:   cfg,
		failures: make(map[string]*failureRecord),
	}

	// 定期清理过期记录
	go l.cleanup()

	return l
}

// IsBlocked 检查 IP 是否被封禁
func (l *Limiter) IsBlocked(ip string) bool {
	l.mu.RLock()
	defer l.mu.RUnlock()

	record, exists := l.failures[ip]
	if !exists {
		return false
	}

	// 检查是否在封禁期内
	if !record.blockedAt.IsZero() {
		if time.Since(record.blockedAt) < l.config.BlockTime {
			return true
		}
	}

	return false
}

// RecordFailure 记录认证失败
func (l *Limiter) RecordFailure(ip string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()
	record, exists := l.failures[ip]

	if !exists {
		l.failures[ip] = &failureRecord{
			count:     1,
			firstFail: now,
		}
		return false
	}

	// 如果已经被封禁，跳过
	if !record.blockedAt.IsZero() && time.Since(record.blockedAt) < l.config.BlockTime {
		return true
	}

	// 检查是否在统计窗口内
	if time.Since(record.firstFail) > l.config.WindowTime {
		// 窗口已过期，重置计数
		record.count = 1
		record.firstFail = now
		record.blockedAt = time.Time{}
		return false
	}

	// 增加失败计数
	record.count++

	// 检查是否达到封禁阈值
	if record.count >= l.config.MaxFailures {
		record.blockedAt = now
		logger.Warn("IP 已被封禁",
			"ip", ip,
			"failures", record.count,
			"block_duration", l.config.BlockTime.String(),
		)
		return true
	}

	remaining := l.config.MaxFailures - record.count
	logger.Debug("认证失败记录",
		"ip", ip,
		"failures", record.count,
		"remaining", remaining,
	)

	return false
}

// RecordSuccess 记录认证成功，清除失败记录
func (l *Limiter) RecordSuccess(ip string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.failures, ip)
}

// GetBlockedIPs 获取所有被封禁的 IP（用于调试）
func (l *Limiter) GetBlockedIPs() []string {
	l.mu.RLock()
	defer l.mu.RUnlock()

	var blocked []string
	now := time.Now()

	for ip, record := range l.failures {
		if !record.blockedAt.IsZero() && now.Sub(record.blockedAt) < l.config.BlockTime {
			blocked = append(blocked, ip)
		}
	}

	return blocked
}

// cleanup 定期清理过期记录
func (l *Limiter) cleanup() {
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()

	for range ticker.C {
		l.mu.Lock()
		now := time.Now()
		for ip, record := range l.failures {
			// 清理已过期的封禁记录
			if !record.blockedAt.IsZero() {
				if now.Sub(record.blockedAt) > l.config.BlockTime {
					delete(l.failures, ip)
					continue
				}
			}
			// 清理过期的失败记录
			if now.Sub(record.firstFail) > l.config.WindowTime*2 {
				delete(l.failures, ip)
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
