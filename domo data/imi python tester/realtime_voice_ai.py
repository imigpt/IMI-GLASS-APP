#!/usr/bin/env python3
"""
Real-time Voice-to-Voice AI Assistant using OpenAI GPT-4o Realtime Model
=========================================================================

Production-ready script for full-duplex audio streaming with OpenAI's Realtime API.
Captures microphone input, streams to OpenAI, receives AI audio response, and plays it back.

Author: AI Assistant
Date: 2026-02-12
"""

import websocket
import sounddevice as sd
import numpy as np
import base64
import json
import threading
import queue
import time
import signal
import sys
from typing import Optional, Callable
from dataclasses import dataclass


# ===========================
# Configuration
# ===========================

@dataclass
class AudioConfig:
    """Audio configuration parameters."""
    input_sample_rate: int = 16000  # Hz - Microphone input
    output_sample_rate: int = 24000  # Hz - AI audio output (OpenAI uses 24kHz)
    channels: int = 1  # Mono
    dtype: str = 'int16'  # PCM 16-bit
    chunk_size: int = 1024  # Samples per chunk
    block_duration: float = 0.1  # Seconds


@dataclass
class RealtimeConfig:
    """OpenAI Realtime API configuration."""
    api_key: str
    model: str = "gpt-4o-realtime-preview"
    base_url: str = "wss://api.openai.com/v1/realtime"
    
    # Pricing per minute (update as needed)
    input_audio_price_per_min: float = 0.10
    output_audio_price_per_min: float = 0.20
    
    @property
    def websocket_url(self) -> str:
        return f"{self.base_url}?model={self.model}"
    
    @property
    def headers(self) -> dict:
        return {
            "Authorization": f"Bearer {self.api_key}",
            "OpenAI-Beta": "realtime=v1"
        }


@dataclass
class SessionMetrics:
    """Track session statistics and costs."""
    total_input_chunks: int = 0
    total_output_chunks: int = 0
    input_audio_seconds: float = 0.0
    output_audio_seconds: float = 0.0
    messages_sent: int = 0
    messages_received: int = 0
    responses_completed: int = 0
    errors_count: int = 0
    last_response_time: float = 0.0
    total_response_time: float = 0.0
    
    def calculate_cost(self, config: RealtimeConfig) -> tuple[float, float, float]:
        """Calculate input, output, and total cost."""
        input_minutes = self.input_audio_seconds / 60.0
        output_minutes = self.output_audio_seconds / 60.0
        
        input_cost = input_minutes * config.input_audio_price_per_min
        output_cost = output_minutes * config.output_audio_price_per_min
        total_cost = input_cost + output_cost
        
        return input_cost, output_cost, total_cost


# ===========================
# Audio Player
# ===========================

class AudioPlayer:
    """Handles real-time audio playback from streaming chunks."""
    
    def __init__(self, config: AudioConfig, debug: bool = False):
        self.config = config
        self.debug = debug
        self.play_queue = queue.Queue(maxsize=50)
        self.is_playing = False
        self.play_thread: Optional[threading.Thread] = None
        self.stop_event = threading.Event()
        
    def start(self):
        """Start the audio playback thread."""
        self.stop_event.clear()
        self.play_thread = threading.Thread(target=self._play_loop, daemon=True)
        self.play_thread.start()
        if self.debug:
            print("[AudioPlayer] Started")
    
    def stop(self):
        """Stop the audio playback thread."""
        self.stop_event.set()
        if self.play_thread:
            self.play_thread.join(timeout=2)
        if self.debug:
            print("[AudioPlayer] Stopped")
    
    def add_audio(self, audio_data: bytes):
        """Add audio chunk to playback queue."""
        try:
            # Decode base64 if needed
            if isinstance(audio_data, str):
                audio_data = base64.b64decode(audio_data)
            
            # Convert bytes to numpy array
            audio_array = np.frombuffer(audio_data, dtype=np.int16)
            
            if not self.play_queue.full():
                self.play_queue.put(audio_array, block=False)
            else:
                if self.debug:
                    print("[AudioPlayer] Queue full, dropping chunk")
        except Exception as e:
            if self.debug:
                print(f"[AudioPlayer] Error adding audio: {e}")
    
    def _play_loop(self):
        """Continuous playback loop."""
        with sd.OutputStream(
            samplerate=self.config.output_sample_rate,
            channels=self.config.channels,
            dtype=self.config.dtype,
            blocksize=self.config.chunk_size
        ) as stream:
            while not self.stop_event.is_set():
                try:
                    audio_chunk = self.play_queue.get(timeout=0.1)
                    
                    if not self.is_playing:
                        self.is_playing = True
                        print("🔊 AI speaking...")
                    
                    # Reshape for sounddevice
                    audio_chunk = audio_chunk.reshape(-1, self.config.channels)
                    stream.write(audio_chunk)
                    
                except queue.Empty:
                    if self.is_playing:
                        self.is_playing = False
                        print("🔇 AI finished speaking")
                    continue
                except Exception as e:
                    if self.debug:
                        print(f"[AudioPlayer] Playback error: {e}")


# ===========================
# Audio Streamer
# ===========================

class RealtimeAudioStreamer:
    """Captures microphone audio and provides streaming chunks."""
    
    def __init__(self, config: AudioConfig, debug: bool = False):
        self.config = config
        self.debug = debug
        self.audio_queue = queue.Queue(maxsize=100)
        self.is_recording = False
        self.stream: Optional[sd.InputStream] = None
        self.stop_event = threading.Event()
        
    def start(self):
        """Start capturing microphone audio."""
        self.stop_event.clear()
        self.stream = sd.InputStream(
            samplerate=self.config.input_sample_rate,
            channels=self.config.channels,
            dtype=self.config.dtype,
            blocksize=self.config.chunk_size,
            callback=self._audio_callback
        )
        self.stream.start()
        if self.debug:
            print("[AudioStreamer] Started")
    
    def stop(self):
        """Stop capturing microphone audio."""
        self.stop_event.set()
        if self.stream:
            self.stream.stop()
            self.stream.close()
        if self.debug:
            print("[AudioStreamer] Stopped")
    
    def _audio_callback(self, indata, frames, time_info, status):
        """Callback for audio input stream."""
        if status and self.debug:
            print(f"[AudioStreamer] Status: {status}")
        
        if not self.stop_event.is_set():
            # Copy audio data
            audio_chunk = indata.copy()
            
            # Detect if user is speaking (simple voice activity detection)
            volume = np.abs(audio_chunk).mean()
            if volume > 100:  # Threshold for voice detection
                if not self.is_recording:
                    self.is_recording = True
                    print("🎤 User speaking...")
            
            # Add to queue
            if not self.audio_queue.full():
                self.audio_queue.put(audio_chunk)
    
    def get_audio_chunk(self, timeout: float = 0.1) -> Optional[np.ndarray]:
        """Get next audio chunk from queue."""
        try:
            return self.audio_queue.get(timeout=timeout)
        except queue.Empty:
            return None


# ===========================
# OpenAI Realtime Client
# ===========================

class OpenAIRealtimeClient:
    """WebSocket client for OpenAI Realtime API."""
    
    def __init__(
        self,
        config: RealtimeConfig,
        audio_config: AudioConfig,
        on_audio_received: Callable[[bytes], None],
        debug: bool = False
    ):
        self.config = config
        self.audio_config = audio_config
        self.on_audio_received = on_audio_received
        self.debug = debug
        
        self.ws: Optional[websocket.WebSocketApp] = None
        self.is_connected = False
        self.should_reconnect = True
        self.reconnect_delay = 2  # Seconds
        
        self.send_thread: Optional[threading.Thread] = None
        self.receive_thread: Optional[threading.Thread] = None
        self.stop_event = threading.Event()
        
        self.audio_streamer: Optional[RealtimeAudioStreamer] = None
        
        # Metrics tracking
        self.metrics = SessionMetrics()
        self.request_start_time: Optional[float] = None
        
    def connect(self):
        """Establish WebSocket connection to OpenAI Realtime API."""
        print("📡 Connecting to OpenAI Realtime API...")
        
        self.ws = websocket.WebSocketApp(
            self.config.websocket_url,
            header=self.config.headers,
            on_open=self._on_open,
            on_message=self._on_message,
            on_error=self._on_error,
            on_close=self._on_close
        )
        
        # Run WebSocket in separate thread
        ws_thread = threading.Thread(target=self.ws.run_forever, daemon=True)
        ws_thread.start()
        
        # Wait for connection
        timeout = 10
        start_time = time.time()
        while not self.is_connected and time.time() - start_time < timeout:
            time.sleep(0.1)
        
        if not self.is_connected:
            raise ConnectionError("Failed to connect to OpenAI Realtime API")
        
        print("✅ Connected successfully!")
    
    def start_streaming(self, audio_streamer: RealtimeAudioStreamer):
        """Start bidirectional audio streaming."""
        self.audio_streamer = audio_streamer
        self.stop_event.clear()
        
        # Start sending thread
        self.send_thread = threading.Thread(target=self._send_loop, daemon=True)
        self.send_thread.start()
        
        print("🚀 Streaming started. Speak into your microphone...")
    
    def stop_streaming(self):
        """Stop audio streaming."""
        self.stop_event.set()
        self.should_reconnect = False
        
        if self.send_thread:
            self.send_thread.join(timeout=2)
        
        if self.ws:
            self.ws.close()
        
        print("🛑 Streaming stopped")
    
    def _on_open(self, ws):
        """WebSocket connection opened."""
        self.is_connected = True
        if self.debug:
            print("[WebSocket] Connection opened")
        
        # Send initial configuration
        config_message = {
            "type": "session.update",
            "session": {
                "modalities": ["audio", "text"],
                "instructions": "You are a helpful voice assistant. Respond naturally and conversationally.",
                "voice": "alloy",
                "input_audio_format": "pcm16",
                "output_audio_format": "pcm16",
                "input_audio_transcription": {
                    "model": "whisper-1"
                },
                "turn_detection": {
                    "type": "server_vad",
                    "threshold": 0.5,
                    "prefix_padding_ms": 300,
                    "silence_duration_ms": 500
                }
            }
        }
        
        # Note: OpenAI Realtime API uses 24kHz for output audio by default
        if self.debug:
            print("[WebSocket] Configured: 16kHz input, 24kHz output")
        
        ws.send(json.dumps(config_message))
    
    def _on_message(self, ws, message):
        """Handle incoming WebSocket messages."""
        try:
            data = json.loads(message)
            event_type = data.get("type", "")
            
            self.metrics.messages_received += 1
            
            if self.debug:
                print(f"[WebSocket] Received: {event_type}")
            
            # Handle audio responses
            if event_type == "response.audio.delta":
                # Audio chunk from AI
                audio_b64 = data.get("delta", "")
                if audio_b64:
                    # Calculate audio duration (approximate)
                    audio_bytes = base64.b64decode(audio_b64)
                    audio_samples = len(audio_bytes) // 2  # 16-bit = 2 bytes per sample
                    duration_seconds = audio_samples / self.audio_config.output_sample_rate
                    
                    self.metrics.output_audio_seconds += duration_seconds
                    self.metrics.total_output_chunks += 1
                    
                    self.on_audio_received(audio_bytes)
            
            elif event_type == "response.audio.done":
                if self.debug:
                    print("[WebSocket] Audio response completed")
            
            elif event_type == "response.done":
                self.metrics.responses_completed += 1
                
                # Calculate response time
                if self.request_start_time:
                    response_time = time.time() - self.request_start_time
                    self.metrics.last_response_time = response_time
                    self.metrics.total_response_time += response_time
                    self.request_start_time = None
                    
                    print(f"⚡ Response time: {response_time:.2f}s")
                
                if self.debug:
                    print("[WebSocket] Response completed")
            
            elif event_type == "error":
                self.metrics.errors_count += 1
                error_msg = data.get("error", {}).get("message", "Unknown error")
                print(f"❌ Error: {error_msg}")
            
            elif event_type == "session.created":
                print("✅ Session established")
            
            elif event_type == "session.updated":
                if self.debug:
                    print("[WebSocket] Session configuration updated")
            
            elif event_type == "input_audio_buffer.speech_started":
                self.request_start_time = time.time()
                print("🎤 Speech detected")
            
            elif event_type == "input_audio_buffer.speech_stopped":
                print("🎤 Speech ended")
            
            elif event_type == "conversation.item.created":
                if self.debug:
                    print("[WebSocket] Conversation item created")
                    
        except json.JSONDecodeError as e:
            if self.debug:
                print(f"[WebSocket] JSON decode error: {e}")
        except Exception as e:
            if self.debug:
                print(f"[WebSocket] Message handling error: {e}")
    
    def _on_error(self, ws, error):
        """Handle WebSocket errors."""
        print(f"❌ WebSocket error: {error}")
    
    def _on_close(self, ws, close_status_code, close_msg):
        """Handle WebSocket closure."""
        self.is_connected = False
        print(f"🔌 Connection closed (Code: {close_status_code})")
        
        if self.should_reconnect and not self.stop_event.is_set():
            print(f"🔄 Reconnecting in {self.reconnect_delay} seconds...")
            time.sleep(self.reconnect_delay)
            try:
                self.connect()
            except Exception as e:
                print(f"❌ Reconnection failed: {e}")
    
    def _send_loop(self):
        """Continuously send microphone audio to API."""
        if not self.audio_streamer:
            return
        
        consecutive_silence = 0
        max_silence_chunks = 50  # Reset recording indicator after silence
        
        while not self.stop_event.is_set() and self.is_connected:
            try:
                audio_chunk = self.audio_streamer.get_audio_chunk()
                
                if audio_chunk is not None:
                    # Check if audio has content
                    volume = np.abs(audio_chunk).mean()
                    
                    if volume > 100:  # Voice detected
                        consecutive_silence = 0
                    else:
                        consecutive_silence += 1
                        if consecutive_silence >= max_silence_chunks:
                            if self.audio_streamer.is_recording:
                                self.audio_streamer.is_recording = False
                                print("🔇 User stopped speaking")
                                consecutive_silence = 0
                    
                    # Convert to bytes and encode
                    audio_bytes = audio_chunk.tobytes()
                    audio_b64 = base64.b64encode(audio_bytes).decode('utf-8')
                    
                    # Track input audio metrics
                    audio_samples = len(audio_chunk)
                    duration_seconds = audio_samples / self.audio_config.input_sample_rate
                    self.metrics.input_audio_seconds += duration_seconds
                    self.metrics.total_input_chunks += 1
                    
                    # Send audio chunk
                    message = {
                        "type": "input_audio_buffer.append",
                        "audio": audio_b64
                    }
                    
                    if self.ws and self.is_connected:
                        self.ws.send(json.dumps(message))
                        self.metrics.messages_sent += 1
                
            except Exception as e:
                if self.debug:
                    print(f"[SendLoop] Error: {e}")
                time.sleep(0.01)


# ===========================
# Main Application
# ===========================

class VoiceAssistant:
    """Main application orchestrator."""
    
    def __init__(self, api_key: str, model: str, debug: bool = False):
        self.audio_config = AudioConfig()
        self.realtime_config = RealtimeConfig(api_key=api_key, model=model)
        self.debug = debug
        
        self.audio_player = AudioPlayer(self.audio_config, debug=debug)
        self.audio_streamer = RealtimeAudioStreamer(self.audio_config, debug=debug)
        self.client = OpenAIRealtimeClient(
            self.realtime_config,
            self.audio_config,
            on_audio_received=self.audio_player.add_audio,
            debug=debug
        )
        
        self.start_time: Optional[float] = None
        self.running = False
        
        # Setup signal handler for clean shutdown
        signal.signal(signal.SIGINT, self._signal_handler)
    
    def _signal_handler(self, signum, frame):
        """Handle CTRL+C for clean shutdown."""
        print("\n\n⚠️  Shutdown signal received...")
        self.stop()
        sys.exit(0)
    
    def start(self):
        """Start the voice assistant."""
        print("\n" + "="*70)
        print("🎙️  Real-time Voice AI Assistant")
        print("="*70)
        print(f"Model:              {self.realtime_config.model}")
        print(f"Input Sample Rate:  {self.audio_config.input_sample_rate} Hz")
        print(f"Output Sample Rate: {self.audio_config.output_sample_rate} Hz")
        print(f"Audio Format:       PCM 16-bit Mono")
        print(f"Input Price:        ${self.realtime_config.input_audio_price_per_min:.2f}/min")
        print(f"Output Price:       ${self.realtime_config.output_audio_price_per_min:.2f}/min")
        print("="*70 + "\n")
        
        self.start_time = time.time()
        self.running = True
        
        try:
            # Connect to OpenAI
            self.client.connect()
            
            # Start audio player
            self.audio_player.start()
            
            # Start audio streamer
            self.audio_streamer.start()
            
            # Start bidirectional streaming
            self.client.start_streaming(self.audio_streamer)
            
            # Keep running and show statistics
            self._run_loop()
            
        except KeyboardInterrupt:
            print("\n\n⚠️  Interrupted by user")
            self.stop()
        except Exception as e:
            print(f"\n❌ Fatal error: {e}")
            self.stop()
            raise
    
    def _run_loop(self):
        """Main run loop with periodic status updates."""
        last_status_time = time.time()
        status_interval = 15  # Show status every 15 seconds
        
        print("\n💡 Tips:")
        print("   - Speak naturally into your microphone")
        print("   - Wait for AI to finish speaking before responding")
        print("   - Press CTRL+C to exit\n")
        print("-" * 70 + "\n")
        
        while self.running:
            try:
                time.sleep(1)
                
                # Show periodic status with metrics
                current_time = time.time()
                if current_time - last_status_time >= status_interval:
                    self._display_metrics()
                    last_status_time = current_time
                    
            except KeyboardInterrupt:
                break
    
    def _display_metrics(self):
        """Display current session metrics."""
        metrics = self.client.metrics
        elapsed = int(time.time() - self.start_time)
        minutes = elapsed // 60
        seconds = elapsed % 60
        
        # Calculate costs
        input_cost, output_cost, total_cost = metrics.calculate_cost(self.realtime_config)
        
        # Average response time
        avg_response_time = (
            metrics.total_response_time / metrics.responses_completed 
            if metrics.responses_completed > 0 else 0
        )
        
        print("\n" + "="*70)
        print("📊 SESSION METRICS")
        print("="*70)
        print(f"⏱️  Session Duration:      {minutes}m {seconds}s")
        print(f"🎤 Input Audio:           {metrics.input_audio_seconds:.1f}s ({metrics.total_input_chunks} chunks)")
        print(f"🔊 Output Audio:          {metrics.output_audio_seconds:.1f}s ({metrics.total_output_chunks} chunks)")
        print(f"💬 Messages Sent:         {metrics.messages_sent}")
        print(f"📨 Messages Received:     {metrics.messages_received}")
        print(f"✅ Responses Completed:   {metrics.responses_completed}")
        print(f"⚡ Last Response Time:    {metrics.last_response_time:.2f}s")
        print(f"📈 Avg Response Time:     {avg_response_time:.2f}s")
        print(f"❌ Errors:                {metrics.errors_count}")
        print("-"*70)
        print(f"💵 Input Cost:            ${input_cost:.4f}")
        print(f"💵 Output Cost:           ${output_cost:.4f}")
        print(f"💰 TOTAL COST:            ${total_cost:.4f}")
        print("="*70 + "\n")
    
    def stop(self):
        """Stop the voice assistant."""
        if not self.running:
            return
        
        self.running = False
        
        print("\n🛑 Shutting down...")
        
        # Stop streaming
        self.client.stop_streaming()
        
        # Stop audio components
        self.audio_streamer.stop()
        self.audio_player.stop()
        
        # Show comprehensive session summary
        if self.start_time:
            metrics = self.client.metrics
            elapsed = int(time.time() - self.start_time)
            minutes = elapsed // 60
            seconds = elapsed % 60
            
            # Calculate costs
            input_cost, output_cost, total_cost = metrics.calculate_cost(self.realtime_config)
            
            # Average response time
            avg_response_time = (
                metrics.total_response_time / metrics.responses_completed 
                if metrics.responses_completed > 0 else 0
            )
            
            print("\n" + "="*70)
            print("📊 FINAL SESSION SUMMARY")
            print("="*70)
            print(f"Model:                    {self.realtime_config.model}")
            print(f"Total Duration:           {minutes}m {seconds}s")
            print("-"*70)
            print(f"🎤 Input Audio Duration:  {metrics.input_audio_seconds:.2f}s ({metrics.input_audio_seconds/60:.2f} min)")
            print(f"   Total Input Chunks:    {metrics.total_input_chunks}")
            print(f"🔊 Output Audio Duration: {metrics.output_audio_seconds:.2f}s ({metrics.output_audio_seconds/60:.2f} min)")
            print(f"   Total Output Chunks:   {metrics.total_output_chunks}")
            print("-"*70)
            print(f"💬 Total Messages Sent:   {metrics.messages_sent}")
            print(f"📨 Total Messages Rcvd:   {metrics.messages_received}")
            print(f"✅ Responses Completed:   {metrics.responses_completed}")
            print(f"❌ Total Errors:          {metrics.errors_count}")
            print("-"*70)
            print(f"⚡ Last Response Time:    {metrics.last_response_time:.2f}s")
            print(f"📈 Avg Response Time:     {avg_response_time:.2f}s")
            print(f"📊 Total Response Time:   {metrics.total_response_time:.2f}s")
            print("-"*70)
            print(f"💵 Input Cost:            ${input_cost:.4f} (@${self.realtime_config.input_audio_price_per_min:.2f}/min)")
            print(f"💵 Output Cost:           ${output_cost:.4f} (@${self.realtime_config.output_audio_price_per_min:.2f}/min)")
            print(f"💰 TOTAL COST:            ${total_cost:.4f}")
            print("="*70)
        
        print("\n✅ Shutdown complete. Goodbye!\n")


def main():
    """Main entry point."""
    print("\n" + "="*70)
    print("🤖 OpenAI GPT-4o Realtime Voice Assistant Setup")
    print("="*70 + "\n")
    
    # Get API key
    api_key = input("Enter your OpenAI API key: ").strip()
    if not api_key:
        print("❌ API key is required!")
        sys.exit(1)
    
    # Get model name
    default_model = "gpt-4o-realtime-preview"
    model = input(f"Enter model name (default: {default_model}): ").strip()
    if not model:
        model = default_model
    
    # Ask for debug mode
    debug_input = input("Enable debug mode? (y/N): ").strip().lower()
    debug = debug_input == 'y'
    
    print("\n💡 NOTE: Cost estimates use approximate pricing ($0.10/min input, $0.20/min output).")
    print("   Check https://openai.com/pricing for current rates.")
    print("   Audio: 16kHz input (microphone) → 24kHz output (speakers)\n")
    
    # Check for required audio device
    try:
        devices = sd.query_devices()
        print(f"🎧 Found {len(devices)} audio device(s)")
        
        default_input = sd.query_devices(kind='input')
        default_output = sd.query_devices(kind='output')
        
        print(f"   Input: {default_input['name']}")
        print(f"   Output: {default_output['name']}")
        
    except Exception as e:
        print(f"❌ Audio device error: {e}")
        sys.exit(1)
    
    # Create and start assistant
    assistant = VoiceAssistant(api_key=api_key, model=model, debug=debug)
    
    try:
        assistant.start()
    except Exception as e:
        print(f"\n❌ Error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
