package com.github.i2534.notice.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {

    /**
     * 获取分页消息（按时间倒序）
     */
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getMessagesPaging(): PagingSource<Int, NoticeMessage>

    /**
     * 获取最新的一条消息
     */
    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestMessage(): NoticeMessage?

    /**
     * 获取消息数量
     */
    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getCount(): Int

    /**
     * 插入消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: NoticeMessage)

    /**
     * 批量插入消息
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<NoticeMessage>)

    /**
     * 删除单条消息
     */
    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun delete(messageId: String)

    /**
     * 批量删除消息
     */
    @Query("DELETE FROM messages WHERE id IN (:messageIds)")
    suspend fun deleteByIds(messageIds: List<String>)

    /**
     * 清空所有消息
     */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /**
     * 保留最近 N 条消息，删除其余的
     */
    @Query("DELETE FROM messages WHERE id NOT IN (SELECT id FROM messages ORDER BY timestamp DESC LIMIT :keepCount)")
    suspend fun trimToSize(keepCount: Int)
}
