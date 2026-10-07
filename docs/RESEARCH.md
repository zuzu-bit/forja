# FORJA · Remote Android Research

Modulul Research extinde aplicația existentă pentru telefoane de laborator înscrise explicit. Ecranele de fitness, nutriție, somn, hartă, Focus și Detox rămân funcționale. Activarea Research se face separat în **Profile → Device / Lab Access**, cu asociere la un cont Firebase și o sesiune de laborator.

Observația Lab cere o notificare foreground vizibilă, cu acțiune de oprire. Dacă permisiunea de notificări sau canalul Lab sunt dezactivate, acest modul refuză pornirea sau oprește colectarea; funcțiile obișnuite de fitness își păstrează comportamentul și propriile setări.

## Infrastructura reutilizată

- Android: Kotlin, Jetpack Compose, Firebase Auth, OkHttp și WorkManager.
- Datele de fitness rămân în baza Room existentă `forja.db`, versiunea 5. Jurnalul Research folosește o bază separată; nu impune migrarea sau ștergerea jurnalelor existente.
- `CloudSync` continuă să sincronizeze mesele confirmate, rapoartele de somn și activitățile finalizate în Firestore, sub `users/{uid}`. Acest flux rămâne separat de telemetria Research.
- Backend-ul rămâne Cloudflare Worker. Rutele existente pentru analiza meselor, somn și media nu devin rute Research.
- Autentificarea investigatorului și a telefonului folosește identități Firebase. O adresă `deviceId` sau un nume de dispozitiv nu acordă acces.

## Ce înseamnă LIVE și HISTORY

Un eveniment observat primește un UUID `eventId`, un `deviceId` și un `sequenceNumber` alocat tranzacțional pe dispozitiv. Persistarea în jurnalul Room precede upload-ul. Evenimentele nesincronizate sunt trimise în ordinea secvenței, iar serverul deduplică după identitatea evenimentului și dispozitiv.

`sourceTimestamp` este timpul raportat de sursă. `receivedTimestamp` este momentul recepției locale. `serverReceivedTimestamp` este alocat de server la prima acceptare. Întârzierea de upload și modificările ceasului telefonului nu trebuie ascunse prin înlocuirea timpului sursei.

LIVE arată ultima stare observată și fluxul noilor evenimente sincronizate. HISTORY interoghează evenimentele persistate. Starea păstrează timpul observației și poate deveni veche: `ONLINE` indică o conexiune recentă, nu dovedește că toate sursele au permisiuni sau că o observație este încă actuală.

Fluxul live folosește transport Internet autentificat. Investigatorul nu are nevoie de aceeași rețea, USB, ADB sau IP-ul privat al telefonului. Un investigator offline poate relua citirea din cursorul serverului; un telefon offline păstrează coada locală.

## Consimțământ, asociere și revocare

1. Organizatorul creează o sesiune de laborator și o invitație de înscriere.
2. Participantul se autentifică în aplicația Android și confirmă înscrierea telefonului.
3. Serverul confirmă proprietarul, sesiunea și un nou `deviceId` înainte de colectare.
4. Sursele sensibile se activează separat. Activarea unei surse nu înlocuiește acordarea permisiunii în Android.
5. Investigatorii primesc roluri în sesiune. Citirea și solicitarea de originale sunt autorizate separat.
6. Deconectarea, schimbarea contului sau revocarea opresc observația/sincronizarea acelei asocieri. O nouă înscriere nu reasociază coada veche altui dispozitiv sau cont.

Deconectarea fără Internet oprește imediat accesul local și păstrează separat intenția de revocare. WorkManager reîncearcă revocarea când există conexiune și contul proprietarului este autentificat; un alt cont nu trimite revocări în numele celui anterior. Până la confirmarea serverului, organizatorul poate revoca sesiunea/alocarea din Web. Acest interval trebuie afișat distinct de starea locală „oprit”.

Nu există shell Android, executare arbitrară, bypass de permisiuni sau utilizare a Accessibility Service pentru extragerea conținutului altor aplicații. Detox își păstrează funcția existentă; scrisoarea personală și cuvintele declanșator declarate exclusiv locale nu sunt colectate de Research.

## Surse și limite Android

| Sursă | Mecanism legitim și limite |
| --- | --- |
| Aplicații | `UsageStatsManager`, după activarea Usage Access, interogat la 5 secunde în sesiunea foreground activă. Android nu oferă un callback universal pentru schimbarea aplicației foreground. Foreground este ultima tranziție observată, nu o captură a ecranului; istoricul de utilizare anterior pornirii observatorului nu este importat. |
| Notificări | `NotificationListenerService`, activat de utilizator. Se stochează numai câmpurile pe care Android le expune. Conținutul sensibil poate fi redactat de sistem; nu există acces implicit la conversații. |
| Media | `MediaStore` și observatori de conținut, numai pentru rânduri noi/modificate după activare, în limitele accesului acordat. Android 30+ folosește generații și pagini de 200; versiunile 26–29 folosesc paginare limitată și semnalează provideri fără suport. EXIF/GPS și miniaturile pot lipsi. Miniatura JPEG are cel mult 15 KiB; o fotografie inaccesibilă nu produce un artefact inventat. |
| Locație | Callback-uri de locație la aproximativ 15 secunde cu precizie echilibrată și colectoarele existente pentru hartă/activitate, doar cu permisiunea și sursa active. Precizia, timpul și provider-ul sunt păstrate; coordonatele pot fi aproximative. |
| Rețea | `ConnectivityManager.NetworkCallback`. SSID/BSSID pot fi indisponibile sau redactate; starea Wi-Fi nu oferă istoricul traficului și nici conținutul conexiunilor. |
| Bluetooth | Scanare BLE autorizată, cu consum redus, 15 secunde la fiecare 2 minute. Numele/adresa pot lipsi sau se pot schimba; RSSI este o observație, nu o poziție exactă. Nu este o scanare continuă a tuturor dispozitivelor Bluetooth Classic. |
| Contacte | `ContactsContract`, numai cu permisiune explicită: snapshot la activare și callback-uri ulterioare, maximum 500 de contacte, cu eveniment explicit de limită. |
| Fișiere | Storage Access Framework, pentru folderul ales explicit: index inițial, callback-uri de provider și verificare de rezervă la 60 secunde. Maximum 500 de intrări și adâncime 6, semnalate prin `observation_limit`. Nu există acces general la directoarele private ale altor aplicații. |
| Activitate | Evenimentele sesiunilor GO și antrenamentelor FORJA existente. Nu este implementată recunoaștere pasivă generală a mersului/staționării; nu se deduce un eveniment absent dintr-un raport sumar. |
| Somn | Pornire, mișcări, evenimente audio deja detectate, alarmă și raport din modulul Sleep existent. Research nu pornește înregistrare audio separat. |
| Nutriție | Mese confirmate, fotografii asociate și coduri de bare din fluxurile existente. La analiza inițială, aplicația nu avea un flux de înregistrare a apei; `water_logged` nu trebuie pretins fără un producător real. |

Originalele media și documentele rămân pe telefon. O solicitare validată transferă numai artefactul ales, cu verificarea accesului actual, limită de **8 MiB** și expirarea copiei tranzitorii la **5 minute de la solicitare**. Telefonul verifică solicitările aproximativ la 25–30 secunde în sesiunea activă; un telefon offline sau o observație oprită nu furnizează originalul. Citirea și transferul originalului rulează într-un job separat de upload-ul jurnalului, astfel încât un transfer lent să nu blocheze evenimentele live. Solicitarea și descărcarea apar în auditul investigatorului, nu ca activitate originală a telefonului.

## Web, CLI și API

Interfața este disponibilă la `/research` pe același Worker. CLI-ul folosește o gramatică comună browser/teste Node; validarea serverului se aplică suplimentar fiecărui endpoint. Comenzile nu sunt trimise către un shell. Orele și datele CLI sunt în **UTC**.

```text
devices
use LAB-S23-04
status
watch all
watch apps
watch notifications
timeline --last 2h
timeline --date 2026-10-04
timeline --from 18:00 --to 22:00
around 20:17 --window 5m
notifications search "Bogdan"
media list --date 2026-10-04
media metadata IMG_2041
media get IMG_2041
location history --from 18:00 --to 22:00
files search "*.pdf"
activity history --today
sleep history --today
nutrition history --today
evidence search "Bogdan"
stop watch
```

`use` selectează un dispozitiv din lista alocată contului; ID-ul canonic este UUID-ul asocierii. Numele afișat este o etichetă de laborator. `media get` și `files get` creează cereri pentru artefacte din indexul observat, nu pentru căi arbitrare de fișiere.

Toate rutele API de mai jos au prefixul `/v1/research` și cer `Authorization: Bearer <Firebase ID token>`, verificat de Worker pentru proiectul FORJA.

| Rută | Scop |
| --- | --- |
| `POST /sessions` | Organizator: creează o sesiune și primește invitația de înscriere. |
| `POST /sessions/{sessionId}/invitations` | Organizator: emite o invitație nouă, cu o singură utilizare și expirare. |
| `GET/POST /sessions/{sessionId}/assignments` | Organizator: citește/acordă roluri `viewer` sau `researcher`. |
| `DELETE /sessions/{sessionId}/assignments/{uid}` | Organizator: revocă alocarea unui investigator. |
| `DELETE /sessions/{sessionId}` | Organizator: revocă sesiunea de laborator. |
| `POST /devices/enroll` | Participant: consumă invitația, confirmă consimțământul și asociază un `deviceId` nou. |
| `GET /devices` | Lista dispozitivelor autorizate pentru contul curent. |
| `DELETE /devices/{deviceId}/association` | Proprietar: revocă asocierea telefonului. |
| `GET /devices/{deviceId}/status` | Stare observată, timpi și vârsta conexiunii. |
| `POST /devices/{deviceId}/events` | Proprietar: lot ordonat de evenimente și confirmarea acceptării/deduplicării. |
| `GET /devices/{deviceId}/events` | Istoric filtrat și paginat: sursă, timp, căutare și cursor. |
| `GET /devices/{deviceId}/stream` | SSE autentificat, cu reluare prin `afterSequence` și filtre de sursă. |
| `GET /devices/{deviceId}/artifacts` | Index de metadate/miniaturi observate. |
| `GET /devices/{deviceId}/artifacts/{artifactId}` | Metadatele artefactului și evenimentul original care l-a indexat. |
| `POST /devices/{deviceId}/commands` | Solicitare predefinită `artifact_get`, pentru un artefact media/file. |
| `GET /devices/{deviceId}/commands` | Proprietar: telefonul citește solicitările în așteptare. |
| `POST /devices/{deviceId}/commands/{commandId}/result` | Proprietar: rezultat limitat și verificat al transferului. |
| `GET /devices/{deviceId}/commands/{commandId}/download` | Descărcare autentificată a originalului tranzitoriu. |
| `GET /devices/{deviceId}/audit` | Acțiuni ale investigatorilor, separat de evenimentele dispozitivului. |
| `POST /devices/{deviceId}/evidence/search` | Recuperare de dovezi cu evenimentele originale ca citări. |

SSE folosește lease-uri de 60 secunde, cu reverificarea alocării aproximativ la 15 secunde și reluare din ultima secvență confirmată. Evenimentele filtrate avansează cursorul separat. HISTORY este sortat după timpul sursei și secvență; fluxul de sincronizare respectă secvența observațiilor, păstrând timpul sursei chiar dacă ceasul se schimbă.

## Analiza asistată și ce rămâne limitat

Endpoint-ul Evidence folosește planificare de interogări: termenii literali și întrebările uzuale sunt rezolvate determinist, iar întrebările mai libere sunt interpretate prin binding-ul Workers AI existent. Modelul primește numai întrebarea investigatorului și instrucțiunile protocolului; jurnalul dispozitivului, notificările, fișierele și locațiile nu sunt trimise automat modelului. Un nume sau un fragment introdus chiar de investigator în întrebare face parte din acest input.

AI-ul poate propune numai filtre validate de sursă, termen, timp sau artefact, într-o schemă strictă. Nu poate furniza SQL, comenzi Android, evenimente sau citări. Recuperarea din jurnal, comparațiile și citările sunt făcute de backend din datele originale autorizate. `generated: false` se referă la rezultatele factuale: modelul planifică interogarea, fără să genereze dovezi.

Modelul configurat este `@cf/meta/llama-3.3-70b-instruct-fp8-fast`. Întrebarea are maximum 300 de caractere, răspunsul maximum 4 KiB/256 tokens, iar așteptarea rezultatului este limitată la 6 secunde. Planurile acceptă cele 12 surse canonice, intervale absolute de cel mult 31 de zile și ferestre de 1–60 minute în jurul unui artefact observat. Apelurile AI sunt limitate la 20 de încercări/minut pentru fiecare UID, după autorizare; accesul este reverificat după planificare. Binding-ul AI nu oferă anularea calculului cloud deja început, chiar dacă limita locală de așteptare expiră.

Interfața arată metoda de planificare și starea acesteia. Lipsa binding-ului, indisponibilitatea modelului, expirarea limitei de timp sau un răspuns invalid produc un fallback explicit la căutarea literală. Un rezultat din fallback nu trebuie interpretat ca și cum întrebarea ar fi fost înțeleasă de model.

Prima pagină stabilește un `planId`, filtrele și secvența maximă a istoricului inclus. Acest snapshot este păstrat 15 minute, legat de investigator, dispozitiv, alocare și întrebare. Paginile următoare folosesc același plan și cursor; observațiile sincronizate ulterior nu se introduc în acel istoric. Comparația fixează și `currentState` la crearea planului, astfel încât paginile să compare aceeași stare cu același istoric. Starea actualizată ulterior rămâne disponibilă prin Live și `status`, sau printr-o întrebare nouă. Se păstrează maximum 128 de planuri pe dispozitiv; expirarea ori eliminarea celui mai vechi plan produce HTTP 410 și cere relansarea întrebării, fără să șteargă jurnalul.

Citările și numărul de evenimente din pagină au limitele paginării. Comparația cu 24h include separat contoare pe surse pentru întreaga perioadă și starea curentă cu referințe originale. Interogarea din jurul unui artefact citează și evenimentul de referință; un nume ambiguu sau neobservat produce o indicație explicită, fără inferențe. Un traseu absent, un contact peste limita de indexare sau o fotografie creată înainte de activare nu sunt dovezi că artefactul nu există pe telefon.

## Configurare pentru o sesiune reală

1. Păstrați proiectul Firebase existent `forja-65093` și regulile Firestore ale funcțiilor normale. Research nu introduce o colecție publică cu evenimente sau asignări modificabile de client.
2. Worker-ul păstrează binding-urile existente `AI`, `RECORDS` și `MEDIA`. Configurația adaugă `RESEARCH_REGISTRY`/`ResearchRegistry` și `RESEARCH_DEVICES`/`ResearchDevice`, cu migrarea SQLite Durable Objects `research-v1`. Istoricul, asignările și auditul se păstrează în aceste obiecte.
3. Acordați dreptul de organizator printr-un custom claim Firebase verificat `labOrganiser: true`, folosind un proces administrativ autorizat, sau configurați allowlist-ul serverului `RESEARCH_ORGANIZER_UIDS` cu UID-uri separate prin virgulă. Câmpurile profilului `users/{uid}` nu acordă roluri Research.
4. Verificați pachetul prin teste și `wrangler deploy --dry-run`, apoi publicați Worker-ul prin procesul de deployment autorizat al proiectului, cu migrarea inclusă. Un dry-run nu creează stocarea în cloud.
5. Construiți Android cu `FORJA_API_URL` setat la adresa **HTTPS** a Worker-ului. Dacă adresa lipsește, aplicația de fitness funcționează, iar Lab Access afișează indisponibilitatea serverului; nu activează un canal alternativ nesigur.
6. În `/research`, organizatorul creează sesiunea și o invitație de înscriere. Invitația expiră după 24h și poate fi consumată o singură dată. Nu publicați invitația ca parte din materialele unui challenge accesibil tuturor.
7. Participantul asociază telefonul din Profile, apoi activează numai sursele cerute de laborator și acordă permisiunile Android aferente. Pentru Files, selectează explicit folderul de documente; pentru notificări și Usage Access, folosește ecranele de setări Android.
8. Pentru un investigator separat, organizatorul adaugă UID-ul Firebase în sesiune cu rolul corespunzător. Confirmați cu două conturi că un dispozitiv nealocat nu poate fi citit, apoi testați rețele diferite, offline/retry și revocarea.

Politica de retenție a jurnalului permanent trebuie stabilită de organizator. Implementarea păstrează HISTORY până la o procedură administrativă de ștergere; expirarea originalelor tranzitorii nu șterge evenimentele, metadatele sau auditul deja persistate.

## Integrarea funcțiilor existente

Producătorii Research sunt adăugați la punctele deja existente, după succesul operației de fitness. O eroare Research nu trebuie să împiedice salvarea unei mese, a unui set, a unei activități sau a raportului de somn.

| Funcție existentă | Punct de integrare |
| --- | --- |
| Cod de bare, produs și masă manuală | `NutritionViewModel.onBarcode`, `confirmPending`, `addManual` |
| Masă fotografiată | `MealAnalyze.saveMeal` |
| Activitate înregistrată | `GoTrackService.begin`, callback-ul locației și `finish` |
| Locație pentru hartă și fundal | Callback-ul din `MapScreen` și `BgLocationReceiver` |
| Somn | `SleepTrackService.startSession`, evenimentele existente de sunet/mișcare, alarmă și `finishSession` |
| Sesiune de antrenament și seturi | `WorkoutViewModel`, după scrierea în Room |

## Fiabilitate și proveniență

Garanția cozii privește evenimentele **deja observate și persistate cu succes**. Lipsa Internetului sau închiderea interfeței investigatorului nu trebuie să le piardă. Confirmarea serverului marchează numai evenimentele din lotul trimis pe care acesta le-a acceptat sau recunoscut ca duplicate.

Android poate opri procesul, amâna lucrul în fundal, limita scanările sau refuza o permisiune. Force-stop, revocarea accesului, schimbarea contului, spațiul de stocare epuizat și sursele neexpuse pot produce intervale fără observații. Nu se promite înregistrare universală neîntreruptă. Reluarea WorkManager după pornirea procesului nu înseamnă că evenimentele neexpuse anterior pot fi reconstruite.

Scrierea de fitness în baza existentă și adăugarea în jurnalul Research sunt operații separate. Un crash exact între ele poate păstra datele de fitness fără evenimentul Research aferent; modulul nu revendică tranzacționalitate între cele două baze.

Rezultatele de research trebuie să includă ID-urile evenimentelor originale și limitele ferestrei căutate. Un rezultat gol înseamnă „nu există observații sincronizate în această interogare”, nu dovada că pe telefon nu s-a întâmplat nimic. Analiza asistată nu trebuie să inventeze notificări, contacte, locații sau conținut de fișiere.

## Validare

Comenzile de verificare și rezultatele efectiv rulate sunt documentate în [RESEARCH_VALIDATION.md](RESEARCH_VALIDATION.md). Testele locale și un dry-run Cloudflare nu înlocuiesc testarea pe telefon real, verificarea permisiunilor Firebase publicate și validarea unei sesiuni de laborator cu acces Internet separat.
