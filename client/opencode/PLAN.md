# OpenCode Client — 实施计划

## 架构

```
Client A (web/android/cli)
  ↓ webhook/MQTT
Notice Server (云)
  ↓↕ MQTT (notice/opencode)
opencode-client (本地，Python)
  ↓ HTTP (localhost:4096)
OpenCode serve (本地)
```

与 Hermes/XiaoAi 模式一致：MQTT 订阅 → OpenCode API 处理 → MQTT 发布。

## 功能

- 连接 Notice Server 的 MQTT Broker（支持 `tcp://` / `ws://` / `wss://`）
- 订阅 `notice/opencode` 接收消息（同主题收发）
- **回环抑制**：跳过 `client == "opencode"` 的消息（与 Hermes 一致）
- 收到消息后调用本地 OpenCode serve API 处理
- 将 OpenCode 的回复发布到 `notice/opencode`
- **多目录 session 管理**：支持通过指令切换目录、创建/切换 session
- 消息格式兼容现有体系（gzip+base64 压缩/解压）
- **容错**：启动时健康检查、API 超时错误消息、自动重试

### 指令集

| 指令 | 作用 |
|------|------|
| `/dir <path>` | 切换目录（查询该目录最新 session） |
| `/dir` | 查询当前目录（无参数时查询，有参数时切换） |
| `/new` | 为当前目录创建新 session |
| `/list` | 列出当前目录的所有 session |
| `/switch <id>` | 切换到指定 session（前缀匹配，多匹配时要求更精确 ID） |
| `/model` | 查看当前模型 |
| `/model list` | 列出可用模型 |
| `/model set <model>` | 切换模型（provider/model 格式） |
| `/help` | 列出所有可用指令 |

## 目录结构

```
client/opencode/
├── .gitignore           # __pycache__/, *.pyc, .venv/, config.yaml.local, *.log
├── PLAN.md              # 本文件
├── opencode.py          # 主程序
├── config.yaml          # 配置模板（提交到 git）
├── config.yaml.local    # 本地配置（.local 覆盖 base，gitignore）
├── requirements.txt     # Python 依赖
├── Makefile             # 运行/停止
└── README.md            # 使用说明
```

## 配置

采用 `.local` 覆盖机制（与 XiaoAi 一致）：
- `config.yaml` — 配置模板，提交到 git
- `config.yaml.local` — 本地覆盖，优先级高于 base，已在 gitignore

```yaml
mqtt:
  broker_url: "wss://your-server.com"
  token: "your-token"
  topic: "notice/opencode"          # 订阅和发布的 topic（同一主题）

opencode:
  server_url: "http://localhost:4096"
  username: "opencode"              # Basic Auth 用户名（默认与 OpenCode serve 一致）
  password: ""                      # Basic Auth 密码（对应 OPENCODE_SERVER_PASSWORD）
  project_dir: "."                  # 默认工作目录（相对于配置文件所在目录）
  system_prompt: ""                 # 可选的系统提示词
  model: ""                         # 模型 (provider/model 格式，如 deepseek/deepseek-chat)
```

## 消息格式

### 发出的消息 payload

```json
{
  "title": "",
  "content": "消息内容（≥256 字符时 gzip+base64 压缩）",
  "client": "opencode",
  "timestamp": 1746182400000,
  "extra": {},
  "content_encoding": "gzip+base64"  // 压缩时添加
}
```

`client` 字段固定为 `"opencode"`，用于回环抑制。

## 消息流

### 普通消息

```
1. 用户通过任意客户端发消息到 notice/opencode
2. Notice Server 通过 MQTT 推送给 opencode-client
3. opencode-client 收到消息：
   a. 跳过 client == "opencode" 的消息（防回环）
   b. 立即发布"⏳ 处理中..."到同一 topic（typing 反馈，所有订阅者可见）
   c. POST /session/:id/prompt_async 到 OpenCode serve（异步）
4. 轮询 GET /session/:id/message 获取响应（间隔 2s，超时 120s）
5. OpenCode 完成后发布最终回复到 notice/opencode
6. 其他订阅了 notice/opencode 的客户端收到回复
```

### 指令消息

```
1. 用户发送 /dir, /new, /list, /switch, /help 指令
2. opencode-client 收到消息：
   a. 跳过 client == "opencode" 的消息（防回环）
   b. 解析指令并执行
   c. 发布执行结果到 notice/opencode（确认/列表等信息）
```

**指令回复格式**（Markdown）：
- 成功：`## ✓ 已切换\n\n- **目录**: \`/path\`\n- **Session**: \`ses_xxx\``
- 失败：`❌ 目录不存在: /path` 或 `❌ 未知指令: /xxx`
- 列表：Markdown 表格（ID、标题、状态标记）

### 轮询机制

- **端点**：`GET /session/:id/message?limit=30`
- **间隔**：2 秒
- **超时**：120 秒（超时发布 "❌ OpenCode 响应超时" 到 MQTT）
- **完成标志**：连续 5 次轮询无新内容（10 秒）判定完成

### Session 管理

通过 OpenCode API 实时查询，不维护本地缓存：

| 操作 | API 调用 |
|------|----------|
| 切换目录 | `GET /session?directory=<path>&limit=1` → 取最新 session |
| 创建 session | `POST /session?directory=<path>` |
| 列出 session | `GET /session?directory=<path>` |
| 切换 session | 直接设置活跃 session ID（前缀匹配，多匹配时要求更精确 ID） |

**边界情况**：
- 目录不存在：`/dir <path>` 先在本地验证 `Path.is_dir()`，失败回复 "❌ 目录不存在: <path>"（注意：此客户端设计为本地运行，`opencode-client` 与 `OpenCode serve` 共享同一文件系统，本地验证有效）
- 目录无 session：目录存在但查询无结果，回复 "❌ 目录 `<path>` 下无 session"
- Session 过期：查询到 404 时自动重建 session
- 前缀匹配歧义：多个 session 匹配时回复 "多个 session 匹配 '<prefix>'，请使用更精确的 ID"
- 指令即刻生效：指令执行后立即更新活跃 session，后续消息自动使用新 session

### 容错

| 场景 | 处理 |
|---|---|
| OpenCode serve 未启动 | 启动时 `GET /global/health` 检测，失败则退出并报错 |
| API 调用超时 | 发布错误消息到 MQTT（"❌ OpenCode 响应超时"），不阻塞后续消息 |
| 网络闪断 | MQTT 自动重连（paho-mqtt 内置），OpenCode API 重试 1 次 |

## 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 | Python 3.10+ | 与 Hermes/XiaoAi 一致，paho-mqtt + httpx 生态成熟 |
| MQTT | paho-mqtt | 项目已有客户端统一使用 |
| HTTP | httpx | 异步支持，类型安全 |
| Session | 多 session（按目录/API 查询） | 支持多项目、多 session 切换 |
| 并发 | 串行处理（asyncio 锁） | 避免 OpenCode 并发冲突 |
| 消息发送 | 异步 prompt + 轮询 | 避免阻塞 MQTT 回调线程，支持 typing 中间反馈 |

## 不需要改动

- **Notice Server** — 无需修改，纯 MQTT 消息转发
- **现有客户端** — 不受影响
