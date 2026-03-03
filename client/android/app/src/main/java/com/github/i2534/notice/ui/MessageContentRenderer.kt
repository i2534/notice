package com.github.i2534.notice.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import coil.load
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.github.i2534.notice.R
import io.noties.markwon.Markwon

/**
 * 将解析后的内容块渲染到容器中：文本块用 Markwon，图片块用 ImageView，失败时显示 URL。
 */
object MessageContentRenderer {

    /**
     * @param touchThrough 为 true 时，文本与 URL 块不消费触摸，点击会传递到父 View（如列表项），用于历史列表
     */
    fun render(
        container: ViewGroup,
        blocks: List<ContentBlock>,
        markwon: Markwon,
        maxTextLinesInList: Int? = null,
        touchThrough: Boolean = false
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        val dp4 = (4 * container.context.resources.displayMetrics.density).toInt()

        for ((index, block) in blocks.withIndex()) {
            val topMargin = if (index == 0) 0 else dp4
            when (block) {
                is ContentBlock.Text -> {
                    val textView = TextView(container.context).apply {
                        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                        textSize = 14f
                        if (maxTextLinesInList != null) {
                            maxLines = maxTextLinesInList
                        }
                        isClickable = false
                        isFocusable = false
                    }
                    val text = block.text.ifBlank { " " }
                    markwon.setMarkdown(textView, text)
                    if (touchThrough) {
                        textView.movementMethod = null
                    }
                    val lp = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { this.topMargin = topMargin }
                    container.addView(textView, lp)
                }
                is ContentBlock.Image -> {
                    val view = inflater.inflate(R.layout.view_message_image_block, container, false) as ViewGroup
                    view.isClickable = false
                    view.isFocusable = false
                    val imageView = view.findViewById<ImageView>(R.id.imageBlockImage)
                    val urlFallback = view.findViewById<TextView>(R.id.imageBlockUrlFallback)
                    imageView.isClickable = false
                    imageView.isFocusable = false
                    urlFallback.isClickable = false
                    if (touchThrough) {
                        urlFallback.isFocusable = false
                        urlFallback.movementMethod = null
                    }

                    imageView.load(block.url) {
                        placeholder(R.drawable.ic_image_placeholder)
                        listener(
                            onSuccess = { _, _ ->
                                imageView.isVisible = true
                                urlFallback.isGone = true
                            },
                            onError = { _, _ ->
                                imageView.isGone = true
                                val label = urlFallback.context.getString(R.string.image_url_fallback_label)
                                urlFallback.text = "$label：\n${block.url}"
                                urlFallback.isVisible = true
                            }
                        )
                    }

                    val viewLp = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { this.topMargin = topMargin }
                    container.addView(view, viewLp)
                }
            }
        }
    }
}
