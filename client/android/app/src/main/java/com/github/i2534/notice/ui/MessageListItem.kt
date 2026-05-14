package com.github.i2534.notice.ui

import androidx.recyclerview.widget.DiffUtil
import com.github.i2534.notice.data.NoticeMessage
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock

sealed class MessageListItem {
    data class TopicHeader(val topic: String) : MessageListItem()
    data class DateSeparator(val dateLabel: String) : MessageListItem()
    data class MessageBubble(
        val message: NoticeMessage,
        val mergedCount: Int = 1
    ) : MessageListItem()
}

object BubbleListBuilder {

    private const val MERGE_THRESHOLD_MS = 30_000L
    private val calendarLock = ReentrantLock()

    private fun safeCalendar(): Calendar {
        calendarLock.lock()
        try {
            return Calendar.getInstance()
        } finally {
            calendarLock.unlock()
        }
    }

    private fun NoticeMessage.isDuplicateOf(other: NoticeMessage): Boolean {
        return isOutgoing == other.isOutgoing &&
            content.trim() == other.content.trim()
    }

    fun buildMessages(messages: List<NoticeMessage>): List<MessageListItem> {
        val result = mutableListOf<MessageListItem>()
        var prevTopic: String? = null
        var prevDateBucket: Long? = null

        val now = System.currentTimeMillis()
        val todayCal = safeCalendar().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayMidnight = todayCal.timeInMillis
        val yesterdayMidnight = todayMidnight - 86400000L

        for (message in messages) {
            if (message.topic != prevTopic) {
                result.add(MessageListItem.TopicHeader(message.topic))
                prevTopic = message.topic
            }

            val msgCal = safeCalendar().apply {
                timeInMillis = message.timestamp
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val msgBucket = msgCal.timeInMillis

            val dateLabel = when {
                msgBucket == todayMidnight -> "今天"
                msgBucket == yesterdayMidnight -> "昨天"
                else -> java.text.SimpleDateFormat("MM/dd", Locale.getDefault()).format(Date(message.timestamp))
            }

            if (msgBucket != prevDateBucket) {
                /* Topic 头已有视觉分隔，紧随其后不再插日期线 */
                if (result.lastOrNull() !is MessageListItem.TopicHeader) {
                    result.add(MessageListItem.DateSeparator(dateLabel))
                }
                prevDateBucket = msgBucket
            }

            val lastBubble = (result.lastOrNull() as? MessageListItem.MessageBubble)
            if (lastBubble != null && message.isDuplicateOf(lastBubble.message) &&
                message.timestamp - lastBubble.message.timestamp <= MERGE_THRESHOLD_MS) {
                result[result.size - 1] = lastBubble.copy(mergedCount = lastBubble.mergedCount + 1)
            } else {
                result.add(MessageListItem.MessageBubble(message))
            }
        }

        return result
    }

    class DiffCallback(
        private val oldList: List<MessageListItem>,
        private val newList: List<MessageListItem>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            val old = oldList[oldPos]
            val `new` = newList[newPos]
            return when {
                old is MessageListItem.TopicHeader && `new` is MessageListItem.TopicHeader -> old.topic == `new`.topic
                old is MessageListItem.DateSeparator && `new` is MessageListItem.DateSeparator -> old.dateLabel == `new`.dateLabel
                old is MessageListItem.MessageBubble && `new` is MessageListItem.MessageBubble -> old.message.id == `new`.message.id
                else -> false
            }
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos] == newList[newPos]
        }
    }
}
