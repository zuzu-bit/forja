# Validarea extensiei Research

## Comenzi locale

Android necesită **JDK 17 complet** și SDK Android 35. Un runtime Java fără `javac` nu este suficient. Testele server folosesc `node:sqlite` și necesită Node 22 sau mai nou; CI configurează Node 22, iar rulările locale documentate folosesc Node 24.19.0. Într-un mediu obișnuit:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon
cd server
npm ci
npm test
npm run check
npm run deploy:check
```

În mediul de lucru folosit aici, SDK și JDK au fost instalate în afara repository-ului, din distribuțiile oficiale Google și Eclipse Temurin. Proxy-ul sesiunii și certificatul său CA au fost păstrate, cu verificarea TLS activă:

```sh
FORJA_API_URL=https://forja-ci.invalid \
ANDROID_HOME=/workspace/android-sdk \
JAVA_HOME=/workspace/jdk17 \
JAVA_TOOL_OPTIONS="$(cat /workspace/sdk-downloads/proxy-jvm-options)" \
./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon --console=plain
```

`forja-ci.invalid` este o adresă de test pentru verificarea căii BuildConfig. APK-ul rezultat nu este conectat la un backend operațional; pentru laborator trebuie reconstruit cu adresa HTTPS reală, conform [RESEARCH.md](RESEARCH.md). Nu copiați căile acestui mediu în configurația de producție. Dry-run verifică pachetul și configurația Worker; nu publică serverul și nu confirmă migrarea stocării unei instalări deja publicate.

## Rezultate locale confirmate

| Verificare | Rezultat |
| --- | --- |
| Compilare Kotlin/KSP și `assembleDebug` | APK debug construit cu succes. |
| `testDebugUnitTest` | 6 teste trecute: 3 politici de jurnal și 3 teste Room/SQLite reale cu Robolectric, inclusiv secvențe concurente, redeschiderea cozii și izolarea cont/sesiune. |
| `lintDebug` | Trecut: 0 erori noi, 99 avertismente și 25 informații vizibile; numai cele 4 erori originale sunt în baseline. |
| Backend `npm test` | 47 teste trecute: 5 runtime Miniflare/SQLite, 17 securitate, 9 planificare AI, 8 CLI, 4 regresie și 4 Web. |
| Runtime Cloudflare local | Jurnal ordonat/deduplicat cu trei timpi, reluare SSE filtrată pentru 240 evenimente, originale de 2,1 MiB în chunk-uri de 512 KiB, revocare/reacordare și persistență după restart. |
| `npm run check` | Verificarea sintaxei modulelor server a trecut. |
| `npm run deploy:check` | Wrangler 4.45.0 dry-run trecut: 319,52 KiB, gzip 82,25 KiB; binding-uri R2/AI existente și 2 SQLite Durable Objects. |
| Interfață Web în Chromium local | Smoke test trecut pe pagina servită de Wrangler; autentificarea Firebase și API-urile autentificate au fost simulate explicit prin fixture-uri de test. |

Ultima rulare Android completă a trecut în 1m20s. Hash-urile celor 108 fișiere de intrare Android urmărite au rămas identice pe durata verificării. APK-ul este generat la `app/build/outputs/apk/debug/app-debug.apk`, cu endpoint-ul de test descris mai sus.

Verificarea backend finală a fost reconfirmată după repornirea mediului, la 2026-10-07 aproximativ 04:07 UTC: 47/47 teste în 1,523 secunde, fără eșecuri sau teste omise, verificare de sintaxă și dry-run trecute.

Smoke test-ul Web a verificat stările fără dispozitive, comenzi CLI, reluare SSE, afișarea inertă a unui payload HTML, metadate și solicitări de artefacte, paginarea a 101 artefacte prin CLI/UI, citările Evidence și continuarea cu același plan, metadate AI afișate inert, distincția ONLINE/PAUSED și păstrarea timestampurilor offline, logout cu curățarea datelor și layout-ul la 390px. Detaliile și limitele sunt în artefactul local `artifacts/research-browser-smoke.json`; acesta nu este inclus în sursele versionate. Nu este un login real și nu validează un telefon remote.

Prima rulare lint a identificat patru erori `NewApi` deja prezente în sursele originale, plus o incompatibilitate nouă în modulul Research. Eroarea Research a fost corectată prin verificarea versiunii API. Baseline-ul [lint-baseline.xml](../app/lint-baseline.xml) conține **numai cele patru erori originale**, confirmate în commitul de bază `e22f5c8`:

- `AlarmActivity`: `setShowWhenLocked` și `setTurnScreenOn`, disponibile începând cu API 27, apelate fără verificare în codul original care declară minimum API 26.
- `DetoxBlockActivity`: `setShowWhenLocked`, aceeași limită API 27.
- `FocusMonitorService`: `unsafeCheckOpNoThrow`, disponibil începând cu API 29.

Aceste datorii de compatibilitate preexistente nu au fost rezolvate prin modificarea funcțiilor de fitness. Baseline-ul nu ascunde avertismentele sau erorile noi Research; rularea finală lint a trecut cu acest baseline restrâns.

## Verificări de servicii efectuate

La 2026-10-07, aproximativ 03:14 UTC, verificarea numai-citire a proiectului Firebase `forja-65093` a arătat:

| Verificare | Rezultat | Ce confirmă |
| --- | --- | --- |
| Endpoint Firebase Email/Password, acreditări de test intenționat invalide | HTTP 400, `INVALID_LOGIN_CREDENTIALS` | Endpoint-ul de autentificare răspunde; nu confirmă login real sau roluri. |
| Citire anonimă Firestore `ping/ping` | HTTP 403, `PERMISSION_DENIED`, `Missing or insufficient permissions.` | Cererea anonimă este blocată; nu confirmă toate regulile pentru utilizatori autentificați. |

Mediul nu are identități Cloudflare/Firebase de publicare configurate. Nu au fost publicate reguli Firestore, migrări Cloudflare sau deployment-uri din această verificare. RBAC Research trebuie testat separat de regulile Firestore ale funcțiilor sociale existente.

## Cazuri obligatorii pe telefon de laborator

Nu era conectat un telefon/emulator Android, iar `/dev/kvm` nu era disponibil. Următoarele sunt scenarii de acceptanță de rulat pe un dispozitiv real; nu sunt rezultate deja confirmate.

| Scenariu | Rezultat de acceptat |
| --- | --- |
| Utilizator normal, Research dezactivat | Dashboard, antrenamente, mese, Sleep, hartă, Focus, Detox și login/logout funcționează; nu apare telemetrie Research. |
| Înscriere cu invitație și acord explicit | `deviceId` nou, proprietar/sesiune confirmate de server, sursa DEVICE inițială; sursele sensibile cer activare separată. |
| Permisiune refuzată sau revocată | Sursa indică lipsa accesului; aplicația rămâne utilizabilă, fără retry agresiv sau date inventate. |
| Telefon și browser în rețele diferite | Evenimentele acceptate apar în Live prin Internet; niciun pas normal nu cere ADB/USB/LAN. |
| Telefon offline, apoi reconectat | Evenimentele deja persistate rămân pending; se trimit în ordinea secvenței și devin synced numai după confirmare. |
| Răspuns pierdut după acceptarea unui lot | Retrimiterea produce duplicate confirmate; HISTORY are o singură copie și timpii originali rămân stabili. |
| Investigator offline | Reluarea fluxului cu cursor recuperează evenimentele persistate în perioada deconectării. |
| Clock rollback/forward | Timpul sursei și timpul serverului rămân separate; starea veche nu înlocuiește una mai nouă doar din cauza ordinii upload-ului. |
| Pause din notificarea foreground | Observația se oprește vizibil, starea devine paused; coada poate continua sincronizarea conform politicii de consent. |
| Deconectare/revocare | Sursele și comenzile pentru asocierea revocată se opresc; vechiul `deviceId` nu se înscrie din nou. |
| Schimbare de cont sau nou laborator | Pending-ul vechi nu ajunge la contul/sesiunea nouă. |
| Revocarea unui investigator cu stream deschis | Fluxul se închide la reverificarea autorizării; istoricul și descărcările ulterioare sunt refuzate. |
| Viewer versus researcher | Viewer poate citi; o solicitare de original este refuzată pentru rolul fără drepturi. |
| Media/documents | Sunt indexate numai artefactele accesibile; originalele nu se încarcă automat. Artefactul șters/revocat produce un rezultat unavailable. |
| Original la cerere | Telefonul validează artefactul; serverul limitează mărimea și expiră copia temporară; investigatorul vede audit distinct. |
| Fotografie și evenimente vecine | `around`/timeline returnează evenimentele originale și ID-urile lor; nu combină auditul cu activitatea telefonului. |
| Întrebare Evidence în limbaj liber | Workers AI produce numai un plan validat; rezultatele citează jurnalul original. Interfața indică metoda și fallback-ul, iar paginile următoare păstrează același plan. |
| Notification content redactat de Android | Sunt afișate câmpurile expuse; conținutul absent nu este completat prin presupuneri. |
| Oprire forțată/Doze/restart | Interfața arată starea și vârsta ultimei observații; nu pretinde că intervalele fără observator au fost înregistrate. |
| Istoric fitness preexistent | Room `forja.db` și jurnalul Firestore privat rămân accesibile; înscrierea Research nu le importă fără consent și nu le șterge. |

## Verificări de securitate și regresie

Testele backend confirmă izolarea între conturi/sesiuni, roluri, invitație cu o singură utilizare, revocare, retry/deduplicare, jurnal imuabil, conflict de secvență, payload și comandă invalide, limitare de rată per UID, TTL/ștergerea chunk-urilor, paginare și reluarea fluxului. Au fost verificate și ignorarea header-elor de identitate falsificate, ordinea livrării când verificarea autorizării întârzie și faptul că metadatele schimbătoare nu măresc nelimitat starea curentă. Pagina servește un CLI care funcționează inclusiv după bundling-ul Worker-ului și afișează conținutul artefactelor ca text.

Testele AI verifică schema strictă, inputul limitat la întrebare/protocol, respingerea citărilor sau comenzilor fabricate de model, timeout și fallback explicit. Testele de securitate includ revocare/reacordare și expirarea tokenului în timpul apelului modelului, revocare în timpul programării alarmelor, bugetul AI fără apel suplimentar, planuri izolate pe investigator/alocare/întrebare, expirare și snapshot imuabil la paginare, inclusiv observații sosite ulterior cu timestamp mai vechi.

Verificarea criptografică a token-urilor Firebase a fost revizuită în cod: semnătură RS256, issuer, audience, subiect și timpi de validitate. Testele locale nu înlocuiesc autentificarea cu un cont real, custom claims reale sau verificarea regulilor Firestore publicate. Răspunsurile Workers AI din testele locale sunt simulate; disponibilitatea și interpretarea întrebărilor de către modelul real trebuie verificate în mediul Cloudflare configurat.

Regresia Android trebuie să includă păstrarea permisiunilor solicitate de funcțiile existente, salvarea locală a meselor/seturilor/activităților în lipsa backend-ului, oprirea corectă a serviciilor Sleep/GO și sincronizarea socială/Firestore existentă. Telemetria Research nu trebuie să introducă excepții în fluxurile de fitness.

Verificările automate de mai sus au trecut. Acceptanța pe telefon real, funcțiile obișnuite de fitness, latența și consumul bateriei în politicile OEM rămân de verificat prin scenariile enumerate; nu sunt rezultate deduse din compilare sau din fixture-urile locale.
