# Notice Server

集成 HTTP Webhook 和 MQTT Broker 的消息推送服务器。

## 架构

```
外部系统 --[Webhook POST]--> Notice Server --[MQTT]--> 客户端 App
                                  |
                            内置 MQTT Broker
                            (TCP + WebSocket)
                                  |
                       移动端/桌面端直接连接
```

## 功能特性

- 📥 HTTP Webhook 接收消息
- 📡 内置 MQTT Broker（TCP + WebSocket）
- 🔐 Token 认证（Webhook + MQTT）
- 🛡️ IP 限流（防止暴力破解）
- 🌐 内置 Web 管理界面（消息发送/接收）
- 📝 日志轮转（按天分割、自动清理）
- ⚡ 单一服务，无外部依赖

## 项目结构

```
server/
├── main.go              # 主程序入口
├── config/
│   └── config.go        # 配置管理
├── broker/
│   └── broker.go        # 内置 MQTT Broker
├── webhook/
│   └── handler.go       # Webhook 处理器
├── ratelimit/
│   └── ratelimit.go     # IP 限流
├── logger/
│   └── logger.go        # 日志系统（轮转）
├── web/
│   └── index.html       # Web 管理界面
├── go.mod
├── Makefile
└── README.md
```

## 快速开始

### 1. 安装依赖

```bash
go mod tidy
```

### 2. 运行服务器

```bash
make run
```

服务端口：
- HTTP Webhook + Web 界面: 9090
- MQTT TCP: 9091
- MQTT WebSocket: 9092

### 3. 测试

```bash
# 终端1：订阅消息（需要 mosquitto-clients）
make test-sub

# 终端2：发送测试消息
make test-push
```

## 配置

### 基础配置

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| HTTP_PORT | 9090 | HTTP 服务端口 |
| MQTT_TCP_PORT | 9091 | MQTT TCP 端口 |
| MQTT_WS_PORT | 9092 | MQTT WebSocket 端口 |
| MQTT_TOPIC | notice | 默认推送主题 |

### 认证配置

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| AUTH_TOKEN | (自动生成) | 访问令牌，不设置则自动生成 |

### 限流配置

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| RATE_LIMIT_MAX_FAILURES | 5 | 最大失败次数 |
| RATE_LIMIT_BLOCK_TIME | 900 | 封禁时间（秒） |
| RATE_LIMIT_WINDOW_TIME | 300 | 统计窗口（秒） |

### 日志配置

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| LOG_CONSOLE_LEVEL | info | 控制台日志级别: debug, info, warn, error, off |
| LOG_FILE_LEVEL | debug | 文件日志级别: debug, info, warn, error, off |
| LOG_FILE_PATH | logs/server.log | 日志文件路径 |
| LOG_PRETTY | true | 控制台美化输出 |
| LOG_ROTATE_DAYS | 1 | 日志轮转天数（每N天一个文件） |
| LOG_MAX_FILES | 7 | 保留日志文件数量 |

### MQTT 会话配置

| 环境变量 | 默认值 | 说明 |
|---------|--------|------|
| MQTT_SESSION_EXPIRY | 3600 | 会话过期时间（秒） |
| MQTT_MESSAGE_EXPIRY | 86400 | 消息过期时间（秒） |

## API 端点

### POST /webhook

接收消息并推送到所有已连接的客户端。

**请求头（认证）：**

```
Authorization: Bearer <token>
# 或
X-Auth-Token: <token>
# 或
?token=<token>
```

**请求体：**

```json
{
  "title": "通知标题",
  "content": "通知内容（必填）",
  "topic": "custom/topic",
  "extra": {"key": "value"}
}
```

**响应：**

```json
{
  "success": true,
  "message": "消息推送成功",
  "clients": 3
}
```

### GET /status

```json
{"status":"ok","clients":3}
```

### GET /health

```json
{"status":"ok"}
```

### GET /

Web 管理界面（需要认证）

## 客户端连接

### 连接地址

| 协议 | 地址 |
|-----|------|
| TCP | tcp://your-server:9091 |
| WebSocket | ws://your-server:9092 |
| WebSocket + TLS | wss://your-server (需代理) |

### 认证方式

MQTT 客户端通过 `username` 或 `password` 传递 Token：

```bash
# mosquitto_sub 示例
mosquitto_sub -h localhost -p 9091 -t notice/# -u "<token>"
```

### 示例代码

**JavaScript (WebSocket)**

```javascript
const client = mqtt.connect('ws://your-server:9092', {
  username: 'your-token'
});
client.subscribe('notice/#');
client.on('message', (topic, message) => {
  console.log(JSON.parse(message.toString()));
});
```

**Android (Kotlin)**

```kotlin
val options = MqttConnectOptions().apply {
    userName = "your-token"
}
val client = MqttAsyncClient("tcp://your-server:9091", clientId)
client.connect(options)
client.subscribe("notice/#", 1)
```

## 消息格式

推送到客户端的消息：

```json
{
  "title": "通知标题",
  "content": "通知内容",
  "extra": {},
  "timestamp": "2026-01-08T12:00:00Z"
}
```

## 使用示例

```bash
# 发送简单通知（带认证）
curl -X POST http://localhost:9090/webhook \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-token>" \
  -d '{"title":"新消息","content":"你有一条新消息"}'

# 发送到自定义主题
curl -X POST http://localhost:9090/webhook \
  -H "Authorization: Bearer <your-token>" \
  -d '{"content":"订单已发货","topic":"notice/order"}'
```

## 构建

```bash
make build
./notice-server
```

## Docker

```dockerfile
FROM golang:1.25-alpine AS builder
WORKDIR /app
COPY . .
RUN go mod tidy && go build -o notice-server

FROM alpine:latest
WORKDIR /app
COPY --from=builder /app/notice-server .
COPY --from=builder /app/web ./web
EXPOSE 9090 9091 9092
CMD ["./notice-server"]
```

```bash
docker build -t notice-server .
docker run -p 9090:9090 -p 9091:9091 -p 9092:9092 \
  -e AUTH_TOKEN=your-secret-token \
  notice-server
```

## License

MIT
