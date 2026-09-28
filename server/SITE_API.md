# FORJA 4.4 — API-ul secțiunilor site-ului (forja-insights, `/health` v18)

O secțiune pe fiecare abilitate a aplicației. Formele exacte ale răspunsurilor sunt în `DESIGN-4.4.md` §3.2; aici sunt
regulile de funcționare. Cod: `site-api.mjs` (Worker), `site-store.mjs` (Durable Object-ul contului), `site-time.mjs`
(zilele după ora României). Teste: `site-api.test.mjs` (Firestore REST emulat, cu numărarea citirilor).

## De unde vin datele

| Rută (GET, Bearer Firebase) | Sursă |
|---|---|
| `/insights/api/azi` | Firestore (profil, ținte, muzică, mese de azi, 7 zile de mișcare, ultimele 2 nopți, ultimul Inventar) + DO (telefoane, sesiuni, pază) + graful social (explorare) |
| `/insights/api/cerc` | Firestore: `users/{eu}`, `friendships`, `users/{prieteni}` (batchGet cu mască), `familyLoc`, `places` (visibleTo), `users/{eu}/activities` |
| `/insights/api/somn?days=14` · `/somn/<sId>` · `/somn/<sId>/chunk/<i>` | Firestore `users/{eu}/sleep` + R2 `forja-sleep` (binding `SLEEP`): `<uid>/<sId>/chunk_<i>.m4a`, `analysis.json` |
| `/insights/api/ratie?days=30` | Firestore `settings/targets` + `meals` |
| `/insights/api/mars?days=30` | Firestore `activities` + `workouts` |
| `/insights/api/muzica` | Firestore `users/{eu}.nowPlaying` + `settings/music` |
| `/insights/api/paza` | DO: `usage-day:YYYY-MM-DD` (14 zile) |
| `/insights/api/inventar` | Firestore `inventory` (ultimele 20) + DO (vault) |
| `/insights/api/cont` | Firestore (contract, ultima dată pe fiecare conductă) + DO (sesiune, galerie, telefon, pauză) + graful social |

Firestore se citește prin REST **cu tokenul celui care cere**: regulile se aplică exact ca în aplicație; o citire refuzată de
reguli înseamnă „nu există”. Emailul vine doar din tokenul propriu; al prietenilor nu se cere niciodată (măști fără `email`).
Cheile R2 se construiesc numai din uid-ul verificat.

## Regulile copiate din aplicație

- Fantomă (`ghostUntil == -1` sau în viitor): fără `lat`/`lng`/`at`/`nowPlaying`, `state:"ghost"` — și pentru tine.
- `nowPlaying` doar sub 10 minute.
- `family[]`: prietenii în fantomă care te-au pus în familia lor, cu poziția din `familyLoc` (ca pe hartă, cu 0,55).
  Pentru un prieten vizibil, un punct `familyLoc` mai nou decât cel public îi ține pinul la zi; `viaFamily` rămâne `false`.
- `routes`: ultimele 30 de activități cu traseu, simplificate (Douglas–Peucker ~5 m, cel mult 300 de puncte).
  În `mars`, traseele mici au cel mult 120 de puncte.
- Listele pe zile (`ratie`, `paza`) conțin doar zilele cu date, cea mai nouă prima; mesele unei zile sunt în ordinea orei.

## Memorie și cota Firestore (Spark: 50 000 citiri/zi)

`cerc` are trei trepte: live (eu + prieteni + familyLoc) 20 s, lista de prieteni 10 min, locuri recomandate 10 min; toate
în DO-ul contului (`site-cache:*`), plus 20 s în memoria izolatului. Traseele se construiesc o singură dată (pagini de 5
activități, cel mult ~600 KB de polilinii pe cerere, ca să încapă în CPU-ul gratuit al Workerului) și apoi se cer doar
alergările mai noi decât ultima cunoscută; Marș le refolosește pentru hărțile mici. Cu 10 prieteni și un poll la 30 s, o oră
de site deschis costă ~1 550 de citiri (testul o verifică). `azi` stă 20 s în memorie. Celelalte secțiuni se citesc la deschidere.
Când Firestore nu răspunde deloc: 503 `{error}`; `cerc` servește atunci ultimul răspuns din DO, cu `updated_at`-ul lui.

## Timpul pe ecran (Pază)

Sesiunea automată trimite la ~60 s totalurile cumulate ale ferestrei ei. La fiecare `POST /v2/sessions/{id}/data` cu
`app_usage`, DO-ul adună doar diferența față de fotografia precedentă a aceleiași sesiuni (`usage-last:{id}`) și o împarte pe
zilele locale ale intervalului (o aplicație folosită ultima dată înainte de miezul nopții rămâne în ziua ei). Se păstrează 14 zile;
site-ul primește ultimele 7.

## Legături (Azi) și conducte (Livret)

`state`: `on` când conducta a livrat în fereastra ei, `stale` când e mai veche, `off` când nu a livrat niciodată.
Ferestre: Teren, Camarazi, Rație, Muzică 24 h · Găsire 20 min · Somn 48 h · Marș 7 zile · Inventar 30 de zile · Pază 2 h.
Livret: `on` cu contractul v3 semnat, `stale` cu v2 (de re-semnat), `off` nesemnat sau revocat.

## Configurare

`wrangler.insights.toml` are `[[r2_buckets]] binding = "SLEEP", bucket_name = "forja-sleep"` (același cont Cloudflare ca
forja-api). forja-api scrie `status` în metadatele `analysis.json`, ca lista de nopți să știe dintr-o singură listare ce e gata.
