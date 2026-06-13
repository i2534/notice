package handlers

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"notice-server/broker"
	"notice-server/badgeropts"
	"notice-server/config"
	"notice-server/store"
)

func TestStatusHandler(t *testing.T) {
	bp := badgeropts.Params{}.Normalized()
	storeManager := store.NewManager("/tmp/notice-test-status", true, bp)
	if storeManager == nil {
		t.Fatal("storeManager is nil")
	}
	defer storeManager.Close()

	br := broker.New("notice", broker.Config{
		SessionExpiry:  60,
		MessageExpiry:  60,
		AuthToken:      "test-token",
		StorageEnabled: false,
		Badger:         bp,
	}, storeManager)
	if err := br.Start("127.0.0.1:0", "127.0.0.1:0"); err != nil {
		t.Fatal(err)
	}
	defer br.Close()

	handler := StatusHandler(br, storeManager, "v1.2.3", "2026-06-13T10:00:00Z")

	req := httptest.NewRequest(http.MethodGet, "/status", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status code: got %d, want %d", rec.Code, http.StatusOK)
	}

	var resp map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if resp["status"] != "ok" {
		t.Errorf("status: got %v, want 'ok'", resp["status"])
	}
	if resp["version"] != "v1.2.3" {
		t.Errorf("version: got %v, want 'v1.2.3'", resp["version"])
	}
	if resp["build_time"] != "2026-06-13T10:00:00Z" {
		t.Errorf("build_time: got %v, want '2026-06-13T10:00:00Z'", resp["build_time"])
	}
}

func TestMessagesHandler(t *testing.T) {
	bp := badgeropts.Params{}.Normalized()
	storeManager := store.NewManager("/tmp/notice-test-msgs", true, bp)
	if storeManager == nil {
		t.Fatal("storeManager is nil")
	}
	defer storeManager.Close()

	cfg := &config.Config{
		Auth: config.AuthConfig{Token: "test-token"},
		Message: config.MessageConfig{
			MaxTitleLength:   50,
			MaxContentLength: 1024,
		},
	}

	handler := MessagesHandler(storeManager, cfg)

	// 先插入一些测试消息
	for i := 0; i < 3; i++ {
		_, err := storeManager.Save("test-token", "notice", "Test", "Content", nil)
		if err != nil {
			t.Fatal(err)
		}
	}

	// 等待写入完成
	time.Sleep(50 * time.Millisecond)

	req := httptest.NewRequest(http.MethodGet, "/messages?page_size=10", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status code: got %d, want %d", rec.Code, http.StatusOK)
	}

	var resp map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}

	if resp["success"] != true {
		t.Errorf("success: got %v, want true", resp["success"])
	}

	data := resp["data"].(map[string]any)
	messages := data["messages"].([]any)
	if len(messages) != 3 {
		t.Errorf("messages count: got %d, want 3", len(messages))
	}
}
