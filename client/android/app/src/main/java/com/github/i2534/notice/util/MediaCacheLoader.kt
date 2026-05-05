package com.github.i2534.notice.util

import android.content.Context
import com.github.i2534.notice.data.AppDatabase
import com.github.i2534.notice.data.MediaCacheEntity
import com.github.i2534.notice.ui.ContentBlockParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 根据消息 content 加载图片/语音 URL → 本地路径的缓存映射（用于列表、最新消息、详情渲染）。
 * 无缓存时可先调用 [ensureMediaAndImageCache] 从 URL 下载并写入缓存，再按有缓存逻辑渲染。
 */
object MediaCacheLoader {

    suspend fun loadMediaCacheMap(context: Context, content: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val urls = ContentBlockParser.extractMediaAndImageUrls(content)
            if (urls.isEmpty()) emptyMap()
            else AppDatabase.getInstance(context.applicationContext).mediaCacheDao()
                .getByUrls(urls)
                .associate { it.mediaUrl to it.localPath }
        }

    /**
     * 确保消息中的图片/媒体 URL 均有本地缓存：缺失的从 URL 下载一次并写入 DB，然后返回当前缓存映射。
     * 调用方拿到 map 后按「有缓存」逻辑渲染即可；若某 URL 下载失败则 map 中无该项，渲染时可按无缓存显示或再试 URL。
     */
    suspend fun ensureMediaAndImageCache(context: Context, content: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val urls = ContentBlockParser.extractMediaAndImageUrls(content)
            if (urls.isEmpty()) return@withContext emptyMap()
            val mediaUrls = ContentBlockParser.extractMediaUrls(content).toSet()
            val dao = AppDatabase.getInstance(app).mediaCacheDao()
            val existing = dao.getByUrls(urls).associate { it.mediaUrl to it.localPath }
            for (url in urls) {
                val path = existing[url]
                if (!path.isNullOrBlank() && File(path).exists()) continue
                val isMedia = url in mediaUrls
                val localPath = MediaCacheDownloader.downloadToCache(app, url, isMedia)
                if (localPath != null) {
                    dao.insert(MediaCacheEntity(url, localPath))
                }
            }
            dao.getByUrls(urls).associate { it.mediaUrl to it.localPath }
        }
}
