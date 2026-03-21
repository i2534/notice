# Notice CLI Client

跨平台命令行通知客户端，支持 **Linux**、**Windows** 和 **macOS**。连接到 Notice Server 接收消息并显示系统通知。

## 功能

- 📡 连接 MQTT Broker 订阅消息
- 📤 **send 子命令**：通过 webhook 发送消息，**可指定 topic**（回复到指定主题）；长正文可按服务端约定自动 **gzip+base64** 压缩
- 🔔 收到消息后显示系统通知 (跨平台)；订阅到的 MQTT JSON 若带 `content_encoding: gzip+base64` 会自动解压后再通知与 `-exec`
- 🔐 支持 Token 认证
- 🔄 自动重连
- ⚡ 支持收到消息时执行外部命令
- 🖥️ 支持 Linux / Windows / macOS

## 快速开始

### 1. 安装依赖

```bash
go mod tidy
```

### 2. 确保 Notice Server 已运行

```bash
cd ../../server
make run
```

### 3. 运行客户端

```bash
# Linux / macOS
make run TOKEN=your-token

# Windows (PowerShell)
go run main.go -broker=tcp://localhost:9091 -token=your-token
```

## 构建

### 构建当前平台

```bash
make build
# 输出: build/notice-cli
```

### 跨平台构建

```bash
# 构建所有平台
make build-all

# 单独构建
make build-linux        # build/notice-cli-linux-amd64
make build-linux-arm64  # build/notice-cli-linux-arm64
make build-windows      # build/notice-cli-windows-amd64.exe
make build-darwin       # build/notice-cli-darwin-amd64
make build-darwin-arm64 # build/notice-cli-darwin-arm64
```

### 手动交叉编译

```bash
# Linux → Windows
GOOS=windows GOARCH=amd64 go build -o notice-cli.exe

# Linux → macOS
GOOS=darwin GOARCH=amd64 go build -o notice-cli-mac
```

## 启动脚本

提供开箱即用的启动脚本，无需记忆命令行参数。

### Linux / macOS (start.sh)

```bash
# 基本用法
./start.sh <BROKER> <TOKEN> [TOPIC] [EXEC_CMD]

# 示例
./start.sh tcp://localhost:9091 your-token
./start.sh wss://mqtt.example.com your-token notice/#
./start.sh tcp://localhost:9091 your-token notice/# "./handler.sh"

# 使用环境变量
CLIENT_ID=my-client EXEC_CMD="./handler.sh" ./start.sh tcp://localhost:9091 your-token
```

### Windows (start.bat)

```batch
REM 基本用法
start.bat BROKER TOKEN [TOPIC] [EXEC_CMD]

REM 示例
start.bat tcp://localhost:9091 your-token
start.bat wss://mqtt.example.com your-token notice/#
start.bat tcp://localhost:9091 your-token notice/# "handler.bat"

REM 使用环境变量
set CLIENT_ID=my-client
set EXEC_CMD=handler.bat
start.bat tcp://localhost:9091 your-token
```

## 命令行参数

| 参数 | 默认值 | 说明 |
|-----|--------|------|
| -broker | tcp://localhost:9091 | MQTT Broker 地址 |
| -topic | notice/# | 订阅的主题 |
| -id | cli-client | 客户端 ID |
| -token | (空) | 认证 Token |
| -exec | (空) | 收到消息时执行的命令 |

### 使用示例

```bash
# 基本用法
./notice-cli -broker=tcp://localhost:9091 -token=your-secret-token

# 连接到远程服务器
./notice-cli -broker=tcp://192.168.1.100:9091 -token=your-token

# 使用 WebSocket 连接（通过 Cloudflare Tunnel）
./notice-cli -broker=wss://notice-ws.example.com -token=your-token

# 订阅自定义主题
./notice-cli -topic=order/# -token=your-token

# 收到消息时执行命令
./notice-cli -token=your-token -exec="./handler.sh"
```

### send 子命令（发送消息，可指定 topic）

通过服务端 webhook 发送一条消息，适合脚本或回复场景，**可指定发布到的 topic**：

```bash
# 必填：-token、-content；可选：-topic、-title、-server
./notice-cli send -server=http://localhost:9090 -token=your-token -content="回复内容" -title="回复"
./notice-cli send -server=http://localhost:9090 -token=your-token -topic=notice/alert -content="发到 alert 主题"
```

| 参数 | 默认值 | 说明 |
|-----|--------|------|
| -server | http://localhost:9090 | Notice 服务端 HTTP 地址（Webhook） |
| -token | (必填) | 认证 Token |
| -topic | (空) | 指定发布到的主题；不填则使用服务端默认主题 |
| -content | (必填) | 消息内容 |
| -title | CLI | 消息标题 |
| -client | cli | 发送端标识 |
| -compress-min-runes | 255 | 正文 Unicode 标量值数量 ≥ 此值且压缩后更短则对 Webhook 使用 `gzip+base64`；`0` 表示从不压缩 |

## Makefile 变量

| 变量 | 默认值 | 说明 |
|-----|--------|------|
| BROKER | tcp://localhost:9091 | MQTT Broker 地址 |
| TOPIC | notice/# | 订阅主题 |
| CLIENT_ID | cli-client | 客户端 ID |
| TOKEN | (空) | 认证 Token |
| EXEC | (空) | 收到消息时执行的命令 |
| MODE | env | 示例脚本模式：`env` 或 `stdin` |

```bash
# 使用 Makefile 运行
make run TOKEN=your-token
make run TOKEN=your-token BROKER=wss://example.com
make run TOKEN=your-token EXEC="./handler.sh"

# 使用内置示例脚本
make run-example TOKEN=your-token
make run-example TOKEN=your-token MODE=stdin
```

## 执行外部命令

使用 `-exec` 参数可以在收到消息时执行外部命令。消息内容通过以下方式传递：

### 环境变量

| 变量 | 说明 |
|------|------|
| NOTICE_TOPIC | 消息主题 |
| NOTICE_TITLE | 消息标题 |
| NOTICE_CONTENT | 消息内容 |
| NOTICE_EXTRA | 额外数据 (JSON 格式) |
| NOTICE_TIMESTAMP | 消息时间戳 (RFC3339 格式) |
| NOTICE_RAW | 原始 JSON 消息 |

### stdin

原始 JSON 消息也会通过 stdin 传递给命令。

### Linux/macOS 示例

```bash
# 执行脚本
./notice-cli -token=xxx -exec="./handler.sh"

# 使用 shell 命令
./notice-cli -token=xxx -exec="sh -c 'echo \$NOTICE_TITLE >> /tmp/notices.log'"

# 使用内置示例脚本
make run-example TOKEN=xxx
make run-example TOKEN=xxx MODE=stdin
```

### Windows 示例

```powershell
# 执行 PowerShell 脚本
.\notice-cli.exe -token=xxx -exec="powershell -File handler.ps1"

# 执行批处理
.\notice-cli.exe -token=xxx -exec="cmd /c handler.bat"
```

**handler.ps1 示例：**
```powershell
Write-Host "收到消息: $env:NOTICE_TITLE"
Write-Host "内容: $env:NOTICE_CONTENT"
```

**handler.bat 示例：**
```batch
@echo off
echo 收到消息: %NOTICE_TITLE%
echo 内容: %NOTICE_CONTENT%
```

## 连接地址格式

```bash
# TCP 连接（局域网）
tcp://192.168.1.100:9091

# WebSocket 连接（穿透防火墙）
ws://example.com:9092

# WebSocket + TLS（通过 Nginx/Cloudflare）
wss://notice-ws.example.com
```

## 测试

1. 运行客户端：
   ```bash
   ./notice-cli -broker=tcp://localhost:9091 -token=your-token
   ```

2. 在另一个终端发送测试消息：
   ```bash
   cd ../../server
   make test-push AUTH_TOKEN=your-token
   ```

3. 你应该能看到系统通知弹出！

## 故障排除

### 连接失败

1. 检查服务器是否运行
2. 确认 Token 是否正确
3. 如果服务器启用了认证，必须提供 `-token` 参数

### 通知不显示

**Linux:**
- 安装 libnotify: `sudo apt install libnotify-bin`
- 测试通知: `notify-send "测试" "内容"`
- 检查桌面环境的通知服务是否运行

**Windows:**
- 检查通知中心是否启用
- 确保应用通知权限已开启

**macOS:**
- 检查系统偏好设置中的通知权限

## 文件结构

```
client/cli/
├── main.go              # 主程序
├── notify_windows.go    # Windows 通知实现
├── notify_other.go      # Linux/macOS 通知实现
├── Makefile             # 构建和运行命令
├── go.mod               # Go 模块
├── scripts/
│   ├── start.sh         # Linux/macOS 启动脚本
│   ├── start.bat        # Windows 启动脚本
│   └── example.sh       # 消息处理示例脚本
└── build/               # 构建输出目录
```

## License

MIT
