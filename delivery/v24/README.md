# FORJA v24 — interfață simplificată

Configurarea, harta și panoul web afișau multe explicații și controale simultan. Actualizarea aduce acțiunile principale în față și mută detaliile în secțiuni care se deschid la cerere.

## Ce se schimbă

- **Permisiuni:** o pagină cu stări reale, acces Android activat ghidat și un buton Continuă. Accesul la senzori este separat de acordul pentru sincronizare. Locația aproximativă, accesul parțial la poze și refuzurile permanente au stări și căi de recuperare explicite.
- **Hartă Android:** panou compact de prieteni, trei destinații principale, restul instrumentelor într-un singur meniu. Partajarea activă și Oprește rămân vizibile. Contactele, cuplul și telefonul pierdut își păstrează toate acțiunile.
- **Audio și fișiere:** două secțiuni distincte; setările avansate și explicațiile sunt extensibile. Activarea, înregistrarea, oprirea, transferurile și aprobările folosesc logica versiunii livrate.
- **Site:** patru destinații principale; cele secundare sunt în Mai multe. Harta este centrală, galeria arată întâi fișierele, configurarea importurilor și programărilor se deschide contextual. Confidențialitatea este accesibilă și înainte de autentificare.
- **Feedback:** stări scurte, confirmări după rezultat, tranziții discrete, suport pentru mișcare redusă. Pozițiile vechi nu sunt prezentate ca live.

## Funcții păstrate

Antrenamente, nutriție, somn, Focus, curățenie locală/manuală/online, organizare și programări, cont și sincronizare, audio, prieteni, chat, invitații, întâlniri, locuri, explorare, cuplu, contacte, telefon pierdut, recomandări și campanii. Nu au fost adăugate permisiuni în manifest și nu au fost eliminate API-uri. Refactorizarea repară și stări vechi la acțiuni suprapuse cu actualizarea periodică.

## Surse de design

Acestea sunt repere selectate, nu un audit exhaustiv al tuturor aplicațiilor. Efecte neurochimice nu au fost măsurate sau promise.

| Reper | Aplicare |
| --- | --- |
| [Android — solicitarea permisiunilor](https://developer.android.com/training/permissions/requesting) | Stări reale, cereri de sistem și recuperare după refuz. |
| [Android — locație aproximativă și precisă](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime) | Solicitarea FINE și COARSE împreună, inclusiv la actualizarea preciziei. |
| [Snap Map — controlul locației](https://help.snapchat.com/hc/en-gb/articles/24547077410580-Snap-Map-Privacy-Safety-Reminder) | Accesul dispozitivului separat de alegerea partajării. |
| [Life360 — partajarea locației](https://www.life360.com/en-eu/location-sharing) | Harta centrată pe persoane și detalii contextuale. |
| [Files by Google — duplicate](https://support.google.com/files/answer/9764075) | Fișiere și rezultate înaintea instrucțiunilor. |
| [Duolingo — animații de progres](https://blog.duolingo.com/streak-milestone-design-animation/) | Confirmări scurte, după reușite reale. |

## Validare și livrare

- 149 teste Node pentru server, colectare și publicare.
- 22 verificări DOM cu date sintetice: navigare, consimțământ, formulare, hartă, confidențialitate, logout și răspunsuri întârziate.
- Worker-ul este împachetat și verificat local: versiunea 12, HTML și JavaScript identice surselor, acces privat respins cu 401 fără autentificare. Verificarea nu contactează Cloudflare.
- 39 teste JVM ale modulului Android și compilare Kotlin reușită.
- Testarea interfeței Android rămâne neconfirmată: emulatorul API 35, rulat fără accelerare KVM, nu a terminat inițializarea în intervalul disponibil. Prima încercare de instalare a întâlnit servicii Android încă neinițializate, înainte de pornirea FORJA. Acest rezultat nu este prezentat drept o validare pe dispozitiv.
- APK-ul se construiește peste versiunea v23 semnată, păstrând identitatea de instalare. Verificarea binară compară manifestul, clasele, apelurile și fișierele neatinse. Vezi `android-updates/visual-update/` pentru pași reproductibili și raportul buildului.
- Previzualizarea HTML folosește date demonstrative și fundal de hartă ilustrativ; nu accesează contul utilizatorului. Nu reprezintă un test al serverului live.
- Site-ul nu a fost publicat: verificarea automată de aprobare a blocat comanda Wrangler, inclusiv verificarea dry-run, deoarece trimiterea către Cloudflare nu avea acord explicit în această cerere. Nu s-a folosit o altă cale de publicare. Branch-ul UX nu declanșează publicarea.
- Verificarea vizuală web în browserul disponibil a fost blocată pentru protocolul local `file:`. Validarea DOM este raportată separat de testarea unui browser real.

Pentru testele web: `cd server && npm ci`, apoi din rădăcină `node scripts/ux-ui-test.cjs`. Pentru o previzualizare locală autonomă: `node scripts/ux-preview.cjs /cale/previzualizare.html`.
