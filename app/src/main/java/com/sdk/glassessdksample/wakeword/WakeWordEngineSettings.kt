package com.sdk.glassessdksample.wakeword

import android.content.Context

/**
 * Persisted wake-word engine selection used across app screens.
 */
enum class WakeWordEngine(val prefValue: String, val displayName: String) {
    CUSTOM_ONNX("custom_onnx", "Custom Wake Word"),
    SNOWBOY("snowboy", "Snowboy");

    companion object {
        fun fromPrefValue(value: String?): WakeWordEngine {
            return entries.firstOrNull { it.prefValue == value } ?: CUSTOM_ONNX
        }
    }
}

object WakeWordEngineSettings {
    private const val PREFS_NAME = "imi_prefs"
    private const val KEY_WAKE_WORD_ENGINE = "wake_word_engine"

    fun getSelectedEngine(context: Context): WakeWordEngine {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return WakeWordEngine.fromPrefValue(prefs.getString(KEY_WAKE_WORD_ENGINE, WakeWordEngine.CUSTOM_ONNX.prefValue))
    }

    fun setSelectedEngine(context: Context, engine: WakeWordEngine) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WAKE_WORD_ENGINE, engine.prefValue).apply()
    }
}
