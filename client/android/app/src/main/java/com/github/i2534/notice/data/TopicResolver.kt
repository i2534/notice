package com.github.i2534.notice.data

/**
 * 主题解析器
 * 负责将订阅主题转换为发布主题，以及解析实际发送主题
 */
class TopicResolver {

    /**
     * 解析实际发送主题
     * 优先级: override > settingsSendTopic > subscribeTopic 转换
     */
    fun resolve(
        override: String?,
        settingsSendTopic: String?,
        subscribeTopic: String?
    ): String? {
        return override?.takeIf { it.isNotBlank() }
            ?: settingsSendTopic?.takeIf { it.isNotBlank() }
            ?: convertSubscribeToPublish(subscribeTopic)
    }

    /**
     * 获取默认主题显示文本
     */
    fun getDefaultDisplayText(settingsSendTopic: String?, subscribeTopic: String?): String? {
        return settingsSendTopic?.takeIf { it.isNotBlank() }
            ?: convertSubscribeToPublish(subscribeTopic)
    }

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
