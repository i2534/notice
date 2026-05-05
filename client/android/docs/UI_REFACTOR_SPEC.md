# Android 客户端 UI 重构 — 设计规格书

## 1. 项目背景

当前 Notice Android 客户端的消息列表以「灰色卡片 + 平铺」方式展示。在用户订阅多个 MQTT topic（如 `notice/#`）的场景下，消息来源无法一眼区分，信息层次不清晰。

本次重构参考 Telegram / Slack / 微信的主流聊天应用交互范式，将消息列表改造为**聊天气泡 + Topic 分组 + 日期分隔**的消息流布局。

---

## 2. 设计目标

| # | 目标 | 参考 |
|---|------|------|
| G1 | 不同 topic 的消息在视觉上**一眼可区分** | Telegram 彩色头像 / Slack #channel |
| G2 | 消息流具有清晰的**层次结构**（Topic 分组 > 日期 > 气泡） | Telegram / 飞书 |
| G3 | 信息密度合理，**阅读/回复**操作顺畅 | 微信 |
| G4 | 保持现有功能完整性（多选删除、详情弹窗、语音/图片渲染等） | N/A |

---

## 3. 整体布局结构

### 3.1 消息流层级（从上到下）

```
┌─────────────────────────────────────┐
│  Toolbar (标题 + 连接/回复/设置/关于) │  ← 保持
├─────────────────────────────────────┤
│                                     │
│  ┌──── Topic 分组头部 ────┐         │  ← NEW: 按 topic 分组
│  │   # notice/alert       │         │
│  ├─────────────────────────┤         │
│  │  ─── 昨天 ───           │         │  ← NEW: 日期分隔线
│  │                         │         │
│  │     (收到)    │    (发出)         │  ← 聊天气泡
│  │   [ 消息气泡  ]  [   消息气泡  ]   │
│  │                         │         │
│  │     (收到)               │         │
│  │   [ 消息气泡  ]          │         │
│  │                         │         │
│  │  ─── 今天 ───           │         │
│  │                         │         │
│  │   [ 消息气泡  ]          │         │
│  └─────────────────────────┘         │
│  ┌──── Topic 分组头部 ────┐         │
│  │   # notice/reply       │         │
│  └─────────────────────────┘         │
│                                     │
├─────────────────────────────────────┤
│  回复栏 (输入框 / 语音)              │  ← 保持
└─────────────────────────────────────┘
```

### 3.2 移除的元素

| 移除项 | 原因 |
|--------|------|
| 顶部「最新消息」大卡片（header） | 去掉冗余，最新一条消息已自然出现在列表底部 |

---

## 4. 详细设计规范

### 4.1 Topic 分组头部

- **样式**: 紧凑小条，居中显示，圆角 Chip 风格
- **内容**: `# {topic 名}`，例如 `# notice/alert`
- **配色**: 左侧 4dp 色条 + 圆角背景填充（半透明色），文字深色
- **间距**: 上下各 12dp，与消息气泡形成视觉分隔
- **交互**: 点击该 topic 头部 → 弹出该 topic 的「回复到」操作
- **布局文件**: `layout/item_topic_header.xml`（新建）

```
┌─────────────────────────────────────────┐
│  ▍ # notice/alert                       │  <-- 4dp 色条 + topic 文本
└─────────────────────────────────────────┘

```

### 4.2 Topic 颜色映射

- **策略**: 根据 topic 字符串的 MD5 hash 映射到 12 色色板，保证同一 topic 始终同色
- **色板** (Material Design 3 Tonal Palette):
  | Index | Color     | Name      |
  |-------|-----------|-----------|
  | 0     | `#0D7377` | Teal      |
  | 1     | `#FF6B35` | Orange    |
  | 2     | `#6B4FA0` | Purple    |
  | 3     | `#2E7D32` | Green     |
  | 4     | `#C62828` | Red       |
  | 5     | `#F57C00` | Amber     |
  | 6     | `#0288D1` | Blue      |
  | 7     | `#00897B` | Teal-Dark |
  | 8     | `#D81B60` | Pink      |
  | 9     | `#5D4037` | Brown     |
  | 10    | `#546E7A` | BlueGray  |
  | 11    | `#7CB342` | Lime      |

- **实现**: 新建 `util/TopicColor.kt`，`object TopicColor { fun forTopic(topic: String): Int }`

### 4.3 日期分隔线

- **样式**: 细横线（1dp）+ 居中文本（"今天" / "昨天" / "2025-05-01"）
- **颜色**: 横线 `text_hint` 颜色半透明，文本 `text_hint`
- **字号**: 11sp
- **间距**: 上下各 8dp
- **布局文件**: `layout/item_date_separator.xml`（新建）

### 4.4 聊天气泡

#### 4.4.1 收到消息（左侧气泡）

```
  ┌──────────────────────────────┐
  │ from: web                    │  ← 来源标识（可选，11sp）
  │ 10:30                        │  ← 时间（右侧）
  │                              │
  │  消息正文                     │  ← markdown 渲染 / 图片 / 语音
  │  多行内容...                  │
  └────────                      │
```

- **圆角**: 左上/左下/右下 16dp，右上 4dp
- **背景**: `surface_variant`（略深于页面背景）
- **宽度**: `wrap_content`，最大占比 80%
- **内边距**: 12dp
- **对齐**: 左对齐

#### 4.4.2 发出消息（右侧气泡）

```
                                ┌──────────────────────────────┐
                                │ 10:31                        │  ← 时间（右侧）
                                │                              │
                                │  消息正文                     │
                                │                              │
                                 ─────────────────┘
```

- **圆角**: 左下/右下/右上 16dp，左上 4dp
- **背景**: primary 色（`#0D7377`）
- **宽度**: `wrap_content`，最大占比 80%
- **内边距**: 12dp
- **对齐**: 右对齐
- **文字**: 全部白色

#### 4.4.3 气泡内内容

精简展示，**只显示时间和消息正文**。标题、topic、来源等元数据不显示在气泡内（点开详情可见）。

### 4.5 交互调整

| 交互 | 当前行为 | 重构后 |
|------|---------|--------|
| 点击消息 | 打开详情弹窗 | 保持 |
| 长按消息 | 进入多选模式 | 保持 |
| 多选删除 | 勾选 + 确认弹窗 | 保持 |
| 下拉刷新 | N/A（当前无） | N/A |
| 上拉加载 | Paging 分页加载历史 | 保持，滚动方向翻转 |
| 回复栏 | 顶部按钮切换显示/隐藏 | 保持 |

---

## 5. 数据结构与排序

### 5.1 排序方向

- **当前**: `ORDER BY timestamp DESC`（最新在顶部）
- **重构后**: `ORDER BY timestamp ASC`（最新在底部）
- 需要反转 PagingSource 的锚点逻辑（当前用倒数分页，改为正序分页）

### 5.2 分组逻辑

列表 Adapter 需要支持 3 种 ViewType：

```kotlin
sealed class MessageListItem {
    data class TopicHeader(val topic: String) : MessageListItem()
    data class DateSeparator(val dateLabel: String) : MessageListItem()
    data class MessageBubble(val message: NoticeMessage, val topic: String) : MessageListItem()
}
```

- 使用 `DiffUtil` 计算分组差异，避免重复渲染
- PagingSource 返回原始消息流，Adapter 层做「扁平消息 → 分组列表」的转换

---

## 6. 文件清单

### 6.1 新建文件

| 文件 | 说明 |
|------|------|
| `res/layout/item_topic_header.xml` | Topic 分组头部布局 |
| `res/layout/item_date_separator.xml` | 日期分隔线布局 |
| `res/layout/item_bubble_message.xml` | 聊天气泡布局（复用同一布局，通过圆角 drawable 区分左右） |
| `res/drawable/bg_bubble_incoming.xml` | 收到气泡背景（左对齐圆角） |
| `res/drawable/bg_bubble_outgoing.xml` | 发出气泡背景（右对齐圆角） |
| `res/drawable/bg_topic_header.xml` | Topic 头部背景（带左侧色条的圆角条） |
| `res/drawable/bg_date_separator.xml` | 日期分隔线背景 |
| `java/.../ui/MessageListItem.kt` | 分组数据模型 + DiffUtil callback |
| `java/.../ui/BubbleMessageAdapter.kt` | 聊天气泡 Adapter（MultiType） |
| `java/.../util/TopicColor.kt` | Topic → 颜色映射工具 |

### 6.2 修改文件

| 文件 | 改动 |
|------|------|
| `res/layout/activity_main.xml` | 去掉 header，调整 RecyclerView 占满空间 + scrollToEnd |
| `res/layout/item_message.xml` | **不删保留**，后续按需归档。不再被使用。 |
| `res/layout/header_message_list.xml` | **不删保留**，不再被使用。 |
| `res/values/colors.xml` | 新增 topic 色板颜色 |
| `java/.../ui/MainActivity.kt` | 替换 Adapter，scrollToEnd，去掉 header 相关逻辑 |
| `java/.../ui/MessageAdapter.kt` | **保留但不使用**。气泡渲染逻辑移入 BubbleMessageAdapter |
| `java/.../ui/MessageListHeaderAdapter.kt` | **保留但不使用**。 |
| `java/.../ui/MessageContentRenderer.kt` | 调整接口适配气泡场景（参数精简） |
| `java/.../data/MessageDao.kt` | 新增 `ORDER BY timestamp ASC` 的 PagingSource |
| `res/values/strings.xml` | 新增日期/气泡相关字符串 |
| `res/values/dimens.xml` | 新增气泡/分隔线相关尺寸 |

### 6.3 不需要改动的文件

| 文件 | 原因 |
|------|------|
| `MqttService.kt` | 不涉 UI |
| `NoticeApp.kt` | 不涉 UI |
| `SettingsActivity.kt` | 设置页不在本轮范围 |
| `AboutActivity.kt` | 不涉 UI |
| `LogsActivity.kt` | 不涉 UI |
| `dialog_message_detail.xml` | 详情弹窗保持，仅微调 topic 展示 |
| `ContentBlocks.kt` | 内容解析逻辑不变 |
| `data/` 下所有 | 数据模型不变 |
| `receiver/` 下所有 | 不涉 UI |
| `util/AppLogger.kt` | 不涉 UI |
| `util/MediaCache*.kt` | 不涉 UI |

---

## 7. 实施计划

### Phase 1: 基础设施（可独立并行）

| 任务 | 文件 | 说明 |
|------|------|------|
| 1.1 | `TopicColor.kt` | topic hash → 12 色板映射 |
| 1.2 | `colors.xml` / `dimens.xml` / `strings.xml` | 新增颜色、尺寸、字符串资源 |
| 1.3 | `bg_bubble_incoming.xml` / `bg_bubble_outgoing.xml` | 气泡背景 drawable（圆角矩形） |
| 1.4 | `bg_topic_header.xml` / `bg_date_separator.xml` | 分组头部、分隔线背景 drawable |

### Phase 2: 布局

| 任务 | 文件 | 说明 |
|------|------|------|
| 2.1 | `item_topic_header.xml` | Topic 头部: 4dp 色条 + `# topic` 文本 |
| 2.2 | `item_date_separator.xml` | 日期线: 横线 + 居中文字 |
| 2.3 | `item_bubble_message.xml` | 气泡: 时间 + 内容容器 |
| 2.4 | `activity_main.xml` | 去掉 header，RecyclerView 铺满 |

### Phase 3: Adapter 与分组逻辑

| 任务 | 文件 | 说明 |
|------|------|------|
| 3.1 | `MessageListItem.kt` | 分组数据模型 (TopicHeader / DateSeparator / MessageBubble) + DiffUtil |
| 3.2 | `BubbleMessageAdapter.kt` | 3 种 ViewHolder 的 MultiType PagingDataAdapter |
| 3.3 | `MessageDao.kt` | 新增 ASC 排序的 PagingSource |

### Phase 4: MainActivity 集成

| 任务 | 文件 | 说明 |
|------|------|------|
| 4.1 | `MainActivity.kt` | 替换为 BubbleMessageAdapter，去掉 header 逻辑，scrollToEnd |
| 4.2 | `dialog_message_detail.xml` | 微调 topic 展示（加入彩色标识） |

### Phase 5: 清理与验证

| 任务 | 说明 |
|------|------|
| 5.1 | 构建验证 — 无编译错误 |
| 5.2 | 功能验证 — 多选删除/详情/语音/图片 仍然可用 |
| 5.3 | UI 走查 — 暗色模式下各元素对比度达标 |
| 5.4 | 运行 `lsp_diagnostics` 检查所有改动文件 |

---

## 8. UI 线框图

### 8.1 完整页面

```
┌──────────────────────────────────────────────┐
│  Notice                            ○ ⋮       │ Toolbar
├──────────────────────────────────────────────┤
│                                              │
│         ▍ # notice/alert                     │ Topic Header
│                                              │
│  ──────────── 昨天 ──────────────────────────│ Date Separator
│                                              │
│   ┌──────────────────┐                       │
│   │ 14:30            │                       │ Incoming Bubble
│   │ 服务器 CPU 使用率  │                       │
│   │ 超过 90%         │                       │
│   └──────────────────┘                       │
│                                              │
│                   ┌──────────────────┐       │
│                   │           14:32  │       │ Outgoing Bubble
│                   │ 收到，我去看看    │       │
│                   │                  │       │
│                   ───────────────────┘       │
│                                              │
│   ┌──────────────────┐                       │
│   │ 15:01            │                       │
│   │ 已恢复正常        │                       │
│   └──────────────────┘                       │
│                                              │
│                                      ────────│ 滚动到底部
├──────────────────────────────────────────────┤
│  🎤  ┌──────────────────────────────┐  [↑]  │ Reply Bar
│       │ 输入回复内容…                │       │
│       └──────────────────────────────┘       │
└──────────────────────────────────────────────┘
```

### 8.2 多 Topic 场景

```
┌──────────────────────────────────────────────┐
│         ▍ # notice/alert                     │ Topic 1 Header
│                                              │
│   ┌──────────────────┐                       │
│   │ 10:30  CPU 告警   │                       │
│   └──────────────────┘                       │
│                                              │
├──────────────────────────────────────────────┤
│         ▍ # notice/deploy                    │ Topic 2 Header
│                                              │
│   ┌──────────────────┐                       │
│   │ 10:25  部署完成    │                       │
│   └──────────────────┘                       │
│                                              │
│                   ┌──────────────────┐       │
│                   │ 收到              │       │
│                   ───────────────────┘       │
│                                              │
├──────────────────────────────────────────────┤
│         ▍ # notice/infra                     │ Topic 3 Header
│                                              │
│   ┌──────────────────┐                       │
│   │ 09:00  磁盘告警    │                       │
│   └──────────────────┘                       │
│                                              │
├──────────────────────────────────────────────┤
│  🎤  ┌──────────────────────────────┐  [↑]  │ Reply Bar
│       │ 输入回复内容…                │       │
│       └──────────────────────────────┘       │
└──────────────────────────────────────────────┘
```

---

## 9. 约束与保持

| 约束 | 说明 |
|------|------|
| 暗色模式 | 全部颜色在深色背景上对比度 ≥ 4.5:1（WCAG AA） |
| minSdk 31 | 不使用低于 API 31 的兼容组件 |
| 现有功能 | 多选删除、ASR 语音确认、图片/语音渲染、详情弹窗全部保留 |
| 本地化 | 所有文字通过 `strings.xml`，不硬编码中文字符串 |
| 性能 | Paging 分页不受影响，分组仅在做视图层转换，不影响数据库查询 |
