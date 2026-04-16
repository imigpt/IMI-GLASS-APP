# Hey IMI - Model Exports

Ready for production integration. Choose your implementation guide below.

## 🚀 Quick Start (5 min)

👉 **For developers in a hurry:** Read `QUICK_REFERENCE.md`

Contains minimal code example + thresholds + common fixes.

---

## 📚 Full Documentation (30 min)

👉 **For full integration:** Read `FRONTEND_INTEGRATION.md`

Complete guide covering:
- Model specs and formats
- Audio preprocessing pipeline (critical!)
- Detection algorithm with code examples
- Real-time streaming integration
- Performance tuning for your environment
- Troubleshooting guide

---

## 📦 Files in This Directory

| File | Purpose | For Whom |
|------|---------|----------|
| `imi_cnn.onnx` | **Production Model** (FP32) | Everyone — use this! |
| `imi_cnn_int8.onnx` | Quantized Model (INT8) | Embedded/mobile with memory constraints |
| `cnn_model_info.json` | Metadata & Config | Reference only |
| `FRONTEND_INTEGRATION.md` | Full Integration Guide | Developers integrating the model |
| `QUICK_REFERENCE.md` | Quick Start Guide | Developers in a hurry |
| `README.md` | This file | Everyone |

---

## ⚡ Key Points

✅ **What You Need**
- `imi_cnn.onnx` (26 KB)
- ONNX Runtime library
- Audio at 16 kHz, mono

✅ **What You Do**
1. Load model with ONNX Runtime
2. Feed 1.5s audio chunks (preprocessed with Mel-spectrogram)
3. Get confidence score (0.0 - 1.0)
4. Apply thresholds & post-processing

✅ **Production Thresholds**
- Threshold: 0.03
- Consec: 2 hits
- Cooldown: 2.0s
- (Tune for your environment)

---

## 🎯 Model Performance

| Metric | Value |
|--------|-------|
| Recall | 90% (positive test set) |
| False Positive Rate | ~35% on background (tune down with thresholds) |
| Inference Latency | ~50ms per frame (CPU) |
| Model Size | 26 KB |
| Training Data | ~22,716 negative + 10,000 positive samples |

---

## 🔧 Integration Checklist

- [ ] Copy `imi_cnn.onnx` to app
- [ ] Install ONNX Runtime
- [ ] Implement audio preprocessing (mel-spectrogram with exact normalization)
- [ ] Load model and set up inference
- [ ] Implement detection logic (EMA + hysteresis + consecutive gate)
- [ ] Tune thresholds for your environment
- [ ] Test with microphone
- [ ] Deploy

---

## 📖 Implementation Languages

**Supported:**
- Python (ONNX Runtime) — see examples in guides
- JavaScript/Web (ONNX.js)
- C/C++ (ONNX Runtime)
- Java (ONNX Runtime)
- Mobile (iOS, Android)

**Example Code:**
See `FRONTEND_INTEGRATION.md` section 3 for code in multiple languages.

---

## ❓ Frequently Asked Questions

**Q: Which model should I use?**  
A: Use `imi_cnn.onnx` (FP32). It's the production model. Use `imi_cnn_int8.onnx` only if you have severe memory constraints.

**Q: What's the minimum audio I need?**  
A: 1.5 seconds @ 16 kHz mono = 24,000 samples. Shorter audio can be zero-padded.

**Q: How often should I run inference?**  
A: Every 100ms sliding window for real-time detection. Don't process every single sample.

**Q: What if detection is missing words?**  
A: Lower the threshold or reduce `consec` to 1. See tuning guide in `FRONTEND_INTEGRATION.md`.

**Q: What if there are too many false alarms?**  
A: Raise the threshold or increase `consec` to 3-4. Increase `delta_trigger` for environment-based detection.

**Q: Can I use a different sample rate?**  
A: No. Resample to 16 kHz first. The model is trained on 16 kHz audio.

**Q: Is there a latency guarantee?**  
A: ~50ms per frame on CPU. Aim for 100ms chunks to reduce overhead.

---

## 🤝 Support

**Python Reference Implementation:**
Find the full detector code in `../../hey_imi.py`

**Training Details:**
Model checkpoint at `../../checkpoints/best_cnn_model.pt`  
Training config at `../../src/cnn_train.py`

---

## 📅 Model Information

- **Trained**: April 2026
- **Archive**: Epoch 76
- **Validation F1**: 0.8451
- **Production Threshold**: 0.03
- **Input**: 40 Mel bins × 150 time frames
- **Architecture**: 3-layer CNN

---

**Ready to integrate?** Start with `QUICK_REFERENCE.md` → `FRONTEND_INTEGRATION.md`

