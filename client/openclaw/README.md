# Notice Channel Plugin for Openclaw

提供 **notice** Channel，通过 MQTT 连接 Notice Broker 发送和接收消息（不经过 Webhook）。

## 安装

### 方式一：使用打包好的插件（推荐）

从 [GitHub Releases](https://github.com/i2534/notice/releases) 下载 `notice-openclaw-<版本>.zip`，解压到本地目录后安装：

```bash
# 解压到任意目录，例如 ~/.openclaw/plugins/notice
unzip notice-openclaw-v1.0.0.zip -d ~/.openclaw/plugins/notice
cd ~/.openclaw/plugins/notice
npm install
openclaw plugins install .
```

打包内已包含插件清单与源码，无需克隆完整仓库。

### 方式二：从源码目录链接安装

适合参与开发或需要改源码时使用。Openclaw 支持从本地目录链接加载，不复制文件：

```bash
cd /path/to/notice/client/openclaw
npm install
openclaw plugins install -l .
# 或
openclaw plugins install --link .
```

`-l`/`--link` 会把当前目录加入 `plugins.load.paths`，插件从该目录加载。

若之前用「复制安装」失败或已建了 `~/.openclaw/extensions/notice/`，可先删掉再改用链接：

```bash
rm -rf ~/.openclaw/extensions/notice
openclaw plugins install -l .
```

## 配置

```yaml
plugins:
  load:
    paths: ["/path/to/notice/client/openclaw"]   # -l 时会自动写入
  entries:
    notice:
      enabled: true
      config: {}

channels:
  notice:
    enabled: true
    brokerUrl: "wss://your-notice-broker.com"   # MQTT Broker 地址（订阅与发送共用）
    token: "your-auth-token"                    # 与 Notice Server auth.token 一致
    topic: "notice/openclaw"                     # 订阅主题，发送时转为可发布主题（如 notice/# → notice）
    blockStreaming: true                         # 按块流式发送（默认逐条发出）
    blockStreamingBreak: "text_end"              # text_end=逐条，message_end=整条结束后再发
    maxContentLength: 0                          # 默认 0=不限制整段发送；>0 为分块时每块最大 rune 数（经 Webhook 时勿超过服务端限制）
    compressMinRunes: 0                          # 仅发布端：≥此 rune 数且 gzip+base64 更短才编码；0=从不压缩
    allowSlashCommands: true                     # 是否将「/」开头入站消息标记为 CommandAuthorized
```

若主机不读取通道级 `blockStreamingBreak`，需在 openclaw 主配置中设置 `agents.defaults.blockStreamingBreak: "text_end"` 以实现逐条发送。

**消息长度**：`maxContentLength` 默认 **0**（不限制、整段 MQTT 发送）。设为 **>0** 时按每块最大 **rune** 数分块；若内容会经 Notice **Webhook** 写入，需 **≤** 服务端 `message.max_content_length`。纯 MQTT 不经 Webhook 时服务端不按 `max_content_length` 校验正文。

**载荷压缩**：`compressMinRunes`（默认 0）>0 时，仅当正文足够长且 `gzip+base64` 比 UTF-8 字节更短时，MQTT JSON 会带 `content_encoding: gzip+base64`。服务端不配置压缩，仅转发；各客户端与消息入库需支持解码。

**/ 命令**：`allowSlashCommands`（默认 true）为 true 时，以 `/` 开头的入站正文会设置 `CommandAuthorized`（Dispatch）并在 `/hooks/agent` 请求中带 `commandAuthorized`。MQTT 与 token 同权，请评估信任模型。

## 收发方式

- **接收**：MQTT 订阅 `channels.notice.topic`（可含通配符，如 `notice/#`）。
- **发送**：同一 MQTT 连接上直接 `publish`，不经过 Webhook。发布主题由配置的 `topic` 经 `topicForPublish` 转换（去掉 `#` 及后缀、`+` 换为 `reply`），与 Notice Server 规则一致，保证自收自发。

## 功能

- **发送**：通过 MQTT 发布到同一 Broker，可指定 topic（`ctx.to` 或通道 `topic`）。
- **接收**：MQTT 订阅，消息可通过 RPC `notice.getRecentMessages` 查询。
- **回信**：Agent 回复通过 MQTT 发回来信同一 topic，实现「收信 → 处理 → 回信」闭环。
- **媒体/图片**：回信时可带 `mediaUrl` 或 `mediaUrls`（或正文中的本地路径）。配置 `serverUrl` 与 `token` 后，本地图片会先上传到 Notice 的 `POST /api/upload`，返回的图片 URL 以 Markdown `![](url)` 写入内容；已是 `http(s):` 或协议相对 `//host/path` 的 URL 会直接使用，不再当成本地文件上传。
- **语音消息**：收到语音（消息内容为音频 URL）时，插件会下载音频并调用 Openclaw 的 `runtime.stt.transcribeAudioFile` 做转写，再通过 MQTT 发送「请确认语音转写」给客户端；用户确认后转写文本才投递给 Agent。语音转写依赖 Openclaw 主配置中的 **`tools.media.audio`**（如 `~/.openclaw/openclaw.json`），详见 [Openclaw 文档](https://openclaw.dev/docs#tools-media)。未配置时用户端会看到提示文案并附带配置说明。

## Openclaw STT 处理流程（源码说明）

插件调用 `api.runtime.stt.transcribeAudioFile({ filePath, cfg })` 时，Openclaw 内部流程如下（基于 `openclaw/openclaw` 仓库 `src/media-understanding/`）：

1. **入口** `transcribe-audio.ts`：接收 `cfg`（应为完整 OpenClawConfig），调用 `runAudioTranscription({ ctx, cfg, agentDir })`。
2. **runner** `audio-transcription-runner.ts`：用 `ctx` 构造附件列表，然后调用 `runCapability({ capability: "audio", cfg, config: cfg.tools?.media?.audio, ... })`。即 **config 来自 `cfg.tools.media.audio`**。
3. **runCapability**（`runner.ts`）：
   - 若 `config?.enabled === false` 或 scope 拒绝，直接返回无输出。
   - **entries 来源**：先 `resolveModelEntries({ cfg, capability, config })` 得到 `config?.models` 与 `cfg.tools?.media?.models` 中带 `audio` capability 的条目；若 **entries 为空**，再 `resolveAutoEntries(...)` 自动检测：本地 CLI（sherpa-onnx → whisper-cli → whisper）→ Gemini CLI → 按 `AUTO_AUDIO_KEY_PROVIDERS` 顺序用各 provider 的 key。
   - 对每个 attachment 调用 **runAttachmentEntries**：按 entries 顺序依次尝试，直到某个 entry 返回非 null 结果。
4. **runAttachmentEntries**（`runner.ts`）：遍历 `entries`，对每条调用 `runProviderEntry` 或 `runCliEntry`。**只要 `result` 非 null，就立即 `return { output: result, attempts }`，不再尝试后续 entry。** 若 result 为 null，会 push 一条 `outcome: "skipped", reason: "empty output"` 并继续下一个 entry。
5. **runProviderEntry**（`runner.entries.ts`，audio 分支）：调用 provider 的 `transcribeAudio`，然后 **无条件** `return { kind: "audio.transcription", text: trimOutput(result.text, maxChars), ... }`。**即使 `result.text` 为空字符串，也返回该对象（truthy），不会返回 null。**
6. **runCliEntry**（`runner.entries.ts`）：CLI 输出解析后若 `text` 为空会 **`return null`**，从而触发对下一个 entry 的回退。

因此：**当第一个尝试的是 provider（如 OpenAI）且该 provider 返回空文本时，Openclaw 会认为本次转写“成功”并返回空字符串，不会回退到 `tools.media.audio.models` 中的下一个或 auto 检测到的下一个。** 只有抛错、或走 CLI 且 CLI 输出为空时才会试下一个。若需“空结果也回退”，需在 Openclaw 的 `runProviderEntry` 中对 audio 的 `result.text` 做空判断并返回 null（与 `runCliEntry` 一致）。
