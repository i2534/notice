package com.github.i2534.notice.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 应用日志管理器
 * 日志持久化到文件，保留最近 7 天
 */
object AppLogger {
    private const val TAG = "AppLogger"
    private const val MAX_MEMORY_LOGS = 200
    private const val RETENTION_DAYS = 7
    private const val LOG_DIR = "logs"

    private val logs = ConcurrentLinkedDeque<LogEntry>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val writeLock = Object()

    private var logDir: File? = null
    private var currentLogFile: File? = null
    private var currentDate: String = ""

    data class LogEntry(
        val timestamp: Long,
        val level: Level,
        val tag: String,
        val message: String
    ) {
        fun timeString(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
        fun dateTimeString(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

        override fun toString(): String {
            return "${dateTimeString()} ${level.name.first()}/$tag: $message"
        }

        fun toDisplayString(): String {
            return "${timeString()} ${level.name.first()}/$tag: $message"
        }
    }

    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    /**
     * 初始化日志目录（在 Application.onCreate 中调用）
     */
    fun init(context: Context) {
        logDir = File(context.filesDir, LOG_DIR).apply {
            if (!exists()) mkdirs()
        }
        cleanOldLogs()
        Log.d(TAG, "Logger initialized: ${logDir?.absolutePath}")
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        addLog(Level.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addLog(Level.INFO, tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        addLog(Level.WARN, tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            addLog(Level.ERROR, tag, "$message: ${throwable.message}")
        } else {
            Log.e(tag, message)
            addLog(Level.ERROR, tag, message)
        }
    }

    private fun addLog(level: Level, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)

        // 添加到内存
        logs.addFirst(entry)
        while (logs.size > MAX_MEMORY_LOGS) {
            logs.removeLast()
        }

        // 同步写入文件（确保不丢失）
        writeToFile(entry)
    }

    private fun writeToFile(entry: LogEntry) {
        val dir = logDir ?: return

        synchronized(writeLock) {
            val today = fileDateFormat.format(Date())
            if (today != currentDate) {
                currentDate = today
                currentLogFile = File(dir, "notice-$today.log")
            }

            try {
                PrintWriter(FileWriter(currentLogFile, true)).use { writer ->
                    writer.println(entry.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write log: ${e.message}")
            }
        }
    }

    /**
     * 清理超过保留天数的日志文件
     */
    private fun cleanOldLogs() {
        Thread {
            val dir = logDir ?: return@Thread
            val cutoffTime = System.currentTimeMillis() - RETENTION_DAYS * 24 * 60 * 60 * 1000L

            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("notice-") && file.name.endsWith(".log")) {
                    if (file.lastModified() < cutoffTime) {
                        file.delete()
                        Log.d(TAG, "Deleted old log: ${file.name}")
                    }
                }
            }
        }.start()
    }

    /**
     * 获取内存中的日志（用于快速显示）
     */
    fun getMemoryLogs(): List<LogEntry> = logs.toList()

    /**
     * 获取所有日志（从文件读取，按时间倒序）
     */
    fun getAllLogs(days: Int = 1): List<LogEntry> {
        val dir = logDir ?: return logs.toList()
        val result = mutableListOf<LogEntry>()

        // 获取最近 N 天的日志文件
        val calendar = Calendar.getInstance()
        for (i in 0 until days) {
            val date = fileDateFormat.format(calendar.time)
            val file = File(dir, "notice-$date.log")
            if (file.exists()) {
                result.addAll(parseLogFile(file))
            }
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }

        return result.sortedByDescending { it.timestamp }
    }

    private fun parseLogFile(file: File): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        val lineFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        try {
            file.readLines().forEach { line ->
                // 格式: 2024-01-01 12:00:00.000 D/Tag: Message
                if (line.length > 25) {
                    try {
                        val dateStr = line.substring(0, 23)
                        val timestamp = lineFormat.parse(dateStr)?.time ?: return@forEach
                        val levelChar = line.getOrNull(24) ?: return@forEach
                        val level = when (levelChar) {
                            'D' -> Level.DEBUG
                            'I' -> Level.INFO
                            'W' -> Level.WARN
                            'E' -> Level.ERROR
                            else -> return@forEach
                        }
                        val rest = line.substring(26)
                        val colonIdx = rest.indexOf(':')
                        if (colonIdx > 0) {
                            val tag = rest.substring(0, colonIdx)
                            val message = rest.substring(colonIdx + 2)
                            entries.add(LogEntry(timestamp, level, tag, message))
                        }
                    } catch (_: Exception) {
                        // 忽略解析失败的行
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse log file: ${e.message}")
        }

        return entries
    }

    /**
     * 清空所有日志
     */
    fun clear() {
        logs.clear()
        synchronized(writeLock) {
            logDir?.listFiles()?.forEach { file ->
                if (file.name.startsWith("notice-") && file.name.endsWith(".log")) {
                    file.delete()
                }
            }
        }
    }

    /**
     * 获取日志文本（用于复制）
     */
    fun getLogsAsString(days: Int = 1): String {
        return getAllLogs(days).reversed().joinToString("\n") { it.toString() }
    }

    /**
     * 获取日志文件列表
     */
    fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith("notice-") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }
}
