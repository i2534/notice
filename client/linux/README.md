# Notice Client (Linux)

Linux 桌面通知客户端，连接到 Notice Server 接收消息并显示系统通知。

## 功能

- 📡 连接 MQTT Broker 订阅消息
- 🔔 收到消息后显示 Linux 桌面通知
- 🔐 支持 Token 认证
- 🔄 自动重连

## 快速开始

### 1. 安装依赖

```bash
go mod tidy
```

### 2. 确保 Notice Server 已运行

```bash
cd ../server
make run
```

### 3. 运行客户端

```bash
make run
```

## 配置

### 命令行参数

| 参数 | 默认值 | 说明 |
|-----|--------|------|
| -broker | tcp://localhost:9091 | MQTT Broker 地址 |
| -topic | notice/# | 订阅的主题 |
| -id | linux-client | 客户端 ID |
| -token | (空) | 认证 Token |

### 使用示例

```bash
# 基本用法（需要服务器的 Token）
make run TOKEN=your-secret-token

# 连接到远程服务器
make run BROKER=tcp://192.168.1.100:9091 TOKEN=your-token

# 使用 WebSocket 连接（通过 Cloudflare Tunnel）
make run BROKER=wss://notice-ws.example.com TOKEN=your-token

# 订阅自定义主题
make run TOPIC=order/# TOKEN=your-token
```

### Makefile 变量

| 变量 | 默认值 | 说明 |
|-----|--------|------|
| BROKER | tcp://localhost:9091 | MQTT Broker 地址 |
| TOPIC | notice/# | 订阅主题 |
| CLIENT_ID | linux-client | 客户端 ID |
| TOKEN | (空) | 认证 Token |

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
   make run TOKEN=your-token
   ```

2. 在另一个终端发送测试消息：
   ```bash
   cd ../server
   make test-push AUTH_TOKEN=your-token
   ```

3. 你应该能看到 Linux 桌面通知弹出！

## 构建

```bash
make build
./notice-client -broker=tcp://server:9091 -token=your-token
```

## 故障排除

### 连接失败

1. 检查服务器是否运行
2. 确认 Token 是否正确
3. 如果服务器启用了认证，必须提供 `-token` 参数

### 通知不显示

1. 确保系统支持桌面通知 (需要 libnotify)
2. 检查通知服务是否运行

## License

MIT
