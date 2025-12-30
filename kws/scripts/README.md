Training a custom wake-word model ("hey imi")

Steps:
1. Prepare data under `kws/data`:
   - `wake/` contains many positive examples (hey imi) in .wav format (16kHz, mono, 1s preferred)
   - `notwake/` similar speech non-target
   - `background/` optional background noise wavs

2. Create a Python virtualenv and install deps:

```bash
python -m venv .venv
source .venv/bin/activate    # or .venv\Scripts\Activate on Windows
pip install -r requirements.txt
```

3. Train and export TFLite:

You can train a spectrogram-based model (train_kws.py) or a raw-waveform model (train_raw_kws.py).

Spectrogram (existing):

```bash
python train_kws.py --data-dir ../data --output ../models/hey_imi_spectrogram.tflite --epochs 10
```

Raw-waveform (recommended for Android raw-input verifier):

```bash
python train_raw_kws.py --data-dir ../data --output ../models/hey_imi.tflite --epochs 12
```

4. Copy the produced `hey_imi.tflite` into the Android project (e.g., `app/src/main/assets/hey_imi.tflite`). The app's `CustomVoiceDetector.kt` attempts to load `hey_imi.tflite` from assets and will use it to verify candidates.

5. Replace the RMS-based detection with model inference using short audio buffers and on-device inference. When the model predicts the `wake` class, post the `BluetoothEvent(EventType.VOICE_TEXT, "wake up")` as the existing code expects.

If you'd like, I can now:
- Implement Android-side TFLite loading and inference in `CustomVoiceDetector.kt` (fast), or
- Run a local training session (I cannot run it in your environment without GPU/time), but I can provide commands and a cloud-ready Colab notebook.
