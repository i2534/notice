package com.github.i2534.notice.ui

import android.content.Context
import android.text.TextWatcher
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
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

    binding.topicList.apply {
        layoutManager = LinearLayoutManager(context)
        this.adapter = adapter
    }

    adapter.submitList(state.recentTopics.filter { it.isNotBlank() })

    binding.customTopicInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val topic = s?.toString()?.trim()
            if (!topic.isNullOrBlank()) onCustomTopic(topic)
        }
    })

    binding.btnUseCustom.setOnClickListener {
        val topic = binding.customTopicInput.text?.toString()?.trim()
        if (!topic.isNullOrBlank()) onCustomTopic(topic)
    }

    return AlertDialog.Builder(context, R.style.Theme_Notice_BottomSheet)
        .setView(binding.root)
        .create().also { dialog ->
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            val window = dialog.window
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val dialogWidth = (screenWidth * 0.85).toInt()
            window?.setLayout(dialogWidth, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
}
