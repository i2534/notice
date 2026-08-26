package com.github.i2534.notice.ui

import android.content.Context

import android.media.MediaPlayer
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import coil.load
import com.github.i2534.notice.R
import com.github.i2534.notice.util.AppLogger
import com.github.i2534.notice.util.BannerType
import com.github.i2534.notice.util.MessageBanner
import io.noties.markwon.Markwon
import java.io.File

/**
 * 全局唯一语音播放：同一条点击播放/暂停切换，另一条会先停旧再播新。
 */
private object VoicePlaybackCoordinator {

    private data class Active(
        val path: String,
        val idle: () -> Unit,
        val playing: () -> Unit,
        val paused: () -> Unit,
        var player: MediaPlayer?,
        var pausedFlag: Boolean,
    )

    private var active: Active? = null

    private fun finishActive(playIdle: Boolean) {
        val a = active ?: return
        try {
            a.player?.release()
        } catch (_: Exception) {
        }
        a.player = null
        active = null
        if (playIdle) {
            a.idle()
        }
    }

    fun handleVoiceRowClick(
        applicationContext: Context,
        uiHost: View,
        localPath: String,
        idle: () -> Unit,
        playing: () -> Unit,
        paused: () -> Unit,
    ) {
        val path = try {
            File(localPath).canonicalPath
        } catch (_: Exception) {
            localPath
        }

        val cur = active
        if (cur != null && cur.path == path && cur.player != null) {
            val mp = cur.player!!
            when {
                mp.isPlaying -> {
                    try {
                        mp.pause()
                    } catch (_: Exception) {
                    }
                    cur.pausedFlag = true
                    uiHost.post { paused() }
                    return
                }
                cur.pausedFlag -> {
                    try {
                        mp.start()
                    } catch (_: Exception) {
                    }
                    cur.pausedFlag = false
                    uiHost.post { playing() }
                    return
                }
            }
        }

        if (cur != null) {
            finishActive(true)
        }

        try {
            val mp = MediaPlayer().apply {
                setDataSource(applicationContext, Uri.fromFile(File(localPath)))
                setOnCompletionListener {
                    val a = active
                    if (a != null && a.path == path) {
                        finishActive(true)
                    }
                }
                setOnErrorListener { _, _, _ ->
                    MessageBanner.showRes(applicationContext, R.string.voice_play_error, BannerType.Error)
                    val a = active
                    if (a != null && a.path == path) {
                        finishActive(true)
                    }
                    true
                }
                setOnPreparedListener {
                    start()
                    val a = active
                    if (a != null && a.path == path) {
                        a.pausedFlag = false
                        uiHost.post { playing() }
                    }
                }
                prepareAsync()
            }
            active = Active(path, idle, playing, paused, mp, false)
       } catch (_: Exception) {
            MessageBanner.showRes(applicationContext, R.string.voice_play_error, BannerType.Error)
        }
    }
}

/**
 * 将解析后的内容块渲染到容器中：文本块用 Markwon，图片块用 ImageView（有缓存从本地加载，无缓存时列表显示「图片加载失败」、详情显示 URL），
 * 媒体/语音块：有本地缓存显示播放，无缓存时列表显示「音频加载失败」、详情显示 URL。
 */
object MessageContentRenderer {

    /**
     * @param mediaCachePathByUrl 媒体/图片 URL → 本地路径；图片/语音有缓存则从本地显示，无缓存见 showUrlWhenNoCache
     * @param showUrlWhenNoCache 无缓存时是否显示 URL：true=详情页显示 URL，false=列表显示「图片/音频加载失败」
     * @param touchThrough 为 true 时，文本与 URL 块不消费触摸，点击会传递到父 View（如列表项），用于历史列表
     * @param textSelectable 为 true 时，文本块可长按选择，用于详情弹窗
     * @param maxImageHeightInList 列表场景下图片最大高度（px），过高则固定高度 + centerCrop 裁剪
     * @param detailImageMinWidth 详情场景下图片最小宽度（px），保证窄图能看清
     * @param onImageLongClick 图片长按回调（url, 本地缓存路径, ImageView），用于保存等操作；列表预览不渲染图片，不会触发
     */
    fun render(
        container: ViewGroup,
        blocks: List<ContentBlock>,
        markwon: Markwon,
        maxTextLinesInList: Int? = null,
        touchThrough: Boolean = false,
        textSelectable: Boolean = false,
        isOutgoing: Boolean = false,
        mediaCachePathByUrl: Map<String, String>? = null,
        showUrlWhenNoCache: Boolean = true,
        maxImageHeightInList: Int? = null,
        detailImageMinWidth: Int? = null,
        onTruncated: ((Boolean) -> Unit)? = null,
        onImageLongClick: ((url: String, localPath: String?, imageView: ImageView) -> Unit)? = null
    ) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        val dp4 = (4 * container.context.resources.displayMetrics.density).toInt()

        for ((index, block) in blocks.withIndex()) {
            val topMargin = if (index == 0) 0 else dp4
            when (block) {
                is ContentBlock.Text -> {
                    val text = block.text.replace("\r\n", "\n").ifBlank { " " }
                    val textView = TextView(container.context).apply {
                        setTextColor(ContextCompat.getColor(context, if (isOutgoing) R.color.white else R.color.text_secondary))
                        textSize = 14f
                        isClickable = textSelectable && !touchThrough
                        isFocusable = textSelectable && !touchThrough
                        isFocusableInTouchMode = textSelectable && !touchThrough
                        setTextIsSelectable(textSelectable && !touchThrough)
                        if (maxTextLinesInList != null) {
                            maxLines = maxTextLinesInList
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            this.text = text
                        } else {
                            markwon.setMarkdown(this, text)
                        }
                    }
                    if (touchThrough) {
                        textView.movementMethod = null
                    }
                    if (maxTextLinesInList != null && onTruncated != null) {
                        textView.post {
                            val isTruncated = textView.text?.let { t ->
                                val lastVisibleLine = textView.lineCount.coerceAtMost(maxTextLinesInList) - 1
                                val lastChar = (textView.layout?.getLineEnd(lastVisibleLine) ?: -1) - 1
                                lastChar >= 0 && lastChar < t.length && t[lastChar] == '\u2026'
                            } ?: false
                            onTruncated(isTruncated)
                        }
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

                    // 列表：固定高度 + centerCrop 裁剪，防止长图撑满
                    if (maxImageHeightInList != null) {
                        imageView.layoutParams = imageView.layoutParams?.apply { height = maxImageHeightInList }
                            ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxImageHeightInList)
                        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
                        imageView.adjustViewBounds = false
                    }
                    // 详情：最小宽度，配合滚动能看清
                    if (detailImageMinWidth != null) {
                        imageView.minimumWidth = detailImageMinWidth
                    }

                    val imageLocalPath = mediaCachePathByUrl?.get(block.url)
                    val loadFromFile = imageLocalPath != null && imageLocalPath.isNotBlank() && File(imageLocalPath).exists()

                    if (loadFromFile) {
                        imageView.load(File(imageLocalPath)) {
                            placeholder(R.drawable.ic_image_placeholder)
                            listener(
                                onSuccess = { _, _ ->
                                    imageView.isVisible = true
                                    urlFallback.isGone = true
                                },
                                onError = { _, _ ->
                                    imageView.isGone = true
                                    urlFallback.text = if (showUrlWhenNoCache) block.url else urlFallback.context.getString(R.string.image_load_failed)
                                    urlFallback.isVisible = true
                                }
                            )
                        }
                    } else {
                        if (showUrlWhenNoCache) {
                            imageView.load(block.url) {
                                placeholder(R.drawable.ic_image_placeholder)
                                listener(
                                    onSuccess = { _, _ ->
                                        imageView.isVisible = true
                                        urlFallback.isGone = true
                                    },
                                    onError = { _, _ ->
                                        imageView.isGone = true
                                        urlFallback.text = block.url
                                        urlFallback.isVisible = true
                                    }
                                )
                            }
                        } else {
                            imageView.isGone = true
                            urlFallback.text = urlFallback.context.getString(R.string.image_load_failed)
                            urlFallback.isVisible = true
                        }
                    }

                    // 图片长按：调用方（如详情页）用于弹保存菜单；返回 true 消费事件
                    if (onImageLongClick != null) {
                        imageView.setOnLongClickListener {
                            onImageLongClick(block.url, imageLocalPath, imageView)
                            true
                        }
                    }

                    val viewLp = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { this.topMargin = topMargin }
                    container.addView(view, viewLp)
                }
                is ContentBlock.Media -> {
                    val view = inflater.inflate(R.layout.view_message_voice_block, container, false) as ViewGroup
                    val urlFallback = view.findViewById<TextView>(R.id.voiceBlockUrlFallback)
                    val playRow = view.findViewById<ViewGroup>(R.id.voiceBlockPlayRow)
                    val playIcon = view.findViewById<ImageView>(R.id.voiceBlockPlayIcon)
                    val playLabel = view.findViewById<TextView>(R.id.voiceBlockPlayLabel)
                    val localPath = mediaCachePathByUrl?.get(block.url)
                    if (!localPath.isNullOrBlank() && File(localPath).exists()) {
                        urlFallback.isGone = true
                        playRow.isVisible = true
                        playRow.isClickable = true
                           playRow.isFocusable = !touchThrough
                        playRow.isFocusableInTouchMode = !touchThrough
                        playRow.setOnClickListener {
                            val ctx = playRow.context.applicationContext
                            fun idleUi() {
                                playRow.post {
                                    playIcon.setImageResource(R.drawable.ic_play)
                                    playLabel.setText(R.string.voice_play_label)
                                }
                            }
                            fun playingUi() {
                                playRow.post {
                                    playIcon.setImageResource(R.drawable.ic_voice_playing)
                                    playLabel.setText(R.string.voice_playing_label)
                                }
                            }
                            fun pausedUi() {
                                playRow.post {
                                    playIcon.setImageResource(R.drawable.ic_play)
                                    playLabel.setText(R.string.voice_paused_label)
                                }
                            }
                            AppLogger.i("VoicePlay", "点击播放行: $localPath")
                            VoicePlaybackCoordinator.handleVoiceRowClick(
                                ctx,
                                playRow,
                                localPath,
                                idle = { idleUi() },
                                playing = { playingUi() },
                                paused = { pausedUi() },
                            )
                        }
                    } else {
                        playRow.isGone = true
                        urlFallback.isVisible = true
                        urlFallback.text = if (showUrlWhenNoCache) block.url else urlFallback.context.getString(R.string.audio_load_failed)
                        urlFallback.isClickable = false
                        if (touchThrough) {
                            urlFallback.isFocusable = false
                            urlFallback.movementMethod = null
                        }
                    }
                    view.isClickable = false
                    view.isFocusable = false

                    val viewLp = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { this.topMargin = topMargin }
                    container.addView(view, viewLp)
                }
            }
        }
    }

    /**
     * 在容器中查找第一个可见的播放行并请求焦点，避免首次点击被当作「焦点转移」而不触发播放。
     */
    fun requestFocusOnFirstVisiblePlayRow(container: ViewGroup) {
        container.post {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                val playRow = child.findViewById<View>(R.id.voiceBlockPlayRow)
                if (playRow != null && playRow.visibility == View.VISIBLE) {
                    playRow.requestFocus()
                    return@post
                }
            }
        }
    }
}
