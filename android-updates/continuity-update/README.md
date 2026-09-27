# v26 continuity delivery

This builder updates the exact delivered v25 APK, rather than the older root application sources. It accepts only SHA-256 `b569e767f88de7f3a7825580313efe4f0f9594a9340f994524e146bf566a0ecd` and requires the existing research application signing key. The output is `com.forja.app.research`, version `3.7-online.26`, code `56`.

## Build

1. Restore Android SDK 35/build-tools 35 and Gradle 8.10.2 from their official archives, plus packaging tools with `../cleanup-update/scripts/fetch_packaging_tools.py` and build the canonical `../cleanup-update` feature with Gradle 8.10.2 / JDK 17 / Android SDK 35:
   `:feature:assembleDebug :feature:testDebugUnitTest :feature:exportCompileClasspath`.
2. Set `FORJA_KEYSTORE_PASSWORD` in the build environment without logging it.
3. Run `python build.py --base /path/FORJA_v25_Somn.apk --tools /path/android-tools --compiled ../cleanup-update --keystore ../../app/debug.keystore --output /path/FORJA_v26_Continuitate.apk`.

The builder preserves every original asset and native library byte-for-byte, retains 20 DEX files, checks the signer, and only replaces DEX 4/16 version strings, DEX 22 feature implementation, and the manifest version/service entry. The only additional Android component is the unexported location foreground service `JourneyLocationService`; no new permission is requested. Existing sleep, consent, audio model, account, and onboarding hooks are verified against their retained bytecode ABI. No signing keys or APK files belong in Git.

## Organizer v4

`OrganizerLedger` stores owner-bound original identities, content versions, URI aliases, frozen selections, per-item leases, receipts, and filesystem intentions separately from temporary cloud copies. SQLite transactions prevent a replay or concurrent job from selecting the same leased original. Inventories use bounded gallery pages / a persistent SAF directory queue; selecting all is not silently capped at 15,000. Next N continues the same logical job and skips completed versions. Local analysis still synchronizes real metadata/progress; online mode additionally sends hash-verified bytes, source-bound extraction and an actual image preview.

The phone requests explicit source access and the Android gallery modification dialog. Server approval and a durable intention precede every file mutation. Gallery updates are verified by path/name/hash. SAF moves use owned hidden staging files, verify bytes, rename, verify the final identity, and only then remove the unchanged original. Cancellation/revocation permits read-only reconciliation and factual receipts; ambiguous provider outcomes preserve originals for review. A same-name or same-hash independent destination is never adopted as a successful move.

Temporary extracted content is discarded after acknowledged publication or expires within 24 hours, with lazy pruning on ledger access. Durable identities, operation receipts and counts remain. Remote manual jobs upload actual copies and wait for an explicit website destination/approval; they do not silently invoke online AI.

## Validation

The feature test suite includes real Robolectric SQLite close/reopen, duplicate content with distinct originals, lease isolation, injected transaction failures, unchanged-timestamp content changes, and a 15,037-file inventory. Provider reconciliation policies cover unavailable versus absent sources and unowned destinations. The APK verifier checks all new static calls against retained Android/dependency bytecode, exact manifest scope, unchanged archive entries, original signing certificate, and retained sleep model checksum.

A passing build and static verification do not establish end-to-end account, provider, camera/gallery permission, cloud inference or physical-device behavior. Record emulator/device evidence separately with the delivery report.
