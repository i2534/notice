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
- 📦 YAML 配置文件支持
- 💾 离线消息支持（会话保持）
- ⚡ 单一服务，无外部依赖

## 项目结构

```
server/
├── main.go              # 主程序入口
├── config.yaml          # 默认配置文件
├── config/
│   ├── config.go        # 配置管理（支持 YAML + 环境变量）
│   └── config_test.go   # 配置单元测试
├── broker/
│   └── broker.go        # 内置 MQTT Broker
├── webhook/
│   └── handler.go       # Webhook 处理器
├── ratelimit/
│   └── ratelimit.go     # IP 限流
├── logger/
│   └── logger.go        # 日志系统（轮转）
├── web/
│   └── index.html       # Web 管理界面（嵌入二进制）
├── scripts/
│   ├── start.sh         # Linux 启动脚本
│   └── start.bat        # Windows 启动脚本
├── Dockerfile
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
# 方式一：使用 Makefile
make run

# 方式二：使用启动脚本
./scripts/start.sh

# 方式三：直接运行
go run main.go
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

支持三种配置方式，优先级：**环境变量 > 配置文件 > 默认值**

### 配置文件

编辑 `config.yaml`：

```yaml
http:
  port: "9090"

mqtt:
  tcp_port: "9091"
  ws_port: "9092"
  topic: "notice"
  session_expiry: 86400  # 会话过期时间（秒）
  message_expiry: 86400  # 消息过期时间（秒）

auth:
  token: ""              # 留空则自动生成

rate_limit:
  max_failures: 5
  block_time: 900
  window_time: 300

log:
  console_level: "info"
  file_level: "debug"
  file_path: ""          # 留空则不写入文件
  pretty: true
  rotate_days: 1
  max_files: 7
```

指定配置文件：

```bash
./notice-server -c /path/to/config.yaml
# 或
CONFIG_PATH=/path/to/config.yaml ./notice-server
```

### 环境变量

所有配置项都可通过环境变量覆盖，详见 `config.yaml` 中的注释。

| 分类 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| HTTP | HTTP_PORT | 9090 | HTTP 服务端口 |
| MQTT | MQTT_TCP_PORT | 9091 | MQTT TCP 端口 |
| MQTT | MQTT_WS_PORT | 9092 | MQTT WebSocket 端口 |
| MQTT | MQTT_TOPIC | notice | 默认推送主题 |
| MQTT | MQTT_SESSION_EXPIRY | 86400 | 会话过期时间（秒） |
| MQTT | MQTT_MESSAGE_EXPIRY | 86400 | 消息过期时间（秒） |
| 认证 | AUTH_TOKEN | (自动生成) | 访问令牌 |
| 限流 | RATE_LIMIT_MAX_FAILURES | 5 | 最大失败次数 |
| 限流 | RATE_LIMIT_BLOCK_TIME | 900 | 封禁时间（秒） |
| 限流 | RATE_LIMIT_WINDOW_TIME | 300 | 统计窗口（秒） |
| 日志 | LOG_CONSOLE_LEVEL | info | 控制台日志级别 |
| 日志 | LOG_FILE_LEVEL | debug | 文件日志级别 |
| 日志 | LOG_FILE_PATH | (空) | 日志文件路径 |
| 日志 | LOG_PRETTY | true | 控制台美化输出 |
| 日志 | LOG_ROTATE_DAYS | 1 | 日志轮转天数 |
| 日志 | LOG_MAX_FILES | 7 | 保留日志文件数 |

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

MQTT 客户端通过 `username` 传递 Token：

```bash
# mosquitto_sub 示例
mosquitto_sub -h localhost -p 9091 -t notice/# -u "<token>"
```

### 离线消息

客户端使用固定 Client ID + CleanSession=false 可接收离线消息：

- 会话保持时间：默认 1 天（MQTT_SESSION_EXPIRY）
- 消息保留时间：默认 1 天（MQTT_MESSAGE_EXPIRY）

**注意**：服务器重启后离线消息会丢失（内存存储）。

### 示例代码

**JavaScript (WebSocket)**

```javascript
const client = mqtt.connect('ws://your-server:9092', {
  username: 'your-token',
  clientId: 'my-client-id',
  clean: false  // 启用持久会话
});
client.subscribe('notice/#', { qos: 1 });
client.on('message', (topic, message) => {
  console.log(JSON.parse(message.toString()));
});
```

**Android (Kotlin)**

```kotlin
val options = MqttConnectOptions().apply {
    userName = "your-token"
    isCleanSession = false  // 启用持久会话
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
./notice-server --version
```

## Docker

```bash
# 构建镜像
make docker-build

# 运行（挂载配置文件）
docker run -d --name notice-server \
  -p 9090:9090 -p 9091:9091 -p 9092:9092 \
  -v $(pwd)/config.yaml:/app/config.yaml \
  notice-server:latest

# 或使用环境变量
docker run -d --name notice-server \
  -p 9090:9090 -p 9091:9091 -p 9092:9092 \
  -e AUTH_TOKEN=your-secret-token \
  notice-server:latest
```

## 启动脚本

```bash
# Linux
./scripts/start.sh           # 前台运行
./scripts/start.sh -d        # 后台运行
./scripts/start.sh -s        # 停止
./scripts/start.sh -r        # 重启
./scripts/start.sh --status  # 查看状态

# Windows
scripts\start.bat
```

## License

MIT
