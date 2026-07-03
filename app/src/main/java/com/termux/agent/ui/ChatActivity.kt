package com.termux.agent.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.termux.agent.R
import com.termux.agent.settings.SettingsActivity

class ChatActivity : AppCompatActivity() {

    private val viewModel: ChatViewModel by viewModels()
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ChatAdapter
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var confirmButton: Button
    private lateinit var rejectButton: Button
    private lateinit var settingsButton: Button
    private lateinit var llmStatusText: TextView
    private lateinit var bootstrapStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        recyclerView = findViewById(R.id.recycler_chat)
        inputEditText = findViewById(R.id.edit_input)
        sendButton = findViewById(R.id.btn_send)
        confirmButton = findViewById(R.id.btn_confirm)
        rejectButton = findViewById(R.id.btn_reject)
        settingsButton = findViewById(R.id.btn_settings)
        llmStatusText = findViewById(R.id.text_llm_status)
        bootstrapStatusText = findViewById(R.id.text_bootstrap_status)

        adapter = ChatAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        viewModel.messages.observe(this) { messages ->
            adapter.submitList(messages)
            if (messages.isNotEmpty()) {
                recyclerView.smoothScrollToPosition(messages.size - 1)
            }
        }

        viewModel.showConfirmation.observe(this) { show ->
            confirmButton.isEnabled = show
            rejectButton.isEnabled = show
        }

        viewModel.llmStatus.observe(this) { status ->
            llmStatusText.text = status
        }

        viewModel.bootstrapStatus.observe(this) { status ->
            bootstrapStatusText.text = status
            bootstrapStatusText.visibility = if (status.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        viewModel.toastMessage.observe(this) { msg ->
            if (msg != null) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        sendButton.setOnClickListener {
            val text = inputEditText.text.toString().trim()
            if (text.isNotEmpty()) {
                viewModel.sendMessage(text)
                inputEditText.text.clear()
            }
        }

        confirmButton.setOnClickListener {
            viewModel.confirmStep()
        }

        rejectButton.setOnClickListener {
            viewModel.rejectStep()
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
