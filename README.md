# FORJA — Android · „REAL & VIU"

Aplicația nativă FORJA (Jetpack Compose, dark-only, în română), construită după prototipul
Claude Design din `design_handoff_forja`. Fitness & lifestyle: antrenamente cu video,
nutriție cu cod de bare + baza de date OpenFoodFacts, somn, hartă socială live cu prieteni
reali (Firebase) și Focus (blocare de aplicații, onestă, fără AccessibilityService).

## Ce e nou în 4.0 (septembrie 2026)

Pachetul aplicației este acum `com.forja.app.research` (versionCode 60), ca 4.0 să se instaleze **peste**
aplicația „3.7-online” de pe telefon, cu același cont și aceleași date locale (baza Room migrează fără pierderi).

- **Hartă (MapLibre + OpenFreeMap)**: motor vectorial nativ cu stilul `liberty` (fără cheie, fără limite) recolorat
  la runtime în paleta „FORJA zi” (caldă, ca Plimb) și „FORJA noapte” (Auto 21:00–06:00 / Pornit / Oprit), **3D real**
  (pitch 55°, clădiri extrudate din `building-3d`), etichete în română. **Teritorii cucerite** — celulele de ~150 m prin care
  treci pe jos / alergând / pe bicicletă (nu din mașină) se colorează după mod, cu strălucire la zoom mic, „X % din zona ta”
  (cerc de 5 km) și „Loc #k între prieteni”; **străzile tale** (toate turele GO, amber); **locurile** unde ai STAT ≥ prag
  (30 min / 1 h / 2 h / 5 h, implicit 5 h) cu pin amber, nume, stele, notă, „ai fost de N ori” și „Recomandă prietenilor”
  (pin albastru la ei); **prietenii** cu avatar (foto sau inițiale), „Ana · 1,2 km”, bandă cu prieteni, card cu distanță și
  timp pe jos, linie punctată până la el; foaia **Straturi**; „Fără net · harta din memorie” (cache ambient 150 MB).
  **Familia**: prietenii marcați „Familie” te văd și în modul fantomă.
- **Somn**: ecranul original (alarmă circadiană + istoric „Nopțile tale”); serviciul nu mai crapă pe Android 14/15
  (tip de prim-plan după permisiuni), STOP/SNOOZE sigure, „Încă 10 minute”, alarma nu tace niciodată,
  „Veghea de noapte” (excepție de baterie + alarmă pe tot ecranul).
- **Start**: splash nou cu scântei, prezentare cu 4 „ordine de zi” (video cu parallax), cont peste video,
  **Echipare** — 5 bife (notificări, locație, microfon, poze, baterie) cu bară de progres, tranziții între ecrane.
  Prezentarea se arată o dată și conturilor existente. Pagina „Contul tău este conectat” nu mai există.
- **Curățenie v2**: „Ce curățăm azi?” (galerie / album / următoarele 50-100-300), **reluare obligatorie** de unde ai rămas,
  duplicate (SHA-256), aproape identice (dHash), capturi de ecran, neclare, mici, mari; mutare în
  `Pictures/FORJA Curățenie/<categorie>`; documente prin SAF în `Organizate/<categorie>`.
  **Curățenia online, implicit** (pachetul `cleanup-online`): „Sugestii AI” e pornit din start (DataStore propriu
  `forja_cleanup_online`, cheia `ai_on`) și analiza cu model pornește singură după scanarea galeriei și când alegi sau
  rescanezi folderul de documente (nu și la deschiderea aplicației, când folderul e doar reamintit) — `POST /v1/organize` v2
  (`core/network/OrganizeApi.kt`): loturi de ≤ 24 poze (miniaturi ≤ 512 px), ≤ 6 PDF-uri întregi (`pdfB64`, ≤ 4 MB fiecare,
  cel mult 12 pe rundă, citite și codificate base64 abia la scrierea cererii, unul câte unul — nu stau ca text în memorie),
  fragmente ≤ 2000 caractere pentru fișierele text, ≤ 30 elemente și ≤ 6 MB per cerere. Verdictele primite rămân: după
  „Următoarele N” pleacă doar pozele noi, verdictele unui folder se țin minte în sesiune și la rescanare pleacă doar fișierele
  fără verdict. Răspunsul v2 per element: `rezumat`, `categorie`, `dosar` (≤ 24 caractere), `sterge {recomandat, motiv, incredere}`,
  `duplicatDe`, plus `suggestion/folder/reason/confidence` din v1; în UI apare linia „AI: <rezumat> · dosar: <nume> ·
  copie a <fișier> · <motiv>”, dosarele propuse devin butoane „Mută N în <dosar>” (`Organizate/<dosar>` sau
  `FORJA Curățenie/<dosar>`), iar ștergerile recomandate sunt doar pre-bifate — nimic nu se șterge fără dialogul de sistem.
  Linia de stare e onestă: „Trimit 24 poze la analiză…”, „Modelul a răspuns (Claude)” (furnizorul după prefixul din `provider`:
  Gemini, Groq, Claude, OpenAI, Cloudflare), „Serverul nu răspunde; sugestiile locale rămân.”, iar când un lot pică după altul:
  „doar o parte: <motiv>”. „Și pe site (copii 24 h)” e și el implicit
  pornit (`organizer_v4/site_on`, lipsa cheii = pornit) și pornește odată cu curățenia când ești în cont; oprit sau
  neconectat, ecranul spune că analiza rămâne pe telefon. Fără cheie pe server totul merge cu verdictele locale + modelele Cloudflare.
- **Site-ul FORJA** (`server/insights-worker.mjs`, https://forja-insights.forja-22e7ea2d.workers.dev) e acum în acest repo,
  cu testele lui, și se publică automat din CI (`insights-deploy.yml`). Harta 2D/3D reparată (v27) e live ca versiunea 16.
- **Online, în sursa reală** (toate opt-in, implicit oprite): **Sincronizare în cont** (Echipare → secțiunea de jos: locație și opriri,
  activitate în aplicații, fotografii/fișiere alese, microfon live → `/v2/sessions` pe site), **Telefonul meu** (Profil → găsirea telefonului
  pierdut din panoul online, 5/15/30 min), **Explorarea „Și pe site”** (zonele și locurile tale, cu stele și note, pe aceeași hartă din panou;
  editările din laptop se întorc pe telefon), **Curățenia „Și pe site (copii 24 h)”** (protocolul 4: job-uri comune telefon–site, pozele și
  documentele analizate urcă în cont, aprobarea și organizarea se pot face din laptop), **Gemini 2.5 Flash pe site** când există cheia
  (altfel Cloudflare Workers AI).
- **Firestore**: regulile au colecții noi (`places`, `familyLoc`) — lipește din nou `firestore.rules` în consola Firebase → Publish.
- **Prieteni din agendă (ca la Telegram)** — Echipare are a șasea bifă, **Agendă**: cere `READ_CONTACTS`, apoi „Numărul tău” (E.164,
  normalizat local: `+` păstrat, `00` → `+`, `0…` → prefixul din SIM/rețea, RO implicit) și „Gata”. Numărul MEU e **declarat**
  (antetul `x-forja-phone-declared`; serverul păstrează o amprentă HMAC, nu numărul) sau **verificat** (claim `phone_number`
  în tokenul Firebase, când Phone Auth e activat în consolă — „Verifică prin SMS” din Profil; verificatul are prioritate și
  poate revendica un număr declarat de alt cont, invers nu: 409). Un lucrător WorkManager (24 h + „Sincronizează acum”)
  citește agenda, trimite loturi ≤ 200 la `POST /v2/social/contacts/match` și primește `mutual` când **și el mă are în
  agendă** (serverul ține doar amprentele numerelor din agenda mea, `contacts-of:{uid}`, ≤ 5000, 30 de zile) — atunci
  prietenia `friendships/{a_b}` apare singură la amândoi, cu notificarea „Ana din agenda ta e pe FORJA”. Potrivirile
  nereciproce stau în „Prietenii tăi” → **Din agendă** cu „Trimite-i codul tău” (nicio vizibilitate fără acordul lui).
  Numele și numerele nu se scriu pe server și nu apar în jurnale. Comutatorul „Pot fi găsit după număr” (Profil) oprit =
  DELETE pe site + lucrătorul anulat + potrivirile șterse local. Site-ul e versiunea 17 (`contacts:2`).
- **Familie mereu pornită**: cu cineva în familie, locația în fundal pornește chiar fără „Locație în fundal” (doar `familyLoc`
  se scrie — nici prietenii, nici Explorarea), la 120 s în loc de 180 s; cererea de poziții se oprește singură când nici familia,
  nici „Locație în fundal” nu o mai cer (`BgLocation.registerIfReady` reconciliază). „Prietenii tăi” arată un rând cald cu
  „Permite tot timpul” când lipsește locația „Tot timpul”.

## Somn — noaptea, ascultată (pachetul „sleep-app”)

Ce se întâmplă cu o noapte, pas cu pas (totul e estimare, nu diagnostic — și textele o spun):

1. **Înregistrare în bucăți** (`core/sleep/AacRecorder.kt`): AAC-LC 48 kbps mono, fișiere de ~30 min în
   `filesDir/sleep_full/<sesiune>/chunk_<i>.m4a`, cu manifestul `chunks.json`
   (`{session, startedAt, sampleRate, chunks:[{index, file, from, dur}], closed}`; `from`/`dur` în ms față de începutul
   audio-ului). Rotația se face la granița de mostre: bucata nouă se deschide pe firul audio (zeci de ms), cea veche
   se închide pe un fir separat, iar tamponul de captură de 2 s acoperă pauza — în condiții normale nu se pierd mostre
   (timpii vin din mostre, nu din ceas). Sesiunile vechi cu un singur `sleep_full/<id>.m4a` se tratează ca bucata 0 și
   rămân redabile (fără cronologie).
2. **Stadii din mișcare** (`core/sleep/SleepStaging.kt`, funcții pure): epoci de 1 min din mișcările accelerometrului
   (+ micro-mișcări), scor ponderat ±2 min (stil Cole-Kripke), prag treaz/somn, latența de adormire (10 min liniștite),
   „profund” = ≥ 20 min fără mișcare la > 30 min după adormire (plafonat pe ciclu), REM ≈ finalul ciclurilor de ~90 min,
   treziri (≥ 2 min), scor 0–100 explicat linie cu linie („−8: 3 treziri”). Rezultatul stă în `staging.json`,
   hipnograma și scorul din baza Room vin de aici; `measurement` rămâne `estimated`.
3. **Urcare automată** (`core/sleep/SleepUpload.kt`, WorkManager): la „M-am trezit”, pe Wi-Fi (sau „și pe date mobile”,
   din ecranul de somn) și cu bateria ≥ 15 %: `PUT /v1/sleep-chunk?session=s<id>&index&from&dur` pentru fiecare bucată,
   apoi `POST /v1/sleep-analyze` (cu `sessionMs`), apoi sondaj `GET /v1/sleep-analysis` la 20 s. Un POST duce la capăt
   ~1–2 bucăți pe server; când GET răspunde `processing` + `stale` (rularea s-a oprit), POST-ul se re-trimite cu aceeași
   listă și serverul continuă doar cu bucățile rămase. Renunțăm după 20 min FĂRĂ progres (bugetul curge din nou la fiecare
   bucată analizată), în rulări de cel mult 7 min, și păstrăm ce s-a ascultat (`status: timeout` cu evenimentele parțiale
   și „analiza s-a oprit” în ecran; „Încearcă din nou” reia de unde a rămas). Progresul e în `upload.json`; rezultatul
   normalizat în `timeline.json` (`{status: done|processing|clips_only|failed|timeout, reason, events:[{at, end, type,
   intensity, transcript, confidence}], stats:{snoreMin, snoreEpisodes, talkCount, coughCount, coverageMin, totalMin},
   limits:[…], startedAt}`; acoperirea se citește din `coverage{analyzedMs, totalMs}`, limitările din `limitari`). La final:
   rezumatul de dimineață refăcut cu cronologia (`POST /v1/sleep-summary`, cu `coverage`, `stats` în forma serverului și
   `tzOffsetMin`), Firestore (`snoreMin`, `talkCount`, `coverageMin`) și notificarea „Raportul nopții e gata” pe canalul
   „sleep”. Clientul HTTP e `core/network/SleepApi.kt`.
4. **Raportul** (`feature/sleep/SleepScreen.kt`): secțiunea „Noaptea, ascultată” — acoperirea onestă („Am ascultat 7 h 42 min
   din 7 h 50”), cronologia cu oră, tip, intensitate, transcriere EXACTĂ între ghilimele și încredere, „Ascultă” = 8 s din
   bucata potrivită (offset − `from`), „Ascultă toată noaptea” + „SARI LA MOMENT” pe bucăți. Limitările serverului se
   afișează în cuvintele lui (fără Gemini → doar transcriere Whisper: „sforăit: nu s-a putut detecta”, nu „fără sforăit”).
   Fără nicio cale de ascultare pe server: „Serverul nu a putut asculta noaptea (lipsește cheia Gemini). Ai doar clipurile
   prinse pe telefon.”
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

## „Casca în uniformă” — mascota avansează în grad cu ce faci zilnic (5.0)

Casca, mascota FORJA, poartă uniformă și avansează în grad cu punctele câștigate din ce faci tu zilnic în
aplicație. Nu bifezi nimic de mână: **misiunile de azi** se citesc din jurnale — o masă în jurnal (10), rația
completă (10), o tură de un kilometru (15) și de cinci (10), un antrenament încheiat (20), noaptea înregistrată
(15), cincisprezece minute de focus (10), un minut de respirație (5), paznicul Detox ținut (5), un loc nou pe
hartă (10), un joc câștigat (5), o comandă „Hei FORJA” (5), prezența (5), energia trimisă unui prieten (5).
Fiecare misiune dă puncte o singură dată pe zi, și când FORJA e închisă (lucrătorul orar).

**Gradele** (Recrut → Soldat → Fruntaș → Caporal → Sergent → Sergent-major → Plutonier → Plutonier-adjutant →
Sublocotenent → Locotenent → Căpitan → Maior → Colonel → General) vin din punctele câștigate vreodată, care nu
scad. Soldul se cheltuie în **garderobă**: 31 de piese pe șapte locuri (cap, ochi, corp, centură, încălțări,
spate, piept), fiecare cu un grad de la care se poate purta și un preț; fiecare grad nou aduce o piesă în dar,
pusă direct pe Casca (bocancii și centura la Soldat, capela la Fruntaș, geaca de teren la Caporal, bereta la
Sergent, camuflajul, vesta, casca, uniforma de paradă, chipiul de ofițer…). Insigna gradului e pe pieptul
hainei. Trei misiuni într-o zi fac o zi bună; șapte zile bune la rând aduc 50 de puncte bonus.

Unde se vede: cardul de pe panoul „Azi” (mascota, gradul, drumul spre gradul următor, punctele misiunilor de
azi), ecranul **Cazarma** (eroul pe jar, misiunile, garderoba cu previzualizări „cum stă”, scara gradelor,
foaia „Avansat în grad”), rândul din Profil și, pentru că ținuta e a mascotei, peste tot unde apare Casca:
bule, ghidaje, notificări. Cu vocea: „ce grad am”, „câte puncte am”, „misiunile de azi”.

Grafica e integral vectorială (`OutfitArt.kt`, generat din `scratchpad/art/forge.py`): aceeași geometrie
100×100 ca mascota, straturi în ordinea potrivită (în spate, tălpile, haina decupată de corp, centura,
pieptul, insigna, capul, ochelarii), fără imagini.

**5.0.1** (după o revizuire adversă a 4.9 + 5.0): seria de zile bune e un contor persistat (nu se rupe când
zilele vechi ies din memorie), misiunile se citesc strict în ziua clipei (fără puncte pe o zi trecută dacă
ceasul se dă înapoi; antrenamentul început aseară contează în ziua încheierii), „+N puncte” vine din
schimbarea stării (o singură dată, de la ecranul care o vede), cardul de pe panou stă nemișcat și se atinge
oriunde, insigna de grad e mai mare și mai lizibilă, cureaua căștii trece pe sub bărbie, textele garderobei
sunt neutre („pe Casca”, „în dotare”). Cu vocea: „rezumat” fără obiect dă antrenamentul (dacă e pornit) sau
ziua; „următorul” în antrenament e exercițiul; „unde e X” fără prieten și fără „pe hartă” întreabă webul;
prietenul cerut pe hartă se așteaptă până sosește lista (sau se spune că nu e); o navigare cerută cu FORJA
închisă deschide aplicația pe ecranul cerut; lista FORJA pornită cu vocea fără antrenament nu mai blochează
pauza de inventar.

## „Hei FORJA” — comenzi vocale (pentru cine nu poate sau nu vrea să se uite la ecran)

FORJA are un asistent vocal gândit și pentru colegii cu probleme de vedere: totul se poate face
vorbind, iar FORJA răspunde cu voce. Se deschide din butonul rotund cu microfon (jos-dreapta, pe
orice ecran principal), din **Profil → Asistent vocal**, din butonul **FORJA Voce** din setările
rapide ale telefonului, sau cu apăsare lungă pe iconița FORJA → **Hei FORJA**.

Apeși microfonul, auzi un bip și spui comanda. Ecranul asistentului arată comenzile pe module
(Antrenament, Muzică, Hartă, Focus, Somn, Respiră, Nutriție, Ziua ta, Mesaje & apeluri, Aplicații & web,
Pe ecran): o plăcuță cu un exemplu, iar la atingere lista întreagă, fiecare rând o atingere „Încearcă”.
Exemple (merg și în engleză):

| Spui | Se întâmplă |
|---|---|
| „Începe antrenamentul” / „… de picioare” · „Am terminat seria” · „Pauză” / „Continuă” · „Sari pauza” · „Următorul exercițiu” · „Rezumat antrenament” · „Termină antrenamentul” | conduce sesiunea live din FORJA, fără să atingi ecranul (cronometrul stă în pauză, muzica FORJA tace) |
| „Pornește muzica” · „Pornește un playlist” · „Pune muzică de antrenament” · „Pornește playlistul Rock pe Spotify” · „Următoarea melodie” · „Ce cântă acum?” · „Pauză la muzică” | playerul tău, prin sesiunile media (cu „Acces la notificări”); lista FORJA e clădită din ce asculți |
| „Unde e Ion?” · „Arată-l pe Andrei pe hartă” · „Pornește o alergare / o plimbare / o tură pe bicicletă” · „Oprește tura” | prietenul pe harta FORJA (altfel, locul în aplicația de hărți); turele GO |
| „Pornește focusul” · „Pornește detoxul digital 30 de minute” · „Cât mai am din focus?” · „Respiră cu mine” · „Câte calorii am azi?” · „Cât am dormit?” | Focus / detox, respirația pornită, jurnalul de mese și noaptea trecută, citite cu voce |
| „Trimite mesaj lui Ion: ajung în zece minute” | caută numărul în agendă, citește mesajul, întreabă „Confirmi?” și trimite SMS-ul |
| „Scrie-i pe WhatsApp lui Maria, sunt pe drum” | deschide WhatsApp cu textul gata scris |
| „Send this text to this contact” | întreabă cui și ce să trimită, pe rând |
| „Sună-l pe Andrei” / „Call John” | formează și pornește apelul |
| „Pune Phoenix pe YouTube” / „Deschide YouTube și pune Meniato” | pornește primul rezultat de pe YouTube (sau întreabă ce melodie) |
| „Caută pe Google despre căpșuni” / „Open Google and search about strawberries” | caută pe Google și citește cu voce ce a apărut pe ecran |
| „Deschide Waze și du-mă la gară” · „Arată-mi pe hartă farmacia” | navigare / loc pe hartă (Waze sau Google Maps) |
| „Citește ecranul” · „Apasă pe primul rezultat” · „Scrie salut” · „Derulează” · „Înapoi” · „Caută aici …” | lucrează în interiorul aplicației din față (cu „Comenzi pe ecran” pornit) |
| „Deschide Facebook și apasă pe notificări” · „Open Settings and search bluetooth” | deschide aplicația, apoi face acțiunea în ea |
| „Pune muzică pe Spotify” | pornește playerul |
| „Deschide WhatsApp” / „Open Waze” | deschide orice aplicație instalată |
| „Deschide antrenamentul”, „Harta”, „Profil” | navighează în FORJA |
| „Pornește somnul” / „Noapte bună” · „M-am trezit” | pornește / oprește monitorizarea somnului |
| „Cum stau azi?” · „Cine e online?” | citește progresul zilei / starea prietenilor |
| „Cât e ceasul?” · „Ce zi e azi?” · „Pune alarma la 7” | ora, data, alarma în aplicația de Ceas |
| „Ajutor” · „Repetă” · „Anulează” · „Oprește ascultarea” | ajutor, repetă răspunsul, anulează dialogul, oprește modul mereu-la-ascultare |

**În FORJA, nu doar pe telefon.** Comenzile de mai sus lucrează în modulele FORJA prin punți mici
(`WorkoutLink`, `MapLinks`, `BreathLinks`): sesiunea de antrenament e condusă din ViewModel-ul ei (cu pauză
adevărată: cronometrul și pauza dintre serii stau pe loc), harta zboară la prietenul cerut când s-a încărcat,
respirația pornește singură. „Pauză”, „continuă” și „următorul” spuse singure aleg singure: antrenamentul dacă
e pornit, altfel muzica, altfel ecranul. Butonul plutitor cu microfon apare și în sesiunea live.

**Mereu la ascultare.** Din ecranul asistentului poți porni „Hei FORJA mereu la ascultare”: un
serviciu în fundal (cu notificare permanentă, cum cere Android) ascultă cuvântul „Hei FORJA” și
cu ecranul stins. Pe telefoanele cu recunoaștere pe dispozitiv (Android 12+) ascultarea e locală;
altfel trece prin serviciul de recunoaștere al telefonului (Google), ca orice dictare.

**Comenzi pe ecran (în alte aplicații).** FORJA are un singur serviciu de accesibilitate, „FORJA · Accesibilitate”,
cu două roluri: Paznicul Detox din Focus și comenzile vocale pe ecran. Se pornește o singură dată, din panoul de
început „Echipare” (bifa „Accesibilitate”), care deschide direct pagina lui din Setări → Accesibilitate. Cu el pornit,
FORJA vede ce e pe ecranul aplicației din față și lucrează în ea: citește conținutul cu voce, apasă pe ce îi spui,
scrie, caută, derulează, merge înapoi. După „caută”, „apasă” sau „deschide X și …” citește singură ce a apărut.
Citește ecranul doar când Detoxul e pornit sau când o comandă vocală o cere; nu ține jurnal și nu trimite nimic
nicăieri. Cu „Hei FORJA” mereu la ascultare (sau butonul „Ascultă acum” din notificare / tile-ul „FORJA Voce”)
comenzile se dau direct din aplicația în care ești, fără să deschizi FORJA.

**Permisiuni.** Microfonul e necesar. Contactele, SMS-ul și apelurile se cer doar când o comandă
are nevoie de ele; fără ele FORJA deschide aplicația de mesaje / telefon cu totul completat, și
tu doar apeși „Trimite”. Nimic din agendă sau din mesaje nu pleacă de pe telefon.

**Accesibilitate.** Ecranul vocal are butoane mari, contrast puternic, descrieri pentru TalkBack
și o zonă „live” care anunță răspunsurile. Dacă folosești TalkBack, poți opri „Răspunsuri cu
voce” ca să nu auzi totul de două ori. Comenzile se pot și scrie, pentru cine preferă tastatura.

## Arhitectură

- **UI**: Jetpack Compose, design tokens exacți din handoff (culori, Archivo Expanded /
  Hanken Grotesk / JetBrains Mono ca fonturi variabile, cele 3 arcuri spring: snappy/natural/gentle).
- **Local (pe telefon)**: Room — antrenamente, serii, mese, somn, activități, reguli Focus.
  DataStore — preferințe. Mesele nu pleacă de pe telefon; din somn urcă doar înregistrarea nopții (pe server 7 zile,
  pentru cronologie) și cifrele raportului.
- **Cloud (între prieteni)**: Firebase Auth (email+parolă) + Firestore — profil, cod de
  invitație, prietenii (reciproc, prin cod), poziția live (doar când nu ești fantomă), energie (kudos).
- **Hartă**: MapLibre Native (`org.maplibre.gl:android-sdk-opengl`) + OpenFreeMap `liberty`, recolorat la runtime
  (`core/map/ForjaStyle.kt`); toată logica într-un `MapController` (`core/map/ForjaMap.kt`): surse GeoJSON create o dată,
  `setGeoJson` pe firul principal, prieteni glisați 400 ms, „Tu” cu puls, cameră 2D/3D; mod fantomă, înregistrare GO
  cu serviciu foreground. Straturile și modul de noapte stau în DataStore-ul propriu `forja_map` (`core/map/MapPrefs.kt`).
  Atribuire: butonul „i” al MapLibre + „© OpenFreeMap · OpenMapTiles · OpenStreetMap”.
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
  core/map/            ForjaMap (MapController + gazda Compose), ForjaStyle (liberty recolorat zi/noapte),
                       MapLayers (surse/straturi/GeoJSON), MapIcons (avataruri, foto), MapPrefs, ExploreStats
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
