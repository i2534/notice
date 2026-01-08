package com.github.i2534.notice.data

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class NoticeMessage(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val raw: String? = null
) {
    companion object {
        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        /**
         * 从 MQTT 消息解析
         * 支持 JSON 格式: {"title": "xxx", "content": "xxx"}
         * 也支持纯文本
         */
        fun parse(topic: String, payload: ByteArray): NoticeMessage {
            val text = String(payload, Charsets.UTF_8)
            return try {
                val json = JSONObject(text)
                NoticeMessage(
                    topic = topic,
                    title = json.optString("title", "通知"),
                    content = json.optString("content", text),
                    raw = text
                )
            } catch (e: Exception) {
                // 非 JSON 格式，使用纯文本
                NoticeMessage(
                    topic = topic,
                    title = topic.substringAfterLast("/").ifBlank { "通知" },
                    content = text,
                    raw = text
                )
            }
        }
    }

    fun getFormattedTime(): String {
        return dateFormat.format(Date(timestamp))
    }
}
