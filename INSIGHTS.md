# FORJA Insights

An online data/recommendation panel for the existing FORJA account. The Android
copy still starts at normal login → Dashboard. **Profil → Panoul meu online**
opens the portal; **Profil → Datele mele** uploads selected measurement sessions
using the signed-in Firebase account. No pairing token or editable server URL
is required for these online uploads.

## Services

- Firebase Authentication / Firestore: existing `forja-65093` project, real FORJA
  accounts and private fitness journals.
- Fitness analysis Worker: existing `forja-api` service, unchanged by this work.
- New portal / receiver: `forja-insights`, configured in
  `server/wrangler.insights.toml`; public address
  `https://forja-insights.forja-22e7ea2d.workers.dev/insights`.
- Account metadata: SQLite-backed Durable Object `InsightsAccount` per verified
  Firebase UID. Selected binaries: dedicated private `forja-insights-data` R2 bucket,
  inside an `_insights/{uid}/{session}/` prefix. This bucket is not bound to the
  legacy admin Worker.

The web panel is account-scoped: signing in shows that account's data. It is
not a global list of every FORJA user's private data, and the old system admin
key is not used. Public pages contain no private records. Browser tokens and
refresh tokens stay in memory; logout aborts requests, stops playback and clears
rendered data. Protected requests require a verified Firebase RS256 JWT; the
Worker overwrites the internal owner header from its verified subject.

## What the panel shows

- Fitness journals from the preceding seven days: sleep, activities and meals
  read through Firestore using the viewer's own token; up to 100/category.
- Active phone-data sessions, category selection, receipt time and byte counts.
- Timestamped coordinates, estimated dwell intervals and a relative route sketch
  without external map tiles. This does not identify home or work automatically.
- Foreground app durations and activity-resume counts from the selected window.
- Original selected files/photos, byte hashes, download, photo preview and
  individual WAV playback. Explicit **Ascultă clipurile noi** plays arriving
  audio clips; it cannot start the phone microphone.
- Session deletion and expiry. Session metadata and binaries are inaccessible
  after 24 hours, with Durable Object alarm cleanup. Deletion also lists the
  session's prefix to remove binaries left by interrupted uploads.

Sessions poll every 15 seconds while the page is visible; fitness journals are
refreshed approximately once per minute or on manual Refresh. Open session
views have an explicit refresh control to avoid interrupting file/audio use.

Existing Android session limits remain: 300 location fixes / 15 minutes, last
24 hours of available app-use events, five selected files/photos of up to 5 MiB
apiece, and up to 24 microphone clips in a two-minute visible session. The online
receiver also enforces 20 active sessions/account, 32 MiB/session, WAV structure,
category flags and duplicate item rejection. Android phone collectors remain
user-started and stop on Activity pause; original fitness services are separate.

## Recommendations

Text model: `@cf/meta/llama-3.3-70b-instruct-fp8-fast` on the user's existing
Cloudflare Workers AI account. Image descriptions:
`@cf/meta/llama-3.2-11b-vision-instruct`. No new external model API key is needed.

**Generează recomandări** sends bounded derived evidence: sleep/activity
summaries, meal names, observed stop counts, app-use summaries and previously
requested file interpretations. It does not send the raw coordinate list or
audio clips to the recommendation model. The model proposes comfort, movement,
food, outings, movies, games or activities, with confidence and evidence IDs.
Generation uses JSON mode with a schema that enumerates the supplied evidence
IDs. Independent server validation rejects unsupported citations and malformed
outputs; both object and string model responses are supported. UI text
uses textContent; AI output is not executable markup or a URL. Search links are
constructed by the application and are labeled as exploration, not verified
products, stock, prices or venue availability.

File analysis requires an explicit action and confirmation for that item.
Supported photos: JPEG/PNG/WebP. Supported text: plain text, Markdown, CSV and
JSON, up to 64 KiB, with up to 16,000 decoded characters submitted. Other formats
are downloadable but not parsed. The file interpretation is labeled as AI output
to confirm, not an established preference. The AI has no tools for opening links,
running code, remotely controlling the phone or making purchases. Uploaded text
is treated as untrusted evidence, not instructions. The system prompt excludes
identity/sensitive-trait inference and medical diagnoses; recommendation fields
must cite supplied evidence. A model can still misinterpret a file or evidence.

Calls are explicit, limited to 30 per account per UTC day and spaced by at least
10 seconds. No subscription or paid plan upgrade is made. A provider failure
returns an error instead of fabricated recommendations. No specific pillow is
claimed to treat poor sleep; missing activity records do not prove inactivity.

## Build and deployment

`npm ci` and `npm test` in `server/` run the new tests. The shared schema remains
compatible with `node --test research-server/server.test.mjs`.

`.github/workflows/insights-deploy.yml` tests and deploys **only `forja-insights`**
on server changes pushed to `research/consented-data-export`. It uses the
repository's existing `CLOUDFLARE_API_TOKEN` and optional `CLOUDFLARE_ACCOUNT_ID`.
It creates the dedicated private R2 bucket if absent and adds a SQLite Durable
Object migration. It does not deploy the old fitness Worker, publish APK releases or
merge main. Keep this workflow/branch for future portal updates; the old main
branch's fitness build is independent.

The mobile artifact is `com.forja.app.research`, version `3.7-online.4`, label
FORJA, using the existing signing key. `clean assembleResearch
testResearchUnitTest` builds it. See `INSIGHTS_VALIDATION.md` for actual test and
public deployment results.

Primary platform references:
- [Durable Object concurrency](https://developers.cloudflare.com/durable-objects/api/state/)
- [R2 Worker API](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/)
- [Structured model output](https://developers.cloudflare.com/workers-ai/features/json-mode/)
- [Text model](https://developers.cloudflare.com/workers-ai/models/llama-3.3-70b-instruct-fp8-fast/)
- [Vision model](https://developers.cloudflare.com/workers-ai/models/llama-3.2-11b-vision-instruct/)
- [Firestore queries](https://firebase.google.com/docs/firestore/reference/rest/v1/projects.databases.documents/runQuery)
