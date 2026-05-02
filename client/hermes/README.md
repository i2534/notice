# Hermes Notice 插件

Hermes Agent 的 Notice 平台适配器，通过 MQTT 接入 Notice 消息推送系统。

## 功能

- 实时接收 Notice 消息并转发给 Hermes Agent
- Hermes Agent 回复自动推送到 Notice 客户端
- 支持 Markdown 渲染
- 长消息自动 gzip+base64 压缩
- 图片上传到 Notice Server（可选配置）
- 自动跳过自身消息防止回环

## 安装

### 1. 安装依赖
```bash
pip install paho-mqtt
```

### 2. 安装插件
```bash
# 方式一：直接复制目录到 Hermes 插件目录
cp -r client/hermes/ ~/.hermes/plugins/

# 方式二：pip 安装（开发模式）
pip install -e .
```

## 配置

编辑 `~/.hermes/config.yaml`：

```yaml
platforms:
  notice:
    enabled: true
    extra:
      brokerUrl: "wss://你的-notice-server.com"    # 或 tcp://localhost:9091
      token: "你的-auth-token"                      # Notice Server 的 auth.token
      topic: "notice/#"                             # 订阅主题 (默认 notice/#)
      serverUrl: "https://你的-notice-server.com"   # 可选，用于图片上传
```

### 配置项说明

| 字段 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| brokerUrl | ✅ | - | MQTT Broker 地址 (支持 `tcp://`、`ws://`、`wss://`) |
| token | ✅ | - | Notice Server 认证令牌 |
| topic | | `notice/#` | 订阅的主题模式 |
| serverUrl | | 无 | Notice Server HTTP 地址，用于图片上传等功能 |

## 消息格式

### 接收到的消息
```json
{
  "title": "通知标题",
  "content": "通知内容",
  "content_encoding": "gzip+base64",   // 可选，内容被 gzip+base64 压缩
  "extra": {},                          // 额外数据
  "timestamp": "2026-05-02T10:00:00Z",
  "client": "web"                       // 发送端标识: web / android / cli / webhook
}
```

收到消息后，Hermes 会：
1. 解析 JSON 格式
2. 自动解压 `gzip+base64` 编码的内容
3. 跳过 `client == "hermes"` 的消息（防回环）
4. 提取 `content` 字段作为消息文本

### 发送的消息
```json
{
  "title": "",
  "content": "回复内容",
  "client": "hermes",
  "timestamp": 1746182400000,
  "extra": {},
  "content_encoding": "gzip+base64"     // 内容 >= 256 字符时自动压缩
}
```

## 连接方式

| 协议 | 地址示例 | 安全性 |
|------|----------|--------|
| TCP | `tcp://localhost:9091` | ⚠️ 仅适合局域网 |
| WebSocket | `ws://localhost:9092` | ⚠️ 仅适合局域网 |
| WSS | `wss://your-server.com` | ✅ 推荐公网使用 |

## 环境变量

```bash
# 允许所有用户使用 Notice 平台（默认需要配置）
export NOTICE_ALLOW_ALL_USERS=1
```

## 故障排除

### 连接失败
```log
Error: Connection timeout
```
- 检查 `brokerUrl` 地址是否正确
- 确认 Notice Server 正在运行
- 公网部署确保使用 WSS + 证书

### Token 认证失败
```log
Error: MQTT connection failed with code 5
```
- 检查 `token` 是否与 Notice Server 配置一致
- 确认 Notice Server 的 `auth.token` 设置

### 消息未收到
- 确认 `topic` 配置与发送端匹配
- 检查 Hermes 日志：`hermes gateway --log-level=debug`

## 开发

```bash
# 运行测试
python -m pytest tests/ -v

# 查看插件注册信息
hermes plugins list | grep notice
```
