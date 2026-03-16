package com.github.i2534.notice.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.i2534.notice.R
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.util.MediaCacheLoader
import com.github.i2534.notice.databinding.ItemMessageBinding
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MessageAdapter(
    private val markwon: Markwon,
    private val onItemClick: ((NoticeMessage) -> Unit)? = null,
    private val onEnterSelectMode: (() -> Unit)? = null,
    private val onSelectionChanged: ((Int) -> Unit)? = null,
    private val onAsrConfirm: ((topic: String, text: String) -> Unit)? = null
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
        if (message.id in selectedIds) selectedIds.remove(message.id)
        else selectedIds.add(message.id)
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
        holder.bind(message, isSelectMode, isSelected, onAsrConfirm)

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

    companion object {
        /** 若 content 为 asr_confirm_request JSON 则返回转写文本，否则返回 null */
        private fun parseAsrConfirmRequest(content: String): String? {
            return try {
                val obj = JSONObject(content)
                if (obj.optString("type") == "asr_confirm_request") {
                    obj.optString("text", "").takeIf { it.isNotBlank() }
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    class MessageViewHolder(
        private val binding: ItemMessageBinding,
        private val markwon: Markwon
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            message: NoticeMessage,
            isSelectMode: Boolean,
            isSelected: Boolean,
            onAsrConfirm: ((topic: String, text: String) -> Unit)?
        ) {
            // 本机回复不显示标题，也不显示「来自 xxx」
            if (message.isOutgoing) {
                binding.messageTitle.visibility = View.GONE
                binding.messageClient.visibility = View.GONE
            } else {
                binding.messageTitle.visibility = View.VISIBLE
                binding.messageTitle.text = message.title
                val client = message.client
                if (!client.isNullOrBlank()) {
                    binding.messageClient.text = binding.root.context.getString(R.string.from_client, client)
                    binding.messageClient.visibility = View.VISIBLE
                } else {
                    binding.messageClient.visibility = View.GONE
                }
            }
            binding.messageSentByMe.visibility = if (message.isOutgoing) View.VISIBLE else View.GONE
            binding.messageTime.text = message.getFormattedTime()
            binding.messageTopic.text = message.topic

            // 语音转写确认：content 为 JSON { type: "asr_confirm_request", text: "..." }
            val asrRequest = MessageAdapter.parseAsrConfirmRequest(message.content)
            if (asrRequest != null && onAsrConfirm != null) {
                binding.asrConfirmRow.visibility = View.VISIBLE
                binding.messageContentContainer.visibility = View.GONE
                binding.messageContentMore.visibility = View.GONE
                binding.asrConfirmEdit.setText(asrRequest)
                binding.asrConfirmBtn.setOnClickListener {
                    val text = binding.asrConfirmEdit.text?.toString()?.trim() ?: return@setOnClickListener
                    onAsrConfirm(message.topic, text)
                }
            } else {
                binding.asrConfirmRow.visibility = View.GONE
                binding.messageContentContainer.visibility = View.VISIBLE
                val blocks = ContentBlockParser.parse(message.content)
                MessageContentRenderer.render(
                    binding.messageContentContainer,
                    blocks,
                    markwon,
                    maxTextLinesInList = 2,
                    touchThrough = true,
                    mediaCachePathByUrl = null,
                    showUrlWhenNoCache = false
                )
                val mediaAndImageUrls = ContentBlockParser.extractMediaAndImageUrls(message.content)
                if (mediaAndImageUrls.isNotEmpty()) {
                    binding.messageContentContainer.setTag(message.id)
                    val scope = binding.root.findViewTreeLifecycleOwner()?.lifecycleScope
                        ?: (binding.root.context as? FragmentActivity)?.lifecycleScope
                    scope?.launch {
                        val messageId = message.id
                        // 无缓存时从 URL 下载一次并写入缓存，再按有缓存逻辑渲染
                        val map = MediaCacheLoader.ensureMediaAndImageCache(
                            binding.root.context.applicationContext,
                            message.content
                        )
                        withContext(Dispatchers.Main) {
                            if (binding.messageContentContainer.getTag() == messageId) {
                                MessageContentRenderer.render(
                                    binding.messageContentContainer,
                                    blocks,
                                    markwon,
                                    maxTextLinesInList = 2,
                                    touchThrough = true,
                                    mediaCachePathByUrl = if (map.isEmpty()) null else map,
                                    showUrlWhenNoCache = true
                                )
                            }
                        }
                    }
                }
                val content = message.content
                val likelyTruncated = content.length > 100 || content.lines().size > 2 || blocks.size > 3
                binding.messageContentMore.visibility = if (likelyTruncated) View.VISIBLE else View.GONE
            }

            // 选中状态 / 本机发送：使用边框和背景色区分
            val context = binding.root.context
            if (isSelectMode && isSelected) {
                binding.root.strokeWidth = 2
                binding.root.strokeColor = ContextCompat.getColor(context, R.color.primary)
                binding.root.setCardBackgroundColor(
                    ColorStateList.valueOf(ContextCompat.getColor(context, R.color.selected_background))
                )
            } else {
                binding.root.strokeWidth = 0
                val bgColor = if (message.isOutgoing) {
                    ContextCompat.getColor(context, R.color.message_outgoing_bg)
                } else {
                    ContextCompat.getColor(context, R.color.surface_variant)
                }
                binding.root.setCardBackgroundColor(ColorStateList.valueOf(bgColor))
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
