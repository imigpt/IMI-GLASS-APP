package com.sdk.glassessdksample.ui

import android.content.Context
import java.util.Calendar

/**
 * Central store for user-customisable AI behaviour: how long answers should be,
 * what tone the assistant uses, and how it greets the user when a session starts.
 *
 * Everything lives in the shared "imi_prefs" file so it is readable from both the
 * Settings screen (where the user picks values) and the live-conversation paths
 * (MainActivity system instruction + GeminiLiveService greeting).
 */
object AiResponsePrefs {

    private const val PREFS = "imi_prefs"

    private const val KEY_LENGTH = "ai_response_length"
    private const val KEY_TONE = "ai_response_tone"
    private const val KEY_GREETING_STYLE = "ai_greeting_style"
    private const val KEY_CUSTOM_GREETING = "ai_custom_greeting"
    private const val KEY_USE_NAME = "ai_greeting_use_name"

    // ---- Answer length ----
    enum class Length { SHORT, BALANCED, DETAILED }
    // ---- Conversational tone ----
    enum class Tone { FRIENDLY, PROFESSIONAL, CASUAL, DIRECT }
    // ---- Greeting style ----
    enum class GreetingStyle { TIME_BASED, SIMPLE, CUSTOM }

    // User-facing labels for spinners (index matches enum ordinal).
    val lengthLabels = listOf("Short (1–2 sentences)", "Balanced", "Detailed")
    val toneLabels = listOf("Friendly", "Professional", "Casual", "Direct")
    val greetingLabels = listOf("Time-based (Good morning…)", "Simple (Hi there)", "Custom")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Getters ----
    fun getLength(context: Context): Length =
        runCatching { Length.valueOf(prefs(context).getString(KEY_LENGTH, Length.SHORT.name)!!) }
            .getOrDefault(Length.SHORT)

    fun getTone(context: Context): Tone =
        runCatching { Tone.valueOf(prefs(context).getString(KEY_TONE, Tone.FRIENDLY.name)!!) }
            .getOrDefault(Tone.FRIENDLY)

    fun getGreetingStyle(context: Context): GreetingStyle =
        runCatching { GreetingStyle.valueOf(prefs(context).getString(KEY_GREETING_STYLE, GreetingStyle.TIME_BASED.name)!!) }
            .getOrDefault(GreetingStyle.TIME_BASED)

    fun getCustomGreeting(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_GREETING, "")?.trim() ?: ""

    fun getUseName(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_NAME, true)

    // ---- Setters ----
    fun setLength(context: Context, value: Length) =
        prefs(context).edit().putString(KEY_LENGTH, value.name).apply()

    fun setTone(context: Context, value: Tone) =
        prefs(context).edit().putString(KEY_TONE, value.name).apply()

    fun setGreetingStyle(context: Context, value: GreetingStyle) =
        prefs(context).edit().putString(KEY_GREETING_STYLE, value.name).apply()

    fun setCustomGreeting(context: Context, value: String) =
        prefs(context).edit().putString(KEY_CUSTOM_GREETING, value.trim()).apply()

    fun setUseName(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_USE_NAME, value).apply()

    /**
     * Sentence(s) describing answer length + tone, injected into the live system
     * instruction so every reply follows the user's chosen style.
     */
    fun buildResponseStyleInstruction(context: Context): String {
        val length = when (getLength(context)) {
            Length.SHORT -> "Keep your responses very concise — 1 to 2 sentences maximum."
            Length.BALANCED -> "Keep your responses concise but complete — a few short sentences when needed."
            Length.DETAILED -> "Give thorough, detailed responses with helpful explanations when the topic calls for it."
        }
        val tone = when (getTone(context)) {
            Tone.FRIENDLY -> "Use a warm, friendly, conversational tone."
            Tone.PROFESSIONAL -> "Use a polished, professional tone."
            Tone.CASUAL -> "Use a relaxed, casual, upbeat tone."
            Tone.DIRECT -> "Be direct and straight to the point, without filler."
        }
        return "$length $tone"
    }

    /**
     * Instruction telling the model how to open the conversation. The model speaks
     * this as a short greeting, then waits. Honours the chosen greeting style, the
     * user's name (if enabled and known) and the current time of day.
     */
    fun buildGreetingInstruction(context: Context): String {
        val name = if (getUseName(context)) {
            runCatching { UserMemoryManager(context).getUserMemory().userName.trim() }
                .getOrDefault("")
        } else ""

        val namePart = if (name.isNotBlank()) " Address the user by name ($name)." else ""
        val tonePart = when (getTone(context)) {
            Tone.FRIENDLY -> "warm and friendly"
            Tone.PROFESSIONAL -> "polished and professional"
            Tone.CASUAL -> "relaxed and casual"
            Tone.DIRECT -> "brief and direct"
        }

        return when (getGreetingStyle(context)) {
            GreetingStyle.TIME_BASED -> {
                val timeOfDay = currentTimeOfDay()
                "Open the conversation by greeting the user out loud. Say \"Good $timeOfDay\", " +
                    "introduce yourself as imi glass, and ask how you can help.$namePart " +
                    "Make it $tonePart and keep it to one short sentence, then stop and wait for them to speak."
            }
            GreetingStyle.SIMPLE -> {
                "Open the conversation by greeting the user out loud with a short hello as imi glass, " +
                    "and ask how you can help.$namePart Make it $tonePart and keep it to one short sentence, " +
                    "then stop and wait for them to speak."
            }
            GreetingStyle.CUSTOM -> {
                val custom = getCustomGreeting(context)
                if (custom.isBlank()) {
                    // Fall back to a simple greeting if the user left the field empty.
                    "Open the conversation by greeting the user out loud with a short hello as Imi Glasses, " +
                        "and ask how you can help.$namePart Keep it to one short sentence, then stop and wait."
                } else {
                    "Open the conversation by greeting the user out loud with this greeting: \"$custom\".$namePart " +
                        "Keep it to one short sentence, then stop and wait for them to speak."
                }
            }
        }
    }

    private fun currentTimeOfDay(): String {
        return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "evening"
        }
    }
}
