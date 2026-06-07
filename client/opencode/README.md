# OpenCode Client — 接入 Notice 消息系统

将本地 OpenCode serve 接入 Notice 消息推送系统，通过 MQTT 收发消息。

## 架构

```
任意客户端 → Notice Server (云) → MQTT → opencode-client (本地) → OpenCode serve
```

## 安装

```bash
cd client/opencode
make prepare
```

## 配置

复制配置模板并修改：

```bash
cp config.yaml config.yaml.local
# 编辑 config.yaml.local
```

## 运行

```bash
make start     # 后台运行
make stop      # 停止
make logs      # 查看日志
```

## 指令

发送消息到 `notice/opencode` topic 时支持以下指令：

| 指令 | 作用 |
|------|------|
| `/dir <path>` | 切换工作目录 |
| `/dir` | 查询当前目录 |
| `/new` | 创建新 session |
| `/list` | 列出当前目录的 session |
| `/switch <id>` | 切换 session（前缀匹配，多匹配时报错） |
| `/model` | 查看当前模型 |
| `/model list` | 列出可用模型 |
| `/model set <model>` | 切换模型（provider/model 格式） |
| `/help` | 列出所有可用指令 |

普通消息会发送到当前活跃的 session 处理。
