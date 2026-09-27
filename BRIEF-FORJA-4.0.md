# BRIEF FORJA 4.0 — de dat lui Claude într-o sesiune nouă („continuă FORJA după brief”)

## Stare (27 septembrie 2026)

- Sursa reală, completă și construibilă a lui **FORJA 4.0** este pe branch-ul `claude/quirky-hamilton-7nbpi8`
  (GitHub `zuzu-bit/forja`). `main` a rămas la v3.7 — se poate face merge oricând (fast-forward nu; e un branch cu multe merge-uri).
- **APK-ul 4.0** e publicat la Releases → „FORJA — ultimul APK” (build-ul din Actions `build-apk.yml`, rulat manual pe branch cu
  `publish=true`). Pachet `com.forja.app.research`, versionCode 60, semnat cu `app/debug.keystore` (același semnatar ca 3.7-online.xx,
  SHA-256 `6fc56c1f…ed74`) → se instalează PESTE aplicația de pe telefon; baza Room migrează 5→6→7 fără pierderi.
- **Site-ul** (`https://forja-insights.forja-22e7ea2d.workers.dev`) e publicat din CI (`insights-deploy.yml`, la orice push în
  `main`/`claude/**` care atinge `server/**`). Versiunea `/health` = 16, cu `explore_sync:1`, `content_ai:2`. Harta 2D/3D reparată (v27).
- Worker-ul aplicației (`server/worker.js`, forja-api) are `POST /v1/organize` (sugestii de curățenie) și lista Gemini
  `gemini-2.5-flash → gemini-2.0-flash`; fără cheie folosește banca de modele Cloudflare.

## Ce s-a livrat (v4.0 + pasul 2)

Hartă (ForjaTiles cu rezervă CARTO→OSM→offline, ciclu de viață, GO înapoi, 2D/3D), Explorare (celule ~150 m, locuri = ai stat ≥ prag,
stele/notă/recomandă prietenilor, Familie te vede și în fantomă, `firestore.rules` cu `places`, `familyLoc`), Somn (serviciu sigur pe
Android 14/15, STOP/SNOOZE, „Încă 10 minute”, alarmă care nu tace, „Veghea de noapte”, „Nopțile tale”), Start (Effects.kt, splash, 4 ordine
de zi, cont peste video, Echipare cu 5 bife, tranziții, `INTRO_VERSION=2`), Curățenie v2 (scop, reluare obligatorie, SHA-256/dHash/capturi/
neclare/mici/mari, dosare `Pictures/FORJA Curățenie/…`, documente în `Organizate/…`, AI opt-in), Dashboard cu tile-uri Explorare/Curățenie,
Profil cu „Panoul meu online”, „Echipare”, „Explorare”, „Sincronizare în cont”, „Telefonul meu”.
Pasul 2 (toate opt-in, implicit oprite): Sincronizare în cont (`core/sync`, secțiune în Echipare, `/v2/sessions`), Telefonul meu
(`core/recovery`, `/v2/recovery`), Explorarea „Și pe site” (`core/explore/ExploreSync.kt` + `server/social-journey.mjs` rute
`/v2/social/explore/*`, hartă și listă „Locurile mele” pe site), Curățenia „Și pe site (copii 24 h)” (`core/cleanup/OrganizerJobs.kt`,
protocol 4, `/v2/organizer`, `/v2/files`), Gemini 2.5 Flash pe site (`server/gemini.mjs`, folosit când există `GEMINI_API_KEY`).

## Pași manuali pentru Lana (o singură dată)

1. Firebase → Firestore → Rules: lipește conținutul din `firestore.rules` → Publish (colecțiile noi `places`, `familyLoc`).
2. (Opțional) GitHub → Settings → Secrets → `GEMINI_API_KEY`: cu ea, mesele, curățenia și site-ul folosesc Gemini 2.5 Flash.
3. (Opțional) Merge `claude/quirky-hamilton-7nbpi8` → `main`; după merge, orice push pe `main` construiește și publică APK-ul singur.
4. Clipurile Veo/Adobe originale în bucketul `forja-media` (vezi `MEDIA.md` / `VIDEO.md`) — aplicația le ia automat.

## Cum se lucrează (sesiunea următoare)

- Build de verificare fără să atingă release-ul: Actions → „Build FORJA APK” → Run workflow pe branch, `publish=false` (artifact).
- Type-check local fără SDK Android (dl.google.com e blocat în container): harnașamentul `kc/` din scratchpad-ul sesiunii anterioare
  nu persistă; se reconstruiește în ~40 min după rețeta din `kc/README.md` (kotlinc 2.1.0 + android.jar din Sable/android-platforms +
  dependențe din NuGet/Maven Central). Merită.
- Spec-urile de lucru folosite: `SPEC-v4.0.md` și `SPEC-step2.md` (în scratchpad; ideile principale sunt în README).

## Ce urmează

1. Test real pe telefon (Lana): instalare peste 3.7-online, prezentarea nouă o dată, Echipare, hartă (dale, zone, locuri după prag),
   somn cu alarmă, curățenie cu reluare, sincronizare/telefonul meu/explorare pe site — și lista de bug-uri.
2. Tonul „armată serioasă + citate calde” extins în restul aplicației (Antrenament, Nutriție, Focus); aspectul să nu pară „AI”.
3. Descoperirea prietenilor din agendă (contacte) — cere Firebase Phone Auth + facturare Blaze; amânat până se activează în consolă.
4. Harta 3D reală în aplicație (MapLibre în WebView, ca pe site) — acum e înclinare 2.5D; de decis după testul pe telefon.
5. Curățare: branch-urile GPT (`research/*`, `ux/*`, `delivery/*`, PR #17–#20) pot fi închise — tot ce era util e în sursa reală.
