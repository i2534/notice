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
│   ├── webhook/         # HTTP Webhook 处理
│   ├── ratelimit/       # IP 限流
│   ├── logger/          # 日志系统（支持轮转）
│   ├── web/             # Web 管理界面
│   └── config/          # 配置管理
│
├── client/
│   ├── linux/           # Linux 桌面客户端 (Go)
│   └── android/         # Android 客户端 (Kotlin)
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

**Linux:**
```bash
cd client/linux
make run TOKEN=<server-token>
```

**Android:**
```bash
cd client/android
make docker  # 使用 Docker 构建 APK
```

### 3. 发送消息

```bash
curl -X POST http://localhost:9090/webhook \
  -H "Authorization: Bearer <token>" \
  -d '{"title":"测试","content":"Hello World"}'
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

| 功能 | Server | Linux | Android |
|------|--------|-------|---------|
| MQTT Broker | ✅ 内置 | - | - |
| Webhook 接收 | ✅ | - | - |
| Token 认证 | ✅ | ✅ | ✅ |
| IP 限流 | ✅ | - | - |
| Web 界面 | ✅ | - | - |
| 日志轮转 | ✅ | - | - |
| 桌面通知 | - | ✅ | ✅ |
| 后台运行 | - | - | ✅ |
| 开机自启 | - | - | ✅ |

## 文档

- [Server 文档](server/README.md) - 服务端部署和配置
- [Linux Client 文档](client/linux/README.md) - Linux 桌面客户端
- [Android Client 文档](client/android/README.md) - Android 客户端

## License

MIT
