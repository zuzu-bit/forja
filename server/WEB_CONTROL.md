# FORJA web control and in-app campaigns

The client controls their own paired phone from their verified Firebase account at
`/insights`. The Worker always replaces the internal owner header and binds every
Durable Object to a single UID.

## Phone authorization and operation

- On the phone, open **Profil → Permisiuni și sincronizare → Control din contul web**.
  Grant microphone/notification access and explicitly authorize the own-account web
  control once. Location and app usage remain separately selected categories.
- Web controls provide Start now, Stop, or a scheduled X–Y interval of 1–60 minutes
  within the next 24 hours. The phone polls while FORJA is open, while recording,
  or during explicitly enabled background collection. There is no push wakeup or
  boot receiver. The app must be visible when microphone recording starts.
- The start command expires 30 seconds after its scheduled beginning. A delayed
  delivery keeps the original end time. A missed window never starts later.
- The Android microphone indicator and recording notification with Stop remain
  visible during recording. Starting from a closed app is not supported.
- A new local authorization uses a new grant UUID, invalidating older commands
  and collection grants. Logout/revocation invalidates the recording epoch,
  stops recording and cancels pending network work.
- Stop is a queued request until acknowledged by the phone. Offline phones are
  displayed as unavailable; the web UI never presents a click as an executed action.
- Device collection and server intake are distinct controls. Intake pause rejects
  new `/v2` writes with 423; existing data can still be read/deleted. Fitness
  journal synchronization has its own existing consent and Firestore path.

## Complete audio uploads

`TimedRecordingService` records one private AAC/M4A file, bounded to 60 minutes and
30 MiB. WorkManager automatically uploads it when connected, under its original
account and recording epoch. There are at most five local recording files. Failed
copies can be retried or deleted in the pairing settings. Deleting the local copy
does not delete a previously received server copy.

`POST /v2/sessions` with `mode: recording` requires audio-only consent.
`POST /v2/sessions/{id}/recording` accepts `audio/mp4` and millisecond headers
`X-Recorded-From` / `X-Recorded-To`. The server validates the container, audio-only
AAC track, self-contained references and duration against the declared interval.
Duplicate uploads return the same receipt; conflicting content returns 409.
The app deletes its file only after validating the receipt's byte count and SHA-256.
Explicit web deletion leaves a seven-day tombstone to prevent automatic retries
from recreating that session. Session retention remains 24 hours after upload;
this is separate from the recording duration.

## Campaigns

The editor supports AI drafts, manual edits, draft storage, explicit publication,
withdrawal and deletion. AI sees only the creator's brief and sponsor. It does not
receive phone files, coordinates, health journals or audio for ad generation.
Published campaigns are labeled **Publicitate** and shown only in the same account's
home carousel when its optional ads preference is enabled. The app refreshes the
feed every 30 seconds while visible. There is no global or cross-account ad feed.

## Deployment and verification

Deploy the APK and Insights Worker together. An older live Worker does not provide
phone controls or complete recording uploads; the app retains pending recordings
and does not fall back to five-second audio chunks.

```sh
cd server
npm ci
npm test
npx wrangler deploy --config wrangler.insights.toml --dry-run
# Requires the project's authenticated Cloudflare account:
npx wrangler deploy --config wrangler.insights.toml
```

The Worker uses the existing Insights Durable Object, R2 bucket and Workers AI
bindings. No new secrets are embedded in the APK. Validate on a real Android device
before production rollout: pairing, visible start, lock screen continuation,
notification Stop, web Stop/acknowledgement, scheduled missed interval, logout,
revocation, offline retry, intake pause, and same-account campaign publication.
