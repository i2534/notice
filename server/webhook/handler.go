package webhook

import (
	"encoding/json"
	"io"
	"net/http"
	"time"

	"notice-server/broker"
	"notice-server/config"
	"notice-server/logger"
	"notice-server/ratelimit"
)

// Request Webhook 请求结构
type Request struct {
	Title   string `json:"title"`           // 消息标题
	Content string `json:"content"`         // 消息内容（必填）
	Topic   string `json:"topic,omitempty"` // 可选：指定主题
	Extra   any    `json:"extra,omitempty"` // 可选：额外数据
}

// Response Webhook 响应
type Response struct {
	Success bool   `json:"success"`
	Message string `json:"message"`
	Clients int    `json:"clients,omitempty"` // 当前连接的客户端数
}

// Handler Webhook 处理器
type Handler struct {
	broker  *broker.Broker
	config  *config.Config
	limiter *ratelimit.Limiter
}

// NewHandler 创建新的 Webhook 处理器
func NewHandler(b *broker.Broker, cfg *config.Config) *Handler {
	limiter := ratelimit.New(ratelimit.Config{
		MaxFailures: cfg.RateLimitMaxFailures,
		BlockTime:   time.Duration(cfg.RateLimitBlockTime) * time.Second,
		WindowTime:  time.Duration(cfg.RateLimitWindowTime) * time.Second,
	})

	return &Handler{
		broker:  b,
		config:  cfg,
		limiter: limiter,
	}
}

// ServeHTTP 处理 Webhook 请求
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	clientIP := ratelimit.GetClientIP(r)

	// 检查 IP 是否被封禁
	if h.limiter.IsBlocked(clientIP) {
		logger.Warn("请求被拒绝，IP 已封禁", "ip", clientIP)
		h.sendError(w, http.StatusTooManyRequests, "请求过于频繁，请稍后再试")
		return
	}

	// 只接受 POST 请求
	if r.Method != http.MethodPost {
		logger.Warn("Webhook 收到非 POST 请求", "method", r.Method)
		h.sendError(w, http.StatusMethodNotAllowed, "只支持 POST 请求")
		return
	}

	// Token 校验
	if !h.validateToken(r) {
		h.limiter.RecordFailure(clientIP)
		logger.Warn("Webhook Token 校验失败", "ip", clientIP)
		h.sendError(w, http.StatusUnauthorized, "认证失败")
		return
	}

	// 认证成功，清除失败记录
	h.limiter.RecordSuccess(clientIP)

	// 读取请求体
	body, err := io.ReadAll(r.Body)
	if err != nil {
		logger.Error("读取请求体失败", "error", err)
		h.sendError(w, http.StatusBadRequest, "读取请求体失败")
		return
	}
	defer r.Body.Close()

	logger.Debug("收到 Webhook 请求", "body_size", len(body))

	// 解析消息
	var req Request
	if err := json.Unmarshal(body, &req); err != nil {
		logger.Warn("JSON 解析失败", "error", err)
		h.sendError(w, http.StatusBadRequest, "JSON 解析失败")
		return
	}

	// 验证必填字段
	if req.Content == "" {
		logger.Warn("content 字段为空")
		h.sendError(w, http.StatusBadRequest, "content 字段不能为空")
		return
	}

	// 构建推送消息
	msg := broker.Message{
		Title:     req.Title,
		Content:   req.Content,
		Extra:     req.Extra,
		Timestamp: time.Now(),
	}

	// 发布到 MQTT
	topic := req.Topic
	if topic == "" {
		topic = h.config.MQTTTopic
	}

	if err := h.broker.Publish(topic, msg); err != nil {
		logger.Error("消息发布失败", "topic", topic, "error", err)
		h.sendError(w, http.StatusInternalServerError, "消息推送失败")
		return
	}

	clientCount := h.broker.ClientCount()
	logger.Info("消息推送成功", "topic", topic, "title", req.Title, "clients", clientCount)

	// 成功响应
	h.sendSuccess(w, "消息推送成功", clientCount)
}

func (h *Handler) sendError(w http.ResponseWriter, status int, message string) {
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(Response{Success: false, Message: message})
}

func (h *Handler) sendSuccess(w http.ResponseWriter, message string, clients int) {
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(Response{Success: true, Message: message, Clients: clients})
}

// validateToken 校验请求中的 Token
// 支持以下方式传入：
//   - Header: Authorization: Bearer <token>
//   - Header: X-Auth-Token: <token>
//   - Query:  ?token=<token>
func (h *Handler) validateToken(r *http.Request) bool {
	token := h.config.AuthToken

	// 1. 检查 Authorization Header
	if auth := r.Header.Get("Authorization"); auth != "" {
		// Bearer token
		if len(auth) > 7 && auth[:7] == "Bearer " {
			if auth[7:] == token {
				return true
			}
		}
		// 直接传 token
		if auth == token {
			return true
		}
	}

	// 2. 检查 X-Auth-Token Header
	if r.Header.Get("X-Auth-Token") == token {
		return true
	}

	// 3. 检查 Query 参数
	if r.URL.Query().Get("token") == token {
		return true
	}

	return false
}
