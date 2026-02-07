package com.github.i2534.notice.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "messages")
data class NoticeMessage(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val client: String? = null  // 发送端标识：web / android / cli / webhook
) {
    companion object {
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val dateTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        private val fullDateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

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
                    client = json.optString("client").takeIf { it.isNotEmpty() }
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

        private fun isSameDay(time1: Long, time2: Long): Boolean {
            val cal1 = Calendar.getInstance().apply { timeInMillis = time1 }
            val cal2 = Calendar.getInstance().apply { timeInMillis = time2 }
            return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                    cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
        }

        private fun isSameYear(timestamp: Long): Boolean {
            val cal1 = Calendar.getInstance().apply { timeInMillis = timestamp }
            val cal2 = Calendar.getInstance()
            return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
        }
    }

    /**
     * 格式化时间显示
     * - 今天: 15:30
     * - 今年其他日期: 01-08 15:30
     * - 跨年: 2025-01-08 15:30
     */
    fun getFormattedTime(): String {
        val now = System.currentTimeMillis()
        return when {
            isSameDay(timestamp, now) -> timeFormat.format(Date(timestamp))
            isSameYear(timestamp) -> dateTimeFormat.format(Date(timestamp))
            else -> fullDateTimeFormat.format(Date(timestamp))
        }
    }
}
