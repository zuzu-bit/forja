# FORJA v26 — organizare continuă și explorare personală

Stare: contract de implementare, nu funcții livrate. Cerințe aprobate de utilizator la 27 septembrie 2026. Bază verificată: commit 6b442a16e8a92a6937c70ca91d682dbcfae3f8e4, APK v25. Site-ul v25 a fost publicat prin run 36307053396. Mediul de compilare Android este indisponibil în sesiunea curentă; acest document nu afirmă că există un APK v26.

## Experiența vizuală

Păstrăm introducerea și harta aerisită din v25. Un ecran, o selecție clară și o acțiune principală. Detaliile de acces și retenție rămân în confidențialitate și setări.

| Ecran | Alegere vizibilă | Acțiune principală |
| --- | --- | --- |
| Ce vrei să curățăm astăzi? | Galerie / Documente; Toate / Dosar | Alege sursa |
| Sursa aleasă | Toate cele rămase / Următoarele 50 / 100 / număr propriu | Organizează / Continuă |
| Lucru în desfășurare | Progres real și dosare create | Vezi rezultatul |
| Rezultat | Mutate, de verificat, duplicate propuse | Continuă cu următoarele N |
| Harta mea | Trasee, zone deblocate și locuri vizitate | Explorează |
| Loc selectat | Durată, rating, recomandare, salvat pentru mai târziu | Hai aici |
| Vizibilitate | Fantomă și destinatarii aleși explicit | Salvează |

Modul hărții 2D/3D folosește un selector compact. Detaliile unui loc sau persoanei se deschid în același panou contextual, fără panouri suprapuse peste navigarea aplicației.

## Ce există și ce trebuie schimbat

Audit direct al surselor v25:

- OrganizerSelection.select sortează descrescător după data fotografiei/modificării, apoi URI și ia N sau maximum 15.000. Repetarea unei selecții poate analiza aceleași originale.
- CleanupCatalog limitează inventarul la 15.000 de elemente. Acest plafon trebuie transformat într-o limită de lucru paginat, cu continuare verificată.
- OrganizerPlans are planuri persistate și verifică SHA-256 înaintea mutării. apply salvează contoare agregate; worker-ul transformă o stare applying întreruptă în error și cere verificare manuală.
- server/organizer.mjs păstrează metadate, copii și planuri legate de rulări temporare. Expirarea lor nu poate fi folosită ca memorie permanentă a organizării.
- CleanupContent citește text, OCR și etichete vizuale local. PDF-urile lungi și alte formate au limite explicite și pot produce rezultate parțiale.
- server/social.mjs păstrează cel mult 500 de celule explorate și 30 de rezumate de sesiuni. Nu stochează trasee complete sau vizite demonstrate de peste 5 ore.
- Partajarea continuă actuală este pentru un singur partener acceptat. Ea nu creează istoricul personal de explorare.

## Organizare care continuă de unde a rămas

### Identitate și inventar

Introducem protocolul organizer 4, menținând protocolul 2/3 pentru telefoanele existente. Un job persistent conține contul, telefonul executant, sursa autorizată, selecția, destinația, regula de clasificare și versiunea acordului.

Telefonul inventariază paginat și păstrează un manifest stabil. Fiecare fișier are propria identitate, versiunea conținutului, URI-urile cunoscute și istoricul mutărilor. Două fișiere cu aceiași bytes rămân două originale distincte; hash-ul nu înlocuiește identitatea.

- Continuă reia jobul curent și fișierele lui neterminate.
- Următoarele N selectează doar elemente eligibile, neorganizate și neblocate de alt job.
- O cerere repetată cu același request_id returnează același lot.
- Fișierele mutate și verificate sunt excluse din loturile ulterioare chiar dacă li se schimbă data sau URI-ul.
- Un fișier modificat ulterior primește o versiune nouă și poate necesita o analiză nouă.
- Destinațiile create de job nu sunt reinventariate ca muncă nouă; memoria finalizării rămâne necesară și când destinația este în interiorul sursei.
- O eroare sau un fișier indisponibil nu este numărat ca organizat.

Coada și jurnalul de elemente sunt independente de copiile temporare disponibile pe site. Se păstrează progresul și după expirarea unei rulări ori a unui upload.

### Mutări și recuperare

Persistăm intenția înainte de operație și rezultatul verificat imediat după. Etapele minime: pending, analyzed, upload_pending, uploaded, ready, applying, moved, copied_pending_removal, needs_review, failed_retryable, skipped.

La revenire după întrerupere:

| Dovezi găsite | Comportament |
| --- | --- |
| Original neschimbat, destinație absentă | Reia operația autorizată |
| Destinație verificată, original absent | Salvează rezultatul fără o nouă mutare |
| Copie verificată, original încă prezent | Finalizează doar mutarea deja autorizată, dacă acordul și versiunea originalului sunt încă valabile |
| Original modificat sau destinație conflictuală | Păstrează originalele și cere verificare |
| Rezultat ambiguu al furnizorului de fișiere | Oprește elementul pentru verificare, continuă separat elementele sigure |

Nu presupunem tranzacții atomice universale pentru toate serviciile externe de fișiere. Folosim nume deterministe pentru coliziuni și păstrăm ambele fișiere distincte. Un upload ori o indexare eșuată reia sincronizarea; nu repetă mutarea.

Dosarele sugestive provin din conținutul analizat, din preferințele utilizatorului și din destinația autorizată. Mutările automate folosesc acordul explicit al jobului. Dialogurile Android pentru modificarea galeriei și accesul SAF rămân respectate. Ștergerea este o propunere separată, motivată și confirmată, cu coș de gunoi când platforma permite.

### Telefon și site

Un singur job și aceeași versiune a progresului apar pe telefon și site, indiferent de unde s-a cerut pornirea. Telefonul execută modificarea originalelor. Site-ul trimite comenzi idempotente și primește rezultate individuale.

Copiile fotografiilor/documentelor din selecția jobului se încarcă efectiv și sunt disponibile în propriul cont pentru analiză și previzualizare. Metadatele singure nu înseamnă upload reușit. Fiecare copie se leagă de identitatea originalului, versiunea/hash-ul conținutului, job și rezultat.

Afișăm corect: așteaptă telefonul, se încarcă, analizat parțial, aplicat pe telefon, necesită acces. Comenzile concurente din telefon/site nu pot executa de două ori aceeași mutare. Schimbarea contului, revocarea acordului sau anularea opresc operațiile noi. Operația deja începută este reconciliată, cu rezultat exact.

## Analiză de conținut și sugestii de ștergere

Pipeline-ul propus:

1. Verifică bytes, tipul real, integritatea, dimensiunea și versiunea fișierului.
2. Folosește textul documentului, OCR și imaginile paginilor unde sunt relevante; păstrează acoperirea reală a analizei.
3. Detectează duplicatele identice prin verificări deterministe. Similaritatea vizuală este o sugestie distinctă.
4. Modelul multimodal propune o categorie, un dosar, motive și dovezi legate de fișier/pagină.
5. Validează schema rezultatului, ID-urile, limitele sursei/destinației și rezultatele contradictorii.
6. Aplică numai acțiunile autorizate. Documentele și imaginile nu pot furniza instrucțiuni executabile modelului.

Nu prezentăm o lectură parțială ca integrală și nu deducem inutilitatea unui document doar din vechime, aspect sau titlu. Preferințele și fișierele protejate de utilizator au prioritate. Păstrăm un original în grupurile de duplicate.

Selecția modelului rămâne bazată pe evaluare, nu pe o promisiune de „cel mai bun vreodată”. Candidați verificați în documentația furnizorului: Kimi K2.5 și Llama 4 Scout pentru text/imagine prin infrastructura Cloudflare existentă. Nu au fost activați sau comparați pe conținutul utilizatorului în această sesiune.

Setul de evaluare trebuie să includă documente românești, PDF-uri scanate, tabele, fotografii similare dar distincte, documente cu mai multe teme, scanări neclare, erori de OCR și instrucțiuni malițioase în fișiere. Măsurăm dosarul ales, dovezile reale, propunerile greșite de ștergere, acoperirea, latența și costul. Niciun schimb de model în producție fără această verificare.

## Harta personală și memoria locurilor

Trei tipuri separate de date, păstrate și sincronizate în propriul cont:

| Tip | Semnificație | Reprezentare propusă |
| --- | --- | --- |
| Traseu | Străzi/segmente parcurse, susținute de poziții valide | Linie albastră |
| Zonă deblocată | Suprafață explorată conform regulii geografice definite | Verde mentă |
| Loc vizitat | Ședere validată de peste 5 ore într-o vizită | Marcaj auriu distinct |

Culorile sunt completate de forme și o legendă compactă. Progresul de oraș se calculează pe o limită geografică și o grilă declarate; nu afișăm procente inventate sau echivalența unei singure poziții cu o stradă parcursă integral.

Regula inițială solicitată este strict mai mult de 5 ore în aceeași vizită. Se numără intervalele susținute de observații valide; o poziție urmată de cinci ore fără semnal nu demonstrează o vizită. Filtrăm precizia slabă, salturile, viteza incompatibilă și înregistrările duplicate ori în afara ordinii. Oprirea urmăririi închide intervalul. Limitele pentru goluri și raza locului trebuie stabilite și testate pe dispozitiv.

Numele unui local incert poate fi confirmat de utilizator. Datele istorice grosiere din v25 rămân explorare veche; nu se transformă retroactiv în vizite de cinci ore sau trasee precise.

Memoria este o bază de date de vizite, ratinguri și preferințe, cu ștergere controlată de utilizator. AI-ul poate sugera locuri și plimbări din aceste date; nu este sursa adevărului pentru durata unei vizite. Ratingurile și recomandările către prieteni sunt acțiuni explicite în detaliul locului.

2D/3D trebuie să folosească date geografice și randare reale. Direcția tehnică este MapLibre Native pe Android și MapLibre GL JS pe site, cu aceleași stiluri, identități, culori și date GeoJSON/vectoriale. Verificăm sursele de clădiri, licențele, cotele, consumul și suportul dispozitivelor; o simplă hartă raster înclinată nu este livrată ca 3D.

## Fantomă, familie și istoric

Istoricul personal și partajarea socială sunt independente. Eticheta „familie” singură nu acordă acces.

Fiecare destinatar ales are un acord explicit și separat pentru locația curentă, funcționarea în modul fantomă și, dacă se dorește, istoricul traseelor/locurilor. Fantomă ascunde locația față de ceilalți prieteni și arată clar „Vizibil pentru [persoanele selectate]”. Oprirea tuturor partajărilor prevalează asupra tuturor excepțiilor.

Verificăm acordurile la fiecare citire, inclusiv pe site. Blocarea sau eliminarea unui prieten revocă accesul curent și viitor. Istoricul privat nu este returnat accidental în răspunsul social general. Un contact din agendă nu devine automat destinatar. Se păstrează marcarea pozițiilor vechi și notificarea Android aferentă partajării continue.

## Verificări care blochează livrarea v26

- Reluare după închiderea procesului înainte și după fiecare mutare/receipt, fără repetarea operațiilor finalizate.
- Următoarele N după mutări și schimbări ale datei/URI-ului; galerie de peste 15.000 de fișiere.
- Original schimbat, șters extern, nume identice, conținut identic în fișiere distincte, spațiu insuficient, acces revocat.
- Expirarea copiilor online păstrează progresul și nu autorizează o mutare nouă cu date expirate.
- Pornire simultană telefon/site, request și receipt retransmise, offline după mutare și schimbare de cont.
- Progresul provine din stările elementelor; fără dublarea contoarelor ori upload declarat înainte de receipt.
- Ședere de 4h59m, exact 5h, peste 5h, intervale fără GPS, salturi și staționare ambiguă.
- Același istoric, aceleași culori și aceleași acorduri pe telefon/site; schimbare 2D/3D fără pierderea selecției.
- Fantomă cu și fără excepții; oprire totală, revocare, blocare și acces la istoric refuzat când nu este acordat.
- Validare Android pe dispozitiv și flux real cont → telefon → upload → AI → rezultat → mutare → continuare.

## Ordinea de integrare

1. Persistență și recuperare per fișier, protocol v4 compatibil, teste de întrerupere.
2. Picker simplu, loturile următoare, mutări și uploaduri reale în ambele interfețe.
3. Evaluare multimodală și activarea modelului validat, cu rapoarte de acoperire.
4. Înregistrator personal de trasee/vizite și sincronizare privată.
5. Hartă 2D/3D, zone și locuri, ratinguri/recomandări.
6. Excepții explicite pentru familie și teste de autorizare.
7. APK semnat, QA vizuală și verificare completă înainte de publicarea v26.

Surse tehnice consultate la 2026-09-27:

- Android MediaStore: https://developer.android.com/training/data-storage/shared/media
- Kimi K2.5, disponibilitate și capabilități: https://developers.cloudflare.com/changelog/post/2026-03-19-kimi-k2-5-workers-ai/
- Llama 4 Scout: https://developers.cloudflare.com/workers-ai/models/llama-4-scout-17b-16e-instruct/
- Intrări PDF/text/imagine: https://developers.openai.com/api/docs/guides/file-inputs
- MapLibre Native Android: https://maplibre.org/maplibre-native/android/api/
- MapLibre GL JS, clădiri 3D: https://maplibre.org/maplibre-gl-js/docs/examples/display-buildings-in-3d/
