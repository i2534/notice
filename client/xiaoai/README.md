# 小爱音箱 Pro Notice 客户端

将小爱音箱 Pro 接入 Notice 消息系统，实现双向通信：

- **接收**：Notice 推送消息 → 小爱 TTS 播报
- **发送**：用户对音箱说话 → 发布到 Notice

## 架构

```
Notice Server (公网)
       │ MQTT WSS
       ▼
xiaoai-client (Ubuntu 服务器)
       │ MiService API (小米云端)
       ▼
小爱音箱 Pro (局域网)
```

## 安装

```bash
cd client/xiaoai
pip install -r requirements.txt
```

或直接安装：

```bash
pip install paho-mqtt>=2.0.0 miservice-fork>=0.3.0 aiohttp>=3.8.0 pyyaml>=6.0
```

## 配置

复制配置模板并修改：

```bash
cp config.yaml config.local.yaml
```

编辑 `config.local.yaml`：

```yaml
mqtt:
  broker_url: "wss://your-notice-server.com:9092"
  token: "your-notice-token"
  subscribe_topic: "notice/xiaoai"
  publish_topic: "notice/from-xiaoai"

xiaoai:
  mi_user: "your-xiaomi-account@example.com"
  mi_pass: "your-xiaomi-password"
  device_id: ""  # 留空自动选择第一个设备
  token_cache: "~/.config/xiaoai/mi_token.json"

poll:
  enabled: true
  interval: 5
  filter_prefix: ""

tts:
  prefix: ""
  format: "{title}，{content}"
```

### 配置说明

| 配置项 | 说明 |
|--------|------|
| `mqtt.broker_url` | Notice Server 的 WebSocket 地址，支持 `wss://`、`ws://`、`tls://`、`tcp://` |
| `mqtt.token` | Notice Server 的认证 token |
| `mqtt.subscribe_topic` | 订阅主题，接收推送给小爱的消息 |
| `mqtt.publish_topic` | 发布主题，小爱语音发出的消息 |
| `xiaoai.mi_user` | 小米账号 |
| `xiaoai.mi_pass` | 小米密码 |
| `xiaoai.device_id` | 小爱设备 ID，留空自动选择第一个设备 |
| `xiaoai.token_cache` | Token 缓存路径，避免重复登录 |
| `poll.enabled` | 是否启用对话轮询 |
| `poll.interval` | 轮询间隔（秒） |
| `poll.filter_prefix` | 过滤唤醒词前缀 |
| `tts.prefix` | 播报前缀 |
| `tts.format` | 播报格式，`{title}` 和 `{content}` 为占位符 |

## 运行

```bash
python xiaoai.py --config config.local.yaml
```

或使用默认配置文件 `config.yaml`：

```bash
python xiaoai.py
```

调试模式：

```bash
python xiaoai.py --log-level DEBUG
```

## 使用场景

### 1. 接收消息播报

其他客户端发送消息到 `notice/xiaoai` 主题，小爱会自动播报：

```bash
# CLI 发送
notice-cli send -server=http://your-server:9090 -token=xxx -topic=notice/xiaoai -content="测试消息"

# 或通过 HTTP API
curl -X POST http://your-server:9090/webhook \
  -H "Authorization: Bearer your-token" \
  -H "Content-Type: application/json" \
  -d '{"title":"告警","content":"CPU 使用率超过 90%","topic":"notice/xiaoai"}'
```

小爱会播报："告警，CPU 使用率超过 90%"

### 2. 语音发送消息

对音箱说："帮我发送消息：今天下班早"

小爱客户端会轮询到这条对话，提取文本并发布到 `notice/from-xiaoai` 主题，所有订阅了该主题的客户端都会收到。

如果配置了 `filter_prefix: "帮我发送消息："`，前缀会被自动去掉。

## 获取设备 ID

运行客户端后会自动选择第一个设备。如需指定设备：

1. 登录小米账号后，客户端会列出设备
2. 或在小米智能家庭 App 中查看设备 ID

## 注意事项

1. **小米账号安全**：密码会明文存储在配置文件中，请确保配置文件权限安全 (`chmod 600 config.yaml`)

2. **Token 缓存**：登录后会缓存 token 到 `token_cache` 路径，避免频繁登录触发风控

3. **轮询频率**：建议 `poll.interval` 设为 3-5 秒，过快可能被限流

4. **网络要求**：
   - 客户端需要能访问 Notice Server（公网）
   - 客户端需要能访问小米云端 API
   - 小爱音箱只需在局域网内，通过云端接收指令

## 依赖

- [paho-mqtt](https://www.eclipse.org/paho/python/) - MQTT 客户端
- [miservice-fork](https://github.com/nicholasnguyen/miservice) - 小米云服务 API
- [aiohttp](https://docs.aiohttp.org/) - 异步 HTTP 客户端
- [pyyaml](https://pyyaml.org/) - YAML 解析
