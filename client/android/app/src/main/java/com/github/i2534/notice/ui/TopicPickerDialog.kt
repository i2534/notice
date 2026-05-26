package com.github.i2534.notice.ui

import android.content.Context
import android.text.TextWatcher
import android.view.LayoutInflater
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.i2534.notice.R
import com.github.i2534.notice.databinding.DialogTopicPickerBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

fun showTopicPicker(
    context: Context,
    state: ReplyState,
    defaultTopic: String?,
    onDefaultSelected: () -> Unit,
    onTopicSelected: (String) -> Unit,
    onCustomTopic: (String) -> Unit
): BottomSheetDialog {
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

    return BottomSheetDialog(context, R.style.Theme_Notice_BottomSheet).apply {
        setContentView(binding.root)
        show()
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }
}
