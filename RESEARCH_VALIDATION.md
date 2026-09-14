> Historical revision 3 checks. Current revision 4 checks are in [INSIGHTS_VALIDATION.md](INSIGHTS_VALIDATION.md).

# FORJA online revision 3 validation

Base Android release: `e22f5c89913248aa9bf0719cf62c40cd5de2bbc5`.
Previous research revision: `7b4ca57c294b600267fdc04e7d32223cb473096e`.

## Completed checks

| Check | Result |
| --- | --- |
| `assembleResearch` | Passed; Kotlin, Java, DEX and APK packaging completed |
| `testResearchUnitTest` | 9 passed, 0 failed, 0 skipped |
| Receiver integration tests | 16 passed, 0 failed |
| APK signature verification | Passed |
| Packaged identity | `com.forja.app.research`, version `3.7-online.3` |
| Android compatibility declared | Minimum API 26, target API 35 |
| Launcher | Original `MainActivity`; login → Dashboard, optional data panel in Profile |

APK SHA-256:
`6c24ecd8e872282a01b9cf6d8c4b07179d5525a7afbcba0ceb0e60fdf47b1a40`.

Build tools: Gradle 8.10.2, JDK 17, Android platform 35 and the existing Android
dependencies. KSP incremental processing is disabled in this research branch
after reproducible shadow/generated-source collisions with the existing KSP
version. This revision required a clean build to clear stale generated Room files;
`clean assembleResearch testResearchUnitTest` then passed. This uses the documented [KSP troubleshooting option](https://kotlinlang.org/docs/ksp-incremental.html).
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

## Online integration check (2026-09-12)

A randomly named temporary email/password account was created through the live
Firebase REST API with the copy's Android package header. The following live
checks completed:

- Registration and password login: HTTP 200.
- A synthetic private meal document: write/read HTTP 200; exact fields matched.
- Anonymous read of that document: HTTP 403.
- Worker public health using the app's OkHttp user agent: HTTP 200.
- A cheap authenticated Worker request, with no audio payload: reached route
  validation and returned the expected HTTP 400 for a missing session (not 401).
- Synthetic journal deletion and temporary account deletion: HTTP 200.

No user-supplied email, password, photo, location or audio was used in these
checks. No root profile/invitation documents were created, because the existing
rules do not permit client deletion of those documents. Tokens were not logged.
The initial Python-default user agent received Cloudflare 1010; the actual
OkHttp agent and a browser agent both returned 200.

The additional JVM tests cover stable per-installation cloud record IDs, avoiding
collisions with original/copy records, and the existing Worker's 40-character
recording-key limit. The packaged manifest and Firebase/Worker configuration
are checked independently of the source changes.

## Device and deployment limits

No physical phone is connected. Previous software-emulator attempts stalled in
Android System UI; this revision is not claimed as visually tested or tested
end to end on Android. The live REST checks establish backend account and
journal connectivity, not proof of a successful UI flow on the user's phone.

The optional research receiver's prior 16 integration tests passed using local
synthetic data. Its implementation has not changed in this revision. It has not
been deployed publicly and its pairing-token `/v2/sessions` API is separate from
the existing FORJA online account/journal services. No original cloud service,
Firebase rules, or main-branch release has been changed by deployment.

Fitness journal upload uses the original Firestore cache path, with namespaced
IDs in the copy. Full cloud-to-Room journal restoration is not implemented.
Runtime location, Usage Access, file/photo pickers, microphone capture and live
browser playback remain unverified on a physical phone.

More uploaded data is a declared treatment in this modified app. These tests do
not establish permission abuse in the original FORJA APK or a prevalence rate
across other apps.
