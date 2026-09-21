# Android runtime QA — blocked by emulator bootstrap

Candidate: `FORJA_v24_Interfata.apk`  
Package: `com.forja.app.research`, versionCode `54`  
SHA-256: `853ad79920e283d819a40c4bcbdddbc0ea0f99e4a1a0c3b49e65d763f1c8f200`

On 21 September 2026, runtime validation was attempted with the official Android Emulator 37.2.10 and AOSP API 35 x86_64 image. Both downloaded archives matched the checksums in Google's package metadata. The host has no `/dev/kvm`; the device used software CPU emulation and SwiftShader.

A synthetic AVD with no real accounts or user data reached an online adb connection and started Android's system server. At the last check, **14:11:29 UTC**, `sys.boot_completed` was still empty after roughly nine minutes of uninterrupted boot. Android was still performing its own first-boot ART/dex2oat work. The emulator was stopped at the agreed time limit.

One early APK installation attempt was rejected by Android's PackageInstaller because `StorageManager` was not yet initialized (`getVolumes()` on a null reference). This happened before a usable system boot and is **not evidence of an app regression**. No successful APK installation or FORJA launch was recorded.

**Not runtime verified:** first-launch permission hub and reflection calls, Continue without grants, Back persistence, privacy navigation, and unauthenticated audio/file tabs. No app screenshots or successful device-test claim are provided. Source/build/unit checks are reported separately and do not replace these checks.

Re-run the included `tools/android_ux_qa.py` harness on an accelerated emulator, or perform the same adb flows on a physical test device, before treating runtime QA as passed. Transient boot and system logs are intentionally excluded from the repository. No device permissions were granted, accounts created, or recording/collection enabled.
