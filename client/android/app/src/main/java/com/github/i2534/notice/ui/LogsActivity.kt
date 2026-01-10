package com.github.i2534.notice.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.i2534.notice.R
import com.github.i2534.notice.databinding.ActivityLogsBinding
import com.github.i2534.notice.util.AppLogger
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private val adapter = LogAdapter()
    private var currentDays = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        loadLogs()
    }

    private fun setupToolbar() {
        updateTitle()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_days -> {
                    showDaysDialog()
                    true
                }
                R.id.action_copy -> {
                    copyLogs()
                    true
                }
                R.id.action_share -> {
                    shareLogs()
                    true
                }
                R.id.action_clear -> {
                    clearLogs()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateTitle() {
        binding.toolbar.title = getString(R.string.logs_title_format, currentDays)
    }

    private fun showDaysDialog() {
        val daysValues = intArrayOf(1, 3, 7)
        val currentIndex = daysValues.indexOf(currentDays).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.logs_select_days)
            .setSingleChoiceItems(R.array.logs_days_options, currentIndex) { dialog, which ->
                currentDays = daysValues[which]
                updateTitle()
                loadLogs()
                dialog.dismiss()
            }
            .show()
    }

    private fun setupRecyclerView() {
        binding.logList.layoutManager = LinearLayoutManager(this)
        binding.logList.adapter = adapter
    }

    private fun loadLogs() {
        // 先显示内存日志（快速响应）
        val memoryLogs = AppLogger.getMemoryLogs()
        adapter.submitList(memoryLogs)
        updateEmptyView(memoryLogs.isEmpty())

        // 异步加载文件日志
        lifecycleScope.launch {
            val allLogs = withContext(Dispatchers.IO) {
                AppLogger.getAllLogs(currentDays)
            }
            adapter.submitList(allLogs)
            updateEmptyView(allLogs.isEmpty())
        }
    }

    private fun updateEmptyView(isEmpty: Boolean) {
        if (isEmpty) {
            binding.emptyView.visibility = View.VISIBLE
            binding.logList.visibility = View.GONE
        } else {
            binding.emptyView.visibility = View.GONE
            binding.logList.visibility = View.VISIBLE
        }
    }

    private fun copyLogs() {
        lifecycleScope.launch {
            val allLogs = withContext(Dispatchers.IO) {
                AppLogger.getAllLogs(currentDays)
            }

            if (allLogs.isEmpty()) {
                Snackbar.make(binding.root, R.string.logs_empty, Snackbar.LENGTH_SHORT).show()
                return@launch
            }

            // Android 剪贴板限制约 1MB，保守使用 500KB
            val (logsText, count, truncated) = withContext(Dispatchers.Default) {
                val maxSize = 500 * 1024
                val logsReversed = allLogs.reversed()
                val builder = StringBuilder()
                var cnt = 0

                for (entry in logsReversed) {
                    val line = entry.toString() + "\n"
                    if (builder.length + line.length > maxSize) break
                    builder.append(line)
                    cnt++
                }
                Triple(builder.toString(), cnt, cnt < allLogs.size)
            }

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Notice Logs", logsText)
            clipboard.setPrimaryClip(clip)

            val message = if (truncated) {
                getString(R.string.logs_copied_partial, count, allLogs.size)
            } else {
                getString(R.string.logs_copied_count, count)
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun clearLogs() {
        AppLogger.clear()
        loadLogs()
    }

    private fun shareLogs() {
        Snackbar.make(binding.root, R.string.logs_exporting, Snackbar.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val (logFile, logsText) = withContext(Dispatchers.IO) {
                    val text = AppLogger.getLogsAsString(currentDays)
                    if (text.isBlank()) return@withContext null to text

                    val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    val fileName = "notice_logs_${dateFormat.format(Date())}.txt"
                    val dir = File(cacheDir, "shared_logs").apply { mkdirs() }
                    val file = File(dir, fileName)
                    file.writeText(text)
                    file to text
                }

                if (logFile == null) {
                    Snackbar.make(binding.root, R.string.logs_empty, Snackbar.LENGTH_SHORT).show()
                    return@launch
                }

                val uri = FileProvider.getUriForFile(
                    this@LogsActivity,
                    "${packageName}.fileprovider",
                    logFile
                )

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, getString(R.string.logs_share_title)))
            } catch (e: Exception) {
                Snackbar.make(binding.root, R.string.logs_export_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        private var logs: List<AppLogger.LogEntry> = emptyList()

        fun submitList(newLogs: List<AppLogger.LogEntry>) {
            logs = newLogs
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(logs[position])
        }

        override fun getItemCount() = logs.size

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val timeView: TextView = view.findViewById(R.id.logTime)
            private val levelView: TextView = view.findViewById(R.id.logLevel)
            private val messageView: TextView = view.findViewById(R.id.logMessage)

            fun bind(entry: AppLogger.LogEntry) {
                timeView.text = entry.timeString()
                levelView.text = entry.level.name.first().toString()
                messageView.text = "${entry.tag}: ${entry.message}"

                // 根据日志级别设置颜色
                val color = when (entry.level) {
                    AppLogger.Level.DEBUG -> Color.GRAY
                    AppLogger.Level.INFO -> Color.parseColor("#4CAF50")
                    AppLogger.Level.WARN -> Color.parseColor("#FF9800")
                    AppLogger.Level.ERROR -> Color.parseColor("#F44336")
                }
                levelView.setTextColor(color)
            }
        }
    }
}
