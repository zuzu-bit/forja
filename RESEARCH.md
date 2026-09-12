# FORJA controlled data-export copy

This copy adds a manual export path for measuring exactly which selected app data
reaches a server. It starts from release commit
`e22f5c89913248aa9bf0719cf62c40cd5de2bbc5` (`apk-latest` at inspection).
The build installs as **FORJA Research**, package `com.forja.app.research`, version
`3.7-research`, alongside the original app with a separate Android data sandbox.
It uses the repository's existing debug signing key and is a lab build.

## What can be sent

All categories start unchecked. Synthetic example data is enabled by default.
Local mode reads only the research copy's own database; it cannot read the
original FORJA installation's private database.

| Category | Exported fields |
| --- | --- |
| Sleep | Start/end times, score, movements, deep/light/REM minutes, event count |
| Meals | Time, meal type, calories, protein, carbohydrates, fat, grams |
| Activities | Start/end times, activity type, distance, duration, calories |
| Diagnostics | Android API level; camera, microphone, precise location, background location and images permission states |

Local record selection covers today and the preceding six local calendar days,
up to the latest 50 rows per category. Diagnostics is a current snapshot; in
synthetic mode its values are also synthetic. Unknown permissions on older
Android versions appear false. The export adds no Android permissions and never
requests one. Raw audio, transcripts, photos, GPS routes, food names/free text,
contacts, messages, installed-app lists, credentials and device identifiers are
not part of the export schema.

The screen previews the exact JSON body. A separate, unchecked consent box is
required for each upload. Changing the categories, mode, destination or pairing
token invalidates the preview and consent. There is no automatic retry or
scheduled research export. A successful response is checked against the exact
sent bytes using SHA-256, byte length and run ID. This shows what this receiver
reported receiving; an untrusted server could fabricate a receipt, so controlled
experiments should also retain its stored payload as independent evidence.

## Build and test

Prerequisites: JDK 17, Android SDK platform 35/build tools 35, Node.js 22 or newer.

```sh
node --test research-server/server.test.mjs
./gradlew assembleResearch
adb install app/build/outputs/apk/research/app-research.apk
```

The `Research build` GitHub Actions workflow runs the same tests and creates an
APK artifact on research branches. It does not deploy the existing Worker or
publish to the `apk-latest` release.

If the existing KSP version reports duplicate generated Room classes from a
`byRounds` directory, remove the temporary
`app/build/generated/ksp/research/java/byRounds` directory and rerun the same
build command. This was required in the local validation environment; see the
validation record for details.

## Run the separate receiver

From `research-server`, generate a token and start the dependency-free receiver:

```sh
cd research-server
export FORJA_RESEARCH_TOKEN="$(node -e 'process.stdout.write(require("node:crypto").randomBytes(32).toString("hex"))')"
node server.mjs
```

Enter the same token into the research screen. To view it locally when needed,
use `printenv FORJA_RESEARCH_TOKEN` in the terminal where it was set. Do not put it
in a source file or public issue.

Default receiver: `127.0.0.1:8787`; default storage: `research-server/data/` when
started there. The Android emulator reaches it at `http://10.0.2.2:8787`.
Optional settings: `FORJA_RESEARCH_HOST`, `FORJA_RESEARCH_PORT`,
`FORJA_RESEARCH_DATA_DIR`. The server's files are local plaintext health data
with owner-only file permissions. Use a dedicated test machine and synthetic
records for the initial experiment.

For the export screen on a USB-connected physical test phone:

```sh
adb reverse tcp:8787 tcp:8787
```

Set the screen's server URL to `http://127.0.0.1:8787`. Remote endpoints require
HTTPS. This repository does not provision a remote host or TLS termination.

1. Leave synthetic mode enabled and select one or more categories.
2. Generate and inspect the JSON preview and destination.
3. Check the upload consent box and send once.
4. Record the run ID, receipt ID, SHA-256 and counts shown by the app.
5. Compare the stored receiver payload with the preview, then delete the upload.

The screen can delete its latest receipt while that Activity remains alive.
Earlier receipt IDs can be deleted using the API. Closing or recreating the
Activity clears its in-memory token, preview, consent and receipt reference.
The receiver rejects expired records after 24 hours, removes expired files at
startup and checks for cleanup every minute while running. Files can remain on
disk while the server is stopped. If a network response is lost, an upload may
have arrived even though the app cannot confirm it; inspect the stored run ID
before deliberately retrying.

## Receiver contract

`GET /health` returns only status and schema version. All other supported routes
require `Authorization: Bearer <pairing token>`.

| Method and route | Behavior |
| --- | --- |
| `POST /v1/research-export` | Validate schema, consent and limits; store exact body; return receipt |
| `GET /v1/research-export/:receiptId` | Return the original JSON body until expiry |
| `DELETE /v1/research-export/:receiptId` | Delete the stored export |

The request schema and numeric limits are in `research-server/server.mjs`;
`server.test.mjs` contains a complete synthetic example. The receiver caps
requests at 128 KiB and 50 records per category, rejects unknown fields, and
requires the data categories to match the category consent flags exactly.
Consent flags are client assertions, not cryptographic proof of human consent.
The pairing token is for one controlled lab; there is no multi-user access model.
Request payloads and tokens are not written to server logs.

## Isolation from the original backend

The research variant forces the original `FORJA_API_URL` to an empty string,
disabling its Worker audio-upload paths even if CI supplies that environment
variable. Its Firebase configuration is the dummy project `demo-forja-research`.
Authentication and Firestore route to local emulator ports 9099 and 8080 on
`10.0.2.2`. Production Firebase credentials and accounts are not used by this
variant. Startup location registration, media refresh, nudges and focus restart
are skipped; the boot receiver is disabled.

The manual synthetic export screen does not need Firebase emulators. To use
the original FORJA account and sync features inside an Android emulator, start
the Firebase CLI's local suite from the repository root:

```sh
firebase emulators:start --project demo-forja-research --config firebase.research.json --only auth,firestore
```

Create a synthetic test account. Firebase routing in this first version is
specifically configured for an Android emulator, not a physical phone. Opening
the original FORJA features may still call public providers such as map tiles
and food lookup APIs. Existing sync to the local Firebase emulator is separate
from the new manually selected export. This is not an app-wide network firewall.

## How to use this in the study

Treat this as a controlled single-app case study with known code and receiver
state. Compare category-off, preview-without-send, explicit-send, deletion and
permission-denied conditions using synthetic data. Measure selected fields,
bytes received, API calls, timestamps and receiver records per run.

An Android permission grant permits a class of platform access; it does not by
itself establish consent for server transfer. The `INTERNET` permission does not
show a runtime consent dialog. More uploaded fields alone do not establish
permission abuse. The modified version is a declared experimental treatment,
not evidence that the original app already had that behavior. It cannot provide
a prevalence estimate for 100 health apps or establish a first-in-Europe claim.

See `RESEARCH_VALIDATION.md` for the checks actually completed and their limits.
