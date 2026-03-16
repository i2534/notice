package com.github.i2534.notice.util

import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 上传媒体文件到 Notice 服务器 POST /api/media/upload。
 * @param baseUrl 服务器根 URL，如 https://notice.example.com（无末尾斜杠）
 * @param token Bearer Token（与 MQTT 认证一致）
 * @param file 本地文件（如 m4a 语音）
 * @return 成功时返回 media_url，失败时返回 null 并已打日志
 */
fun uploadMedia(baseUrl: String, token: String, file: File): String? {
    val urlStr = baseUrl.trimEnd('/') + "/api/media/upload"
    var connection: HttpURLConnection? = null
    try {
        val url = URL(urlStr)
        connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $token")
        val boundary = "----NoticeUpload${UUID.randomUUID()}"
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000

        val out: OutputStream = connection.outputStream
        val crlf = "\r\n"
        out.write("--$boundary$crlf".toByteArray(Charsets.UTF_8))
        out.write("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"$crlf".toByteArray(Charsets.UTF_8))
        out.write("Content-Type: application/octet-stream$crlf$crlf".toByteArray(Charsets.UTF_8))
        file.inputStream().use { it.copyTo(out) }
        out.write("$crlf--$boundary--$crlf".toByteArray(Charsets.UTF_8))
        out.flush()
        out.close()

        val code = connection.responseCode
        val body = if (code in 200..299) {
            connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } else {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        }
        if (code != 200) {
            AppLogger.w("MediaUploadHelper", "upload failed code=$code body=$body")
            return null
        }
        val json = org.json.JSONObject(body)
        if (!json.optBoolean("success", false)) {
            AppLogger.w("MediaUploadHelper", "upload success=false body=$body")
            return null
        }
        val mediaUrl = json.optString("media_url", "").takeIf { it.isNotBlank() }
        if (mediaUrl == null) {
            AppLogger.w("MediaUploadHelper", "upload response missing media_url")
        }
        return mediaUrl
    } catch (e: Exception) {
        AppLogger.e("MediaUploadHelper", "upload error: ${e.message}")
        return null
    } finally {
        connection?.disconnect()
    }
}
