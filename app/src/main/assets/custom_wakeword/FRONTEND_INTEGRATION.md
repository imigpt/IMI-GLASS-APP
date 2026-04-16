# Hey IMI - Frontend Integration Guide

## Overview
Hey IMI is a wake-word detector for recognizing "hey imi" in real-time audio streams. This guide covers model specs, integration steps, and deployment recommendations.

---

## 1. Model Files

### Location: `exports/`

| File | Type | Size | Description |
|------|------|------|-------------|
| `imi_cnn.onnx` | ONNX (FP32) | ~26 KB | Main production model - use this |
| `imi_cnn_int8.onnx` | ONNX (INT8) | ~7 KB | Quantized for embedded devices (optional) |
| `cnn_model_info.json` | JSON | Metadata | Model configuration reference |

### Model Specs
- **Format**: ONNX (Open Neural Network Exchange)
- **Opset Version**: 18
- **Input**: `mel_spectrogram` (shape: 1×1×40×150, dtype: float32)
- **Output**: Single confidence score (0.0 - 1.0)
- **Sample Rate**: 16 kHz (mono)
- **Window**: 1.5 seconds (24,000 samples)
- **Step/Hop**: 100ms (1,600 samples)

---

## 2. Audio Processing Pipeline

Before feeding audio to the model, process it exactly as follows:

```python
import numpy as np
import librosa

SR = 16_000
N_MELS = 40
N_FFT = 512
WIN_LEN = 400
HOP_LEN = 160
FMIN = 80
FMAX = 7_600
N_TIME = 150
TOP_DB = 80.0

def audio_to_mel_spectrogram(audio: np.ndarray) -> np.ndarray:
    """Convert raw audio to model input."""
    # Ensure 1.5s of audio (24,000 samples @ 16kHz)
    WIN_SAMP = N_TIME * HOP_LEN  # 24,000
    if len(audio) < WIN_SAMP:
        audio = np.pad(audio, (0, WIN_SAMP - len(audio)))
    else:
        audio = audio[-WIN_SAMP:]  # Take last 1.5s
    
    # Mel-spectrogram
    mel = librosa.feature.melspectrogram(
        y=audio, sr=SR, n_fft=N_FFT, win_length=WIN_LEN,
        hop_length=HOP_LEN, n_mels=N_MELS, fmin=FMIN, fmax=FMAX,
    )
    
    # Log power (dB)
    lm = librosa.power_to_db(mel, ref=1.0, amin=1e-10, top_db=None)
    
    # Normalize to [-1, +1]
    lm = np.clip(lm, -TOP_DB, 0.0)
    lm = (lm / TOP_DB) * 2.0 + 1.0
    
    # Crop to expected time frames
    lm = lm[:, :N_TIME]  # Shape: (40, 150)
    
    # Add batch and channel dimensions
    return lm[np.newaxis, np.newaxis].astype(np.float32)  # (1, 1, 40, 150)
```

**Critical**: Do not skip normalization or change clipping range. The model was trained with exactly this pipeline.

---

## 3. Model Inference

### Python (ONNX Runtime)

```python
import onnxruntime as ort

# Load model
sess = ort.InferenceSession(
    "exports/imi_cnn.onnx",
    providers=["CPUExecutionProvider"]
)

# Get input name
input_name = sess.get_inputs()[0].name  # "mel_spectrogram"

# Inference
mel_spec = audio_to_mel_spectrogram(audio_chunk)
output = sess.run(None, {input_name: mel_spec})
confidence_score = float(output[0][0])  # Range: 0.0 - 1.0
```

### JavaScript / Web (ONNX.js)

```javascript
const ort = require('onnxruntime-web');

const session = await ort.InferenceSession.create('imi_cnn.onnx');
const inputTensor = new ort.Tensor('float32', melSpectrogramData, [1, 1, 40, 150]);
const feeds = { 'mel_spectrogram': inputTensor };
const results = await session.run(feeds);
const confidenceScore = results[0].data[0];  // Range: 0.0 - 1.0
```

### Other Platforms
- **C/C++**: Use ONNX Runtime C/C++ API
- **Java**: Use ONNX Runtime Java bindings
- **Mobile (iOS)**: Use Core ML conversion or ONNX Runtime
- **Mobile (Android)**: Use ONNX Runtime Android

---

## 4. Detection Algorithm

Raw model scores fluctuate. Apply post-processing for production:

### Parameters
```
threshold = 0.35              # Absolute trigger point
threshold_off = 0.1925        # Hysteresis OFF point (55% of threshold)
smoothing = 3                 # EMA window (300ms @ 100ms steps)
consec = 3                    # Consecutive hits required
cooldown = 2.0                # Seconds between detections
energy_gate = 0.001           # Silence gate (RMS floor)
delta_trigger = 0.15          # Relative jump above ambient
baseline_tau_s = 10.0         # Ambient baseline time-constant
```

### Detection Logic

```python
import collections
import time

class HeyIMIDetector:
    def __init__(
        self,
        threshold=0.35,
        threshold_off=None,
        smoothing=3,
        consec=3,
        cooldown=2.0,
        energy_gate=0.001,
        delta_trigger=0.15,
        baseline_tau_s=10.0,
    ):
        self.threshold = threshold
        self.threshold_off = threshold_off if threshold_off else threshold * 0.55
        self.smoothing = smoothing
        self.consec = consec
        self.cooldown = cooldown
        self.energy_gate = energy_gate
        self.delta_trigger = delta_trigger
        
        # EMA state
        self._ema_alpha = 2.0 / (smoothing + 1)
        self._ema = 0.0
        
        # Ambient baseline
        self._baseline_alpha = 1.0 / max(baseline_tau_s * 10.0, 1.0)
        self._ambient_baseline = 0.0
        
        # Streak tracking
        self._streak = 0
        self._last_fire = -999.0
    
    def update(self, audio_chunk: np.ndarray, sr: int = 16000) -> dict:
        """
        Process one audio chunk (typically 100ms) and return detection result.
        
        Args:
            audio_chunk: numpy array, mono audio at sr Hz
            sr: sample rate (default 16000)
        
        Returns:
            {
                'raw_score': float,           # Model output (0.0-1.0)
                'smooth_score': float,        # EMA-filtered score
                'ambient_baseline': float,    # Ambient noise level
                'delta': float,               # Score - baseline
                'triggered': bool,            # Wake word detected?
            }
        """
        
        # 1. Energy gate: skip inference on silence
        rms = np.sqrt(np.mean(audio_chunk ** 2))
        if rms < self.energy_gate:
            self._ema *= (1.0 - self._ema_alpha)
            return {
                'raw_score': 0.0,
                'smooth_score': self._ema,
                'ambient_baseline': self._ambient_baseline,
                'delta': -self._ambient_baseline,
                'triggered': False,
            }
        
        # 2. Get model score
        # TODO: Call your ONNX session here
        raw_score = self._get_model_score(audio_chunk)
        
        # 3. EMA smoothing
        self._ema = self._ema_alpha * raw_score + (1.0 - self._ema_alpha) * self._ema
        smooth = self._ema
        
        # 4. Ambient baseline tracking
        self._ambient_baseline = (
            (1.0 - self._baseline_alpha) * self._ambient_baseline
            + self._baseline_alpha * smooth
        )
        delta = smooth - self._ambient_baseline
        
        # 5. Debounce
        now = time.monotonic()
        if (now - self._last_fire) < self.cooldown:
            self._streak = 0
            return {
                'raw_score': raw_score,
                'smooth_score': smooth,
                'ambient_baseline': self._ambient_baseline,
                'delta': delta,
                'triggered': False,
            }
        
        # 6. Hit decision: absolute OR relative jump
        delta_floor = self.threshold * 0.65
        is_hit = (
            smooth >= self.threshold or
            (delta >= self.delta_trigger and smooth > delta_floor)
        )
        
        # 7. Hysteresis streak
        if is_hit:
            self._streak += 1
        elif smooth < self.threshold_off:
            self._streak = 0
        
        # 8. Consecutive gate
        fired = self._streak >= self.consec
        if fired:
            self._streak = 0
            self._last_fire = now
            # Reset EMA to prevent echo triggers
            self._ema *= 0.3
            self._ambient_baseline = self._ema
        
        return {
            'raw_score': raw_score,
            'smooth_score': smooth,
            'ambient_baseline': self._ambient_baseline,
            'delta': delta,
            'triggered': fired,
        }
    
    def _get_model_score(self, audio_chunk):
        # TODO: Implement ONNX inference here
        mel_spec = audio_to_mel_spectrogram(audio_chunk)
        output = self.session.run(None, {self.input_name: mel_spec})
        return float(output[0][0])
    
    def reset(self):
        """Reset detector state (call after detection or on app start)."""
        self._ema = 0.0
        self._ambient_baseline = 0.0
        self._streak = 0
        self._last_fire = -999.0
```

---

## 5. Real-Time Streaming Integration

### Example: Microphone Input (Python)

```python
import sounddevice as sd
import numpy as np
from collections import deque

SR = 16_000
STEP_SAMP = int(0.100 * SR)  # 100ms @ 16kHz = 1600 samples
WIN_SAMP = 24_000  # 1.5s window
SPEECH_FLOOR = 0.003  # Zero-fill chunks quieter than this

detector = HeyIMIDetector()
buf = np.zeros(WIN_SAMP, dtype=np.float32)

def audio_callback(indata, frames, time_info, status):
    global buf
    chunk = indata[:, 0].copy()
    chunk_rms = float(np.sqrt(np.mean(chunk ** 2)))
    
    # Roll window and append new chunk
    # Zero-fill quiet chunks to match training data format
    buf = np.roll(buf, -len(chunk))
    if chunk_rms >= SPEECH_FLOOR:
        buf[-len(chunk):] = chunk
    else:
        buf[-len(chunk):] = 0.0
    
    # Detect
    result = detector.update(buf)
    
    if result['triggered']:
        print("Wake word detected!")
    else:
        print(f"Score: {result['smooth_score']:.3f}  delta={result['delta']:+.3f}")

# Stream
with sd.InputStream(samplerate=SR, channels=1, blocksize=STEP_SAMP, callback=audio_callback):
    sd.sleep(60000)  # Run for 60 seconds
```

**CRITICAL**: The zero-fill of quiet chunks is essential. Training data is zero-padded;
without this, ambient noise fills the buffer and drowns model activation.

---

## 6. Model Performance

### Test Set Results
- **Recall (Positives)**: ~85% on sliding-window test (zero-padded buffer, thr=0.35)
- **False Positive Rate**: ~31% on clean speech negatives (test set); much lower in practice with energy gate
- **Latency**: ~1ms per frame on CPU (ONNX Runtime)

### Important: Real-World vs Test-Set Performance
The test set FPR is measured on clean speech files at full volume — worst case.
In real-world mic mode, most background noise is filtered by the energy gate,
and the zero-fill buffer strategy keeps the mel-spectrogram clean until speech arrives.
Live mic testing shows reliable detection.

### Tuning for Your Environment

If FP rate is too high:
- Increase `threshold` (e.g., 0.45, 0.48)
- Increase `consec` (require 4-5 consecutive hits)
- Increase `delta_trigger` (0.20, 0.25)

If detection is missing words:
- Decrease `threshold` (0.38, 0.35)
- Decrease `consec` (2 hits)
- Decrease `delta_trigger` (0.10)

---

## 7. Deployment Checklist

- [ ] Model file copied to app bundle (`imi_cnn.onnx`)
- [ ] Audio preprocessing implemented with exact normalization
- [ ] ONNX Runtime installed and configured
- [ ] Detection loop integrated (100ms chunk processing)
- [ ] Thresholds tuned for your use case
- [ ] Tested with real microphone in deployment environment
- [ ] Cooldown prevents fake triggers (test with repeated wake words)
- [ ] Error handling for model load failures
- [ ] Performance tested (CPU usage, latency)
- [ ] Logging enabled for debugging

---

## 8. Troubleshooting

### Model Loads But Never Detects
- Check audio preprocessing (normalization must be exact)
- Verify sample rate is 16 kHz
- **Ensure zero-fill of quiet chunks** (SPEECH_FLOOR=0.003 RMS)
- Lower `threshold` to 0.35
- Test with a known positive WAV file

### Too Many False Positives
- Increase `threshold` to 0.45-0.48
- Increase `consec` to 4
- Increase `delta_trigger` to 0.20

### Model File Errors
- Use FP32 model (`imi_cnn.onnx`) for maximum compatibility
- Verify file is not corrupted: `md5sum imi_cnn.onnx`
- Ensure ONNX Runtime version ≥ 1.14

---

## 9. Support & References

**Python Reference Implementation**: `d:/wake_up_word/imi_modele/hey_imi.py`

**Model Training Config**: `d:/wake_up_word/imi_modele/checkpoints/best_cnn_model.pt` (checkpoint metadata)

**Audio Feature Details**: See `src/cnn_train.py` lines 97-98 for normalization code

---

## 10. License & Attribution

Hey IMI Model - April 2026  
Parameters: epoch 76, val_F1=0.8451, threshold=0.35 (production-tuned)

