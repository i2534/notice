# Notice

轻量级消息推送系统，包含服务端和多平台客户端。

## AIGC

除了本行是人工添加以外, 其余所有的内容均为 Cursor Agent Auto 模式生成, 并且是通过简单的 chat 交互而得

## 架构

```
+---------------+                  +-------------------+                  +-------------+
|  External     |   POST /webhook  |   Notice Server   |      MQTT        |   Clients   |
|  Systems      | ---------------> |   (Go + MQTT)     | ---------------> |  (Mobile/   |
|  (CI/CD etc)  |                  |                   |   TCP/WS/WSS     |   Desktop)  |
+---------------+                  +-------------------+                  +-------------+
                                           |
                                   Built-in Features:
                                   - MQTT Broker
                                   - Token Auth
                                   - Rate Limiting
                                   - Web Console
                                   - Log Rotation
```

## 目录结构

```
notice/
├── server/              # 服务端 (Go)
│   ├── broker/          # 内置 MQTT Broker
│   ├── handlers/        # HTTP 处理器 (Webhook 等)
│   ├── store/           # 消息持久化存储
│   ├── ratelimit/       # IP 限流
│   ├── logger/          # 日志系统（支持轮转）
│   ├── web/             # Web 管理界面
│   └── config/          # 配置管理
│
├── client/
│   ├── cli/             # 跨平台命令行客户端 (Go) - Linux/Windows/macOS，支持 send -topic
│   ├── gui/             # 跨平台桌面客户端 (Tauri) - Linux/Windows/macOS
│   ├── android/         # Android 客户端 (Kotlin)，支持默认发送主题、回复指定 topic、Markdown
│   └── openclaw/        # Openclaw 插件，收发可指定 topic，Agent 回信到同一 topic
│
└── README.md
```

## 快速开始

### 1. 启动服务端

```bash
cd server
go mod tidy
make run
```

服务端口:
- `9090` - HTTP Webhook + Web 界面
- `9091` - MQTT TCP
- `9092` - MQTT WebSocket

### 2. 启动客户端

**CLI (Linux/Windows/macOS):**
```bash
cd client/cli
make run TOKEN=<server-token>

# Windows
go run main.go -broker=tcp://localhost:9091 -token=<server-token>
```

**Android:**
```bash
cd client/android
make docker  # 使用 Docker 构建 APK
```

### 3. 发送消息

```bash
# 发送到默认主题
curl -X POST http://localhost:9090/webhook \
  -H "Authorization: Bearer <token>" \
  -d '{"title":"测试","content":"Hello World"}'

# 指定发布主题（回复到指定 topic）
curl -X POST http://localhost:9090/webhook \
  -H "Authorization: Bearer <token>" \
  -d '{"title":"测试","content":"Hello World","topic":"notice/alert"}'
```

## ⚠️ 公网部署安全须知

### 不推荐：TCP 直连

```
❌ tcp://your-server:9091
```

**风险：**
- 数据明文传输，可被窃听
- Token 和消息内容暴露
- 易遭受中间人攻击

### 推荐：WebSocket + TLS (WSS)

```
✅ wss://your-server
```

**部署方式：**

1. **Cloudflare Tunnel（最简单）**
   - 创建 Tunnel，指向 `http://localhost:9092`
   - 自动获得 HTTPS/WSS 支持

2. **Nginx 反向代理**
   ```nginx
   server {
       listen 443 ssl;
       server_name mqtt.example.com;
       
       ssl_certificate /path/to/cert.pem;
       ssl_certificate_key /path/to/key.pem;
       
       location / {
           proxy_pass http://127.0.0.1:9092;
           proxy_http_version 1.1;
           proxy_set_header Upgrade $http_upgrade;
           proxy_set_header Connection "upgrade";
       }
   }
   ```

### 连接方式对比

| 场景 | 协议 | 安全性 |
|------|------|--------|
| 局域网内 | `tcp://` | ✅ 可接受 |
| 公网（Cloudflare） | `wss://` | ✅ 安全 |
| 公网（Nginx+TLS） | `wss://` | ✅ 安全 |
| 公网裸 TCP | `tcp://` | ❌ **不安全** |

## 功能特性

| 功能 | Server | CLI | GUI | Android |
|------|--------|-----|-----|---------|
| MQTT Broker | ✅ 内置 | - | - | - |
| Webhook 接收 | ✅ | - | - | - |
| Token 认证 | ✅ | ✅ | ✅ | ✅ |
| IP 限流 | ✅ | - | - | - |
| Web 界面 | ✅ | - | - | - |
| 日志轮转 | ✅ | - | - | - |
| 桌面通知 | - | ✅ | ✅ | ✅ |
| 消息历史 | ✅ | - | ✅ | ✅ |
| 系统托盘 | - | - | ✅ | - |
| 后台运行 | - | - | ✅ | ✅ |
| 开机自启 | - | - | - | ✅ |
| 执行命令 | - | ✅ | - | - |
| 发送可指定 topic | ✅ Webhook | ✅ send -topic | - | ✅ 设置+回复指定 |
| 消息 Markdown 渲染 | ✅ Web | - | - | ✅ |
| Linux | ✅ | ✅ | ✅ | - |
| Windows | ✅ | ✅ | ✅ | - |
| macOS | ✅ | ✅ | ✅ | - |
| Android | - | - | - | ✅ |

## 文档

- [Server 文档](server/README.md) - 服务端部署和配置
- [CLI Client 文档](client/cli/README.md) - 跨平台命令行客户端（含 send 子命令）
- [GUI Client 文档](client/gui/README.md) - 跨平台桌面客户端
- [Android Client 文档](client/android/README.md) - Android 客户端（默认发送主题、回复指定 topic、Markdown）
- [Openclaw 插件文档](client/openclaw/README.md) - Openclaw Channel 插件，收发可指定 topic

## License

MIT
