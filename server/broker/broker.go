package broker

import (
	"encoding/json"
	"math"
	"net"
	"path/filepath"
	"strings"
	"time"

	badgerdb "github.com/dgraph-io/badger/v4"
	mqtt "github.com/mochi-mqtt/server/v2"
	"github.com/mochi-mqtt/server/v2/hooks/storage/badger"
	"github.com/mochi-mqtt/server/v2/listeners"
	"github.com/mochi-mqtt/server/v2/packets"

	"notice-server/badgeropts"
	"notice-server/logger"
	"notice-server/ratelimit"
	"notice-server/store"
)

const (
	// mqttStorageDir MQTT 持久化存储子目录
	mqttStorageDir = "mqtt"
)

// Message 推送消息结构
type Message struct {
	ID               uint64    `json:"id,omitempty"` // 服务端消息库 id（由 MessageStoreHook 在分发前注入）
	Title            string    `json:"title"`
	Content          string    `json:"content"`
	ContentEncoding  string    `json:"content_encoding,omitempty"` // 如 gzip+base64，见 DecodeMessageContent
	Extra            any       `json:"extra,omitempty"`
	Timestamp        time.Time `json:"timestamp"`
	Client           string    `json:"client,omitempty"` // 发送端标识：web / android / cli / webhook
}

// Config Broker 配置
type Config struct {
	SessionExpiry  uint32             // 会话过期时间（秒）
	MessageExpiry  uint32             // 消息过期时间（秒）
	AuthToken      string             // 认证 Token，为空则不校验
	StorageEnabled bool               // 是否启用持久化存储
	StoragePath    string             // 持久化存储路径
	Badger         badgeropts.Params  // Badger 内存参数（与消息库共用 badgeropts.Options）
	AuthLimiter    *ratelimit.Limiter // 认证失败限流（与 Webhook 共用时可防暴力尝试），nil 则不限流
}

// Broker MQTT Broker 服务
type Broker struct {
	server       *mqtt.Server
	topic        string
	config       Config
	storeManager *store.Manager
}

// New 创建新的 Broker
func New(topic string, cfg Config, m *store.Manager) *Broker {
	return &Broker{
		topic:        topic,
		config:       cfg,
		storeManager: m,
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
			MaximumQos:                   2,                             // 最大 QoS 级别（支持 QoS 0/1/2）
		},
		ClientNetWriteBufferSize: 4096, // 客户端写缓冲区
		ClientNetReadBufferSize:  4096, // 客户端读缓冲区
	})

	logger.Info("MQTT 配置加载",
		"session_expiry", b.config.SessionExpiry,
		"message_expiry", b.config.MessageExpiry,
	)

	// 添加持久化存储钩子（必须最先添加，以便加载已保存的会话和订阅）
	if b.config.StorageEnabled && b.config.StoragePath != "" {
		mqttPath := filepath.Join(b.config.StoragePath, mqttStorageDir)
		bp := b.config.Badger.Normalized()
		mqttBadgerOpts := badgeropts.Options(mqttPath, b.config.Badger).WithLoggingLevel(badgerdb.INFO)
		if err := b.server.AddHook(new(badger.Hook), &badger.Options{
			Path:    mqttPath,
			Options: &mqttBadgerOpts,
		}); err != nil {
			return err
		}
		logger.Info("MQTT 持久化存储已启用", "path", mqttPath,
			"badger_memtable_mb", bp.MemTableMB,
			"badger_block_cache_mb", bp.BlockCacheMB,
			"badger_num_memtables", bp.NumMemtables,
		)
	}

	// 启用 Token 认证（可选限流防暴力尝试）
	if err := b.server.AddHook(&AuthHook{
		token:   b.config.AuthToken,
		limiter: b.config.AuthLimiter,
	}, nil); err != nil {
		return err
	}
	logger.Info("MQTT Token 认证已启用")
	if b.config.AuthLimiter != nil {
		logger.Info("MQTT 认证限流已启用")
	}

	// 添加日志钩子
	if err := b.server.AddHook(new(LogHook), nil); err != nil {
		return err
	}

	// 添加消息存储钩子（记录所有发布的消息）
	if b.storeManager != nil && b.storeManager.IsEnabled() {
		if err := b.server.AddHook(&MessageStoreHook{
			manager: b.storeManager,
			token:   b.config.AuthToken,
		}, nil); err != nil {
			return err
		}
		logger.Info("消息历史记录已启用")
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

// Clients 获取所有已连接的客户端（排除内置客户端）
func (b *Broker) Clients() []*mqtt.Client {
	var result []*mqtt.Client
	for _, cl := range b.server.Clients.GetAll() {
		if cl.ID != "inline" && len(cl.ID) > 0 && cl.ID[0] != '$' {
			result = append(result, cl)
		}
	}
	return result
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

// TCPAddr 返回 TCP 监听地址（如 "127.0.0.1:12345"），用于测试。未启动或无 TCP 监听时返回空字符串。
func (b *Broker) TCPAddr() string {
	if b.server == nil {
		return ""
	}
	l, ok := b.server.Listeners.Get("tcp")
	if !ok {
		return ""
	}
	return l.Address()
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
		b == mqtt.OnPublished ||
		b == mqtt.OnSessionEstablished ||
		b == mqtt.OnQosPublish ||
		b == mqtt.OnQosComplete ||
		b == mqtt.OnClientExpired
}

func (h *LogHook) OnConnect(cl *mqtt.Client, pk packets.Packet) error {
	logger.Info("MQTT 客户端连接", "client_id", cl.ID)
	return nil
}

func (h *LogHook) OnSessionEstablished(cl *mqtt.Client, pk packets.Packet) {
	// 只有当有待发送的离线消息时才记录
	if cl.State.Inflight.Len() > 0 {
		logger.Info("MQTT 会话恢复", "client_id", cl.ID, "pending_messages", cl.State.Inflight.Len())
	}
}

func (h *LogHook) OnQosPublish(cl *mqtt.Client, pk packets.Packet, sent int64, resends int) {
	// 只记录离线消息入队（客户端已断开时）
	if cl.Closed() {
		logger.Debug("离线消息入队", "client_id", cl.ID, "topic", pk.TopicName)
	}
}

func (h *LogHook) OnQosComplete(cl *mqtt.Client, pk packets.Packet) {
	logger.Debug("MQTT QoS 消息完成",
		"client_id", cl.ID,
		"packet_id", pk.PacketID,
	)
}

func (h *LogHook) OnDisconnect(cl *mqtt.Client, err error, expire bool) {
	if err != nil {
		// 客户端主动关闭（如刷新、关页）时常见 "use of closed network connection"，降为 Debug 减少噪音
		if strings.Contains(err.Error(), "closed network connection") {
			logger.Debug("MQTT 客户端断开", "client_id", cl.ID, "error", err)
			return
		}
		logger.Info("MQTT 客户端断开", "client_id", cl.ID, "error", err)
	} else {
		logger.Info("MQTT 客户端断开", "client_id", cl.ID)
	}
}

func (h *LogHook) OnClientExpired(cl *mqtt.Client) {
	logger.Info("MQTT 会话已过期并清除", "client_id", cl.ID)
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
	token   string
	limiter *ratelimit.Limiter
}

func (h *AuthHook) ID() string {
	return "token-auth"
}

func (h *AuthHook) Provides(b byte) bool {
	return b == mqtt.OnConnectAuthenticate || b == mqtt.OnACLCheck
}

// mqttClientIP 从 MQTT 客户端连接取 IP（cl.Net.Remote 格式为 "host:port"）
func mqttClientIP(remote string) string {
	remote = strings.TrimSpace(remote)
	if remote == "" {
		return ""
	}
	host, _, err := net.SplitHostPort(remote)
	if err != nil {
		return remote
	}
	return host
}

// OnConnectAuthenticate 连接认证
// MQTT 客户端通过 username 或 password 传入 token；限流支持按 IP、按错误凭证、全局
func (h *AuthHook) OnConnectAuthenticate(cl *mqtt.Client, pk packets.Packet) bool {
	ip := mqttClientIP(cl.Net.Remote)
	username := string(pk.Connect.Username)
	password := string(pk.Connect.Password)
	attemptedCredential := username
	if attemptedCredential == "" {
		attemptedCredential = password
	}

	if h.limiter != nil {
		if h.limiter.IsBlocked(ip, attemptedCredential) {
			logger.Warn("MQTT 认证拒绝，已触发限流", "client_id", cl.ID, "ip", ip)
			return false
		}
	}

	// 支持以下方式传入 token:
	// 1. username = token
	// 2. password = token
	// 3. username = "token", password = <actual_token>
	if username == h.token {
		if h.limiter != nil && ip != "" {
			h.limiter.RecordSuccess(ip)
		}
		logger.Debug("MQTT 认证成功 (username)", "client_id", cl.ID)
		return true
	}
	if password == h.token {
		if h.limiter != nil && ip != "" {
			h.limiter.RecordSuccess(ip)
		}
		logger.Debug("MQTT 认证成功 (password)", "client_id", cl.ID)
		return true
	}

	if h.limiter != nil {
		h.limiter.RecordFailure(ip, attemptedCredential)
	}
	logger.Warn("MQTT 认证失败", "client_id", cl.ID, "ip", ip, "username", username)
	return false
}

// OnACLCheck ACL 检查，允许所有已认证用户
func (h *AuthHook) OnACLCheck(cl *mqtt.Client, topic string, write bool) bool {
	// 已通过认证的客户端允许所有操作
	return true
}

// MessageStoreHook 消息存储钩子
type MessageStoreHook struct {
	mqtt.HookBase
	manager *store.Manager
	token   string // 当前服务使用的 token
}

func (h *MessageStoreHook) ID() string {
	return "message-store"
}

func (h *MessageStoreHook) Provides(b byte) bool {
	return b == mqtt.OnPublish
}

// OnPublish 在分发给订阅者之前入库，并把稳定的服务端 id 注入 JSON 载荷。
// 这样 MQTT 实时消息与 GET /messages 历史使用同一身份，客户端可按 id 去重。
func (h *MessageStoreHook) OnPublish(cl *mqtt.Client, pk packets.Packet) (packets.Packet, error) {
	// 跳过系统消息（以 $ 开头的主题）
	if len(pk.TopicName) > 0 && pk.TopicName[0] == '$' {
		return pk, nil
	}
	if h.manager == nil || !h.manager.IsEnabled() {
		return pk, nil
	}

	var saved *store.Message
	var err error

	var msg Message
	if uerr := json.Unmarshal(pk.Payload, &msg); uerr != nil {
		// 非 JSON：仍入库，但无法在原载荷上注入 id
		saved, err = h.manager.Save(h.token, pk.TopicName, "", string(pk.Payload), nil)
	} else {
		// JSON：入库前解压已知编码，便于 HTTP 历史为明文；线上载荷尽量保留原字段仅加 id
		if msg.ContentEncoding != "" {
			if derr := DecodeMessageContent(&msg); derr != nil {
				logger.Warn("消息 content 解压失败，按原始字段入库", "error", derr)
			}
		}
		saved, err = h.manager.Save(h.token, pk.TopicName, msg.Title, msg.Content, msg.Extra)
	}
	if err != nil {
		logger.Warn("消息保存失败", "error", err)
		return pk, nil
	}
	if saved == nil {
		return pk, nil
	}
	if out, ok := InjectStoreIDIntoPayload(pk.Payload, saved.ID); ok {
		pk.Payload = out
	}
	return pk, nil
}
