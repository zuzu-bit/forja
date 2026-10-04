# Implemented organizer protocol 4

Protocol 2/3 routes remain available. Protocol 4 uses the authenticated owner's
existing account Durable Object; the Worker replaces the internal owner header.
All IDs are persisted UUID v4 values. Original identity is separate from SHA-256:
two originals with identical bytes remain separate items.

## Phone grant and job

`POST /v2/cleanup/devices/:device/grant` accepts `protocol:4` and optional
`sources:[{id,source,label,folder}]`. Source IDs identify currently granted phone
roots; `folder` is display metadata. Changing sources or move permission needs a
new grant ID. `organize:true` permits original moves, independently of a job's
automatic application preference.

`POST /v2/organizer/devices/:device/jobs` accepts:

```json
{
  "id": "UUID", "grant_id": "UUID", "revision": 1,
  "source": "files", "source_id": "UUID",
  "scope": {"folder": "", "recursive": true},
  "destination": "FORJA", "mode": "online",
  "auto_apply": false, "ai_consent": true,
  "preferences": {"protected_folders": []}
}
```

`scope.folder` and item folders are relative to the authorized root. Mode is
`manual`, `local`, or `online`; online requires separate AI consent. Jobs are
immutable in scope, destination, preferences, and grant. Same ID and specification
returns the existing job. Device schedule revision is checked only on creation.

`GET .../jobs` returns `{jobs}`; `GET .../jobs/:job` returns one job. `revision`
changes on control commands. `sync_revision` changes with progress/approvals/copy
availability, so phone and website can observe changes while command ID stays the
same. States are `awaiting_phone`, `running`, `partial`, `complete`, `paused`,
`cancelled`, or projected `access_needed`.

## Commands, inventory, and batches

| Route under `.../jobs/:job` | POST body / behavior |
| --- | --- |
| `/command` | `{request_id,revision,action,count}`; action `continue`, `pause`, `cancel`; count 0 means all remaining, otherwise 1–1000. Persist exact request for retry. |
| `/items` | `{grant_id,items,inventory_complete?,inventory_total?,inventory_unavailable?}`; at most 100 items/page. Complete marks the current selected snapshot, not all files on the device. |
| `/batch` | `{request_id,grant_id,limit,ids?}`; at most 100 items. Optional IDs select exact published originals. Returns `{id,ids,items,has_more,command_revision}`. |
| `/approve` | `{request_id,revision,items:[{id,destination}],confirm:true}`; explicit approval, at most 100 items. Destinations must remain under the job root. |
| `/receipts` | `{grant_id,receipts:[...]}`; at most 50. Transactional per-page updates. |

Inventory items have `id` (version-specific UUID), `original_id` (stable UUID),
`version` (SHA-256), `sha256`, `name`, `folder`, `media_type`, `bytes`, `modified_at`,
and optional source-bound `extraction`. Inventory limit is 100,000 versions/job,
with 10 active jobs/device and 100 retained jobs/account. There is no legacy
15,000-item truncation. `GET /items?after=:id` paginates 50 items; `?ids=a,b` loads
up to five known items.

Unreadable originals before hashing are represented only by final-page
`inventory_unavailable` (0–`inventory_total`, with `inventory_complete:true`).
No item/hash is invented; the job remains partial and exposes the separate count.

Commands expose `status:pending|running|complete|paused|cancelled`, `selected`,
and `finished`. A completed finite command permits the next N while preserving
the same job and unfinished inventory. A paused command invalidates unstarted
move approvals; manual approval must be renewed after resuming.

## Actual copies and durable move receipts

Existing `PUT /v2/files/:copy` uploads full bytes with the normal source, hash,
MIME, and name headers, plus `x-organizer-job`, `x-organizer-item`, `x-original-id`,
and `x-original-version`. These are exclusive with `x-cleanup-run`. The server
verifies the actual byte hash and binds a copy only to the selected original.
Metadata alone never sets `received:true`. Existing limits remain 25 MiB/file,
500 retained copies, and 512 MiB/account; each received copy expires after 24 h.
Before an acknowledged physical intent, a ready item may renew an expired copy
using a new persisted copy UUID and the same original/version/hash. PUT itself
records upload; do not regress ready with old upload-state receipts. Re-fetch the
item and send ready with its current destination/new file ID. A begun/ambiguous
move cannot bind another copy. Explicit destination changes invalidate previous
ready receipts and reject stale destinations.

Receipts contain `{id,operation_id,state,source_sha256}` and optional
`target_sha256,destination,file_id,reason,message`. States are `analyzed`,
`upload_pending`, `uploaded`, `ready`, `applying`, `moved`,
`copied_pending_removal`, `needs_review`, `failed_retryable`, `skipped`.

- Persist one operation ID through a move attempt and reconciliation.
- `ready` needs a current verified copy, current move permission and explicit
  approval or the job's automatic grant. Local mode instead requires a prior
  same-hash `analyzed` receipt with a reason; it never invents a cloud copy.
- Acknowledged `applying` stores the intent before any phone mutation.
- `moved` / `copied_pending_removal` require the same intent, destination and
  verified target hash. A copied original is not counted as moved.
- Unknown outcomes after intent remain locked for reconciliation. Only failures
  before intent can take a new batch and new operation ID.
- Pause, cancellation, grant revocation and account changes stop new operations.
  Already-begun matching factual receipts remain recordable after revocation.
- `PATCH /v2/files/:copy` for an organizer copy requires
  `{folder,request_id,revision}` and queues a real approved phone move. It returns
  `pending_folder` / `sync_state`; the visible folder changes after a moved receipt.

## Content analysis, duplicate evidence and retention

`POST /insights/api/organizer-analysis` accepts
`{device,job,ids:[up to 5],consent:true}`. It reads actual owned copies, verifies
their hash, validates extracted-text provenance/coverage, uses the installed AI
binding, and rechecks the same grant/control revision before saving. Analysis
runs outside the account lock. Current results are cached. The separate bounded
budget reserves at most 300 inference units/account/day (photos conservatively
two, other files one); exhaustion preserves progress for continuation.

Items expose `analysis`, `coverage`, `partial`, `file` and `received`. Evidence and
extracted text expire after 24 h; compact organization progress survives copy
expiry. AI failure or unsupported content never becomes fabricated success.
An in-flight result cannot replace an explicitly approved destination.

`duplicate` is separate deterministic evidence from current, actual same-job
copies with equal bytes/hash and distinct original IDs. It names one keeper and
includes `is_keeper`, `original_count`, `expires_at`,
`verified_received_bytes:true`, `review_only:true`, and
`requires_confirmation:true`. No original is skipped or deleted by its hash.

`DELETE .../jobs/:job` forgets progress in bounded cleanup pages, writes a durable
tombstone immediately, and leaves phone originals unchanged. It is blocked while
a mutation needs reconciliation. Cloud copies retain their separate expiry and
deletion controls. Delayed requests cannot recreate a forgotten job.
