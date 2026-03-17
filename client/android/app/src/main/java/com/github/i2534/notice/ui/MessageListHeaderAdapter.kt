package com.github.i2534.notice.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.github.i2534.notice.R
import com.github.i2534.notice.data.NoticeMessage
import com.github.i2534.notice.databinding.HeaderMessageListBinding
import com.github.i2534.notice.util.MediaCacheLoader
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 消息列表头部：最新消息卡片 +「消息历史」标题行。
 * 与 [MessageAdapter] 通过 [androidx.recyclerview.widget.ConcatAdapter] 组合后，
 * 整页单区滚动，长最新消息可随列表滚走，同时保留列表回收与分页。
 */
class MessageListHeaderAdapter(
    private val context: Context,
    private val markwon: Markwon,
    private val onLatestCardClick: () -> Unit,
    private val onClearClick: () -> Unit
) : RecyclerView.Adapter<MessageListHeaderAdapter.HeaderViewHolder>() {

    var latestMessage: NoticeMessage? = null
        set(value) {
            if (field != value) {
                field = value
                notifyItemChanged(0)
            }
        }

    var isSelectMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyItemChanged(0)
            }
        }

    override fun getItemCount(): Int = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
        val binding = HeaderMessageListBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return HeaderViewHolder(binding, context, markwon, onLatestCardClick, onClearClick)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.bind(latestMessage, isSelectMode)
    }

    class HeaderViewHolder(
        private val binding: HeaderMessageListBinding,
        private val context: Context,
        private val markwon: Markwon,
        private val onLatestCardClick: () -> Unit,
        private val onClearClick: () -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: NoticeMessage?, isSelectMode: Boolean) {
            if (message != null) {
                binding.latestMessageCard.visibility = View.VISIBLE
                binding.latestMessageCard.setOnClickListener { onLatestCardClick() }
                binding.latestTitle.text = message.title
                binding.latestTime.text = message.getFormattedTime()
                val blocks = ContentBlockParser.parse(message.content)
                MessageContentRenderer.render(
                    binding.latestContentContainer,
                    blocks,
                    markwon,
                    mediaCachePathByUrl = null,
                    showUrlWhenNoCache = false
                )
                binding.latestContentContainer.setTag(message.id)
                val scope = binding.root.findViewTreeLifecycleOwner()?.lifecycleScope
                    ?: (context as? FragmentActivity)?.lifecycleScope
                scope?.launch {
                    val map = MediaCacheLoader.ensureMediaAndImageCache(context.applicationContext, message.content)
                    withContext(Dispatchers.Main) {
                        if (binding.latestContentContainer.getTag() != message.id) return@withContext
                        MessageContentRenderer.render(
                            binding.latestContentContainer,
                            blocks,
                            markwon,
                            mediaCachePathByUrl = if (map.isEmpty()) null else map,
                            showUrlWhenNoCache = true
                        )
                        MessageContentRenderer.requestFocusOnFirstVisiblePlayRow(binding.latestContentContainer)
                    }
                }
            } else {
                binding.latestMessageCard.visibility = View.GONE
                binding.latestMessageCard.setOnClickListener(null)
            }

            binding.btnClear.text = context.getString(
                if (isSelectMode) R.string.btn_delete_selected else R.string.clear_history
            )
            binding.btnClear.setOnClickListener { onClearClick() }
        }
    }
}
