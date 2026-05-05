package com.github.i2534.notice.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 媒体 URL 与本地缓存文件路径的映射（发送的语音等缓存到本地，便于列表内直接播放）。
 */
@Entity(tableName = "media_cache")
data class MediaCacheEntity(
    @PrimaryKey
    val mediaUrl: String,
    val localPath: String
)
