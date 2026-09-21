# FORJA v20 — organization from laptop

Deploy Worker v8 (`organizer:1`) and install APK 3.7-online.20 (code 50).
The website is https://forja-insights.forja-22e7ea2d.workers.dev.

On the phone, open Cleanup → Configurare: Somn, audio și fișiere. This also uses
the existing Profile → Control din contul web activity. Select gallery access
and/or one writable document tree with persisted Android permission. Explicitly
allow requested/scheduled analysis and upload. An additional choice authorizes
applying original-file moves subsequently approved in the owner's website.
Android microphone permission is shared with Sleep and is only requested if
missing. Sleep permission alone does not enable remote microphone control or
file upload. The visible v19 audio readiness service and notification remain.

On the website, Poze și documente → Organizează telefonul din laptop:
- Request 50, 100, a custom 1–15,000 count, or all authorized items per source.
- Filter a relative gallery path / document subfolder and an inclusive date
  interval (browser local timezone). Subfolders and unmetered transfer are
  explicit choices. Photo date = capture date, fallback modified date;
  documents use modification date. Unknown dates are omitted with date filters.
- Folder/date filters run during inventory, before count limits. Folder path
  matching uses segment boundaries. Work inventories up to 15,000 matching
  files/source and a document depth of eight. “All” is bounded and disclosed.
- Requests are durable, account/grant-bound and idempotent. They are picked up
  approximately every 15 seconds while the app is visible and by WorkManager
  approximately every 15 minutes in the background. Android, battery and network
  can delay work. This is not push delivery and cannot defeat a force-stop.
- Scheduled 25/50 runs remain supported. Schedules use their existing 2-hour
  missed-slot window; on-demand requests may start until their 24-hour expiry.
- Analysis and exact-inventory uploads run concurrently. Local OCR/semantic
  findings, bounded excerpts and IDs are paginated on the website. Metadata,
  excerpts, and plans expire at run creation + 24 hours. Uploaded originals
  retain their independent receipt + 24-hour expiry. Large inventories are
  paginated in storage; expiry deletes in bounded batches.

The cloud copy quota remains 500 files / 512 MiB/account, 25 MiB/file, with
existing rate limits. “All” never means unlimited uploaded storage. The website
and phone distinguish analyzed count from confirmed copy count and display
pending/blocked transfers. Original bytes are not silently downscaled.

Local analysis remains the default. Optional online analysis explicitly sends
selected existing snippets and photo thumbnails to Cloudflare Workers AI:
Llama 3.3 70B for organization, Llama 3.2 11B Vision for images. One batch has
1–5 selected items; a shared budget allows 30 inferences/day and 10 seconds
between requests. One inference per image thumbnail plus one grouping inference
is reserved before starting. Low-evidence files go to “De verificat”. Model
outputs are drafts with bounded paths, known IDs and reasons, never commands.
No provider key is placed in the APK or browser. Models:
https://developers.cloudflare.com/workers-ai/models/llama-3.3-70b-instruct-fp8-fast/
https://developers.cloudflare.com/workers-ai/models/llama-3.2-11b-vision-instruct/

Approve separate gallery/document plans (up to 200 items; current UI selects
one page of 25 at a time). Every approved item requires a live received copy
bound to that run, and carries its confirmed SHA-256. The phone resolves IDs
only through its frozen local inventory. It checks the same owner, grant,
expiry, server approval and unchanged original hash before each mutation.
Document plans run through the existing SAF engine in the authorized tree.
Gallery plans appear in Cleanup and use Android's MediaStore write consent
before applying. Actual MediaStore/SAF original moves, copy-only provider results,
skips and errors are reported separately. No automatic deletion is added.
Journal/undo remain on the phone; gallery-backed file indexing is refreshed.
The server locks a plan in applying state before the first mutation. A local
checkpoint is written after each result. Interrupted applying plans are not
blindly replayed; they require journal review/new analysis. Counts on an abrupt
process death reflect confirmed checkpoints, not an unsupported success claim.
Cancel is allowed before acquisition, never during applying. Intake pause and
phone-side revoke block subsequent operations; an individual provider operation
already in progress can finish before the stop is observed.

Validation is automated/local: Kotlin unit tests, server/ownership/expiry/AI
contract tests, Worker dry-run, Android DEX/ABI/signature checks, and DOM flows.
No attached Android device was available and the live Cloudflare deployment
was not verified here. Run PUBLICA_FORJA.cmd to deploy into the existing account.
