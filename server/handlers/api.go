package handlers

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"

	"notice-server/broker"
	"notice-server/config"
	"notice-server/store"
)

// HealthHandler 健康检查
func HealthHandler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write([]byte(`{"status":"ok"}`))
}

// StatusHandler 状态检查
func StatusHandler(b *broker.Broker, m *store.Manager) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)

		clientCount := b.ClientCount()
		// 状态页不返回消息数（因为需要 token）
		fmt.Fprintf(w, `{"status":"ok","clients":%d}`, clientCount)
	}
}

// MessagesHandler 消息历史查询（游标分页）
// 参数: ?before_id=123&page_size=20
func MessagesHandler(m *store.Manager, cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")

		// 获取并校验 Token
		token := ExtractToken(r)
		if token == "" || token != cfg.Auth.Token {
			w.WriteHeader(http.StatusUnauthorized)
			json.NewEncoder(w).Encode(map[string]any{
				"success": false,
				"message": "认证失败",
			})
			return
		}

		// 解析分页参数
		pageSize, _ := strconv.Atoi(r.URL.Query().Get("page_size"))
		if pageSize < 1 {
			pageSize = 20
		}

		var beforeID uint64
		if s := r.URL.Query().Get("before_id"); s != "" {
			beforeID, _ = strconv.ParseUint(s, 10, 64)
		}

		// 使用 token 查询该用户的消息
		result, err := m.List(token, beforeID, pageSize)
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			json.NewEncoder(w).Encode(map[string]any{
				"success": false,
				"message": "查询失败: " + err.Error(),
			})
			return
		}

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"data":    result,
		})
	}
}

// ExtractToken 从请求中提取 Token
func ExtractToken(r *http.Request) string {
	// Authorization: Bearer <token>
	if auth := r.Header.Get("Authorization"); auth != "" {
		if len(auth) > 7 && auth[:7] == "Bearer " {
			return auth[7:]
		}
		return auth
	}

	// X-Auth-Token: <token>
	if token := r.Header.Get("X-Auth-Token"); token != "" {
		return token
	}

	// ?token=<token>
	if token := r.URL.Query().Get("token"); token != "" {
		return token
	}

	return ""
}

// ValidateToken 校验 Token
func ValidateToken(r *http.Request, token string) bool {
	return ExtractToken(r) == token
}
