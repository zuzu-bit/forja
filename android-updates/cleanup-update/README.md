# FORJA feature module — current v24 source

For the current build use [../visual-update/README.md](../visual-update/README.md).
The v24 update requires the signed v23 APK and preserves all other delivered
features. The sections below retain historical v13–v20 implementation notes;
their packaging commands and old server versions are not the current release.

# FORJA cleanup update 3.7-online.17

This module updates the checksum-pinned delivered v13 APK and preserves the rest
of that application. The full modern Android project was not available in the
restored checkout. Do not build the older parent `app` project as a replacement:
it lacks previously delivered features.


## New in v17: scheduled local analysis

The new **Analizează-mi automat** entry opts this phone into selected photo/folder
sources once. The website controls 25/50 items per enabled source, weekdays, up to
four wall-clock times, timezone and unmetered networking. Server v6 with
`cleanup_schedule:1` is required; deploy the v17 Windows server package first.
The previous manual v16 workflow below remains available, with 25 added to scope.

WorkManager polls every 15 minutes and schedules additional delayed checks for
known times. Android timing is approximate and subject to battery/network limits.
A server run ID and grant/revision bind each occurrence. Overdue starts (>2 h)
are skipped, DST gaps are skipped and repeated wall times run only once.

Before analysis, the worker atomically persists the exact inventory and creates
one idempotent upload batch for the run. Scanning uses `CleanupScanInput`, so
retries use that inventory rather than reselecting recent files. Local AI cache
and saved reports let interrupted work resume. Uploads run concurrently, verify
receipts and send `X-Cleanup-Run`; server revocation blocks further scheduled
uploads. SQLite upgrades from v16 preserve all manual batches. A shared mutex
prevents overlapping model/cache work and phone mutations during automatic runs.

Automatic workers never call move, trash or delete operations. Saved reports,
including exact duplicate keeper/hash references, open in the existing reviewed
phone-mutation flow. After applying a report, that archived source is marked
reviewed so stale proposals cannot be reapplied from the automatic entry.
Local report files use an AtomicFile and a shared read/write lock. Web reports
contain bounded proposal text, not document excerpts, and expire after 24 h.
Original upload copies retain their independent server expiry.

The APK remains a constrained update on the exact v13 base. Additional
compile-only `CleanReport`, `DuplicateGroup` and `SimilarGroup` constructor/getter
ABIs are checked against that base. No ABI stubs enter the installed DEX.

## What v16 changes

The default cleanup screen is now a complete phone organization flow:

1. Choose Photos or a writable phone document folder and latest 50/100/200/all.
2. `Analizează și trimite pe site` explicitly sends every original in that exact
   analysis selection to the signed-in user's FORJA account while AI analysis
   runs locally. Uncheck the visible web-copy option for local-only analysis.
3. Review original names, current paths, proposed real destinations, and reasons.
   Edit destinations or deselect items before applying.
4. `Mută în Galeria telefonului` / `Mută în Fișierele telefonului` performs actual
   MediaStore/SAF moves. Android's write authorization is requested where needed.
   The receipt gate protects pending uploads from moved/deleted SAF source URIs.
5. Confirmed move/copy/failure counts, real destination links, and undo remain
   available. Copies left behind by a provider are explicitly not counted as
   cleaned originals. Moving alone does not claim to free bytes.

The v13 placement builder excluded all files already under a FORJA directory.
The new planner builds from local content findings directly, including those
files. Gallery topics become distinctive physical album names in `Pictures/`;
subtopics are joined with ` · ` because gallery apps commonly display the leaf
folder. Documents use semantic subfolders inside the chosen phone folder, without
an extra FORJA container. Existing destination paths produce no repeat move.

Duplicate selection starts empty. The preserved engine rechecks content hashes
and retains an original from every group. Gallery duplicates use Android's trash
flow, with undo; document duplicates move to a visible review folder. Space is
not reported as freed until files actually leave device storage.

## Exact-selection uploads and 24-hour copies

A coroutine context hook on CleanupCatalog's final inventory persists a complete
SQLite batch before local analysis proceeds. It runs even for cached analysis
results. This upload path never rescans the phone, never chooses a new latest-N
list, and has no 25-item round limit. Photos and documents use the same durable
queue. A new analysis creates fresh UUIDs, so prior expired web copies do not
suppress the new session's uploads.

The immediate foreground coroutine and a WorkManager recovery request share a
mutex. Android can defer/stop background jobs; retry resumes the exact pending
IDs. An in-progress request is cancelled on owner change or explicit cancellation.
A request authenticates with the current Firebase token and verifies the original
UID. No server credentials are embedded. Every upload verifies SHA-256, byte
length, item ID, and the server's retention timestamps before counting success.
Retries preserve the same UUID and server expiry. Originals never enter a
permanent app-private copy directory. Only bounded transfer buffers and optional
thumbnails are generated.

Starting an integrated online cleanup replaces enabled legacy v15 periodic
rescans. The dedicated cleanup device supports successive photo and document
batches. Each batch independently records its exact authorized inventory. Web
pause controls remain enforced. The UI shows confirmed/total counts, pending
errors, retry, and an explicit stop-transfer action that allows local-only work.

Uses the **existing v15 server contract**. Manual v16 uploads remain compatible with that contract. Scheduled v17 analysis
requires the new server deployment described above. The site is
`https://forja-insights.forja-22e7ea2d.workers.dev`. The live deployment was not
independently verified in this environment.

Each received server copy expires 24 hours after its first accepted upload;
server alarms remove both original and thumbnail independently of the phone.
Limits remain **25 MiB per file, 500 live copies, 512 MiB per account**, and 1500
accepted uploads per day. Every analyzed file is queued, but oversized or
inaccessible files are explicitly reported as not sent. Quota/network failures
retain pending entries for retry; there is no false `all received` result.
Changing these limits would require a separate server deployment. Gallery and
folder inventory retain the disclosed 15,000-entry and 8-level bounds.

## Build and packaging

Use complete JDK 17, Gradle 8.10.2, Android platform/build tools 35, R8/D8 8.7.18,
smali/baksmali 2.5.2, and Python. Dependencies are pinned in the Gradle project.

```sh
gradle assembleDebug testDebugUnitTest exportCompileClasspath
python scripts/fetch_packaging_tools.py --tools /path/tools
python scripts/package_update.py --base /path/FORJA-v13.apk --tools /path/tools --output /path/FORJA_Research.apk
```

Packaging uses a fresh system temporary directory and prints its path. Use
`--work-dir /new/local/path` to choose another new directory outside a synchronized
workspace. Keep its `new-smali` directory for `scripts/check_dependency_calls.py`;
do not reuse generated trees from a previous build.

Base APK SHA-256:
`8edc341e8699b32945c63bbee65c1b3c379f11fe4b936c2575c8c1d8916ee550`

`ExistingApi.kt` and `LegacyApi.kt` are compile-only ABI declarations. Packaging
excludes them and validates all calls against the original retained classes.
The original CleanupScan and CleanupOperations implementations remain in DEX 13;
new code occupies DEX 22. Only version metadata changes in DEX 4 and 16. All 18
unrelated DEX and original resources/assets are preserved byte-for-byte. Signing
uses the established parent `app/debug.keystore`; the signer must match v15.

## Verification and remaining device checks

JUnit tests exercise full 200-file batches, mixed photo/document selections,
interrupted transfer resume, idempotent IDs, expired-file reanalysis, receipt
rejection, owner cancellation, per-file failures, path validation, legacy FORJA
folder migration, and repeat-move prevention, alongside the retained analysis
and HTTP wire tests. v17 adds report serialization tests that preserve exact
duplicate references and reject stale ones, bringing the Android JVM total to 31.
The server's 61 tests cover exact binary copies, account isolation, expiry,
retries, deletion, previews, audio compatibility, scheduling/DST, revocation,
idempotent claims and pause/resume. The Windows publisher has 7 controller tests;
the web form was checked with JSDOM for drafts, source restrictions, safe text
rendering and logout during an outstanding request.

No Android device/emulator is available here. APK build, signing, ABI and DEX
checks do not replace installation, Gallery/SAF provider behavior, runtime UI,
process restart, and a live authenticated upload test on a phone. Gallery moves
require Android 11+; older devices can use the writable folder workflow. Gallery
apps differ in album display and refresh timing. Restore/move counts come from
provider-confirmed outcomes, not the analysis screen.
