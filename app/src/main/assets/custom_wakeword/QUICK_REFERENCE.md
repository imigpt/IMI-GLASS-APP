# Hey IMI - Quick Reference

## TL;DR Setup (5 minutes)

### 1. Copy Model
```
Copy: exports/imi_cnn.onnx → your_app/models/
```

### 2. Install Dependencies
```bash
pip install onnxruntime librosa numpy
```

### 3. Minimal Code Example

```python
import onnxruntime as ort
import librosa
import numpy as np

# Load model
sess = ort.InferenceSession("models/imi_cnn.onnx")

# Process audio
def score_audio(audio_chunk_16khz):
    # Ensure 1.5s (24,000 samples)
    if len(audio_chunk_16khz) < 24000:
        audio_chunk_16khz = np.pad(audio_chunk_16khz, (0, 24000 - len(audio_chunk_16khz)))
    
    # Mel-spectrogram
    mel = librosa.feature.melspectrogram(
        y=audio_chunk_16khz, sr=16000, n_fft=512, win_length=400,
        hop_length=160, n_mels=40, fmin=80, fmax=7600
    )
    lm = librosa.power_to_db(mel, ref=1.0, amin=1e-10, top_db=None)
    lm = np.clip(lm, -80, 0.0)
    lm = (lm / 80.0) * 2.0 + 1.0
    lm = lm[:, :150]
    
    # Infer
    mel_spec = lm[np.newaxis, np.newaxis].astype(np.float32)
    score = float(sess.run(None, {"mel_spectrogram": mel_spec})[0][0])
    
    return score

# Test
test_score = score_audio(np.random.randn(24000).astype(np.float32))
print(f"Score: {test_score:.4f}")  # Should be ~0.0-0.1 for random
```

### 4. Production Thresholds
```
- threshold = 0.03           (raw confidence)
- threshold_off = 0.0225     (hysteresis)
- delta_trigger = 0.015      (relative jump)
- consec = 2                 (need 2 hits)
- cooldown = 2.0             (2 seconds between detections)
```

**If not detecting**: Lower threshold to 0.025 or reduce `consec` to 1  
**If false positives**: Raise threshold to 0.04 or increase `consec` to 3

---

## Model I/O

| Aspect | Value |
|--------|-------|
| Input | `mel_spectrogram` (float32, shape: 1×1×40×150) |
| Output | Single float (0.0 - 1.0) |
| Sample Rate | 16 kHz mono |
| Window | 1.5 seconds |
| Opset | 18 |
| Size | 26 KB |

---

## Audio Pipeline (Critical!)

```
Raw audio (16kHz, mono)
  ↓
[Ensure 1.5s length: pad or crop to 24,000 samples]
  ↓
Librosa Mel-spectrogram (n_mels=40, 512 FFT)
  ↓
Log power (db)
  ↓
Clip [-80, 0] → normalize to [-1, +1]
  ↓
Crop to 150 time frames
  ↓
Shape (40, 150) → add batch/channel → (1, 1, 40, 150) float32
  ↓
Model inference
```

⚠️ **Do not skip normalization or change parameters**

---

## Performance Targets

| Metric | Value |
|--------|-------|
| CPU Inference | ~50ms per frame |
| Memory | ~50 MB (ONNX Runtime + model) |
| Recall @ threshold=0.03 | 90% |
| FP Rate @ threshold=0.03 | ~35% (tune down with delta or higher threshold) |

---

## Common Issues

| Problem | Solution |
|---------|----------|
| Never detects | Lower threshold to 0.02, verify audio is 16kHz |
| False positives | Raise threshold to 0.04-0.05, increase consec to 3 |
| Crashes on load | Use FP32 model not INT8, check ONNX Runtime version |
| Wrong output | Check librosa preprocessing — must match exactly |
| Slow detection | Ensure 100ms step size, not sliding window per sample |

---

## Files Provided

```
exports/
├── imi_cnn.onnx                 ← Use this (FP32, 26 KB)
├── imi_cnn_int8.onnx            ← Optional (INT8, 7 KB)
├── FRONTEND_INTEGRATION.md      ← Full guide
├── QUICK_REFERENCE.md           ← This file
└── cnn_model_info.json          ← Metadata
```

---

## Next Steps

1. **Integrate Model**: Copy `imi_cnn.onnx` to your app
2. **Implement Audio Preprocessing**: Follow the pipeline above
3. **Add Detection Loop**: Process audio in 100ms chunks
4. **Tune Thresholds**: Test with real audio in your environment
5. **Validate**: Compare detections against ground truth

---

**Questions?** See `FRONTEND_INTEGRATION.md` for detailed docs.

