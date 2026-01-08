package broker

import (
	"encoding/json"
	"math"
	"time"

	mqtt "github.com/mochi-mqtt/server/v2"
	"github.com/mochi-mqtt/server/v2/listeners"
	"github.com/mochi-mqtt/server/v2/packets"

	"notice-server/logger"
)

// Message 推送消息结构
type Message struct {
	Title     string    `json:"title"`
	Content   string    `json:"content"`
	Extra     any       `json:"extra,omitempty"`
	Timestamp time.Time `json:"timestamp"`
}

// Config Broker 配置
type Config struct {
	SessionExpiry uint32 // 会话过期时间（秒）
	MessageExpiry uint32 // 消息过期时间（秒）
	AuthToken     string // 认证 Token，为空则不校验
}

// Broker MQTT Broker 服务
type Broker struct {
	server *mqtt.Server
	topic  string
	config Config
}

// New 创建新的 Broker
func New(topic string, cfg Config) *Broker {
	return &Broker{
		topic:  topic,
		config: cfg,
	}
}

// Start 启动 MQTT Broker
func (b *Broker) Start(tcpAddr, wsAddr string) error {
	// 使用我们的 logger
	mqttLogger := logger.Get()

	b.server = mqtt.New(&mqtt.Options{
		InlineClient: true,
		Logger:       mqttLogger,
		Capabilities: &mqtt.Capabilities{
			MaximumClients:               math.MaxInt64,                 // 最大客户端数（无限制）
			MaximumSessionExpiryInterval: b.config.SessionExpiry,        // 会话过期时间
			MaximumClientWritesPending:   1024,                          // 最大待写入消息数
			MaximumMessageExpiryInterval: int64(b.config.MessageExpiry), // 消息过期时间
			ReceiveMaximum:               1024,                          // 最大接收队列
			MaximumInflight:              8192,                          // 最大飞行中消息数
		},
		ClientNetWriteBufferSize: 4096, // 客户端写缓冲区
		ClientNetReadBufferSize:  4096, // 客户端读缓冲区
	})

	logger.Info("MQTT 配置加载",
		"session_expiry", b.config.SessionExpiry,
		"message_expiry", b.config.MessageExpiry,
	)

	// 启用 Token 认证
	if err := b.server.AddHook(&AuthHook{token: b.config.AuthToken}, nil); err != nil {
		return err
	}
	logger.Info("MQTT Token 认证已启用")

	// 添加日志钩子
	if err := b.server.AddHook(new(LogHook), nil); err != nil {
		return err
	}

	// TCP 监听器
	tcp := listeners.NewTCP(listeners.Config{
		ID:      "tcp",
		Address: tcpAddr,
	})
	if err := b.server.AddListener(tcp); err != nil {
		return err
	}
	logger.Info("MQTT TCP 监听", "addr", tcpAddr)

	// WebSocket 监听器
	ws := listeners.NewWebsocket(listeners.Config{
		ID:      "ws",
		Address: wsAddr,
	})
	if err := b.server.AddListener(ws); err != nil {
		return err
	}
	logger.Info("MQTT WebSocket 监听", "addr", wsAddr)

	// 启动服务器
	go func() {
		if err := b.server.Serve(); err != nil {
			logger.Error("MQTT Broker 错误", "error", err)
		}
	}()

	logger.Info("MQTT Broker 已启动")
	return nil
}

// Publish 发布消息到指定主题
func (b *Broker) Publish(topic string, msg Message) error {
	payload, err := json.Marshal(msg)
	if err != nil {
		return err
	}

	return b.server.Publish(topic, payload, false, 1)
}

// PublishToDefault 发布消息到默认主题
func (b *Broker) PublishToDefault(msg Message) error {
	return b.Publish(b.topic, msg)
}

// ClientCount 获取当前连接的客户端数量（排除内置客户端）
func (b *Broker) ClientCount() int {
	count := 0
	for _, cl := range b.server.Clients.GetAll() {
		// 排除内置客户端（以 "inline" 开头）
		if cl.ID != "inline" && len(cl.ID) > 0 && cl.ID[0] != '$' {
			count++
		}
	}
	return count
}

// Close 关闭 Broker
func (b *Broker) Close() error {
	return b.server.Close()
}

// LogHook 日志钩子
type LogHook struct {
	mqtt.HookBase
}

func (h *LogHook) ID() string {
	return "log-hook"
}

func (h *LogHook) Provides(b byte) bool {
	return b == mqtt.OnConnect ||
		b == mqtt.OnDisconnect ||
		b == mqtt.OnSubscribed ||
		b == mqtt.OnPublished
}

func (h *LogHook) OnConnect(cl *mqtt.Client, pk packets.Packet) error {
	logger.Info("MQTT 客户端连接", "client_id", cl.ID)
	return nil
}

func (h *LogHook) OnDisconnect(cl *mqtt.Client, err error, expire bool) {
	if err != nil {
		logger.Info("MQTT 客户端断开", "client_id", cl.ID, "expired", expire, "error", err)
	} else {
		logger.Info("MQTT 客户端断开", "client_id", cl.ID, "expired", expire)
	}
}

func (h *LogHook) OnSubscribed(cl *mqtt.Client, pk packets.Packet, reasonCodes []byte) {
	for _, sub := range pk.Filters {
		logger.Debug("MQTT 客户端订阅", "client_id", cl.ID, "topic", sub.Filter)
	}
}

func (h *LogHook) OnPublished(cl *mqtt.Client, pk packets.Packet) {
	logger.Debug("MQTT 消息发布", "topic", pk.TopicName, "payload_size", len(pk.Payload))
}

// AuthHook Token 认证钩子
type AuthHook struct {
	mqtt.HookBase
	token string
}

func (h *AuthHook) ID() string {
	return "token-auth"
}

func (h *AuthHook) Provides(b byte) bool {
	return b == mqtt.OnConnectAuthenticate || b == mqtt.OnACLCheck
}

// OnConnectAuthenticate 连接认证
// MQTT 客户端通过 username 或 password 传入 token
func (h *AuthHook) OnConnectAuthenticate(cl *mqtt.Client, pk packets.Packet) bool {
	// 支持以下方式传入 token:
	// 1. username = token
	// 2. password = token
	// 3. username = "token", password = <actual_token>

	username := string(pk.Connect.Username)
	password := string(pk.Connect.Password)

	// 方式 1: username 直接是 token
	if username == h.token {
		logger.Debug("MQTT 认证成功 (username)", "client_id", cl.ID)
		return true
	}

	// 方式 2: password 是 token
	if password == h.token {
		logger.Debug("MQTT 认证成功 (password)", "client_id", cl.ID)
		return true
	}

	logger.Warn("MQTT 认证失败", "client_id", cl.ID, "username", username)
	return false
}

// OnACLCheck ACL 检查，允许所有已认证用户
func (h *AuthHook) OnACLCheck(cl *mqtt.Client, topic string, write bool) bool {
	// 已通过认证的客户端允许所有操作
	return true
}
