# Meeting Minutes Feature Implementation

## Overview
Complete voice-activated meeting transcription and summarization system with continuous speech-to-text recording and AI-powered summarization using Gemini 1.5 Flash.

## Features
- 🎤 **Voice-Activated**: Say "Start Meeting Minutes" to begin recording
- 📝 **Continuous Speech-to-Text**: Real-time transcription using Android SpeechRecognizer
- 🤖 **AI Summarization**: Gemini 1.5 Flash generates comprehensive meeting summaries
- 📊 **Meeting History**: View all past meetings with transcripts and summaries
- ⏱️ **Live Metrics**: Duration timer and word count during recording
- ▶️ **Pause/Resume**: Control recording flow
- 🔍 **Search**: Find meetings by title or content

## Architecture

### Data Model (`MeetingMinute.kt`)
```kotlin
data class MeetingMinute(
    val id: String,
    val title: String,
    val startTime: Long,
    var endTime: Long?,
    var transcript: String,
    var summary: String?,
    val participants: List<String>,
    var isActive: Boolean
)
```

**Helper Methods:**
- `getDuration()`: Returns formatted duration (e.g., "15m 30s")
- `getFormattedStartTime()`: Returns time (e.g., "2:30 PM")
- `getFormattedDate()`: Returns date (e.g., "Jan 15, 2024")
- `getWordCount()`: Returns transcript word count

### Storage Manager (`MeetingMinutesManager.kt`)
**Persistence:** SharedPreferences with Gson serialization

**Key Methods:**
- `startMeeting(title)`: Creates new active meeting
- `getActiveMeeting()`: Retrieves current recording session
- `updateActiveMeetingTranscript(text)`: Appends to live transcript
- `endMeeting(summary)`: Finalizes meeting with AI summary
- `getAllMeetings()`: Returns all meetings, newest first
- `deleteMeeting(id)`: Removes meeting record
- `searchMeetings(query)`: Fuzzy search across titles and transcripts

## UI Components

### 1. ActiveMeetingActivity (Recording Screen)
**Purpose:** Live meeting recording with continuous speech-to-text

**Features:**
- Red recording indicator (pulsing dot)
- Live duration timer (updates every 1s)
- Real-time word count
- Auto-scrolling transcript display
- Pause/Resume functionality
- End meeting button

**Speech Recognition:**
- Uses Android `SpeechRecognizer` with `PARTIAL_RESULTS`
- Automatic restart on error for continuous recording
- 100ms delay between restarts for smooth operation
- Accumulates all text in `fullTranscript` StringBuilder

**AI Summarization:**
```kotlin
private suspend fun generateMeetingSummary(transcript: String): String {
    val apiKey = "YOUR_GEMINI_API_KEY"
    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
    
    val prompt = """
    Please analyze this meeting transcript and provide a comprehensive summary:
    
    **Key Discussion Points:**
    **Decisions Made:**
    **Action Items:**
    **Important Notes:**
    
    Transcript: $transcript
    """
}
```

**Layout:** `activity_active_meeting.xml`
- Dark theme (#1A1A1A background)
- Material cards for info sections
- Full-height scrollable transcript
- Bottom action bar with Pause/End buttons

### 2. MeetingMinutesActivity (List Screen)
**Purpose:** View all meeting history and details

**Features:**
- List of all meetings with preview
- Empty state with voice command guidance
- View full transcript + summary dialog
- Delete meetings with confirmation
- FAB to start new meeting

**RecyclerView Adapter:** `MeetingMinutesAdapter`
- Shows: title, date, duration, word count, summary preview (100 chars)
- Actions: View details, Delete

**Layout:** `activity_meeting_minutes.xml`
- CoordinatorLayout with AppBar
- Empty state (🎤 icon + instructions)
- RecyclerView for meeting list
- FAB with mic icon

### 3. Item Layout (`item_meeting_minute.xml`)
```xml
CardView
├── Title (bold, 18sp)
├── Date + Duration row
├── Summary preview (gray, truncated)
├── Word count badge (e.g., "1,234 words")
└── Action buttons (View | Delete)
```

## AI Integration

### 1. Tool Declaration (`GeminiLiveService.kt`)
```kotlin
mapOf(
    "type" to "function",
    "name" to "start_meeting",
    "description" to "Start meeting minutes recording with speech-to-text transcription",
    "parameters" to mapOf(
        "type" to "object",
        "properties" to mapOf(
            "title" to mapOf(
                "type" to "string",
                "description" to "Optional meeting title, auto-generated if not provided"
            )
        )
    )
)
```

### 2. System Instruction
```
MEETING MINUTES: When the user asks to "start meeting minutes", "record this meeting", 
"start recording the meeting", or similar, use the start_meeting tool to begin recording.
```

### 3. Tool Handler (`MainActivity.kt`)
```kotlin
"start_meeting" -> {
    runOnUiThread {
        startMeetingMinutes()
        Toast.makeText(this, "🎤 Starting meeting recording...", Toast.LENGTH_SHORT).show()
    }
    "Meeting recording started"
}
```

## User Flows

### Flow 1: Voice-Activated Meeting Start
1. User says: **"Hey IMI, start meeting minutes"**
2. AI recognizes intent → calls `start_meeting` tool
3. MainActivity launches `ActiveMeetingActivity`
4. Prompt for meeting title → auto-generated if skipped
5. Recording starts with live transcription

### Flow 2: Manual Meeting Start
1. User taps **Meeting Minutes** card on home screen
2. `MeetingMinutesActivity` opens (list view)
3. User taps FAB (mic button)
4. Enter meeting title (optional)
5. `ActiveMeetingActivity` launches
6. Recording starts

### Flow 3: During Recording
1. Speech continuously transcribed → live display
2. Duration and word count update in real-time
3. User can pause/resume as needed
4. Tap **End Meeting** when done

### Flow 4: Meeting Completion
1. Recording stops
2. Full transcript sent to Gemini 1.5 Flash
3. AI generates structured summary:
   - Key Discussion Points
   - Decisions Made
   - Action Items
   - Important Notes
4. Meeting saved to history
5. User redirected to list view

### Flow 5: Viewing Past Meetings
1. Open Meeting Minutes from home
2. Browse list (sorted newest first)
3. Tap meeting → see full details dialog
4. View complete transcript + AI summary
5. Delete if no longer needed

## Technical Implementation Details

### Continuous Speech Recognition
```kotlin
override fun onResults(results: Bundle?) {
    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
    val spokenText = matches?.firstOrNull() ?: return
    
    fullTranscript.append(" ").append(spokenText)
    updateUI()
    
    // Auto-restart for continuous recording
    handler.postDelayed({
        if (isRecording) startListening()
    }, 100)
}

override fun onError(errorCode: Int) {
    // Restart on any error for seamless recording
    handler.postDelayed({
        if (isRecording) startListening()
    }, 100)
}
```

### Permission Handling
```kotlin
private fun checkAudioPermission() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
        != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
    }
}
```

### AI Summarization API Call
```kotlin
private suspend fun generateMeetingSummary(transcript: String): String = withContext(Dispatchers.IO) {
    val client = OkHttpClient()
    val requestBody = JSONObject().apply {
        put("contents", JSONArray().apply {
            put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", prompt))
                })
            })
        })
    }
    
    val request = Request.Builder()
        .url(url)
        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
        .build()
    
    val response = client.newCall(request).execute()
    // Parse and return summary
}
```

## Files Modified

### New Files Created
1. `MeetingMinute.kt` - Data model
2. `MeetingMinutesManager.kt` - Storage and business logic
3. `ActiveMeetingActivity.kt` - Recording screen
4. `MeetingMinutesActivity.kt` - List screen
5. `MeetingMinutesAdapter.kt` - RecyclerView adapter
6. `activity_active_meeting.xml` - Recording layout
7. `activity_meeting_minutes.xml` - List layout
8. `item_meeting_minute.xml` - List item layout
9. `recording_indicator.xml` - Red dot drawable

### Modified Files
1. `MainActivity.kt`
   - Added `meetingManager: MeetingMinutesManager`
   - Added `openMeetingMinutes()` method
   - Added `startMeetingMinutes()` method
   - Added button click handler
   - Added `start_meeting` tool handler

2. `GeminiLiveService.kt`
   - Added `start_meeting` tool declaration
   - Updated system instruction with meeting minutes guidance

3. `activity_main.xml`
   - Added Meeting Minutes card (🎤 icon, button id: `btnMeetingMinutes`)

4. `AndroidManifest.xml`
   - Registered `MeetingMinutesActivity`
   - Registered `ActiveMeetingActivity`

## Voice Commands Supported
- "Start meeting minutes"
- "Record this meeting"
- "Start recording the meeting"
- "Meeting minutes shuru karo" (Hindi/Hinglish)
- "Begin meeting transcription"

## Configuration

### API Key Setup
Update `ActiveMeetingActivity.kt` line ~XXX:
```kotlin
private val GEMINI_API_KEY = "YOUR_GEMINI_API_KEY_HERE"
```

### Customization Options
- **Auto-restart delay**: Adjust `100ms` in `onResults()` and `onError()`
- **Summary prompt**: Modify prompt in `generateMeetingSummary()`
- **UI colors**: Update colors in XML layouts
- **Word count threshold**: Add custom logic for long meetings

## Testing Checklist
- [ ] Voice command "start meeting minutes" triggers recording
- [ ] Continuous speech transcription works without gaps
- [ ] Pause/Resume functionality works correctly
- [ ] Duration timer updates accurately
- [ ] Word count reflects transcript length
- [ ] End meeting generates AI summary
- [ ] Meeting saved to history correctly
- [ ] List view shows all meetings
- [ ] View details displays full transcript + summary
- [ ] Delete meeting works with confirmation
- [ ] Search finds meetings by title/content
- [ ] Home button opens list view
- [ ] FAB starts new meeting
- [ ] RECORD_AUDIO permission requested and handled

## Known Limitations
1. Speech recognizer may have brief gaps between continuous listening cycles
2. Requires active internet for AI summarization
3. Long meetings (>1 hour) may hit transcript length limits
4. Summary quality depends on transcript clarity

## Future Enhancements
- [ ] Export meetings to PDF/TXT
- [ ] Share meeting summaries
- [ ] Speaker identification
- [ ] Live editing of transcript
- [ ] Custom summary templates
- [ ] Meeting tags/categories
- [ ] Calendar integration
- [ ] Multi-language support
- [ ] Offline mode with deferred summarization

## Build & Deploy
```bash
# Build debug APK
./gradlew assembleDebug

# Install on device
adb install app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat -s MainActivity ActiveMeetingActivity MeetingMinutesActivity
```

## Support
For issues or questions, refer to:
- [IMPLEMENTATION_SUMMARY.md](IMPLEMENTATION_SUMMARY.md)
- [QUICK_START_GEMINI_LIVE.md](QUICK_START_GEMINI_LIVE.md)
