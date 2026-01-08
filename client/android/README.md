# Notice Android Client

Android MQTT 客户端，用于接收 Notice Server 的推送通知。

## 功能特性

- ✅ 支持 TCP 和 WebSocket 连接
- ✅ 支持 TLS/SSL 加密 (ssl://, wss://)
- ✅ Token 认证
- ✅ 后台前台服务，保持连接
- ✅ 开机自动启动
- ✅ 系统通知推送
- ✅ 消息历史记录
- ✅ 配置持久化（自动保存）
- ✅ 节能优化 (可配置心跳间隔)
- ✅ 自动重连

## 系统要求

- Android 12 (API 31) 或更高版本
- 网络权限

## 构建

### 方式一: Docker 构建 (推荐，无需安装 Android SDK)

```bash
cd client/android

# Debug 版本
make docker

# Release 版本（需要 keystore）
make docker-release KEYSTORE_PASSWORD=your-password
```

首次构建会下载 Android SDK (~1.5GB)，后续构建使用缓存。

构建完成后 APK 输出到: `app/build/outputs/apk/`

### 方式二: 本地构建 (需要 Android SDK)

```bash
# 需要先安装 Android SDK 和 JDK 17+
cd client/android

make build          # Debug
make build-release  # Release
```

### 方式三: Android Studio

1. 用 Android Studio 打开 `client/android` 目录
2. 等待 Gradle 同步完成
3. 点击 Run 运行到设备

### 构建命令

```bash
make help           # 查看所有命令
make docker         # Docker 构建 Debug APK
make docker-release # Docker 构建 Release APK
make docker-rebuild # 强制重新构建 Docker 镜像
make docker-clean   # 清理 Docker 镜像
make clean          # 清理构建产物
```

### Release 签名配置

1. 生成 keystore：
   ```bash
   keytool -genkey -v -keystore release.keystore \
     -alias notice -keyalg RSA -keysize 2048 -validity 10000
   ```

2. 构建 Release：
   ```bash
   make docker-release KEYSTORE_PASSWORD=xxx KEY_ALIAS=notice
   # KEY_PASSWORD 默认与 KEYSTORE_PASSWORD 一致
   ```

## 配置

### 默认配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| Broker 地址 | `wss://mqtt.example.com` | MQTT Broker 地址 |
| 订阅主题 | `notice/#` | 订阅的 MQTT 主题 |
| 心跳间隔 | 30 秒 | MQTT KeepAlive |
| 开机自启 | 开启 | 开机后自动连接 |
| 认证 Token | (空) | 服务器认证令牌 |

### 连接地址格式

```
# WebSocket (推荐，穿透防火墙)
wss://example.com        # WebSocket + TLS (端口443)
ws://example.com:9092    # WebSocket 明文

# TCP
ssl://example.com:8883   # TCP + TLS
tcp://192.168.1.100:9091 # TCP 明文（局域网）
```

### 认证配置

在设置页面输入服务器的 `AUTH_TOKEN`，应用会通过 MQTT username 传递 Token 进行认证。

## 消息格式

客户端支持两种消息格式:

### JSON 格式 (推荐)

```json
{
  "title": "通知标题",
  "content": "通知内容"
}
```

### 纯文本格式

直接发送文本，标题将使用主题名称。

## 项目结构

```
app/src/main/java/com/github/i2534/notice/
├── NoticeApp.kt              # Application 类
├── data/
│   ├── MqttConfig.kt         # 配置管理 (DataStore)
│   └── NoticeMessage.kt      # 消息数据类
├── service/
│   └── MqttService.kt        # MQTT 后台服务
├── receiver/
│   └── BootReceiver.kt       # 开机启动接收器
└── ui/
    ├── MainActivity.kt       # 主界面
    ├── SettingsActivity.kt   # 设置界面
    └── MessageAdapter.kt     # 消息列表适配器
```

## 依赖库

- [Eclipse Paho MQTT](https://github.com/eclipse/paho.mqtt.java) - MQTT 客户端
- AndroidX DataStore - 配置存储
- Kotlin Coroutines - 异步处理
- Material Design 3 - UI 组件

## 节能说明

为了节省电量，应用采用以下策略:

1. **前台服务**: 使用低优先级通知，不打扰用户
2. **心跳优化**: 默认 30 秒心跳，可根据需要调整
3. **自动重连**: 断线后延迟 5 秒重连，避免频繁连接
4. **清理会话**: 使用 `cleanSession=false` 减少重复订阅

## 故障排除

### 无法连接

1. 检查网络连接
2. 确认 Broker 地址格式正确
3. 确认认证 Token 正确
4. 如果使用 WebSocket，确认服务器支持
5. 查看 Logcat 中的错误日志

### 收不到通知

1. 检查通知权限是否开启
2. 确认订阅主题与发布主题匹配
3. 检查消息格式是否正确

### 后台被杀

1. 将应用加入电池优化白名单
2. 在系统设置中允许后台运行
3. 部分国产 ROM 需要额外设置自启动权限

### Docker 构建问题

1. 首次构建需要下载依赖，可能较慢
2. 构建缓存保存在 `.gradle-cache/` 目录
3. 如遇问题，尝试 `make docker-rebuild` 重建镜像

## License

MIT
