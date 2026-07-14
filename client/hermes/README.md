# Hermes Notice 插件

Hermes Agent 的 Notice 平台适配器（plugin），通过 MQTT 接入 Notice 消息推送系统。  
**不修改 Hermes Agent 源码**；通过 `PluginContext.register_platform` + `standalone_sender_fn` 接入（对齐 Hermes ≥ 0.18）。

## 功能

- 实时接收 Notice 消息并转发给 Hermes Agent
- Agent 回复经 MQTT 推送到 Notice 客户端
- Markdown；长文本自动 `gzip+base64`
- 可选：本地文件上传到 Notice Server 后以 `![](url)` 发送
- 跳过 `client == "hermes"`，防回环
- 语音转写：`mediaAudio` 本地 providers，失败时回退 Hermes 全局 `stt`
- 平台契约：`connect(is_reconnect=...)`、`_mark_connected`、env、cron home channel、scoped lock

## 模块结构

| 文件 | 职责 |
|------|------|
| `adapter.py` | `NoticeAdapter` + `register()` |
| `config.py` | extra / `NOTICE_*` 环境变量、校验 |
| `payload.py` | Notice JSON 信封、gzip 编解码 |
| `mqtt_transport.py` | MQTT 客户端创建与 QoS1 发布 |
| `media.py` | `/api/upload` 与 Markdown 图片 |
| `coalesce.py` | 流式回复 debounce（无 edit_message） |
| `standalone.py` | cron / 无 live adapter 时的发送 |
| `asr.py` | 语音检测、下载、转写链 |
| `plugin.yaml` | 清单（`requires_env` / `optional_env`） |
| `tests/` | 纯函数单测（不连真 broker） |

## 安装

要求：**Hermes Agent ≥ 0.18**，Python ≥ 3.11。

### 1. 依赖

在 Hermes 使用的同一 Python 环境中：

```bash
pip install 'paho-mqtt>=2.0'
```

### 2. 安装插件（整目录软链）

必须把 **`client/hermes` 整个目录** 链到 `~/.hermes/plugins/notice`。  
不要只链单个 `.py`（重构后缺 `config.py` / `payload.py` 等会加载失败）。

```bash
# 开发模式（推荐）：整目录软链，改代码后 restart 即生效
rm -rf ~/.hermes/plugins/notice   # 若曾是「逐文件软链」目录，先清掉
ln -sfn /绝对路径/notice/client/hermes ~/.hermes/plugins/notice

# 确认是目录链接，且能看到全部模块
ls -la ~/.hermes/plugins/notice/
# 应能看到 adapter.py config.py payload.py mqtt_transport.py media.py ...

# 启用并重启
# ~/.hermes/config.yaml → plugins.enabled 含 notice
hermes gateway restart
```

复制安装：

```bash
cp -a client/hermes/. ~/.hermes/plugins/notice/
```

或 editable pip：

```bash
cd client/hermes && pip install -e .
```

## 配置

编辑 `~/.hermes/config.yaml`。**环境变量优先于 yaml**。

```yaml
plugins:
  enabled: [notice]

platforms:
  notice:
    enabled: true
    extra:
      brokerUrl: "wss://你的-notice-server.com"   # 或 tcp://localhost:9091
      token: "你的-auth-token"
      topic: "notice/#"
      serverUrl: "https://你的-notice-server.com"  # 可选，图片上传
```

### 环境变量

| 变量 | 说明 |
|------|------|
| `NOTICE_BROKER_URL` | MQTT Broker（覆盖 `brokerUrl`） |
| `NOTICE_TOKEN` | 认证令牌 |
| `NOTICE_TOPIC` | 订阅主题 |
| `NOTICE_SERVER_URL` | HTTP 基址（上传） |
| `NOTICE_HOME_CHANNEL` | cron / 通知默认投递 topic |
| `NOTICE_ALLOWED_USERS` | 客户端 allowlist（逗号分隔） |
| `NOTICE_ALLOW_ALL_USERS` | 允许全部客户端（仅开发，如 `1`/`true`） |

### 配置项

| 字段 | 必填 | 默认 | 说明 |
|------|------|------|------|
| brokerUrl | ✅ | - | `tcp://` / `ws://` / `wss://` |
| token | ✅ | - | 与 Server `auth.token` 一致 |
| topic | | `notice/#` | 订阅主题 |
| serverUrl | | 无 | HTTP，用于上传 |
| typingInterval | | `20` | 处理中 typing 间隔（秒，最小 5） |
| mediaAudio.enabled | | `false` | 是否处理纯语音消息 |
| mediaAudio.providers | | 无 | 本地转写后端列表 |

### 语音转写 (ASR)

启用 `mediaAudio.enabled` 后：识别「正文仅为 `/api/media/` 音频 URL」的消息 → 下载 → 转写 → 交给 Agent。

1. 按 `providers` 顺序尝试本地后端  
2. 全部失败 → 调用 Hermes 全局 `tools.transcription_tools.transcribe_audio`（需 `stt.enabled`）  
3. 仍失败 → 向原 topic 推送错误文案

```yaml
platforms:
  notice:
    enabled: true
    extra:
      brokerUrl: "wss://你的-notice-server.com"
      token: "你的-auth-token"
      mediaAudio:
        enabled: true
        providers:
          - type: qwen
            url: "http://localhost:12000/v1/chat/completions"
            token: "xxxxxx"
            timeout: 300
          - type: cli
            command: "/usr/bin/whisper"
            args: ["{{MediaPath}}", "--language", "zh", "--output-text"]
            timeout: 60
```

| 字段 | 必填 | 默认 | 说明 |
|------|------|------|------|
| `type` | ✅ | - | `qwen` / `cli` |
| `url` | API ✅ | - | 端点 |
| `token` | API | - | Bearer |
| `timeout` | | 300 / 60 | 秒，最小 5 |
| `command` | CLI ✅ | - | 可执行文件绝对路径 |
| `args` | | `[]` | `{{MediaPath}}` 替换为本地音频路径 |

**错误回传（示例）**

| 情况 | 消息 |
|------|------|
| 下载失败 | `❌ 语音下载失败` |
| 本地 + 全局 STT 均失败 | `❌ 语音转写失败（请配置 platforms.notice.extra.mediaAudio 或全局 stt）` |

自定义 API 后端：`asr.register_api_provider("myapi", MyApiTranscriber)`，配置里 `type: myapi`。

也可只开全局 STT、不配 `providers`（仍须 `mediaAudio.enabled: true` 才会走语音分支）。

## 消息格式

### 入站

```json
{
  "title": "通知标题",
  "content": "通知内容",
  "content_encoding": "gzip+base64",
  "extra": {},
  "timestamp": 1746182400000,
  "client": "android"
}
```

处理：JSON 解析 → 解压 → 跳过 `client == "hermes"` → `content` 作为 Agent 文本。

### 出站

```json
{
  "title": "",
  "content": "回复内容",
  "client": "hermes",
  "timestamp": 1746182400000,
  "extra": {},
  "content_encoding": "gzip+base64"
}
```

`content` ≥ 256 字符时可能压缩。错误/状态类消息应走 `_do_publish(明文, metadata)`，**禁止**把整段 JSON 再塞进 `content`（双重包装）。

## 连接方式

| 协议 | 示例 | 说明 |
|------|------|------|
| TCP | `tcp://localhost:9091` | 局域网 |
| WS | `ws://localhost:9092` | 局域网 |
| WSS | `wss://your-server.com/ws` | 公网推荐 |

## 发送路径（勿改 Hermes 核心）

插件通过官方扩展点注册，**不要**再给 `tools/send_message_tool.py` 打 notice 补丁。

### Gateway 在线（日常对话）

```
Agent 回复
  → NoticeAdapter.send / send_image
  → coalesce（流式合并）→ payload.build_outbound_payload
  → MQTT publish
```

图片：`send_image` / `send_image_file` → `media.resolve_media_url`（本地路径则上传）→ Markdown。

### 无 live adapter（cron / 部分 `send_message`）

```
send_message / cron
  → platform_registry["notice"].standalone_sender_fn
  → standalone.standalone_send(...)
  → 上传（如有 media_files）+ MQTT
```

`register()` 已设置 `standalone_sender_fn=standalone_send` 与 `cron_deliver_env_var=NOTICE_HOME_CHANNEL`。

### 上游 MEDIA 白名单限制（Hermes 现状）

Hermes `send_message` 对「仅 MEDIA、无正文」仍只对部分内置平台放行（telegram / discord / …）。  
**Notice 不在该白名单内**：纯 `MEDIA:...` 可能报错或被省略附件。

可行做法：

1. **推荐**：在 Notice 会话里让 Agent 直接回复（走 live `NoticeAdapter`），图片用 `send_image` 路径。  
2. `send_message` 时带**非空文本** + 允许目录下的 `MEDIA:`（附件仍可能被上游省略，以实际 Hermes 版本为准）。  
3. 需要一等公民 MEDIA 时：向上游 PR 把 notice 纳入媒体分支，或走 registry `standalone_sender_fn` 的专用分支——**仍不要本地 fork 改白名单糊弄**。

### MEDIA 路径安全

文件须在 Hermes 允许目录（或 `HERMES_MEDIA_ALLOW_DIRS`）：

- `~/.hermes/cache/images/`（兼容 `image_cache/`）
- `~/.hermes/cache/audio/`、`videos/`、`documents/`、`screenshots/` 等

`/tmp/` 等不在列表中的路径会被丢弃。

```bash
cp /tmp/image.png ~/.hermes/cache/images/
# 再由 Agent / send_message 引用该路径
```

## 故障排除

### 插件未加载

**相对导入**

```
attempted relative import with no known parent package
```

插件加载器不设 parent package，须用绝对导入：`from adapter import register`，不要用 `from .adapter import ...`。

**逐文件软链 / 缺模块**

```
ModuleNotFoundError: No module named 'config'
```

说明 `~/.hermes/plugins/notice` 不是整目录。按上文用 `ln -sfn .../client/hermes ~/.hermes/plugins/notice` 重装。

**plugin.yaml 大小写**

必须为小写 `plugin.yaml`。

**依赖**

```bash
pip install 'paho-mqtt>=2.0'
```

**通用**

```bash
# plugins.enabled 含 notice
ls -la ~/.hermes/plugins/notice/   # 应为目录 → 仓库 client/hermes
rm -rf ~/.hermes/plugins/notice/__pycache__
hermes gateway restart
hermes gateway status              # Notice 应为 configured / connected
grep notice ~/.hermes/logs/gateway.log
```

### MEDIA / send_message 失败

若提示 `MEDIA delivery is currently only supported for telegram, discord, ...`：  
这是 **上游白名单**，不是 Notice 插件缺补丁。见上文「上游 MEDIA 白名单限制」，不要改 Hermes 核心。

### 连接超时

- 核对 `brokerUrl` / `NOTICE_BROKER_URL`
- Server 是否监听对应端口；公网用 WSS

### MQTT 认证失败（如 code 5）

- `token` 与 Server `auth.token` 一致

### 收不到消息

- `topic` 与发送端一致
- `hermes gateway --log-level=debug`
- 日志：`~/.hermes/logs/gateway.log`、`errors.log`；可选 `journalctl --user -u hermes-gateway`

## 开发与测试

```bash
cd client/hermes
ln -sfn "$(pwd)" ~/.hermes/plugins/notice
hermes gateway restart

# 纯函数单测（无需 gateway）
python3 -m unittest tests.test_payload_config -v
```

版本号以 `plugin.yaml` 的 `version` 为准（当前 **1.1.0**），启动日志会打印 `notice plugin v...`。

```yaml
name: notice
label: Notice
kind: platform
version: 1.1.0
```
