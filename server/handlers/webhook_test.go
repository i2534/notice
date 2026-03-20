package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"notice-server/broker"
	"notice-server/config"
	"notice-server/logger"
	"notice-server/ratelimit"
)

const testAttackerIP = "192.168.1.100"

// TestWebhookBruteForceToken 暴力破解 token：连续用错误 token 请求，超过 max_failures 后应返回 429 封禁
func TestWebhookBruteForceToken(t *testing.T) {
	cfg := &config.Config{
		Auth: config.AuthConfig{Token: "correct-token"},
		RateLimit: config.RateLimitConfig{
			MaxFailures: 3,
			BlockTime:   2,   // 秒，测试用短封禁
			WindowTime:  60,  // 秒
		},
		Message: config.MessageConfig{
			MaxTitleLength:   50,
			MaxContentLength: 1024,
		},
	}
	limiter := ratelimit.New(ratelimit.Config{
		MaxFailures: cfg.RateLimit.MaxFailures,
		BlockTime:   time.Duration(cfg.RateLimit.BlockTime) * time.Second,
		WindowTime:  time.Duration(cfg.RateLimit.WindowTime) * time.Second,
	})
	h := NewWebhookHandler(nil, cfg, limiter)

	body := []byte(`{"content":"test"}`)

	// 前 MaxFailures 次：错误 token，应返回 401
	for i := 0; i < cfg.RateLimit.MaxFailures; i++ {
		req := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
		req.RemoteAddr = testAttackerIP + ":12345"
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer wrong-token")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Errorf("request %d: got status %d, want 401 Unauthorized", i+1, rec.Code)
		}
	}

	// 下一次：IP 已封禁，应返回 429
	req := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
	req.RemoteAddr = testAttackerIP + ":12345"
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer wrong-token")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("after %d failures: got status %d, want 429 Too Many Requests", cfg.RateLimit.MaxFailures, rec.Code)
	}

	// 封禁期内即使用正确 token 也应返回 429
	reqOk := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
	reqOk.RemoteAddr = testAttackerIP + ":12345"
	reqOk.Header.Set("Content-Type", "application/json")
	reqOk.Header.Set("Authorization", "Bearer correct-token")
	recOk := httptest.NewRecorder()
	h.ServeHTTP(recOk, reqOk)
	if recOk.Code != http.StatusTooManyRequests {
		t.Errorf("blocked IP with correct token: got status %d, want 429", recOk.Code)
	}
}

// TestWebhookBruteForceTokenUnblockAfterBlockTime 封禁过期后同一 IP 可再次尝试（仍会 401，但不再 429）
func TestWebhookBruteForceTokenUnblockAfterBlockTime(t *testing.T) {
	blockTime := 100 * time.Millisecond
	cfg := &config.Config{
		Auth: config.AuthConfig{Token: "secret"},
		RateLimit: config.RateLimitConfig{
			MaxFailures: 2,
			BlockTime:   1,  // 1 秒，但下面用短 limiter 覆盖
			WindowTime:  60,
		},
		Message: config.MessageConfig{
			MaxTitleLength:   50,
			MaxContentLength: 1024,
		},
	}
	limiter := ratelimit.New(ratelimit.Config{
		MaxFailures: 2,
		BlockTime:   blockTime,
		WindowTime:  60 * time.Second,
	})
	h := NewWebhookHandler(nil, cfg, limiter)
	body := []byte(`{"content":"x"}`)

	// 触发封禁
	for i := 0; i < 2; i++ {
		req := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
		req.RemoteAddr = "10.0.0.1:0"
		req.Header.Set("Authorization", "Bearer wrong")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("request %d: got %d, want 401", i+1, rec.Code)
		}
	}
	reqBlock := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
	reqBlock.RemoteAddr = "10.0.0.1:0"
	reqBlock.Header.Set("Authorization", "Bearer wrong")
	recBlock := httptest.NewRecorder()
	h.ServeHTTP(recBlock, reqBlock)
	if recBlock.Code != http.StatusTooManyRequests {
		t.Fatalf("expected 429 after 2 failures, got %d", recBlock.Code)
	}

	// 等待封禁过期
	time.Sleep(blockTime + 50*time.Millisecond)

	// 再次错误 token 应得到 401 而非 429（已解封，重新计失败次数）
	reqAfter := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
	reqAfter.RemoteAddr = "10.0.0.1:0"
	reqAfter.Header.Set("Authorization", "Bearer wrong")
	recAfter := httptest.NewRecorder()
	h.ServeHTTP(recAfter, reqAfter)
	if recAfter.Code != http.StatusUnauthorized {
		t.Errorf("after block time: got %d, want 401 (unblocked, wrong token)", recAfter.Code)
	}
}

// TestWebhookGlobalRateLimit 全局限流：多 IP 累计失败达上限后，任意 IP（含正确 token）均 429，冷却后恢复
func TestWebhookGlobalRateLimit(t *testing.T) {
	globalBlockTime := 200 * time.Millisecond
	cfg := &config.Config{
		Auth: config.AuthConfig{Token: "secret-token"},
		RateLimit: config.RateLimitConfig{
			MaxFailures:            10,  // 单 IP 放宽，避免先于全局触发
			BlockTime:              60,
			WindowTime:             60,
			GlobalMaxPerMinute:     3,
			GlobalBlockTime:        int(globalBlockTime / time.Millisecond),
			CredentialMaxFailures:  0,
		},
		Message: config.MessageConfig{
			MaxTitleLength:   50,
			MaxContentLength: 1024,
		},
	}
	limiter := ratelimit.New(ratelimit.Config{
		MaxFailures:            cfg.RateLimit.MaxFailures,
		BlockTime:              time.Duration(cfg.RateLimit.BlockTime) * time.Second,
		WindowTime:             time.Duration(cfg.RateLimit.WindowTime) * time.Second,
		GlobalMaxPerMinute:     cfg.RateLimit.GlobalMaxPerMinute,
		GlobalBlockTime:        globalBlockTime,
		CredentialMaxFailures:  cfg.RateLimit.CredentialMaxFailures,
	})
	h := NewWebhookHandler(nil, cfg, limiter)
	body := []byte(`{"content":"x"}`)

	// 3 个不同 IP 各用错误 token 请求 1 次，累计 3 次触发全局
	for i := 0; i < 3; i++ {
		req := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
		req.RemoteAddr = fmt.Sprintf("10.0.0.%d:0", i+1)
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer wrong")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("request %d: got %d, want 401", i+1, rec.Code)
		}
	}

	// 新 IP + 正确 token 也应 429（全局限流）
	reqCorrect := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
	reqCorrect.RemoteAddr = "10.0.0.99:0"
	reqCorrect.Header.Set("Content-Type", "application/json")
	reqCorrect.Header.Set("Authorization", "Bearer secret-token")
	recCorrect := httptest.NewRecorder()
	h.ServeHTTP(recCorrect, reqCorrect)
	if recCorrect.Code != http.StatusTooManyRequests {
		t.Errorf("correct token during global block: got %d, want 429", recCorrect.Code)
	}

	time.Sleep(globalBlockTime + 50*time.Millisecond)

	// 冷却后：错误 token 应得到 401（全局限流已解除，不再 429）
	reqAfter := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
	reqAfter.RemoteAddr = "10.0.0.99:0"
	reqAfter.Header.Set("Content-Type", "application/json")
	reqAfter.Header.Set("Authorization", "Bearer wrong")
	recAfter := httptest.NewRecorder()
	h.ServeHTTP(recAfter, reqAfter)
	if recAfter.Code != http.StatusUnauthorized {
		t.Errorf("after global block time (wrong token): got %d, want 401", recAfter.Code)
	}
}

// TestWebhookRequestBodyTooLarge 请求体超过限制时返回 413
func TestWebhookRequestBodyTooLarge(t *testing.T) {
	cfg := &config.Config{
		Auth: config.AuthConfig{Token: "secret"},
		HTTP: config.HTTPConfig{MaxRequestBodyBytes: 10},
		Message: config.MessageConfig{
			MaxTitleLength:   50,
			MaxContentLength: 1024,
		},
	}
	h := NewWebhookHandler(nil, cfg, nil)
	body := []byte(`{"content":"this body is way longer than 10 bytes"}`)
	req := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
	req.RemoteAddr = "1.2.3.4:0"
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer secret")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Errorf("got status %d, want 413 Request Entity Too Large", rec.Code)
	}
}

// TestWebhookZeroMaxLengthAcceptsLongContent max_title/max_content 为 0 时不截断、校验通过且可发布
func TestWebhookZeroMaxLengthAcceptsLongContent(t *testing.T) {
	if logger.Get() == nil {
		_, _ = logger.Init(logger.Config{ConsoleLevel: "off", FileLevel: "off"})
	}
	br := broker.New("notice", broker.Config{
		SessionExpiry:  60,
		MessageExpiry:  60,
		AuthToken:      "test-tok",
		StorageEnabled: false,
	}, nil)
	if err := br.Start("127.0.0.1:0", "127.0.0.1:0"); err != nil {
		t.Fatal(err)
	}
	defer br.Close()
	time.Sleep(80 * time.Millisecond)

	cfg := &config.Config{
		Auth:    config.AuthConfig{Token: "test-tok"},
		MQTT:    config.MQTTConfig{Topic: "notice"},
		Message: config.MessageConfig{MaxTitleLength: 0, MaxContentLength: 0},
	}
	h := NewWebhookHandler(br, cfg, nil)

	long := strings.Repeat("汉", 3000)
	body, err := json.Marshal(map[string]string{"content": long})
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer test-tok")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("want 200, got %d: %s", rec.Code, rec.Body.String())
	}
}

// TestWebhookInvalidTopic 非法 topic（路径穿越或非法字符）返回 400
func TestWebhookInvalidTopic(t *testing.T) {
	cfg := &config.Config{
		Auth: config.AuthConfig{Token: "secret"},
		Message: config.MessageConfig{
			MaxTitleLength:   50,
			MaxContentLength: 1024,
		},
	}
	h := NewWebhookHandler(nil, cfg, nil)
	for _, topic := range []string{"../etc/passwd", "a/../b", "<script>"} {
		body := []byte(fmt.Sprintf(`{"content":"x","topic":%q}`, topic))
		req := httptest.NewRequest(http.MethodPost, "/webhook", bytes.NewReader(body))
		req.RemoteAddr = "1.2.3.4:0"
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer secret")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Errorf("topic %q: got status %d, want 400", topic, rec.Code)
		}
	}
}
