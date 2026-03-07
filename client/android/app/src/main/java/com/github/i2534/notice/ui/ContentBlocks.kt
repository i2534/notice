package com.github.i2534.notice.ui

/**
 * 将消息内容拆成「文本块」与「图片块」，便于分别渲染：
 * - 文本用 Markwon 渲染（不含图片语法），无占位/重叠问题
 * - 图片单独用 ImageView 加载，失败时直接显示 URL 文本，逻辑统一
 */
sealed class ContentBlock {
    data class Text(val text: String) : ContentBlock()
    data class Image(val url: String) : ContentBlock()
}

object ContentBlockParser {

    private val imagePattern = Regex("!\\[\\]\\(([^)]+)\\)")

    /**
     * 按 `![](url)` 拆分内容，返回交替的文本块与图片块。
     * 若整段无图片，返回一个 Text(content)。
     */
    fun parse(content: String): List<ContentBlock> {
        val t = content.trim()
        if (t.isEmpty()) return listOf(ContentBlock.Text(" "))

        val results = mutableListOf<ContentBlock>()
        var lastEnd = 0

        for (match in imagePattern.findAll(t)) {
            val before = t.substring(lastEnd, match.range.first).trim()
            if (before.isNotEmpty()) {
                results.add(ContentBlock.Text(before))
            }
            results.add(ContentBlock.Image(match.groupValues[1]))
            lastEnd = match.range.last + 1
        }
        val after = t.substring(lastEnd).trim()
        if (after.isNotEmpty()) {
            results.add(ContentBlock.Text(after))
        }
        return results
    }
}
