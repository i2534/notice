package com.github.i2534.notice.ui

import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import org.commonmark.node.SoftLineBreak

object SoftBreakPlugin : AbstractMarkwonPlugin() {
    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(SoftLineBreak::class.java) { visitor, _ ->
            visitor.forceNewLine()
        }
    }
}
