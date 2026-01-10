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

class MessageAdapter(
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
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position) ?: return
        val isSelected = selectedIds.contains(message.id)
        holder.bind(message, isSelectMode, isSelected) { msg ->
            if (isSelectMode) {
                toggleSelection(msg)
            } else {
                onItemClick?.invoke(msg)
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
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            message: NoticeMessage,
            isSelectMode: Boolean,
            isSelected: Boolean,
            onClick: (NoticeMessage) -> Unit
        ) {
            binding.messageTitle.text = message.title
            binding.messageContent.text = message.content
            binding.messageTime.text = message.getFormattedTime()
            binding.messageTopic.text = message.topic

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

            binding.root.setOnClickListener {
                onClick(message)
            }
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
