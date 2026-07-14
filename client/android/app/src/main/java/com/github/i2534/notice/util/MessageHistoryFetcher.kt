package com.github.i2534.notice.util

import com.github.i2534.notice.data.NoticeMessage
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 Notice HTTP API 拉取消息历史，补齐 MQTT 离线/会话过期期间漏掉的消息。
 */
object MessageHistoryFetcher {
    private const val TAG = "MessageHistoryFetcher"
    private const val MAX_PAGES = 5
    private const val PAGE_SIZE = 50

    /**
     * @return 需要写入本地的漏消息（id 为服务端数字字符串，与 MQTT 注入的 id 一致）
     */
    fun fetchMissed(
        baseUrl: String,
        token: String,
        afterTimestampMs: Long,
        localIds: Set<String>,
        localFingerprints: Set<String>
    ): List<NoticeMessage> {
        val collected = mutableListOf<RemoteHistoryMessage>()
        var beforeId = 0L
        var pages = 0
        val ids = localIds.toMutableSet()
        val fingerprints = localFingerprints.toMutableSet()

        while (pages < MAX_PAGES) {
            pages++
            val page = fetchPage(baseUrl, token, beforeId, PAGE_SIZE) ?: break
            if (page.messages.isEmpty()) break

            val missed = MessageHistorySync.filterMissed(
                remote = page.messages,
                afterTimestampMs = afterTimestampMs,
                localIds = ids,
                localFingerprints = fingerprints
            )
            collected.addAll(missed)
            missed.forEach {
                ids.addAll(MessageHistorySync.serverIdKeys(it.id))
                fingerprints.add(MessageHistorySync.normalizedFingerprint(it.topic, it.content))
            }

            // 本页最旧一条已不新于本地水位，无需再往前翻
            val oldest = page.messages.minOfOrNull { it.timestampMs } ?: break
            if (oldest <= afterTimestampMs || !page.hasMore || page.nextId <= 0) break
            beforeId = page.nextId
        }

        // 按时间升序写入，便于列表追加
        return collected
            .sortedBy { it.timestampMs }
            .map { it.toNoticeMessage() }
    }

    private fun fetchPage(
        baseUrl: String,
        token: String,
        beforeId: Long,
        pageSize: Int
    ): HistoryPage? {
        var connection: HttpURLConnection? = null
        return try {
            val qs = buildString {
                append("page_size=").append(pageSize)
                if (beforeId > 0) append("&before_id=").append(beforeId)
            }
            val url = URL("${baseUrl.trimEnd('/')}/messages?$qs")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }
            if (code != 200) {
                AppLogger.w(TAG, "history fetch failed code=$code body=${body.take(200)}")
                return null
            }
            MessageHistorySync.parseHistoryPage(body)
        } catch (e: Exception) {
            AppLogger.e(TAG, "history fetch error: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun RemoteHistoryMessage.toNoticeMessage(): NoticeMessage {
        var outTitle = title.ifBlank { "通知" }
        var outContent = content
        MessageHistorySync.unwrapNestedNoticeContent(content)?.let { nested ->
            outContent = nested.content
            nested.title?.let { outTitle = it }
        }
        return NoticeMessage(
            id = id.toString(),
            topic = topic,
            title = outTitle,
            content = outContent,
            timestamp = timestampMs,
            client = "history",
            isOutgoing = false
        )
    }
}
