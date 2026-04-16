# Speaker Diarization Implementation

## Overview
Meeting Minutes now includes **automatic speaker detection and identification** using Google Cloud Speech-to-Text API with diarization enabled.

## Features Implemented

### 1. **Speaker Detection**
- Automatically identifies different speakers in meeting recordings
- Supports 1-10 speakers per meeting
- No pre-training or voice profiles required

### 2. **Speaker Labeling**
- Speakers are labeled as "Speaker 1", "Speaker 2", etc.
- Transcript shows: `Speaker 1: text... Speaker 2: text...`
- Labels are consistent throughout the meeting

### 3. **Speaker Statistics**
- Total speaker count displayed
- Individual contribution percentages
- Word count per speaker
- Sorted by most active speaker

### 4. **UI Enhancements**
- 👥 Speaker count shown in meeting cards
- Speaker breakdown in meeting details
- Percentage contribution per speaker
- Speaker-separated transcript view

## Technical Implementation

### API Used
**Google Cloud Speech-to-Text API**
- Endpoint: `https://speech.googleapis.com/v1/speech:recognize`
- Model: `latest_long` (optimized for long recordings)
- Feature: `enableSpeakerDiarization: true`

### Configuration
```json
{
  "config": {
    "encoding": "AMR_WB",
    "sampleRateHertz": 16000,
    "languageCode": "en-US",
    "enableAutomaticPunctuation": true,
    "diarizationConfig": {
      "enableSpeakerDiarization": true,
      "minSpeakerCount": 1,
      "maxSpeakerCount": 10
    },
    "model": "latest_long"
  },
  "audio": {
    "content": "base64_encoded_audio"
  }
}
```

### Data Model
**MeetingMinute** now includes:
- `speakerCount: Int` - Number of unique speakers detected
- `speakerTranscript: String` - Transcript with speaker labels
- `getSpeakerStats(): Map<Int, Int>` - Speaker number to word count mapping

### Files Modified
1. **MeetingMinute.kt** - Added speaker fields and stats function
2. **ActiveMeetingActivity.kt** - Implemented Speech-to-Text with diarization
3. **MeetingMinutesActivity.kt** - Updated dialog to show speaker info
4. **MeetingMinutesAdapter.kt** - Added speaker count to card display

## Usage

### Recording a Meeting
1. Start meeting: "Start meeting minutes for Team Standup"
2. Recording shows: "Recording audio with speaker detection..."
3. Multiple speakers talk naturally
4. End meeting

### Viewing Results
Meeting displays:
- **Header**: "📝 450 words | 👥 3 speakers"
- **Speaker Breakdown**:
  - Speaker 1: 45%
  - Speaker 2: 35%
  - Speaker 3: 20%
- **Transcript**: Speaker-separated conversation

### Example Output
```
Speaker 1: Let's discuss the project timeline and milestones.

Speaker 2: I agree, we should focus on the Q2 deliverables first.

Speaker 1: Good point. What about the resource allocation?

Speaker 3: I can help with that. We have three developers available.
```

## Fallback Mechanism
If Google Speech-to-Text API fails, system automatically falls back to:
- **Gemini AI transcription** (no speaker diarization)
- Still generates transcript and summary
- Shows warning: speaker detection unavailable

## Requirements
- Google Cloud Project with Speech-to-Text API enabled
- Same API key used for Gemini API
- Internet connection during transcription

## Future Enhancements (Possible)
- [ ] Custom speaker names (rename "Speaker 1" to "John")
- [ ] Voice profile training for automatic identification
- [ ] Speaker emotion/sentiment analysis
- [ ] Distance estimation (requires multi-mic hardware)
- [ ] Real-time speaker detection during recording

## Notes
- **Language**: Currently set to English (en-US), can be changed
- **Accuracy**: Depends on audio quality and speaker distinctiveness
- **Processing Time**: ~10-30 seconds for 5-minute meeting
- **Cost**: Uses Google Cloud Speech-to-Text API (check pricing)
