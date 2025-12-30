"""
Train a raw-waveform KWS model that accepts 1s (16000 samples) of float32 audio and outputs two-class probabilities: [wake, not_wake].
Designed to run in Colab or local Python environment.

Usage:
python train_raw_kws.py --data-dir ../data --output ../models/hey_imi.tflite --epochs 20

Data layout (same as before):
- kws/data/wake/*.wav      (positive examples - "hey imi")
- kws/data/notwake/*.wav  (negative examples)
- kws/data/background/*.wav (optional)

Notes:
- WAV must be 16kHz; if not, the script will resample using librosa.
- Output TFLite is int8-quantized and expects input uint8 or float depending on conversion settings; Android code expects float32 normalized [-1,1] array shape [1,16000]. If needed, adjust converter settings.
"""

import argparse
import os
import random
import numpy as np
import tensorflow as tf
import soundfile as sf
import librosa
from glob import glob

SEED = 1234
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

SAMPLE_RATE = 16000
SAMPLES = SAMPLE_RATE  # 1 second


def load_wav(path):
    data, sr = sf.read(path)
    if sr != SAMPLE_RATE:
        data = librosa.resample(data.astype(np.float32), orig_sr=sr, target_sr=SAMPLE_RATE)
    # Mono
    if data.ndim > 1:
        data = np.mean(data, axis=1)
    # Trim or pad
    if len(data) > SAMPLES:
        data = data[:SAMPLES]
    elif len(data) < SAMPLES:
        pad = SAMPLES - len(data)
        data = np.pad(data, (0, pad), 'constant')
    return data.astype(np.float32)


def list_files(data_dir):
    classes = [d for d in os.listdir(data_dir) if os.path.isdir(os.path.join(data_dir, d))]
    files = []
    for cls in classes:
        for p in glob(os.path.join(data_dir, cls, '*.wav')):
            files.append((p, cls))
    return files, sorted(classes)


def make_dataset(files, classes, batch=32, shuffle=True):
    X = []
    y = []
    class_to_index = {c: i for i, c in enumerate(classes)}
    for path, cls in files:
        audio = load_wav(path)
        X.append(audio)
        y.append(class_to_index[cls])
    X = np.stack(X)
    y = tf.keras.utils.to_categorical(y, num_classes=len(classes))
    ds = tf.data.Dataset.from_tensor_slices((X, y))
    if shuffle:
        ds = ds.shuffle(len(X), seed=SEED)
    ds = ds.batch(batch).prefetch(tf.data.AUTOTUNE)
    return ds


def build_model(input_shape, num_classes):
    inputs = tf.keras.Input(shape=input_shape)
    x = tf.expand_dims(inputs, -1)  # (samples,1)
    x = tf.keras.layers.Conv1D(32, 3, activation='relu', padding='same')(x)
    x = tf.keras.layers.MaxPool1D(4)(x)
    x = tf.keras.layers.Conv1D(64, 3, activation='relu', padding='same')(x)
    x = tf.keras.layers.MaxPool1D(4)(x)
    x = tf.keras.layers.Conv1D(128, 3, activation='relu', padding='same')(x)
    x = tf.keras.layers.GlobalAveragePooling1D()(x)
    x = tf.keras.layers.Dense(64, activation='relu')(x)
    outputs = tf.keras.layers.Dense(num_classes, activation='softmax')(x)
    model = tf.keras.Model(inputs, outputs)
    return model


def representative_gen(files, num=100):
    for i, (p, _) in enumerate(files):
        if i >= num:
            break
        audio = load_wav(p)
        # TFLite representative expects float32 input shaped [1,16000]
        yield [np.expand_dims(audio, axis=0).astype(np.float32)]


def main(args):
    files, classes = list_files(args.data_dir)
    if not files:
        print('No files found in', args.data_dir)
        return
    print('Classes:', classes)
    ds = make_dataset(files, classes, batch=args.batch)

    input_shape = (SAMPLES,)
    model = build_model(input_shape, len(classes))
    model.compile(optimizer='adam', loss='categorical_crossentropy', metrics=['accuracy'])
    model.summary()

    model.fit(ds, epochs=args.epochs)

    keras_out = args.output.replace('.tflite', '.h5')
    model.save(keras_out)
    print('Saved Keras model to', keras_out)

    # Convert to TFLite - float
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    float_out = args.output.replace('.tflite', '_float.tflite')
    with open(float_out, 'wb') as f:
        f.write(tflite_model)
    print('Saved float tflite to', float_out)

    # Quantize with representative dataset (int8)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.representative_dataset = lambda: representative_gen(files, num=200)
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.float32
    converter.inference_output_type = tf.float32
    tflite_quant = converter.convert()
    with open(args.output, 'wb') as f:
        f.write(tflite_quant)
    print('Saved quantized tflite to', args.output)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--data-dir', type=str, default='../data', help='path to data dir')
    parser.add_argument('--output', type=str, default='../models/hey_imi.tflite', help='output tflite path')
    parser.add_argument('--epochs', type=int, default=10)
    parser.add_argument('--batch', type=int, default=16)
    args = parser.parse_args()
    main(args)
