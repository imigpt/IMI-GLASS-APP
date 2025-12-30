# How "Hey Imi" Wake Word Detection Works

## 🎯 Complete Workflow Overview

### 1️⃣ **Wake Word Detection** (Porcupine)

```
User says "Hey Imi" → Porcupine detects → EventBus posts "wake up"
```

**Location:** [HotHelper.kt](d:/office%20work/imitext1.o/HeyCyan_Android_SDK_1.0.2_20250816%20(1)/HeyCyan_Android_SDK_1.0.2_20250816/GlassesSDKSample/GlassesSDKSample/app/src/main/java/com/sdk/glassessdksample/ui/HotHelper.kt#L113-L127)

```kotlin
// In HotHelper.kt - initPorcupine()
porcupineManager = builder.build(context) { keywordIndex ->
    Log.i(TAG, "🔥 Porcupine detected keyword index=$keywordIndex")
    
    // 1. STOP Porcupine immediately to release microphone
    porcupineManager?.stop()
    porcupineManager?.delete()
    porcupineManager = null
    isStarted = false
    Log.d(TAG, "✅ Porcupine stopped - microphone released for SpeechRecognizer")
    
    // 2. POST wake word event via EventBus
    EventBus.getDefault().post(BluetoothEvent(BluetoothEvent.EventType.VOICE_TEXT, "wake up"))
}
```

**Key Points:**
- Uses **Picovoice Porcupine** for wake word detection
- Listens on **phone microphone** (not glass microphone)
- Custom `.ppn` keyword file for "Hey Imi"
- Immediately releases microphone after detection
- Posts event to EventBus

---

### 2️⃣ **Event Handling** (MainActivity)

```
EventBus → MainActivity.onBluetoothEvent() → Process "wake up"
```

**Location:** [MainActivity.kt](d:/office%20work/imitext1.o/HeyCyan_Android_SDK_1.0.2_20250816%20(1)/HeyCyan_Android_SDK_1.0.2_20250816/GlassesSDKSample/GlassesSDKSample/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt#L871-L901)

```kotlin
@Subscribe(threadMode = ThreadMode.MAIN)
fun onBluetoothEvent(event: BluetoothEvent) {
    when (event.type) {
        BluetoothEvent.EventType.VOICE_TEXT -> {
            val text = event.data as? String ?: return
            if (text == "wake up") {
                // 1. STOP HotHelper (wake word detection)
                HotHelper.getInstance(this).stop()
                
                // 2. ACTIVATE conversation mode
                isInConversationMode = true
                updateConversation("System", "Hey Imi detected!")

                // 3. IMMEDIATE ACKNOWLEDGMENT (Fast Fix)
                val fastReply = "Yes Sir" 
                updateConversation("Imi", fastReply)
                
                // 4. SPEAK acknowledgment on glass
                speakOnGlass(fastReply, "WAKE_ACK")
                
                // 5. ENABLE voice command button
                voiceCommandEnabled = true
                binding.btnVoice.isEnabled = true
            }
        }
    }
}
```

**What Happens:**
- ✅ **Stop wake word detection** (HotHelper.stop())
- ✅ **Enter conversation mode** (isInConversationMode = true)
- ✅ **Immediate acknowledgment** - "Yes Sir" (no AI delay!)
- ✅ **Speak on glass** via TTS
- ✅ **Enable voice commands**

---

### 3️⃣ **Acknowledgment Speech** (TTS to Glass)

```
"Yes Sir" → speakOnGlass() → Route to Glass speaker
```

**Location:** [MainActivity.kt](d:/office%20work/imitext1.o/HeyCyan_Android_SDK_1.0.2_20250816%20(1)/HeyCyan_Android_SDK_1.0.2_20250816/GlassesSDKSample/GlassesSDKSample/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt#L377-L408)

```kotlin
private fun speakOnGlass(text: String, utteranceId: String) {
    if (!glassStreamingAvailable) {
        // Display text on glass screen
        val displayCmd = GlassesControl.setTextDisplay(
            text, 
            GlassesControl.DisplayMode.DEFAULT, 
            GlassesControl.Align.CENTER
        )
        LargeDataHandler.getInstance().glassesControl(displayCmd)
        
        // Speak on phone speaker
        speakOut(text, utteranceId)
    } else {
        // Route TTS to Bluetooth SCO (glass speaker)
        routeTtsToBluetoothAndSpeak(text, utteranceId)
    }
}
```

**Audio Routing Options:**
1. **Bluetooth SCO** - Direct Bluetooth audio to glass speaker
2. **PCM Streaming** - Send TTS as PCM chunks via BLE
3. **Fallback** - Phone speaker if glass audio unavailable

---

### 4️⃣ **After Acknowledgment** (TTS Callback)

```
TTS finishes → onDone() → Start listening for user command
```

**Location:** [MainActivity.kt](d:/office%20work/imitext1.o/HeyCyan_Android_SDK_1.0.2_20250816%20(1)/HeyCyan_Android_SDK_1.0.2_20250816/GlassesSDKSample/GlassesSDKSample/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt#L335-L350)

```kotlin
// TTS UtteranceProgressListener
override fun onDone(utteranceId: String?) {
    Log.d(TAG, "TTS onDone: $utteranceId")
    
    // After wake acknowledgment, start listening for user command
    if (utteranceId == "WAKE_ACK" && isInConversationMode) {
        mainScope.launch {
            // Wait for SCO to release, then start speech recognition
            waitForScoReleaseThenStartListening()
        }
    }
    
    currentTtsJob?.complete(Unit)
    currentTtsJob = null
}
```

**What Happens:**
- ✅ TTS completes speaking "Yes Sir"
- ✅ Wait for Bluetooth SCO to release
- ✅ **Start listening** for user's voice command
- ✅ Activate SpeechRecognizer

---

### 5️⃣ **Voice Recognition** (User Command)

```
User speaks → SpeechRecognizer → onResults() → Process command
```

**Location:** [MainActivity.kt](d:/office%20work/imitext1.o/HeyCyan_Android_SDK_1.0.2_20250816%20(1)/HeyCyan_Android_SDK_1.0.2_20250816/GlassesSDKSample/GlassesSDKSample/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt#L625-L660)

```kotlin
override fun onResults(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    val userText = matches?.firstOrNull() ?: return
    
    Log.d(TAG, "🗣️ User said: '$userText'")
    isListening = false
    noMatchRetryCount = 0 // Reset retry counter
    
    // Update conversation UI
    updateConversation("You", userText)
    
    // Process the command
    mainScope.launch {
        try {
            // Send to Gemini AI for response
            val aiResponse = geminiClient?.chat(userText, aiHistory) ?: "Sorry, no response"
            
            // Update UI with AI response
            updateConversation("Imi", aiResponse)
            
            // Speak AI response on glass
            speakOnGlass(aiResponse, "AI_RESPONSE")
            
            // Save to conversation history
            aiHistory.add(Pair(userText, aiResponse))
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing command: ${e.message}")
        }
    }
}
```

**What Happens:**
- ✅ Speech-to-text conversion
- ✅ Send to **Gemini AI** for response
- ✅ Display response on glass screen
- ✅ Speak response via TTS
- ✅ Save to conversation history

---

## 🔄 Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. IDLE STATE                                                   │
│    - HotHelper listening on phone mic                           │
│    - Waiting for "Hey Imi"                                      │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     │ User says "Hey Imi"
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ 2. WAKE WORD DETECTED (Porcupine)                              │
│    - Stop HotHelper                                             │
│    - Post EventBus: VOICE_TEXT("wake up")                       │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ 3. EVENT HANDLER (MainActivity)                                 │
│    - isInConversationMode = true                                │
│    - Show "Yes Sir" acknowledgment                              │
│    - Enable voice commands                                      │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ 4. TTS ACKNOWLEDGMENT                                           │
│    - Speak "Yes Sir" on glass speaker                           │
│    - Display text on glass screen                               │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     │ onDone(utteranceId="WAKE_ACK")
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ 5. START LISTENING (SpeechRecognizer)                           │
│    - Wait for Bluetooth SCO release                             │
│    - Start Google SpeechRecognizer                              │
│    - Waiting for user command...                                │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     │ User speaks command
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ 6. PROCESS COMMAND                                              │
│    - Convert speech to text                                     │
│    - Send to Gemini AI                                          │
│    - Get AI response                                            │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ 7. AI RESPONSE                                                  │
│    - Display on glass screen                                    │
│    - Speak via TTS                                              │
│    - Save to conversation history                               │
└────────────────────┬────────────────────────────────────────────┘
                     │
                     │ onDone(utteranceId="AI_RESPONSE")
                     ▼
┌─────────────────────────────────────────────────────────────────┐
│ 8. CONTINUE CONVERSATION                                        │
│    - Start listening again (if in conversation mode)            │
│    - OR restart wake word detection (if user silent)            │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🎤 Audio Components

### Porcupine Wake Word Detection
- **Library:** Picovoice Porcupine 3.0.0
- **Input:** Phone microphone
- **Sample Rate:** 16kHz
- **Sensitivity:** 0.65
- **Keyword File:** Custom `.ppn` file (Hey-Imi)

### SpeechRecognizer (Google)
- **Purpose:** Convert user speech to text
- **Language:** Auto-detect or configured
- **Partial Results:** Enabled for real-time transcription

### TextToSpeech (Android TTS)
- **Output:** Glass speaker via Bluetooth SCO or PCM streaming
- **Language:** English (default)
- **Engine:** Google TTS or system default

---

## 🔧 Key Configuration

### Wake Word Settings
```kotlin
// In HotHelper.kt
private const val PORCUPINE_FRAME_LENGTH = 512
sensitivity = 0.65f // Adjust for detection accuracy
```

### Conversation Mode
```kotlin
// In MainActivity.kt
private var isInConversationMode = false // Set to true after wake word
private val aiHistory = mutableListOf<Pair<String, String>>() // Conversation context
```

### Audio Routing
```kotlin
// In MainActivity.kt
private var glassStreamingAvailable = false // Auto-detected
private fun routeTtsToBluetoothAndSpeak() // Bluetooth SCO
private fun streamPcmAudioToGlass() // PCM chunks via BLE
```

---

## 🚀 Integration with Gemini Live

To use the new **Gemini Live API** instead of the current REST API:

```kotlin
// In MainActivity.onCreate()
private lateinit var geminiLiveService: GeminiLiveService

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Initialize Gemini Live Service
    geminiLiveService = GeminiLiveService(object : GeminiLiveService.GeminiLiveCallbacks {
        override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
            // Real-time transcription
            updateConversation("You", input)
            updateConversation("Imi", output)
        }
        
        override fun onTurnComplete(fullInput: String, fullOutput: String) {
            aiHistory.add(Pair(fullInput, fullOutput))
        }
        
        override fun onAudioPlaybackStart() {
            // Pause wake word detection while AI speaks
            HotHelper.getInstance(this@MainActivity).stop()
        }
        
        override fun onAudioPlaybackEnd() {
            // Resume listening or restart wake word
        }
        
        override fun onError(error: String) {
            Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
        }
        
        override fun onConnectionStatusChanged(isConnected: Boolean) {
            // Update UI
        }
    })
}

// After "Hey Imi" is detected
if (text == "wake up") {
    HotHelper.getInstance(this).stop()
    
    // Option 1: Start Gemini Live (bidirectional audio)
    geminiLiveService.startLiveConversation("You are Imi, a helpful AI assistant")
    
    // Option 2: Keep existing flow (speech recognition + text-based AI)
    // speakOnGlass("Yes Sir", "WAKE_ACK")
}
```

---

## 📊 Performance Metrics

| Stage | Latency | Notes |
|-------|---------|-------|
| Wake Word Detection | ~200-500ms | Porcupine processing |
| EventBus Dispatch | <10ms | Instant |
| TTS Acknowledgment | ~500-800ms | "Yes Sir" synthesis |
| Speech Recognition Start | ~1-2s | Wait for Bluetooth SCO |
| Speech-to-Text | ~500ms-2s | Google SpeechRecognizer |
| Gemini AI Response | ~1-3s | Network dependent |
| TTS Response | ~1-2s | Response synthesis |
| **Total (Wake → Response)** | **~4-10s** | Typical flow |

---

## 🐛 Common Issues & Solutions

### Issue: Wake word not detected
**Cause:** Low microphone sensitivity or background noise  
**Solution:** Adjust Porcupine sensitivity in HotHelper.kt

### Issue: "Yes Sir" not playing
**Cause:** Bluetooth SCO not connected  
**Solution:** Check `glassStreamingAvailable` flag, fallback to phone speaker

### Issue: Speech recognition fails (ERROR_AUDIO)
**Cause:** Microphone still in use by Porcupine  
**Solution:** Ensure `HotHelper.stop()` is called before `startListening()`

### Issue: Conversation mode stuck
**Cause:** Error in speech recognition loop  
**Solution:** Check `isInConversationMode` flag, implement timeout

---

## 🎯 Next Steps

1. **Test wake word detection** - Say "Hey Imi" and verify EventBus triggers
2. **Check TTS acknowledgment** - Confirm "Yes Sir" plays on glass
3. **Test voice commands** - Speak after acknowledgment and verify Gemini response
4. **Integrate Gemini Live** - Replace text-based API with bidirectional audio streaming
5. **Optimize latency** - Reduce delays between wake word and response

---

**Documentation Created:** December 18, 2025  
**Related Files:**
- [HotHelper.kt](d:/office%20work/imitext1.o/HeyCyan_Android_SDK_1.0.2_20250816%20(1)/HeyCyan_Android_SDK_1.0.2_20250816/GlassesSDKSample/GlassesSDKSample/app/src/main/java/com/sdk/glassessdksample/ui/HotHelper.kt)
- [MainActivity.kt](d:/office%20work/imitext1.o/HeyCyan_Android_SDK_1.0.2_20250816%20(1)/HeyCyan_Android_SDK_1.0.2_20250816/GlassesSDKSample/GlassesSDKSample/app/src/main/java/com/sdk/glassessdksample/MainActivity.kt)
- [GeminiLiveService.kt](d:/office%20work/imitext1.o/HeyCyan_Android_SDK_1.0.2_20250816%20(1)/HeyCyan_Android_SDK_1.0.2_20250816/GlassesSDKSample/GlassesSDKSample/app/src/main/java/com/sdk/glassessdksample/ui/GeminiLiveService.kt)
