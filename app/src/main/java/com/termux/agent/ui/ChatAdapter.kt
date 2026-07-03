package com.termux.agent.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.termux.agent.R
import com.termux.agent.orchestrator.ChatMessage
import com.termux.agent.orchestrator.MessageRole

class ChatAdapter : ListAdapter<ChatMessage, ChatAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val roleLabel: TextView = itemView.findViewById(R.id.text_role)
        private val contentText: TextView = itemView.findViewById(R.id.text_content)
        private val timestampText: TextView = itemView.findViewById(R.id.text_timestamp)

        fun bind(message: ChatMessage) {
            roleLabel.text = when (message.role) {
                MessageRole.USER -> "你"
                MessageRole.AGENT -> "助手"
                MessageRole.TOOL -> "工具 [${message.stepId ?: ""}]"
                MessageRole.SYSTEM -> "系统"
            }

            contentText.text = message.content

            val bgColor = when (message.role) {
                MessageRole.USER -> Color.parseColor("#E3F2FD")
                MessageRole.AGENT -> Color.parseColor("#F3E5F5")
                MessageRole.TOOL -> Color.parseColor("#E8F5E9")
                MessageRole.SYSTEM -> Color.parseColor("#FFF3E0")
            }
            itemView.setBackgroundColor(bgColor)

            val dateFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            timestampText.text = dateFormat.format(java.util.Date(message.timestamp))
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.role == newItem.role
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
