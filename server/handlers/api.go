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

		// 解析分页参数（校验防注入）
		pageSize, _ := strconv.Atoi(r.URL.Query().Get("page_size"))
		pageSize = ValidatePageSize(pageSize)

		var beforeID uint64
		if s := r.URL.Query().Get("before_id"); s != "" {
			var ok bool
			beforeID, ok = ParseUintParam(s)
			if !ok {
				w.WriteHeader(http.StatusBadRequest)
				json.NewEncoder(w).Encode(map[string]any{
					"success": false,
					"message": "参数 before_id 不合法",
				})
				return
			}
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

// ExtractToken 从请求中提取 Token（长度受限，防滥用）
func ExtractToken(r *http.Request) string {
	var token string
	if auth := r.Header.Get("Authorization"); auth != "" {
		if len(auth) > 7 && auth[:7] == "Bearer " {
			token = auth[7:]
		} else {
			token = auth
		}
	} else if token = r.Header.Get("X-Auth-Token"); token == "" {
		token = r.URL.Query().Get("token")
	}
	return TruncateToken(token, MaxTokenLength)
}

// ClientsHandler 客户端列表
func ClientsHandler(b *broker.Broker, cfg *config.Config) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")

		token := ExtractToken(r)
		if token == "" || token != cfg.Auth.Token {
			w.WriteHeader(http.StatusUnauthorized)
			json.NewEncoder(w).Encode(map[string]any{
				"success": false,
				"message": "认证失败",
			})
			return
		}

		type clientInfo struct {
			ID            string   `json:"id"`
			Remote        string   `json:"remote"`
			Subscriptions []string `json:"subscriptions"`
		}

		var clients []clientInfo
		for _, cl := range b.Clients() {
			if cl.ID == "inline" || len(cl.ID) == 0 || cl.ID[0] == '$' {
				continue
			}
			info := clientInfo{
				ID:     cl.ID,
				Remote: cl.Net.Remote,
			}
			for filter := range cl.State.Subscriptions.GetAll() {
				info.Subscriptions = append(info.Subscriptions, filter)
			}
			clients = append(clients, info)
		}

		if clients == nil {
			clients = []clientInfo{}
		}

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]any{
			"success": true,
			"data":    clients,
		})
	}
}

// ValidateToken 校验 Token
func ValidateToken(r *http.Request, token string) bool {
	return ExtractToken(r) == token
}
