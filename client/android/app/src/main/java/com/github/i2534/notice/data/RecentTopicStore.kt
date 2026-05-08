package com.github.i2534.notice.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 管理最近收到的 topic 列表
 */
class RecentTopicStore(
    private val messageDao: MessageDao
) {
    companion object {
        private const val MAX_TOPICS = 10
    }

    /**
     * 获取最近收到的不同 topic 列表（排除回复消息）
     * 按最新时间排序，去重
     */
    suspend fun getRecentTopics(): List<String> {
        return withContext(Dispatchers.IO) {
            messageDao.getDistinctTopicsOrderedByTime()
                .take(MAX_TOPICS)
        }
    }
}
