package com.sdk.glassessdksample.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.sdk.glassessdksample.R

/**
 * History screen with two states:
 *  - Recents list: distinct conversation topics (screen 1)
 *  - Thread view:  the selected topic rendered as chat bubbles (screen 2)
 *
 * Backing data is the flat conversation log stored in "imi_prefs" as a
 * List<Pair<role, text>>. Each user turn (and the AI reply that follows) becomes
 * one topic.
 */
class ConversationHistoryActivity : AppCompatActivity() {

    private lateinit var viewRecents: View
    private lateinit var viewThread: View
    private lateinit var tvEmptyRecents: TextView

    private lateinit var rvRecents: RecyclerView
    private lateinit var rvThread: RecyclerView
    private lateinit var recentsAdapter: HistoryRecentsAdapter
    private lateinit var bubbleAdapter: HistoryBubbleAdapter

    private var showingThread = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_history)

        viewRecents = findViewById(R.id.view_recents)
        viewThread = findViewById(R.id.view_thread)
        tvEmptyRecents = findViewById(R.id.tvEmptyRecents)
        rvRecents = findViewById(R.id.rvRecents)
        rvThread = findViewById(R.id.rvThread)

        recentsAdapter = HistoryRecentsAdapter(emptyList()) { topic -> openThread(topic) }
        rvRecents.layoutManager = LinearLayoutManager(this)
        rvRecents.adapter = recentsAdapter

        bubbleAdapter = HistoryBubbleAdapter(emptyList())
        rvThread.layoutManager = LinearLayoutManager(this)
        rvThread.adapter = bubbleAdapter

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { handleBack() }
        findViewById<TextView>(R.id.btnClearHistory).setOnClickListener { clearHistory() }

        loadAndRenderHistory()
    }

    private fun openThread(topic: HistoryTopic) {
        bubbleAdapter.update(topic.messages)
        showingThread = true
        viewRecents.visibility = View.GONE
        viewThread.visibility = View.VISIBLE
    }

    private fun showRecents() {
        showingThread = false
        viewThread.visibility = View.GONE
        viewRecents.visibility = View.VISIBLE
    }

    private fun handleBack() {
        if (showingThread) showRecents() else finish()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (showingThread) showRecents() else super.onBackPressed()
    }

    private fun loadAndRenderHistory() {
        val prefs = getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("conversation_history", null)

        val entries = if (historyJson.isNullOrBlank()) emptyList() else parseHistory(historyJson)
        val topics = buildTopics(entries)

        recentsAdapter.update(topics)
        tvEmptyRecents.visibility = if (topics.isEmpty()) View.VISIBLE else View.GONE
        rvRecents.visibility = if (topics.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Groups the flat (role, text) log into topics. A user turn opens a new topic
     * whose title is that message; the following AI turn(s) attach to it until the
     * next user turn. Leading AI turns (no preceding user turn) form their own topic.
     */
    private fun buildTopics(entries: List<Pair<String, String>>): List<HistoryTopic> {
        if (entries.isEmpty()) return emptyList()

        val topics = mutableListOf<HistoryTopic>()
        var currentTitle: String? = null
        var currentMessages = mutableListOf<Pair<Boolean, String>>()

        fun flush() {
            if (currentMessages.isNotEmpty()) {
                val title = currentTitle ?: currentMessages.first().second
                topics.add(HistoryTopic(title.take(60), currentMessages.toList()))
            }
        }

        entries.forEach { (role, text) ->
            val isUser = role.equals("User", ignoreCase = true)
            if (isUser) {
                // Start a new topic on each user turn.
                flush()
                currentTitle = text
                currentMessages = mutableListOf(true to text)
            } else {
                if (currentMessages.isEmpty()) {
                    currentTitle = text
                }
                currentMessages.add(false to text)
            }
        }
        flush()

        return topics.asReversed() // newest first
    }

    private fun clearHistory() {
        val prefs = getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("conversation_history")
            .remove("conversation_timestamp")
            .apply()

        recentsAdapter.update(emptyList())
        bubbleAdapter.update(emptyList())
        showRecents()
        tvEmptyRecents.visibility = View.VISIBLE
        rvRecents.visibility = View.GONE
        Toast.makeText(this, "Conversation history cleared", Toast.LENGTH_SHORT).show()
    }

    private fun parseHistory(historyJson: String): List<Pair<String, String>> {
        val normalized = mutableListOf<Pair<String, String>>()

        try {
            val gson = Gson()
            val type = object : TypeToken<MutableList<Pair<String, String>>>() {}.type
            val raw: MutableList<Pair<String, String>> = gson.fromJson(historyJson, type) ?: mutableListOf()

            raw.forEach { pair ->
                appendNormalizedPair(normalized, pair.first, pair.second)
            }
        } catch (_: Exception) {
            // Fall through to generic JSON parsing.
        }

        if (normalized.isNotEmpty()) {
            return normalized
        }

        return try {
            val root = JsonParser.parseString(historyJson)
            if (!root.isJsonArray) {
                return emptyList()
            }

            root.asJsonArray.forEach { item ->
                if (item.isJsonArray) {
                    val arr = item.asJsonArray
                    if (arr.size() >= 2) {
                        appendNormalizedPair(normalized, arr[0].asStringOrNull(), arr[1].asStringOrNull())
                    }
                    return@forEach
                }

                if (!item.isJsonObject) {
                    return@forEach
                }

                val obj = item.asJsonObject
                val first = obj.stringOrNull("first")
                val second = obj.stringOrNull("second")
                if (!first.isNullOrBlank() || !second.isNullOrBlank()) {
                    appendNormalizedPair(normalized, first, second)
                    return@forEach
                }

                val role = obj.stringOrNull("role") ?: obj.stringOrNull("speaker")
                val content = obj.stringOrNull("content") ?: obj.stringOrNull("message") ?: obj.stringOrNull("text")
                if (!role.isNullOrBlank() || !content.isNullOrBlank()) {
                    appendNormalizedPair(normalized, role, content)
                    return@forEach
                }

                val input = obj.stringOrNull("input") ?: obj.stringOrNull("fullInput") ?: obj.stringOrNull("prompt")
                val output = obj.stringOrNull("output") ?: obj.stringOrNull("fullOutput") ?: obj.stringOrNull("response")
                appendNormalizedPair(normalized, input, output)
            }

            normalized
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun appendNormalizedPair(result: MutableList<Pair<String, String>>, leftRaw: String?, rightRaw: String?) {
        val left = leftRaw?.trim().orEmpty()
        val right = rightRaw?.trim().orEmpty()
        if (left.isEmpty() && right.isEmpty()) {
            return
        }

        val roleLike = left.lowercase() in setOf("user", "you", "model", "assistant", "ai", "imi", "system")
        if (roleLike) {
            val role = when (left.lowercase()) {
                "user", "you" -> "User"
                "model", "assistant", "ai", "imi" -> "AI"
                else -> "System"
            }
            if (right.isNotEmpty()) {
                result.add(role to right)
            }
            return
        }

        if (left.isNotEmpty()) {
            result.add("User" to left)
        }
        if (right.isNotEmpty()) {
            result.add("AI" to right)
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        if (!has(key)) {
            return null
        }
        return get(key).asStringOrNull()
    }

    private fun com.google.gson.JsonElement?.asStringOrNull(): String? {
        if (this == null || isJsonNull) {
            return null
        }
        return try {
            asString
        } catch (_: Exception) {
            toString()
        }
    }
}
