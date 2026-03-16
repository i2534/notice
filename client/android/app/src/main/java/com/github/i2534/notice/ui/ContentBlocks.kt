package com.github.i2534.notice.ui

/**
 * 将消息内容拆成「文本块」「图片块」「媒体/语音块」，便于分别渲染：
 * - 文本用 Markwon 渲染（不含图片语法），无占位/重叠问题
 * - 图片单独用 ImageView 加载，失败时直接显示 URL 文本，逻辑统一
 * - 媒体/语音 URL 与图片地址同款样式（左条+标签+URL）
 */
sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class Image(val url: String) : ContentBlock()
    /** 媒体/语音链接，渲染样式与图片地址一致 */
    data class Media(val url: String) : ContentBlock()
}

object ContentBlockParser {

    private val imagePattern = Regex("!\\[\\]\\(([^)]+)\\)")
    private val mediaLinkPattern = Regex("\\[语音\\]\\(([^)]+)\\)|\\[音频\\]\\(([^)]+)\\)")
    private const val MEDIA_URL_MARKER = "/api/media"

    /**
     * 按 `![](url)`、`[语音](url)`/`[音频](url)` 及纯媒体 URL 拆分内容。
     * 若整段仅为一条媒体 URL，返回一个 Media(url)；无图片/媒体时返回 Text(content)。
     */
    fun parse(content: String): List<ContentBlock> {
        val t = content.trim()
        if (t.isEmpty()) return listOf(ContentBlock.Text(" "))

        // 整条内容为单行媒体 URL 时，直接作为媒体块（发送语音后消息常为此形式）
        if (t.contains(MEDIA_URL_MARKER) && !t.contains('\n')) {
            return listOf(ContentBlock.Media(t))
        }

        val results = mutableListOf<ContentBlock>()
        var lastEnd = 0

        for (match in imagePattern.findAll(t)) {
            val before = t.substring(lastEnd, match.range.first)
            results.addAll(splitTextByMediaLinks(before))
            results.add(ContentBlock.Image(match.groupValues[1]))
            lastEnd = match.range.last + 1
        }
        val after = t.substring(lastEnd)
        results.addAll(splitTextByMediaLinks(after))
        return results
    }

    private fun splitTextByMediaLinks(s: String): List<ContentBlock> {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return emptyList()
        val list = mutableListOf<ContentBlock>()
        var lastEnd = 0
        for (match in mediaLinkPattern.findAll(trimmed)) {
            val before = trimmed.substring(lastEnd, match.range.first).trim()
            if (before.isNotEmpty()) list.add(ContentBlock.Text(before))
            val url = match.groupValues[1].ifEmpty { match.groupValues[2] }
            list.add(ContentBlock.Media(url))
            lastEnd = match.range.last + 1
        }
        val after = trimmed.substring(lastEnd).trim()
        if (after.isNotEmpty()) list.add(ContentBlock.Text(after))
        return list.ifEmpty { listOf(ContentBlock.Text(trimmed)) }
    }

    /**
     * 从消息 content 中提取所有媒体/语音 URL（用于删除消息时清理对应语音缓存）。
     */
    fun extractMediaUrls(content: String): List<String> =
        parse(content).filterIsInstance<ContentBlock.Media>().map { it.url }.distinct()

    /**
     * 从消息 content 中提取所有图片与媒体/语音 URL（用于列表/最新消息查多媒体缓存）。
     */
    fun extractMediaAndImageUrls(content: String): List<String> {
        val blocks = parse(content)
        val imageUrls = blocks.filterIsInstance<ContentBlock.Image>().map { it.url }
        val mediaUrls = blocks.filterIsInstance<ContentBlock.Media>().map { it.url }
        return (imageUrls + mediaUrls).distinct()
    }
}
