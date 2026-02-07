# Notice Channel Plugin for Openclaw

提供 **notice** Channel，通过 Notice Server 发送和接收消息。

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
    serverUrl: "https://your-notice-server.com"
    token: "your-auth-token"
    brokerUrl: "wss://..."   # 可选，用于接收
    topic: "notice/#"       # 可选，订阅主题；若为 notice/openclaw 等子主题，发送时需带相同 topic 才能收到
```

## 收发 topic 一致

- 插件 **订阅** 的 MQTT 主题由 `channels.notice.topic` 决定（如 `notice/#` 或 `notice/openclaw`）。
- 服务端 **发布** 时使用的主题来自 webhook 请求里的 `topic`；若不传则用服务端默认（如 `notice`）。
- **若你订阅的是 `notice/openclaw`，发送时必须带 `topic: "notice/openclaw"`**，服务端才会发布到该主题，同一客户端才能收到。否则服务端会发到默认主题（如 `notice`），只订阅 `notice/openclaw` 的客户端收不到。
- 插件在调用 webhook 时应始终把当前通道配置的 `topic` 转成「可发布」形式（与服务端 `topicForPublish` 一致：去掉 `#` 及后缀、`+` 换成 `reply`）再传给 `body.topic`。

## 功能

- **发送**：通过 Webhook POST 到 Notice Server；**可指定 topic**（`ctx.to` 或通道 `topic`），请求体带 `topic` 与订阅一致才能自收自发。
- **接收**：MQTT 订阅，消息可通过 RPC `notice.getRecentMessages` 查询。
- **回信**：在 Openclaw 中由 Agent 产生的回复可通过插件发回 Notice，并**发回来信同一 topic**，实现「收信 → 处理 → 回信」闭环。
