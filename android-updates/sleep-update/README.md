# Android v25: sleep, onboarding and map update

This update is applied to the exact delivered v24 APK. The old root Gradle app is not the current binary and must not be substituted as its base.

- Base SHA-256: `853ad79920e283d819a40c4bcbdddbc0ea0f99e4a1a0c3b49e65d763f1c8f200`.
- Version: `3.7-online.25`, version code 55, existing package and signing identity.
- New source is in the sibling `cleanup-update/feature` and `audio-update/src` modules.
- The existing sleep navigation and legacy start action route into `SleepHub` and the same owner-bound microphone foreground service. Historical UI and alarms remain available.
- Sessions last 1 minute to 12 hours. Complete AAC segments are bounded at 2 minutes and retained through the existing authenticated upload queue; there can be short recorder restart gaps.
- A sleep reservation precedes every recording upload. Reports and acoustic metadata use the authenticated `/v2/sleep` routes. Local start works without a network; upload and report creation retry when service is available.
- Only explicit consent from a visible app arms remote sleep control. Arming creates the visible foreground notification and does not capture audio. Remote commands require the same current readiness session, owner, grant and epoch. Reboot/process death requires rearming; active capture is never sticky.
- The packaged official YAMNet model classifies possible snoring from completed local audio. Whisper transcripts and grounded topics are separate server analysis. Neither estimates sleep stages, a sleep quality score, apnea or psychological diagnoses.

Build the feature with Gradle 8.10.2, JDK 17 and SDK 35:

```sh
cd android-updates/cleanup-update
gradle :feature:assembleDebug :feature:testDebugUnitTest :feature:exportCompileClasspath
```

Then, from the repository root (password supplied securely as an environment variable):

```sh
python3 android-updates/sleep-update/build.py \
  --base /path/FORJA_v24_Interfata.apk \
  --tools /path/android-tools \
  --compiled android-updates/cleanup-update \
  --app-patch android-updates/visual-update/onboarding_patch.py \
  --keystore /path/original-keystore \
  --output /path/FORJA_v25_Somn.apk
```

The tools layout is the same as `visual-update/fetch-tools.py`. Model, provenance, licenses and onboarding images are packaged from `visual-update/assets`. On a fresh clone the builder assembles `audio-update/model/data/yamnet.onnx.part1` and `.part2` into the ignored `assets/forja/yamnet.onnx` and verifies its SHA-256 before use. Only the complete model goes into the APK. The output is rejected unless the original signature, DEX definitions and referenced ABI, version-only manifest mutation, precise routing hooks, pinned model hash, and all non-allowlisted archive entries pass verification. APKs, toolchains and generated build outputs remain outside version control.

Pure JVM checks: `audio-update/tests/SleepAudioPolicyTest.java` (68 cases) and `AudioReadyPolicyTest.java` (26 cases). Model tests and reproducibility are documented alongside the YAMNet provenance. A successful build and these checks do not constitute a physical device, overnight battery, real account upload, or production provider test; those need separate evidence.
