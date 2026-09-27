# Somn și audio — protocol v1

One visible, phone-authorized microphone session is used for both immediate audio and a scheduled sleep/nap interval. Generic legacy audio commands remain compatible. The new capability must be advertised by the server (`sleep_audio: 1` in phone-sync) and the phone (`sleep_capable: true`). No request alone proves that capture started: phone acknowledgement/status is authoritative.

## Commands and consent

`POST /insights/api/phones/{device}/command` keeps its revision and ready-session checks. Sleep start adds `purpose: "sleep"`, a stable UUID `sleep_id`, and `minutes: 1..720`. Optional `start_at` and `stop_at` are epoch milliseconds, must match `minutes`, and must start within the next 24 hours. The response includes `chunk_minutes: 2`. A future pending command can be edited with a fresh command ID, current revision and `replace_command` containing the previous command ID. Editing an already-started interval is rejected.

`POST /insights/api/phone-sync` accepts `sleep_capable`, `sleep_analysis_allowed` and optional `sleep_session: {id,state,planned_stop_at}`. State is one of `recording`, `uploading`, `analyzing`, `complete`, `failed`. These fields do not bypass `audio_allowed`, `audio_ready`, `audio_session`, owner or freshness checks. Disarming still invalidates the old ready-session and queued command. Sleep and generic Stop must stop an active sleep capture on the phone.

## Upload and report lifecycle

All `/v2/sleep/*` routes require the same verified Firebase account as existing insights APIs.

1. Create with `POST /v2/sleep/sessions`: `{id,device_id,started_at,planned_stop_at,analysis_consent}`. Metadata is immutable on duplicate IDs. Actual recording should have started before this status is reported.
2. Before each existing recording upload, reserve with `POST /v2/sleep/sessions/{id}/reserve`: `{recording_session_id}`. This lets the unmodified existing recording upload use a separate bounded sleep pool.
3. Upload the complete AAC/M4A with the existing `/v2/sessions` recording API. Nominal chunks are two minutes; final chunks may be shorter. Each uploaded recording remains byte-for-byte playable.
4. Attach with `POST /v2/sleep/sessions/{id}/chunks`: `{recording_session_id, acoustic?}`. Server-side ASR executes outside the Durable Object concurrency lock. A stored lease prevents duplicate AI work. `200` means terminal processing state; `202` means another request is still processing and should be polled/retried. `503` persists the failed analysis, which can be retried with the same IDs and bytes. Pending responses include `retry_after_ms`; an expired lease is surfaced as `pending` with `retryable: true`. A lease expires after 180 seconds; provider deadlines total at most 90 seconds. Five inference attempts per chunk are permitted.
5. Finish with `POST /v2/sleep/sessions/{id}/finish`: `{ended_at}`. Repeating the exact end is idempotent; changing an already-recorded end is rejected. Uploads can finish afterward if their recorded interval belongs to this session.
6. `GET /v2/sleep/sessions` returns summaries. `GET /v2/sleep/sessions/{id}` returns a report with 25 chunks and `next_cursor`; append `?cursor=<last chunk ID>` for the next page. Reports show received and analyzed duration, pending/failed/skipped counts and actual acoustic coverage.

Capacity: up to 365 unexpired sleep chunk reservations and 384 MiB of sleep audio per account. Generic sessions keep their existing limit of 20. A sleep chunk is limited to five minutes and 2 MiB; AAC metadata and timestamps are validated. Overlapping attachments and recording reuse across sleep sessions are rejected.

AI uses the server's existing Workers AI binding: Whisper Turbo for transcription and Llama for extractive, evidence-linked topics. Transcripts remain explicitly unverified. Provider failures are never represented as silence. The ASR model cannot classify snoring, speakers, sleep stages or health conditions.

## Acoustic observations from the phone

Optional `acoustic` includes:

- `status`: `complete` or `unavailable`
- `model: "yamnet"`, `model_version: "yamnet-audioset-1"`
- `model_sha256`: the exact on-device artifact hash (required for completed inference)
- `analyzed_ms` and nonoverlapping `analyzed_ranges: [{start_ms,end_ms}]`
- `events: [{start_ms,end_ms,kind:"possible_snoring",score}]`
- For unavailable inference only, `reason`: `decode_failed`, `model_unavailable`, `inference_failed` or `cancelled`

Offsets refer to the complete uploaded recording. Events must be inside actual analyzed coverage; scores are finite 0..1 and are **not** interpreted as calibrated probabilities. The server labels these observations as `source: "on_device"`. Missing/unavailable inference produces a null event count, not a fabricated zero. Inference with actual coverage and no detections can report zero, scoped to that coverage. Unavailable observations may later be replaced by a genuine completed result; an existing completed result is immutable.

## Retention and removal

Audio expires 24 hours after its existing session creation/upload lifecycle. Reports begin with a 24-hour lifetime and may extend to 24 hours after the reported end, capped at 36 hours from report creation. Deleted reports receive tombstones; in-flight inference cannot restore them. Report deletion leaves the separately controlled audio sessions available until their expiry or explicit deletion. AI opt-out increments a consent epoch and discards in-flight results even if the option is re-enabled before inference returns. Deleting or expiring the source recording also prevents new in-flight derived text from being committed.

## Validation

`npm run test:server` includes bounded real M4A fixture validation, owner/grant/session gates, upload and AI retry idempotence, inference outside the account lock, in-flight revocation/deletion, daytime/future schedule editing, pool limits, actual acoustic coverage and failure states. AI responses are mocked in tests; these checks do not establish real-world transcription or snoring accuracy.
