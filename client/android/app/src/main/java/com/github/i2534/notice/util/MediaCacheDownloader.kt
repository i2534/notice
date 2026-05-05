package com.github.i2534.notice.util

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 将图片/媒体 URL 下载到本地缓存目录，供 MediaCacheLoader 写入 DB 后按「有缓存」逻辑展示。
 */
object MediaCacheDownloader {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val mediaExtensions = setOf("m4a", "mp3", "ogg", "aac", "wav", "webm")

    /**
     * 下载 URL 到 media_cache 目录。
     * @param context 用于获取 filesDir
     * @param url 图片或媒体 URL
     * @param isMedia true 表示语音/音频，false 表示图片；扩展名均优先从 URL 路径推断，没有再使用默认值（媒体 .m4a，图片 .jpg）
     * @return 成功时返回本地绝对路径，失败返回 null
     */
    fun downloadToCache(context: Context, url: String, isMedia: Boolean): String? {
        var connection: HttpURLConnection? = null
        try {
            val parsed = URL(url)
            connection = parsed.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                AppLogger.w("MediaCacheDownloader", "download failed code=$code url=$url")
                return null
            }
            val ext = if (isMedia) {
                pathToExtension(url, mediaExtensions) ?: "m4a"
            } else {
                pathToExtension(url, imageExtensions) ?: "jpg"
            }
            val dir = File(context.filesDir, MediaCacheConstants.DIR_NAME)
            dir.mkdirs()
            val name = MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8))
                .take(16).joinToString("") { "%02x".format(it) } + ".$ext"
            val dest = File(dir, name)
            connection.inputStream.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return dest.absolutePath
        } catch (e: Exception) {
            AppLogger.e("MediaCacheDownloader", "download error: ${e.message} url=$url")
            return null
        } finally {
            connection?.disconnect()
        }
    }

    /** 从 URL 路径取最后一个后缀，若在允许集合内则返回（小写），否则返回 null。 */
    private fun pathToExtension(url: String, allowed: Set<String>): String? {
        val path = try {
            URL(url).path
        } catch (_: Exception) {
            return null
        }
        val last = path.substringAfterLast('.')
        return if (last != path && last.lowercase() in allowed) last.lowercase() else null
    }
}
