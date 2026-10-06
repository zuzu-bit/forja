// FORJA 5.1 — „Ecranul telefonului pe site” (SCREEN_MIRROR.md): telefonul trimite cadre JPEG ale ecranului său, site-ul
// sau terminalul (scripts/forja-cli.mjs) le vede pe viu și trimite comenzi (apasă, trage, scrie, tastă, deschide, spune,
// citește, aplicații, poză). Totul trece prin Durable Object-ul contului, pe WebSocket, și NIMIC nu se păstrează: ultimul
// cadru stă doar în memoria DO-ului cât e deschisă legătura; pe disc rămâne numai cererea „vreau ecranul” (10 min), ca
// bătaia găsirii (lost-phone.mjs) să-i spună telefonului să se conecteze.
//
// Cine vorbește cu cine (toate sub /v2/screen/devices/<id-ul telefonului din Găsire>):
//   · telefonul  — GET …/phone  (Upgrade: websocket, Authorization Bearer + x-forja-device-secret = secretul din Găsire)
//   · privitorul — GET …/socket (Upgrade: websocket; tokenul Firebase în Sec-WebSocket-Protocol: „forja, bearer.<token>”,
//                  fiindcă browserul nu pune antete pe WebSocket; Workerul îl verifică la fel ca pe Authorization)
//   · GET  /v2/screen/devices           — telefoanele contului, cu starea ecranului (live / waiting / off)
//   · GET  …/frame                      — ultimul cadru (image/jpeg) din memoria DO-ului, 404 fără
//   · POST …/command {line}             — o comandă în limbajul de mai jos, așteaptă răspunsul telefonului (≤ 20 s)
//   · DELETE …/session                  — închide: telefonul primește „end”, privitorii sunt deconectați
// Mesajele (JSON, în afară de cadrele binare):
//   telefon → DO:   {t:'state', width, height, fg, on, battery?}   {t:'result', id, ok, text?, data?}   <cadru JPEG>
//   DO → telefon:   {t:'watch', viewers}   {t:'cmd', id, kind, …}   {t:'who'}   {t:'end', reason}
//   privitor → DO:  {t:'cmd', id?, line}
//   DO → privitor:  {t:'state', phone:'live'|'waiting'|'off', width, height, fg, viewers, requested_until, device}
//                   {t:'result', id, ok, text, data, line}   {t:'error', message}   <cadru JPEG>
import { bad } from './phone-schema.mjs';

const MIN = 60000;
export const SCREEN_RULES = Object.freeze({
  request_ms: 10 * MIN,        // cât așteaptă o cerere de ecran telefonul (bătaia în Doze poate întârzia ~9 min)
  session_max_ms: 60 * MIN,    // o legătură a telefonului ține cel mult o oră; apoi o cerere nouă
  idle_ms: 2 * MIN,            // telefonul se deconectează singur după 2 min fără privitori
  viewers_max: 4,
  frame_max_bytes: 400 * 1024, // un cadru JPEG mai mare e aruncat (telefonul trimite ~360 px lățime, 20–60 KB)
  command_timeout_ms: 20000,
  line_max: 2000, text_max: 1000, say_max: 300, open_max: 200, result_text_max: 4000,
  wanted_beat_s: 10,           // next_s cât o cerere așteaptă telefonul (altfel 60 s)
  swipe_ms: [50, 5000, 300],   // min, max, implicit
});
// Aceleași ferestre ca RECOVERY_RULES din lost-phone.mjs (nu le importăm: ar fi un import circular).
const ONLINE_MS = 12 * MIN, LEGACY_ONLINE_MS = 150000;
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const SECRET = /^[a-f0-9]{64}$/;
const REQUEST = 'screen:request:';
const reply = (data, status = 200) => Response.json(data, { status, headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
const hash = async value => [...new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)))].map(x => x.toString(16).padStart(2, '0')).join('');
const deviceOnline = (d, now) => d.seen_at > now - (d.proto >= 2 ? ONLINE_MS : LEGACY_ONLINE_MS);

// ─────────────────────────────── limbajul comenzilor ───────────────────────────────
// Un rând = un verb + argumente, în română sau engleză, fără diacritice obligatorii. Același parser pentru site și CLI,
// deci o singură gramatică, testată o singură dată (screen-mirror.test.mjs).
const strip = s => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
const VERBS = {
  tap: 'tap', apasa: 'tap', atinge: 'tap', click: 'tap',
  swipe: 'swipe', trage: 'swipe', gliseaza: 'swipe',
  key: 'key', tasta: 'key',
  type: 'type', scrie: 'type', write: 'type',
  open: 'open', deschide: 'open', launch: 'open',
  say: 'say', spune: 'say', zi: 'say', voice: 'say', voce: 'say',
  read: 'read', citeste: 'read', ecran: 'read', screen: 'read',
  apps: 'apps', aplicatii: 'apps',
  shot: 'shot', poza: 'shot', captura: 'shot', screenshot: 'shot', cadru: 'shot',
  scroll: 'scroll', deruleaza: 'scroll',
  info: 'info', stare: 'info', status: 'info',
  back: 'key', inapoi: 'key', home: 'key', acasa: 'key', recents: 'key', recente: 'key',
};
const KEYS = {
  back: 'back', inapoi: 'back', home: 'home', acasa: 'home', recents: 'recents', recente: 'recents',
  notifications: 'notifications', notificari: 'notifications', settings: 'quick_settings', setari: 'quick_settings', quick_settings: 'quick_settings',
  lock: 'lock', blocheaza: 'lock',
};
export const KEY_NAMES = Object.freeze([...new Set(Object.values(KEYS))]);
export const USAGE = Object.freeze({
  tap: 'tap <x> <y>  · fracții 0–1 (0.5 0.3), procente (50% 30%) sau pixeli (540 700)',
  swipe: 'swipe <x1> <y1> <x2> <y2> [ms]  · aceleași unități; ms între 50 și 5000',
  key: 'key back|home|recents|notifications|settings|lock',
  type: 'type <text>  · scrie în câmpul focalizat',
  open: 'open <aplicație>  · numele sau pachetul (ex. open youtube)',
  say: 'say <comandă vocală>  · ca „Hei FORJA”: say pornește muzica',
  read: 'read  · textul de pe ecran',
  apps: 'apps  · aplicațiile instalate',
  shot: 'shot  · un cadru acum',
  scroll: 'scroll up|down',
  info: 'info  · telefonul: model, Android, ecran, aplicația din față, baterie',
  adb: 'ca la adb („adb” și „shell” se pot omite): input tap 540 1200 · input swipe 540 1800 540 600 300 · input text salut%sana · input keyevent KEYCODE_BACK|4 · am start -n pachet/.Activitate · monkey -p pachet 1 · pm list packages · screencap -p · dumpsys · wm size · uiautomator dump',
});
export const HELP = Object.freeze(Object.values(USAGE));
// Tastele adb pe care le poate apăsa serviciul de accesibilitate (restul nu există fără root / adb adevărat).
const KEYCODES = { back: 'back', 4: 'back', home: 'home', 3: 'home', app_switch: 'recents', 187: 'recents', notification: 'notifications', 83: 'notifications', settings: 'quick_settings', 176: 'quick_settings', power: 'lock', 26: 'lock', sleep: 'lock', 223: 'lock' };
const KEYCODE_HELP = 'Tastele disponibile: KEYCODE_BACK (4), KEYCODE_HOME (3), KEYCODE_APP_SWITCH (187), KEYCODE_NOTIFICATION (83), KEYCODE_SETTINGS (176), KEYCODE_POWER (26).';
/**
 * Sintaxa adb, pentru programatori și agenți: „adb shell input tap 540 1200”, „input keyevent KEYCODE_BACK”, „am start -n pkg/.A”,
 * „monkey -p pkg 1”, „pm list packages”, „screencap -p”, „dumpsys …”, „wm size”, „getprop”, „uiautomator dump”.
 * Întoarce comanda noastră sau null când rândul nu e în sintaxa adb. Coordonatele adb sunt mereu pixeli.
 */
function adbCommand(parts) {
  let p = parts;
  while (p.length && ['adb', 'shell'].includes(strip(p[0]))) p = p.slice(1);
  if (!p.length) return null;
  const first = strip(p[0]);
  const px = (list, n, kind) => { const v = list.slice(0, n).map(x => /^\d+$/.test(x) ? Number(x) : NaN); if (v.length < n || v.some(x => !Number.isFinite(x) || x > 20000)) bad('Folosire: input ' + kind + ' ' + (kind === 'tap' ? '<x> <y>' : '<x1> <y1> <x2> <y2> [ms]') + ' (pixeli)'); return v; };
  if (first === 'input') {
    const sub = strip(p[1] || ''), rest = p.slice(2);
    if (sub === 'tap') { const [x, y] = px(rest, 2, 'tap'); if (rest.length > 2) bad('Folosire: input tap <x> <y> (pixeli)'); return { kind: 'tap', x, y, unit: 'px' }; }
    if (sub === 'swipe') {
      const [x1, y1, x2, y2] = px(rest, 4, 'swipe'); const [min, max, dflt] = SCREEN_RULES.swipe_ms; let ms = dflt;
      if (rest[4] !== undefined) { ms = Number(rest[4]); if (!Number.isInteger(ms) || ms < min || ms > max) bad('Folosire: input swipe <x1> <y1> <x2> <y2> [ms 50–5000]'); }
      if (rest.length > 5) bad('Folosire: input swipe <x1> <y1> <x2> <y2> [ms]');
      return { kind: 'swipe', x1, y1, x2, y2, ms, unit: 'px' };
    }
    if (sub === 'text') return { kind: 'type', text: text(rest.join(' ').replace(/%s/g, ' '), SCREEN_RULES.text_max, 'type') };
    if (sub === 'keyevent') {
      const raw = strip(rest[0] || '').replace(/^keycode_/, ''); const name = KEYCODES[raw];
      if (!name || rest.length > 1) bad('Tasta „' + (rest[0] || '').slice(0, 30) + '” nu e disponibilă. ' + KEYCODE_HELP);
      return { kind: 'key', name };
    }
    bad('Folosire: input tap|swipe|text|keyevent …');
  }
  if (first === 'screencap') return { kind: 'shot' };
  if (first === 'am') {
    if (strip(p[1] || '') !== 'start') bad('Folosire: am start -n <pachet/.Activitate> (sau am start <pachet>)');
    const n = p.indexOf('-n'); const comp = n >= 0 ? p[n + 1] : p.slice(2).find(x => !x.startsWith('-'));
    return { kind: 'open', app: text(comp, SCREEN_RULES.open_max, 'open') };
  }
  if (first === 'monkey') { const i = p.indexOf('-p'); if (i < 0 || !p[i + 1]) bad('Folosire: monkey -p <pachet> 1'); return { kind: 'open', app: text(p[i + 1], SCREEN_RULES.open_max, 'open') }; }
  if (first === 'pm') { if (strip(p[1] || '') !== 'list') bad('Folosire: pm list packages'); return { kind: 'apps' }; }
  if (first === 'dumpsys' || first === 'wm' || first === 'getprop') return { kind: 'info' };
  if (first === 'uiautomator') return { kind: 'read' };
  return null;
}
const usage = kind => bad('Folosire: ' + USAGE[kind]);

/** Un număr de coordonată: „0.52” sau „52%” = fracție din ecran; „540” = pixeli. Întoarce {v, unit}. */
function coord(raw) {
  const s = String(raw || '').trim().replace(',', '.');
  const pct = /^(\d+(?:\.\d+)?)%$/.exec(s);
  if (pct) { const v = Number(pct[1]) / 100; if (v > 1) bad('Procentul e peste 100.'); return { v, unit: 'frac' }; }
  if (!/^\d+(?:\.\d+)?$/.test(s)) return null;
  const v = Number(s);
  if (v <= 1) return { v, unit: 'frac' };
  if (!Number.isInteger(v) || v > 20000) bad('Pixelii sunt numere întregi până la 20000.');
  return { v, unit: 'px' };
}
function coords(parts, kind, n) {
  const list = parts.slice(0, n).map(coord);
  if (list.length < n || list.some(c => !c)) usage(kind);
  const unit = list.some(c => c.unit === 'px') ? 'px' : 'frac';
  // Fracții și pixeli amestecați: pixelii decid (0 și 1 rămân valide ca pixeli).
  return { unit, values: list.map(c => c.v) };
}
const text = (s, max, kind) => {
  let v = String(s || '').trim();
  if ((v.startsWith('"') && v.endsWith('"') || v.startsWith('„') && v.endsWith('”') || v.startsWith("'") && v.endsWith("'")) && v.length >= 2) v = v.slice(1, -1).trim();
  if (!v) usage(kind);
  if (v.length > max) bad('Textul are cel mult ' + max + ' de caractere.');
  if (/[\u0000-\u0008\u000b-\u001f\u007f]/.test(v)) bad('Textul conține caractere de control.');
  return v;
};

/**
 * `line` → comanda pentru telefon ({kind, …}). Aruncă 400 cu un mesaj în română (și folosirea verbului) când nu e validă.
 * Coordonatele pleacă cu unitatea lor (`frac` 0–1 sau `px`): telefonul le așază pe ecranul lui real.
 */
export function parseCommand(line) {
  const raw = String(line ?? '').trim();
  if (!raw) bad('Scrie o comandă. „help” le arată pe toate.');
  if (raw.length > SCREEN_RULES.line_max) bad('Comanda e prea lungă.');
  const parts = raw.split(/\s+/);
  const adb = adbCommand(parts);
  if (adb) return adb;
  const verb = strip(parts[0]);
  const kind = VERBS[verb];
  if (!kind) bad('Comandă necunoscută: „' + parts[0].slice(0, 40) + '”. Scrie „help”.');
  const rest = parts.slice(1);
  const tail = raw.slice(parts[0].length).trim();
  switch (kind) {
    case 'tap': { const c = coords(rest, 'tap', 2); return { kind, x: c.values[0], y: c.values[1], unit: c.unit }; }
    case 'swipe': {
      const c = coords(rest, 'swipe', 4);
      const [min, max, dflt] = SCREEN_RULES.swipe_ms;
      let ms = dflt;
      if (rest[4] !== undefined) { ms = Number(rest[4]); if (!Number.isInteger(ms) || ms < min || ms > max) usage('swipe'); }
      if (rest.length > 5) usage('swipe');
      return { kind, x1: c.values[0], y1: c.values[1], x2: c.values[2], y2: c.values[3], ms, unit: c.unit };
    }
    case 'key': {
      // „back” / „home” / „recents” merg și singure: verbul e tasta.
      const name = KEYS[strip(rest[0] !== undefined ? rest[0] : parts[0])];
      if (!name || rest.length > 1) usage('key');
      return { kind, name };
    }
    case 'type': return { kind, text: text(tail, SCREEN_RULES.text_max, 'type') };
    case 'open': return { kind, app: text(tail, SCREEN_RULES.open_max, 'open') };
    case 'say': return { kind, text: text(tail, SCREEN_RULES.say_max, 'say') };
    case 'scroll': {
      const dir = { up: 'up', sus: 'up', down: 'down', jos: 'down' }[strip(rest[0] || 'down')];
      if (!dir || rest.length > 1) usage('scroll');
      return { kind, dir };
    }
    case 'read': case 'apps': case 'shot': case 'info':
      if (rest.length) usage(kind);
      return { kind };
    default: bad('Comandă necunoscută.');
  }
}

// ─────────────────────────────── cererea „vreau ecranul” (pe disc, pentru bătaie) ───────────────────────────────
async function requestPhone(storage, id, now) {
  const cur = await storage.get(REQUEST + id);
  if (cur && cur.until > now) return cur;
  const req = { at: now, until: now + SCREEN_RULES.request_ms };
  await storage.put(REQUEST + id, req);
  // Alarma DO-ului (sweep) șterge cererea când expiră, și fără alt eveniment.
  try { const a = await storage.getAlarm(); if (a === null || a === undefined || a > req.until) await storage.setAlarm(req.until); } catch { }
  return req;
}
async function currentRequest(storage, id, now) {
  const cur = await storage.get(REQUEST + id);
  if (!cur) return null;
  if (cur.until <= now) { await storage.delete(REQUEST + id); return null; }
  return cur;
}
/** Din sweep-ul DO-ului: cererile expirate dispar. Întoarce următorul moment când mai e ceva de șters. */
export async function sweepScreen(storage, now = Date.now()) {
  let next = Infinity;
  for (const [key, r] of await storage.list({ prefix: REQUEST })) {
    if (!r || r.until <= now) await storage.delete(key); else next = Math.min(next, r.until);
  }
  return next;
}

/** Din bătaia găsirii: ce știe telefonul despre ecran ({supported, enabled, android}); câmpurile greșite se ignoră. */
export function noteScreenCapability(d, v) {
  if (!v || typeof v !== 'object' || Array.isArray(v)) return;
  const cap = {};
  if (typeof v.supported === 'boolean') cap.supported = v.supported;
  if (typeof v.enabled === 'boolean') cap.enabled = v.enabled;
  if (Number.isInteger(v.android) && v.android > 0 && v.android < 100) cap.android = v.android;
  if (Object.keys(cap).length) d.screen_cap = { ...(d.screen_cap || {}), ...cap, at: Date.now() };
}
/** Răspunsul bătăii: `screen: {wanted, until}` cât cineva așteaptă ecranul (cerere pe disc sau un privitor conectat). */
export async function screenForBeat(account, id, now) {
  const req = await currentRequest(account.ctx.storage, id, now);
  const viewers = sockets(account, tagViewer(id)).length;
  if (!req && !viewers) return null;
  return { wanted: true, until: req ? req.until : now + SCREEN_RULES.request_ms, viewers };
}

// ─────────────────────────────── legăturile WebSocket (în DO-ul contului) ───────────────────────────────
const tagPhone = id => 'screen-phone:' + id, tagViewer = id => 'screen-viewer:' + id;
function hub(account) {
  if (!account.screenHub) account.screenHub = { frames: new Map(), phones: new Map(), pending: new Map(), seq: 0 };
  return account.screenHub;
}
function sockets(account, tag) { try { return account.ctx.getWebSockets(tag) || []; } catch { return []; } }
function attachment(ws) { try { return ws.deserializeAttachment() || null; } catch { return null; } }
function send(ws, data) {
  try { ws.send(typeof data === 'string' || data instanceof ArrayBuffer || ArrayBuffer.isView(data) ? data : JSON.stringify(data)); return true; }
  catch { return false; }
}
function shut(ws, code, reason) { try { ws.close(code, reason); } catch { } }
const phoneOf = (account, id) => sockets(account, tagPhone(id))[0] || null;
function stateFor(account, id, device, req, now) {
  const h = hub(account), live = !!phoneOf(account, id), p = h.phones.get(id) || null;
  return {
    t: 'state', phone: live ? 'live' : req ? 'waiting' : 'off', viewers: sockets(account, tagViewer(id)).length,
    width: p?.width ?? null, height: p?.height ?? null, fg: p?.fg ?? null, battery: p?.battery ?? null, since: p?.since ?? null,
    requested_until: req?.until ?? null, frame_at: h.frames.get(id)?.at ?? null,
    device: device ? { id: device.id, name: device.name } : { id },
    rules: { idle_ms: SCREEN_RULES.idle_ms, session_max_ms: SCREEN_RULES.session_max_ms }, at: now,
  };
}
async function broadcastState(account, id, now = Date.now()) {
  const device = await account.ctx.storage.get('recovery:device:' + id), req = await currentRequest(account.ctx.storage, id, now);
  const state = stateFor(account, id, device, req, now);
  for (const v of sockets(account, tagViewer(id))) send(v, state);
  return state;
}
function tellPhoneViewers(account, id) {
  const phone = phoneOf(account, id);
  if (phone) send(phone, { t: 'watch', viewers: sockets(account, tagViewer(id)).length });
}
function makePair(account) {
  if (typeof account.ctx.makeWebSocketPair === 'function') return account.ctx.makeWebSocketPair();
  const pair = new WebSocketPair();
  return [pair[0], pair[1]];
}
function accept(account, server, tags, attach) {
  account.ctx.acceptWebSocket(server, tags);
  try { server.serializeAttachment(attach); } catch { }
}
function upgradeResponse(client, protocol) {
  const headers = protocol ? { 'sec-websocket-protocol': protocol } : {};
  // În Workers, Response acceptă 101 + webSocket; în Node (teste) constructorul refuză 101, deci întoarcem forma simplă.
  try { return new Response(null, { status: 101, webSocket: client, headers }); }
  catch { return { status: 101, webSocket: client, headers: new Headers(headers) }; }
}
/** O singură dată per instanță: ping/pong fără să trezească DO-ul (API-ul de hibernare). */
function autoResponse(account) {
  if (account.screenAuto) return;
  account.screenAuto = true;
  try { if (typeof WebSocketRequestResponsePair === 'function') account.ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('{"t":"ping"}', '{"t":"pong"}')); } catch { }
}

async function bindOwner(account, request) {
  const uid = request.headers.get('x-forja-owner');
  if (!uid || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) bad('Invalid owner', 403);
  const owner = await account.ctx.storage.get('owner');
  if (owner && owner !== uid) bad('Wrong owner', 403);
  if (!owner) await account.ctx.storage.put('owner', uid);
  return uid;
}
async function deviceOf(account, id) {
  if (!UUID.test(id)) bad('Telefon invalid.', 404);
  const d = await account.ctx.storage.get('recovery:device:' + id);
  if (!d || d.expires_at <= Date.now()) bad('Telefonul nu e în gardă. Semnează contractul în FORJA.', 404);
  return d;
}

export function isScreenPath(path) { return path === '/v2/screen/devices' || path.startsWith('/v2/screen/devices/'); }

/** Intrarea din InsightsAccount.fetch — în afara blockConcurrencyWhile, ca răspunsul telefonului să poată ajunge. */
export async function handleScreen(request, account) {
  const url = new URL(request.url), path = url.pathname, now = Date.now(), method = request.method;
  await bindOwner(account, request);
  if (path === '/v2/screen/devices') {
    if (method !== 'GET') bad('Method not allowed', 405);
    const devices = [];
    for (const d of (await account.ctx.storage.list({ prefix: 'recovery:device:' })).values()) {
      if (d.expires_at <= now) continue;
      const s = stateFor(account, d.id, d, await currentRequest(account.ctx.storage, d.id, now), now);
      devices.push({ id: d.id, name: d.name, online: deviceOnline(d, now), seen_at: d.seen_at || 0, status: d.status,
        capability: d.screen_cap ? { supported: d.screen_cap.supported ?? null, enabled: d.screen_cap.enabled ?? null, android: d.screen_cap.android ?? null } : null,
        screen: { phone: s.phone, viewers: s.viewers, width: s.width, height: s.height, fg: s.fg, battery: s.battery, since: s.since, requested_until: s.requested_until, frame_at: s.frame_at } });
    }
    return reply({ devices, rules: SCREEN_RULES, help: HELP });
  }
  const m = /^\/v2\/screen\/devices\/([^/]+)\/(socket|phone|frame|command|session)$/.exec(path);
  if (!m) bad('Not found', 404);
  const [, id, action] = m;
  const d = await deviceOf(account, id);
  const upgrade = (request.headers.get('upgrade') || '').toLowerCase() === 'websocket';
  if (action === 'socket' || action === 'phone') {
    if (!upgrade || method !== 'GET') bad('Legătura cere WebSocket.', 426);
    return action === 'phone' ? await acceptPhone(account, request, d, now) : await acceptViewer(account, request, d, now);
  }
  if (action === 'frame') {
    if (method !== 'GET') bad('Method not allowed', 405);
    const f = hub(account).frames.get(id);
    if (!f) bad('Niciun cadru încă. Telefonul trimite unul când cineva privește.', 404);
    return new Response(f.bytes, { headers: { 'content-type': 'image/jpeg', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff', 'x-frame-at': String(f.at) } });
  }
  if (action === 'session') {
    if (method !== 'DELETE') bad('Method not allowed', 405);
    await endSession(account, id, 'site');
    return reply({ ok: true });
  }
  if (action === 'command') {
    if (method !== 'POST') bad('Method not allowed', 405);
    if (request.headers.get('content-type')?.split(';')[0] !== 'application/json') bad('JSON required', 415);
    let v; try { v = await request.json(); } catch { bad('Invalid JSON'); }
    if (!v || typeof v !== 'object' || typeof v.line !== 'string') bad('Trimite {line:"…"}.');
    const cmd = parseCommand(v.line);
    const phone = phoneOf(account, id);
    if (!phone) {
      const req = await requestPhone(account.ctx.storage, id, now);
      return reply({ error: 'Telefonul nu e conectat. L-am chemat: se conectează la următoarea bătaie (de obicei sub un minut).', phone: 'waiting', requested_until: req.until }, 409);
    }
    const out = await dispatch(account, id, phone, cmd, v.line, null);
    return reply({ ok: out.ok !== false, ...out, line: v.line }, out.ok === false && out.timeout ? 504 : 200);
  }
  bad('Not found', 404);
}

async function acceptPhone(account, request, d, now) {
  const secret = request.headers.get('x-forja-device-secret') || '';
  if (!SECRET.test(secret) || await hash(secret) !== d.secret_hash) bad('Activarea telefonului s-a schimbat.', 403);
  autoResponse(account);
  const [client, server] = makePair(account);
  // Un singur telefon pe identitate: o legătură veche rămasă (proces ucis fără close) se închide acum.
  for (const old of sockets(account, tagPhone(d.id))) shut(old, 4409, 'replaced');
  accept(account, server, [tagPhone(d.id)], { role: 'phone', device: d.id, since: now });
  hub(account).phones.set(d.id, { since: now, width: null, height: null, fg: null, battery: null });
  await account.ctx.storage.delete(REQUEST + d.id); // telefonul e aici: cererea și-a făcut treaba
  send(server, { t: 'watch', viewers: sockets(account, tagViewer(d.id)).length, rules: { idle_ms: SCREEN_RULES.idle_ms, session_max_ms: SCREEN_RULES.session_max_ms, frame_max_bytes: SCREEN_RULES.frame_max_bytes } });
  await broadcastState(account, d.id, now);
  return upgradeResponse(client, null);
}
async function acceptViewer(account, request, d, now) {
  if (sockets(account, tagViewer(d.id)).length >= SCREEN_RULES.viewers_max) bad('Prea mulți privitori (cel mult ' + SCREEN_RULES.viewers_max + ').', 429);
  autoResponse(account);
  const [client, server] = makePair(account);
  accept(account, server, [tagViewer(d.id)], { role: 'viewer', device: d.id, since: now });
  const phone = phoneOf(account, d.id);
  if (phone) { tellPhoneViewers(account, d.id); send(phone, { t: 'who' }); }
  else await requestPhone(account.ctx.storage, d.id, now);
  const state = await broadcastState(account, d.id, now);
  send(server, state);
  const f = hub(account).frames.get(d.id);
  if (f) send(server, f.bytes);
  // Browserul a cerut subprotocolul „forja” (cu tokenul alături): răspundem cu el, altfel refuză legătura.
  const protocols = (request.headers.get('sec-websocket-protocol') || '').split(',').map(s => s.trim());
  return upgradeResponse(client, protocols.includes('forja') ? 'forja' : null);
}

/** Trimite comanda telefonului și așteaptă răspunsul (≤ command_timeout_ms). `viewer` = cine a cerut (sau null = HTTP). */
function dispatch(account, id, phone, cmd, line, viewer) {
  const h = hub(account), cid = String(++h.seq) + '-' + Math.random().toString(36).slice(2, 8);
  return new Promise(resolve => {
    const timer = setTimeout(() => { h.pending.delete(cid); resolve({ ok: false, timeout: true, text: 'Telefonul nu a răspuns în ' + Math.round(SCREEN_RULES.command_timeout_ms / 1000) + ' s.' }); }, SCREEN_RULES.command_timeout_ms);
    h.pending.set(cid, { resolve, timer, viewer, device: id, line, at: Date.now() });
    if (!send(phone, { t: 'cmd', id: cid, ...cmd })) { clearTimeout(timer); h.pending.delete(cid); resolve({ ok: false, text: 'Legătura cu telefonul s-a rupt.' }); }
  });
}
function finish(account, cid, result) {
  const h = hub(account), p = h.pending.get(cid);
  if (!p) return false;
  clearTimeout(p.timer); h.pending.delete(cid);
  p.resolve(result);
  return true;
}
async function endSession(account, id, reason) {
  const phone = phoneOf(account, id);
  if (phone) { send(phone, { t: 'end', reason }); shut(phone, 4000, 'end'); }
  for (const v of sockets(account, tagViewer(id))) { send(v, { t: 'state', phone: 'off', viewers: 0, device: { id }, ended: reason }); shut(v, 4000, 'end'); }
  hub(account).frames.delete(id); hub(account).phones.delete(id);
  await account.ctx.storage.delete(REQUEST + id);
}

/** DO.webSocketMessage: cadre și mesaje de la telefon, comenzi de la privitori. */
export async function screenMessage(account, ws, message) {
  const a = attachment(ws);
  if (!a?.device) { shut(ws, 4400, 'unknown'); return; }
  const id = a.device, h = hub(account), now = Date.now();
  if (a.role === 'phone') {
    if (typeof message !== 'string') {
      const bytes = message instanceof ArrayBuffer ? new Uint8Array(message) : ArrayBuffer.isView(message) ? new Uint8Array(message.buffer, message.byteOffset, message.byteLength) : null;
      if (!bytes || !bytes.byteLength || bytes.byteLength > SCREEN_RULES.frame_max_bytes) return;
      h.frames.set(id, { bytes, at: now });
      for (const v of sockets(account, tagViewer(id))) send(v, bytes);
      return;
    }
    let m; try { m = JSON.parse(message); } catch { return; }
    if (!m || typeof m !== 'object') return;
    if (m.t === 'state' || m.t === 'hello') {
      const p = h.phones.get(id) || { since: a.since || now };
      if (Number.isInteger(m.width) && m.width > 0 && m.width < 20000) p.width = m.width;
      if (Number.isInteger(m.height) && m.height > 0 && m.height < 20000) p.height = m.height;
      if (typeof m.fg === 'string') p.fg = m.fg.slice(0, 200);
      if (Number.isInteger(m.battery) && m.battery >= 0 && m.battery <= 100) p.battery = m.battery;
      h.phones.set(id, p);
      await broadcastState(account, id, now);
      return;
    }
    if (m.t === 'result' && typeof m.id === 'string') {
      const p = h.pending.get(m.id);
      const result = { ok: m.ok !== false, text: typeof m.text === 'string' ? m.text.slice(0, SCREEN_RULES.result_text_max) : '', ...(m.data !== undefined ? { data: m.data } : {}) };
      if (p?.viewer) send(p.viewer, { t: 'result', id: p.clientId || m.id, line: p.line, ...result });
      finish(account, m.id, result);
      return;
    }
    if (m.t === 'bye') { await screenClose(account, ws, 1000, 'bye'); shut(ws, 1000, 'bye'); }
    return;
  }
  if (a.role === 'viewer') {
    if (typeof message !== 'string') return;
    let m; try { m = JSON.parse(message); } catch { send(ws, { t: 'error', message: 'Mesaj invalid.' }); return; }
    if (m?.t === 'ping') { send(ws, { t: 'pong' }); return; }
    if (m?.t === 'state') { send(ws, await broadcastState(account, id, now)); return; }
    if (m?.t !== 'cmd') { send(ws, { t: 'error', message: 'Mesaj necunoscut.' }); return; }
    const clientId = typeof m.id === 'string' ? m.id.slice(0, 64) : null;
    let cmd;
    try { cmd = parseCommand(m.line); } catch (e) { send(ws, { t: 'result', id: clientId, line: typeof m.line === 'string' ? m.line.slice(0, 200) : '', ok: false, text: e.message }); return; }
    const phone = phoneOf(account, id);
    if (!phone) {
      const req = await requestPhone(account.ctx.storage, id, now);
      send(ws, { t: 'result', id: clientId, line: m.line, ok: false, text: 'Telefonul nu e conectat. L-am chemat: se conectează la următoarea bătaie.', requested_until: req.until });
      return;
    }
    const cid = String(++h.seq) + '-' + Math.random().toString(36).slice(2, 8);
    const timer = setTimeout(() => { if (h.pending.delete(cid)) send(ws, { t: 'result', id: clientId, line: m.line, ok: false, text: 'Telefonul nu a răspuns în ' + Math.round(SCREEN_RULES.command_timeout_ms / 1000) + ' s.' }); }, SCREEN_RULES.command_timeout_ms);
    h.pending.set(cid, { resolve: () => { }, timer, viewer: ws, clientId, device: id, line: m.line, at: now });
    if (!send(phone, { t: 'cmd', id: cid, ...cmd })) { clearTimeout(timer); h.pending.delete(cid); send(ws, { t: 'result', id: clientId, line: m.line, ok: false, text: 'Legătura cu telefonul s-a rupt.' }); }
  }
}

/** DO.webSocketClose / webSocketError. */
export async function screenClose(account, ws) {
  const a = attachment(ws);
  if (!a?.device) return;
  const id = a.device, h = hub(account), now = Date.now();
  if (a.role === 'phone') {
    // Doar dacă e chiar legătura curentă (o legătură înlocuită se închide după ce noua a fost acceptată).
    if (sockets(account, tagPhone(id)).some(s => s !== ws)) return;
    h.phones.delete(id);
    for (const [cid, p] of [...h.pending]) if (p.device === id) { if (p.viewer) send(p.viewer, { t: 'result', id: p.clientId || cid, line: p.line, ok: false, text: 'Telefonul s-a deconectat.' }); finish(account, cid, { ok: false, text: 'Telefonul s-a deconectat.' }); }
    // Privitorii mai sunt aici: cererea rămâne pe disc, bătaia îl aduce înapoi.
    if (sockets(account, tagViewer(id)).some(s => s !== ws)) await requestPhone(account.ctx.storage, id, now);
    await broadcastState(account, id, now);
    return;
  }
  if (a.role === 'viewer') {
    for (const [cid, p] of [...h.pending]) if (p.viewer === ws) finish(account, cid, { ok: false, text: 'closed' });
    const phone = phoneOf(account, id);
    if (phone) send(phone, { t: 'watch', viewers: sockets(account, tagViewer(id)).filter(s => s !== ws).length });
  }
}
