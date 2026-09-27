# Sleep sound classifier

`SleepSoundClassifier` runs Google's pretrained YAMNet on the phone using the
ONNX Runtime already present in FORJA. It decodes completed AAC chunks, resamples
to mono 16 kHz with a low-pass filter, computes YAMNet log-mel features, and runs
the 521-class network. Class 38 is `Snoring` (`/m/01d3sd`). It never opens the
microphone. The server receives acoustic observations and combines them with
server-side speech transcription and an evidence-linked report.

Packaged files are in `android-updates/visual-update/assets/forja/`:
`yamnet.onnx`, `yamnet.classes.csv`, `yamnet.LICENSE.txt`, and
`yamnet.provenance.json`. The helper verifies the model SHA-256 before loading it.
These files add about 15 MB; no new native runtime is added. The upstream source
and weights are Apache 2.0; the license and source hashes are retained.

Each input is limited to 2 MiB and 122 seconds, allowing encoder rounding around
a two-minute chunk. Decode has a 30-second deadline. Cancellation is checked
throughout decoding, resampling and inference. Integration must check the
session owner, current grant and analysis consent before analysis and again
before storing or uploading results. No user audio is cached by this helper.

Offsets refer to the decoded chunk. The 975-ms acoustic windows advance by
480 ms; the final window is zero padded and its reported interval is clipped to
actual decoded audio. Overlapping candidate intervals are merged. A score of
at least 0.5 produces `possible_snoring`; this is an uncalibrated model threshold,
not a probability of disease. Failed/cancelled analysis reports `unavailable`,
with zero coverage and no observations. An empty successful event list only
means that no window met this threshold.

The model does not identify a speaker, diagnose apnea, infer mental health, or
measure sleep stages. Bedroom noise, playback, other people and microphone
placement can affect predictions. Numerical conversion checks establish that
the implementation matches YAMNet, not its sensitivity or specificity for a
particular sleeper. Android decoder behavior and sustained device performance
still require a real device test.

## Rebuild and verify

Create an isolated Python 3.12 environment. Install `tensorflow-cpu==2.16.2`,
`tf-keras==2.16.0` (with `--no-deps` to retain the CPU TensorFlow package),
`tf2onnx==1.16.1`, `onnx==1.17.0`, and `onnxruntime==1.20.1`.

```sh
python build_yamnet.py --cache /tmp/yamnet-source --output /tmp/yamnet-rebuilt
```

The script verifies upstream hashes, builds from the pinned TensorFlow Models
commit and compares all 521 outputs against TensorFlow on four deterministic
waveforms. It also exports fixtures for `SleepSoundClassifierFeaturesTest.java`.
Compile that test with the production helper, Android API 35 and the existing
ONNX Android AAR's `classes.jar`, then pass `/tmp/yamnet-rebuilt` as its argument.
Its emitted `*.java_features` can be run through ONNX and compared with the
`*.scores` TensorFlow outputs. Review provenance and update the helper's pinned
hash whenever deliberately replacing a model; do not silently accept a new hash.

Official references:

- https://github.com/tensorflow/models/tree/9d33a164bf50e7084680a4ff4b88fc70be809631/research/audioset/yamnet
- https://www.tensorflow.org/hub/tutorials/yamnet
- https://storage.googleapis.com/audioset/yamnet.h5
