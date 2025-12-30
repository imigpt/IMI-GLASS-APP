package com.sdk.glassessdksample.ui

import android.content.Context
import android.content.Intent

/**
 * Helper class to easily integrate Gemini Live functionality
 * into your existing activities
 */
object GeminiLiveHelper {
    
    /**
     * Launch the Gemini Live Activity
     */
    fun launchGeminiLive(context: Context) {
        val intent = Intent(context, GeminiLiveActivity::class.java)
        context.startActivity(intent)
    }
    
    /**
     * Embed Gemini Live Service directly in your activity
     * 
     * Example usage in your Activity:
     * 
     * ```kotlin
     * class YourActivity : AppCompatActivity(), GeminiLiveService.GeminiLiveCallbacks {
     *     private val geminiLiveHelper = GeminiLiveHelper.createService(this)
     *     
     *     fun startConversation() {
     *         geminiLiveHelper.startLiveConversation("You are a helpful assistant")
     *     }
     *     
     *     override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
     *         // Handle transcription updates
     *     }
     *     // ... implement other callbacks
     * }
     * ```
     */
    fun createService(context: Context, callbacks: GeminiLiveService.GeminiLiveCallbacks): GeminiLiveService {
        return GeminiLiveService(context, callbacks)
    }
    
    /**
     * Check if required permissions are granted
     */
    fun hasRequiredPermissions(context: Context): Boolean {
        return android.content.pm.PackageManager.PERMISSION_GRANTED ==
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO
                )
    }
}
