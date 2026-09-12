> Current revision: see [INSIGHTS.md](INSIGHTS.md). Version 3.7-online.4 adds the online account-based receiver and AI panel. The older pairing-token receiver below remains a local lab tool.

# FORJA · online accounts and optional phone-data sessions

This copy adds visible phone-data sessions and reviewed uploads for measuring
exactly which selected data reaches a server. It starts from release commit
`e22f5c89913248aa9bf0719cf62c40cd5de2bbc5` (`apk-latest` at inspection).
The build installs as **FORJA**, package `com.forja.app.research`, version
`3.7-online.3`, and updates the previous research copy while retaining its separate
Android data sandbox. It now uses the real FORJA online account and journal services.
It uses the repository's existing debug signing key and is a lab build.

## Normal app entry

The launcher is the original `MainActivity`. Signed-out users see the normal
FORJA login/registration screen; signed-in users open the existing fitness
Dashboard. This copy skips the onboarding film and automatic permission-intro
page, including after logout. Android permissions are still requested by the
features that need them.

The phone-data panel is optional, under **Profil → Datele mele**. Its four
collectors, reviewed uploads and server settings remain there. The older
health-metric export is available inside that panel.

| Card | What it does | Collection boundary |
| --- | --- | --- |
| Location & places | Records timestamped coordinates and accuracy; estimates observed time around stops | Explicit start; screen stays open; up to 15 minutes/300 samples |
| App activity | Reads available foreground activity events for the preceding 24 hours and estimates per-app duration | Android Usage Access plus explicit Read action; at most 100 apps |
| Files & photos | Uploads original contents of items chosen with Android's document/photo pickers | Up to five items total, 5 MiB each; reviewed before upload |
| Live audio | Sends microphone WAV clips to the selected receiver | Explicit confirmation and microphone permission; about five seconds per clip, up to two minutes; visible Stop control |

Location and microphone collection stop in `onPause`, including when leaving
the app or locking the phone. No remote command can start these collectors.
No research background service is added. Location/app metrics and selected
files remain in memory until an explicit reviewed upload or until cleared.
Microphone clips are sent during the explicitly started live session.

Observed stops group consecutive fixes within 75 metres. Samples worse than
100 metres accuracy, gaps exceeding two minutes, and stopping/restarting
collection break dwell estimates. A single fix contributes zero observed time.
This does not retrieve historical Google Maps/location history. App durations
are unions of available resumed/paused activity intervals within the selected
window. Missing boundary events, multiple instances of the same activity class,
and Android history retention can cause undercounts; split-screen apps can
have overlapping foreground time. Activity-start counts are resume events,
not a claim about distinct human app launches. No screen text or keystrokes are
read.

Photo/file contents are sent exactly as selected, including embedded metadata
such as EXIF location tags. There is no automatic file or photo scan. Android
permissions do not grant access to another app's private file sandbox.

Live audio is microphone input only, not call interception or other apps'
internal playback. It is near-live delivery of short clips, not a continuous
low-latency audio protocol. The current capture/upload loop can have gaps under
network congestion. A failed/interrupted upload can leave some verified clips
on the server. The phone's Uploads page and server viewer can delete the entire
session. The phone keeps session references/tokens only during that Activity's
lifetime; older sessions remain accessible in the authenticated server viewer.

## Inspect data and listen on the server

Run the receiver as described below, then open **http://127.0.0.1:8787/** on the
computer hosting it. Enter the same pairing token and click **Connect & refresh**.
The viewer lists sessions, readable stop/app-usage tables, selected-file
Downloads, and audio clip playback. **Listen to arriving clips** plays clips
received after listening starts. It cannot activate the phone microphone.
The page does not persist its pairing token in browser storage.

The `/v2/sessions` routes require the pairing token. Session creation carries
separate consent flags for location, app usage, files, photos and audio. The
receiver validates metric fields, coordinates, durations, WAV headers, item
sizes and sequence numbers; it rejects unselected categories. JSON metrics and
binary items each return SHA-256 and byte-count receipts. Limits are 32 MiB per
server session, 5 MiB per selected file/photo, and 24 bounded audio chunks.
Audio uploads close after three minutes server time. All session data expires
after 24 hours; cleanup runs while the receiver runs and again on startup.
The receiver is a single-operator lab tool, not a production multi-user service.

Build and test both sides:

```sh
node --test research-server/server.test.mjs
./gradlew assembleResearch testResearchUnitTest
```

This research branch disables KSP incremental processing after reproducible
shadow/generated-source collisions in the existing compiler plugin. All
compiler tasks still run; no test or compilation gate is skipped.

## Advanced health export

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

The advanced health screen previews the exact JSON body. A separate, unchecked consent box is
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

## Advanced health receiver contract

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

## Online accounts and backend connections

Revision 3 deliberately replaces the emulator-only account configuration at the
user's request. Firebase Authentication and Firestore now use the existing
`forja-65093` project. There are no `useEmulator` calls in app startup. The copy
uses the project's existing public client configuration; this does not provision
another Firebase Android app registration or change API-key restrictions.

`FORJA_API_URL` is the already-published service:
`https://forja-api.forja-22e7ea2d.workers.dev`.
Real FORJA email/password accounts work in this copy. Newly saved meal, sleep
and activity journal entries use the existing Firestore upload/cache path.
The original app does not implement a full cloud-to-Room restore; this revision
does not claim cross-device restoration of old local journals. Copy records and
sleep-recording keys have a persisted installation prefix so local row IDs cannot
overwrite records from the original app or a different copy installation.

These are two distinct integrations:

| Feature | Destination |
| --- | --- |
| Account and private fitness journals | Existing FORJA Firebase project |
| Meal/sleep analysis and sleep recordings | Existing FORJA Cloudflare Worker |
| Optional location/app-usage/files/photos/live-audio sessions in Datele mele | Separately configured research receiver, whose destination is shown before sending |

The existing Worker does **not** implement `/v2/sessions`. The optional receiver
has not been deployed publicly; its setup instructions below/above remain
necessary for those exports. An online FORJA login is not a pairing token for
that receiver. No Cloudflare service or Firebase rules were redeployed here.

Automatic startup sensor registration remains disabled for this copy. Presence
and background-location publishers are also disabled so signing in does not
start location sharing. The optional measurement sessions retain their visible
Start/Stop controls. The original user-started fitness services are separate:
for example, sleep recording can run as a foreground service and upload to FORJA;
the sleep screen now describes that online behavior before Start.

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
