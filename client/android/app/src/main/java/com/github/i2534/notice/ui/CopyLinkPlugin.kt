package com.github.i2534.notice.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextPaint
import android.text.style.URLSpan
import android.view.View
import android.widget.Toast
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.RenderProps
import io.noties.markwon.SpanFactory
import io.noties.markwon.core.CoreProps
import org.commonmark.node.Link

object CopyLinkPlugin : AbstractMarkwonPlugin() {
    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(Link::class.java, CopyLinkSpanFactory)
    }
}

private object CopyLinkSpanFactory : SpanFactory {
    override fun getSpans(
        configuration: MarkwonConfiguration,
        props: RenderProps
    ): Any = CopyLinkSpan(
        theme = configuration.theme(),
        link = CoreProps.LINK_DESTINATION.require(props)
    )
}

private class CopyLinkSpan(
    private val theme: io.noties.markwon.core.MarkwonTheme,
    link: String
) : URLSpan(link) {
    override fun onClick(widget: View) {
        val appContext = widget.context.applicationContext
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", url))
        Toast.makeText(appContext, "已复制链接", Toast.LENGTH_SHORT).show()
    }

    override fun updateDrawState(ds: TextPaint) {
        theme.applyLinkStyle(ds)
    }
}
