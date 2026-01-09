package handlers

import (
	"bytes"
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

// WebhookHandler Webhook 处理器
type WebhookHandler struct {
	broker  *broker.Broker
	config  *config.Config
	limiter *ratelimit.Limiter
}

// NewWebhookHandler 创建新的 Webhook 处理器
func NewWebhookHandler(b *broker.Broker, cfg *config.Config) *WebhookHandler {
	limiter := ratelimit.New(ratelimit.Config{
		MaxFailures: cfg.RateLimit.MaxFailures,
		BlockTime:   time.Duration(cfg.RateLimit.BlockTime) * time.Second,
		WindowTime:  time.Duration(cfg.RateLimit.WindowTime) * time.Second,
	})

	return &WebhookHandler{
		broker:  b,
		config:  cfg,
		limiter: limiter,
	}
}

// ServeHTTP 处理 Webhook 请求
func (h *WebhookHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
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
	if !ValidateToken(r, h.config.Auth.Token) {
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

	// 预处理：修复 JSON 字符串中的换行符
	// 将字符串值中的真实换行符转换为 \n 转义序列
	body = fixJSONNewlines(body)

	// 解析消息
	var req Request
	if err := json.Unmarshal(body, &req); err != nil {
		logger.Warn("JSON 解析失败", "error", err, "body", string(body))
		h.sendError(w, http.StatusBadRequest, "JSON 解析失败: "+err.Error())
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
		topic = h.config.MQTT.Topic
	}

	if err := h.broker.Publish(topic, msg); err != nil {
		logger.Error("消息发布失败", "topic", topic, "error", err)
		h.sendError(w, http.StatusInternalServerError, "消息推送失败")
		return
	}

	// 消息存储由 broker 的 MessageStoreHook 自动处理

	clientCount := h.broker.ClientCount()
	logger.Info("消息推送成功", "topic", topic, "title", req.Title, "clients", clientCount)

	// 成功响应
	h.sendSuccess(w, "消息推送成功", clientCount)
}

func (h *WebhookHandler) sendError(w http.ResponseWriter, status int, message string) {
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(Response{Success: false, Message: message})
}

func (h *WebhookHandler) sendSuccess(w http.ResponseWriter, message string, clients int) {
	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(Response{Success: true, Message: message, Clients: clients})
}

// fixJSONNewlines 修复 JSON 字符串值中的真实换行符
// 将字符串内的 \n \r \t 等控制字符转换为对应的转义序列
func fixJSONNewlines(data []byte) []byte {
	// 如果不包含换行符，直接返回
	if !bytes.ContainsAny(data, "\n\r\t") {
		return data
	}

	var result bytes.Buffer
	inString := false
	escaped := false

	for i := range data {
		c := data[i]

		if escaped {
			// 上一个字符是反斜杠，当前字符是转义的一部分
			result.WriteByte(c)
			escaped = false
			continue
		}

		if c == '\\' && inString {
			// 遇到反斜杠，标记下一个字符为转义
			result.WriteByte(c)
			escaped = true
			continue
		}

		if c == '"' {
			// 切换字符串状态
			inString = !inString
			result.WriteByte(c)
			continue
		}

		if inString {
			// 在字符串内，转换控制字符
			switch c {
			case '\n':
				result.WriteString("\\n")
			case '\r':
				result.WriteString("\\r")
			case '\t':
				result.WriteString("\\t")
			default:
				result.WriteByte(c)
			}
		} else {
			result.WriteByte(c)
		}
	}

	return result.Bytes()
}
