package com.github.i2534.notice.util

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.absoluteValue

object TopicColor {

    private val PALETTE = listOf(
        "#0D7377", "#FF6B35", "#6B4FA0", "#2E7D32",
        "#C62828", "#F57C00", "#0288D1", "#00897B",
        "#D81B60", "#5D4037", "#546E7A", "#7CB342"
    ).map { Color.parseColor(it) }.toIntArray()

   private fun hashString(s: String): Int {
        var h = 2166136261L
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 16777619L
        }
        return ((h xor (h ushr 16)) and 0xFFFFFFFFL).toInt().absoluteValue
    }

    @ColorInt
    fun forTopic(topic: String): Int {
        val index = hashString(topic) % PALETTE.size
        return PALETTE[index]
    }

    @ColorInt
    fun bgSoft(topic: String): Int {
        return ColorUtils.setAlphaComponent(forTopic(topic), 0x1F)
    }

    @ColorInt
    fun stripe(topic: String): Int {
        return ColorUtils.setAlphaComponent(forTopic(topic), 0xD8)
    }
}
