# FORJA Research 2 validation

Base Android release: `e22f5c89913248aa9bf0719cf62c40cd5de2bbc5`.
Previous research revision: `8f8543c3a8e7de85c452cf67ac11f464b6eaba35`.

## Completed checks

| Check | Result |
| --- | --- |
| `assembleResearch` | Passed; Kotlin, Java, DEX and APK packaging completed |
| `testResearchUnitTest` | 6 passed, 0 failed, 0 skipped |
| Receiver integration tests | 16 passed, 0 failed |
| APK signature verification | Passed |
| Packaged identity | `com.forja.app.research`, version `3.7-research.2` |
| Android compatibility declared | Minimum API 26, target API 35 |
| Launcher | Redesigned `ResearchExportActivity` |

APK SHA-256:
`e7d3ba67c2c8538ce529b15ffd755b0e8c459ccd934ee05ac4146ce742cdded4`.

Build tools: Gradle 8.10.2, JDK 17, Android platform 35 and the existing Android
dependencies. KSP incremental processing is disabled in this research branch
after reproducible shadow/generated-source collisions with the existing KSP
version. This uses the documented [KSP troubleshooting option](https://kotlinlang.org/docs/ksp-incremental.html).
No compilation or test task was excluded.

## What the tests establish

The receiver tests use a real local HTTP server, temporary storage, random lab
tokens and synthetic records/bytes. They cover the original health export plus:

- Location/app-usage metrics stored and retrieved byte-for-byte, with matching
  SHA-256 receipts.
- Rejection of unselected categories, unexpected fields, invalid coordinates,
  impossible durations and oversized items.
- Selected binary-file round trips, authorization checks and complete session
  deletion.
- Microphone consent enforcement, valid mono 16 kHz PCM16 WAV structure,
  unique/bounded clip sequences and closing audio uploads after the allowed
  server window.
- Session expiry and authenticated listing; the public viewer returns its UI
  without including private session contents.

The JVM calculation tests cover overlapping activity intervals, clipping to the
usage window, a single location fix contributing zero dwell time, long gaps,
movement, poor accuracy and stopping/restarting collection. They do not validate
Android's history completeness or real-world GPS accuracy.

## Device and deployment limits

The final APK was built and an earlier build of the same redesigned UI was
installed successfully on an Android 28 software emulator (`adb install` returned
Success). Android's System UI then displayed an unresponsive-system dialog.
The visual check could not be completed reliably. The final build additionally
adds a hard audio timeout; final-build installation was not confirmed.

Runtime permission prompts, location callbacks, Usage Access, system file/photo
pickers, actual microphone capture, live browser playback and phone-to-server
delivery remain **unverified end to end**. No physical phone is connected.
The blank Android crash buffer collected during this attempt is not proof of
successful app execution. No emulator image is presented as an app screenshot.

The receiver has been tested locally with synthetic data. No public receiver
has been deployed and no original FORJA cloud service has been modified. The
research Firebase configuration still points to a dummy emulator project and
the original Worker upload URL remains empty.

More uploaded data is a declared treatment in this modified app. These tests do
not establish permission abuse in the original FORJA APK or a prevalence rate
across other apps.
