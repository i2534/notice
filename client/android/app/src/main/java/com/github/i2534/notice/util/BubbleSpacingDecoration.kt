package com.github.i2534.notice.util

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.github.i2534.notice.ui.BubbleMessageAdapter
import com.github.i2534.notice.ui.MessageListItem

/**
 * 消息气泡间距装饰器。根据前后消息类型计算 top margin。
 */
class BubbleSpacingDecoration(
    private val adapter: BubbleMessageAdapter,
    private val density: Float,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position < 0 || position >= adapter.itemCount) return

        val currentItem = adapter.getItems()[position]
        if (currentItem !is MessageListItem.MessageBubble) {
            outRect.set(0, (10 * density).toInt(), 0, 0)
            return
        }

        // 往前找上一条真正的 MessageBubble，跳过 TopicHeader/DateSeparator
        var prevOutgoing: Boolean? = null
        for (i in position - 1 downTo 0) {
            val prev = adapter.getItems()[i]
            if (prev is MessageListItem.MessageBubble) {
                prevOutgoing = prev.message.isOutgoing
                break
            }
        }

        val topMarginDp = when {
            prevOutgoing == null -> 10
            prevOutgoing == currentItem.message.isOutgoing -> 2
            else -> 10
        }
        outRect.set(0, (topMarginDp * density).toInt(), 0, 0)
    }
}
