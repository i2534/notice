package com.github.i2534.notice.data

import android.util.Base64
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.github.i2534.notice.ui.ContentBlock
import com.github.i2534.notice.ui.ContentBlockParser
import com.github.i2534.notice.util.MessageHistorySync
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.zip.GZIPInputStream

@Entity(tableName = "messages")
data class NoticeMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val client: String? = null,  // 发送端标识：web / android / cli / webhook
    val isOutgoing: Boolean = false  // true=本机回复发送的消息，false=收到的消息
) {
    /** 运行时缓存：避免每次列表绑定或详情弹出时重复解析 */
    @Ignore
    var parsedBlocks: List<ContentBlock>? = null

    @Ignore
    var parsedMediaUrls: List<String>? = null

    fun getBlocks(): List<ContentBlock> {
        if (parsedBlocks == null) {
            parsedBlocks = ContentBlockParser.parse(content)
        }
        return parsedBlocks!!
    }

    fun getMediaUrls(): List<String> {
        if (parsedMediaUrls == null) {
            parsedMediaUrls = ContentBlockParser.extractMediaAndImageUrls(content)
        }
        return parsedMediaUrls!!
    }

    companion object {
        private const val CONTENT_ENCODING_GZIP_B64 = "gzip+base64"

        private fun decodeGzipBase64Content(b64: String): String? {
            return try {
                val raw = Base64.decode(b64, Base64.DEFAULT)
                GZIPInputStream(ByteArrayInputStream(raw)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                null
            }
        }

        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val dateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
        private val fullDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /**
         * 从 MQTT 消息解析
         * 支持 JSON 格式: {"title": "xxx", "content": "xxx"}
         * 也支持纯文本
         */
        fun parse(topic: String, payload: ByteArray): NoticeMessage {
            val text = String(payload, Charsets.UTF_8)
            return try {
                val json = JSONObject(text)
                var content = json.optString("content", text)
                if (json.optString("content_encoding", "") == CONTENT_ENCODING_GZIP_B64 && content.isNotEmpty()) {
                    decodeGzipBase64Content(content)?.let { content = it }
                }
                var title = json.optString("title", "").ifBlank { "通知" }
                var client = json.optString("client").takeIf { it.isNotEmpty() }
                MessageHistorySync.unwrapNestedNoticeContent(content)?.let { nested ->
                    content = nested.content
                    nested.title?.let { title = it }
                    nested.client?.let { client = it }
                }
                val timestamp = MessageHistorySync.parseTimestamp(json.opt("timestamp"))
                    ?: System.currentTimeMillis()
                NoticeMessage(
                    topic = topic,
                    title = title,
                    content = content,
                    timestamp = timestamp,
                    client = client
                )
            } catch (e: Exception) {
                // 非 JSON 格式，使用纯文本
                NoticeMessage(
                    topic = topic,
                    title = topic.substringAfterLast("/").ifBlank { "通知" },
                    content = text
                )
            }
        }
    }

    /**
     * 格式化时间显示（线程安全，java.time 无 SimpleDateFormat 的竞态问题）
     * - 今天: 15:30
     * - 今年其他日期: 01-08 15:30
     * - 跨年: 2025-01-08 15:30
     */
    fun getFormattedTime(): String {
        val now = Instant.now()
        val zdt = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        val nowZdt = now.atZone(ZoneId.systemDefault())
        return when {
            zdt.toLocalDate() == nowZdt.toLocalDate() -> timeFormatter.format(zdt)
            zdt.year == nowZdt.year -> dateTimeFormatter.format(zdt)
            else -> fullDateTimeFormatter.format(zdt)
        }
    }
}
