# FORJA v25 — prezentare, sincronizare și somn

Versiune Android: `3.7-online.25`, cod `55`. Actualizarea se aplică APK-ului v24 livrat, cu aceeași identitate de instalare. Sursele Gradle vechi din rădăcină nu înlocuiesc baza binară verificată.

## Ce se schimbă

- Prezentarea vizuală explică pe scurt FORJA și somnul înaintea paginii de permisiuni. Detaliile rămân în meniul de confidențialitate și în secțiuni extensibile.
- După permisiuni, un singur buton activează sincronizarea disponibilă în propriul cont. Rezultatul diferențiază funcțiile pregătite, transferurile în așteptare și accesul încă necesar. Nu pornește imediat microfonul și nu activează implicit partajarea cu alte persoane.
- Somnul și audio-ul folosesc aceeași sesiune și același serviciu de microfon: pornire imediată sau interval de până la 12 ore, inclusiv ziua. Orele site-ului folosesc fusul browserului; o programare viitoare poate fi editată înainte de pornire.
- Fragmentele AAC complete se trimit automat. Raportul combină transcrierea și temele extrase pe server cu observații acustice YAMNet pe telefon. Sunt afișate numai duratele primite și procesate, cu erori și reîncercări explicite. Transcrierea și posibilul sforăit rămân estimări de verificat prin ascultare.
- Site-ul păstrează navigarea existentă, accesul public la confidențialitate și controalele hărții. Rapoartele deschise se actualizează fără a întrerupe redarea audio.

## Validare și limite

- Worker-ul împachetat local verifică versiunea 13, HTML/CSP, scripturile și răspunsul 401 pentru rutele private. Fără publicare sau apeluri AI live. Cele 7 teste ale scriptului de publicare trec.
- 32 verificări DOM trecute, cu date sintetice: navigare, permisiuni separate de partajare, formulare, programări de somn, rapoarte, redare, scoruri necunoscute, logout și răspunsuri întârziate.
- 47 teste Kotlin Gradle trecute, inclusiv cele 5 teste `SyncSetup`; separat, 68 verificări Java pentru somn și 26 pentru pregătirea audio. Compilarea Android și verificatorul APK acoperă ABI-ul, semnătura, manifestul și fișierele care trebuie păstrate; raportul ultimei reconstruiri este sursa pentru hash și rezultat.
- 155 teste server trecute: proprietarul datelor, consimțământul, duplicatele, reîncercările, ștergerea în timpul analizei și acoperirea acustică. Modelul AI este simulat în aceste teste; nu rezultă o măsurare a preciziei pe înregistrări reale.
- **Site-ul v25 a fost publicat cu acordul utilizatorului la 27 septembrie 2026.** [FORJA live](https://forja-insights.forja-22e7ea2d.workers.dev) · [verificarea publicării](https://github.com/zuzu-bit/forja/actions/runs/36307053396). Worker versiunea 13, pagina și scripturile corespund surselor; rutele private refuză cererile anonime. Testarea cu cont și telefon real rămâne separată.
- **Fluxul complet cu un cont real nu a fost executat.** Emulatorul Android 35 a pornit, dar instalarea APK-ului a depășit limita de 300 de secunde; lansarea și aspectul aplicației nu sunt validate pe dispozitiv. Nu există un crash al aplicației confirmat de această încercare. Nu există validare a unei nopți întregi, a consumului pe baterie sau a tuturor restricțiilor de fundal ale producătorilor.
- Previzualizarea este o simulare HTML interactivă, etichetată DEMO. Nu solicită permisiuni Android, nu accesează un cont real și nu este o captură a aplicației. Structura, fluxul și scripturile au fost verificate; acestea nu echivalează cu verificarea vizuală într-un browser real.

## Fișiere și reproducere

Căi de ieșire, raportate la rădăcina repository-ului:

- `../deliverables/FORJA_v25_Somn.apk` — APK final semnat, SHA-256 `b569e767f88de7f3a7825580313efe4f0f9594a9340f994524e146bf566a0ecd`.
- `../deliverables/FORJA_v25_Somn.verification.json` — hash, semnătură și verificări ale APK-ului aferent.
- `../deliverables/FORJA_v25_Previzualizare.html` — un singur fișier cu prezentarea aplicației și site-ul demonstrativ.

Instrucțiunile APK sunt în [sleep-update/README.md](../../android-updates/sleep-update/README.md), iar protocolul și retenția sunt în [SLEEP_AUDIO.md](../../server/SLEEP_AUDIO.md). Nu se includ APK-uri sau unelte de compilare în Git.

```sh
npm ci --prefix server
npm run test:server --prefix server
node scripts/ux-ui-test.cjs
node scripts/ux-review.cjs
```

`ux-review.cjs` include imaginile și site-ul construit din sursele curente, cu date fictive și pagina „Somn și audio” deschisă implicit. `ux-preview.cjs` rămâne disponibil pentru previzualizarea separată a site-ului.

## Ilustrații de prezentare

Fișiere PNG de 1536 × 1024 în `android-updates/visual-update/assets/forja/`, create cu ImageGen prin operația **generate**, fără imagini de referință și fără editarea unei imagini existente. Brief comun: verde pădure `#101A18`, lime pal `#C9E89C`, crem și forme 3D tactile inspirate de hârtie și lut; fără text de interfață sau logo. Descrierile de mai jos rezumă briefurile, fără a pretinde reproducerea exactă a prompturilor originale.

| Fișier | Brief vizual |
| --- | --- |
| `onboarding-welcome.png` | Pantof sport crem, frunză, soare și lună printre forme moi de dealuri verzi; mișcare și echilibru. |
| `onboarding-sleep.png` | Semilună verde deschis deasupra unei perne crem, cu valuri și relief verde închis; odihnă. |
| `onboarding-sync.png` | Telefon și laptop crem, unite de o panglică verde; continuitate între ecrane. |
