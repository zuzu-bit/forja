# FORJA 4.4 — Găsirea telefonului (protocolul 2)

Principiul: **telefonul e ținta, nu căutătorul**. Pe telefon nu există buton „Activează găsirea”.
Când contractul v3 e semnat, telefonul se înrolează singur, își lasă pe site ultima poziție și bateria
(doar pentru tine), iar căutarea și soneria pornesc din site, secțiunea **GĂSIRE · Telefonul tău** (`/insights#gasire`).
Site-ul `/health` anunță `lost_phone: 2`.

## Ce face fiecare parte

1. **Înrolarea** (automată, la semnarea contractului v3 sau la prima deschidere după actualizare):
   `POST devices/:id/grant {name, secret, basis:"contract", contract_version:3}`. Id-ul (UUID) și secretul
   (256 biți, 64 hex) se nasc pe telefon; serverul păstrează doar SHA-256 al secretului. Telefoanele 4.3
   înrolate cu `{name, secret, consent:true}` își păstrează id-ul și secretul: nu e nevoie de o nouă activare.
2. **Bătaia** (la fiecare tură a serviciului contractului, ~60 s; în Doze Android o poate întinde la 9–15 min):
   `POST devices/:id/beat {secret, status, fix?:{lat,lon,accuracy,at}, battery?, charging?}` →
   `{command|null, next_s}`. Actualizează „văzut la”, starea, bateria, prelungește înrolarea cu 30 de zile
   și mută `last` **doar dacă** fix-ul e mai nou decât cel păstrat (un fix vechi trimis târziu e ignorat).
3. **Căutarea din site** (`POST devices/:id/command`):
   - `{id, kind:"locate", minutes:10}` — „Urmărește 10 min”; `{id, minutes:5|15|30}` (forma veche) = localizare;
   - `{id, kind:"ring", seconds:30|60|120}` — „Sună” (implicit 60 s). Doar un telefon care a trimis măcar o
     bătaie (protocolul 2) poate suna; un telefon 4.3 primește 409 „Actualizează FORJA pe telefon…”.
     „Sună” preia imediat locul unei urmăriri active; o urmărire nu întrerupe soneria.
   - Cererea așteaptă telefonul **30 de minute** (`start_before`). Minutele sau secundele încep când
     telefonul confirmă (`status` = `locating`/`ringing`), nu când ai apăsat, așa că un telefon adormit nu pierde din ele.
4. **Prelungirea**: `POST devices/:id/extend {command, minutes:10}` — doar pentru urmărire, cel mult 60 de minute în total.
5. **Oprirea**: `DELETE devices/:id/command?id=` (site) sau `POST devices/:id/stop {secret, command}` (telefon,
   „Am găsit telefonul” / „Oprește”) sau `status: found|stopped`. Oprirea închide doar cererea:
   **ultima poziție și poziția căutării rămân** pe hartă.
6. **Redenumirea** din site: `PATCH devices/:id {name}` (1–40 de caractere). Un nume dat pe site nu mai este
   suprascris de numele implicit trimis de telefon la o re-înrolare.
7. **Scoaterea**: `DELETE devices/:id/grant` (site, „Scoate telefonul”) sau `POST devices/:id/revoke {secret}`
   (telefon: contract revocat, ieșire din cont). Înregistrarea se șterge cu totul.

Rute păstrate pentru APK-urile 4.3: `poll {secret,status}` → `{command}`, `status`, `position`, `stop`, `revoke`.

## Ce vede site-ul

`GET /v2/recovery/devices` →
`{devices:[{id, name, basis, seen_at, online, status, battery|null, charging|null, last|null, position|null, command|null}],
retention_hours:24, last_retention_days:7}`.

- `online` = o bătaie în ultimele 150 s. Stările telefonului: `ready`, `locating`, `ringing`, `found`, `location_off`,
  `permission_missing`, `notification_missing`, `offline`, `stopped`; stările serverului: `waiting_for_phone`,
  `requested`, `expired`.
- `command` = `{id, kind, created_at, start_before, until, phase:"queued"|"active", minutes|null, seconds|null}`.
- `last` = un singur punct suprascris (nu un traseu), păstrat **7 zile** de la măsurare.
  `position` = ultima poziție primită într-o căutare, păstrată **24 h**, cu `fresh` sub 2 minute.
- Nimic din listă nu conține secretul sau hash-ul lui.

## Limite și reguli

- 5 telefoane pe cont; 100 de cereri în 24 h; 30 s între cereri noi (excepție: „Sună” peste o urmărire);
  un id de cerere închis nu mai poate fi redeschis 24 h; o oprire veche nu oprește o cerere mai nouă.
- Pozițiile unei căutări cer o cerere confirmată și activă, coordonate și precizie valide, o măsurare
  făcută după cerere, cel mult cu 90 s în urmă.
- Bătaia e îngăduitoare, fiindcă de ea depinde găsirea: câmpurile necunoscute sunt ignorate, iar un fix sau o baterie
  inutilizabile (malformate, mai vechi de 7 zile, cu ceasul telefonului mult înainte) se lasă deoparte fără să refuze bătaia;
  un ceas puțin înainte e adus la ora serverului. Doar secretul și starea sunt obligatorii.
- Înrolările fără nicio bătaie 30 de zile expiră. Alarma Durable Object-ului șterge fizic `position` după 24 h,
  `last` după 7 zile și cererile expirate.
- Transparență față de cine ține telefonul: orice căutare și orice sonerie arată pe telefon o notificare
  care nu poate fi ascunsă, cu „Oprește”.

## Implementare și acces

Workerul verifică tokenul Firebase (RS256, emitent, audiență, expirare); pentru `/v2/recovery/*` UID-ul intern este
suprascris din token și cererea ajunge la Durable Object-ul contului (`account:<UID>`). Nicio rută de găsire nu
atinge graful de prieteni. Codul: `server/lost-phone.mjs`; testele: `server/recovery.test.mjs`
(acces între conturi, secret, bătaie fără fix, `last` monoton, soneria de la cerere la „found”, oprirea care păstrează
`last`, fereastra de 30 de minute, înrolarea pe contract, plafonul de 60 de minute, redenumirea, clientul 4.3).

Fără FCM și fără „sună-i telefonul” din familie în 4.4 (faza 2): cererea ajunge la telefon la următoarea bătaie.
Închis, descărcat sau fără internet, telefonul nu poate răspunde; rămâne ultima poziție.
