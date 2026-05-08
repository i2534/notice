package com.github.i2534.notice.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.i2534.notice.R
import com.github.i2534.notice.util.TopicColor

private val topicDiffCallback = object : DiffUtil.ItemCallback<String>() {
    override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
}

class TopicListAdapter(
    private val onItemClick: (String) -> Unit
) : ListAdapter<String, TopicListAdapter.ViewHolder>(topicDiffCallback) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textViewTopic: TextView = view.findViewById(R.id.textViewTopic)
        val viewTopicDot: View = view.findViewById(R.id.viewTopicDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_topic, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val topic = getItem(position)
        holder.textViewTopic.text = topic
        holder.viewTopicDot.setBackgroundColor(TopicColor.forTopic(topic))
        holder.itemView.setOnClickListener { onItemClick(topic) }
    }
}
