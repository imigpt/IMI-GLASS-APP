# Echo Cancellation & Noise Suppression Implementation

## Overview
This document describes the software-side echo cancellation and noise suppression solution implemented in the smart glasses companion app without requiring firmware changes.

## Problem Statement
When using smart glasses with Gemini Live AI assistant:
1. **Echo Problem**: The glasses microphone picks up the AI's voice playback, creating an echo loop
2. **Ambient Noise**: Surrounding environmental sounds are captured and sent to Gemini
3. **Constraint**: No access to firmware - solution must be software-only

## Solution Architecture
Three-pronged approach to eliminate echo and reduce noise:

### 1. Platform Audio Effects (AEC/NS/AGC)
**Location**: `GeminiLiveService.kt` - `initializeAudioComponents()` method

Enabled Android's built-in audio processing on the AudioRecord session:
- **AcousticEchoCanceler**: Removes echo from AI playback
- **NoiseSuppressor**: Filters background/ambient noise
- **AutomaticGainControl**: Normalizes volume levels

```kotlin
audioRecord?.audioSessionId?.let { sessionId ->
    if (AcousticEchoCanceler.isAvailable()) {
        acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)
        acousticEchoCanceler?.enabled = true
    }
    
    if (NoiseSuppressor.isAvailable()) {
        noiseSuppressor = NoiseSuppressor.create(sessionId)
        noiseSuppressor?.enabled = true
    }
    
    if (AutomaticGainControl.isAvailable()) {
        automaticGainControl = AutomaticGainControl.create(sessionId)
        automaticGainControl?.enabled = true
    }
}
```

**Benefits**:
- Hardware-accelerated processing (if available)
- Standard Android audio pipeline integration
- No additional latency

**Fallback**: Logs warning if effects not available on device, but continues with other methods

### 2. Half-Duplex Communication
**Location**: `GeminiLiveService.kt` - Audio capture and playback loops

Implemented half-duplex mode: **pause microphone capture when AI is speaking**

**Implementation**:
- Added `isAIPlaying` AtomicBoolean flag
- Set to `true` when AI audio playback starts
- Set to `false` when audio queue is empty
- Capture loop skips sending audio chunks when `isAIPlaying == true`

```kotlin
// In audio capture loop (startAudioCapture)
while (isRecording.get()) {
    val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
    
    if (readSize > 0) {
        // Half-duplex: Skip sending audio when AI is speaking
        if (isAIPlaying.get()) {
            continue
        }
        // ... send audio to Gemini
    }
}

// In audio playback loop (startAudioPlayback)
synchronized(audioQueueLock) {
    val queueEmpty = audioQueue.isEmpty()
    if (queueEmpty) {
        isAIPlaying.set(false) // AI stopped speaking, resume mic
    } else {
        isAIPlaying.set(true) // AI is speaking, pause mic
    }
}
```

**Benefits**:
- **Complete echo elimination** - mic is OFF when AI speaks, zero chance of capturing playback
- Simple and robust
- No algorithmic complexity

**Trade-off**: User can't interrupt AI mid-sentence (must wait for AI to finish speaking)

### 3. Voice Activity Detection (VAD)
**Location**: `GeminiLiveService.kt` - Audio capture loop + `calculateRMS()` helper

Implemented energy-based VAD to filter out quiet/noise frames before sending to Gemini

**Implementation**:
- Calculate RMS (Root Mean Square) energy of each audio chunk
- Compare to threshold (500.0 - tunable)
- Skip sending chunks below threshold

```kotlin
// Calculate RMS energy
private fun calculateRMS(buffer: ShortArray, size: Int): Double {
    var sum = 0.0
    for (i in 0 until size) {
        val sample = buffer[i].toDouble()
        sum += sample * sample
    }
    return sqrt(sum / size)
}

// In capture loop
val rmsEnergy = calculateRMS(buffer, readSize)
if (rmsEnergy < ENERGY_THRESHOLD) {
    continue // Skip quiet/noise frames
}
```

**Benefits**:
- Reduces bandwidth (don't send silence)
- Prevents Gemini from processing ambient noise
- Improves transcription accuracy

**Tuning**: `ENERGY_THRESHOLD = 500.0` can be adjusted based on environment:
- **Higher value**: Requires louder speech (filters more noise, may cut off quiet speech)
- **Lower value**: More sensitive (captures more, may include background noise)

## Resource Management
All audio effects are properly released in cleanup:

```kotlin
private fun cleanup() {
    // Release audio effects
    acousticEchoCanceler?.release()
    noiseSuppressor?.release()
    automaticGainControl?.release()
    
    // ... release other resources
}
```

## Testing Recommendations
1. **Device Testing**: Test on real glasses hardware to verify:
   - AEC/NS/AGC availability on target Android version
   - Energy threshold effectiveness in real environment
   - Half-duplex behavior (can you live with not interrupting AI?)

2. **Environment Testing**:
   - Quiet room (check if VAD threshold is too high)
   - Noisy environment (verify noise suppression works)
   - AI playback volume test (ensure no echo leakage)

3. **Threshold Tuning**:
   - Start with `ENERGY_THRESHOLD = 500.0`
   - If quiet speech is cut off → reduce to 300.0-400.0
   - If too much noise → increase to 700.0-1000.0
   - Add UI setting for user adjustment if needed

## Future Enhancements (Optional)
1. **WebRTC AEC**: If platform AEC insufficient, integrate WebRTC's AEC3 library
2. **Adaptive Threshold**: Dynamically adjust VAD threshold based on ambient noise floor
3. **Push-to-talk**: Allow user to interrupt AI by detecting strong energy spike
4. **Advanced VAD**: Replace RMS with ML-based VAD (e.g., WebRTC VAD, Silero VAD)

## File Changes Summary
**Modified**: `GeminiLiveService.kt`
- Added imports: `AcousticEchoCanceler`, `NoiseSuppressor`, `AutomaticGainControl`, `kotlin.math.sqrt`
- Added constants: `ENERGY_THRESHOLD`
- Added members: `isAIPlaying`, `acousticEchoCanceler`, `noiseSuppressor`, `automaticGainControl`
- Modified `initializeAudioComponents()`: Enable audio effects
- Modified `startAudioCapture()`: Add half-duplex check and VAD
- Modified `startAudioPlayback()`: Update `isAIPlaying` flag
- Added `calculateRMS()`: RMS energy calculation for VAD
- Modified `cleanup()`: Release audio effects

## Build Status
✅ Build successful - all code compiles without errors

## Conclusion
This implementation provides a robust software-side solution to echo and noise problems without firmware changes. The combination of platform audio effects, half-duplex control, and VAD should significantly improve user experience with the AI glasses assistant.
