# Quick Start Guide - Gemini Live Integration

## 🚀 Quick Integration (3 Options)

### Option 1: Launch Standalone Activity (Easiest)

Add a button to your existing activity to launch the full Gemini Live UI:

```kotlin
import com.sdk.glassessdksample.ui.GeminiLiveHelper

// In your activity (e.g., MainActivity)
binding.btnGeminiLive.setOnClickListener {
    GeminiLiveHelper.launchGeminiLive(this)
}
```

### Option 2: Embed Service in Existing Activity

Integrate directly into your MainActivity for seamless experience:

```kotlin
class MainActivity : AppCompatActivity(), GeminiLiveService.GeminiLiveCallbacks {
    
    private lateinit var geminiLiveService: GeminiLiveService
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... existing code ...
        
        // Initialize Gemini Live
        geminiLiveService = GeminiLiveHelper.createService(this)
        
        // Add button handler
        binding.btnStartLive.setOnClickListener {
            startGeminiLiveConversation()
        }
    }
    
    private fun startGeminiLiveConversation() {
        if (!GeminiLiveHelper.hasRequiredPermissions(this)) {
            // Request permissions first
            return
        }
        
        val systemPrompt = """
            You are Imi Glass AI assistant.
            Keep responses very concise (1-2 sentences).
            Be helpful and friendly.
        """.trimIndent()
        
        geminiLiveService.startLiveConversation(systemPrompt)
    }
    
    // Implement callbacks
    override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
        runOnUiThread {
            // Update your UI with live transcriptions
            binding.tvLiveTranscript.text = "You: $input\nAI: $output"
        }
    }
    
    override fun onTurnComplete(fullInput: String, fullOutput: String) {
        // Add to conversation history
        aiHistory.add(Pair(fullInput, fullOutput))
        Log.d(TAG, "Conversation turn complete")
    }
    
    override fun onAudioPlaybackStart() {
        // Pause wake word detection while AI is speaking
        customVoiceDetector?.pause()
    }
    
    override fun onAudioPlaybackEnd() {
        // Resume wake word detection
        customVoiceDetector?.resume()
    }
    
    override fun onError(error: String) {
        Toast.makeText(this, "Gemini Live Error: $error", Toast.LENGTH_LONG).show()
    }
    
    override fun onConnectionStatusChanged(isConnected: Boolean) {
        runOnUiThread {
            binding.tvConnectionStatus.text = if (isConnected) "🟢 Live" else "🔴 Offline"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        geminiLiveService.destroy()
    }
}
```

### Option 3: Custom Integration

For advanced use cases, extend the `GeminiLiveService` class:

```kotlin
class MyCustomGeminiService(callbacks: GeminiLiveCallbacks) : GeminiLiveService(callbacks) {
    
    // Add custom functionality here
    fun sendCustomMessage(text: String) {
        // Custom implementation
    }
}
```

## 📋 Prerequisites Checklist

- [x] API Key configured in `local.properties`
- [x] RECORD_AUDIO permission in AndroidManifest (already present)
- [x] Internet connectivity
- [x] OkHttp dependency (already present in build.gradle.kts)
- [x] Gson dependency (already present)

## 🎯 Testing the Integration

1. **Build the project:**
   ```bash
   ./gradlew build
   ```

2. **Install on device:**
   ```bash
   ./gradlew installDebug
   ```

3. **Grant microphone permission** when prompted

4. **Launch Gemini Live:**
   - Option 1: Navigate to the activity via button
   - Option 2: Test directly from MainActivity

5. **Start speaking** - the AI will respond in real-time!

## 🔧 Configuration Options

### Change Voice

In `GeminiLiveService.kt`, modify:
```kotlin
private const val VOICE_NAME = "Puck" // Change to: Charon, Kore, Fenrir, Aoede
```

### Adjust Audio Quality

```kotlin
private const val INPUT_SAMPLE_RATE = 16000  // 8000, 16000, 24000
private const val OUTPUT_SAMPLE_RATE = 24000 // 16000, 24000, 48000
```

### Customize System Prompt

```kotlin
val systemPrompt = """
    You are [YOUR CHARACTER].
    [SPECIFIC INSTRUCTIONS]
    [RESPONSE STYLE]
""".trimIndent()

geminiLiveService.startLiveConversation(systemPrompt)
```

## 🐛 Common Issues

### Issue: No audio output
**Solution:** Check device volume, verify AudioTrack initialization in logs

### Issue: WebSocket connection fails
**Solution:** Verify API key is valid, check internet connection

### Issue: High latency
**Solution:** Ensure WiFi connection, reduce buffer sizes in service

### Issue: Crashes on permission denial
**Solution:** Check permissions before starting service

## 📊 Performance Monitoring

Add logging to track performance:

```kotlin
override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
    val timestamp = System.currentTimeMillis()
    Log.d("PERFORMANCE", "Transcription update at: $timestamp")
    Log.d("PERFORMANCE", "Input length: ${input.length}, Output length: ${output.length}")
}
```

## 🎨 UI Integration Examples

### Add to MainActivity Layout

```xml
<Button
    android:id="@+id/btn_gemini_live"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="🎤 Start Gemini Live"
    android:backgroundTint="#4CAF50" />

<TextView
    android:id="@+id/tv_live_status"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="Ready"
    android:textAlignment="center" />
```

### Add to Menu

```xml
<!-- res/menu/main_menu.xml -->
<menu xmlns:android="http://schemas.android.com/apk/res/android">
    <item
        android:id="@+id/menu_gemini_live"
        android:title="Gemini Live"
        android:icon="@android:drawable/ic_btn_speak_now" />
</menu>
```

```kotlin
override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
        R.id.menu_gemini_live -> {
            GeminiLiveHelper.launchGeminiLive(this)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
```

## 🔐 Production Considerations

1. **Error Handling:** Implement comprehensive error recovery
2. **Rate Limiting:** Add throttling to prevent API abuse
3. **Analytics:** Track usage metrics
4. **User Feedback:** Add visual indicators for all states
5. **Battery:** Monitor battery usage during long sessions
6. **Network:** Handle network transitions gracefully

## 📚 Next Steps

1. ✅ Review the full documentation: `GEMINI_LIVE_INTEGRATION.md`
2. ✅ Check the example activity: `GeminiLiveActivity.kt`
3. ✅ Explore the service implementation: `GeminiLiveService.kt`
4. ✅ Test with different voices and configurations
5. ✅ Integrate with your smart glasses hardware

---

**Need Help?** Check the main integration guide or review the logs with tag `GeminiLiveService`
