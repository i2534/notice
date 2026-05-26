# Hermes Notice 插件

Hermes Agent 的 Notice 平台适配器，通过 MQTT 接入 Notice 消息推送系统。

## 功能

- 实时接收 Notice 消息并转发给 Hermes Agent
- Hermes Agent 回复自动推送到 Notice 客户端
- 支持 Markdown 渲染
- 长消息自动 gzip+base64 压缩
- 图片上传到 Notice Server（可选配置）
- 自动跳过自身消息防止回环
- **语音消息转写**（ASR，可选配置）

## 安装

### 1. 安装依赖

在 Hermes 虚拟环境中安装：
```bash
pip install paho-mqtt
```

### 2. 安装插件

**开发模式（推荐）** — 软链接，代码修改后即时生效：
```bash
ln -sf /path/to/client/hermes ~/.hermes/plugins/notice
```

**复制方式**：
```bash
cp -r client/hermes/ ~/.hermes/plugins/notice
```

**pip 安装**（需要 `pyproject.toml` 配置 `py_modules`）：
```bash
pip install -e .
```

## 配置

编辑 `~/.hermes/config.yaml`：

```yaml
plugins:
  enabled: [notice]              # User 目录插件需要显式启用

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
| mediaAudio.enabled | | `false` | 是否启用语音转写 |
| mediaAudio.providers | | 无 | 转写后端列表（见下方 ASR 配置） |

### 语音转写 (ASR) 配置

启用 `mediaAudio` 后，Hermes 会自动识别包含语音消息的内容，下载音频文件并调用转写后端将语音转换为文本，再交给 Agent 处理。

#### 支持的后端类型

| 类型 | 说明 | 推荐场景 |
|------|------|----------|
| `qwen` | Qwen3 ASR API（远程 HTTP 调用） | 有现成 Qwen ASR 部署 |
| `cli` | 本地 CLI 工具（如 OpenAI Whisper） | 需要离线转写或自定义工具 |

#### `providers` 列表配置

支持配置多个后端，按顺序尝试（前一个成功则跳过后续）：

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

#### ASR 配置项说明

| 字段 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `type` | ✅ | - | 后端类型：`qwen` 或 `cli` |
| `url` | ✅ (API) | - | API 端点地址 |
| `token` | ✅ (API) | - | API 认证令牌 |
| `timeout` | | 300 (API) / 60 (CLI) | 超时秒数（最小 5） |
| `command` | ✅ (CLI) | - | CLI 工具绝对路径 |
| `args` | | `[]` | CLI 参数列表，`{{MediaPath}}` 会被替换为音频文件路径 |

#### 工作流程

1. 收到消息后检测是否为纯语音消息（只包含 `/api/media/` 音频链接）
2. 下载音频文件到临时目录
3. 按配置顺序调用后端进行转写（前一个成功则跳过后续）
4. 转写成功 → 将文本作为普通消息交给 Agent 处理
5. 转写失败 → 回传错误消息给客户端，终止处理

#### 错误提示

| 状态 | 返回消息 |
|------|----------|
| 未配置后端 | ⚠️ 语音转写未配置，请在 Hermes 配置中启用 mediaAudio |
| 下载失败 | ❌ 语音下载失败 |
| 转写失败/超时/无结果 | ❌ 语音转写失败 |

#### 扩展自定义 API 后端

`asr.py` 提供 `register_api_provider` 函数，支持注册自定义 API 后端实现：

```python
from asr import register_api_provider, ApiTranscriber

class MyApiTranscriber(ApiTranscriber):
    def _build_payload(self, b64_audio, mime):
        return {"audio": b64_audio, "mime": mime}

    def _parse_response(self, raw):
        return json.loads(raw).get("text")

register_api_provider("myapi", MyApiTranscriber)
```

注册后可在配置中使用：
```yaml
providers:
  - type: myapi
    url: "http://localhost:8080/transcribe"
    token: "..."
```

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

## 发送媒体消息的工作流程

Hermes Agent 调用 `send_message` 发送媒体文件到 Notice 平台时，走的是**专用 standalone 发送路径**，而非通用的 `_send_via_adapter`。

### 调用链

```
send_message_tool.py
  → notice 早期处理器（在 Feishu 处理器之后、白名单检查之前拦截）
  → _send_notice_standalone()
  → platform_registry.get("notice").standalone_sender_fn
  → adapter.py: _standalone_send(message, media_files)
  → 上传到 Notice Server /api/upload → MQTT 推送 markdown ![](url)
```

### 为什么不走 `_send_via_adapter`

`_send_via_adapter` 存在一个已知缺陷：当 Gateway 运行时（live adapter 路径），它调用 `adapter.send()` **不传递 `media_files` 参数**，导致媒体附件被静默丢弃。Notice 平台（以及 Matrix、Signal）通过专用函数绕过这个问题，直接调用 `standalone_sender_fn` 确保 `media_files` 完整透传。

### `_standalone_send` 实现

- 接收 `message`（文本）和 `media_files`（文件路径列表）
- 将本地文件上传到 Notice Server `/api/upload`
- 上传成功后构建 markdown 图片 `![](url)` 通过 MQTT 推送
- 支持图片、音频、视频等任意类型附件

### ⚠️ MEDIA 路径安全限制

`send_message` 中的 `MEDIA:<path>` 受 Hermes 安全策略过滤，文件必须在允许的目录中：

**默认允许的目录（新路径优先，旧路径兼容）：**
- `~/.hermes/cache/images/` 或 `~/.hermes/image_cache/`
- `~/.hermes/cache/audio/` 或 `~/.hermes/audio_cache/`
- `~/.hermes/cache/videos/` 或 `~/.hermes/video_cache/`
- `~/.hermes/cache/documents/` 或 `~/.hermes/document_cache/`
- `~/.hermes/cache/screenshots/` 或 `~/.hermes/browser_screenshots/`

**自定义目录：** 通过环境变量 `HERMES_MEDIA_ALLOW_DIRS` 追加。

**常见陷阱：** 文件放在 `/tmp/` 或其他非允许目录时，会被**静默丢弃**，只发送纯文本部分。

```
# ❌ 不会被发送（/tmp 不在允许列表中）
send_message(target="notice", message="MEDIA:/tmp/image.png")

# ✅ 正确做法 — 先复制到允许目录
cp /tmp/image.png ~/.hermes/image_cache/
send_message(target="notice", message="MEDIA:/home/user/.hermes/image_cache/image.png")
```

## 故障排除

### 插件未加载

Gateway 日志显示 `No messaging platforms enabled`:

**常见原因 1 — 相对导入失败**
```
Failed to load plugin 'notice': attempted relative import with no known parent package
```
Gateway 的插件加载器以单文件方式加载 `__init__.py`，不设置 parent package。
**解决方法**：`__init__.py` 和 `adapter.py` 中使用**绝对导入**而非相对导入：
```python
# ✅ 正确 — 绝对导入
from adapter import register
from asr import register_api_provider

# ❌ 错误 — 相对导入（在插件加载器中会失败）
from .adapter import register
from .asr import register_api_provider
```

**常见原因 2 — `plugin.yaml` 文件名大小写**
插件扫描器只识别小写的 `plugin.yaml` 或 `plugin.yml`。如果文件名为 `PLUGIN.yaml` 或 `Plugin.yaml`，插件不会被发现。
```bash
# 检查文件名
ls ~/.hermes/plugins/notice/plugin.yaml  # 必须是小写
```

**常见原因 3 — 缺少依赖**
```
ModuleNotFoundError: No module named 'paho'
```
```bash
pip install paho-mqtt
```

**通用排查步骤**：
- 确认 `plugins.enabled` 包含 `notice`
- 检查软链接是否正确：`ls -la ~/.hermes/plugins/notice/`
- 清除 `__pycache__`：`rm -rf ~/.hermes/plugins/notice/__pycache__`
- 重启 gateway：`hermes gateway restart`
- 查看日志确认加载：`tail -f ~/.hermes/logs/gateway.log | grep notice`

### 媒体消息发送失败

**错误提示**：`send_message MEDIA delivery is currently only supported for telegram, discord, matrix...`

**原因**：`send_message_tool.py` 中的白名单检查不包含 notice。

**解决方法**：在 `send_message_tool.py` 中，notice 处理器必须在 Feishu 处理器之后、白名单检查**之前**拦截，调用 `_send_notice_standalone` 而非 `_send_via_adapter`。

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

### 日志位置
- **主日志**：`~/.hermes/logs/gateway.log`（记录 `gateway.*` 模块日志，包括插件版本信息）
- **错误日志**：`~/.hermes/logs/errors.log`（记录插件加载失败、工具调用错误等）
- **Systemd 日志**：`journalctl --user -u hermes-gateway`（可能有缓冲延迟）

## 开发

```bash
# 设置软链接（开发模式）
ln -sf /path/to/client/hermes ~/.hermes/plugins/notice

# 重启 gateway 加载新代码
hermes gateway restart

# 查看插件是否加载成功（gateway 日志中搜索）
grep "notice" ~/.hermes/logs/gateway.log

# 实时查看日志
tail -f ~/.hermes/logs/gateway.log | grep notice
```

### 版本号管理

版本号在 `plugin.yaml`（必须小写）中配置，Gateway 启动时会自动读取并记录到日志：
```yaml
name: notice
version: 0.1.0
kind: platform
```
