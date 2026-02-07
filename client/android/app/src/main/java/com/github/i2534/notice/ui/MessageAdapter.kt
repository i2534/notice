package com.github.i2534.notice.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.i2534.notice.R
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.databinding.ItemMessageBinding
import io.noties.markwon.Markwon

class MessageAdapter(
    private val markwon: Markwon,
    private val onItemClick: ((NoticeMessage) -> Unit)? = null,
    private val onEnterSelectMode: (() -> Unit)? = null,
    private val onSelectionChanged: ((Int) -> Unit)? = null
) : PagingDataAdapter<NoticeMessage, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    // 多选模式
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

    private fun toggleSelection(message: NoticeMessage) {
        if (selectedIds.contains(message.id)) {
            selectedIds.remove(message.id)
        } else {
            selectedIds.add(message.id)
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selectedIds.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MessageViewHolder(binding, markwon)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position) ?: return
        val isSelected = selectedIds.contains(message.id)
        holder.bind(message, isSelectMode, isSelected)

        // 点击时根据当前是否多选模式决定行为：多选时只切换选中，非多选时打开详情（删除时不能触发查看详情）
        holder.itemView.setOnClickListener {
            if (isSelectMode) {
                toggleSelection(message)
            } else {
                onItemClick?.invoke(message)
            }
        }
        holder.itemView.setOnLongClickListener {
            if (!isSelectMode) {
                enterSelectMode(message)
            }
            true
        }
    }

    class MessageViewHolder(
        private val binding: ItemMessageBinding,
        private val markwon: Markwon
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            message: NoticeMessage,
            isSelectMode: Boolean,
            isSelected: Boolean
        ) {
            binding.messageTitle.text = message.title
            markwon.setMarkdown(binding.messageContent, message.content.ifBlank { " " })
            binding.messageContent.movementMethod = null
            binding.messageTime.text = message.getFormattedTime()
            binding.messageTopic.text = message.topic
            val client = message.client
            if (!client.isNullOrBlank()) {
                binding.messageClient.text = binding.root.context.getString(R.string.from_client, client)
                binding.messageClient.visibility = android.view.View.VISIBLE
            } else {
                binding.messageClient.visibility = android.view.View.GONE
            }

            // 内容可能被截断时显示「更多」提示（列表最多 2 行）
            val content = message.content
            val likelyTruncated = content.length > 100 || content.lines().size > 2
            binding.messageContentMore.visibility = if (likelyTruncated) android.view.View.VISIBLE else android.view.View.GONE

            // 选中状态：使用边框和轻微的颜色变化
            val context = binding.root.context
            if (isSelectMode && isSelected) {
                binding.root.strokeWidth = 2
                binding.root.strokeColor = ContextCompat.getColor(context, R.color.primary)
                binding.root.setCardBackgroundColor(
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.selected_background))
                )
            } else {
                binding.root.strokeWidth = 0
                binding.root.setCardBackgroundColor(
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.surface_variant))
                )
            }

            binding.root.isClickable = true
            binding.root.isFocusable = true
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<NoticeMessage>() {
        override fun areItemsTheSame(oldItem: NoticeMessage, newItem: NoticeMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: NoticeMessage, newItem: NoticeMessage): Boolean {
            return oldItem == newItem
        }
    }
}
