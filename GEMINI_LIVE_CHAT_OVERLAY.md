# Gemini Live Audio Chat with Overlay UI

## Overview
This implementation adds a **real-time chat overlay** to the Gemini Live audio conversation, showing what's being said through audio in a messaging-style interface while maintaining full-duplex audio streaming.

## Features ✨
- **Live Audio Streaming**: Real-time bidirectional audio with Gemini 2.0 Flash
- **Chat Overlay**: WhatsApp-style message bubbles showing conversation in real-time
- **Streaming Transcripts**: Partial (streaming) and final transcripts update live
- **Visual Differentiation**: User messages (blue, right-aligned) vs AI messages (green, left-aligned)
- **Compact Transcript View**: Small info panel showing current partial transcripts
- **Auto-scroll**: Chat automatically scrolls to latest message

## Architecture

### Components Created
1. **ChatMessage.kt** - Data class for chat messages
   - Fields: `text`, `isFromUser`, `isFinal`, `timestamp`, `id`
   - Supports partial/streaming updates

2. **ChatAdapter.kt** - RecyclerView adapter for messages
   - Methods:
     - `addMessage()` - Add new message
     - `updateLastMessage()` - Update streaming message
     - `clearMessages()` - Reset on new conversation
   - Visual styling based on sender (user/AI)
   - Transparency for partial messages (70% opacity)

3. **item_chat_message.xml** - Message bubble layout
   - CardView with rounded corners
   - Supports left/right alignment
   - Max width 280dp for readability

4. **Updated activity_gemini_live.xml** - Main layout
   - RecyclerView for chat (takes most space)
   - Collapsible compact transcript panel
   - Connection status and controls at top

5. **Updated GeminiLiveActivity.kt** - Activity logic
   - Initializes RecyclerView with LinearLayoutManager
   - Hooks `onTranscriptionUpdate()` callback to update chat
   - Smart logic: updates last message if streaming, adds new if turn changes

## How It Works 🔧

### Flow
1. User speaks → Audio captured via AudioRecord
2. Audio streamed to Gemini via WebSocket
3. Gemini returns:
   - `userInputTranscription` (partial → final)
   - Audio chunks (TTS)
   - `modelTurnTranscription` (partial → final)
4. GeminiLiveService calls `onTranscriptionUpdate(input, output, isFinal)`
5. Activity updates chat:
   - If partial: updates last message (same sender)
   - If new turn: adds new message bubble
   - Auto-scrolls to bottom

### Streaming Logic
```kotlin
// In onTranscriptionUpdate()
if (input.isNotEmpty()) {
    val updated = chatAdapter.updateLastMessage(input, isFinal, isFromUser = true)
    if (!updated) {
        // New message
        chatAdapter.addMessage(ChatMessage(input, isFromUser = true, isFinal))
    }
}
// Same for AI output
```

## Usage 📱

### Running the App
1. Open project in Android Studio
2. Build and run on device/emulator with microphone
3. Grant RECORD_AUDIO permission when prompted
4. Tap **Start Conversation**
5. Speak naturally — your words appear in chat (blue bubbles)
6. AI responds with audio + text appears (green bubbles)
7. Tap **Stop Conversation** when done

### Expected Behavior
- **While User Speaks**: 
  - Partial transcripts update in blue bubble (70% opacity)
  - Final transcript becomes solid (100% opacity)
- **While AI Speaks**:
  - Audio plays from speakers
  - Partial AI text updates in green bubble
  - Final text becomes solid
- **Compact Panel**: Shows current partial transcripts at bottom
- **Auto-scroll**: RecyclerView scrolls to latest message

## Testing Checklist ✅
- [ ] Start conversation successfully
- [ ] User speech appears in blue bubbles (right side)
- [ ] AI responses appear in green bubbles (left side)
- [ ] Partial messages show 70% opacity
- [ ] Final messages show 100% opacity
- [ ] Chat auto-scrolls to bottom
- [ ] Compact transcript panel updates
- [ ] Audio plays correctly during AI speech
- [ ] Stop conversation clears chat

## Troubleshooting 🔧

### Messages not appearing in chat
- Check logcat for "Transcription - User:" and "Transcription - AI:"
- Ensure `onTranscriptionUpdate()` is being called
- Verify RecyclerView is visible in layout inspector

### Chat not auto-scrolling
- Ensure `recyclerChat.scrollToPosition(chatMessages.size - 1)` is called
- Check if LayoutManager is LinearLayoutManager

### Visual styling issues
- Verify CardView colors in ChatAdapter
- Check if `item_chat_message.xml` is inflated correctly
- Inspect view hierarchy in Layout Inspector

### Performance issues (lag)
- Reduce frequency of partial updates (debounce in GeminiLiveService)
- Use DiffUtil instead of notifyDataSetChanged()
- Limit max messages in chatMessages list (trim old messages)

## Optimization Tips 🚀

### Reduce UI Churn
In GeminiLiveService.kt, add debouncing:
```kotlin
private var lastTranscriptUpdate = 0L
private const val UPDATE_THROTTLE_MS = 100

// In handleServerContent()
val now = System.currentTimeMillis()
if (now - lastTranscriptUpdate > UPDATE_THROTTLE_MS) {
    callbacks.onTranscriptionUpdate(...)
    lastTranscriptUpdate = now
}
```

### Limit Message History
In GeminiLiveActivity.kt:
```kotlin
private const val MAX_MESSAGES = 50

fun addMessage(message: ChatMessage) {
    if (chatMessages.size >= MAX_MESSAGES) {
        chatMessages.removeAt(0)
        chatAdapter.notifyItemRemoved(0)
    }
    chatMessages.add(message)
    chatAdapter.notifyItemInserted(chatMessages.size - 1)
}
```

### Use DiffUtil for Smooth Updates
Create `ChatDiffCallback` and use `submitList()` with ListAdapter instead of RecyclerView.Adapter.

## API Reference 📚

### ChatMessage
```kotlin
data class ChatMessage(
    var text: String,
    val isFromUser: Boolean,
    var isFinal: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
```

### ChatAdapter
```kotlin
fun addMessage(message: ChatMessage)
fun updateLastMessage(text: String, isFinal: Boolean, isFromUser: Boolean): Boolean
fun clearMessages()
```

### GeminiLiveService.GeminiLiveCallbacks
```kotlin
fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean)
fun onTurnComplete(fullInput: String, fullOutput: String)
fun onAudioPlaybackStart()
fun onAudioPlaybackEnd()
fun onError(error: String)
fun onConnectionStatusChanged(isConnected: Boolean)
```

## Next Steps 🎯
1. **Test on real device** with Bluetooth glasses
2. **Add message timestamps** in UI (optional)
3. **Persist conversation history** to database
4. **Export chat** to text/PDF
5. **Add voice activity indicator** (waveform animation)
6. **Support multiple languages** with language detection

## Known Issues 🐛
- First message may take 2-4 seconds to appear (Gemini cold start)
- Very fast speech may cause partial updates to be skipped
- No message persistence across app restarts

## Credits
- Built on Gemini 2.0 Flash Live API
- Uses OkHttp WebSocket for bidirectional streaming
- Android AudioRecord/AudioTrack for audio I/O

---
**Version**: 1.0  
**Last Updated**: December 2025  
**Author**: GitHub Copilot
