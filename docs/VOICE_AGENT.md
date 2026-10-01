# FORJA · Control vocal

Funcție pentru utilizare obișnuită, cu pornire voluntară, feedback vocal, progres
vizibil și anulare. Prima comandă implementată caută un videoclip în aplicația
YouTube și deschide un rezultat care corespunde titlului solicitat. Nu există un
comutator de laborator și căutarea nu folosește deep-link către rezultatul final.

## Activare pe telefon

1. Instalează FORJA și YouTube. Configurează amprenta în setările Android dacă vrei
   să deblochezi telefonul astfel.
2. Deschide **Control vocal** din ecranul principal sau Profil. Poți adăuga și
   scurtătura **Control vocal** apăsând lung pictograma FORJA din launcher.
3. Citește explicația funcției și activează manual **FORJA · Control vocal** în
   **Setări Android → Accesibilitate**. Serviciul este separat de FORJA Detox.
4. Dacă vrei ascultare după deblocare, activează **Ascultă după deblocarea
   telefonului**. Opțiunea este inițial oprită.
5. Apasă **Pornește și ascultă**, acordă permisiunea de microfon și, pentru control
   prin notificare, permisiunea de notificări. Agentul trebuie pornit din acest
   ecran vizibil, înainte să blochezi telefonul.
6. Spune un titlu suficient de precis, de exemplu:
   **„FORJA, deschide YouTube și caută documentarul Planeta Pământ.”**

Agentul pornește aplicația YouTube, identifică butonul de căutare, completează
câmpul de căutare, trimite căutarea, identifică un rezultat și îl deschide.
Verificarea cere atât titlul ales, cât și dovezi despre ecranul de redare.

Spune **„FORJA, oprește”** sau **„FORJA, anulează”** în timpul ascultării unei
comenzi ori al executării. Există și **Anulează comanda** în FORJA, un buton
afișat peste YouTube și o acțiune în notificare. **Oprește agentul** încheie
sesiunea inclusiv ascultarea după deblocare. Anularea oprește acțiunile următoare;
nu inversează o apăsare deja făcută.

## Amprenta și ascultarea după deblocare

Android deblochează telefonul folosind metoda aleasă de utilizator. FORJA nu
colectează date biometrice, nu simulează amprenta și nu încearcă să ocolească
ecranul de blocare. Evenimentul Android de deblocare nu precizează dacă ai folosit
amprenta, PIN-ul sau altă metodă; agentul verifică faptul că telefonul este deblocat.

Ascultarea automată funcționează doar cât timp serviciul pornit voluntar rămâne
activ. Agentul nu este pornit automat la boot sau dintr-un eveniment de deblocare,
deoarece Android restricționează pornirea microfonului din fundal. După repornire,
oprire forțată sau închiderea agentului, pornește-l din nou din FORJA. Producătorul
telefonului poate impune restricții suplimentare serviciilor din fundal.

Recunoașterea folosește serviciul Android instalat, în limba română, și poate
necesita internet. Aceasta este o fereastră de ascultare după pornire/deblocare,
nu un detector permanent al cuvântului „FORJA”. Dacă nu ai spus o comandă,
folosește **Ascultă o comandă** din FORJA sau notificare ori deblochează din nou.

## Arhitectură extensibilă

- `VoiceAgentService` gestionează serviciul foreground, recunoașterea, mesajele
  vocale, evenimentele de blocare/deblocare și anularea sesiunii.
- `VoiceIntentResolver` acceptă forme explicite ale comenzii de căutare YouTube
  și comenzile de anulare. Nu transformă vorbirea liberă în acțiuni arbitrare.
- `VoiceUiConnection` conectează sesiunea vocală cu serviciul autorizat
  `ForjaVoiceAccessibilityService`.
- `UiAutomationEngine` coordonează pașii, timpul de așteptare, observațiile proaspete,
  limitele de execuție și verificarea rezultatului, independent de Android.
- `UiDriver` expune lansarea, observația UI și acțiunile. `AndroidUiDriver`
  construiește observații limitate și verifică din nou fereastra, aplicația și
  elementul imediat înainte de fiecare acțiune.
- `AppAdapter` definește selectorii semantici și furnizează `UiPlan` pentru intenție.
  `YouTubeAdapter` folosește textul, descrierile, rolurile și ID-urile elementelor,
  fără coordonate fixe sau gesturi calculate.
- `SearchAndOpenPlan` descrie fluxul inițial. Alte comenzi pot furniza alte planuri;
  `UiExecutionContext` păstrează observațiile, acțiunile și verificările sub
  controlul motorului comun.

Pentru altă aplicație, adaugă un adaptor și înregistrează-l în motor, apoi adaugă
intenția explicită permisă în resolver. Pentru alt tip de comandă, definește
intenția și un `UiPlan` corespunzător, reutilizând driverul, anularea, verificările și
telemetria. Verifică și acordurile de permisiuni/query necesare aplicației noi.

## Limite ale acțiunilor

La început este permisă numai căutarea și deschiderea conținutului în pachetul
oficial YouTube. Telefonul trebuie să fie deblocat. Schimbarea aplicației în timpul
execuției, apariția unui dialog ori a unui câmp de autentificare/plată opresc
comanda. Nu se confirmă permisiuni Android, autentificări, cumpărături sau plăți.

Un rezultat ambiguu nu este ales automat. Dacă YouTube nu expune semantic
elementele necesare sau nu apare un rezultat potrivit în timpul disponibil,
agentul raportează eșecul. Se caută în rezultatele expuse de arborele curent;
derularea automată a listelor nu este încă implementată. Interfața YouTube diferă între versiuni, limbi și
experimente ale furnizorului; selectorii trebuie validați pe telefonul folosit.
Pe Android 26–29, trimiterea căutării cere un buton semantic oferit de aplicație;
acțiunea Accessibility `ACTION_IME_ENTER` este disponibilă începând cu Android 30.
Deschiderea ecranului de redare nu garantează redarea completă a videoclipului:
rețeaua, reclamele și restricțiile de conținut pot influența playerul.

## Telemetrie locală

**Exportă telemetria** salvează și partajează un fișier JSONL. Fiecare rând conține
`session_id`, `stage`, `timestamp` UTC, `timestamp_ms`, `elapsed_realtime_ms` și
`detail`; eșecurile includ `reason` și `failed_stage`.

Fluxul este:

```text
command_received → speech_recognized → intent_resolved → app_opened
  → target_found → action_executed [repetat pentru pașii UI]
  → result_verified / failed
```

Jurnalul nu include audio, capturi, arbori UI sau textul recunoscut. Retenția este
limitată la 14 zile, aproximativ 512 KiB și 2.000 evenimente. **Șterge jurnalul
local** elimină și exportul. Arborele UI este citit doar pe durata unei comenzi;
accesul Android la serviciu se poate revoca oricând din Accesibilitate.

## Verificare

```sh
./gradlew testDebugUnitTest assembleDebug
```

Testele verifică interpretarea comenzilor, selectorii semantici, refuzul situațiilor
sensibile, execuția secvențială, verificarea rezultatului, anularea și timpii limită.
Aceste teste folosesc observații UI sintetice și nu înlocuiesc proba pe telefon.

Pentru proba reală, folosește un telefon cu YouTube instalat, microfon permis și
serviciul Control vocal activat manual. Pornește agentul, blochează telefonul,
deblochează-l cu amprenta și spune comanda. Verifică navigarea vizibilă, mesajul de
rezultat și jurnalul exportat. Repetă cu anulare în timpul căutării, rezultat
ambiguu, YouTube absent, Accessibility revocat și blocarea ecranului în timpul
comenzii. Nu marca demonstrația end-to-end ca validată doar pe baza testelor JVM.
