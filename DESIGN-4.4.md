# FORJA 4.4 — design and package contracts

Source of truth for the 4.4 implementation. Every package agent reads **§0–§3 fully**, then its own section in §4.
Evidence and detailed specs live in the investigation reports (absolute paths, read the ones your package cites):

```
R=/tmp/claude-0/-home-user-forja/109e3e8e-c053-5270-befe-412c0d7b8d91/scratchpad/understanding44
$R/site-ia.md  $R/site-map.md  $R/data-contract.md  $R/site-harness.md  $R/critic.md
$R/inventar-fixes.md  $R/games.md  $R/music-start.md  $R/workout-music.md  $R/lost-phone.md
$R/notifications-design.md (+ notif-bank-*.md, notifications-audit.md, check_bank.py)
TONE=/tmp/claude-0/-home-user-forja/109e3e8e-c053-5270-befe-412c0d7b8d91/scratchpad/understanding/TONE.md
D43=/tmp/claude-0/-home-user-forja/109e3e8e-c053-5270-befe-412c0d7b8d91/scratchpad/understanding/DESIGN-4.3.md
```

Where this document and a report disagree, **this document wins** (it resolves the contradictions listed in `$R/critic.md` §1).

---

## 0. What Lana asked after testing 4.3 on her Galaxy S23 (SM-S911B, One UI, Android 14/15)

| # | Request (her words, translated) | Package |
|---|---|---|
| 1 | Inventar final screen: "Fișiere" must open the **new location** of the moved files, and **she must choose the location** on the phone. (Also: the X overlaps the status bar; "Nimic nu s-a șters" touches the button.) | P3 |
| 2 | Not a fixed "Ultimele 500": an **editable number**. (Also: the DOCUMENTE tile showed "0 · 0 KB" after organizing.) | P3 |
| 3 | "Activează găsirea" on the phone makes no sense ("do I lose my phone on purpose?"). | P6 (+P1, P2) |
| 4 | Replace SCROLL and SPORT with **two games** (Tetris-like + one more, simple, **with levels**). The music playlist start **does not work**. | P4, P5 |
| 5 | The site (`/insights`) is "very, very disorganized", the **map is not updated**. Make **one section per app ability connected to the site**. "ORDONEAZĂ și site-ul cum trebuie." | P1, P2 (+P8) |
| 6 | Music in the **workout**: a playlist starts together with the Antrenament session, built from her preferences (new or old). | P5 |
| 7 | Notifications like "FORJA sincronizează în cont…" are annoying: make them **personal, warm, motivational, Duolingo style**. | P7 |

Standing product rules (all packages): Romanian UI with correct diacritics (**ș ț with comma below**, never ş ţ);
TONE.md rules (no "!", no emoji in UI copy, buttons ≤ 18 characters, short sentences, the army-camarad voice);
**text diet** (DESIGN-4.3 P1: explanations go to first-visit guides / the "i" dot, not on screens);
free tiers only (Cloudflare Workers/R2/DO free, Firestore Spark, Gemini/Groq free); no paid SDKs; no local AI server.

---

## 1. Decisions (defaults for every open question; Lana is not asked before building)

1. **Site = one section per ability**, hash-routed, same visual language as the app. Canonical list in §3.1.
2. **Friends/map on the site read the app's Firestore graph** (through the worker, with the caller's own ID token).
   The site-only SocialGraph social product (chat, plans, couple/partner, groups, visibility, site invite codes, browser GPS
   journey) is **retired from the UI**. Server routes the app uses stay (`/v2/social/contacts/*`, `/v2/social/explore/*`).
3. **One map engine on the site: MapLibre** with the app's `ForjaStyle` palette ported to JS (Night default). Leaflet is
   removed from the page bundle. The map **fits to her own data** on first load.
4. **Nights on the site:** R2 binding `SLEEP` → bucket `forja-sleep` in `wrangler.insights.toml` (read-only use; keys are built
   from the verified uid only). The Firestore sleep doc id `s{id}` is the join key (no index needed).
5. **New small uploads go to Firestore** under the owner-only subtree `users/{uid}/…` (rules already allow owner read/write for any
   subcollection): Inventar run summaries, music weekly summary, workouts, nutrition targets. The site worker reads them
   with the user's token. No new upload endpoints on forja-insights except the finder beat.
6. **Login on the site persists** (Firebase refresh token in `localStorage`, key `forja.auth.v1`, "Ieși" clears it) and
   **deep links survive login** (`/insights#gasire` → login → Găsire). "Ai uitat parola?" sends the Firebase reset email.
7. **Lost phone ("Găsire"):** the phone is the *target*, never the searcher. It becomes findable **automatically when contract v3
   is signed** (no button, no name field, no second consent, no permanent second notification). Searching/ringing happens on the
   **site**. Last known position kept **7 days** (single overwritten point). **No FCM and no family ring in 4.4** (phase 2).
8. **Contract v3** (one re-sign): adds the finder, Inventar summaries on the site, music top + start diagnostics, workouts and
   targets, and fixes every inaccurate line from `$R/data-contract.md` §5. See §3.6.
9. **Games:** ZID (falling blocks, 10×18, 15 levels + endless) and ASALT (breakout: anvil + ember spark, 12 levels), exactly as
   `$R/games.md`. Reachable from Inventar's waiting cards (ZID · ASALT · MUZICĂ). The "Cum te simți?" mood workout, Scroll feed,
   Sport intervals and the Pexels shorts pipeline are **deleted** (git keeps them; `2d70ac9` is the restore point).
10. **Music:** one engine `core/music/MusicStarter.kt` (verified rung ladder, kind classifier MUSIC/SPOKEN/VIDEO, learned table,
    diagnostics without titles to forja-api) used by Inventar ("Pornește muzica", Play, TOP 1) and Antrenament. Works for Spotify
    Free and Premium; no Spotify Web API. A hidden **probe** (Profil → tap the version 5×) runs every rung once and uploads the log.
11. **Notifications:** the "Casca" voice from `$R/notifications-design.md`, with all five blockers B1–B5 and corrections 1–12 of its
    "Verificare adversarială" applied. New channel `coach` ("Mesaje motivaționale"). ≤ 3 coach messages/day, quiet 22:00–08:00.
12. **Android 15 dataSync 6 h limit:** the contract service no longer declares DATA_SYNC unless it really has user-selected files to
    send; it runs as LOCATION (+ SPECIAL_USE/MICROPHONE as today). This keeps location, the finder beat and the site data alive all day.
13. **Screenshot profile for the S23:** `PHONE_S23 = "w360dp-h696dp-xxhdpi"` (already in `app/src/test/.../screenshots/Shot.kt`).
    Every new or changed full screen gets a shot at `PHONE` **and** `PHONE_S23`.
14. **Firestore privacy:** the app stops writing `email` into the world-readable `users/{uid}` doc (moves it to the owner-only
    `users/{uid}/settings/account`) and a tightened `firestore.rules` is prepared for Lana to publish manually (P8).
15. **Site-started organizing (organizer protocol 4), the legacy organizer, cleanup schedule, Campanii, web mic/phones, "Pentru tine",
    `/insights/map-frame`:** removed from the site UI. Their server routes stay unless a package explicitly owns their removal.

---

## 2. Rules for every package agent

- **Branch:** in your worktree run `git checkout -b wip44/<pkg>` (e.g. `wip44/p3-inventar`) before the first edit. Commit there,
  small commits, Romanian commit subjects like the existing history. Every commit message ends with exactly:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01FwK7gAePmoxiN7NqMpyW8N
  ```
  **Never push. Never touch another branch.** Never put a model name anywhere in code or commits.
- **Ownership:** edit only the files your package owns (§4). A file owned by another package may be edited only where §4 grants a
  named hunk ("one-line hook"). New files are free inside your own directories. If you truly need more, do the smallest change and list
  it under `cross_package_edits` in your final report.
- **Type-check (app):** `flock /tmp/claude-0/-home-user-forja/109e3e8e-c053-5270-befe-412c0d7b8d91/scratchpad/kc/.lock bash /tmp/claude-0/-home-user-forja/109e3e8e-c053-5270-befe-412c0d7b8d91/scratchpad/kc/check.sh <your worktree root>`
  must end with `OK`. (flock serialises compiles: 8 agents share 15 GB RAM.) It type-checks `app/src/main` only; unit and
  screenshot tests run in CI after integration, so write them carefully (JUnit4 + Robolectric + Roborazzi `shot(...)` as in
  `InventoryShots.kt`). Pure-Kotlin engines should keep tests free of Android imports.
- **Server tests:** in `<worktree>/server`: `ln -sfn /home/user/forja/server/node_modules node_modules` (never commit it), then `npm test`.
- **Site harness:** `node scripts/site-shots/shots.cjs --out=<dir> ...` (see `$R/site-harness.md`; Playwright at
  `/opt/node22/lib/node_modules/playwright`, browsers in `/opt/pw-browsers`). workers.dev hosts are unreachable from this container.
- **Copy:** Romanian, comma-below ș/ț, no "!", no emoji, buttons ≤ 18 chars, ≤ 1 helper line per screen, explanations in "i"/guides.
  Keep new strings inline in Kotlin like the rest of the app (no strings.xml), except notification channel names (resources).
- **Design system:** reuse `core/designsystem` (colors, type, `Stamp`, `PrimaryButton`, `InfoDot`, `CoachMarks`, sheets, `Mascot`).
  Honour `LocalReducedMotion` (infinite animations under `if (!reduced)`), otherwise screenshots hang.
- **No regressions:** do not change behaviour you do not own. Do not delete tests to get green.
- **Final report** (your last message) must list: branch, commits, files changed, what was verified and how (paste the last lines of
  check/test output), known gaps, `cross_package_edits`, and anything the integrator must do (migrations, secrets, manual steps).

---

## 3. Cross-package contracts

### 3.1 Site sections (P2 renders, P1 serves, app deep-links with `core/network/SiteLinks.kt`)

| Hash | Stamp · title | Hero | Data |
|---|---|---|---|
| `#azi` | RAPORT DE ZI · Azi | today's numbers + "Legături" strip (one tile per section: on/stale/off + last time) + mascot | `GET /insights/api/azi` |
| `#teren` | TEREN · Harta | full map: explore cells by mode + heat, places (edit name/★/note), recommended places, "Străzile tale" routes, Tu, friends, family, phone ⌖ | `GET /insights/api/cerc` + `GET /v2/social/explore/state` |
| `#camarazi` | CAMARAZI · Prieteni | friends (state, distance, age, ♪), family, invite code, "Din agendă" toggle | `GET /insights/api/cerc`, `/v2/social/contacts/discovery` |
| `#gasire` | GĂSIRE · Telefonul tău | map + device card: Sună · Urmărește 10 min · Oprește; last position 7 days | `/v2/recovery/*` (§3.4) |
| `#inventar` | INVENTAR · Poze și documente | last Inventar runs (folders, De aruncat, destination), gallery copies (24 h) | `GET /insights/api/inventar`, `/v2/files*` |
| `#somn` | STINGEREA · Somn | nights list + chart; night detail = timeline with playable events | `GET /insights/api/somn*` |
| `#ratie` | RAȚIE · Nutriție | day rings vs targets, meals with source badges, 30 days | `GET /insights/api/ratie` |
| `#mars` | JURNAL DE MARȘ · Mișcare | activities with mini route maps + workouts, 7-day totals | `GET /insights/api/mars` |
| `#muzica` | MUZICĂ · Ce asculți | now playing + TOP of the week | `GET /insights/api/muzica` |
| `#paza` | POST DE PAZĂ · Timp pe ecran | top apps today + 7-day bars | `GET /insights/api/paza` |
| `#cont` | LIVRET · Cont și contract | account, contract version/date, pipes with "ultima dată", intake pause, logout, privacy | `GET /insights/api/cont` + existing intake toggle |

Navigation: desktop = left sidebar grouped *Ziua* (Azi) · *Lumea* (Teren, Camarazi, Găsire) · *Corpul* (Somn, Rație, Marș) ·
*Telefonul* (Inventar, Muzică, Pază) · *Cont*. Phone width = bottom bar **Azi · Teren · Găsire · Mai mult** (sheet with the rest).
Detail routes: `#somn/<id>`, `#inventar/<runId>`, `#teren/<lat>,<lng>`. Unknown hash → `#azi`.

### 3.2 Site read API (P1 implements in a new module, P2 consumes; all `GET`, `Authorization: Bearer <Firebase ID token>`)

All responses are JSON with `cache-control: no-store`; times are **ms epoch numbers**; missing data is `null`/`[]`, never an error.
Errors: `{ "error": "<Romanian message>" }` with 4xx/5xx. The worker never returns another user's email or a ghosted position.

```
GET /insights/api/azi
{ me:{uid,name,email},
  today:{ date:"YYYY-MM-DD", kcal, kcalTarget|null, protein, carbs, fat, meals, moveMin, km, workouts },
  night:{ id, startAt, endAt, minutes, score, summary|null } | null,
  links:[{ key:"teren"|"camarazi"|"gasire"|"inventar"|"somn"|"ratie"|"mars"|"muzica"|"paza"|"cont",
           state:"on"|"stale"|"off", lastAt|null, count|null }],
  updated_at }

GET /insights/api/cerc            (cached ≤ 20 s per uid; the site polls every 30 s only while Teren/Camarazi is visible)
{ me:{ lat|null, lng|null, at|null, ghost, ghostUntil|null, state|null, nowPlaying|null, exploreCells|null } | null,
  friends:[{ uid, name, initials, lat|null, lng|null, at|null, state|null, ghost, viaFamily:false,
             nowPlaying:{title,artist,app,at}|null, exploreCells|null }],
  family:[{ uid, name, initials, lat, lng, at }],
  recommended:[{ id, ownerUid, ownerName, name, stars, note, lat, lng, visits|null }],
  routes:[{ id, type, startAt, distanceM, durationS, polyline }],     // own, newest 30 with polyline
  inviteCode|null, updated_at }
  Rules replicated from the app: ghost hides lat/lng/nowPlaying (FriendsRepository.kt:173-201); nowPlaying only if < 10 min old;
  family position from familyLoc (readable only when the caller is in `allowed`).

GET /insights/api/somn?days=14
{ nights:[{ id:"s123", startAt, endAt, minutes, score, deepMin, lightMin, remMin, snoreMin, talkCount, coverageMin,
            summary|null, audio:"ready"|"pending"|"none" }] }
GET /insights/api/somn/<id>            → { id, summary|null, events:[{ t, kind, label, text|null, chunk, offsetMs, durationMs }], chunks:[{ i, startAt, durationMs }] }
GET /insights/api/somn/<id>/chunk/<i>  → audio (Range supported, 206), 404 when expired (7 days)

GET /insights/api/ratie?days=30
{ targets:{ kcal, protein, carbs, fat }|null,
  days:[{ date, kcal, protein, carbs, fat,
          meals:[{ id, at, name, kcal, protein, carbs, fat, grams, mealType, source, confidence }] }] }

GET /insights/api/mars?days=30
{ activities:[{ id, type, startAt, endAt, distanceM, durationS, kcal, polyline }],
  workouts:[{ id, startAt, endAt, durationS, title, kind, sets, volumeKg|null, kcal|null }],
  week:{ km, minutes, sessions } }

GET /insights/api/muzica
{ now:{ title, artist, app, at }|null,
  summary:{ updatedAt, windowDays, totalMinutes, top:[{ title, artist, plays, minutes, app|null }] }|null }

GET /insights/api/paza
{ updated_at|null, days:[{ date, totalMin, apps:[{ label, pkg, minutes, opens }] }] }     // ≤ 7 days, server keeps a 14-day daily rollup

GET /insights/api/inventar
{ runs:[ <users/{uid}/inventory docs, newest first, ≤ 20> ], vault:{ total, latestAt|null } }

GET /insights/api/cont
{ me:{ uid, name, email },
  contract:{ version|null, at|null, revokedAt|null, current:3 },
  pipes:[{ key:"sesiune"|"galerie"|"explorare"|"agenda"|"somn"|"gasire"|"mese"|"miscare"|"muzica"|"inventar", lastAt|null }],
  intake:{ paused } }
```

Existing routes the new site keeps using: `/insights/api/state` is **replaced** by the above (P1 may keep it for one release);
`/v2/files*` (vault), `/v2/social/explore/state` (+ `?since=<updated_at>` → `{unchanged:true, updated_at}`),
`/v2/social/explore/place*` (edit), `/v2/social/contacts/discovery`, the intake pause toggle, `/v2/recovery/*`.

### 3.3 Firestore documents written by the app (owner-only subtree; camelCase; ms epoch; `SetOptions.merge()`)

```
users/{uid}/inventory/{runId}      (P3; written when a run is applied or abandoned; app keeps ≤ 20, deletes older)
{ id, kind:"photos"|"docs", startedAt, finishedAt, appVersion,
  scope:{ mode:"all"|"last"|"album"|"folder", n|null, label },
  dest:{ label, path },                          // e.g. "Galerie · FORJA", "PICTURES/FORJA"
  folders:[{ name, count, bytes }],              // ≤ 60, largest first
  trash:{ count, bytes }, moved, failed, freedBytes|null }

users/{uid}/settings/music          (P5; at most once per 6 h, only with contract v3 signed)
{ updatedAt, windowDays:7, totalMinutes, top:[{ title, artist, plays, minutes, app|null }] }   // ≤ 10

users/{uid}/workouts/w{id}          (P8; every finished workout + backfill of the last 60 days)
{ startAt, endAt, durationS, title, kind, sets, volumeKg|null, kcal|null, source:"instructie"|"asteptare" }

users/{uid}/settings/targets        (P8; whenever targets change)
{ kcal, protein, carbs, fat, updatedAt }

users/{uid}/settings/account        (P8; replaces users/{uid}.email)
{ email }
```
Unchanged: `users/{uid}` (name, lat, lng, locUpdatedAt, state, ghostUntil, familyUids, nowPlaying, exploreCells, contract{version,at,revokedAt}),
`users/{uid}/meals|sleep|activities`, `friendships`, `familyLoc`, `places`, `energy`, `inviteCodes`.

### 3.4 Finder (Găsire) API v2 (P1 implements in `server/lost-phone.mjs`; P6 = phone; P2 = site)

Phone (authenticated with the device secret, Firebase token as today):
```
POST /v2/recovery/devices/{id}/grant   { name, secret, basis:"contract", contract_version:3 }   (old { name, secret, consent:true } still accepted)
POST /v2/recovery/devices/{id}/beat    { secret, status, fix?:{ lat, lon, accuracy, at }, battery?:0-100, charging?:bool }
     → { command:{ id, kind:"locate"|"ring", created_at, start_before, until, phase, minutes|null, seconds|null }|null, next_s }
     updates seen_at, status, expires_at (+30 d), last (only if fix.at is newer; kept 7 d), battery/charging.
POST /v2/recovery/devices/{id}/status  { secret, command, status }       status ∈ ready|locating|ringing|found|location_off|
POST /v2/recovery/devices/{id}/position { secret, command, lat, lon, accuracy, at, battery }        permission_missing|notification_missing|offline|stopped
POST /v2/recovery/devices/{id}/stop    { secret, command }   ("Am găsit telefonul" / "Oprește" on the phone)
POST /v2/recovery/devices/{id}/revoke  { secret }            (contract revoked / logout)
POST /v2/recovery/devices/{id}/poll    (old clients only)
```
Site (owner, Bearer):
```
GET    /v2/recovery/devices  → { devices:[{ id, name, basis, seen_at, online, status, battery|null, charging|null,
                                  last:{ lat, lon, accuracy, at }|null, position:{…, fresh}|null, command|null }],
                                 retention_hours:24, last_retention_days:7 }
POST   /v2/recovery/devices/{id}/command  { id:<uuid>, kind:"locate", minutes:10 } | { id, kind:"ring", seconds:60 }
       (legacy { id, minutes:5|15|30 } = locate). Queued window: start_before = now + 30 min.
POST   /v2/recovery/devices/{id}/extend   { command, minutes:10 }   (cap: 60 min total)
DELETE /v2/recovery/devices/{id}/command?id=<cmd>   stop — KEEPS `last` and `position`
PATCH  /v2/recovery/devices/{id}          { name }   rename (≤ 40 chars)
DELETE /v2/recovery/devices/{id}/grant    unenroll ("Scoate telefonul")
```

### 3.5 Other server contracts

- **Explore sync v2** (P1 server, P8 app): `POST /v2/social/explore/sync` accepts optional `mode ∈ {walk,run,ride}` per cell and optional
  `visits` (int ≥ 1) per place; `explore/state` returns them. The app sends them only when `/health` `explore_sync ≥ 2`.
- **Music diagnostics** (P1 in forja-api `server/worker.js`, P5 app): `POST /v1/diag/music` (same Firebase auth as other `/v1/*`)
  body `{ device, app, events:[{ at, want:"resume"|"mymusic"|"top"|"workout"|"probe", rung, pkg|null, ver|null,
  kind:"music"|"spoken"|"video"|"unknown"|null, result:"ok"|"refused"|"timeout"|"wrong_kind"|"wrong_track"|"error"|"skipped"|"needs_tap",
  ms, err|null }] }` (≤ 50 events, **no titles or artists**). Stored in R2 per uid, last 500 events; visible to the admin log
  command `music [n]`.
- **`/health` of forja-insights** becomes `version: 18` with every existing flag unchanged **except** `explore_sync: 2`,
  `lost_phone: 2`, plus new `site_sections: 1`, `inventory_runs: 1`, `music_summary: 1`. P1 changes `/health`;
  P2 updates `server/verify-live.mjs` (version guard `> 18`, exact `=== 18`, flags, script/asset lists) and `scripts/ux-*.cjs`.

### 3.6 Contract v3 (P6 owns the text and the version bump; others only gate on `prefs.contractSigned`)

`CONTRACT_VERSION = 3`. `prefs.contractSigned` (signedAt > 0 && version ≥ 3) becomes the single gate for every upload that is
not a plain journal: finder, Inventar summaries, music summary + diagnostics, gallery, explore→site, contacts, sync session.
Clauses to add or fix (short lines, same layout as today):
- **Găsirea telefonului:** last position and battery, only for you, to find it from the site; when you search or ring, the phone shows a
  notification. Kept 7 days.
- **Inventarul:** folder names, counts and the chosen location of each run, so you see them on the site. Kept for your last 20 runs.
- **Muzica:** the top of the week (title, artist, how many times) on your site; start attempts (app name and result, no titles) to the
  FORJA server so "Pornește muzica" can be fixed.
- **Antrenamentele** (now really uploaded) and **ținta de calorii**.
- Fix: remove "pași" (no step counter); barcode goes to OpenFoodFacts; where = Google Firebase (journals, position, friends) +
  Cloudflare (site, nights, AI); phone numbers are sent over TLS and fingerprinted on the server; the gallery on the site = last
  24 h, at most 500; location on the site = last 24 h; "Cine vede" matches the rules after P8.
- Profile line "Fără contract semnat, nimic nu pleacă pe site" (`ProfileScreen.kt:73`) must become true or be rewritten.
After the update, a signed v2 shows **one** re-sign prompt (sheet with the new lines highlighted, "Semnează"). Until re-signed,
contract-gated work pauses; journals keep syncing.

### 3.7 Shared app files and the one-line hooks allowed

| File | Owner | Allowed hooks for others |
|---|---|---|
| `MainActivity.kt` | P4 (routes) | P6: logout hook + remove `lost_phone` route + ON_START `ensureEnrolled`; P5: probe route; P7: `lastOpen` stamp on ON_START |
| `navigation/Nav.kt` | P4 | P5 probe route constant |
| `feature/profile/ProfileScreen.kt` | P6 | P5: version text tap ×5 → probe; P7: nothing |
| `core/data/Prefs.kt` | P6 (contract) | P3: 3 inventory keys; P8: nothing (use Firestore) |
| `core/sync/AutomaticCollectionService.kt` | P6 | P7: `notification()` builder + one rotation call in the loop (guarded `foreground && !stopping`) |
| `core/sync/CollectionSettings.kt` | P6 | P8: one line `ExploreSync.schedule(app)` in `enableAll` |
| `ForjaApp.kt` | P7 (channels) | P6: boot/self-heal hook if needed |
| `AndroidManifest.xml` | P6 | P7: only if a receiver is needed |
| `feature/inventory/InventoryScreen.kt`, `InventoryModels.kt`, `InventorySamples.kt` | P3 | P4: `InvWait`, `RunUiState` levels, removal of the Scroll poster and Sport samples |
| `feature/inventory/RunContent.kt` | P4 | — |
| `feature/inventory/MusicWait.kt` | P5 | — |
| `core/inventory/*`, `core/cleanup/DocumentOrganizer.kt` | P3 | P5 keeps `Music.onInventoryDone(ctx)` signature (no edit in Inventory.kt) |
| `core/inventory/InventoryWorker.kt` | P7 (notification copy only) | P3 must not change its notification code |
| `feature/workout/*` | P5 | P8 must not edit (backfill workouts from Room/DAO instead) |
| `core/data/CloudSync.kt`, `core/explore/*`, `core/data/AuthRepository.kt`, `firestore.rules` | P8 | — |
| `server/insights-worker.mjs` | P1 (routes, /health) | P2: static asset imports and the `/insights/app.js` concatenation list |
| `server/wrangler.insights.toml` | P1 (SLEEP binding) | P2: a `Data` rule for font files |
| `server/verify-live.mjs`, `scripts/ux-*.cjs`, `scripts/map-ui-test.cjs`, `scripts/site-shots/*` | P2 | — |
| `.github/workflows/build-apk.yml`, `shorts.yml` | P4 (drop shorts input/job/cron) | — |
| `app/build.gradle.kts` (version) | integrator only | — |

---

## 4. Packages

### P1 — Server (forja-insights APIs + finder v2 + forja-api diagnostics)
Owns `server/*.mjs` (except client `*.js.txt`), `server/*.md` docs, `server/wrangler*.toml` (except P2's Data rule), `server/worker.js`.
1. New module `server/site-api.mjs` (+ `site-api.test.mjs`) implementing every endpoint of §3.2 with Firestore REST reads made with
   the caller's token (extend `firestoreValue` in `insights-ai.mjs` with `mapValue`, `arrayValue`, `nullValue`, `timestampValue`,
   `booleanValue`, `doubleValue`). One routing line in `insights-worker.mjs`. Cache `cerc` ≤ 20 s per uid. Quotas: stay far below
   Firestore 50 k reads/day at a 30 s poll with ~10 friends (batchGet, field masks, limits).
2. R2 binding `SLEEP` (`forja-sleep`) + the three `somn` endpoints (read `analysis.json`; map its fields to §3.2 `events`; stream chunks
   with Range). Check `server/worker.js` for the exact R2 key layout and analysis schema.
3. `paza`: when the phone posts session data, also update a daily rollup (`usage-day:YYYY-MM-DD`, 14 days) in `InsightsAccount`.
4. Finder v2 exactly as §3.4 in `lost-phone.mjs` + `recovery.test.mjs` (beat without fix, monotonic `last`, ring lifecycle, stop keeps
   `last`, 30-min queue window, contract-basis grant, extend cap, rename). Update `LOST_PHONE.md`.
5. Explore sync v2 (§3.5) in `social-journey.mjs` + tests; `explore/state?since=`.
6. forja-api: `POST /v1/diag/music` + admin `music [n]` + test (§3.5).
7. `/health` v18 (§3.5); update server tests that pin the version (e.g. `social-contacts.test.mjs`).
8. Keep every route the 4.3 app uses working (contacts, explore, files, organizer v4 jobs, recovery v1, sessions).
Verify: `npm test` green; add tests for every new route with mocked `fetch` for Firestore.

### P2 — Site redesign (front-end) — Lana's #5, the "special attention" item
Owns `server/insights.html`, all `server/*.js.txt` clients, `server/vendor/*` usage, new site assets (fonts, mascot SVG),
`server/verify-live.mjs`, `scripts/ux-ui-test.cjs`, `scripts/ux-fixture.cjs`, `scripts/map-ui-test.cjs`, `scripts/site-shots/*`,
and the static-asset part of `insights-worker.mjs`.
1. Rebuild the page as the 11 sections of §3.1 with the app's visual language: colours `#0A0A0B` (bg) `#121214`/`#17181C` (surfaces)
   `#4A5D3A`/`#6F855A` (olive) `#F3B952` (amber) `#FF6B57` (ember) `#F4F2EE` (text) `#A7A9AE`/`#7A7D83` (dim); fonts Barlow Condensed
   (display/stamps), Hanken Grotesk (body), JetBrains Mono (numbers/meta), self-hosted latin-ext subsets (from `app/src/main/res/font`;
   subset to woff2 if fonttools is available, else serve the TTFs) — study `core/designsystem` (Color.kt, Type.kt, Stamp, cards,
   buttons) and mirror it; mascot as inline SVG. Each section: stamp + title + one hero + "i" dot + connection chip; ≤ 1 helper line;
   honest empty states ("Semnează contractul în FORJA. Telefonul apare aici singur.").
2. Map (Teren and Găsire): MapLibre only, `ForjaStyle` ported (`core/map/ForjaStyle.kt`, `MapLayers.kt`), layers per `$R/site-map.md` §4 P1,
   fit to own data, 3D = pitch on the same map, tolerant of tile/glyph errors, `setData` updates (no rebuilds), polling only while
   visible. Remove Leaflet and `/insights/map-frame` from the page.
3. Persisted login + password reset + hash deep links (§1.6). CSP must still allow only `'self'` + Google identity/securetoken hosts
   (+ the map tile/style hosts already allowed).
4. Remove the retired surfaces (§1.2, §1.15). Organizer v4 UI, legacy organizer, cleanup schedule, Campanii, sleep web mic, Pentru tine,
   browser journey, SocialGraph friends/chat/plans/couple.
5. Responsive: desktop sidebar ≥ 1024 px, phone bottom bar ≤ 700 px, no horizontal scroll at 360 px.
6. Harness: update `scripts/site-shots` (fixture profiles `rich`, `lana` = realistic 4.4 account, `empty`; mock every §3.2/§3.4 endpoint
   **exactly as specified**), capture every section at 1440×900 and 390×844 and **look at the PNGs** yourself; iterate until clean.
   Update `ux-ui-test.cjs`/`ux-fixture.cjs` checks to the new IA, delete or re-point `map-ui-test.cjs`, update `verify-live.mjs` (§3.5).
Verify: `npm test`, `node scripts/ux-ui-test.cjs`, harness run with 0 page errors and 0 unmocked calls; attach the screenshot dir path.

### P3 — Inventar fixes (Lana's #1, #2)
Owns `core/inventory/*` (except InventoryWorker notification code), `core/cleanup/DocumentOrganizer.kt`, `feature/inventory/*`
except `RunContent.kt` and `MusicWait.kt`, `InventoryShots.kt`, Prefs keys `inventory_last_n`, `inventory_photo_root`, `inventory_docs_dest`.
Implement `$R/inventar-fixes.md` §1–§6 fully: `DestRec`/`InvDest`/`setDestination`, the **Locație** sheet (photos: Galerie · FORJA,
Direct în Galerie, Lângă Cameră, Alt dosar…; docs: În folderul ales, Alt folder…), `Landing` from `apply` + the ordered opener fallbacks
(the "Fișiere"/"Galerie" button opens the **new location**), `ScopeChoice.LastN` with the **editable number sheet** (presets 100/500/1 000/5 000
+ number field, remembered), Done layout (pinned top/bottom, scaled hero, ≥ 16 dp gap, no top-right X), docs tile loose vs organized
("În ordine · N ÎN DOSARE"), refresh after apply and on resume. Plus: write `users/{uid}/inventory/{runId}` (§3.3) when a run finishes
(gated on `prefs.contractSigned`), and a "Pe site" action on Done → `SiteLinks.open(ctx, Inventar, runId)`.
Shots: LastNSheet, LocationSheet (photos + docs), Start tile "În ordine", Done docs/photos with `failed > 0`, Apply confirm — at `PHONE` and `PHONE_S23`.

### P4 — Games ZID + ASALT (Lana's #4a)
Owns `core/games/**`, `feature/games/**`, `feature/inventory/RunContent.kt`, the `InvWait`/`RunUiState`/removal hunks in
`InventoryScreen.kt`/`InventoryModels.kt`/`InventorySamples.kt`/`InventoryShots.kt`, `navigation/Nav.kt`, the game routes in
`MainActivity.kt`, deletion of `ScrollWait.kt`, `SportWait.kt`, `IntervalFigures.kt`, `feature/shorts/*`, `core/media/Shorts.kt`,
and the shorts parts of `.github/workflows/build-apk.yml` + the cron in `shorts.yml`.
Implement `$R/games.md` completely (§0–§8): deterministic pure-Kotlin engines, 15 ZID levels + endless, 12 ASALT levels, gestures,
loop/auto-pause, save/resume, SoundPool sfx synthesised on device, haptics, mascot reactions, level map, pill/"Dosarele sunt gata"
integration, reduced motion, copy. Cards **ZID · ASALT · MUZICĂ**. Engine unit tests (levels solvable by bots, save round-trip) and
shots at `PHONE` and `PHONE_S23`. Never say "Tetris"/"Breakout"/"Arkanoid" in UI.

### P5 — Music engine + workout playlist (Lana's #4b, #6)
Owns `core/music/**`, `feature/inventory/MusicWait.kt`, `feature/workout/**`, new `feature/probe/**`, `core/network/ForjaApi.kt`
(diag call only), probe route/entry hooks (§3.7).
Implement `$R/music-start.md` §6–§8 (Want/Kind, rung catalogue, plans with/without access, state machine, learned table, UI states,
MusicLog + upload to `/v1/diag/music`, hidden probe) and `$R/workout-music.md` (hub "Muzică" row with switch + Mix/Noi/Vechi/Apreciate
sheet, live-screen disc + rest-strip transport, FORJA queue, leases so an Inventar finish never pauses workout music, `MusicCue` ducking,
richer `MusicStats` recording: pkg, mediaId, uri, duration, kind, skips — metadata only). Weekly summary to `users/{uid}/settings/music`
(§3.3). Keep `Music.onInventoryDone(ctx)` callable from `Inventory.kt` unchanged. Fix the 4.3 bugs H1–H7 (Play sending PAUSE, audiobook
as hero, 2 s timer, hidden failures). Shots for every S3c state and the workout hub/live at both profiles.

### P6 — Găsire + sync service + contract v3 (Lana's #3)
Owns `core/recovery/**`, `feature/recovery/**` (delete `LostPhoneScreen.kt`), `core/sync/AutomaticCollectionService.kt` (loop, mask,
beat, wake), `core/sync/CollectionSettings.kt`, `core/location/BgLocation.kt`, boot receiver, `feature/permissions/ContractScreen.kt`,
`core/data/Prefs.kt` (contract), `feature/profile/ProfileScreen.kt`, `AndroidManifest.xml`, and the P6 hooks in `MainActivity.kt`.
Implement `$R/lost-phone.md` §5.1–§5.3 app side, §5.6–§5.7 (auto-enrol on contract v3, beat every loop with wake lock +
`setAndAllowWhileIdle`, executor for locate/ring with the unsuppressible notification "Te caută contul tău" + `FoundActivity`
"Aici sunt." / "Am găsit telefonul", muted channel reported as `notification_missing` instead of revoking, boot path + self-heal,
logout/revoke → `disable()`, Profile row "Telefonul meu · în gardă/fără contract/incomplet/fără legătură" + sheet with "Deschide pe site"
(`SiteLinks.open(ctx, Gasire)`) and "Probă"). DATA_SYNC only with selected files (§1.12, fix both `CollectionSettings.kt:90` and
`AutomaticCollectionService.kt:252`). Contract v3 text + re-sign prompt + reconcile (§3.6). Shots: Profile row states, sheet, FoundActivity,
contract v3, re-sign prompt.

### P7 — Notifications (Lana's #7)
Owns `core/notify/**` (replace `ForjaNudge` with the Nudge system), notification channel setup in `ForjaApp.kt`, notification copy/builders
in: `AutomaticCollectionService.notification()` (+ the one rotation call), `InventoryWorker.kt`, `SleepUpload.kt`, `GalleryScan.kt`,
`ExploreTracker.kt` (notify part only), `FocusMonitorService.kt`, social/friend notifiers, sleep reminder.
Implement `$R/notifications-design.md` §B–§I with every blocker B1–B5 and correction 1–12 of its "Verificare adversarială": the "Casca"
voice bank (cleaned), `Nudge.pick/render`, `NudgeWorker` (unique periodic, KEEP), quiet hours 22:00–08:00, ≤ 3 coach/day ≥ 3 h apart,
anti-repeat, self-tuning on ignores, lock-screen private versions, Romanian plural helper, distinct notification IDs (fix 35 collision),
groups, `coach` channel inheriting `social`'s importance, bedtime reminder via `AlarmManager.setWindow`, FGS copy honest in collapsed form.
Unit tests of §H list (JVM) + the copy lint (port of `check_bank.py`).

### P8 — Data parity for the site + Firestore privacy
Owns `core/data/CloudSync.kt`, `core/explore/**`, `core/data/AuthRepository.kt` (email), `firestore.rules`, new `FIRESTORE-RULES.md`,
the one-line hook in `CollectionSettings.enableAll`.
1. Explore: send `mode` per cell and `visits` per place when `/health explore_sync ≥ 2`; one-time resync (`setExploreSyncedAt(0)` behind a
   schema pref); debounced kick (≤ 1 per 5 min) on a **new cell**; watermark overlap of 15 min (no Room migration); schedule on contract enable.
2. Workouts → `users/{uid}/workouts/w{id}` (§3.3) via a backfill/observer that does **not** edit `feature/workout/*` (e.g. a DAO Flow
   collector started from the app scope or a small WorkManager job). Targets → `users/{uid}/settings/targets`.
3. Email: stop writing `email` into `users/{uid}`, write `users/{uid}/settings/account`, delete the old field once; audit every reader.
4. `firestore.rules`: `users/{uid}` readable by self, by friends (friendship doc exists — check the id format in the app) and family
   (`familyLoc` allowed), **not** by every signed-in account; keep every read path the app uses working (audit invite-code and
   contacts flows). Write `FIRESTORE-RULES.md` with the 3-click publish steps for Lana. The app must work with **both** old and new rules.
