# Notice Client (GUI)

基于 Tauri + Rust 的跨平台 GUI 客户端，连接到 Notice Server 接收消息推送。

## 功能

- 📡 连接 MQTT Broker 订阅消息
- 🔔 收到消息后显示系统通知
- 🔐 支持 Token 认证
- 🔄 自动重连
- 📋 消息历史记录
- 🖥️ 系统托盘支持

## 开发

### 环境要求

- Rust 1.70+
- Node.js 18+
- Tauri 依赖 (参考 https://tauri.app/start/prerequisites/)

#### Linux 依赖

```bash
# 使用 Makefile 安装
make install-deps

# 或手动安装 (Ubuntu/Debian)
sudo apt install libgtk-3-dev libwebkit2gtk-4.1-dev libayatana-appindicator3-dev librsvg2-dev

# Fedora
sudo dnf install gtk3-devel webkit2gtk4.1-devel libayatana-appindicator-gtk3-devel librsvg2-devel
```

#### Windows 依赖

- WebView2 (Windows 10/11 通常已预装)
- Visual Studio Build Tools

### 安装依赖

```bash
npm install
```

### 开发运行

```bash
npm run tauri dev
```

### 构建发布

```bash
# 本地构建
npm run tauri build

# 或使用 make
make build
```

构建产物位于 `tauri/target/release/` 目录。

### Docker 构建

无需安装本地开发依赖，使用 Docker 进行交叉编译：

```bash
# 构建 Linux 版本 (当前架构)
make docker-build

# 构建指定版本
make docker-build VERSION=1.0.0

# 构建 Linux amd64 版本
make docker-build-amd64

# 构建 Linux arm64 版本
make docker-build-arm64

# 构建所有平台
make docker-build-all VERSION=1.0.0
```

构建产物位于 `dist/bundle/` 目录，包含：
- `deb/` - Debian 安装包
- `appimage/` - AppImage 便携版

## 配置

配置文件位于：
- Windows: `%APPDATA%/com.github.i2534.notice/config.json`
- Linux: `~/.config/com.github.i2534.notice/config.json`
- macOS: `~/Library/Application Support/com.github.i2534.notice/config.json`

### 配置项

| 配置 | 说明 | 默认值 |
|------|------|--------|
| broker | MQTT 服务器地址 | localhost |
| port | MQTT 端口 | 9091 |
| use_tls | 使用 TLS | false |
| topic | 订阅主题 | notice/# |
| token | 认证 Token | (空) |

## 使用

1. 启动 Notice Server（HTTP 9090，MQTT TCP 9091 / WebSocket 9092）
2. 运行 Notice Client (GUI)
3. 填写服务器地址、端口（MQTT 端口 9091 或 WebSocket 9092）和 Token
4. 点击「连接」按钮
5. 收到消息时会显示系统通知

## 技术栈

- **后端**: Rust + Tauri
- **前端**: HTML/CSS/JavaScript
- **MQTT**: rumqttc
- **通知**: tauri-plugin-notification

## License

MIT
