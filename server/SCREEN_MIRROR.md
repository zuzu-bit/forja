# FORJA 5.1 — „Ecranul pe site” (server/screen-mirror.mjs)

Telefonul își trimite ecranul pe viu în contul web, și primește comenzi — de pe site (secțiunea **Ecran**), din
terminalul `forja` (`scripts/forja-cli.mjs`) sau de la orice agent care vorbește protocolul. Site-ul nu atinge telefonul
direct: totul trece prin Durable Object-ul contului, pe WebSocket, și **nimic nu se păstrează**.

`/health` al forja-insights raportează `screen_mirror: 1` la versiunea 20.

## Părțile

- **Telefonul** (`com.forja.app.core.mirror.ScreenMirrorService`) deschide un WebSocket la `…/phone` cu secretul din Găsire
  (`x-forja-device-secret`) plus tokenul Firebase. Trimite `{t:'state', …}`, apoi cadre JPEG cât cineva privește, și
  răspunde la comenzi cu `{t:'result', id, ok, text, data?}`.
- **Privitorii** (site, terminal, agent) deschid `…/socket` cu tokenul în `Sec-WebSocket-Protocol: "forja, bearer.<token>"`
  (browserul nu poate pune antete pe WebSocket). Primesc `{t:'state'}`, cadrele binare și `{t:'result'}`; trimit
  `{t:'cmd', line}`.
- **Durable Object-ul contului** (`InsightsAccount`, prin `handleScreen`/`screenMessage`/`screenClose`) ține legăturile
  (API-ul de hibernare al Workers), împerechează comanda cu răspunsul telefonului (≤ 20 s), ține ultimul cadru în memorie
  cât e deschisă legătura și o cerere „vreau ecranul” pe disc (10 min) pentru bătaia Găsirii.

## Rute

Toate sub `/v2/screen/devices/<id>` (id-ul telefonului din Găsire). Tokenul Firebase se verifică în insights-worker.mjs
(Bearer pe HTTP, `bearer.<token>` în subprotocol pe WebSocket).

| Rută | Cine | Ce face |
|---|---|---|
| `GET /v2/screen/devices` | proprietar | telefoanele contului + starea ecranului (live/waiting/off), capabilitatea, `help`, `rules` |
| `GET …/<id>/socket` (Upgrade) | privitor | legătura de vizionare + comenzi |
| `GET …/<id>/phone` (Upgrade) | telefon | legătura care trimite cadre și execută comenzi |
| `GET …/<id>/frame` | proprietar | ultimul cadru (image/jpeg), 404 fără |
| `POST …/<id>/command` `{line}` | proprietar | o comandă; așteaptă răspunsul (≤ 20 s); 409 dacă telefonul nu e conectat (e chemat) |
| `DELETE …/<id>/session` | proprietar | închide legătura (telefonul primește `end`) |

## Bătaia Găsirii

Cererea de ecran e un rând pe disc în DO (`screen:request:<id>`, 10 min). Bătaia Găsirii (lost-phone.mjs) întoarce
`screen: {wanted, until, viewers}` cât cineva așteaptă, și bate mai des (10 s). Telefonul, când „Ecranul pe site” e pornit,
deschide legătura la următoarea bătaie. Capabilitatea telefonului (`{supported, enabled, android}`) vine tot în bătaie, ca
site-ul să spună onest de ce nu merge (Android < 11, oprit în Profil).

## Comenzile

Gramatica e în `parseCommand` (o singură dată, pentru site, terminal și agenți). Verbe: `tap`, `swipe`, `key`, `type`,
`open`, `say`, `read`, `apps`, `shot`, `scroll`, `info`. Acceptă și **sintaxa adb**: `input tap/swipe/text/keyevent`,
`am start`, `monkey -p`, `pm list packages`, `screencap -p`, `dumpsys`, `wm size`, `uiautomator dump` („adb” și „shell” se
pot omite). Coordonatele: fracții `0–1`, procente, sau pixeli; pixelii adb sunt pixeli reali.

## Confidențialitate și limite

- Fără privitori, telefonul nu capturează nimic; cu ecranul mut, nimic nu pleacă.
- Niciun cadru nu se salvează: nici pe server (doar ultimul, în memoria DO-ului, cât e legătura deschisă), nici pe telefon.
- Cât e privit, telefonul arată o notificare permanentă cu „Oprește”; serviciul se oprește singur după 2 minute fără privitori.
- Capturarea e limitată de Android la ~1 cadru/secundă (takeScreenshot); cadrele identice nu se retrimit.
- Cel mult 4 privitori pe telefon; o comandă expiră în 20 s dacă telefonul nu răspunde.

## Teste

`server/screen-mirror.test.mjs` (gramatica + adb, legăturile WebSocket cu socket-uri false, cadrele, comenzile pe socket și
pe HTTP, cererea din bătaie, închiderea), `scripts/forja-cli.test.mjs` (terminalul), plus secțiunea Ecran în
`scripts/ux-ui-test.cjs` și în harnașamentul de capturi (`scripts/site-shots`, cu un telefon fals pe `page.routeWebSocket`).
`app/src/test/.../ScreenMirrorProtocolTest.kt` acoperă traducerea comenzilor pe telefon (JVM, fără Android).
