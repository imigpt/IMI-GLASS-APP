# Real-time Voice AI Assistant

Production-ready Python script for full-duplex voice conversations with OpenAI's GPT-4o Realtime model.

## Features

✅ **Real-time streaming** - Full duplex audio (speak and listen simultaneously)  
✅ **Native audio handling** - Model processes audio directly (no STT/TTS middleware)  
✅ **Low latency** - Immediate response playback as audio streams in  
✅ **Auto reconnection** - Automatically reconnects if connection drops  
✅ **Voice activity detection** - Detects when user is speaking  
✅ **Clean shutdown** - CTRL+C gracefully stops all threads  
✅ **Production-ready** - Modular architecture, error handling, threading  
✅ **Minimal resources** - Suitable for Raspberry Pi and low-end systems  

## Requirements

- Python 3.8+
- OpenAI API key with access to Realtime API
- Microphone and speakers
- Internet connection

## Installation

```bash
# Install dependencies
pip install -r requirements.txt

# Or install manually
pip install websocket-client sounddevice numpy
```

## Usage

```bash
python realtime_voice_ai.py
```

You'll be prompted for:
1. **OpenAI API key** - Your API key from OpenAI
2. **Model name** - Default: `gpt-4o-realtime-preview`
3. **Debug mode** - Optional verbose logging

Then simply speak into your microphone and have a natural conversation!

## How It Works

```
┌──────────────┐
│  Microphone  │
└──────┬───────┘
       │ PCM 16-bit, 16kHz
       ▼
┌────────────────────┐
│ RealtimeAudioStreamer│
└──────┬─────────────┘
       │ Base64 encoded chunks
       ▼
┌──────────────────────┐
│ OpenAIRealtimeClient │ ◄─► WebSocket to OpenAI
└──────┬───────────────┘      wss://api.openai.com/v1/realtime
       │ Base64 audio response
       ▼
┌──────────────┐
│ AudioPlayer  │
└──────┬───────┘
       │ Decoded PCM audio
       ▼
┌──────────────┐
│   Speakers   │
└──────────────┘
```

## Architecture

### Classes

- **`AudioConfig`** - Audio parameters (sample rate, channels, format)
- **`RealtimeConfig`** - OpenAI API configuration
- **`AudioPlayer`** - Streams and plays AI audio responses in real-time
- **`RealtimeAudioStreamer`** - Captures microphone input continuously
- **`OpenAIRealtimeClient`** - WebSocket connection and message handling
- **`VoiceAssistant`** - Main orchestrator

### Threading Model

- **Main thread** - Status updates and user interaction
- **WebSocket thread** - Maintains connection and receives messages
- **Send thread** - Streams microphone audio to API
- **Playback thread** - Plays received audio chunks

## Audio Specifications

- **Format**: PCM 16-bit signed integer
- **Input sample rate**: 16,000 Hz (microphone)
- **Output sample rate**: 24,000 Hz (AI audio - OpenAI default)
- **Channels**: 1 (mono)
- **Chunk size**: 1024 samples
- **Encoding**: Base64 for transmission

**Note**: OpenAI's Realtime API outputs audio at 24kHz for better quality. The script automatically handles the different sample rates for input (16kHz) and output (24kHz).

## Configuration Options

Edit these in the script if needed:

```python
# Audio settings
input_sample_rate: int = 16000   # Hz - Microphone capture
output_sample_rate: int = 24000  # Hz - AI audio playback (OpenAI default)
channels: int = 1  # Mono
chunk_size: int = 1024  # Samples per chunk

# Voice activity detection threshold
volume_threshold = 100  # Adjust for sensitivity

# Reconnection settings
reconnect_delay = 2  # Seconds
```

## Keyboard Controls

- **CTRL+C** - Graceful shutdown

## Terminal Indicators

- 🎤 **User speaking...** - Voice detected from microphone
- 🔇 **User stopped speaking** - Silence detected
- 🔊 **AI speaking...** - Playing AI audio response
- 🔇 **AI finished speaking** - Response playback complete
- ⏱️ **Session duration** - Shown every 30 seconds
- ✅/❌ - Connection and error status

## Troubleshooting

### No audio device found
```bash
# Check available devices
python -c "import sounddevice as sd; print(sd.query_devices())"
```

### Connection errors
- Verify your API key has Realtime API access
- Check internet connection
- Ensure firewall allows WebSocket connections

### Audio quality issues
- Adjust `chunk_size` (larger = more latency, smoother)
- Check microphone input level in system settings
- Reduce background noise

### AI voice sounds slow/deep
- Fixed by using 24kHz output sample rate (default in script)
- OpenAI sends audio at 24kHz, must play at same rate

### AI voice sounds fast/high-pitched
- Output sample rate is too high
- Verify `output_sample_rate: int = 24000` in AudioConfig

### High CPU usage
- Increase `chunk_size` to reduce processing frequency
- Disable debug mode
- Close other applications

## Debug Mode

Enable debug mode for detailed logging:

```bash
python realtime_voice_ai.py
# When prompted, enter 'y' for debug mode
```

Shows:
- WebSocket event types
- Audio buffer states
- Thread operations
- Error stack traces

## API Limits

- Check OpenAI's pricing for Realtime API usage
- Audio streaming is metered per audio second
- Consider implementing usage limits for production

## Security Notes

- Never commit API keys to version control
- Use environment variables for production:
  ```bash
  export OPENAI_API_KEY="your-key-here"
  ```
- Implement rate limiting for production deployments

## License

Created for production use. Modify as needed.

## Support

For issues:
1. Enable debug mode to see detailed logs
2. Check OpenAI API status
3. Verify audio device configuration
4. Check requirements.txt versions match

---

**Tip**: Speak naturally and wait for the AI to finish before responding for best results!
