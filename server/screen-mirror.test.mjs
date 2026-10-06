// FORJA 5.1 — ecranul telefonului pe site: gramatica comenzilor, legăturile WebSocket din DO-ul contului (telefon + privitori,
// cu socket-uri false), cadrele, comenzile pe socket și pe HTTP, cererea „vreau ecranul” din bătaia găsirii, închiderea.
import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { InsightsAccount } from './insights-store.mjs';
import { parseCommand, SCREEN_RULES, HELP, KEY_NAMES, sweepScreen } from './screen-mirror.mjs';

class Storage {
  m = new Map(); alarm = null;
  async get(k) { return structuredClone(this.m.get(k)); }
  async put(k, v) { if (typeof k === 'object') { for (const [a, b] of Object.entries(k)) this.m.set(a, structuredClone(b)); } else this.m.set(k, structuredClone(v)); }
  async delete(keys) { for (const k of Array.isArray(keys) ? keys : [keys]) this.m.delete(k); }
  async list({ prefix }) { return new Map([...this.m].filter(([k]) => k.startsWith(prefix)).map(([k, v]) => [k, structuredClone(v)])); }
  async getAlarm() { return this.alarm; } async setAlarm(n) { this.alarm = n; } async deleteAlarm() { this.alarm = null; }
}
/** Un capăt de WebSocket fals: ce trimite DO-ul ajunge în `sent`; perechea client/server e legată prin `peer`. */
class FakeSocket {
  constructor(name) { this.name = name; this.sent = []; this.closed = null; this.att = null; this.peer = null; this.tags = []; }
  send(d) { if (this.closed) throw Error('closed'); this.sent.push(d); }
  close(code, reason) { this.closed = { code, reason }; }
  serializeAttachment(a) { this.att = structuredClone(a); }
  deserializeAttachment() { return this.att; }
  json() { return this.sent.filter(x => typeof x === 'string').map(x => JSON.parse(x)); }
  frames() { return this.sent.filter(x => typeof x !== 'string'); }
  last(t) { return this.json().filter(m => m.t === t).at(-1) || null; }
}
function fixture() {
  const storage = new Storage(); let queue = Promise.resolve(); const accepted = [];
  const ctx = {
    storage,
    blockConcurrencyWhile(fn) { const job = queue.then(fn); queue = job.catch(() => { }); return job; },
    acceptWebSocket(ws, tags) { ws.tags = tags; accepted.push(ws); },
    getWebSockets(tag) { return accepted.filter(w => !w.closed && (!tag || w.tags.includes(tag))); },
    makeWebSocketPair() { const client = new FakeSocket('client'), server = new FakeSocket('server'); client.peer = server; server.peer = client; return [client, server]; },
    setWebSocketAutoResponse() { },
  };
  const account = new InsightsAccount(ctx, { RECORDS: {} });
  const call = async (path, method = 'GET', body, uid = 'alice', headers = {}) => {
    const r = await account.fetch(new Request('https://forja.test' + path, { method, headers: { 'content-type': 'application/json', 'x-forja-owner': uid, ...headers }, ...(body ? { body: JSON.stringify(body) } : {}) }));
    const type = r.headers.get('content-type') || '';
    return { status: r.status, headers: r.headers, ...(type.includes('json') ? await r.json() : { bytes: new Uint8Array(await r.arrayBuffer()) }) };
  };
  /** O cerere de Upgrade; la 101 întoarce capătul „client” (ce trimite DO-ul e în `ws.peer.sent`). */
  const upgrade = async (path, headers = {}, uid = 'alice') => {
    const r = await account.fetch(new Request('https://forja.test' + path, { headers: { upgrade: 'websocket', 'x-forja-owner': uid, ...headers } }));
    if (r.status === 101) return { status: 101, ws: r.webSocket, server: r.webSocket.peer, headers: r.headers };
    return { status: r.status, body: await r.json() };
  };
  return { account, storage, ctx, call, upgrade, accepted };
}
async function enroll(f, version = 4) {
  const id = randomUUID(), secret = randomBytes(32).toString('hex');
  assert.equal((await f.call(`/v2/recovery/devices/${id}/grant`, 'POST', { name: 'Galaxy S23', secret, basis: 'contract', contract_version: version })).status, 200);
  return { id, secret };
}
const beat = (f, d, extra = {}) => f.call(`/v2/recovery/devices/${d.id}/beat`, 'POST', { secret: d.secret, status: 'ready', ...extra });
const phoneLink = (f, d) => f.upgrade(`/v2/screen/devices/${d.id}/phone`, { 'x-forja-device-secret': d.secret });
const viewerLink = (f, d, protocol = 'forja, bearer.tok') => f.upgrade(`/v2/screen/devices/${d.id}/socket`, { 'sec-websocket-protocol': protocol });
/** Ce spune telefonul DO-ului (mesajul ajunge pe capătul server). */
const fromPhone = (f, link, data) => f.account.webSocketMessage(link.server, typeof data === 'string' || data instanceof ArrayBuffer ? data : JSON.stringify(data));
const fromViewer = (f, link, data) => f.account.webSocketMessage(link.server, JSON.stringify(data));
const closeLink = (f, link) => { link.server.closed = { code: 1000 }; return f.account.webSocketClose(link.server, 1000, 'bye'); };
const jpeg = n => new Uint8Array([0xff, 0xd8, 0xff, ...new Array(n).fill(7), 0xff, 0xd9]).buffer;

// ── gramatica ──
test('tap: fractions, percents and pixels; mixed units become pixels', () => {
  assert.deepEqual(parseCommand('tap 0.5 0.25'), { kind: 'tap', x: 0.5, y: 0.25, unit: 'frac' });
  assert.deepEqual(parseCommand('apasă 50% 25%'), { kind: 'tap', x: 0.5, y: 0.25, unit: 'frac' });
  assert.deepEqual(parseCommand('atinge 540 1200'), { kind: 'tap', x: 540, y: 1200, unit: 'px' });
  assert.deepEqual(parseCommand('click 1 1'), { kind: 'tap', x: 1, y: 1, unit: 'frac' }, '1 is still a fraction (the far edge)');
  assert.deepEqual(parseCommand('tap 0.5 900'), { kind: 'tap', x: 0.5, y: 900, unit: 'px' });
  assert.throws(() => parseCommand('tap 0.5'), /Folosire: tap/);
  assert.throws(() => parseCommand('tap a b'), /Folosire: tap/);
  assert.throws(() => parseCommand('tap 120% 10%'), /peste 100/);
  assert.throws(() => parseCommand('tap 540.5 10'), /întregi/);
});
test('swipe: four coordinates, optional duration within 50–5000 ms', () => {
  assert.deepEqual(parseCommand('swipe 0.5 0.8 0.5 0.2'), { kind: 'swipe', x1: 0.5, y1: 0.8, x2: 0.5, y2: 0.2, ms: 300, unit: 'frac' });
  assert.deepEqual(parseCommand('trage 100 900 100 300 600'), { kind: 'swipe', x1: 100, y1: 900, x2: 100, y2: 300, ms: 600, unit: 'px' });
  assert.throws(() => parseCommand('swipe 0.5 0.8 0.5 0.2 10'), /Folosire: swipe/);
  assert.throws(() => parseCommand('swipe 0.5 0.8 0.5 0.2 400 extra'), /Folosire: swipe/);
  assert.throws(() => parseCommand('swipe 0.5 0.8 0.5'), /Folosire: swipe/);
});
test('key: named keys with Romanian aliases; back/home/recents work bare', () => {
  assert.deepEqual(parseCommand('key back'), { kind: 'key', name: 'back' });
  assert.deepEqual(parseCommand('tastă înapoi'), { kind: 'key', name: 'back' });
  assert.deepEqual(parseCommand('acasă'), { kind: 'key', name: 'home' });
  assert.deepEqual(parseCommand('recents'), { kind: 'key', name: 'recents' });
  assert.deepEqual(parseCommand('key setări'), { kind: 'key', name: 'quick_settings' });
  assert.deepEqual(parseCommand('key lock'), { kind: 'key', name: 'lock' });
  assert.throws(() => parseCommand('key power'), /Folosire: key/);
  assert.throws(() => parseCommand('key back now'), /Folosire: key/);
  assert.deepEqual(KEY_NAMES, ['back', 'home', 'recents', 'notifications', 'quick_settings', 'lock']);
});
test('type / open / say keep the text (quotes stripped, case kept) within their limits', () => {
  assert.deepEqual(parseCommand('type Salut, Ana'), { kind: 'type', text: 'Salut, Ana' });
  assert.deepEqual(parseCommand('scrie "Bună ziua"'), { kind: 'type', text: 'Bună ziua' });
  assert.deepEqual(parseCommand('open YouTube'), { kind: 'open', app: 'YouTube' });
  assert.deepEqual(parseCommand('deschide com.spotify.music'), { kind: 'open', app: 'com.spotify.music' });
  assert.deepEqual(parseCommand('say pornește muzica'), { kind: 'say', text: 'pornește muzica' });
  assert.deepEqual(parseCommand('spune „ce grad am”'), { kind: 'say', text: 'ce grad am' });
  assert.throws(() => parseCommand('type'), /Folosire: type/);
  assert.throws(() => parseCommand('type ' + 'x'.repeat(SCREEN_RULES.text_max + 1)), /cel mult/);
  assert.throws(() => parseCommand('say ' + 'x'.repeat(SCREEN_RULES.say_max + 1)), /cel mult/);
  assert.throws(() => parseCommand('type a\u0007b'), /control/);
});
test('scroll, read, apps, shot, info; unknown, empty and oversized lines', () => {
  assert.deepEqual(parseCommand('scroll'), { kind: 'scroll', dir: 'down' });
  assert.deepEqual(parseCommand('derulează sus'), { kind: 'scroll', dir: 'up' });
  assert.throws(() => parseCommand('scroll left'), /Folosire: scroll/);
  for (const [line, kind] of [['read', 'read'], ['citește', 'read'], ['apps', 'apps'], ['aplicații', 'apps'], ['shot', 'shot'], ['poză', 'shot'], ['info', 'info'], ['stare', 'info']]) assert.deepEqual(parseCommand(line), { kind });
  assert.throws(() => parseCommand('read now'), /Folosire: read/);
  assert.throws(() => parseCommand('dance'), /Comandă necunoscută/);
  assert.throws(() => parseCommand(''), /Scrie o comandă/);
  assert.throws(() => parseCommand('tap ' + '1 '.repeat(2000)), /prea lungă/);
  assert.equal(HELP.length, 12);
});
test('adb syntax: input tap/swipe/text/keyevent, am start, monkey, pm list, screencap, dumpsys, uiautomator', () => {
  assert.deepEqual(parseCommand('adb shell input tap 540 1200'), { kind: 'tap', x: 540, y: 1200, unit: 'px' });
  assert.deepEqual(parseCommand('input tap 1 1'), { kind: 'tap', x: 1, y: 1, unit: 'px' }, 'adb coordinates are always pixels');
  assert.deepEqual(parseCommand('shell input swipe 540 1800 540 600'), { kind: 'swipe', x1: 540, y1: 1800, x2: 540, y2: 600, ms: 300, unit: 'px' });
  assert.deepEqual(parseCommand('input swipe 540 1800 540 600 800'), { kind: 'swipe', x1: 540, y1: 1800, x2: 540, y2: 600, ms: 800, unit: 'px' });
  assert.deepEqual(parseCommand('input text salut%sana'), { kind: 'type', text: 'salut ana' });
  assert.deepEqual(parseCommand('adb shell input text "Bună ziua"'), { kind: 'type', text: 'Bună ziua' });
  assert.deepEqual(parseCommand('input keyevent KEYCODE_BACK'), { kind: 'key', name: 'back' });
  assert.deepEqual(parseCommand('input keyevent 4'), { kind: 'key', name: 'back' });
  assert.deepEqual(parseCommand('input keyevent KEYCODE_APP_SWITCH'), { kind: 'key', name: 'recents' });
  assert.deepEqual(parseCommand('input keyevent 26'), { kind: 'key', name: 'lock' });
  assert.throws(() => parseCommand('input keyevent KEYCODE_VOLUME_UP'), /KEYCODE_BACK \(4\)/);
  assert.throws(() => parseCommand('input tap 540'), /Folosire: input tap/);
  assert.throws(() => parseCommand('input'), /Folosire: input/);
  assert.deepEqual(parseCommand('am start -n com.google.android.youtube/.HomeActivity'), { kind: 'open', app: 'com.google.android.youtube/.HomeActivity' });
  assert.deepEqual(parseCommand('adb shell am start com.spotify.music'), { kind: 'open', app: 'com.spotify.music' });
  assert.deepEqual(parseCommand('monkey -p com.spotify.music 1'), { kind: 'open', app: 'com.spotify.music' });
  assert.deepEqual(parseCommand('pm list packages'), { kind: 'apps' });
  assert.deepEqual(parseCommand('screencap -p'), { kind: 'shot' });
  assert.deepEqual(parseCommand('dumpsys window'), { kind: 'info' });
  assert.deepEqual(parseCommand('wm size'), { kind: 'info' });
  assert.deepEqual(parseCommand('uiautomator dump'), { kind: 'read' });
  assert.throws(() => parseCommand('pm uninstall x'), /Folosire: pm list/);
  assert.throws(() => parseCommand('am force-stop x'), /Folosire: am start/);
  assert.throws(() => parseCommand('adb shell rm -rf /'), /Comandă necunoscută/);
});

// ── telefoanele și bătaia ──
test('the listing shows every enrolled phone with its screen state and the capability sent in the beat', async () => {
  const f = fixture();
  assert.deepEqual((await f.call('/v2/screen/devices')).devices, []);
  const d = await enroll(f);
  let list = await f.call('/v2/screen/devices');
  assert.equal(list.status, 200); assert.equal(list.devices.length, 1);
  assert.equal(list.devices[0].id, d.id); assert.equal(list.devices[0].name, 'Galaxy S23'); assert.equal(list.devices[0].online, false);
  assert.equal(list.devices[0].capability, null); assert.equal(list.devices[0].screen.phone, 'off'); assert.equal(list.devices[0].screen.viewers, 0);
  assert.equal(list.rules.idle_ms, SCREEN_RULES.idle_ms); assert.equal(list.help.length, 12);
  const b = await beat(f, d, { screen: { supported: true, enabled: false, android: 14, extra: 'ignored' } });
  assert.equal(b.status, 200); assert.equal(b.screen, null, 'nobody asked for the screen'); assert.equal(b.next_s, 60);
  list = await f.call('/v2/screen/devices');
  assert.deepEqual(list.devices[0].capability, { supported: true, enabled: false, android: 14 }); assert.equal(list.devices[0].online, true);
  await beat(f, d, { screen: { supported: 'yes', android: 999 } });
  assert.deepEqual((await f.call('/v2/screen/devices')).devices[0].capability, { supported: true, enabled: false, android: 14 }, 'invalid values are ignored, the beat is accepted');
  assert.equal((await f.call('/v2/screen/devices', 'POST', {})).status, 405);
  assert.equal((await f.call('/v2/screen/devices', 'GET', undefined, 'bob')).status, 403, 'another account is refused by the owner binding');
});
test('a viewer without a phone leaves a request: the beat says screen.wanted and beats every 10 s; it expires after 10 minutes', async t => {
  const f = fixture(), d = await enroll(f);
  const v = await viewerLink(f, d);
  assert.equal(v.status, 101); assert.equal(v.headers.get('sec-websocket-protocol'), 'forja');
  const state = v.server.last('state');
  assert.equal(state.phone, 'waiting'); assert.equal(state.viewers, 1); assert.equal(state.device.id, d.id); assert.ok(state.requested_until > Date.now());
  const b = await beat(f, d);
  assert.equal(b.screen.wanted, true); assert.equal(b.screen.viewers, 1); assert.equal(b.next_s, SCREEN_RULES.wanted_beat_s);
  await closeLink(f, v);
  const req = await f.storage.get('screen:request:' + d.id);
  assert.ok(req, 'the request stays on disk after the viewer leaves (the phone may still be on its way)');
  assert.equal((await beat(f, d)).screen.wanted, true);
  t.mock.method(Date, 'now', () => req.until + 1);
  assert.equal((await beat(f, d)).screen, null, 'expired: nobody waits any more');
  assert.equal(await f.storage.get('screen:request:' + d.id), undefined);
});
test('sweepScreen removes expired requests and reports the next expiry', async () => {
  const s = new Storage(), now = 1_000_000;
  await s.put('screen:request:a', { at: now - 1, until: now - 1 }); await s.put('screen:request:b', { at: now, until: now + 5000 });
  assert.equal(await sweepScreen(s, now), now + 5000);
  assert.deepEqual([...(await s.list({ prefix: 'screen:request:' })).keys()], ['screen:request:b']);
  assert.equal(await sweepScreen(s, now + 6000), Infinity);
});

// ── legăturile ──
test('the phone link needs an Upgrade and the device secret from Găsire; a second link replaces the first', async () => {
  const f = fixture(), d = await enroll(f);
  assert.equal((await f.call(`/v2/screen/devices/${d.id}/phone`)).status, 426);
  assert.equal((await f.upgrade(`/v2/screen/devices/${d.id}/phone`, { 'x-forja-device-secret': '0'.repeat(64) })).status, 403);
  assert.equal((await f.upgrade(`/v2/screen/devices/${randomUUID()}/phone`, { 'x-forja-device-secret': d.secret })).status, 404);
  assert.equal((await f.upgrade(`/v2/screen/devices/${d.id}/phone`, { 'x-forja-device-secret': d.secret }, 'bob')).status, 403);
  await f.storage.put('screen:request:' + d.id, { at: 1, until: Date.now() + 60000 });
  const p1 = await phoneLink(f, d);
  assert.equal(p1.status, 101); assert.equal(p1.headers.get('sec-websocket-protocol'), null);
  assert.deepEqual(p1.server.last('watch').viewers, 0); assert.equal(p1.server.last('watch').rules.idle_ms, SCREEN_RULES.idle_ms);
  assert.equal(await f.storage.get('screen:request:' + d.id), undefined, 'the phone is here: the request is done');
  assert.equal((await f.call('/v2/screen/devices')).devices[0].screen.phone, 'live');
  const p2 = await phoneLink(f, d);
  assert.deepEqual(p1.server.closed, { code: 4409, reason: 'replaced' });
  assert.equal(f.ctx.getWebSockets('screen-phone:' + d.id).length, 1);
  await closeLink(f, p1);
  assert.equal((await f.call('/v2/screen/devices')).devices[0].screen.phone, 'live', 'closing the replaced link does not touch the new one');
  await closeLink(f, p2);
  assert.equal((await f.call('/v2/screen/devices')).devices[0].screen.phone, 'off');
});
test('viewers see the phone state, its frames and the last frame on arrival; the phone learns how many watch', async () => {
  const f = fixture(), d = await enroll(f);
  const p = await phoneLink(f, d);
  const v1 = await viewerLink(f, d);
  assert.equal(p.server.last('watch').viewers, 1); assert.ok(p.server.json().some(m => m.t === 'who'), 'the phone is asked for its state');
  assert.equal(v1.server.last('state').phone, 'live'); assert.equal(v1.server.last('state').width, null);
  await fromPhone(f, p, { t: 'state', width: 1080, height: 2340, fg: 'com.google.android.youtube', battery: 64 });
  const s = v1.server.last('state');
  assert.equal(s.width, 1080); assert.equal(s.height, 2340); assert.equal(s.fg, 'com.google.android.youtube'); assert.equal(s.battery, 64);
  const frame = jpeg(10);
  await fromPhone(f, p, frame);
  assert.equal(v1.server.frames().length, 1); assert.equal(v1.server.frames()[0].byteLength, frame.byteLength);
  const got = await f.call(`/v2/screen/devices/${d.id}/frame`);
  assert.equal(got.status, 200); assert.equal(got.headers.get('content-type'), 'image/jpeg'); assert.equal(got.bytes.byteLength, frame.byteLength); assert.ok(Number(got.headers.get('x-frame-at')) > 0);
  await fromPhone(f, p, new Uint8Array(SCREEN_RULES.frame_max_bytes + 1).buffer);
  assert.equal(v1.server.frames().length, 1, 'an oversized frame is dropped');
  const v2 = await viewerLink(f, d);
  assert.equal(v2.server.frames().length, 1, 'a late viewer gets the last frame at once');
  assert.equal(v2.server.last('state').viewers, 2); assert.equal(p.server.last('watch').viewers, 2);
  await closeLink(f, v1);
  assert.equal(p.server.last('watch').viewers, 1);
  await closeLink(f, v2);
  assert.equal(p.server.last('watch').viewers, 0);
  assert.equal((await f.call('/v2/screen/devices')).devices[0].screen.frame_at, Number(got.headers.get('x-frame-at')));
});
test('at most four viewers; the fifth is refused', async () => {
  const f = fixture(), d = await enroll(f);
  for (let i = 0; i < SCREEN_RULES.viewers_max; i++) assert.equal((await viewerLink(f, d)).status, 101);
  assert.equal((await viewerLink(f, d)).status, 429);
});
test('commands from a viewer reach the phone parsed, and the phone answer comes back to that viewer', async () => {
  const f = fixture(), d = await enroll(f);
  const p = await phoneLink(f, d), v = await viewerLink(f, d), other = await viewerLink(f, d);
  await fromViewer(f, v, { t: 'cmd', id: 'c1', line: 'tap 0.5 0.25' });
  const cmd = p.server.last('cmd');
  assert.equal(cmd.kind, 'tap'); assert.equal(cmd.x, 0.5); assert.equal(cmd.y, 0.25); assert.equal(cmd.unit, 'frac'); assert.ok(cmd.id);
  await fromPhone(f, p, { t: 'result', id: cmd.id, ok: true, text: 'Apăsat.' });
  assert.deepEqual(v.server.last('result'), { t: 'result', id: 'c1', line: 'tap 0.5 0.25', ok: true, text: 'Apăsat.' });
  assert.equal(other.server.last('result'), null, 'another viewer does not get the answer');
  await fromViewer(f, v, { t: 'cmd', id: 'c2', line: 'dance' });
  assert.equal(v.server.last('result').ok, false); assert.match(v.server.last('result').text, /necunoscută/); assert.equal(p.server.json().filter(m => m.t === 'cmd').length, 1, 'an invalid line never reaches the phone');
  await fromViewer(f, v, { t: 'cmd', id: 'c3', line: 'apps' });
  const c3 = p.server.last('cmd');
  await fromPhone(f, p, { t: 'result', id: c3.id, ok: true, text: '2 aplicații', data: { apps: [{ label: 'YouTube', pkg: 'com.google.android.youtube' }] } });
  assert.equal(v.server.last('result').data.apps[0].label, 'YouTube');
  await fromPhone(f, p, { t: 'result', id: 'unknown', ok: true, text: 'late' });
  assert.equal(v.server.json().filter(m => m.t === 'result').length, 3, 'an unknown result id is ignored');
  await fromViewer(f, v, { t: 'ping' });
  assert.equal(v.server.last('pong').t, 'pong');
  await fromViewer(f, v, { t: 'nope' });
  assert.match(v.server.last('error').message, /necunoscut/);
});
test('a command while the phone is away answers at once and calls the phone', async () => {
  const f = fixture(), d = await enroll(f), v = await viewerLink(f, d);
  await fromViewer(f, v, { t: 'cmd', id: 'c1', line: 'read' });
  const r = v.server.last('result');
  assert.equal(r.ok, false); assert.match(r.text, /nu e conectat/); assert.ok(r.requested_until > Date.now());
  const http = await f.call(`/v2/screen/devices/${d.id}/command`, 'POST', { line: 'read' });
  assert.equal(http.status, 409); assert.equal(http.phone, 'waiting'); assert.ok(http.requested_until > Date.now());
  assert.equal((await beat(f, d)).screen.wanted, true);
});
test('HTTP commands wait for the phone answer (and time out at 20 s)', async t => {
  const f = fixture(), d = await enroll(f), p = await phoneLink(f, d);
  const pending = f.call(`/v2/screen/devices/${d.id}/command`, 'POST', { line: 'say ce grad am' });
  await new Promise(r => setTimeout(r, 5));
  const cmd = p.server.last('cmd');
  assert.equal(cmd.kind, 'say'); assert.equal(cmd.text, 'ce grad am');
  await fromPhone(f, p, { t: 'result', id: cmd.id, ok: true, text: 'Ești Caporal.' });
  const r = await pending;
  assert.equal(r.status, 200); assert.equal(r.ok, true); assert.equal(r.text, 'Ești Caporal.'); assert.equal(r.line, 'say ce grad am');
  assert.equal((await f.call(`/v2/screen/devices/${d.id}/command`, 'POST', { line: '' })).status, 400);
  assert.equal((await f.call(`/v2/screen/devices/${d.id}/command`, 'POST', { nope: 1 })).status, 400);
  assert.equal((await f.call(`/v2/screen/devices/${d.id}/command`, 'GET')).status, 405);
  assert.equal((await f.call(`/v2/screen/devices/${d.id}/command`, 'POST', { line: 'read' }, 'bob')).status, 403);
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const slow = f.call(`/v2/screen/devices/${d.id}/command`, 'POST', { line: 'read' });
  // Comanda ajunge la telefon după câteva rânduri de await-uri (storage, JSON): așteptăm cu setImmediate (nemocat).
  for (let i = 0; i < 200 && p.server.json().filter(m => m.t === 'cmd').length < 2; i++) await new Promise(r => setImmediate(r));
  assert.equal(p.server.json().filter(m => m.t === 'cmd').length, 2);
  t.mock.timers.tick(SCREEN_RULES.command_timeout_ms + 1);
  const late = await slow;
  assert.equal(late.status, 504); assert.equal(late.ok, false); assert.match(late.text, /nu a răspuns/);
  t.mock.timers.reset();
});
test('the phone leaving mid-command fails the command; with viewers present it is called back', async () => {
  const f = fixture(), d = await enroll(f), p = await phoneLink(f, d), v = await viewerLink(f, d);
  await fromViewer(f, v, { t: 'cmd', id: 'c1', line: 'read' });
  await closeLink(f, p);
  assert.equal(v.server.last('result').ok, false); assert.match(v.server.last('result').text, /deconectat/);
  assert.equal(v.server.last('state').phone, 'waiting', 'a request is placed again for the beat');
  assert.ok(await f.storage.get('screen:request:' + d.id));
});
test('DELETE session ends the phone link, closes the viewers and drops the request and the frame', async () => {
  const f = fixture(), d = await enroll(f), p = await phoneLink(f, d), v = await viewerLink(f, d);
  await fromPhone(f, p, jpeg(3));
  assert.equal((await f.call(`/v2/screen/devices/${d.id}/session`, 'DELETE')).status, 200);
  assert.equal(p.server.last('end').reason, 'site'); assert.deepEqual(p.server.closed, { code: 4000, reason: 'end' });
  assert.equal(v.server.last('state').phone, 'off'); assert.deepEqual(v.server.closed, { code: 4000, reason: 'end' });
  assert.equal((await f.call(`/v2/screen/devices/${d.id}/frame`)).status, 404);
  assert.equal(await f.storage.get('screen:request:' + d.id), undefined);
  assert.equal((await f.call(`/v2/screen/devices/${d.id}/session`, 'GET')).status, 405);
  assert.equal((await f.call(`/v2/screen/devices/${d.id}/nope`)).status, 404);
  assert.equal((await f.call(`/v2/screen/devices/${d.id}/frame`, 'GET', undefined, 'bob')).status, 403);
});
test('the worker reads the bearer token from Sec-WebSocket-Protocol on upgrades and routes /v2/screen to the account', async () => {
  const src = await readFile(new URL('./insights-worker.mjs', import.meta.url), 'utf8');
  assert.match(src, /sec-websocket-protocol/); assert.match(src, /bearer\./); assert.match(src, /version:20, mirror:1, screen_mirror:1/);
  assert.match(src, /path === '\/v2\/screen\/devices' \|\| path\.startsWith\('\/v2\/screen\/devices\/'\)/);
  const verify = await readFile(new URL('./verify-live.mjs', import.meta.url), 'utf8');
  assert.match(verify, /screen_mirror: 1/); assert.match(verify, /h\.version !== 20/); assert.match(verify, /site-ecran\.js\.txt/);
});
