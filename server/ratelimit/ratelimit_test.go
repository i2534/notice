package ratelimit

import (
	"fmt"
	"testing"
	"time"

	"notice-server/logger"
)

func init() {
	if logger.Get() == nil {
		_, _ = logger.Init(logger.Config{ConsoleLevel: "off", FileLevel: "off"})
	}
}

// TestGlobalRateLimitTrigger 全局限流：多 IP 累计失败达到上限后，任意 IP 均被拒绝
func TestGlobalRateLimitTrigger(t *testing.T) {
	blockTime := 200 * time.Millisecond
	limiter := New(Config{
		MaxFailures:            10,  // 单 IP 放宽，避免先触发 IP 封禁
		BlockTime:              time.Minute,
		WindowTime:             time.Minute,
		GlobalMaxPerMinute:     3,
		GlobalBlockTime:        blockTime,
		CredentialMaxFailures:  0,
	})

	// 3 个不同 IP 各失败 1 次，累计 3 次触发全局
	for i := 0; i < 3; i++ {
		ip := fmt.Sprintf("192.168.1.%d:0", i+1)
		limiter.RecordFailure(ip, "wrong-token")
	}

	// 任意 IP、任意凭证（含正确 token）都应被拒
	if !limiter.IsBlocked("192.168.1.100", "") {
		t.Error("expected blocked by global limit (any IP)")
	}
	if !limiter.IsBlocked("10.0.0.1", "correct-token") {
		t.Error("expected blocked by global limit (correct token)")
	}
}

// TestGlobalRateLimitUnblockAfterBlockTime 全局限流：冷却时间过后恢复
func TestGlobalRateLimitUnblockAfterBlockTime(t *testing.T) {
	blockTime := 100 * time.Millisecond
	limiter := New(Config{
		MaxFailures:           10,
		BlockTime:             time.Minute,
		WindowTime:            time.Minute,
		GlobalMaxPerMinute:    2,
		GlobalBlockTime:       blockTime,
		CredentialMaxFailures:  0,
	})

	limiter.RecordFailure("1.2.3.4:0", "a")
	limiter.RecordFailure("5.6.7.8:0", "b")

	if !limiter.IsBlocked("9.9.9.9", "") {
		t.Fatal("expected blocked immediately after global trigger")
	}

	time.Sleep(blockTime + 50*time.Millisecond)

	if limiter.IsBlocked("9.9.9.9", "") {
		t.Error("expected unblocked after global block time")
	}
}

// TestGlobalRateLimitDisabled 未配置全局限流时不影响原有按 IP 逻辑
func TestGlobalRateLimitDisabled(t *testing.T) {
	limiter := New(Config{
		MaxFailures:           2,
		BlockTime:              time.Minute,
		WindowTime:            time.Minute,
		GlobalMaxPerMinute:    0,  // 关闭
		GlobalBlockTime:       0,
		CredentialMaxFailures: 0,
	})

	limiter.RecordFailure("1.1.1.1", "wrong")
	limiter.RecordFailure("1.1.1.1", "wrong")

	if !limiter.IsBlocked("1.1.1.1", "") {
		t.Error("expected IP blocked")
	}
	// 其他 IP 不受影响
	if limiter.IsBlocked("2.2.2.2", "") {
		t.Error("other IP should not be blocked when global is off")
	}
}
