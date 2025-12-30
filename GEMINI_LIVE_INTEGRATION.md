# Gemini Live API Integration for Android

This implementation provides real-time bidirectional audio streaming with Google's Gemini 2.0 Flash Live API, similar to the TypeScript implementation you provided.

## 📁 Files Created

1. **GeminiLiveService.kt** - Core service handling WebSocket communication and audio streaming
2. **GeminiLiveActivity.kt** - Example activity demonstrating usage
3. **activity_gemini_live.xml** - UI layout for the example activity

## ✨ Features

- ✅ **Real-time bidirectional audio streaming** (WebSocket-based)
- ✅ **Live transcriptions** for both user input and AI output
- ✅ **16kHz PCM audio input** from microphone
- ✅ **24kHz PCM audio output** to speaker
- ✅ **Turn-based conversation management**
- ✅ **Interruption handling** (stops AI when user speaks)
- ✅ **Audio queue management** for smooth playback
- ✅ **Callback-based architecture** for easy integration

## 🏗️ Architecture

### GeminiLiveService

The main service class that handles:

```kotlin
GeminiLiveService(callbacks: GeminiLiveCallbacks)
```

**Key Methods:**
- `startLiveConversation(systemInstruction: String)` - Start a new live session
- `stopLiveConversation()` - End the current session
- `destroy()` - Clean up all resources

**Callbacks:**
```kotlin
interface GeminiLiveCallbacks {
    fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean)
    fun onTurnComplete(fullInput: String, fullOutput: String)
    fun onAudioPlaybackStart()
    fun onAudioPlaybackEnd()
    fun onError(error: String)
    fun onConnectionStatusChanged(isConnected: Boolean)
}
```

## 🔧 Integration Steps

### 1. Add to Your Activity

```kotlin
class YourActivity : AppCompatActivity(), GeminiLiveService.GeminiLiveCallbacks {
    
    private lateinit var geminiLiveService: GeminiLiveService
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize service
        geminiLiveService = GeminiLiveService(this)
    }
    
    private fun startConversation() {
        val systemInstruction = """
            You are a helpful AI assistant.
            Keep responses concise.
        """.trimIndent()
        
        geminiLiveService.startLiveConversation(systemInstruction)
    }
    
    private fun stopConversation() {
        geminiLiveService.stopLiveConversation()
    }
    
    // Implement callbacks
    override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
        // Update UI with live transcriptions
    }
    
    override fun onTurnComplete(fullInput: String, fullOutput: String) {
        // Save to history, log, etc.
    }
    
    override fun onAudioPlaybackStart() {
        // AI started speaking
    }
    
    override fun onAudioPlaybackEnd() {
        // AI finished speaking
    }
    
    override fun onError(error: String) {
        // Handle errors
    }
    
    override fun onConnectionStatusChanged(isConnected: Boolean) {
        // Update connection status UI
    }
    
    override fun onDestroy() {
        super.onDestroy()
        geminiLiveService.destroy()
    }
}
```

### 2. Add Permissions to AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<!-- Add the activity -->
<activity
    android:name=".ui.GeminiLiveActivity"
    android:exported="true" />
```

### 3. Request Runtime Permissions

```kotlin
private fun checkPermissions() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED
    ) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
    }
}
```

### 4. Ensure API Key is Configured

Make sure your `local.properties` file contains:

```properties
GEMINI_API_KEY=your_api_key_here
```

## 🎯 Usage in MainActivity

To integrate into your existing `MainActivity.kt`:

```kotlin
class MainActivity : AppCompatActivity(), GeminiLiveService.GeminiLiveCallbacks {
    
    private var geminiLiveService: GeminiLiveService? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ... existing code ...
        
        // Initialize Gemini Live Service
        geminiLiveService = GeminiLiveService(this)
    }
    
    // Add a button to start live conversation
    private fun startGeminiLive() {
        geminiLiveService?.startLiveConversation(
            "You are Imi Glass AI assistant. Keep responses very concise."
        )
    }
    
    // Implement callbacks...
    override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
        Log.d(TAG, "User: $input | AI: $output")
        // Update your UI
    }
    
    override fun onTurnComplete(fullInput: String, fullOutput: String) {
        // Add to your existing aiHistory
        aiHistory.add(Pair(fullInput, fullOutput))
    }
    
    override fun onAudioPlaybackStart() {
        // Maybe pause your CustomVoiceDetector
    }
    
    override fun onAudioPlaybackEnd() {
        // Resume your CustomVoiceDetector
    }
    
    override fun onError(error: String) {
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
    }
    
    override fun onConnectionStatusChanged(isConnected: Boolean) {
        // Update connection indicator
    }
}
```

## 🔊 Audio Configuration

### Input (Microphone)
- **Sample Rate:** 16kHz
- **Format:** PCM 16-bit
- **Channels:** Mono
- **Source:** VOICE_COMMUNICATION

### Output (Speaker)
- **Sample Rate:** 24kHz  
- **Format:** PCM 16-bit
- **Channels:** Mono
- **Mode:** Streaming playback

## 🌐 WebSocket Protocol

### Connection URL
```
wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=YOUR_API_KEY
```

### Message Types

**1. Setup (Client → Server)**
```json
{
  "setup": {
    "model": "models/gemini-2.0-flash-exp",
    "generation_config": {
      "response_modalities": ["AUDIO"],
      "speech_config": {
        "voice_config": {
          "prebuilt_voice_config": {
            "voice_name": "Puck"
          }
        }
      }
    },
    "system_instruction": {
      "parts": [{"text": "System prompt here"}]
    }
  }
}
```

**2. Realtime Input (Client → Server)**
```json
{
  "realtime_input": {
    "media_chunks": [
      {
        "mime_type": "audio/pcm;rate=16000",
        "data": "base64_encoded_pcm_data"
      }
    ]
  }
}
```

**3. Server Content (Server → Client)**
```json
{
  "serverContent": {
    "modelTurn": {
      "parts": [
        {
          "inlineData": {
            "mimeType": "audio/pcm;rate=24000",
            "data": "base64_encoded_audio"
          }
        }
      ]
    },
    "modelTurnTranscription": {"text": "AI response text"},
    "userInputTranscription": {"text": "User input text"},
    "turnComplete": true,
    "interrupted": false
  }
}
```

## 🎤 Available Voices

You can change the voice by modifying the `VOICE_NAME` constant in `GeminiLiveService.kt`:

- **Puck** (default) - Friendly, casual
- **Charon** - Warm, professional
- **Kore** - Clear, articulate
- **Fenrir** - Deep, authoritative
- **Aoede** - Melodic, expressive

## 📱 Testing

1. Run the app
2. Grant microphone permission
3. Tap "Start Conversation"
4. Speak naturally
5. The AI will respond with voice and text
6. Watch live transcriptions update in real-time

## 🐛 Troubleshooting

### No Audio Output
- Check that `OUTPUT_SAMPLE_RATE` matches server (24kHz)
- Verify AudioTrack initialization succeeded
- Check audio queue is being populated

### No Audio Input
- Verify RECORD_AUDIO permission granted
- Check AudioRecord state is RECORDING
- Ensure microphone is not being used by another app

### WebSocket Connection Failed
- Verify API key is valid
- Check internet connection
- Ensure the model name is correct
- Check Logcat for detailed errors

### Transcriptions Not Updating
- Verify callbacks are being triggered
- Check WebSocket is connected
- Ensure UI updates on main thread

## 🔐 Security Notes

- Never commit `local.properties` with API keys
- Consider using encrypted storage for production
- Implement rate limiting to prevent API abuse
- Add authentication for production apps

## 📊 Performance Tips

1. **Buffer Sizes:** Adjust `BUFFER_SIZE_MULTIPLIER` if experiencing dropouts
2. **Queue Management:** Monitor `audioQueue.size()` - too large means latency issues
3. **Network:** Use WiFi for best experience (high bandwidth required)
4. **Memory:** Service uses ~10-20MB RAM during active streaming

## 🆚 Comparison with TypeScript Version

| Feature | TypeScript | Android |
|---------|-----------|---------|
| WebSocket | ✅ | ✅ |
| Audio Input | Web Audio API | AudioRecord |
| Audio Output | AudioContext | AudioTrack |
| Transcriptions | ✅ | ✅ |
| Turn Management | ✅ | ✅ |
| Interruptions | ✅ | ✅ |
| Sample Rate Input | 16kHz | 16kHz |
| Sample Rate Output | 24kHz | 24kHz |

## 📝 License

This implementation is part of the HeyCyan Android SDK project.

## 🤝 Support

For issues or questions:
1. Check the Logcat output (tag: `GeminiLiveService`)
2. Verify all dependencies are installed
3. Ensure API key has Gemini API access
4. Review the example `GeminiLiveActivity` implementation

---

**Created:** December 18, 2025  
**Version:** 1.0.0  
**Gemini Model:** gemini-2.0-flash-exp
