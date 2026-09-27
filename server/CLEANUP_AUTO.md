# Curățenie automată — actualizarea v17

## Activare

1. Publică acest server în Worker-ul existent `forja-insights`. Pachetul Windows
   include `PUBLICA_FORJA.cmd`, care verifică actualizarea înainte de publicare.
2. Instalează APK-ul 3.7-online.17 peste versiunea existentă.
3. În aplicație: Curățenia de astăzi → **Analizează-mi automat**. Alege fotografiile
   autorizate și/sau dosarul cu fișiere. Activează pe acest telefon.
4. Pe site, în același cont FORJA: **Poze și documente → Curățenie automată**.
   Alege 25 sau 50 din fiecare sursă, zilele săptămânii, una până la patru ore,
   fusul orar (implicit Europe/Bucharest) și dacă se folosesc doar conexiuni
   fără cost de date. Bifează analiza activă și salvează programul.
5. Telefonul preia programul automat. „Verifică programul” cere actualizarea
   imediat. După o rundă, „Vezi propunerile” deschide rezultatele în aplicație;
   aplicarea mutărilor și curățarea duplicatelor necesită confirmarea utilizatorului.

## Comportament

- Analiza conținutului folosește modelele locale existente. Fiecare rundă fixează
  inventarul înainte de analiză și încarcă aceleași originale în paralel.
- O întrerupere păstrează inventarul, raportul local și identificatorii încărcărilor.
  Reluarea nu selectează alte fotografii și nu dublează copiile confirmate.
- Raportul online și propunerile expiră după 24 h de la crearea rundei. Fiecare
  copie are separat 24 h de la prima primire, fără prelungire la reîncercare.
- Fotografiile se selectează după data adăugării în galerie. Fișierele se aleg
  după ultima modificare, în dosarul autorizat și subdosarele accesibile.
- Android poate întârzia execuția în fundal. Programul este verificat periodic
  (minimum 15 minute), cu cereri suplimentare pentru următoarea oră cunoscută.
  Rețeaua, bateria și optimizările telefonului pot amâna lucrul. Pornirile ratate
  cu peste două ore sunt omise. O oră inexistentă la trecerea la ora de vară este
  omisă; o oră repetată toamna se execută o singură dată.
- Schimbarea programului invalidează rundele vechi. Dezactivarea din site sau
  oprirea primirii datelor împiedică noile încărcări programate. Dezactivarea din
  telefon oprește și lucrările locale; datele primite anterior expiră normal.
- Schimbarea surselor pe telefon creează o activare nouă. Programul trebuie
  configurat din nou în site. Site-ul nu poate autoriza alt dosar în telefon.
- Limitele existente rămân 25 MiB/fișier, 500 copii active, 512 MiB/cont și 1500
  încărcări/zi. Fișierele prea mari sau inaccesibile sunt afișate ca netrimise.
  În pagina cu rezultate, un transfer parțial nu apare drept încărcare completă.

## Implementare / verificare

Health: `{service:"forja-insights",version:6,files_sync:1,cleanup_schedule:1}`.
Noile rute `/v2/cleanup/devices/...` sunt autentificate cu Firebase și legate de
DO-ul proprietarului. Activarea, revizia programului și runda sunt verificate din
nou la upload prin `X-Cleanup-Run`. Rezultatele sunt text inactiv; nu sunt tratate
ca HTML. Nu sunt necesare bucket-uri, credențiale în APK sau migrații DO noi.

Teste: `npm test`. Verificarea pachetului: `wrangler deploy --dry-run --config
wrangler.insights.toml`. Întregul server păstrează contractele v15/v16 pentru
încărcări manuale, audio, recomandări și reclame.

Mediul de construire nu are un telefon/emulator disponibil și nici o autentificare
Cloudflare utilizabilă pentru publicare. Testele locale și verificarea pachetului
nu certifică execuția pe dispozitiv sau publicarea live. După instalare, verifică
o rundă pe un set de fișiere proprii, confirmarea pe site și propunerile din telefon.
