package com.github.i2534.notice.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * 将消息中的图片保存到系统相册（Pictures/Notice）。
 * minSdk=29（Android 10+），使用 MediaStore 写入无需任何存储权限。
 *
 * 图片数据来源优先级：
 * 1. 本地缓存文件（详情页已通过 MediaCacheLoader 预下载，最常见路径）→ 拷贝原始字节，保留 GIF/WebP 等原始格式
 * 2. 无缓存 → 重新从 URL 下载一次
 * 3. 都失败 → 从 ImageView 当前位图压缩为 PNG 兜底
 */
object MediaSaveHelper {

    private const val ALBUM_DIR = "Notice" // Pictures/Notice

    /**
     * @param context 应用上下文
     * @param url 图片原始 URL
     * @param localCachePath 本地缓存文件路径，可为 null
     * @param fallbackBitmap 兜底位图（从 ImageView 提取），可为 null
     * @return 保存成功返回 true
     */
    suspend fun saveToGallery(
        context: Context,
        url: String,
        localCachePath: String?,
        fallbackBitmap: Bitmap?,
    ): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext

        // 1. 本地缓存文件直接拷贝
        val cacheFile = localCachePath?.let { File(it) }
        if (cacheFile != null && cacheFile.exists() && cacheFile.isFile) {
            val ok = saveFileToGallery(app, cacheFile, url)
            if (ok) return@withContext true
        }

        // 2. 重新下载
        val downloaded = MediaCacheDownloader.downloadToCache(app, url, isMedia = false)
        if (downloaded != null) {
            val f = File(downloaded)
            if (f.exists() && f.isFile) {
                val ok = saveFileToGallery(app, f, url)
                if (ok) return@withContext true
            }
        }

        // 3. 位图兜底
        fallbackBitmap?.let { bmp ->
            if (saveBitmapToGallery(app, bmp, url)) return@withContext true
        }

        false
    }

    /** 从 ImageView 提取当前显示位图（无有效位图时返回 null）。 */
    fun extractBitmap(imageView: ImageView): Bitmap? =
        (imageView.drawable as? BitmapDrawable)?.bitmap

    private fun saveFileToGallery(context: Context, file: File, url: String): Boolean {
        val resolver = context.contentResolver
        val ext = file.extension.lowercase().ifBlank { "jpg" }
        val values = baseValues(url, ext, mimeFromExtension(ext)).also {
            it.put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val written = resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } != null
            if (written) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null
                )
                true
            } else {
                resolver.delete(uri, null, null)
                false
            }
        } catch (e: Exception) {
            AppLogger.e("MediaSaveHelper", "保存图片失败: ${e.message} url=$url")
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, url: String): Boolean {
        val resolver = context.contentResolver
        val values = baseValues(url, "png", "image/png").also {
            it.put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            val written = resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } != null
            if (written) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null
                )
                true
            } else {
                resolver.delete(uri, null, null)
                false
            }
        } catch (e: Exception) {
            AppLogger.e("MediaSaveHelper", "保存位图失败: ${e.message} url=$url")
            resolver.delete(uri, null, null)
            false
        }
    }

    private fun baseValues(url: String, ext: String, mime: String): ContentValues =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayNameFromUrl(url, ext))
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ALBUM_DIR
            )
        }

    /** 从 URL 提取文件名（去掉后缀），无效时用 url 的 MD5 前缀兜底。 */
    private fun displayNameFromUrl(url: String, ext: String): String {
        val last = try {
            URL(url).path.substringAfterLast('/')
        } catch (_: Exception) {
            ""
        }
        val base = last.substringBeforeLast('.').trim().takeIf { it.isNotBlank() }
            ?: "notice_${md5(url).take(8)}"
        return "$base.$ext"
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun mimeFromExtension(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }
}
