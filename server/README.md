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
- 🔐 Token 认证（Webhook + MQTT + 图片上传/访问）
- 🛡️ 认证限流：按 IP、按错误凭证、全局限流（防单 IP 与换 IP 暴力破解）
- 🖼️ 图片上传（POST /api/upload）与签名 URL 访问（GET /api/image），支持反向代理 HTTPS
- 🎤 多媒体上传（POST /api/media/upload）与签名 URL 访问（GET /api/media），用于语音等（.m4a/.mp3/.webm/.ogg/.wav）
- 🌐 内置 Web 管理界面（消息发送/接收、消息体 Markdown 渲染）
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
│   ├── broker.go        # 内置 MQTT Broker
│   └── broker_test.go   # MQTT 认证限流等测试
├── handlers/
│   ├── webhook.go       # Webhook 接收（支持 body.topic 指定发布主题）
│   ├── api.go           # API 与消息历史
│   ├── image.go         # 图片上传（/api/upload）与签名 URL 访问（/api/image）
│   ├── media.go         # 多媒体上传（/api/media/upload）与签名 URL 访问（/api/media）
│   ├── security.go      # 请求体/参数安全（限长、topic 校验、防 XSS/路径穿越）
│   ├── webhook_test.go  # Webhook 与限流测试
│   ├── image_test.go    # 图片上传与签名校验测试
│   └── security_test.go # 安全校验单元测试
├── store/
│   ├── store.go         # 消息持久化存储
│   └── store_test.go    # 存储单元测试
├── ratelimit/
│   ├── ratelimit.go     # 认证限流（按 IP / 按凭证 / 全局）
│   └── ratelimit_test.go # 限流单元测试
├── logger/
│   └── logger.go        # 日志系统（轮转 + 过滤）
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
# 单元测试（限流、安全校验、Webhook、Broker、Store 等）
go test ./...

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
  max_request_body_bytes: 0   # 请求体最大字节数，0=默认 512KB，防 DoS

mqtt:
  tcp_port: "9091"
  ws_port: "9092"
  topic: "notice"
  session_expiry: 86400  # 会话过期时间（秒）；断联超过此时长的客户端会话会被自动清除
  message_expiry: 86400  # 消息过期时间（秒）

auth:
  token: ""              # 留空则自动生成

rate_limit:
  max_failures: 5
  block_time: 900
  window_time: 300
  # 防换 IP 暴力破解（0=关闭）：global_max_per_minute / global_block_time / credential_max_failures
  global_max_per_minute: 0
  global_block_time: 60
  credential_max_failures: 0

log:
  console_level: "info"
  file_level: "debug"
  file_path: ""          # 留空则不写入文件
  pretty: true
  rotate_days: 1
  max_files: 7

message:
  max_title_length: 50    # 标题最大长度，0 表示不限制
  # 内容最大长度（rune），0 表示不限制。仅作用于经 Webhook 入口写入的正文（SanitizeContent 截断）；客户端经 MQTT 直发并由 Broker 入库时不受此项截断。
  max_content_length: 1024 # 使用 OpenClaw Notice 插件时，插件的 maxContentLength 应 ≤ 此值（若走 Webhook）

# 图片上传与访问（可选）
image:
  folder: "images"           # 存储子目录（相对 storage.path）
  url_expiry_seconds: 86400  # 签名 URL 有效期（秒）
  cleanup_enabled: true     # 是否自动清理过期图片
  cleanup_interval_seconds: 3600
  max_upload_bytes: 5242880       # 单张最大 5MB
  max_upload_total_bytes: 20971520 # 单次请求总最大 20MB
  allowed_extensions: [".jpg", ".jpeg", ".png", ".gif", ".webp"]

# 多媒体上传与访问（语音等，可选）
media:
  folder: "media"              # 存储子目录（相对 storage.path）
  url_expiry_seconds: 86400   # 签名 URL 有效期（秒）
  cleanup_enabled: true
  cleanup_interval_seconds: 3600
  max_upload_bytes: 10485760  # 单文件最大 10MB
  allowed_extensions: [".m4a", ".mp3", ".webm", ".ogg", ".wav"]
```

指定配置文件：

```bash
./notice-server -c /path/to/config.yaml
# 或
CONFIG_PATH=/path/to/config.yaml ./notice-server
```

#### 防「换 IP」暴力破解

在仅按 IP 限流时，攻击者可通过代理/VPN 不断换 IP 尝试。可选启用：

- **全局限流** `global_max_per_minute`：每分钟全站认证失败总次数上限，超过则短时拒绝所有认证（任一 IP 均 429/连接拒）。例如 200–500，可显著降低分布式暴力尝试速率。
- **按错误凭证限流** `credential_max_failures`：同一错误 token（如 `password123`）被多 IP 尝试超过 N 次后，临时封禁该凭证，再试即拒。换 IP 无法绕过。

两者可同时开启，与按 IP 限流一起生效。

#### 请求与参数安全

- **请求体大小**：`http.max_request_body_bytes`（0 表示默认 512KB），超限返回 413。
- **Topic**：仅允许字母、数字、`/`、`-`、`_`，禁止 `..`、`\`、控制字符，长度 ≤ 256；非法返回 400。
- **标题/内容/client**：去除 NUL 及危险控制字符；正文（content）保留 `\n` `\t` `\r`，支持 Markdown。
- **静态路由**：请求路径含 `..` 或 `\` 时返回 400，防路径穿越。
- **API 查询参数**：`page_size` 限制 1～100，`before_id` 仅接受纯数字；token 长度截断以防滥用。

#### 反向代理与图片 URL（HTTPS）

服务放在 Nginx、Caddy 等反向代理后且对外使用 HTTPS 时，上传接口返回的图片地址会按「请求协议」生成。若代理未把原始协议传给后端，服务器会误用 `http://`。请务必在代理中设置：

- **X-Forwarded-Proto**：`https` 或 `http`（如 Nginx：`proxy_set_header X-Forwarded-Proto $scheme;`）

这样 `/api/upload` 与 `/api/media/upload` 返回的 URL 才会是 `https://...`，客户端和浏览器才能正常加载。

### 环境变量

所有配置项都可通过环境变量覆盖，详见 `config.yaml` 中的注释。

| 分类 | 环境变量 | 默认值 | 说明 |
|------|---------|--------|------|
| HTTP | HTTP_PORT | 9090 | HTTP 服务端口 |
| HTTP | HTTP_MAX_REQUEST_BODY_BYTES | 0 | 请求体最大字节数（0=512KB） |
| MQTT | MQTT_TCP_PORT | 9091 | MQTT TCP 端口 |
| MQTT | MQTT_WS_PORT | 9092 | MQTT WebSocket 端口 |
| MQTT | MQTT_TOPIC | notice | 默认推送主题 |
| MQTT | MQTT_SESSION_EXPIRY | 86400 | 会话过期时间（秒） |
| MQTT | MQTT_MESSAGE_EXPIRY | 86400 | 消息过期时间（秒） |
| 认证 | AUTH_TOKEN | (自动生成) | 访问令牌 |
| 限流 | RATE_LIMIT_MAX_FAILURES | 5 | 每 IP 最大失败次数 |
| 限流 | RATE_LIMIT_BLOCK_TIME | 900 | 封禁时间（秒） |
| 限流 | RATE_LIMIT_WINDOW_TIME | 300 | 统计窗口（秒） |
| 限流 | RATE_LIMIT_GLOBAL_MAX_PER_MINUTE | 0 | 每分钟全局限流失败次数（防换 IP，0=关） |
| 限流 | RATE_LIMIT_GLOBAL_BLOCK_TIME | 60 | 全局触发后冷却时间（秒） |
| 限流 | RATE_LIMIT_CREDENTIAL_MAX_FAILURES | 0 | 同一错误 token 被尝试次数上限（0=关） |
| 日志 | LOG_CONSOLE_LEVEL | info | 控制台日志级别 |
| 日志 | LOG_FILE_LEVEL | debug | 文件日志级别 |
| 日志 | LOG_FILE_PATH | (空) | 日志文件路径 |
| 日志 | LOG_PRETTY | true | 控制台美化输出 |
| 日志 | LOG_ROTATE_DAYS | 1 | 日志轮转天数 |
| 日志 | LOG_MAX_FILES | 7 | 保留日志文件数 |
| 存储 | STORAGE_ENABLED | true | 是否启用持久化存储 |
| 存储 | STORAGE_PATH | data | 数据存储路径 |
| 消息 | MESSAGE_MAX_TITLE_LENGTH | 50 | 标题最大长度（字符），**0=不限制** |
| 消息 | MESSAGE_MAX_CONTENT_LENGTH | 1024 | 内容最大长度（字符），**0=不限制** |
| 图片 | IMAGE_FOLDER | images | 图片存储子目录名 |
| 图片 | IMAGE_URL_EXPIRY_SECONDS | 86400 | 签名 URL 有效期（秒） |
| 图片 | IMAGE_CLEANUP_ENABLED | true | 是否启用过期图片清理 |
| 图片 | IMAGE_MAX_UPLOAD_BYTES | 5242880 | 单张图片最大字节数 |
| 图片 | IMAGE_MAX_UPLOAD_TOTAL_BYTES | 20971520 | 单次请求总最大字节数 |
| 多媒体 | MEDIA_FOLDER | media | 多媒体存储子目录名 |
| 多媒体 | MEDIA_URL_EXPIRY_SECONDS | 86400 | 签名 URL 有效期（秒） |
| 多媒体 | MEDIA_MAX_UPLOAD_BYTES | 10485760 | 单文件最大字节数（10MB） |

## API 端点

### POST /webhook

接收消息并推送到所有已连接的客户端。

**请求头（认证，推荐）：**

```
Authorization: Bearer <token>
# 或
X-Auth-Token: <token>
```

也可使用查询参数 `?token=<token>`（不推荐生产环境，易出现在日志/Referer 中）。

**请求体：** 最大长度由 `http.max_request_body_bytes` 控制（默认 512KB），超限返回 413。

| 字段 | 必填 | 说明 |
|------|------|------|
| content | ✅ | 通知内容；可与 `content_encoding` 配合为 gzip 压缩后再 standard base64 的字符串（与 MQTT / OpenClaw 约定一致） |
| title | | 标题，默认空 |
| topic | | 指定发布到的 MQTT 主题（仅允许字母数字、`/`、`-`、`_`，禁止 `..` 等）；不传则使用服务端默认主题 |
| extra | | 额外数据（对象） |
| client | | 发送端标识（如 web / android / cli），长度与内容受安全校验限制 |
| content_encoding | | 可选，仅支持 `gzip+base64`。服务端会解码、按明文做清洗与 `max_content_length` 校验（按解压后的 rune 数），再重新编码后发布到 MQTT |

Web 控制台在正文 Unicode 标量值数量 ≥ **255** 且 gzip+base64 比 UTF-8 字节更短时，会自动附带 `content_encoding`（需浏览器支持 `CompressionStream`）。

```json
{
  "title": "通知标题",
  "content": "通知内容（必填）",
  "topic": "notice/alert",
  "client": "web",
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

### POST /api/upload

上传图片（需 Bearer Token），支持单图或多图。用于 Openclaw 等客户端把本地图片上传后得到可公网访问的 URL，再写入消息内容。

**请求头：** `Authorization: Bearer <token>`（或 `X-Auth-Token`）

**请求体：** `multipart/form-data`，字段名 `file`（可多个）。允许扩展名由配置 `image.allowed_extensions` 控制，默认 `.jpg/.jpeg/.png/.gif/.webp`。单张与总大小限制见 `image.max_upload_bytes` / `image.max_upload_total_bytes`。

**响应：**

```json
{
  "success": true,
  "image_urls": [
    "https://your-server/api/image?n=xxx.png&e=过期时间戳&s=签名"
  ]
}
```

返回的 URL 为带签名的访问地址，有效期由 `image.url_expiry_seconds` 控制。**若服务部署在反向代理（Nginx/Caddy）后且对外为 HTTPS**，代理需设置 `X-Forwarded-Proto: https`，否则返回的 URL 会错误地使用 `http://`，客户端可能无法加载。例如 Nginx：

```nginx
proxy_set_header X-Forwarded-Proto $scheme;
```

### GET /api/image

通过签名 URL 访问已上传图片。参数：`n`（文件名）、`e`（过期时间戳）、`s`（Base64 签名）。无需在请求头带 Token；签名错误或过期返回 403。

### POST /api/media/upload

上传单个多媒体文件（语音等），需 Bearer Token。用于 Android 等客户端上传录音后得到可公网访问的 URL。请求体：`multipart/form-data`，字段名 `file`。允许扩展名由配置 `media.allowed_extensions` 控制，默认 `.m4a/.mp3/.webm/.ogg/.wav`。单文件大小限制见 `media.max_upload_bytes`（默认 10MB）。

**响应：** `{"success": true, "media_url": "https://your-server/api/media?n=xxx.m4a&e=过期时间戳&s=签名"}`

### GET /api/media

通过签名 URL 访问已上传多媒体文件。参数同 `/api/image`。签名错误或过期返回 403。

### GET /status

无需认证，返回当前连接数。`{"status":"ok","clients":3}`

### GET /health

无需认证。`{"status":"ok"}`

### GET /messages

消息历史（需认证）。查询参数：`page_size`（1～100，默认 20）、`before_id`（上一页最后一条 ID，纯数字）。认证方式同 Webhook。

### GET /

Web 管理界面。路径不允许包含 `..` 或 `\`。

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

启用持久化存储后（默认启用），服务器重启不会丢失离线消息。

### MQTT 消息 JSON 与 content_encoding

推送载荷为 JSON（`title`、`content`、`timestamp`、`client` 等）。可选 **`content_encoding`**：值为 **`gzip+base64`** 时，`content` 为正文 UTF-8 字节经 **gzip** 再 **standard base64**；未设置则 `content` 为明文。Broker **原样转发**字节流；**消息历史**入库时若识别该编码会解压为明文，便于 `GET /messages`。是否压缩仅由**发布端**决定，服务端无压缩配置项。

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
./scripts/start.sh stop      # 停止
./scripts/start.sh restart   # 重启
./scripts/start.sh status    # 查看状态
./scripts/start.sh -c config.yaml -d  # 指定配置后台运行

# Windows
scripts\start.bat
```

## License

MIT
