package com.github.i2534.notice.ui

import android.content.Context
import android.text.TextWatcher
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.i2534.notice.R
import com.github.i2534.notice.databinding.DialogTopicPickerBinding

fun showTopicPicker(
    context: Context,
    state: ReplyState,
    defaultTopic: String?,
    onDefaultSelected: () -> Unit,
    onTopicSelected: (String) -> Unit,
    onCustomTopic: (String) -> Unit
): AlertDialog {
    val binding = DialogTopicPickerBinding.inflate(LayoutInflater.from(context))
    val adapter = TopicListAdapter(onTopicSelected)

    binding.textViewDefaultTopic.text = context.getString(
        com.github.i2534.notice.R.string.topic_picker_default_with_topic,
        defaultTopic ?: context.getString(com.github.i2534.notice.R.string.topic_picker_default_label)
    )

    binding.recyclerViewTopics.apply {
        layoutManager = LinearLayoutManager(context)
        this.adapter = adapter
    }

    adapter.submitList(state.recentTopics.filter { it.isNotBlank() })

    if (state.recentTopics.none { it.isNotBlank() }) {
        binding.recyclerViewTopics.isVisible = false
    }

    binding.wrapDefault.setOnClickListener { onDefaultSelected() }

    binding.radioButtonDefault.setOnCheckedChangeListener { _, isChecked ->
        if (isChecked) onDefaultSelected()
    }

    binding.editTextTopic.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val topic = s?.toString()?.trim()
            if (!topic.isNullOrBlank()) onCustomTopic(topic)
        }
    })

    return AlertDialog.Builder(context, R.style.Theme_Notice_Dialog)
        .setView(binding.root)
        .create().also { dialog ->
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            // 设置对话框宽度为屏幕宽度的 85%
            val window = dialog.window
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val dialogWidth = (screenWidth * 0.85).toInt()
            window?.setLayout(dialogWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
}
