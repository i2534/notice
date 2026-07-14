# Notice

轻量级消息推送系统，包含服务端和多平台客户端。

## AIGC

除了本行是人工添加以外，其余所有内容均为 AI 辅助生成，使用的工具包括：
- **Cursor** (Agent Auto 模式) — 项目主体代码
- **OpenCode** (Sisyphus / Claude) — 后续迭代与维护

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

### 数据流向

```
┌─────────────┐     Webhook POST      ┌──────────────┐     MQTT Publish      ┌─────────┐
│  CI/CD      │ ─────────────────────▶│  Notice      │ ────────────────────▶ │  CLI    │
│  Webhook    │                       │  Server      │                       │  GUI    │
│  Alerts     │                       │  (Go)        │                       │  Android│
└─────────────┘                       └──────────────┘                       │  Hermes │
        ▲                                │                                  │  Openclaw│
        │                                │                                  │  XiaoAi  │
        │         MQTT Subscribe         │                                  │  OpenCode│
        └────────────────────────────────┘                                  └─────────┘
              (双向：客户端也可通过 MQTT 发布消息回服务端)
```

**核心组件：**
- **内置 MQTT Broker** — 无需外部依赖，支持 TCP (9091) / WebSocket (9092) / WSS
- **HTTP Webhook** — 统一入口，支持 `topic` 指定发布主题，`content_encoding: gzip+base64` 压缩
- **Web 管理界面** — 嵌入二进制，消息收发、Markdown 渲染、图片/媒体上传
- **BadgerDB 持久化** — 消息历史、MQTT 离线消息、会话/订阅，内存参数可调适合小内存 VPS
- **多层限流** — 按 IP、按错误凭证、全局限流，防御暴力破解与换 IP 攻击

## 目录结构

```
notice/
├── server/                          # 服务端 (Go 1.25)
│   ├── main.go                      # 入口：HTTP + MQTT Broker + BadgerDB
│   ├── config.yaml                  # 配置文件（环境变量优先）
│   ├── broker/                      # 内置 MQTT Broker (TCP/WS/WSS)
│   ├── handlers/                    # HTTP 处理器
│   │   ├── webhook.go               # Webhook 接收（支持 topic、压缩）
│   │   ├── api.go                   # API 与消息历史
│   │   ├── image.go                 # 图片上传 /api/upload、签名访问 /api/image
│   │   ├── media.go                 # 多媒体上传 /api/media、签名访问
│   │   ├── security.go              # 请求体/参数安全校验
│   │   └── *_test.go                # 单元测试
│   ├── store/                       # 消息持久化存储
│   ├── badgeropts/                  # Badger 内存参数（消息库 + MQTT 共用）
│   ├── ratelimit/                   # 多层限流（IP/凭证/全局）
│   ├── logger/                      # 日志系统（轮转 + 过滤）
│   ├── config/                      # 配置管理（YAML + 环境变量）
│   ├── web/                         # Web 管理界面（Vite + Svelte 5）
│   ├── scripts/                     # 启动脚本
│   ├── Dockerfile
│   └── Makefile
│
├── client/
│   ├── cli/                         # 跨平台 CLI (Go) — Linux/Windows/macOS
│   │   ├── main.go                  # 入口：订阅 + send 子命令
│   │   ├── notify_*.go              # 跨平台系统通知
│   │   ├── scripts/                 # 启动脚本
│   │   └── Makefile                 # 构建/交叉编译
│   ├── gui/                         # 跨平台 GUI (Tauri v2 + Rust)
│   │   ├── src/                     # 前端
│   │   ├── tauri/                   # Rust 后端
│   │   └── Makefile                 # 本地/Docker 构建
│   ├── android/                     # Android (Kotlin + Gradle KTS)
│   │   ├── app/                     # 源码（Room + Paging 3 + DataStore）
│   │   ├── Makefile                 # Docker 构建（推荐）
│   │   └── gradle-wrapper.properties
│   ├── hermes/                      # Hermes 平台插件（勿改 Agent 核心）
│   │   ├── adapter.py / config.py / payload.py / ...
│   │   ├── plugin.yaml
│   │   └── README.md
│   ├── openclaw/                    # Openclaw Channel 插件
│   │   ├── index.ts                 # 入口
│   │   └── package.json
│   ├── xiaoai/                      # 小爱音箱 Pro 客户端
│   │   ├── xiaoai.py                # 入口
│   │   ├── config.yaml              # 配置模板
│   │   ├── Makefile                 # prepare/start/stop/test
│   │   └── requirements.txt
│   ├── opencode/                    # OpenCode 客户端
│   │   ├── opencode.py              # 入口
│   │   ├── config.yaml              # 配置模板
│   │   ├── Makefile
│   │   └── requirements.txt
│
├── AGENTS.md                        # 开发者指引（本文件）
├── REASONIX.md                      # 架构决策记录
├── LICENSE
└── .github/workflows/               # CI/CD
    ├── build.yml
    └── release.yml
```

## 快速开始

### 1. 启动服务端

```bash
cd server
go mod tidy
make run
```

服务端口：
- `9090` - HTTP Webhook + Web 管理界面
- `9091` - MQTT TCP
- `9092` - MQTT WebSocket

> **配置优先级**：环境变量 > `config.yaml` > 默认值。Token 留空时自动生成，可通过 `AUTH_TOKEN` 环境变量或 `auth.token` 覆盖。

### 2. Web 管理界面（开发模式）

```bash
cd server/web
npm install            # 首次
npm run dev            # 开发模式（需服务端已启动，API 自动代理到 :9090）
npm run build          # 生产构建（产物在 web/dist/，嵌入 Go 二进制）
```

### 3. 启动客户端

| 客户端 | 启动命令 | 说明 |
|--------|----------|------|
| **CLI** | `cd client/cli && make run TOKEN=<token>` | Linux/Windows/macOS，支持 `send` 子命令 |
| **GUI** | `cd client/gui && npm run tauri dev` | Tauri 桌面端，需 Rust + Node.js |
| **Android** | `cd client/android && make docker` | Docker 构建 APK（推荐） |
| **Hermes** | `cd client/hermes && ln -sfn $(pwd) ~/.hermes/plugins/notice` | Hermes ≥0.18；整目录软链，勿改 Agent 核心 |
| **Openclaw** | `cd client/openclaw && npm install && openclaw plugins install -l .` | 需 Openclaw ≥ 1.0 |
| **XiaoAi** | `cd client/xiaoai && make prepare && make start` | 小爱音箱双向通信 |
| **OpenCode** | `cd client/opencode && make prepare && make start` | 接入 Notice 消息系统 |

### 4. 发送消息

```bash
# 发送到默认主题
curl -X POST http://localhost:9090/webhook \
  -H "Authorization: Bearer <token>" \
  -d '{"title":"测试","content":"Hello World"}'

# 指定发布主题（回复到指定 topic）
curl -X POST http://localhost:9090/webhook \
  -H "Authorization: Bearer <token>" \
  -d '{"title":"测试","content":"Hello World","topic":"notice/alert"}'

# 长内容自动压缩（≥255 字符且压缩更短时）
curl -X POST http://localhost:9090/webhook \
  -H "Authorization: Bearer <token>" \
  -d '{"content":"很长的内容...","content_encoding":"gzip+base64"}'
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
           proxy_set_header X-Forwarded-Proto $scheme;  # 关键：修正图片/媒体 URL 协议
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

> **重要**：反向代理后**必须**设置 `X-Forwarded-Proto`，否则 `/api/upload` 和 `/api/media/upload` 返回的 URL 会错误使用 `http://`，导致客户端无法加载图片/媒体。

## 功能特性

| 功能 | Server | CLI | GUI | Android | Hermes | XiaoAi | Openclaw | OpenCode |
|------|--------|-----|-----|---------|--------|--------|----------|----------|
| MQTT Broker | ✅ 内置 | - | - | - | - | - | - | - |
| Webhook 接收 | ✅ | - | - | - | - | - | - | - |
| Token 认证 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| IP/全局/凭证限流 | ✅ | - | - | - | - | - | - | - |
| Web 管理界面 | ✅ | - | - | - | - | - | - | - |
| 日志轮转 | ✅ | - | - | - | - | - | - | - |
| 系统通知 | - | ✅ | ✅ | ✅ | - | ✅ TTS 播报 | - | - |
| 消息历史 | ✅ | - | ✅ | ✅ | - | - | ✅ RPC | - |
| 系统托盘 | - | - | ✅ | - | - | - | - | - |
| 后台运行 | - | - | ✅ | ✅ | - | ✅ | - | ✅ |
| 开机自启 | - | - | - | ✅ | - | - | - | - |
| 执行外部命令 | - | ✅ | - | - | - | - | - | - |
| 发送可指定 topic | ✅ Webhook | ✅ send -topic | - | ✅ 设置+回复 | ✅ | ✅ 语音发布 | ✅ ctx.to | - |
| Markdown 渲染 | ✅ Web | - | - | ✅ | ✅ | - | - | - |
| 长消息自动压缩 | - | ✅ 发送端 | - | - | ✅ | - | ✅ 发送端 | - |
| 语音消息 | - | - | - | ✅ 录制/播放 | ✅ ASR 转写 | ✅ TTS/语音输入 | ✅ STT 转写 | - |
| 图片上传/签名 URL | ✅ API | - | - | - | ✅ 上传 | - | ✅ 上传 | - |
| 多媒体上传/签名 URL | ✅ API | - | - | ✅ 语音上传 | - | - | - | - |
| 离线消息/持久会话 | ✅ | - | - | ✅ | - | - | - | - |
| Linux | ✅ | ✅ | ✅ | - | ✅ | ✅ | ✅ | ✅ |
| Windows | ✅ | ✅ | ✅ | - | ✅ | - | ✅ | ✅ |
| macOS | ✅ | ✅ | ✅ | - | ✅ | - | ✅ | ✅ |
| Android | - | - | - | ✅ | - | - | - | - |

## 文档

| 组件 | 文档 | 核心能力 |
|------|------|----------|
| **Server** | [server/README.md](server/README.md) | HTTP Webhook + 内置 MQTT Broker + BadgerDB + Web 界面 |
| **CLI** | [client/cli/README.md](client/cli/README.md) | 跨平台订阅 + `send` 子命令 + 执行外部命令 |
| **GUI** | [client/gui/README.md](client/gui/README.md) | Tauri 桌面端，系统托盘、消息历史、自动重连 |
| **Android** | [client/android/README.md](client/android/README.md) | Room 持久化、Markdown+图片+语音块、回复指定 topic |
| **Hermes** | [client/hermes/README.md](client/hermes/README.md) | MQTT 平台插件、standalone/cron、ASR→全局 STT；不修改 Hermes 源码 |
| **Openclaw** | [client/openclaw/README.md](client/openclaw/README.md) | MQTT Channel、收发指定 topic、STT 语音转写、图片上传 |
| **XiaoAi** | [client/xiaoai/README.md](client/xiaoai/README.md) | 小爱音箱双向：TTS 播报 + 语音发送 |
| **OpenCode** | [client/opencode/README.md](client/opencode/README.md) | 本地 OpenCode serve 接入、MQTT 指令控制 |

## License

MIT
