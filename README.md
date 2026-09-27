# FORJA — Android · „REAL & VIU"

Aplicația nativă FORJA (Jetpack Compose, dark-only, în română), construită după prototipul
Claude Design din `design_handoff_forja`. Fitness & lifestyle: antrenamente cu video,
nutriție cu cod de bare + baza de date OpenFoodFacts, somn, hartă socială live cu prieteni
reali (Firebase) și Focus (blocare de aplicații, onestă, fără AccessibilityService).

## Ce e nou în 4.0 (septembrie 2026)

Pachetul aplicației este acum `com.forja.app.research` (versionCode 60), ca 4.0 să se instaleze **peste**
aplicația „3.7-online” de pe telefon, cu același cont și aceleași date locale (baza Room migrează fără pierderi).

- **Hartă**: dale CARTO → OSM întunecat → offline (chip de stare, niciodată gri la nesfârșit), ciclu de viață corect,
  butonul GO înapoi, 2D/3D (înclinare), **Explorare** — zonele (~150 m) prin care treci se deblochează pe hartă (măsliniu),
  **locurile** unde ai STAT ≥ prag (30 min / 1 h / 2 h / 5 h, implicit 5 h) primesc pin amber, nume, stele, notă și
  „Recomandă prietenilor” (pin albastru la ei). **Familia**: prietenii marcați „Familie” te văd și în modul fantomă.
- **Somn**: ecranul original (alarmă circadiană + istoric „Nopțile tale”); serviciul nu mai crapă pe Android 14/15
  (tip de prim-plan după permisiuni), STOP/SNOOZE sigure, „Încă 10 minute”, alarma nu tace niciodată,
  „Veghea de noapte” (excepție de baterie + alarmă pe tot ecranul).
- **Start**: splash nou cu scântei, prezentare cu 4 „ordine de zi” (video cu parallax), cont peste video,
  **Echipare** — 5 bife (notificări, locație, microfon, poze, baterie) cu bară de progres, tranziții între ecrane.
  Prezentarea se arată o dată și conturilor existente. Pagina „Contul tău este conectat” nu mai există.
- **Curățenie v2**: „Ce curățăm azi?” (galerie / album / următoarele 50-100-300), **reluare obligatorie** de unde ai rămas,
  duplicate (SHA-256), aproape identice (dHash), capturi de ecran, neclare, mici, mari; mutare în
  `Pictures/FORJA Curățenie/<categorie>`; documente prin SAF în `Organizate/<categorie>`; sugestii AI (opt-in) prin
  `POST /v1/organize` pe serverul FORJA (Gemini 2.5 Flash când există cheie, altfel banca de modele Cloudflare).
- **Site-ul FORJA** (`server/insights-worker.mjs`, https://forja-insights.forja-22e7ea2d.workers.dev) e acum în acest repo,
  cu testele lui, și se publică automat din CI (`insights-deploy.yml`). Harta 2D/3D reparată (v27) e live ca versiunea 16.
- **Online, în sursa reală** (toate opt-in, implicit oprite): **Sincronizare în cont** (Echipare → secțiunea de jos: locație și opriri,
  activitate în aplicații, fotografii/fișiere alese, microfon live → `/v2/sessions` pe site), **Telefonul meu** (Profil → găsirea telefonului
  pierdut din panoul online, 5/15/30 min), **Explorarea „Și pe site”** (zonele și locurile tale, cu stele și note, pe aceeași hartă din panou;
  editările din laptop se întorc pe telefon), **Curățenia „Și pe site (copii 24 h)”** (protocolul 4: job-uri comune telefon–site, pozele și
  documentele analizate urcă în cont, aprobarea și organizarea se pot face din laptop), **Gemini 2.5 Flash pe site** când există cheia
  (altfel Cloudflare Workers AI).
- **Firestore**: regulile au colecții noi (`places`, `familyLoc`) — lipește din nou `firestore.rules` în consola Firebase → Publish.

## Somn — noaptea, ascultată (pachetul „sleep-app”)

Ce se întâmplă cu o noapte, pas cu pas (totul e estimare, nu diagnostic — și textele o spun):

1. **Înregistrare în bucăți** (`core/sleep/AacRecorder.kt`): AAC-LC 48 kbps mono, fișiere de ~30 min în
   `filesDir/sleep_full/<sesiune>/chunk_<i>.m4a`, cu manifestul `chunks.json`
   (`{session, startedAt, sampleRate, chunks:[{index, file, from, dur}], closed}`; `from`/`dur` în ms față de începutul
   audio-ului). Rotația se face la granița de mostre, fără pierderi între bucăți. Sesiunile vechi cu un singur
   `sleep_full/<id>.m4a` se tratează ca bucata 0 și rămân redabile.
2. **Stadii din mișcare** (`core/sleep/SleepStaging.kt`, funcții pure): epoci de 1 min din mișcările accelerometrului
   (+ micro-mișcări), scor ponderat ±2 min (stil Cole-Kripke), prag treaz/somn, latența de adormire (10 min liniștite),
   „profund” = ≥ 20 min fără mișcare la > 30 min după adormire (plafonat pe ciclu), REM ≈ finalul ciclurilor de ~90 min,
   treziri (≥ 2 min), scor 0–100 explicat linie cu linie („−8: 3 treziri”). Rezultatul stă în `staging.json`,
   hipnograma și scorul din baza Room vin de aici; `measurement` rămâne `estimated`.
3. **Urcare automată** (`core/sleep/SleepUpload.kt`, WorkManager): la „M-am trezit”, pe Wi-Fi (sau „și pe date mobile”,
   din ecranul de somn) și cu bateria ≥ 15 %: `PUT /v1/sleep-chunk?session=s<id>&index&from&dur` pentru fiecare bucată,
   apoi `POST /v1/sleep-analyze`, apoi sondaj `GET /v1/sleep-analysis` la 20 s (cel mult 20 min, împărțite pe rulări).
   Progresul e în `upload.json`; rezultatul normalizat în `timeline.json`
   (`{status: done|processing|clips_only|failed|timeout, reason, events:[{at, end, type, intensity, transcript, confidence}],
   stats:{snoreMin, snoreEpisodes, talkCount, coughCount, coverageMin, totalMin}, startedAt}`). La final: rezumatul de
   dimineață refăcut cu cronologia (`POST /v1/sleep-summary` + `timeline`), Firestore (`snoreMin`, `talkCount`,
   `coverageMin`) și notificarea „Raportul nopții e gata” pe canalul „sleep”. Clientul HTTP e `core/network/SleepApi.kt`.
4. **Raportul** (`feature/sleep/SleepScreen.kt`): secțiunea „Noaptea, ascultată” — acoperirea onestă („Am ascultat 7 h 42 min
   din 7 h 50”), cronologia cu oră, tip, intensitate, transcriere EXACTĂ între ghilimele și încredere, „Ascultă” = 8 s din
   bucata potrivită (offset − `from`), „Ascultă toată noaptea” + „SARI LA MOMENT” pe bucăți. Fără cheie Gemini pe server:
   „Serverul nu a putut asculta noaptea (lipsește cheia Gemini). Ai doar clipurile prinse pe telefon.”
5. **Păstrare**: clipurile de 5 s rămân locale (7 zile) și se clasifică live ca înainte; bucățile stau pe telefon 24 h după
   urcare (3 zile dacă n-au urcat), pe server 7 zile; `timeline.json`/`staging.json` 30 de zile.

## Cum obții aplicația (APK)

La fiecare push pe `main`, GitHub Actions construiește APK-ul și îl publică la
**Releases → „FORJA — ultimul APK"**:

1. Deschide pagina de Releases a acestui repo pe telefon.
2. Descarcă `FORJA.apk`.
3. Deschide fișierul și acceptă instalarea din surse necunoscute.

## Configurare Firebase (o singură dată, ~2 minute)

Proiectul Firebase există deja: **forja-65093** (fișierul `app/google-services.json` e inclus).
În [consola Firebase](https://console.firebase.google.com/project/forja-65093):

1. **Authentication → Get started → Sign-in method → Email/Password → Enable.**
   Fără asta, crearea de conturi afișează o eroare clară în aplicație.
2. **Firestore Database → Create database** (alege locația `eur3` sau `europe-west`).
   Pornește în *production mode*, apoi:
3. **Firestore → Rules** → lipește conținutul din [`firestore.rules`](firestore.rules) → Publish.

Notă despre release: pagina de release afișează starea acestor servicii la momentul build-ului.

## Arhitectură

- **UI**: Jetpack Compose, design tokens exacți din handoff (culori, Archivo Expanded /
  Hanken Grotesk / JetBrains Mono ca fonturi variabile, cele 3 arcuri spring: snappy/natural/gentle).
- **Local (pe telefon)**: Room — antrenamente, serii, mese, somn, activități, reguli Focus.
  DataStore — preferințe. Mesele și somnul NU pleacă de pe telefon.
- **Cloud (între prieteni)**: Firebase Auth (email+parolă) + Firestore — profil, cod de
  invitație, prietenii (reciproc, prin cod), poziția live (doar când nu ești fantomă), energie (kudos).
- **Hartă**: osmdroid + tiles CARTO dark cu tentă caldă (paleta din prototip), markeri cu
  interpolare (fără teleport), mod fantomă, înregistrare GO cu serviciu foreground.
- **Nutriție**: ML Kit (cod de bare, on-device) → OpenFoodFacts (valori verificate).
  Principiu din handoff: AI-ul identifică, baza de date dă valorile. Analiza pozelor cu AI
  e pregătită ca pas următor.
- **Video**: Media3/ExoPlayer — loop-uri mute, poster fallback, pauză off-screen.
  Asset-urile foto/video sunt preview-uri Adobe Stock (watermark), conform manifestului
  din handoff — de licențiat sau înlocuit cu generări AI înainte de lansarea publică.

## Structură

```
app/src/main/java/com/forja/app/
  core/designsystem/   tokens + componente (butoane, carduri, video, tab bar, efecte)
  core/data/           Room (v7), DataStore, repos Firebase (auth, prieteni, prezență, familie)
  core/network/        ForjaApi (serverul central), InsightsApi (site-ul), OpenFoodFacts
  core/location|sleep|focus/  servicii foreground (GO, somn, blocare)
  core/explore/        ExploreTracker — zone deblocate + locuri unde ai stat
  core/map/            ForjaTiles — dale cu rezervă și offline
  core/cleanup/        CleanupEngine, DocumentOrganizer
  feature/             splash, onboarding, auth, dashboard, workout, nutrition,
                       sleep, map (explorare, locuri, familie), focus, cleanup, permissions (Echipare), profile
server/
  worker.js            forja-api — mese, somn, /v1/organize (Cloudflare Worker)
  insights-worker.mjs  forja-insights — site-ul (hartă, organizare, telefon pierdut, cuplu, somn, fișiere) + teste
```

## Dezvoltare locală

Android Studio (Ladybug+): deschide folderul, sync, Run. Sau în terminal:
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
