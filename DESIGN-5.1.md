# FORJA 5.1 — „Ecranul pe site” și terminalul `forja`

Telefonul tău, pe viu, pe ecran mare — și comandat din orice command prompt sau de alt agent (alt Claude Code), cu
limbajul pe care l-ar folosi un programator la `adb`. Site-ul rămâne complet separat de telefon: se leagă de el **prin
aplicație**, nu umblă direct în telefon. Nimic din ce era nu se strică — tot ce urmează e nou și opt-in.

Sursa de adevăr pentru 5.1. Construiește peste 5.0.1 (branch `claude/quirky-hamilton-7nbpi8`, apoi această ramură). Unde
acest document și codul existent se contrazic, acest document câștigă.

---

## 0. Ce a cerut utilizatorul

| # | Cererea (cuvintele lui) | Unde |
|---|---|---|
| 1 | „controlul telefonului ca Genymotion — imaginea întreagă a telefonului pe site, și o grămadă de lucruri doar din CLI” | §1, §2 |
| 2 | „site-ul e total diferit, ceva care se conectează la telefon prin aplicație” | §1 (arhitectura: site/CLI ↔ server ↔ aplicație) |
| 3 | „să poți da comenzi și din command prompt normal; să se poată conecta alt Claude Code la el și să dea comenzi” | §2 (terminalul `forja`), §3 (protocolul, pentru agenți) |
| 4 | „de ce nu poți folosi comenzile pe care le-ar folosi un programator în linie de comandă Android” | §3.3 (sintaxa `adb`) |
| — | (mai vechi, de la Lana) vederea „ca în The Sims”, persoana a treia | §5 „Ce urmează” — plan, nu livrat acum |

Reguli de produs păstrate: română cu diacritice cu virgulă (ș ț), fără „!”, fără emoji în UI, butoane ≤ 18 caractere,
dietă de text (explicațiile la „i”), doar niveluri gratuite (Cloudflare Workers/Durable Objects/R2, Firestore Spark),
fără SDK-uri plătite, fără server AI local.

---

## 1. Arhitectura (decizii)

1. **Trei părți, o singură cale.** Site-ul (forja-insights) și terminalul (`scripts/forja-cli.mjs`) **nu ating niciodată
   telefonul direct**. Vorbesc cu serverul; serverul vorbește cu aplicația. Telefonul rămâne ținta, ca la Găsire.

   ```
   site (secțiunea Ecran)   ─┐                            ┌─ aplicația FORJA (ScreenMirrorService)
   terminal forja / agent   ─┼─ WebSocket ─ forja-insights ─ WebSocket ─┤  serviciul FORJA de accesibilitate
   (orice Claude Code)      ─┘   (Durable Object al contului)           └─ takeScreenshot + gesturi
   ```

2. **Legătura trece prin Durable Object-ul contului** (`server/screen-mirror.mjs`, în `InsightsAccount`): un capăt pentru
   telefon (`/phone`), până la patru capete pentru privitori (`/socket`). Telefonul trimite cadre JPEG; privitorii le
   primesc pe viu și trimit comenzi; serverul le împerechează și așteaptă răspunsul telefonului. **Nimic nu se păstrează**:
   ultimul cadru stă doar în memoria DO-ului cât e deschisă legătura; pe disc rămâne doar cererea „vreau ecranul” (10 min),
   ca bătaia Găsirii să cheme telefonul. La închidere, totul dispare.

3. **Telefonul e același din Găsire.** Cât contractul e semnat, telefonul se înrolează singur (lost-phone.mjs); „Ecranul pe
   site” refolosește aceeași identitate și același secret. Serverul recunoaște telefonul după secretul din Găsire
   (`x-forja-device-secret`), deci nu există o a doua activare.

4. **Capturarea e prin serviciul de accesibilitate deja existent** (`AccessibilityService.takeScreenshot`, Android 11+) —
   același serviciu pe care Focus și „Hei FORJA” îl folosesc. Fără root, fără MediaProjection (care ar cere un dialog de
   fiecare dată și nu merge din fundal). Android limitează capturile la ~1/secundă: mirror-ul trimite ~un cadru pe secundă
   și sare cadrele identice.

5. **Opt-in, de două ori.** Nu se trimite nimic decât dacă (a) contractul e semnat, (b) „Ecranul pe site” e pornit din
   Profil → Telefonul meu (implicit oprit) și (c) cineva chiar privește. Cât e privit, o notificare permanentă „FORJA ·
   Ecranul tău e pe site” cu „Oprește” e vizibilă; fără privitori, serviciul se oprește singur după 2 minute.

6. **Autentificarea pe WebSocket.** Browserul nu poate pune antete pe un WebSocket, deci tokenul Firebase merge în
   `Sec-WebSocket-Protocol: "forja, bearer.<token>"`; workerul îl verifică exact ca pe `Authorization` (RS256, emitent,
   audiență). Terminalul folosește aceeași cale, ca pagina.

7. **O singură gramatică de comenzi**, în `server/screen-mirror.mjs` (`parseCommand`), folosită de site, de terminal și de
   orice agent. Validarea e pe server: clientul trimite rândul, serverul răspunde cu folosirea corectă când e greșit.

8. **`/health` → versiunea 20**, cu `screen_mirror: 1` adăugat; `verify-live.mjs` și testele care fixează versiunea se
   mută pe 20. Nicio altă funcție nu se schimbă.

---

## 2. Terminalul `forja` (scripts/forja-cli.mjs)

Node 22, zero dependențe (doar `fetch` și `WebSocket` din Node). Vorbește cu site-ul ca pagina: logare Firebase prin REST,
tokenul de reîmprospătare păstrat în `~/.config/forja/cli.json` (parola niciodată). Logica testabilă e în
`scripts/forja-cli-lib.mjs` (`scripts/forja-cli.test.mjs`, rulat de `npm test`).

```
forja login [email]            intră cu contul FORJA (parola se cere, nu se păstrează)
forja devices                  telefoanele contului (în gardă / tăcut; ecranul: live / waiting / off)
forja use <nume|id>            telefonul implicit
forja shot [fișier.jpg]        un cadru al ecranului
forja watch [--dir D]          cadre pe viu, salvate ca frame-0001.jpg …
forja sh                       consolă interactivă
forja tap <x> <y> · swipe … · key … · type … · open … · say … · read · apps · scroll … · info
forja admin <comandă…>         terminalul serverului forja-api (status, log 50, media ls, …)
```

**Pentru un alt agent (alt Claude Code).** Terminalul e tot ce-i trebuie unui agent ca să conducă telefonul: login o
dată, apoi `forja read` / `forja tap …` / `forja say …` în buclă, sau `forja sh` pentru o sesiune. Răspunsurile vin pe
stdout (cu `--json` pentru parsare). Un agent poate la fel de bine să vorbească direct protocolul din §3.

---

## 3. Protocolul (pentru site, terminal și agenți)

Toate sub `/v2/screen/devices/<id-ul telefonului din Găsire>`. Autentificare: tokenul Firebase (Bearer pe HTTP; în
`Sec-WebSocket-Protocol` pe WebSocket). Telefonul se autentifică în plus cu secretul Găsirii.

### 3.1 HTTP
```
GET    /v2/screen/devices               → { devices:[{ id, name, online, seen_at, status,
                                              capability:{supported,enabled,android}|null,
                                              screen:{phone:"live"|"waiting"|"off", viewers, width, height, fg, battery, frame_at, requested_until} }], rules, help }
GET    /v2/screen/devices/<id>/frame    → image/jpeg (ultimul cadru) · 404 fără
POST   /v2/screen/devices/<id>/command  { line:"<comandă>" } → { ok, text, data?, line } · 409 dacă telefonul nu e conectat (e chemat)
DELETE /v2/screen/devices/<id>/session  → închide legătura (telefonul primește „end”)
```

### 3.2 WebSocket
- Privitor: `GET …/<id>/socket`, cu `Sec-WebSocket-Protocol: forja, bearer.<token>`.
- Telefon (aplicația): `GET …/<id>/phone`, cu antetul `x-forja-device-secret`.

Mesaje (JSON text, în afară de cadrele binare JPEG):
```
telefon → server:  {t:'state', width, height, fg, battery}   {t:'result', id, ok, text?, data?}   <JPEG binar>
server → telefon:  {t:'watch', viewers}   {t:'cmd', id, kind, …}   {t:'who'}   {t:'end', reason}
privitor → server: {t:'cmd', id?, line}   {t:'ping'}   {t:'state'}
server → privitor: {t:'state', phone, width, height, fg, viewers, requested_until, device}   {t:'result', id, line, ok, text, data}   <JPEG binar>   {t:'error', message}
```

### 3.3 Limbajul comenzilor — și sintaxa `adb`

Un rând = un verb + argumente, română sau engleză. Coordonatele sunt fracții `0–1` (`0.5 0.3`), procente (`50% 30%`) sau
pixeli (`540 1200`).

| Comanda noastră | Echivalentul `adb` (merge la fel; „adb” și „shell” se pot omite) |
|---|---|
| `tap <x> <y>` | `adb shell input tap 540 1200` |
| `swipe <x1> <y1> <x2> <y2> [ms]` | `adb shell input swipe 540 1800 540 600 300` |
| `key back\|home\|recents\|notifications\|settings\|lock` | `adb shell input keyevent KEYCODE_BACK` (sau `4`, `3`, `187`, `83`, `176`, `26`) |
| `type <text>` | `adb shell input text "salut"` (`%s` = spațiu) |
| `open <aplicație>` | `adb shell am start -n pachet/.Activitate` · `monkey -p pachet 1` |
| `apps` | `adb shell pm list packages` |
| `shot` | `adb exec-out screencap -p` (FORJA întoarce JPEG, nu PNG) |
| `read` | `adb shell uiautomator dump` (FORJA citește textul direct) |
| `info` | `adb shell dumpsys` / `wm size` / `getprop` |
| `scroll up\|down` | — |
| `say <comandă>` | — (o comandă „Hei FORJA”, rulată fără voce; răspunsul vine ca text) |

Tastele `adb` care există fără root/`adb` adevărat sunt cele de mai sus; celelalte keyevent-uri sunt refuzate cu un mesaj
care spune ce e disponibil. Pe telefon, pixelii adb sunt pixelii reali ai ecranului (ca la adb); fracțiile se așază pe
ecranul real (`core/mirror/ScreenMirrorProtocol.kt`, testat în `ScreenMirrorProtocolTest`).

---

## 4. Aplicația (ce s-a adăugat)

- `core/mirror/ScreenMirrorProtocol.kt` — pur, testat pe JVM: comandă JSON → acțiune, cu coordonatele pe ecranul real;
  mărimea cadrului; numele aplicației din față.
- `core/mirror/ScreenMirror.kt` — comutatorul opt-in (SharedPreferences `forja_screen_mirror_v1`), condițiile (Android 11+,
  contract semnat, accesibilitate pornită), capabilitatea trimisă în bătaie, `onBeat` care pornește/oprește serviciul.
- `core/mirror/ScreenMirrorService.kt` — serviciu foreground (specialUse): WebSocket (OkHttp) către `/phone`, cadre din
  `takeScreenshot`, comenzile prin `ScreenAgent` (atingeri, gesturi, taste, text, derulare), `open` prin PackageManager,
  `say` prin `CommandExecutor` (ca „Hei FORJA”, fără voce), notificare permanentă cu „Oprește”, oprire singură la 2 min.
- `core/voice/ScreenAgent.kt` — adăugate: `screenSize`, `swipe`, `globalKey` (back/home/recents/notificări/setări rapide/blocare).
- `core/recovery/Finder.kt` — bătaia trimite capabilitatea ecranului și, când răspunsul cere ecranul (`screen.wanted`),
  pornește serviciul (bate mai des cât cineva așteaptă).
- `feature/profile/ProfileScreen.kt` — rândul „Ecranul pe site” (comutator), lângă „Telefonul meu”.
- `AndroidManifest.xml`, `ForjaApp.kt`, `strings.xml` — serviciul, canalul `mirror`. `versionCode` 76, `versionName` 5.1.

Capturarea NU folosește root sau MediaProjection: doar serviciul de accesibilitate deja pornit. Fără privitori nu se
capturează nimic; cu ecranul mut nu pleacă niciun cadru.

---

## 5. Ce urmează

1. **Vederea „persoana a treia / The Sims”** (cerută mai demult de Lana, încă nelivrată): o scenă izometrică pe site, cu
   avatarul tău mișcându-se pe harta Teren după turele și locurile tale. E un pachet separat, mare (motor de scenă, sprite-uri,
   starea zilei), de făcut peste datele existente din `/insights/api/cerc` și explorare. Nu intră în 5.1.
2. **Proba pe telefon real** a „Ecranului pe site”: captura cu ecranul blocat, ritmul în Doze, latența comenzilor.
3. **H.264/WebCodecs** în loc de JPEG, dacă ritmul de ~1 cadru/s nu ajunge (necesită MediaProjection — alt compromis de permisiuni).
