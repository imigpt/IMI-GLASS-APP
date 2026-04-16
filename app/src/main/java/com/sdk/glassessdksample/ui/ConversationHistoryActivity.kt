package com.sdk.glassessdksample.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.sdk.glassessdksample.R

class ConversationHistoryActivity : AppCompatActivity() {

    private lateinit var tvConversationHistory: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_conversation_history)

        tvConversationHistory = findViewById(R.id.tvConversationHistory)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.btnClearHistory).setOnClickListener {
            clearHistory()
        }

        findViewById<TextView>(R.id.btnUserSummary).setOnClickListener {
            startActivity(Intent(this, UserProfileSummaryActivity::class.java))
        }

        loadAndRenderHistory()
    }

    private fun loadAndRenderHistory() {
        val prefs = getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
        val historyJson = prefs.getString("conversation_history", null)

        if (historyJson.isNullOrBlank()) {
            tvConversationHistory.text = "No conversation history found yet."
            return
        }

        val entries = parseHistory(historyJson)
        if (entries.isEmpty()) {
            tvConversationHistory.text = "No conversation history found yet."
            return
        }

        val rendered = buildString {
            entries.forEach { entry ->
                append(entry.first)
                append(": ")
                append(entry.second)
                append("\n\n")
            }
        }

        tvConversationHistory.text = rendered.trimEnd()
    }

    private fun clearHistory() {
        val prefs = getSharedPreferences("imi_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .remove("conversation_history")
            .remove("conversation_timestamp")
            .apply()

        tvConversationHistory.text = "No conversation history found yet."
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
