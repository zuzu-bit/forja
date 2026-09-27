#!/usr/bin/env python3
"""Build and numerically check the acoustic classifier from Google's pinned YAMNet sources.

Run in an isolated Python 3.12 environment with tensorflow-cpu==2.16.2,
tf-keras==2.16.0, tf2onnx==1.16.1, onnx==1.17.0, onnxruntime==1.20.1.
This script uses no inference API, credentials, user audio or paid service.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import urllib.request

COMMIT = "9d33a164bf50e7084680a4ff4b88fc70be809631"
BASE = f"https://raw.githubusercontent.com/tensorflow/models/{COMMIT}/research/audioset/yamnet/"
SOURCES = {
    "yamnet.py": (BASE + "yamnet.py", "7ef3df32b7ecb782490b5a04d7581a23cfcf701dbf476bc4d03deefe22cdb040"),
    "features.py": (BASE + "features.py", "e6cd53f81d072c7c43be4c7fff2b9dd0c5ccc7d64f2fbcfc85b44013d6d2ed5e"),
    "params.py": (BASE + "params.py", "925bb1e62461016031f98aea09aeac28975dd516f5747513767de5d1b06b6145"),
    "yamnet_class_map.csv": (BASE + "yamnet_class_map.csv", "cdf24d193e196d9e95912a2667051ae203e92a2ba09449218ccb40ef787c6df2"),
    "yamnet.h5": ("https://storage.googleapis.com/audioset/yamnet.h5", "13c3308955bbfaef262f175ac9c40e47b134573a93984f009220dd7cc12a1744"),
    "LICENSE": (f"https://raw.githubusercontent.com/tensorflow/models/{COMMIT}/LICENSE", "5b17814bf0de8cf65069bc6d7cc38cff19fcaa864d243423ad3ef3db01b52385"),
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path, help="Use a scratch output; review model hash before replacing the packaged asset.")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    args.output.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in SOURCES.items():
        path = args.cache / name
        if not path.is_file():
            path.write_bytes(urllib.request.urlopen(url, timeout=60).read())
        if sha256(path.read_bytes()) != expected:
            raise RuntimeError(f"Upstream checksum mismatch: {name}")
    for name, value in {"TF_CPP_MIN_LOG_LEVEL": "2", "TF_ENABLE_ONEDNN_OPTS": "0",
                        "TF_NUM_INTRAOP_THREADS": "2", "TF_NUM_INTEROP_THREADS": "1"}.items():
        os.environ[name] = value
    sys.path.insert(0, str(args.cache.resolve()))
    import numpy as np
    import tensorflow as tf
    import tf_keras
    import tf2onnx
    import onnxruntime as ort
    import yamnet
    import params
    import features

    parameters = params.Params()
    inputs = tf_keras.layers.Input(shape=(96, 64), batch_size=1, dtype=tf.float32, name="log_mel")
    core = tf_keras.Model(inputs, yamnet.yamnet(inputs, parameters)[0])
    core.load_weights(str(args.cache / "yamnet.h5"))
    signature = (tf.TensorSpec([1, 96, 64], tf.float32, name="log_mel"),)

    @tf.function(input_signature=signature)
    def infer(log_mel):
        return {"scores": core(log_mel, training=False)}

    output = args.output / "yamnet.onnx"
    tf2onnx.convert.from_function(infer, input_signature=signature, opset=17, output_path=str(output))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    runtime = ort.InferenceSession(str(output), sess_options=options, providers=["CPUExecutionProvider"])
    samples = np.arange(15600)
    fixtures = {
        "silence": np.zeros(15600, dtype=np.float32),
        "tone440": (np.sin(samples * 2 * np.pi * 440 / 16000) * .5).astype(np.float32),
        "white_noise": np.random.default_rng(730).normal(0, .08, 15600).astype(np.float32),
        "tones": ((np.sin(samples * 2 * np.pi * 173 / 16000) + np.sin(samples * 2 * np.pi * 2713 / 16000)) * .15).astype(np.float32),
    }
    checks = []
    for name, waveform in fixtures.items():
        _, patches = features.waveform_to_log_mel_spectrogram_patches(tf.constant(waveform), parameters)
        patch = patches.numpy()
        reference = core(patch, training=False).numpy()
        actual = runtime.run(None, {"log_mel": patch})[0]
        error = float(np.max(np.abs(reference - actual)))
        if error >= 1e-4:
            raise RuntimeError(f"Conversion differs from TensorFlow: {name}: {error}")
        waveform.astype("<f4").tofile(args.output / f"{name}.pcm")
        patch.astype("<f4").tofile(args.output / f"{name}.features")
        reference.astype("<f4").tofile(args.output / f"{name}.scores")
        checks.append({"fixture": name, "max_abs_score_error": error, "top_index": int(actual.argmax())})
    report = {"source_commit": COMMIT, "sources": SOURCES,
              "model_sha256": sha256(output.read_bytes()), "bytes": output.stat().st_size,
              "numeric_reference_checks": checks, "clinical_validation": False}
    (args.output / "conversion-validation.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
