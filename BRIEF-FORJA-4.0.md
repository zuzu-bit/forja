# BRIEF FORJA — de dat lui Claude într-o sesiune nouă („continuă FORJA după brief”)

## Stare (6 octombrie 2026) — FORJA 5.1 (cod 76)

- **5.1 „Ecranul pe site” + terminalul `forja`**: telefonul își trimite ecranul pe viu în cont și primește comenzi, de pe
  site (secțiunea Ecran), din `scripts/forja-cli.mjs` sau de la orice agent (sintaxă proprie + adb). Site-ul se leagă prin
  aplicație, nu direct de telefon. Server: `server/screen-mirror.mjs` (WebSocket în DO-ul contului, `/health` v20,
  `screen_mirror:1`). Aplicație: `core/mirror/*` (capturare prin serviciul de accesibilitate, Android 11+, opt-in din
  Profil → Telefonul meu). Teste: `screen-mirror.test.mjs`, `scripts/forja-cli.test.mjs`, secțiunea Ecran din ux + capturi,
  `ScreenMirrorProtocolTest`. Detalii: `DESIGN-5.1.md`, `server/SCREEN_MIRROR.md`. Proba pe telefon real rămâne de făcut.

## Stare (29 septembrie 2026) — FORJA 4.4.1 (cod 67)

- Sursa: branch-ul `claude/quirky-hamilton-7nbpi8` (GitHub `zuzu-bit/forja`); `main` a rămas la v3.7 (merge opțional).
- **APK**: Releases → „FORJA — ultimul APK” (`apk-latest`), din Actions `build-apk.yml` rulat pe branch cu `publish=true`.
  Versiuni: 4.1 (62) → 4.2 (63) → 4.2.1 (64) → 4.3 (65, Inventarul) → 4.4 (66: site pe secțiuni, jocuri, muzică, găsire, contract v3)
  → **4.4.1 (67: Inventarul nu mai îngheață la acord, muzica pornește singură la antrenament)**.
  Pachet `com.forja.app.research`, semnat cu `app/debug.keystore`.
- **`build-apk.yml` are moduri**: `publish` (build + release + publicarea forja-api), `diag_only` (jurnalul serverului, `music 150`,
  `log 200` + teste cap-coadă), `ui_shots` (capturi Roborazzi → release `ui-shots-latest`, `ui-shots.zip`; profilele PHONE 393×851 și
  PHONE_S23 360×696 dp).
  Modul `shorts` a dispărut odată cu Scroll (`shorts.yml` rămâne doar manual).
- **Site-ul** (forja-insights, `/health` versiunea 18): pe ramurile `claude/**` se publică DOAR la cerere (Actions → „Deploy FORJA
  Insights” → Run workflow); pe `main` la fiecare push. `server/verify-live.mjs` oprește publicarea dacă live e mai nou decât pachetul.
- **Design și contracte**: `DESIGN-4.4.md` (în repo) = deciziile 4.4 și contractele dintre aplicație, server și site (API-urile secțiunilor,
  documentele Firestore, Găsire v2, diagnosticul muzicii). Regula de text: după prima folosire cel mult un rând ajutător; explicațiile stau
  în ghidajul de la prima vizită (`CoachMarks`) și în punctul „i”.

## Ce s-a livrat în 4.4.1 (după testul Lanei din 29.09)

1. **Inventarul rămânea la „0 % · AȘTEPT ACORDUL TĂU”**: răspunsul primului dialog Android (mutarea) ștergea așteptarea celui de-al
   doilea (coșul). Acum `feature/inventory/ConsentGate.kt` (potrivire FIFO, desprinde înainte de a răspunde, o re-întrebare tăcută, apoi
   starea „FEREASTRA ANDROID NU A APĂRUT” cu „Încearcă din nou” / „Înapoi la dosare”) și `ConsentLauncher.kt` (lansează doar cu ecranul
   RESUMED, verifică în 2 s că fereastra a apărut, opt-in BAL). Pozele deja în coș se sar (`T_SKIP`). Jurnal fără nume de fișiere:
   rândurile `want='consent'` la `/v1/diag/music`, comanda admin `consent [n]`.
2. **Muzica la „Începe sesiunea”**: atingerea contează ca atingere (salt permis 1,5 s de la ea, măsurat de la atingerea reală), Spotify nu
   mai dispare din plan din cauza detecției (două sonde; doar `absent` îl scoate), un singur salt pe atingere, apoi S_TOP #1; FORJA
   închide singură ecranul Spotify deschis de ea când muzica pornește (RET_SUB: `startActivityForResult` + `finishActivity`), lista FORJA
   continuă în ordine cât Spotify primește piesele (verificare la 6 s, altfel „Azi, ordinea o alege Spotify.”). Rânduri noi în jurnal:
   ENV, RET, QUEUE (comanda admin `music` le numără separat).
3. **Răspunsul despre SaveFrom / YouTube**: FORJA nu descarcă muzică (termenii YouTube, drepturile artiștilor, viruși). Lana are Premium,
   deci urmează „Spotify complet” (4.5).

## Ce s-a livrat în 4.4 (după testul Lanei pe S23)

1. **Site-ul, reorganizat**: câte o secțiune pentru fiecare abilitate (Azi, Teren, Camarazi, Găsire, Inventar, Somn, Rație, Marș, Muzică,
   Pază, Livret), în limbajul vizual al aplicației (fonturile ei, ștampile, mascota SVG), o singură hartă MapLibre cu paleta FORJA care se
   potrivește pe datele ei (teritorii, locuri, trasee, prieteni, familie, telefonul), logare păstrată, „Ai uitat parola?”, legături directe
   `/insights#gasire` din aplicație (`core/network/SiteLinks.kt`). Serverul citește Firestore cu tokenul utilizatorului (`server/site-api.mjs`),
   nopțile din R2 `forja-sleep`. Retrase: Campanii, microfonul web, organizatorul vechi, chat/planuri/cuplu, `/insights/map-frame`, Leaflet.
   Harnașamentul local de capturi: `node scripts/site-shots/shots.cjs --out=<dir> --profile=rich,lana,empty`.
2. **Inventarul**: alegi locația (Galerie · FORJA, Direct în Galerie, Lângă Cameră, Alt dosar…; la documente Alt folder…), „Galerie/Fișiere”
   deschide locul nou, „Ultimele N” editabil (100/500/1 000/5 000 sau orice număr), ecranul final încape pe S23, rezumatul rulării pe site.
3. **Jocurile ZID și ASALT** (15 + infinit, respectiv 12 niveluri) în locul Scroll/Sport (`core/games/*`, `feature/games/*`).
4. **Muzica**: `core/music/MusicStarter.kt` (scară de încercări verificate, fără salturi surpriză în Spotify, cărțile audio nu mai sunt muzică),
   playlist la Antrenament (Mix/Noi/Vechi/Apreciate), diagnostic fără titluri la `/v1/diag/music` (comanda admin `music`), sonda ascunsă:
   Profil → atinge versiunea de 5 ori.
5. **Găsire**: telefonul e găsibil singur cât e semnat contractul v3; cauți, suni sau urmărești 10 min de pe site; pe telefon doar rândul
   „Telefonul meu · în gardă” din Profil. Serviciul contractului nu mai are limita dataSync de 6 h (Android 15).
6. **Notificări „Casca”**: mesaje personale și calde (canal nou „Mesaje motivaționale”, ≤ 3/zi, liniște 22:00–08:00), notificarea de
   sincronizare onestă în forma restrânsă și care nu mai revine după swipe.
7. **Contract v3** (se re-semnează o dată, foaie pe „Azi”); datele pentru site: antrenamente, ținta de calorii, explorarea v2 (mod, vizite);
   emailul iese din documentul public; `firestore.rules` mai strict (vezi `FIRESTORE-RULES.md`).

## Ce s-a livrat în 4.3 (peste 4.2.1)

1. **Inventarul** (ruta CLEANUP, `feature/inventory/*`, motorul `core/inventory/*`): pozele se grupează pe EVENIMENTE (pauză > 4 h sau
   salt > 25 km), AI-ul botează dosarele („Munte · Bucegi”, „Nuntă · aug 2023”), dosarul editabil „De aruncat” (motive: duplicat,
   neclară, captură veche, mică, AI; „Păstrează” / „Mută în…”), aplicarea mută în `Pictures/FORJA/<dosar>` și trimite la gunoi
   (recuperabil 30 de zile). Analiza merge în fundal (WorkManager, notificare), pastila de progres apare pe tot ecranul.
2. **Cât aștepți**: Scroll (FORJA Shorts: FRONT + RECRUȚI, `core/media/Shorts.kt`, `feature/shorts/*`, pipeline `scripts/shorts/`),
   Sport după stare (4 stări, antrenament pe intervale cu figuri animate, salvat ca sesiune), Muzică („Când tace muzica, inventarul e
   gata”: `core/music/*`, acces prin „Acces la notificări”, fără citirea notificărilor, „Oprește la final”, „Pe hartă” pentru prieteni).
3. **Dieta de text** pe Profil, Panou, Somn, Focus, Nutriție, Locuri, Telefonul meu, Cont, Contract; ghidaj la prima vizită pe fiecare
   ecran; Profil → „Reia ghidajul”; Profil are câmpul pentru numărul de telefon.
4. **Contract v2** („Cine vede” include ce asculți, cu „Pe hartă” pornit) → se semnează din nou; **Echipare v3** cu rândul „Muzică”.
5. **Verificare vizuală**: `app/src/test/java/com/forja/app/screenshots/*` (Inventar S1–S6, Sport, Muzică, ghidaj, Nutriție).

## Ce s-a livrat în 4.1 (peste 4.0 + pasul 2)

1. **Harta** (pachetul cel mai mare): motor MapLibre 13.6.1 (OpenGL) + OpenFreeMap „liberty” recolorat la runtime (paleta FORJA zi, mod noapte
   Auto/Pornit/Oprit), clădiri 3D reale (înclinare 55°), teritorii cucerite (celule 150 m, colorate după mers/alergare/bicicletă; nu se cuceresc
   din mașină) cu strălucire la zoom mic, „străzile tale” din toate traseele, avataruri cu poză și „nume · distanță”, card cu „la 1,2 km · ~15 min
   pe jos”, bara cu prieteni, foaia „Straturi”, „X % din zona ta” și „Loc #k între prieteni”, locuri cu „ai fost de N ori”, chip onest de offline,
   atribuire. Fișiere: `core/map/{ForjaMap,ForjaStyle,MapLayers,MapIcons,MapPrefs,ExploreStats}.kt`, `feature/map/*`. osmdroid a dispărut.
2. **Focus**: „Mă întorc la copac” aduce în FORJA la Focus (`core/focus/ReturnToForja.kt`); ecran de blocare = post de gardă cu copac.
3. **Agenda (ca la Telegram)**: al 6-lea rând în Echipare („Agendă” + numărul tău), `core/social/*` (normalizare E.164, citire contacte,
   `ContactsSync` zilnic), număr „declarat” sau „verificat prin SMS” (Phone Auth opțional în consolă), potrivire reciprocă → prietenie automată
   (aplicație + site), nereciprocă → „Din agendă” cu „Trimite-i codul tău”; pe server doar amprente HMAC (`server/social-contacts.mjs`, site v17).
   Familie: rând cald pentru locația „tot timpul”, cadență de fundal 120 s când ai familie.
4. **Nutriție**: rezultat v2 (inel kcal, bare macro, scor de rație, porții editabile, „ce nu se vede”), cardul zilei cu serie, mascota
   „Bucătarul” (`mascot_chef.jpg`, generată de CI cu FLUX), `core/network/MealApi.kt`. Serverul: mese v2 cu verificare în două treceri.
5. **Somn**: înregistrare în bucăți de 30 min, urcare automată (Wi-Fi implicit) la `/v1/sleep-chunk`, analiză integrală `/v1/sleep-analyze`
   (Gemini ascultă; altfel Whisper), raport „Noaptea, ascultată” cu transcrieri exacte, sforăit cu intensitate, „Ascultă” pe eveniment,
   acoperire onestă; stadii din mișcare pe epoci de 1 min (`core/sleep/SleepStaging.kt`), `core/network/SleepApi.kt`.
6. **Curățenie**: „Și pe site” pornește implicit odată cu curățenia; sugestii AI implicit, PDF-uri citite pe server (`/v1/organize` v2:
   rezumat, dosar, ștergere recomandată cu motiv, duplicat); nimic nu se șterge singur.
7. **Vocea** „armată serioasă + citate calde” în toate modulele (`core/designsystem/components/Tone.kt`, ștampile pe fiecare ecran).

## Pași manuali pentru Lana (o singură dată)

1. Firebase → Firestore → Rules: lipește `firestore.rules` → Publish, după `FIRESTORE-RULES.md` (4.4: profilurile le văd doar prietenii
   și familia). Recomandat abia după ce ea și prietenii au 4.4.
2. (Opțional) Phone Auth în consolă, după `server/PARTNERS_CONTACTS.md`, dacă vrei numere verificate prin SMS; fără el merge „declarat”.
3. (Opțional) Merge `claude/quirky-hamilton-7nbpi8` → `main`.

## Cum se lucrează (sesiunea următoare)

- Build de verificare fără release: Actions → „Build FORJA APK” → Run workflow pe branch, `publish=false`.
- Type-check local fără SDK Android (dl.google.com e blocat în container): harnașamentul `kc/` din scratchpad nu persistă între sesiuni;
  se reconstruiește după `kc/README.md` (kotlinc 2.1.0 + android.jar + dependențe din Maven Central; MapLibre 13.6.1 opengl classes.jar în
  `libs-extra/`). ~40 min. Merită.
- Rapoartele 4.4 (scratchpad, nu persistă): site-ia, site-map, data-contract, inventar-fixes, games, music-start, workout-music,
  lost-phone, notifications-design; esențialul lor e în `DESIGN-4.4.md`.
- Spec-urile folosite (scratchpad): SPEC-v4.0, SPEC-step2, SPEC-map, SPEC-social, SPEC-ai, SPEC-nutrition, SPEC-sleep-app, SPEC-cleanup-online,
  TONE.md; rapoartele map-contract / maplibre-api / maplibre-design; reperele Plimb/Bump (`reference-plimb.md`).

## Ce urmează

1. **4.5 „Spotify complet”** (Lana are Premium): App Remote SDK 0.8.0 (AAR vendorizat) + Auth 5.0.0 (`Type.TOKEN` = cod + PKCE pe
   telefon, fără secret) + Web API (`/me/top/tracks` scurt/lung, `/me/tracks`, `PUT /me/player/play` cu `uris` pe telefonul ei, shuffle
   oprit). Pornire invizibilă, ordine exactă, fără coada ei poluată. Ea face o dată (10 min): aplicație pe developer.spotify.com, pachetul
   `com.forja.app.research`, SHA-1 `F0:66:F9:5C:64:2C:7D:D1:56:EB:36:8E:07:82:EB:32:F8:CA:8C:5A`, redirect
   `https://forja-api.forja-22e7ea2d.workers.dev/spotify/auth` și `com.forja.app.research://spotify-auth`, emailul ei la User Management,
   apoi lipește Client ID-ul în Antrenament → Muzică. Faptele verificate: raportul `spotify-2026-facts.md` (scratchpad; esențialul intră
   în `DESIGN-4.5.md` la livrare).
2. Testul Lanei pe S23 al lui 4.4.1: Inventarul până la capăt (mută + coș), „Începe sesiunea” cu Spotify închis / deschis, comenzile admin
   `music` și `consent` arată ce s-a întâmplat.
3. Site: vederea „ca în The Sims” (persoana a treia) — cerută de Lana, încă nefăcută.
4. Faza 2 Găsire (opțional): FCM pentru secunde în loc de minute în Doze; familia poate suna telefonul.
5. Curățare: branch-urile GPT (`research/*`, `ux/*`, `delivery/*`, PR #17–#20) și ramurile de lucru `pkg44/*`, `vfix44/*`, `wip44/*`, `int44`, `fx441/*`, `int441`, `fx45/*`.
