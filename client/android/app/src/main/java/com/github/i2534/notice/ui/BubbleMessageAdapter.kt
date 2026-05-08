package com.github.i2534.notice.ui

import android.view.Gravity
import android.content.res.ColorStateList
import androidx.core.view.ViewCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.i2534.notice.R
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.util.TopicColor
import io.noties.markwon.Markwon
import org.json.JSONObject

class BubbleMessageAdapter(
    private val markwon: Markwon,
    private val onItemClick: (NoticeMessage) -> Unit,
    private val onLongClick: (NoticeMessage) -> Unit,
    private val onEnterSelectMode: (() -> Unit)? = null,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_TOPIC_HEADER = 0
        private const val TYPE_DATE_SEPARATOR = 1
        private const val TYPE_BUBBLE = 2

        fun parseAsrConfirmRequest(content: String): String? = try {
            val obj = JSONObject(content)
            if (obj.optString("type") == "asr_confirm_request") {
                obj.optString("text", "").takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) { null }

        fun displayTextForOutgoingAsrCommand(
            context: android.content.Context,
            isOutgoing: Boolean,
            content: String
        ): String? {
            if (!isOutgoing) return null
            val trimmed = content.trim()
            if (!trimmed.startsWith("{")) return null
            return try {
                val obj = JSONObject(trimmed)
                when (obj.optString("type")) {
                    "asr_confirm" -> {
                        val text = obj.optString("text", "").trim()
                        if (text.isNotEmpty()) {
                            context.getString(R.string.voice_command_display_confirm, text)
                        } else null
                    }
                    "asr_cancel" -> context.getString(R.string.voice_command_display_cancel)
                    else -> null
                }
            } catch (_: Exception) { null }
        }
    }

    private var items: List<MessageListItem> = emptyList()

    fun getItems(): List<MessageListItem> = items

    private val asrHandledDisplayLines = mutableMapOf<String, String>()
    fun markAsrHandled(messageId: String, displayLine: String) {
        asrHandledDisplayLines[messageId] = displayLine
        notifyDataSetChanged()
    }

    var isSelectMode = false
        private set
    private val selectedIds = mutableSetOf<String>()

    fun enterSelectMode(message: NoticeMessage) {
        isSelectMode = true
        selectedIds.clear()
        selectedIds.add(message.id)
        notifyDataSetChanged()
        onEnterSelectMode?.invoke()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    fun exitSelectMode() {
        isSelectMode = false
        selectedIds.clear()
        notifyDataSetChanged()
    }

    fun getSelectedIds(): Set<String> = selectedIds.toSet()
    fun getSelectedCount(): Int = selectedIds.size

    fun submitDiff(oldList: List<MessageListItem>, newList: List<MessageListItem>) {
        val diffResult = androidx.recyclerview.widget.DiffUtil.calculateDiff(
            BubbleListBuilder.DiffCallback(oldList, newList)
        )
        items = newList
        diffResult.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is MessageListItem.TopicHeader -> TYPE_TOPIC_HEADER
            is MessageListItem.DateSeparator -> TYPE_DATE_SEPARATOR
            is MessageListItem.MessageBubble -> TYPE_BUBBLE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_TOPIC_HEADER -> TopicHeaderViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_topic_header, parent, false)
            )
            TYPE_DATE_SEPARATOR -> DateSeparatorViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_date_separator, parent, false)
            )
            TYPE_BUBBLE -> BubbleViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_bubble_message, parent, false),
                markwon
            )
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is MessageListItem.TopicHeader -> (holder as TopicHeaderViewHolder).bind(item.topic)
            is MessageListItem.DateSeparator -> (holder as DateSeparatorViewHolder).bind(item.dateLabel)
            is MessageListItem.MessageBubble -> {
                val prevIsOutgoing = if (position > 0 && items[position - 1] is MessageListItem.MessageBubble) {
                    (items[position - 1] as MessageListItem.MessageBubble).message.isOutgoing
                } else { null }
                (holder as BubbleViewHolder).bind(
                    item.message,
                    isSelectMode,
                    selectedIds.contains(item.message.id),
                    prevIsOutgoing,
                    { msg -> if (isSelectMode) toggleSelection(msg) else onItemClick(msg) },
                    { msg -> onLongClick(msg) }
                )
            }
        }
    }

    override fun getItemCount() = items.size

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is BubbleViewHolder) {
            holder.setSpacing(holder.itemView.resources.displayMetrics.density)
        }
    }

    private fun toggleSelection(message: NoticeMessage) {
        if (message.id in selectedIds) selectedIds.remove(message.id) else selectedIds.add(message.id)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    class TopicHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val stripe: View = itemView.findViewById(R.id.topicStripe)
        private val name: TextView = itemView.findViewById(R.id.topicName)

        fun bind(topic: String) {
            val color = TopicColor.forTopic(topic)
            ViewCompat.setBackgroundTintList(stripe, ColorStateList.valueOf(color))
            name.setTextColor(color)
            name.text = topic
        }
    }

    class DateSeparatorViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.dateText)

        fun bind(dateLabel: String) {
            dateText.text = dateLabel
        }
    }

    class BubbleViewHolder(
        itemView: View,
        private val markwon: Markwon
    ) : RecyclerView.ViewHolder(itemView) {
        private val root: FrameLayout = itemView.findViewById(R.id.bubbleRoot)
        private val bubbleCard: FrameLayout = itemView.findViewById(R.id.bubbleCard)
        private val bubbleTime: TextView = itemView.findViewById(R.id.bubbleTime)
        private val contentContainer: ViewGroup = itemView.findViewById(R.id.bubbleContentContainer)
        private val bubbleMore: TextView = itemView.findViewById(R.id.bubbleMore)

        fun setSpacing(density: Float) {
            val lp = root.layoutParams as ViewGroup.MarginLayoutParams
            lp.topMargin = (6 * density).toInt()
        }

        fun bind(
            message: NoticeMessage,
            isSelectMode: Boolean,
            isSelected: Boolean,
            prevIsOutgoing: Boolean?,
            onClick: (NoticeMessage) -> Unit,
            onLongClick: (NoticeMessage) -> Unit
        ) {
            val isOutgoing = message.isOutgoing
            val ctx = itemView.context

            // 气泡 80% 宽度，通过 gravity 控制左右对齐
            val containerWidth = ctx.resources.displayMetrics.widthPixels
            val bubbleWidth = (containerWidth * 0.85).toInt()
            val lp = FrameLayout.LayoutParams(bubbleWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = if (isOutgoing) Gravity.END else Gravity.START
            bubbleCard.layoutParams = lp

            bubbleCard.background = ContextCompat.getDrawable(
                ctx,
                if (isOutgoing) R.drawable.bg_bubble_outgoing else R.drawable.bg_bubble_incoming
            )

            if (isSelectMode && isSelected) {
                root.background = ContextCompat.getDrawable(ctx, R.drawable.bg_selected_bubble)
            } else {
                root.background = null
            }

            bubbleTime.text = message.getFormattedTime()
            if (isOutgoing) {
                bubbleTime.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                bubbleTime.alpha = 0.5f
            } else {
                bubbleTime.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                bubbleTime.alpha = 0.8f
            }

            contentContainer.removeAllViews()
            val asrOutgoingDisplay = displayTextForOutgoingAsrCommand(ctx, isOutgoing, message.content)
            /* 列表预览：用原始内容作为单个 Text 块，避免 ContentBlockParser 拆分导致 maxLines 失效 */
            val previewBlocks = if (asrOutgoingDisplay != null) {
                listOf(ContentBlock.Text(asrOutgoingDisplay))
            } else {
                listOf(ContentBlock.Text(message.content))
            }
      MessageContentRenderer.render(
                container = contentContainer,
                blocks = previewBlocks,
                markwon = markwon,
                maxTextLinesInList = 2,
                touchThrough = true,
                isOutgoing = isOutgoing,
                mediaCachePathByUrl = null,
                showUrlWhenNoCache = false
            ) { isTruncated ->
                bubbleMore.apply {
                    visibility = if (isTruncated) View.VISIBLE else View.GONE
                    setTextColor(
                        if (isOutgoing) android.graphics.Color.parseColor("#80FFFFFF")
                        else ContextCompat.getColor(ctx, R.color.primary_light)
                    )
                }
            }

            root.setOnClickListener { onClick(message) }
            root.setOnLongClickListener {
                onLongClick(message)
                true
            }
        }
    }
}
