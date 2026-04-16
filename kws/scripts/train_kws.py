"""
Train a small KWS model for the phrase "hey imi" and export a quantized TFLite model.

Expect directory structure:
kws/data/wake/        -> positive examples ("hey imi")
kws/data/notwake/    -> similar speech but not target
kws/data/background/  -> background noise (optional)

Usage:
python train_kws.py --data-dir ../data --output ../models/hey_imi.tflite

This script uses TensorFlow 2.x and trains a compact CNN on log-mel spectrograms.
"""

import argparse
import os
import pathlib
import random
import numpy as np
import tensorflow as tf

# Reproducibility
SEED = 1234
random.seed(SEED)
np.random.seed(SEED)
tf.random.set_seed(SEED)

SAMPLE_RATE = 16000
DURATION = 1.0  # seconds per example
SAMPLES = int(SAMPLE_RATE * DURATION)

def list_files(data_dir):
    classes = [d.name for d in pathlib.Path(data_dir).iterdir() if d.is_dir()]
    files = []
    for cls in classes:
        for p in pathlib.Path(data_dir, cls).rglob('*.wav'):
            files.append((str(p), cls))
    return files, sorted(classes)


def decode_wav(file_path):
    wav = tf.io.read_file(file_path)
    audio, sample_rate = tf.audio.decode_wav(wav, desired_channels=1, desired_samples=SAMPLES)
    audio = tf.squeeze(audio, axis=-1)
    return audio


def random_background_noise(background_files, audio):
    if not background_files:
        return audio
    bg_file = random.choice(background_files)
    bg = decode_wav(bg_file)
    # Mix a random segment of background at low gain
    gain = random.uniform(0.0, 0.3)
    return audio + gain * bg


def augment_audio(audio):
    """Data augmentation - creates variations of training samples"""
    # Random pitch shift (slight: -1 to +1 semitones)
    if random.random() > 0.5:
        # Speed shift instead (tensorflow doesn't have native pitch)
        speed_factor = random.uniform(0.9, 1.1)
        # Resample by changing indices
        indices = tf.cast(tf.linspace(0.0, tf.cast(tf.shape(audio)[0], tf.float32) - 1, 
                                      tf.cast(tf.cast(tf.shape(audio)[0], tf.float32) / speed_factor, tf.int32)), tf.int32)
        audio = tf.gather(audio, indices)
    
    # Random volume adjustment (±30%)
    volume_factor = random.uniform(0.7, 1.3)
    audio = audio * volume_factor
    
    # Add slight random noise (SNR boost for robustness)
    if random.random() > 0.3:
        noise = tf.random.normal(tf.shape(audio)) * 0.02
        audio = audio + noise
    
    # Random time stretch (slow down/speed up)
    if random.random() > 0.5:
        stretch_factor = random.uniform(0.95, 1.05)
        new_length = tf.cast(tf.cast(tf.shape(audio)[0], tf.float32) * stretch_factor, tf.int32)
        audio = tf.image.resize(audio[tf.newaxis, :, tf.newaxis], [new_length, 1])[0, :, 0]
    
    # Clip to valid range
    audio = tf.clip_by_value(audio, -1.0, 1.0)
    return audio


def preprocess(audio):
    # compute log-mel spectrogram
    stft# Apply augmentation to training data (3x more variations)
        if random.random() > 0.3:
            audio = augment_audio(audio)
         = tf.signal.stft(audio, frame_length=640, frame_step=320, fft_length=1024)
    spectrogram = tf.abs(stft)

    num_spectrogram_bins = spectrogram.shape[-1]
    lower_edge_hertz, upper_edge_hertz, num_mel_bins = 80.0, 7600.0, 40
    linear_to_mel_weight_matrix = tf.signal.linear_to_mel_weight_matrix(
        num_mel_bins, num_spectrogram_bins, SAMPLE_RATE, lower_edge_hertz, upper_edge_hertz)
    mel_spectrogram = tf.tensordot(spectrogram, linear_to_mel_weight_matrix, 1)
    mel_spectrogram.set_shape(spectrogram.shape[:-1].concatenate(linear_to_mel_weight_matrix.shape[-1:]))

    log_mel = tf.math.log(mel_spectrogram + 1e-6)
    # normalize
    mean = tf.math.reduce_mean(log_mel)
    std = tf.math.reduce_std(log_mel)
    norm = (log_mel - mean) / (std + 1e-6)
    # add channel dim
    norm = tf.expand_dims(norm, -1)
    return norm


def make_dataset(data_dir, batch=32, shuffle=True):
    files, classes = list_files(data_dir)
    class_to_index = {c: i for i, c in enumerate(classes)}

    file_paths = [f for f, _ in files]
    labels = [class_to_index[c] for _, c in files]

    ds = tf.data.Dataset.from_tensor_slices((file_paths, labels))

    def _load(path, label):
        audio = decode_wav(path)
        x = preprocess(audio)
        return x, tf.one_hot(label, len(classes))

    if shuffle:
        ds = ds.shuffle(buffer_size=len(file_paths), seed=SEED)
    ds = ds.map(lambda p, l: tf.py_function(func=_load, inp=[p, l], Tout=(tf.float32, tf.float32)), num_parallel_calls=tf.data.AUTOTUNE)
    ds = ds.map(lambda x, y: (tf.ensure_shape(x, [None, None, 1]), tf.ensure_shape(y, [len(classes)])))
    ds = ds.batch(batch).prefetch(tf.data.AUTOTUNE)
    return ds, classes


def build_model(input_shape, num_classes):
    inputs = tf.keras.Input(shape=input_shape)
    x = tf.keras.layers.Conv2D(8, (3, 3), activation='relu', padding='same')(inputs)
    x = tf.keras.layers.MaxPool2D((2, 2))(x)
    x = tf.keras.layers.Conv2D(16, (3, 3), activation='relu', padding='same')(x)
    x = tf.keras.layers.MaxPool2D((2, 2))(x)
    x = tf.keras.layers.Flatten()(x)
    x = tf.keras.layers.Dense(64, activation='relu')(x)
    x = tf.keras.layers.Dropout(0.2)(x)
    outputs = tf.keras.layers.Dense(num_classes, activation='softmax')(x)
    model = tf.keras.Model(inputs, outputs)
    return model


def representative_dataset_gen(files, num_steps=100):
    # yield raw input tensors for TFLite quantization
    for i, (f, _) in enumerate(files):
        if i >= num_steps:
            break
        audio = decode_wav(f)
        input_tensor = preprocess(audio)
        input_tensor = tf.expand_dims(input_tensor, 0)
        yield [input_tensor]


def main(args):
    data_dir = args.data_dir
    out = args.output
    epochs = args.epochs

    print('Scanning data...')
    ds, classes = make_dataset(data_dir, batch=args.batch)
    print('Classes:', classes)

    # get input shape from one batch
    for x, y in ds.take(1):
        input_shape = x.shape[1:]
        break

    model = build_model(input_shape, len(classes))
    model.compile(optimizer='adam', loss='categorical_crossentropy', metrics=['accuracy'])
    model.summary()

    model.fit(ds, epochs=epochs)

    # Save Keras model
    keras_out = out.replace('.tflite', '.h5')
    model.save(keras_out)
    print('Saved Keras model to', keras_out)

    # Convert to TFLite (float)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_model = converter.convert()
    with open(out.replace('.tflite', '_float.tflite'), 'wb') as f:
        f.write(tflite_model)
    print('Saved float TFLite model')

    # Post-training quantization with representative dataset
    files, _ = list_files(data_dir)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    def rep_gen():
        for f, _ in files:
            audio = decode_wav(f)
            input_tensor = preprocess(audio)
            input_tensor = tf.expand_dims(input_tensor, 0)
            yield [input_tensor]
    converter.representative_dataset = rep_gen
    converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
    converter.inference_input_type = tf.uint8
    converter.inference_output_type = tf.uint8
    tflite_quant = converter.convert()
    with open(out, 'wb') as f:
        f.write(tflite_quant)
    print('Saved quantized TFLite model to', out)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--data-dir', type=str, default='..\\data', help='Path to data folder')
    parser.add_argument('--output', type=str, default='../models/hey_imi.tflite', help='TFLite output path')
    parser.add_argument('--epochs', type=int, default=10)
    parser.add_argument('--batch', type=int, default=16)
    args = parser.parse_args()
    main(args)
