package com.github.i2534.notice.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.databinding.ItemMessageBinding

class MessageAdapter : ListAdapter<NoticeMessage, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        private val binding: ItemMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: NoticeMessage) {
            binding.messageTitle.text = message.title
            binding.messageContent.text = message.content
            binding.messageTime.text = message.getFormattedTime()
            binding.messageTopic.text = message.topic
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
