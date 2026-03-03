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
```

若主机不读取通道级 `blockStreamingBreak`，需在 openclaw 主配置中设置 `agents.defaults.blockStreamingBreak: "text_end"` 以实现逐条发送。

## 收发方式

- **接收**：MQTT 订阅 `channels.notice.topic`（可含通配符，如 `notice/#`）。
- **发送**：同一 MQTT 连接上直接 `publish`，不经过 Webhook。发布主题由配置的 `topic` 经 `topicForPublish` 转换（去掉 `#` 及后缀、`+` 换为 `reply`），与 Notice Server 规则一致，保证自收自发。

## 功能

- **发送**：通过 MQTT 发布到同一 Broker，可指定 topic（`ctx.to` 或通道 `topic`）。
- **接收**：MQTT 订阅，消息可通过 RPC `notice.getRecentMessages` 查询。
- **回信**：Agent 回复通过 MQTT 发回来信同一 topic，实现「收信 → 处理 → 回信」闭环。
- **媒体/图片**：回信时可带 `mediaUrl` 或 `mediaUrls`（或正文中的本地路径）。配置 `serverUrl` 与 `token` 后，本地图片会先上传到 Notice 的 `POST /api/upload`，返回的图片 URL 以 Markdown `![](url)` 写入内容；已是 `http(s):` 或协议相对 `//host/path` 的 URL 会直接使用，不再当成本地文件上传。
