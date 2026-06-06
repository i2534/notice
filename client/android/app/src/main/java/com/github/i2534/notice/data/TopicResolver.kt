package com.github.i2534.notice.data

/**
 * 主题工具类
 */
class TopicResolver {

    /**
     * 订阅主题 → 发布主题转换
     * - 去除尾部 #
     * - 将 + 替换为 reply
     * - notice/# → notice/reply
     * - notice/+ → notice/reply
     * - notice/alert → notice/alert (原样)
     */
    fun convertSubscribeToPublish(subscribeTopic: String?): String? {
        if (subscribeTopic.isNullOrBlank()) return null
        return subscribeTopic
            .removeSuffix("#")
            .split("/")
            .map { if (it == "+") "reply" else it }
            .joinToString("/")
    }
}
