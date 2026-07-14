package com.github.i2534.notice.util

import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeParseException

data class RemoteHistoryMessage(
    val id: Long,
    val topic: String,
    val title: String,
    val content: String,
    val timestampMs: Long
)

data class HistoryPage(
    val messages: List<RemoteHistoryMessage>,
    val hasMore: Boolean,
    val nextId: Long
)

enum class KeepAliveAction {
    SKIP,
    HEALTHY,
    RECONNECT
}

object KeepAliveDecision {
    fun decide(
        userDisconnected: Boolean,
        stateConnected: Boolean,
        mqttConnected: Boolean
    ): KeepAliveAction {
        if (userDisconnected) return KeepAliveAction.SKIP
        if (!stateConnected || !mqttConnected) return KeepAliveAction.RECONNECT
        return KeepAliveAction.HEALTHY
    }
}

object MessageHistorySync {
    private const val TAG = "MessageHistorySync"

    fun resolveHttpBaseUrl(serverUrl: String, brokerUrl: String): String? {
        val fromServer = serverUrl.trim().trimEnd('/')
        if (fromServer.isNotEmpty()) return fromServer

        val broker = brokerUrl.trim()
        if (broker.isEmpty()) return null

        return try {
            when {
                broker.startsWith("wss://", ignoreCase = true) -> {
                    val rest = broker.removePrefix("wss://").removePrefix("WSS://")
                    val hostPort = rest.substringBefore('/').substringBefore('?')
                    if (hostPort.isBlank()) null else "https://$hostPort"
                }
                broker.startsWith("ws://", ignoreCase = true) -> {
                    val rest = broker.removePrefix("ws://").removePrefix("WS://")
                    val hostPort = rest.substringBefore('/').substringBefore('?')
                    if (hostPort.isBlank()) null else "http://$hostPort"
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun fingerprint(topic: String, content: String): String {
        return "${topic.trim()}|${content.trim()}"
    }

    /** 与入库内容对齐：先 unwrap 嵌套信封再算指纹，避免 HTTP 原始 JSON 与本地明文对不上。 */
    fun normalizedFingerprint(topic: String, content: String): String {
        val normalized = unwrapNestedNoticeContent(content)?.content ?: content
        return fingerprint(topic, normalized)
    }

    /** HTTP / MQTT 统一用的服务端消息 id 字符串；兼容旧版 hist-{id}。 */
    fun serverIdKeys(serverId: Long): Set<String> {
        if (serverId <= 0) return emptySet()
        return setOf(serverId.toString(), "hist-$serverId")
    }

    fun localHasServerId(localIds: Set<String>, serverId: Long): Boolean {
        return serverIdKeys(serverId).any { it in localIds }
    }

    /**
     * 与 Web [normalizeMessagePayload] 对齐：若 content 本身是嵌套的 Notice 信封 JSON
     *（含 title / content），展开为可读字段，避免双重包装时气泡显示整段 JSON。
     */
    data class UnwrappedFields(
        val title: String?,
        val content: String,
        val client: String?
    )

    fun unwrapNestedNoticeContent(rawContent: String): UnwrappedFields? {
        val raw = rawContent.trim().trimStart('\uFEFF')
        if (raw.length < 10) return null
        val start = raw.indexOf('{')
        if (start < 0) return null
        val end = raw.lastIndexOf('}')
        if (end <= start) return null
        val substr = raw.substring(start, end + 1)
        if (!substr.contains("\"content\"") && !substr.contains("\"title\"")) return null
        return try {
            val parsed = JSONObject(substr)
            // 仅当存在 content 键时展开，避免把普通 JSON 文本误当成 Notice 信封
            if (!parsed.has("content")) return null
            UnwrappedFields(
                title = parsed.optString("title", "").takeIf { it.isNotBlank() },
                content = parsed.optString("content", ""),
                client = parsed.optString("client", "").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun parseTimestamp(raw: Any?): Long? {
        when (raw) {
            null, JSONObject.NULL -> return null
            is Number -> {
                val v = raw.toLong()
                // seconds vs millis heuristic
                return if (v in 1_000_000_000_000L..9_999_999_999_999L) v else v * 1000
            }
            is String -> {
                val s = raw.trim()
                if (s.isEmpty()) return null
                s.toLongOrNull()?.let { v ->
                    return if (v in 1_000_000_000_000L..9_999_999_999_999L) v else v * 1000
                }
                return try {
                    java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                    try {
                        Instant.parse(s).toEpochMilli()
                    } catch (_: DateTimeParseException) {
                        null
                    }
                }
            }
            else -> return null
        }
    }

    fun parseHistoryPage(jsonBody: String): HistoryPage? {
        return try {
            val root = JSONObject(jsonBody)
            if (!root.optBoolean("success", false)) return null
            val data = root.optJSONObject("data") ?: return null
            val arr = data.optJSONArray("messages") ?: return HistoryPage(emptyList(), false, 0)
            val messages = mutableListOf<RemoteHistoryMessage>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optLong("id", 0)
                if (id <= 0) continue
                val ts = parseTimestamp(item.opt("timestamp")) ?: continue
                messages.add(
                    RemoteHistoryMessage(
                        id = id,
                        topic = item.optString("topic", "notice"),
                        title = item.optString("title", "通知"),
                        content = item.optString("content", ""),
                        timestampMs = ts
                    )
                )
            }
            HistoryPage(
                messages = messages,
                hasMore = data.optBoolean("has_more", false),
                nextId = data.optLong("next_id", 0)
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "parseHistoryPage failed: ${e.message}")
            null
        }
    }

    /**
     * 过滤漏消息：优先按服务端 id 去重（含旧 hist-{id}），其次用规范化 topic+content 指纹
     *（兼容升级前无 id 的 MQTT 本地消息）。
     */
    fun filterMissed(
        remote: List<RemoteHistoryMessage>,
        afterTimestampMs: Long,
        localIds: Set<String>,
        localFingerprints: Set<String>
    ): List<RemoteHistoryMessage> {
        return remote.filter { msg ->
            msg.timestampMs > afterTimestampMs &&
                !localHasServerId(localIds, msg.id) &&
                normalizedFingerprint(msg.topic, msg.content) !in localFingerprints
        }
    }
}
