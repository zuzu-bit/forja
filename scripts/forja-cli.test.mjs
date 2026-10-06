// FORJA 5.1 — terminalul `forja`, fără rețea și fără telefon: configurarea, intrarea în cont (Firebase mocat), alegerea
// telefonului, comenzile pe HTTP (409 „l-am chemat”, 504), captura pe WebSocket (fals), formatarea, argumentele.
import test from 'node:test';
import assert from 'node:assert/strict';
import { run, parseArgs, pickDevice, formatDevices, formatState, formatResult, configPath, viewerShot, Site, Firebase, Config, CliError, PHONE_VERBS } from './forja-cli-lib.mjs';

const DEVICE = '0d2f1b7e-5d3b-4c3a-9a2a-7c1e9d8f6a11';
const b64 = v => Buffer.from(JSON.stringify(v)).toString('base64url');
const jwt = (uid, email) => [b64({ alg: 'RS256' }), b64({ sub: uid, email }), 'sig'].join('.');
class MemFs {
  files = new Map();
  readFileSync(f) { if (!this.files.has(f)) { const e = new Error('ENOENT'); e.code = 'ENOENT'; throw e; } return this.files.get(f); }
  writeFileSync(f, data) { this.files.set(f, typeof data === 'string' ? data : Buffer.from(data)); }
  mkdirSync() { } chmodSync() { }
}
class FakeWebSocket {
  static instances = [];
  constructor(url, protocols) { this.url = url; this.protocols = protocols; this.listeners = {}; this.sent = []; this.closed = null; FakeWebSocket.instances.push(this); }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
  emit(type, ev = {}) { for (const fn of this.listeners[type] || []) fn(ev); }
  send(d) { this.sent.push(JSON.parse(d)); }
  close(code, reason) { this.closed = { code, reason }; }
}
const CONF = '/home/lana/.config/forja/cli.json';
/** Serverele false: Firebase + site-ul, cu telefonul „live” sau nu. */
function world({ phone = 'live', devices } = {}) {
  const calls = [];
  const list = devices || [{ id: DEVICE, name: 'Galaxy S23', online: true, seen_at: Date.now() - 20000, status: 'ready', capability: { supported: true, enabled: true, android: 14 }, screen: { phone, viewers: 0, width: 1080, height: 2340, fg: 'com.forja.app.research', battery: 64, since: null, requested_until: null, frame_at: null } }];
  const fetch = async (url, opts = {}) => {
    const u = new URL(url), body = opts.body ? (typeof opts.body === 'string' && opts.body.startsWith('{') ? JSON.parse(opts.body) : opts.body) : null;
    calls.push({ url: u.pathname, method: opts.method || 'GET', headers: opts.headers || {}, body });
    const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } });
    if (u.hostname === 'identitytoolkit.googleapis.com') {
      if (body.password !== 'parola-buna') return json({ error: { message: 'INVALID_LOGIN_CREDENTIALS' } }, 400);
      return json({ idToken: jwt('uidA', body.email), refreshToken: 'refresh-1', localId: 'uidA', email: body.email, expiresIn: '3600' });
    }
    if (u.hostname === 'securetoken.googleapis.com') {
      if (new URLSearchParams(body).get('refresh_token') === 'dead') return json({ error: { message: 'TOKEN_EXPIRED' } }, 400);
      return json({ id_token: jwt('uidA', 'lana@example.test'), refresh_token: 'refresh-2', user_id: 'uidA', expires_in: '3600' });
    }
    if (u.pathname === '/health') return json({ ok: true, service: 'forja-insights', version: 20, screen_mirror: 1 });
    if (u.pathname === '/') return json({ ok: true, service: 'forja-api', meals: 'gemini', audio: 'whisper' });
    if (u.pathname === '/admin/api/cmd') return opts.headers['X-Admin'] === 'adminkey' ? json({ ok: true, out: 'serviciu: online\n' + body }) : json({ error: 'Cheie de admin greșită.' }, 403);
    if (!String(opts.headers?.Authorization || '').startsWith('Bearer ')) return json({ error: 'Conectează-te cu contul FORJA.' }, 401);
    if (u.pathname === '/v2/screen/devices') return json({ devices: list, rules: {}, help: [] });
    if (u.pathname === `/v2/screen/devices/${DEVICE}/command`) {
      if (phone !== 'live') return json({ error: 'Telefonul nu e conectat.', phone: 'waiting', requested_until: Date.now() + 600000 }, 409);
      if (body.line === 'read') return json({ ok: true, text: 'Azi · Casca · Caporal', line: 'read' });
      if (body.line === 'apps') return json({ ok: true, text: '2 aplicații', data: { apps: [{ label: 'YouTube', pkg: 'com.google.android.youtube' }, { label: 'FORJA', pkg: 'com.forja.app.research' }] }, line: 'apps' });
      if (body.line === 'say nimic') return json({ ok: false, timeout: true, text: 'Telefonul nu a răspuns în 20 s.', line: body.line }, 504);
      if (body.line === 'dance') return json({ error: 'Comandă necunoscută: „dance”. Scrie „help”.' }, 400);
      return json({ ok: true, text: 'Apăsat.', line: body.line });
    }
    if (u.pathname === `/v2/screen/devices/${DEVICE}/session` && opts.method === 'DELETE') return json({ ok: true });
    return json({ error: 'Not found' }, 404);
  };
  return { calls, fetch, list };
}
function io(w, { config = { refresh: 'refresh-1', uid: 'uidA', email: 'lana@example.test' }, answers = {}, linesIn = [] } = {}) {
  const fs = new MemFs(), out = [], err = [];
  if (config) fs.writeFileSync(CONF, JSON.stringify(config));
  FakeWebSocket.instances = [];
  return { out, err, fs, io: { env: {}, home: '/home/lana', fs, fetch: w.fetch, WebSocket: FakeWebSocket, stdout: s => out.push(s), stderr: s => err.push(s),
    ask: async () => answers.email || '', secret: async () => answers.password || '', lines: async function* () { for (const l of linesIn) yield l; },
    sleep: async () => { }, now: () => 1_700_000_000_000, signal: new AbortController().signal } };
}
const saved = fs => JSON.parse(fs.readFileSync(CONF));

test('parseArgs and configPath', () => {
  assert.deepEqual(parseArgs(['tap', '0.5', '0.3']), { command: 'tap', args: ['0.5', '0.3'], flags: {} });
  assert.deepEqual(parseArgs(['watch', '--dir', 'out', '--seconds=5', '--json']), { command: 'watch', args: [], flags: { dir: 'out', seconds: '5', json: true } });
  assert.deepEqual(parseArgs(['--device', 'Galaxy', 'read']), { command: 'read', args: [], flags: { device: 'Galaxy' } });
  assert.equal(configPath({ XDG_CONFIG_HOME: '/x' }, '/home/l'), '/x/forja/cli.json');
  assert.equal(configPath({}, '/home/l'), '/home/l/.config/forja/cli.json');
  assert.equal(configPath({ FORJA_CLI_CONFIG: '/tmp/c.json' }, '/home/l'), '/tmp/c.json');
  assert.ok(PHONE_VERBS.includes('apasă') && PHONE_VERBS.includes('say'));
});
test('help prints the usage without touching the network', async () => {
  const w = world(), { io: i, out } = io(w, { config: null });
  assert.equal(await run([], i), 0); assert.match(out.join(''), /forja shot/); assert.equal(w.calls.length, 0);
  assert.equal(await run(['--help'], i), 0);
});
test('login keeps the refresh token, uid and email only; a wrong password is a Romanian message; logout forgets', async () => {
  const w = world(), { io: i, out, fs } = io(w, { config: null, answers: { password: 'parola-buna' } });
  assert.equal(await run(['login', 'lana@example.test'], i), 0);
  assert.deepEqual(Object.keys(saved(fs)).sort(), ['email', 'refresh', 'uid']); assert.equal(saved(fs).refresh, 'refresh-1');
  assert.match(out.join(''), /Conectat ca lana@example.test/);
  const bad = io(w, { config: null, answers: { password: 'alta' } });
  assert.equal(await run(['login', 'lana@example.test'], bad.io), 1); assert.match(bad.err.join(''), /Email sau parolă greșite/);
  const noEmail = io(w, { config: null });
  assert.equal(await run(['login'], noEmail.io), 1); assert.match(noEmail.err.join(''), /adresa de email/);
  assert.equal(await run(['logout'], i), 0); assert.equal(saved(fs).refresh, undefined);
});
test('phone commands without a login say so (exit 2); an expired refresh token too', async () => {
  const w = world(), none = io(w, { config: null });
  assert.equal(await run(['devices'], none.io), 2); assert.match(none.err.join(''), /forja login/);
  const dead = io(w, { config: { refresh: 'dead', uid: 'uidA' } });
  assert.equal(await run(['devices'], dead.io), 2); assert.match(dead.err.join(''), /expirat/);
});
test('devices lists the phones with a Bearer token and keeps the rotated refresh token', async () => {
  const w = world(), { io: i, out, fs } = io(w);
  assert.equal(await run(['devices'], i), 0);
  const text = out.join('');
  assert.match(text, /Galaxy S23/); assert.match(text, /ÎN GARDĂ/); assert.match(text, /LIVE/); assert.match(text, /ecran: pornit în Profil/);
  assert.equal(saved(fs).refresh, 'refresh-2');
  assert.ok(w.calls.find(c => c.url === '/v2/screen/devices').headers.Authorization.startsWith('Bearer '));
  const j = io(w); assert.equal(await run(['devices', '--json'], j.io), 0); assert.equal(JSON.parse(j.out.join('')).devices[0].id, DEVICE);
});
test('pickDevice: id, id prefix, name without diacritics, or the only phone', () => {
  const list = [{ id: DEVICE, name: 'Galaxy S23' }, { id: 'ffffffff-1111-4222-8333-444444444444', name: 'Telefonul Lanei' }];
  assert.equal(pickDevice(list, DEVICE).name, 'Galaxy S23');
  assert.equal(pickDevice(list, '0d2f1b7e').name, 'Galaxy S23');
  assert.equal(pickDevice(list, 'telefonul lanei').id, list[1].id);
  assert.equal(pickDevice(list, 'lanei').id, list[1].id);
  assert.equal(pickDevice([list[0]], '').name, 'Galaxy S23');
  assert.throws(() => pickDevice(list, ''), /Mai multe telefoane/);
  assert.throws(() => pickDevice(list, 'pixel'), /Nu găsesc/);
  assert.throws(() => pickDevice([], ''), /Niciun telefon/);
});
test('use remembers the default phone; --device overrides it', async () => {
  const w = world(), { io: i, fs } = io(w);
  assert.equal(await run(['use', 'galaxy'], i), 0); assert.equal(saved(fs).device, DEVICE);
  const bad = io(w); assert.equal(await run(['use', 'pixel'], bad.io), 1);
});
test('a command runs through POST command and prints the answer; exit codes for 409, 504 and 400', async () => {
  const w = world(), { io: i, out } = io(w);
  assert.equal(await run(['tap', '0.5', '0.3'], i), 0);
  assert.deepEqual(w.calls.at(-1).body, { line: 'tap 0.5 0.3' }); assert.match(out.join(''), /✓ Apăsat/);
  const r = io(w); assert.equal(await run(['read'], r.io), 0); assert.match(r.out.join(''), /Caporal/);
  const a = io(w); assert.equal(await run(['apps'], a.io), 0); assert.match(a.out.join(''), /YouTube\s+com\.google\.android\.youtube/);
  const j = io(w); assert.equal(await run(['read', '--json'], j.io), 0); assert.equal(JSON.parse(j.out.join('')).text, 'Azi · Casca · Caporal');
  const ro = io(w); assert.equal(await run(['apasă', '50%', '30%'], ro.io), 0); assert.deepEqual(w.calls.at(-1).body, { line: 'apasă 50% 30%' });
  const run2 = io(w); assert.equal(await run(['run', 'key', 'back'], run2.io), 0); assert.deepEqual(w.calls.at(-1).body, { line: 'key back' });
  const slow = io(w); assert.equal(await run(['say', 'nimic'], slow.io), 4); assert.match(slow.err.join(''), /nu a răspuns/);
  const unknown = io(w); assert.equal(await run(['dance'], unknown.io), 1); assert.match(unknown.err.join(''), /Comandă necunoscută/);
  const bad = io(w); assert.equal(await run(['run', 'dance'], bad.io), 1); assert.match(bad.err.join(''), /Scrie „help”/);
  const away = io(world({ phone: 'waiting' })); assert.equal(await run(['read'], away.io), 3); assert.match(away.err.join(''), /l-am chemat/i);
});
test('adb-style lines go to the server as they are; exec-out screencap writes the frame to stdout', async () => {
  const w = world(), s = io(w);
  assert.equal(await run(['shell', 'input', 'tap', '540', '1200'], s.io), 0); assert.deepEqual(w.calls.at(-1).body, { line: 'shell input tap 540 1200' });
  const a = io(w); assert.equal(await run(['adb', 'shell', 'input', 'keyevent', 'KEYCODE_BACK'], a.io), 0); assert.deepEqual(w.calls.at(-1).body, { line: 'adb shell input keyevent KEYCODE_BACK' });
  const pm = io(w); assert.equal(await run(['pm', 'list', 'packages'], pm.io), 0); assert.deepEqual(w.calls.at(-1).body, { line: 'pm list packages' });
  const bytes = []; const x = io(w); x.io.stdoutBytes = b => bytes.push(Buffer.from(b));
  const p = run(['exec-out', 'screencap', '-p'], x.io);
  for (let k = 0; k < 50 && !FakeWebSocket.instances.length; k++) await new Promise(r => setImmediate(r));
  const ws = FakeWebSocket.instances[0]; ws.emit('open'); ws.emit('message', { data: new Uint8Array([0xff, 0xd8, 9, 0xff, 0xd9]).buffer });
  assert.equal(await p, 0); assert.equal(bytes[0].length, 5); assert.equal(x.out.length, 0, 'nothing else on stdout');
  const bad = io(w); assert.equal(await run(['exec-out', 'logcat'], bad.io), 1);
});
test('end, health, whoami, admin', async () => {
  const w = world(), e = io(w);
  assert.equal(await run(['end'], e.io), 0); assert.equal(w.calls.at(-1).method, 'DELETE'); assert.match(e.out.join(''), /s-a închis/);
  const h = io(w); assert.equal(await run(['health'], h.io), 0); assert.match(h.out.join(''), /versiunea 20/); assert.match(h.out.join(''), /ecranul pe site: da/); assert.match(h.out.join(''), /server: online/);
  const who = io(w); assert.equal(await run(['whoami'], who.io), 0); assert.match(who.out.join(''), /lana@example.test/);
  const noKey = io(w); assert.equal(await run(['admin', 'status'], noKey.io), 1); assert.match(noKey.err.join(''), /admin-key/);
  const k = io(w); assert.equal(await run(['admin-key', 'adminkey'], k.io), 0);
  assert.equal(await run(['admin', 'log', '50'], k.io), 0); assert.match(k.out.join(''), /serviciu: online\nlog 50/);
  const env = io(w); env.io.env.FORJA_ADMIN_KEY = 'adminkey'; assert.equal(await run(['admin'], env.io), 0); assert.equal(w.calls.at(-1).body, 'help');
});
test('shot opens the viewer socket with the token in the subprotocol, asks for a frame and saves the first one', async () => {
  const w = world(), { io: i, out, fs } = io(w);
  const p = run(['shot', 'out.jpg'], i);
  for (let k = 0; k < 50 && !FakeWebSocket.instances.length; k++) await new Promise(r => setImmediate(r));
  const ws = FakeWebSocket.instances[0];
  assert.equal(ws.url, `wss://forja-insights.forja-22e7ea2d.workers.dev/v2/screen/devices/${DEVICE}/socket`);
  assert.equal(ws.protocols[0], 'forja'); assert.ok(ws.protocols[1].startsWith('bearer.'));
  ws.emit('open');
  assert.deepEqual(ws.sent[0], { t: 'cmd', id: 'shot', line: 'shot' });
  ws.emit('message', { data: JSON.stringify({ t: 'state', phone: 'live', width: 1080, height: 2340, fg: 'com.forja.app.research', viewers: 1 }) });
  ws.emit('message', { data: new Uint8Array([0xff, 0xd8, 1, 2, 3, 0xff, 0xd9]).buffer });
  assert.equal(await p, 0);
  assert.equal(fs.readFileSync('out.jpg').length, 7); assert.match(out.join(''), /out\.jpg/); assert.deepEqual(ws.closed, { code: 1000, reason: 'bye' });
});
test('shot fails cleanly when the phone refuses or the link closes', async () => {
  const w = world(), a = io(w);
  const p = run(['shot', 'x.jpg'], a.io);
  for (let k = 0; k < 50 && !FakeWebSocket.instances.length; k++) await new Promise(r => setImmediate(r));
  const ws = FakeWebSocket.instances[0]; ws.emit('open');
  ws.emit('message', { data: JSON.stringify({ t: 'result', id: 'shot', ok: false, text: 'Ecranul e oprit în Profil.' }) });
  assert.equal(await p, 3); assert.match(a.err.join(''), /oprit în Profil/);
  const b = io(w);
  const q = run(['shot', 'y.jpg'], b.io);
  for (let k = 0; k < 50 && !FakeWebSocket.instances.length; k++) await new Promise(r => setImmediate(r));
  FakeWebSocket.instances[0].emit('close', { code: 4000, reason: 'end' });
  assert.equal(await q, 1); assert.match(b.err.join(''), /s-a închis/);
});
test('sh: lines become commands on one socket, shot saves a frame, exit leaves', async () => {
  const w = world(), s = io(w, { linesIn: ['read', 'shot', 'wait 1', 'exit'] });
  const p = run(['sh'], s.io);
  for (let k = 0; k < 50 && !FakeWebSocket.instances.length; k++) await new Promise(r => setImmediate(r));
  const ws = FakeWebSocket.instances[0]; ws.emit('open');
  for (let k = 0; k < 50 && ws.sent.length < 1; k++) await new Promise(r => setImmediate(r));
  assert.equal(ws.sent[0].line, 'read');
  ws.emit('message', { data: JSON.stringify({ t: 'result', id: ws.sent[0].id, ok: true, text: 'Azi' }) });
  for (let k = 0; k < 50 && ws.sent.length < 2; k++) await new Promise(r => setImmediate(r));
  assert.equal(ws.sent[1].line, 'shot');
  ws.emit('message', { data: new Uint8Array([1, 2, 3]).buffer });
  assert.equal(await p, 0);
  assert.match(s.out.join(''), /✓ Azi/); assert.match(s.out.join(''), /forja-.*\.jpg/); assert.ok([...s.fs.files.keys()].some(f => f.endsWith('.jpg')));
});
test('formatting helpers', () => {
  assert.equal(formatDevices([]), 'Niciun telefon în gardă.');
  assert.match(formatDevices([{ id: 'x', name: 'P', online: false, seen_at: Date.now() - 3600000, screen: { phone: 'off', viewers: 0 }, capability: { supported: false } }]), /TĂCUT\s+acum 1 h.*OPRIT.*Android < 11/);
  assert.equal(formatState({ phone: 'live', width: 1080, height: 2340, fg: 'a.b', battery: 50, viewers: 2 }), 'LIVE · 1080×2340 · a.b · 50% · 2 privitori');
  assert.match(formatState({ phone: 'waiting', requested_until: Date.now() + 60000 }), /AȘTEAPTĂ TELEFONUL · aștept până/);
  assert.equal(formatResult({ ok: true, text: 'x' }), '✓ x'); assert.equal(formatResult({ ok: false }), '✗ nu a mers');
  assert.match(formatResult({ ok: true, text: 'i', data: { info: { model: 'S23', android: 14 } } }), /model\s+S23/);
});
test('Site renews the token once on a 401 and the Firebase client maps errors', async () => {
  let n = 0;
  const fetch = async (url) => { if (String(url).includes('securetoken')) return new Response(JSON.stringify({ id_token: jwt('u', 'e'), refresh_token: 'r', user_id: 'u', expires_in: '3600' }), { status: 200, headers: { 'content-type': 'application/json' } }); n++; return new Response(JSON.stringify(n === 1 ? { error: 'x' } : { ok: true }), { status: n === 1 ? 401 : 200, headers: { 'content-type': 'application/json' } }); };
  const fs = new MemFs(); fs.writeFileSync('/c.json', JSON.stringify({ refresh: 'r', uid: 'u' }));
  const site = new Site({ fetch, WebSocket: FakeWebSocket, firebase: new Firebase({ fetch }), config: new Config({ file: '/c.json', fs }) });
  assert.deepEqual(await site.request('/x'), { ok: true }); assert.equal(n, 2);
  await assert.rejects(new Firebase({ fetch: async () => new Response(JSON.stringify({ error: { message: 'TOO_MANY_ATTEMPTS_TRY_LATER' } }), { status: 400 }) }).signIn('a', 'b'), /Prea multe/);
  assert.ok(new CliError('x', 3).code === 3);
  assert.equal(typeof viewerShot, 'function');
});
