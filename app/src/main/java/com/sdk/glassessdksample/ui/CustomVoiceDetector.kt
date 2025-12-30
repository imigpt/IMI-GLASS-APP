package com.sdk.glassessdksample.ui

import android.content.Context
import android.util.Log

/**
 * Deprecated stub of the previously in-app custom detector.
 * The project now uses `HotHelper` (Porcupine) for wake-word detection.
 * Keeping this minimal stub avoids bringing TensorFlow/TFLite into the compile
 * classpath while preserving the symbol for any remaining references.
 */
class CustomVoiceDetector(private val context: Context) {
    companion object { private const val TAG = "CustomVoiceDetector" }

    fun startDetection() {
        Log.w(TAG, "startDetection() called on deprecated CustomVoiceDetector — no-op")
    }

    fun stopDetection() {
        Log.w(TAG, "stopDetection() called on deprecated CustomVoiceDetector — no-op")
    }

    fun release() {
        Log.w(TAG, "release() called on deprecated CustomVoiceDetector — no-op")
    }
}