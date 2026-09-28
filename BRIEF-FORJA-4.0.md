# BRIEF FORJA — de dat lui Claude într-o sesiune nouă („continuă FORJA după brief”)

## Stare (28 septembrie 2026) — FORJA 4.3 (cod 65)

- Sursa: branch-ul `claude/quirky-hamilton-7nbpi8` (GitHub `zuzu-bit/forja`); `main` a rămas la v3.7 (merge opțional).
- **APK**: Releases → „FORJA — ultimul APK” (`apk-latest`), din Actions `build-apk.yml` rulat pe branch cu `publish=true`.
  Versiuni: 4.1 (62) → 4.2 (63, contract de securitate, nutriție BitePal, mascotă animată) → 4.2.1 (64, Nutriție fără „Setări”,
  poză la „Scanează cod”) → **4.3 (65, Inventarul)**. Pachet `com.forja.app.research`, semnat cu `app/debug.keystore`.
- **`build-apk.yml` are moduri** (intrări la Run workflow, fiecare cu coada lui): `publish` (build + release), `diag_only` (jurnalul
  serverului + test cap-coadă /v1/meal și /v1/organize/clusters), `shorts` (construiește FORJA Shorts în R2), `ui_shots` (capturi
  Roborazzi → release `ui-shots-latest`, `ui-shots.zip`). `shorts.yml` și `ui-shots.yml` se pot porni direct abia după merge în `main`.
- **Serverul** (forja-api): Gemini acordat pe familii (3.x flash → gândire „low”, flash-lite → „minimal”), 503 → o reîncercare,
  429 → răcire 65 s pe model; la mese ordinea e 3.8-flash → 3.5-flash-lite (rapid) → restul flash-urilor; `/v1/organize/clusters`
  (numele dosarelor, ~3 s); `/media/*` cu Range real. Site-ul (forja-insights) folosește Gemini viu (3.5-flash-lite + aliasuri).
- **Design**: prototipul clicabil al Inventarului (Design canvas, privat, al Lanei) https://claude.ai/artifact/WwJAXGoqu3ub28Brjj9MUP ;
  specificația `DESIGN-4.3.md` stă în scratchpad (nu persistă) — regulile ei: după prima folosire cel mult un rând ajutător pe ecran,
  explicațiile trăiesc în ghidajul de la prima vizită (`CoachMarks`) și în punctul „i”.

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

1. Firebase → Firestore → Rules: lipește `firestore.rules` → Publish (v4.1 adaugă `places.visits`; v4.0 a adăugat `places`, `familyLoc`).
2. (Opțional, recomandat) Cheia gratuită Pexels pentru clipuri video reale în Scroll (pisici incluse): pexels.com/api → GitHub → Settings →
   Secrets and variables → Actions → secretul `PEXELS_API_KEY`, apoi `build-apk.yml` cu `shorts=true`. Fără ea: 12 clipuri FRONT din pozele FLUX.
3. (Opțional) Phone Auth în consolă, după `server/PARTNERS_CONTACTS.md`, dacă vrei numere verificate prin SMS; fără el merge „declarat”.
4. (Opțional) Merge `claude/quirky-hamilton-7nbpi8` → `main`.

## Cum se lucrează (sesiunea următoare)

- Build de verificare fără release: Actions → „Build FORJA APK” → Run workflow pe branch, `publish=false`.
- Type-check local fără SDK Android (dl.google.com e blocat în container): harnașamentul `kc/` din scratchpad nu persistă între sesiuni;
  se reconstruiește după `kc/README.md` (kotlinc 2.1.0 + android.jar + dependențe din Maven Central; MapLibre 13.6.1 opengl classes.jar în
  `libs-extra/`). ~40 min. Merită.
- Spec-urile folosite (scratchpad): SPEC-v4.0, SPEC-step2, SPEC-map, SPEC-social, SPEC-ai, SPEC-nutrition, SPEC-sleep-app, SPEC-cleanup-online,
  TONE.md; rapoartele map-contract / maplibre-api / maplibre-design; reperele Plimb/Bump (`reference-plimb.md`).

## Ce urmează

1. Test real pe telefon (Lana) al lui 4.3: Inventarul pe galeria ei (timp, nume de dosare, „De aruncat”, aplicarea cu dialogurile
   sistemului, > 500 de poze), cele trei moduri de așteptare, muzica pe hartă cu un al doilea telefon, ghidajele.
2. Site: vederea „ca în The Sims” (persoana a treia) și dosarele Inventarului pe site — cerute de Lana, încă nefăcute.
3. Curățare: branch-urile GPT (`research/*`, `ux/*`, `delivery/*`, PR #17–#20) pot fi închise.
