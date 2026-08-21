# Android MQTT 假活连接修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 消除 Android 客户端「显示已连接但收不到消息」的僵尸连接问题——通过主动探活 + 网络监听 + 周期兜底重连，让假活连接在 5 分钟内被自动检测并修复，不再依赖用户手动重连。

**Architecture:** 三层修复，全部在客户端 `MqttConnectionManager`/`KeepAliveDecision` 完成（服务端零改动）：

1. **探活（核心）**：`KeepAliveDecision` 将「本地标志 connected」从 `HEALTHY` 改为 `PROBE`——仅当主动探活通过才视为健康。探活方式：向服务端发布 QoS 1 到 `$notice/ping`（服务端 `MessageStoreHook.OnPublish` 跳过 `$` 开头主题，不入库、不转发，见 `server/broker/broker.go:394`），`waitForCompletion(10s)` 收到 PUBACK 即证明双向连通；超时/异常即判定假活 → 强制重连。探活 publish 本身也是 MQTT 活跃流量，顺带防止 NAT/代理会话超时。
2. **网络监听**：`ConnectivityManager.registerDefaultNetworkCallback` 在 WiFi↔蜂窝切换、断网/恢复时立即触发保活检查，不再等 10 分钟闹钟。
3. **兜底**：保活闹钟 10min → 5min；心跳线程增加「连接存活超过 12h 强制重建」检查，覆盖探活遗漏的僵尸场景。

**Tech Stack:** Kotlin, Android (API 29+), Eclipse Paho MQTT v3 (`org.eclipse.paho.client.mqttv3`), kotlinx.coroutines, JUnit4 (JVM 单测)。

**Spec:** 根因分析结论（2026-08-21 会话）：
- Paho `isConnected()` 是本地标志，仅在 `connectionLost` 回调触发时置 false；Android Doze 冻结/网络切换/NAT 代理超时下 `connectionLost` 不触发或滞后 2×keepalive（默认 240s+），形成僵尸连接。
- 应用层 `KeepAliveDecision.decide()`（`client/android/.../util/MessageHistorySync.kt:27-37`）只检查 `stateConnected && mqttConnected`（两个都是本地标志）→ 假活时判定 `HEALTHY`，永不自愈。
- 心跳线程（`MqttConnectionManager.kt:341-351`）只打日志，无探活/纠错动作。
- 手动重连有效的原因：`MqttService.connect()`（`MqttService.kt:212`）无状态检查强制重建连接，且 `onConnected() → syncMissedMessages()` 从 HTTP 补齐漏消息。

## Global Constraints

- 服务端零改动（`$` 主题已跳过入库，探活无副作用）。
- 仅在 `client/android/` 目录操作（独立 Gradle 模块，勿动 `server/` 与其他客户端）。
- 不引入新依赖（用现有 Paho API + Android 框架）。
- 提交规范：Conventional Commits，scope `android`（如 `fix(android): ...`），git message 用简体中文。
- 验证命令：`cd client/android && ./gradlew :app:testDebugUnitTest`（单测）、`./gradlew :app:assembleDebug`（构建）。
- 探活 publish 必须使用 `$` 开头主题（服务端跳过入库/转发），不得用 `notice/...` 业务主题（会产生垃圾历史消息 + 自收自回显）。
- 探活/重连竞态：所有修改 `mqttClient` 或执行 connect 的路径必须经 `connectMutex` 串行化，禁止在持有 `connectMutex` 时再调用 `connectMqtt()`（其内部 `tryLock` 会失败）。

---

### Task 1: KeepAliveDecision 改造 + 强制重连判定纯函数

**Files:**
- Modify: `client/android/app/src/main/java/com/github/i2534/notice/util/MessageHistorySync.kt:21-37`
- Test: `client/android/app/src/test/java/com/github/i2534/notice/util/MessageHistorySyncTest.kt:129-167`

**Interfaces:**
- Consumes: 无（纯逻辑改造）。
- Produces:
  - `KeepAliveAction` 枚举成员变为 `SKIP` / `RECONNECT` / `PROBE`（`HEALTHY` 移除）。
  - `KeepAliveDecision.decide(userDisconnected: Boolean, stateConnected: Boolean, mqttConnected: Boolean): KeepAliveAction` — 语义变化：`stateConnected && mqttConnected` 时返回 `PROBE`（不再返回 `HEALTHY`）。
  - 顶层函数 `shouldForceReconnect(stateConnected: Boolean, mqttConnected: Boolean, lastConnectTime: Long, now: Long, forceIntervalMs: Long): Boolean` — 供 Task 4 心跳兜底使用。

- [x] **Step 1: 更新现有测试（先红）**

修改 `MessageHistorySyncTest.kt` 中 `keepAliveDecision_healthyWhenFullyConnected`（第 157-167 行）为探活语义，并新增 `shouldForceReconnect` 测试：

```kotlin
@Test
fun keepAliveDecision_probeWhenLocalFlagsConnected() {
    // 本地标志 connected 不代表 TCP 真实连通（Paho isConnected 是本地标志），必须主动探活
    assertEquals(
        KeepAliveAction.PROBE,
        KeepAliveDecision.decide(
            userDisconnected = false,
            stateConnected = true,
            mqttConnected = true
        )
    )
}

@Test
fun shouldForceReconnect_trueWhenConnectionOlderThanInterval() {
    val intervalMs = 12 * 60 * 60 * 1000L
    assertTrue(shouldForceReconnect(true, true, 1000L, 1000L + intervalMs, intervalMs))
    assertTrue(shouldForceReconnect(true, true, 1000L, 1000L + intervalMs + 1, intervalMs))
}

@Test
fun shouldForceReconnect_falseWhenRecentOrNotConnected() {
    val intervalMs = 12 * 60 * 60 * 1000L
    assertFalse(shouldForceReconnect(true, true, 1000L, 1000L + intervalMs - 1, intervalMs))
    assertFalse(shouldForceReconnect(false, true, 1000L, 999999999L, intervalMs))
    assertFalse(shouldForceReconnect(true, false, 1000L, 999999999L, intervalMs))
}
```

- [x] **Step 2: 运行测试确认失败**

Run: `cd client/android && ./gradlew :app:testDebugUnitTest --tests "com.github.i2534.notice.util.MessageHistorySyncTest" -i`
Expected: FAIL — 编译失败（`KeepAliveAction.HEALTHY` 不存在 / `shouldForceReconnect` 未定义）。

- [x] **Step 3: 改造 `MessageHistorySync.kt`**

将 `MessageHistorySync.kt` 第 21-37 行替换为：

```kotlin
enum class KeepAliveAction {
    SKIP,        // 用户主动断开，不做任何事
    RECONNECT,   // 显式断开（状态或 mqtt 本地标志为 false），直接重连
    PROBE        // 本地标志为 connected，需主动探活确认（防假活）
}

object KeepAliveDecision {
    fun decide(
        userDisconnected: Boolean,
        stateConnected: Boolean,
        mqttConnected: Boolean
    ): KeepAliveAction {
        if (userDisconnected) return KeepAliveAction.SKIP
        if (!stateConnected || !mqttConnected) return KeepAliveAction.RECONNECT
        // 本地标志不可信：Paho isConnected 是本地状态，僵尸连接下仍为 true，必须主动探活
        return KeepAliveAction.PROBE
    }
}

/**
 * 心跳兜底：连接已建立超过 forceIntervalMs 且仍显示 connected 时，强制重建连接。
 * 覆盖探活遗漏的僵尸场景（如 Paho 线程异常、探活 publish 恰好被吞等）。
 */
fun shouldForceReconnect(
    stateConnected: Boolean,
    mqttConnected: Boolean,
    lastConnectTime: Long,
    now: Long,
    forceIntervalMs: Long
): Boolean {
    if (!stateConnected || !mqttConnected) return false
    return now - lastConnectTime >= forceIntervalMs
}
```

- [x] **Step 4: 运行测试确认通过**

Run: `cd client/android && ./gradlew :app:testDebugUnitTest --tests "com.github.i2534.notice.util.MessageHistorySyncTest" -i`
Expected: PASS（含原有 resolveHttpBaseUrl / filterMissed / unwrapNested 等用例）。

- [x] **Step 5: 提交**

```bash
cd client/android
git add app/src/main/java/com/github/i2534/notice/util/MessageHistorySync.kt \
        app/src/test/java/com/github/i2534/notice/util/MessageHistorySyncTest.kt
git commit -m "fix(android): KeepAliveDecision 改为探活语义，新增强制重连判定函数"
```

---

### Task 2: MqttConnectionManager 主动探活 + 强制重连

**Files:**
- Modify: `client/android/app/src/main/java/com/github/i2534/notice/service/MqttConnectionManager.kt`
  - 新增常量（第 70-76 行区域）：`PROBE_TOPIC`、`PROBE_TIMEOUT_MS`
  - 新增 `probeAlive()`（`handleKeepAlive` 附近）
  - 新增 `forceReconnect()`（`handleKeepAlive` 附近）
  - 改造 `handleKeepAlive()` 的 `when(action)`（第 298-321 行）：`HEALTHY ->` 分支改为 `PROBE ->`，`RECONNECT ->` 分支改用 `forceReconnect()`

**Interfaces:**
- Consumes: Task 1 的 `KeepAliveAction.PROBE`；`MqttConnectionManager` 已有成员 `connectMutex` / `mqttClient` / `_connectionState` / `connectionRef` / `configStore` / `userDisconnected` / `cancelReconnectJob()` / `closeClientQuietly()` / `connectMqtt(settings)`。
- Produces: `suspend fun forceReconnect()`（Task 3/4 复用）。

- [x] **Step 1: 新增探活与强制重连方法**

在 `MqttConnectionManager.kt` 的 `handleKeepAlive()` 之前插入：

```kotlin
private val PROBE_TOPIC = "\$notice/ping"
private val PROBE_TIMEOUT_MS = 10_000L
```

（`PROBE_TOPIC` 必须以 `$` 开头——服务端 `MessageStoreHook.OnPublish` 对 `$` 主题跳过入库与转发，PUBACK 即证明双向连通，且无任何业务副作用。）

在 `handleKeepAlive()` 之后新增两个方法：

```kotlin
/**
 * 主动探活：向服务端发布 QoS1 探测消息并等待 PUBACK。
 * 僵尸连接（TCP 半开）下服务端收不到 publish、不回 PUBACK，waitForCompletion 超时 → 判定假活。
 * 探活消息同时是 MQTT 活跃流量，可防止 NAT/代理会话静默超时。
 */
private suspend fun probeAlive(): Boolean {
    if (!connectMutex.tryLock()) return true // 正在连接中，不打扰（连接流程会自愈状态）
    return try {
        val client = mqttClient
        if (client == null || !client.isConnected) return false
        try {
            client.publish(PROBE_TOPIC, byteArrayOf(0), 1, false)
                .waitForCompletion(PROBE_TIMEOUT_MS)
            true
        } catch (e: MqttException) {
            AppLogger.w(TAG, "Probe failed: ${e.message}")
            false
        }
    } finally {
        connectMutex.unlock()
    }
}

/**
 * 强制重建连接：纠正状态并走 connectMqtt（内部 close 旧 client + 新建连接 + 重新订阅）。
 * 适用于假活/探活失败/周期兜底，不依赖 Paho connectionLost 回调。
 * 注意：本方法不得持有 connectMutex（connectMqtt 内部 tryLock），仅纠正状态后调用。
 */
suspend fun forceReconnect() {
    if (userDisconnected) return
    if (_connectionState.value != MqttService.ConnectionState.DISCONNECTED) {
        _connectionState.value = MqttService.ConnectionState.DISCONNECTED
    }
    cancelReconnectJob()
    val settings = configStore.settings.first()
    connectMqtt(settings)
}
```

- [x] **Step 2: 改造 `handleKeepAlive()` 的决策分支**

将 `MqttConnectionManager.kt` 第 298-321 行的 `when (action)` 替换为：

```kotlin
        when (action) {
            KeepAliveAction.SKIP -> {
                AppLogger.d(TAG, "Keep-alive: user disconnected, skip")
            }
            KeepAliveAction.PROBE -> {
                // handleKeepAlive 非 suspend，探活需在 scope 内执行
                scope.launch {
                    if (probeAlive()) {
                        AppLogger.d(TAG, "Keep-alive: connection healthy (probe ok)")
                    } else {
                        AppLogger.w(TAG, "Keep-alive: probe failed, forcing reconnect")
                        forceReconnect()
                    }
                }
            }
            KeepAliveAction.RECONNECT -> {
                AppLogger.d(TAG, "Keep-alive: not healthy, attempting reconnect...")
                scope.launch { forceReconnect() }
            }
        }
```

> 说明：原 `RECONNECT` 分支中「先纠正状态再 connect」的逻辑已并入 `forceReconnect()`，分支体简化为一行。

- [x] **Step 3: 编译验证**

Run: `cd client/android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL（`KeepAliveAction.HEALTHY` 已无引用，`PROBE` 正常编译）。

- [x] **Step 4: 提交**

```bash
cd client/android
git add app/src/main/java/com/github/i2534/notice/service/MqttConnectionManager.kt
git commit -m "fix(android): MQTT 主动探活（\$notice/ping QoS1），探活失败强制重连"
```

---

### Task 3: 网络状态监听（ConnectivityManager）

**Files:**
- Modify: `client/android/app/src/main/java/com/github/i2534/notice/service/MqttConnectionManager.kt`
  - 新增 `connectivityManager` lazy、`networkCallback`（`handleKeepAlive` 附近）
  - 新增 `registerNetworkCallback()` / `unregisterNetworkCallback()`（`registerDozeReceiver` 附近）
- Modify: `client/android/app/src/main/java/com/github/i2534/notice/service/MqttService.kt:96-99`（onCreate）与 `:201-203`（onDestroy）

**Interfaces:**
- Consumes: Task 2 的 `handleKeepAlive()`（已有）；`context`（已有）。
- Produces: `fun registerNetworkCallback()`、`fun unregisterNetworkCallback()`（Task 3 内部 + MqttService 生命周期接线）。

- [x] **Step 1: 新增网络回调字段与方法**

在 `MqttConnectionManager.kt` 的 `dozeReceiver`（第 93-105 行）之后新增：

```kotlin
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /**
     * 网络变化即时触发保活检查，覆盖 WiFi↔蜂窝切换 / 断网恢复等假活高发场景，
     * 无需等 5 分钟保活闹钟。
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AppLogger.d(TAG, "Network available, checking connection...")
            handleKeepAlive()
        }

        override fun onLost(network: Network) {
            AppLogger.d(TAG, "Network lost")
            handleKeepAlive()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                AppLogger.d(TAG, "Network validated, checking connection...")
                handleKeepAlive()
            }
        }
    }
```

在 `registerDozeReceiver()`（第 268-277 行）之后新增：

```kotlin
    fun registerNetworkCallback() {
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            AppLogger.d(TAG, "Network callback registered")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            AppLogger.d(TAG, "Network callback unregistered")
        } catch (_: Exception) {
        }
    }
```

> `registerDefaultNetworkCallback` 要求 API 24+；项目 minSdk 29（Android 10），无需版本分支。

- [x] **Step 2: MqttService 生命周期接线**

`MqttService.kt` onCreate（第 96-99 行）的 `connectionManager.apply { ... }` 块中加入一行：

```kotlin
            registerNetworkCallback()
```

onDestroy（第 201-203 行）中加入一行：

```kotlin
        connectionManager.unregisterNetworkCallback()
```

- [x] **Step 3: 编译验证**

Run: `cd client/android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [x] **Step 4: 提交**

```bash
cd client/android
git add app/src/main/java/com/github/i2534/notice/service/MqttConnectionManager.kt \
        app/src/main/java/com/github/i2534/notice/service/MqttService.kt
git commit -m "fix(android): 网络切换即时触发保活检查，不再等 5 分钟闹钟"
```

---

### Task 4: 保活间隔缩短 + 心跳周期强制重连兜底

**Files:**
- Modify: `client/android/app/src/main/java/com/github/i2534/notice/service/MqttConnectionManager.kt`
  - 第 76 行：`keepAliveInterval` 10min → 5min
  - 第 73-74 行区域：新增 `forceReconnectInterval` 常量
  - 第 341-351 行：`startHeartbeat()` 增加周期强制重连检查

**Interfaces:**
- Consumes: Task 1 的 `shouldForceReconnect()`；Task 2 的 `forceReconnect()`；已有 `connectionRef.lastConnectTime`（`connectMqtt` 成功时已更新，见第 171 行）。
- Produces: 无新接口。

- [x] **Step 1: 调整常量与心跳逻辑**

将第 76 行：

```kotlin
    private val keepAliveInterval = 10 * 60 * 1000L
```

替换为：

```kotlin
    private val keepAliveInterval = 5 * 60 * 1000L
    // 兜底：连接存活超过该时长则强制重建（覆盖探活遗漏的僵尸场景），12 小时
    private val forceReconnectInterval = 12 * 60 * 60 * 1000L
```

将 `startHeartbeat()`（第 341-351 行）替换为：

```kotlin
    fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(heartbeatInterval)
                val state = _connectionState.value
                val mqttConnected = mqttClient?.isConnected == true
                AppLogger.d(TAG, "Heartbeat: alive, state=$state, mqtt=$mqttConnected")
                if (shouldForceReconnect(
                        stateConnected = state == MqttService.ConnectionState.CONNECTED,
                        mqttConnected = mqttConnected,
                        lastConnectTime = connectionRef.lastConnectTime,
                        now = System.currentTimeMillis(),
                        forceIntervalMs = forceReconnectInterval
                    )
                ) {
                    AppLogger.w(TAG, "Heartbeat: connection older than ${forceReconnectInterval / 3_600_000}h, forcing reconnect")
                    forceReconnect()
                }
            }
        }
    }
```

- [x] **Step 2: 运行单元测试**

Run: `cd client/android && ./gradlew :app:testDebugUnitTest -i`
Expected: PASS（含 Task 1 新增用例）。

- [x] **Step 3: 编译验证**

Run: `cd client/android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [x] **Step 4: 提交**

```bash
cd client/android
git add app/src/main/java/com/github/i2534/notice/service/MqttConnectionManager.kt
git commit -m "fix(android): 保活间隔缩短至 5 分钟，心跳兜底 12 小时强制重连"
```

---

### Task 5: 端到端验证与日志复核

**Files:** 无代码改动（验证任务）。

- [x] **Step 1: 全量单测 + 构建**

Run: `cd client/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL，全部测试通过。

- [x] **Step 2: 代码复核关键点**

逐项核对（对照 Global Constraints）：
1. 探活主题为 `$notice/ping`（`$` 开头，无业务副作用）。
2. `probeAlive()` 内 `tryLock` 与 `finally unlock` 成对；`forceReconnect()` 内**不**持有 `connectMutex`。
3. `KeepAliveAction.HEALTHY` 全仓库无残留引用：`grep -rn "HEALTHY" app/src/main/` 无结果。
4. 心跳 `shouldForceReconnect` 判定与 Task 1 纯函数签名一致。

- [x] **Step 3: 提交验证结论**

在提交前确认 `git status` 仅含 Task 1-4 涉及文件；向用户报告验证结果，由用户决定是否打包安装测试（`make docker` 或 Android Studio 安装）。

---

## 验证场景（安装后手动测试）

| 场景 | 操作 | 预期 |
|------|------|------|
| 假活自动恢复 | 锁屏 10 分钟以上再唤醒 | 日志出现 `probe failed` / `forcing reconnect` 或 `connection healthy`；无需手动操作 |
| 网络切换 | 关 WiFi 开蜂窝（或反向） | 日志即时出现 `Network ... checking connection`；连接自动恢复，消息不丢（syncMissedMessages 补洞） |
| 周期兜底 | 持续连接 12 小时 | 日志出现 `connection older than 12h, forcing reconnect` |
| 无副作用 | 观察服务端消息历史 | `GET /messages` 无 `$notice/ping` 探测垃圾消息 |