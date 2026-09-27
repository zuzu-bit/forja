# BRIEF FORJA — de dat lui Claude într-o sesiune nouă („continuă FORJA după brief”)

## Stare (27 septembrie 2026, seara) — FORJA 4.1 (cod 62)

- Sursa reală, completă și construibilă e pe branch-ul `claude/quirky-hamilton-7nbpi8` (GitHub `zuzu-bit/forja`). `main` a rămas la v3.7;
  merge-ul în `main` e opțional (după merge, orice push pe `main` construiește și publică singur).
- **APK-ul** e publicat la Releases → „FORJA — ultimul APK” (Actions `build-apk.yml`, rulat manual pe branch cu `publish=true`).
  Pachet `com.forja.app.research`, semnat cu `app/debug.keystore` (același semnatar ca 3.7-online.xx) → se instalează PESTE aplicația de pe
  telefon; Room migrează 5→6→7→8 fără pierderi. Versiuni: 4.0 (cod 60) → 4.0.1 (61, hartă OSM provizorie) → 4.1 (62, tot ce e mai jos).
- **Serverul aplicației** (`server/worker.js`, forja-api pe Cloudflare, gratuit): routerul de AI `server/ai-router.mjs` cu ordinea
  Gemini (cheie gratuită) → Groq (cheie gratuită) → Claude/OpenAI (doar dacă ar exista chei plătite) → Cloudflare Workers AI; contoare de
  limită zilnică pe model, trecere automată la următorul furnizor; `/v1/diag` arată furnizorii configurați (fără chei). Secretele GitHub:
  `GEMINI_API_TOKEN` (CI acceptă și `GEMINI_API_KEY`), `GROQ_API_KEY`, `CLOUDFLARE_API_TOKEN`. Decizia Lanei: FĂRĂ bani, FĂRĂ server local.
- **Site-ul** (`https://forja-insights.forja-22e7ea2d.workers.dev`) se publică din CI la orice push pe `main`/`claude/**` care atinge `server/**`;
  `/health` versiunea 17 (`contacts:2`, `explore_sync:1`, `content_ai:2`).

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

1. Firebase → Firestore → Rules: lipește `firestore.rules` → Publish (v4.1 adaugă `places.visits`; v4.0 a adăugat `places`, `familyLoc`).
2. (Opțional) Phone Auth în consolă, după `server/PARTNERS_CONTACTS.md`, dacă vrei numere verificate prin SMS; fără el merge „declarat”.
3. (Opțional) Merge `claude/quirky-hamilton-7nbpi8` → `main`.
4. Clipurile originale în bucketul `forja-media` (`MEDIA.md`).

## Cum se lucrează (sesiunea următoare)

- Build de verificare fără release: Actions → „Build FORJA APK” → Run workflow pe branch, `publish=false`.
- Type-check local fără SDK Android (dl.google.com e blocat în container): harnașamentul `kc/` din scratchpad nu persistă între sesiuni;
  se reconstruiește după `kc/README.md` (kotlinc 2.1.0 + android.jar + dependențe din Maven Central; MapLibre 13.6.1 opengl classes.jar în
  `libs-extra/`). ~40 min. Merită.
- Spec-urile folosite (scratchpad): SPEC-v4.0, SPEC-step2, SPEC-map, SPEC-social, SPEC-ai, SPEC-nutrition, SPEC-sleep-app, SPEC-cleanup-online,
  TONE.md; rapoartele map-contract / maplibre-api / maplibre-design; reperele Plimb/Bump (`reference-plimb.md`).

## Ce urmează

1. Test real pe telefon (Lana): harta 3D (dale, teritorii, prieteni, GO), agenda (număr + potrivire cu un al doilea telefon), nutriția v2,
   o noapte de somn cu urcare și raport, curățenia cu PDF — și lista de bug-uri. Lucruri de verificat pe dispozitiv sunt listate în
   rapoartele pachetelor (SurfaceView în tranziții, atribuirea OpenFreeMap, cadența MapLibre pe telefonul ei).
2. Contul de conversație/chat și muzica din Bump — în afara scopului; colectabile/„dopamină” pe hartă — de decis.
3. Curățare: branch-urile GPT (`research/*`, `ux/*`, `delivery/*`, PR #17–#20) pot fi închise.
