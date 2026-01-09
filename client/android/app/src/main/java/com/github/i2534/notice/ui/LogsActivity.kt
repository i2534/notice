package com.github.i2534.notice.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.i2534.notice.R
import com.github.i2534.notice.databinding.ActivityLogsBinding
import com.github.i2534.notice.util.AppLogger
import com.google.android.material.snackbar.Snackbar

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
        Thread {
            val allLogs = AppLogger.getAllLogs(currentDays)
            runOnUiThread {
                adapter.submitList(allLogs)
                updateEmptyView(allLogs.isEmpty())
            }
        }.start()
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
        Thread {
            val logsText = AppLogger.getLogsAsString(currentDays)
            if (logsText.isBlank()) {
                return@Thread
            }

            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Notice Logs", logsText)
                clipboard.setPrimaryClip(clip)
                Snackbar.make(binding.root, R.string.logs_copied, Snackbar.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun clearLogs() {
        AppLogger.clear()
        loadLogs()
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
