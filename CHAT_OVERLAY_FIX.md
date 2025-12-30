# Chat Overlay Fix - Placeholder Transcripts for Audio-Only Mode

## Problem
The chat overlay wasn't displaying conversation text because:
1. Gemini Live API in AUDIO-only mode doesn't send text transcriptions automatically
2. Requesting `["AUDIO", "TEXT"]` in `responseModalities` causes WebSocket error: **"Request contains an invalid argument"**

**Symptoms:**
- WebSocket closes immediately after setup with error 1007
- Logs showed: `⚠️ WebSocket closing: 1007 - Request contains an invalid argument`
- Audio conversation worked when using AUDIO-only mode, but no text was generated

## Root Cause
**Gemini Live API limitation**: When using real-time audio streaming mode, the API:
- ✅ Accepts audio input
- ✅ Returns audio output
- ❌ Does NOT automatically provide text transcriptions
- ❌ Does NOT support `["AUDIO", "TEXT"]` together in `responseModalities`

## Solution Implemented

### 1. Keep AUDIO-Only Mode
**File:** `GeminiLiveService.kt` (line 548)

```kotlin
"responseModalities" to listOf("AUDIO"),  // Must be AUDIO only!
```

### 2. Track Audio Reception
**File:** `GeminiLiveService.kt` (added flags)

```kotlin
private var receivedAudioInCurrentTurn = false
private var hasTranscriptionForCurrentTurn = false
```

### 3. Generate Placeholder Transcription
**File:** `GeminiLiveService.kt` (`handleServerContent` - turnComplete section)

**Added logic:**
```kotlin
// If we received audio but no transcription, generate a placeholder
if (receivedAudioInCurrentTurn && !hasTranscriptionForCurrentTurn) {
    currentOutputTranscription.append("[AI speaking...]")
    Log.d(TAG, "📝 Generated placeholder transcription (no text from API)")
}
```

### 4. Send Final Update
```kotlin
// Send final update with isFinal=true
callbacks.onTranscriptionUpdate(fullInput, fullOutput, true)
```

## How It Works Now

1. **User speaks** → Audio captured and sent to Gemini
2. **Gemini processes** → Returns AUDIO response only
3. **Audio received** → Flag `receivedAudioInCurrentTurn = true`
4. **Turn completes** → Check if transcription was received
5. **No transcription?** → Generate placeholder: `[AI speaking...]`
6. **Chat adapter** → Updates RecyclerView with placeholder text
7. **Audio plays** → User hears the actual response

## Expected Behavior After Fix

### What You'll See:
- ✅ **WebSocket stays connected** (no more error 1007)
- ✅ **Audio responses play** correctly
- ✅ **Chat overlay shows**: `[AI speaking...]` for each AI response
- ✅ **User messages** may appear if API sends `userInputTranscription`
- ⚠️ **Not actual transcripts**, but provides visual feedback

### Logs to Watch:
```
✅ WebSocket connected
📤 Sending setup with tools: {"setup":{"model":"models/gemini-2.0-flash-exp","generationConfig":{"responseModalities":["AUDIO"]}...
🎵 Received audio chunk: 9600 bytes
📝 Generated placeholder transcription (no text from API)
✅ Turn complete - Input: '', Output: '[AI speaking...]'
```

## Limitations

### Current Implementation:
- ❌ **No actual text transcripts** from Gemini (API limitation)
- ✅ **Placeholder text** shows when AI responds
- ❌ **Cannot display exact words** spoken by AI
- ✅ **Visual feedback** that conversation is happening

### Why This Is Necessary:
Gemini Live API with audio streaming is designed for **real-time voice interaction**, not text transcription. To get actual transcripts, you would need:
1. **Separate STT service** (e.g., Google Cloud Speech-to-Text)
2. **Hybrid approach** (record audio locally + transcribe separately)
3. **Different Gemini API** (use standard Gemini API with text mode)

## Alternative Solutions (Future)

### Option 1: Add Speech-to-Text Service
```kotlin
// Pseudocode
audioQueue.forEach { audioChunk ->
    val transcript = speechToTextService.transcribe(audioChunk)
    currentOutputTranscription.append(transcript)
}
```

### Option 2: Use Gemini Standard API for Text
Switch from Live API to regular Gemini API, send audio as file, get text response

### Option 3: Local Audio Recording + STT
Record AI audio output locally and transcribe using Android SpeechRecognizer

## Testing Steps

1. **Rebuild and install** the debug APK
2. **Start conversation** using "Hey Imi" or the mic button
3. **Speak to the AI** and observe:
   - ✅ WebSocket stays connected (check logs)
   - ✅ Audio plays (as before)
   - ✅ Chat shows `[AI speaking...]` placeholder
4. **Check logs** for successful connection and placeholder generation

## Troubleshooting

If WebSocket still fails:

1. **Check setup message** in logs - should show `"responseModalities":["AUDIO"]` (NOT `["AUDIO","TEXT"]`)
2. **API key** - verify Gemini API key is valid
3. **Network** - check internet connection
4. **Clear app data** and restart

If chat doesn't show placeholder:

1. **Check logs** for `📝 Generated placeholder transcription`
2. **Verify** `receivedAudioInCurrentTurn` is set when audio arrives
3. **Ensure** `ChatAdapter` is initialized in `GeminiLiveActivity`

## Related Files
- `GeminiLiveService.kt` - WebSocket, audio handling, placeholder generation
- `GeminiLiveActivity.kt` - Chat UI and adapter wiring
- `ChatAdapter.kt` - RecyclerView adapter for messages
- `ChatMessage.kt` - Data model for chat bubbles

---

**Build Status:** ✅ Successful (BUILD SUCCESSFUL in 10s)  
**Date:** 2025-12-20  
**Solution:** Placeholder transcripts + proper audio-only mode configuration
