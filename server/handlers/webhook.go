package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"

	"notice-server/broker"
	"notice-server/config"
	"notice-server/logger"
	"notice-server/ratelimit"
)

// Request Webhook 请求结构
type Request struct {
	Title   string `json:"title"`            // 消息标题
	Content string `json:"content"`          // 消息内容（必填）；图片 URL 等可放在 content 中
	Topic   string `json:"topic,omitempty"`  // 可选：指定主题
	Extra   any    `json:"extra,omitempty"`  // 可选：额外数据
	Client  string `json:"client,omitempty"` // 可选：发送端标识，如 web / android / cli
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

// NewWebhookHandler 创建新的 Webhook 处理器。limiter 可与 MQTT 认证共用，传 nil 则内部新建一个。
func NewWebhookHandler(b *broker.Broker, cfg *config.Config, limiter *ratelimit.Limiter) *WebhookHandler {
	if limiter == nil {
		limiter = ratelimit.New(ratelimit.Config{
			MaxFailures:           cfg.RateLimit.MaxFailures,
			BlockTime:             time.Duration(cfg.RateLimit.BlockTime) * time.Second,
			WindowTime:            time.Duration(cfg.RateLimit.WindowTime) * time.Second,
			GlobalMaxPerMinute:    cfg.RateLimit.GlobalMaxPerMinute,
			GlobalBlockTime:       time.Duration(cfg.RateLimit.GlobalBlockTime) * time.Second,
			CredentialMaxFailures: cfg.RateLimit.CredentialMaxFailures,
		})
	}
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

	// 检查是否因限流被拒（按 IP、按尝试的凭证、全局）
	attemptedToken := ExtractToken(r)
	if h.limiter.IsBlocked(clientIP, attemptedToken) {
		logger.Warn("请求被拒绝，已触发限流", "ip", clientIP)
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
		h.limiter.RecordFailure(clientIP, attemptedToken)
		logger.Warn("Webhook Token 校验失败", "ip", clientIP)
		h.sendError(w, http.StatusUnauthorized, "认证失败")
		return
	}

	// 认证成功，清除失败记录
	h.limiter.RecordSuccess(clientIP)

	// 请求体大小限制（防 DoS）
	maxBody := h.config.HTTP.MaxRequestBodyBytes
	if maxBody <= 0 {
		maxBody = DefaultMaxBody
	}
	r.Body = http.MaxBytesReader(w, r.Body, int64(maxBody))

	// 读取请求体
	body, err := io.ReadAll(r.Body)
	if err != nil {
		if err.Error() == "http: request body too large" {
			h.sendError(w, http.StatusRequestEntityTooLarge, "请求体过大")
			return
		}
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
		bodyPreview := string(body)
		if len(bodyPreview) > 200 {
			bodyPreview = bodyPreview[:200] + "..."
		}
		logger.Warn("JSON 解析失败", "error", err, "body_preview", bodyPreview)
		h.sendError(w, http.StatusBadRequest, "JSON 解析失败: "+err.Error())
		return
	}

	// 验证必填字段
	if req.Content == "" {
		logger.Warn("content 字段为空")
		h.sendError(w, http.StatusBadRequest, "content 字段不能为空")
		return
	}

	// 安全校验：主题禁止路径穿越与非法字符
	if req.Topic != "" {
		topic, ok := ValidateTopic(req.Topic, MaxTopicLength)
		if !ok {
			logger.Warn("非法 topic", "topic", req.Topic)
			h.sendError(w, http.StatusBadRequest, "主题格式不合法")
			return
		}
		req.Topic = topic
	}
	// 去除控制字符并限制长度（防 XSS/注入）
	maxTitle := h.config.Message.MaxTitleLength
	if maxTitle <= 0 {
		maxTitle = 256
	}
	req.Title = SanitizeString(req.Title, maxTitle)
	maxContent := h.config.Message.MaxContentLength
	if maxContent <= 0 {
		maxContent = 1024
	}
	req.Content = SanitizeContent(req.Content, maxContent)
	if req.Content == "" {
		h.sendError(w, http.StatusBadRequest, "content 字段不能为空")
		return
	}
	req.Client = SanitizeString(req.Client, 64)

	// 仅做 Token 校验，不发布到 MQTT（Web 端登录时用）
	if req.Content == "__auth_check__" {
		h.sendSuccess(w, "认证成功", h.broker.ClientCount())
		return
	}

	// 验证字段长度
	if h.config.Message.MaxTitleLength > 0 && utf8.RuneCountInString(req.Title) > h.config.Message.MaxTitleLength {
		logger.Warn("title 超出长度限制", "size", utf8.RuneCountInString(req.Title), "max", h.config.Message.MaxTitleLength)
		h.sendError(w, http.StatusBadRequest, fmt.Sprintf("title 长度不能超过 %d 字符", h.config.Message.MaxTitleLength))
		return
	}

	if h.config.Message.MaxContentLength > 0 && utf8.RuneCountInString(req.Content) > h.config.Message.MaxContentLength {
		logger.Warn("content 超出长度限制", "size", utf8.RuneCountInString(req.Content), "max", h.config.Message.MaxContentLength)
		h.sendError(w, http.StatusBadRequest, fmt.Sprintf("content 长度不能超过 %d 字符", h.config.Message.MaxContentLength))
		return
	}

	// 构建推送消息
	client := strings.TrimSpace(req.Client)
	if client == "" {
		client = "webhook" // 经 Webhook 发送且未指定时
	}
	msg := broker.Message{
		Title:     req.Title,
		Content:   req.Content,
		Extra:     req.Extra,
		Timestamp: time.Now(),
		Client:    client,
	}

	// 发布到 MQTT（订阅可用通配符 notice/#，发布必须用具体主题）
	topic := req.Topic
	if topic == "" {
		topic = h.config.MQTT.Topic
	}
	topic = topicForPublish(topic)

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

// topicForPublish 将订阅用主题转为可发布主题（MQTT 禁止向含 #/+ 的主题发布）
func topicForPublish(topic string) string {
	topic = strings.TrimSpace(topic)
	if i := strings.Index(topic, "#"); i >= 0 {
		topic = strings.TrimSuffix(strings.TrimSpace(topic[:i]), "/")
		if topic == "" {
			topic = "notice"
		}
	}
	if strings.Contains(topic, "+") {
		parts := strings.Split(topic, "/")
		for i, p := range parts {
			if p == "+" {
				parts[i] = "reply"
			}
		}
		topic = strings.Join(parts, "/")
	}
	return topic
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
