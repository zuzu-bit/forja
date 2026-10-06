// FORJA 5.1 — biblioteca terminalului `forja` (scripts/forja-cli.mjs): tot ce se poate testa fără rețea și fără telefon.
// Vorbește cu site-ul (forja-insights) exact ca pagina: tokenul Firebase pe HTTP (Authorization) și, pe WebSocket,
// în Sec-WebSocket-Protocol („forja, bearer.<token>”). Comenzile pentru telefon au gramatica din server/screen-mirror.mjs
// (parserul e pe server: terminalul trimite rândul, serverul răspunde cu folosirea când e greșit).
import path from 'node:path';

export const DEFAULTS = Object.freeze({
  site: 'https://forja-insights.forja-22e7ea2d.workers.dev',
  api: 'https://forja-api.forja-22e7ea2d.workers.dev',
  // Cheia web a proiectului Firebase forja-65093 (aceeași din aplicație și din pagina site-ului; e publică prin natura ei).
  apiKey: 'AIzaSyDjZX8AtW9GQUnqA9UtPaVD8dAlw4dOKZI',
  project: 'forja-65093',
});
export const PHONE_VERBS = Object.freeze(['tap', 'swipe', 'key', 'type', 'open', 'say', 'read', 'apps', 'shot', 'scroll', 'info', 'back', 'home', 'recents',
  'shell', 'adb', 'input', 'am', 'pm', 'monkey', 'dumpsys', 'wm', 'getprop', 'uiautomator',
  'apasa', 'apasă', 'atinge', 'click', 'trage', 'gliseaza', 'glisează', 'tasta', 'tastă', 'scrie', 'deschide', 'spune', 'zi', 'citeste', 'citește', 'aplicatii', 'aplicații', 'poza', 'poză', 'captura', 'captură', 'deruleaza', 'derulează', 'stare', 'inapoi', 'înapoi', 'acasa', 'acasă', 'recente']);
export const SHOT_TIMEOUT_MS = 30000, CONNECT_TIMEOUT_MS = 15000;

export const HELP = `forja — telefonul tău, din terminal (FORJA 5.1)

Cont
  forja login [email]        intră cu contul FORJA (parola se cere, nu se păstrează; rămâne tokenul de reîmprospătare)
  forja logout               uită contul din acest calculator
  forja whoami               contul și site-ul folosite
  forja health               starea site-ului și a serverului FORJA

Telefoane
  forja devices              telefoanele contului (în gardă / tăcut, ecranul: live / waiting / off)
  forja use <nume|id>        telefonul implicit pentru comenzile de mai jos

Ecranul (cere: FORJA ≥ 5.1, Android 11+, serviciul FORJA din Accesibilitate pornit, „Ecranul pe site” pornit în Profil → Telefonul meu)
  forja shot [fișier.jpg]    un cadru al ecranului (implicit ./forja-<ora>.jpg)
  forja watch [--dir D] [--seconds N]   cadre pe viu, salvate în D (frame-0001.jpg …), cu starea telefonului
  forja sh                   consolă interactivă: scrii comenzi, vezi răspunsurile, „shot” salvează cadrul
  forja end                  închide legătura ecranului (telefonul se oprește din trimis)

Comenzi pentru telefon (prin aplicație; și din „forja sh”)
  forja tap <x> <y>          atinge (fracții 0–1, procente 50% sau pixeli)
  forja swipe <x1> <y1> <x2> <y2> [ms]
  forja key back|home|recents|notifications|settings|lock   (merg și: forja back / forja home)
  forja type <text>          scrie în câmpul focalizat
  forja open <aplicație>     deschide o aplicație (nume sau pachet)
  forja say <comandă>        o comandă „Hei FORJA” (say pornește muzica · say ce grad am)
  forja read                 textul de pe ecran
  forja apps                 aplicațiile instalate
  forja scroll up|down       derulează
  forja info                 model, Android, ecran, aplicația din față, baterie
  Toate merg și în română: apasă, trage, tastă, scrie, deschide, spune, citește, aplicații, poză, derulează, stare.

Ca la adb (pentru programatori și agenți; „adb” și „shell” se pot omite; coordonatele sunt pixeli)
  forja shell input tap 540 1200 · input swipe 540 1800 540 600 300 · input text "salut" · input keyevent KEYCODE_BACK
  forja shell am start -n pachet/.Activitate · monkey -p pachet 1 · pm list packages · dumpsys · wm size · uiautomator dump
  forja exec-out screencap -p > ecran.jpg     (cadrul e JPEG, nu PNG; „forja screencap ecran.jpg” îl scrie direct în fișier)

Serverul FORJA (forja-api, terminalul de admin)
  forja admin <comandă…>     ex. forja admin status · forja admin log 50 · forja admin media ls   (cheia: forja admin-key <cheie> sau FORJA_ADMIN_KEY)

Opțiuni: --site URL · --api URL · --device <id|nume> · --json (răspunsuri brute)
Configurare: ~/.config/forja/cli.json (sau FORJA_CLI_CONFIG); variabile: FORJA_SITE, FORJA_API, FORJA_ADMIN_KEY`;

export function configPath(env = {}, home = '') {
  if (env.FORJA_CLI_CONFIG) return env.FORJA_CLI_CONFIG;
  const base = env.XDG_CONFIG_HOME || path.join(home || env.HOME || env.USERPROFILE || '.', '.config');
  return path.join(base, 'forja', 'cli.json');
}
/** Fișierul de configurare: citit la fiecare pornire, scris doar la login/use/admin-key. Fără parolă, niciodată. */
export class Config {
  constructor({ file, fs }) { this.file = file; this.fs = fs; this.data = this.load(); }
  load() {
    try { const v = JSON.parse(this.fs.readFileSync(this.file, 'utf8')); return v && typeof v === 'object' ? v : {}; }
    catch { return {}; }
  }
  save(patch) {
    this.data = { ...this.data, ...patch };
    for (const k of Object.keys(this.data)) if (this.data[k] === undefined || this.data[k] === null) delete this.data[k];
    this.fs.mkdirSync(path.dirname(this.file), { recursive: true });
    this.fs.writeFileSync(this.file, JSON.stringify(this.data, null, 2) + '\n', { mode: 0o600 });
    try { this.fs.chmodSync(this.file, 0o600); } catch { /* Windows */ }
  }
}

export class CliError extends Error { constructor(message, code = 1) { super(message); this.code = code; } }

/** Logarea Firebase prin REST (ca pagina site-ului): parolă → token + refresh; refresh → token nou. */
export class Firebase {
  constructor({ apiKey = DEFAULTS.apiKey, fetch }) { this.apiKey = apiKey; this.fetch = fetch; }
  async signIn(email, password) {
    const r = await this.fetch('https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=' + this.apiKey, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email, password, returnSecureToken: true }) });
    const data = await json(r);
    if (!r.ok) {
      const code = String(data?.error?.message || '');
      if (/INVALID_LOGIN_CREDENTIALS|EMAIL_NOT_FOUND|INVALID_PASSWORD|INVALID_EMAIL/.test(code)) throw new CliError('Email sau parolă greșite.');
      if (/TOO_MANY_ATTEMPTS/.test(code)) throw new CliError('Prea multe încercări. Mai încearcă peste câteva minute.');
      throw new CliError('Conectarea nu a reușit (' + (code || r.status) + ').');
    }
    return { token: data.idToken, refresh: data.refreshToken, uid: data.localId, email: data.email || email, expires: Date.now() + (Number(data.expiresIn) - 60) * 1000 };
  }
  async renew(refresh) {
    const r = await this.fetch('https://securetoken.googleapis.com/v1/token?key=' + this.apiKey, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: refresh }).toString() });
    const data = await json(r);
    if (!r.ok) throw new CliError('Sesiunea a expirat. Rulează din nou: forja login', 2);
    return { token: data.id_token, refresh: data.refresh_token || refresh, uid: data.user_id, expires: Date.now() + (Number(data.expires_in) - 60) * 1000 };
  }
}
async function json(r) { try { return await r.json(); } catch { return null; } }

/** Clientul site-ului: JSON cu token, reîmprospătat o dată la 401; WebSocket-ul privitorului cu tokenul în subprotocol. */
export class Site {
  constructor({ base = DEFAULTS.site, fetch, WebSocket, firebase, config, now = () => Date.now() }) {
    this.base = base.replace(/\/+$/, ''); this.fetch = fetch; this.WebSocket = WebSocket; this.firebase = firebase; this.config = config; this.now = now; this.cached = null;
  }
  async token(force = false) {
    if (!force && this.cached && this.now() < this.cached.expires) return this.cached.token;
    const refresh = this.config.data.refresh;
    if (!refresh) throw new CliError('Nu ești conectat. Rulează: forja login', 2);
    const t = await this.firebase.renew(refresh);
    this.cached = t;
    if (t.refresh !== refresh || t.uid !== this.config.data.uid) this.config.save({ refresh: t.refresh, uid: t.uid });
    return t.token;
  }
  async request(p, { method = 'GET', body, raw = false, retry = true } = {}) {
    const send = async token => this.fetch(this.base + p, { method, headers: { Authorization: 'Bearer ' + token, ...(body !== undefined ? { 'content-type': 'application/json' } : {}) }, ...(body !== undefined ? { body: JSON.stringify(body) } : {}) });
    let r = await send(await this.token());
    if (r.status === 401 && retry) r = await send(await this.token(true));
    if (raw) return r;
    const data = await json(r);
    if (!r.ok) { const e = new CliError(data?.error || ('Site-ul a răspuns cu ' + r.status), r.status === 401 ? 2 : 1); e.status = r.status; e.data = data; throw e; }
    return data;
  }
  devices() { return this.request('/v2/screen/devices'); }
  command(deviceId, line) { return this.request('/v2/screen/devices/' + encodeURIComponent(deviceId) + '/command', { method: 'POST', body: { line } }); }
  end(deviceId) { return this.request('/v2/screen/devices/' + encodeURIComponent(deviceId) + '/session', { method: 'DELETE' }); }
  async health() { const r = await this.fetch(this.base + '/health'); return json(r); }
  /**
   * Legătura privitorului. `on` primește: {type:'state', state} · {type:'frame', bytes:Uint8Array} · {type:'result', result} ·
   * {type:'open'} · {type:'close', code, reason} · {type:'error', message}. Întoarce {send(line, id), close()}.
   */
  async open(deviceId, on) {
    const token = await this.token();
    const url = this.base.replace(/^http/, 'ws') + '/v2/screen/devices/' + encodeURIComponent(deviceId) + '/socket';
    const ws = new this.WebSocket(url, ['forja', 'bearer.' + token]);
    try { ws.binaryType = 'arraybuffer'; } catch { /* implementări fără binaryType */ }
    let seq = 0;
    ws.addEventListener('open', () => on({ type: 'open' }));
    ws.addEventListener('close', e => on({ type: 'close', code: e?.code, reason: e?.reason || '' }));
    ws.addEventListener('error', () => on({ type: 'error', message: 'Legătura cu site-ul a picat.' }));
    ws.addEventListener('message', async e => {
      const d = e?.data;
      if (typeof d === 'string') { let m = null; try { m = JSON.parse(d); } catch { return; } if (m?.t === 'state') on({ type: 'state', state: m }); else if (m?.t === 'result') on({ type: 'result', result: m }); else if (m?.t === 'error') on({ type: 'error', message: m.message || 'eroare' }); return; }
      const bytes = d instanceof ArrayBuffer ? new Uint8Array(d) : ArrayBuffer.isView(d) ? new Uint8Array(d.buffer, d.byteOffset, d.byteLength) : typeof d?.arrayBuffer === 'function' ? new Uint8Array(await d.arrayBuffer()) : null;
      if (bytes) on({ type: 'frame', bytes });
    });
    return { send(line, id = 'c' + (++seq)) { ws.send(JSON.stringify({ t: 'cmd', id, line })); return id; }, close() { try { ws.close(1000, 'bye'); } catch { /* deja închis */ } }, ws };
  }
}

/** Telefonul cerut (id, prefix de id sau nume, fără diacritice/majuscule), altfel singurul sau cel implicit. */
export function pickDevice(devices, wanted) {
  if (!devices.length) throw new CliError('Niciun telefon în gardă. Semnează contractul în FORJA (Profil → Telefonul meu).');
  if (!wanted) { if (devices.length === 1) return devices[0]; throw new CliError('Mai multe telefoane. Alege unul: forja use <nume|id>\n' + devices.map(d => '  ' + d.name + '  ' + d.id).join('\n')); }
  const norm = s => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();
  const w = norm(wanted);
  const hit = devices.find(d => d.id === wanted) || devices.find(d => d.id.startsWith(w) && w.length >= 4) || devices.find(d => norm(d.name) === w) || devices.find(d => norm(d.name).includes(w));
  if (!hit) throw new CliError('Nu găsesc telefonul „' + wanted + '”. Vezi: forja devices');
  return hit;
}

export function ago(at, now = Date.now()) {
  if (!at) return 'niciodată';
  const s = Math.max(0, Math.round((now - at) / 1000));
  if (s < 60) return 'acum ' + s + ' s';
  if (s < 3600) return 'acum ' + Math.round(s / 60) + ' min';
  if (s < 86400) return 'acum ' + Math.round(s / 3600) + ' h';
  return 'acum ' + Math.round(s / 86400) + ' zile';
}
const PHONE_WORDS = { live: 'LIVE', waiting: 'AȘTEAPTĂ TELEFONUL', off: 'OPRIT' };
export function formatDevices(devices, now = Date.now()) {
  if (!devices.length) return 'Niciun telefon în gardă.';
  return devices.map(d => {
    const cap = d.capability ? (d.capability.supported === false ? 'ecran: fără (Android < 11)' : d.capability.enabled === false ? 'ecran: oprit în Profil' : d.capability.enabled ? 'ecran: pornit în Profil' : '') : '';
    return [d.name.padEnd(22), d.online ? 'ÎN GARDĂ' : 'TĂCUT', (d.online ? '' : ago(d.seen_at, now)).padEnd(14), PHONE_WORDS[d.screen?.phone] || '', d.screen?.viewers ? d.screen.viewers + ' privitori' : '', cap, d.id].filter(Boolean).join('  ');
  }).join('\n');
}
export function formatState(s) {
  const bits = [PHONE_WORDS[s.phone] || s.phone];
  if (s.width && s.height) bits.push(s.width + '×' + s.height);
  if (s.fg) bits.push(s.fg);
  if (Number.isInteger(s.battery)) bits.push(s.battery + '%');
  if (s.viewers !== undefined) bits.push(s.viewers + ' privitori');
  if (s.phone === 'waiting' && s.requested_until) bits.push('aștept până ' + new Date(s.requested_until).toLocaleTimeString('ro-RO', { hour: '2-digit', minute: '2-digit' }));
  return bits.join(' · ');
}
export function formatResult(r) {
  const head = (r.ok ? '✓ ' : '✗ ') + (r.text || (r.ok ? 'gata' : 'nu a mers'));
  if (r.data?.apps) return head + '\n' + r.data.apps.map(a => '  ' + String(a.label || '').padEnd(28) + a.pkg).join('\n');
  if (r.data?.info) return head + '\n' + Object.entries(r.data.info).map(([k, v]) => '  ' + k.padEnd(10) + String(v)).join('\n');
  return head;
}
export function frameName(dir, n) { return path.join(dir, 'frame-' + String(n).padStart(4, '0') + '.jpg'); }
export function shotName(now = new Date()) { const p = n => String(n).padStart(2, '0'); return 'forja-' + now.getFullYear() + p(now.getMonth() + 1) + p(now.getDate()) + '-' + p(now.getHours()) + p(now.getMinutes()) + p(now.getSeconds()) + '.jpg'; }

/** argv → {command, args, flags}; flagurile `--x` sau `--x=v` / `--x v` (pentru --site, --api, --device, --dir, --seconds). */
export function parseArgs(argv) {
  const out = { command: '', args: [], flags: {} }, valued = new Set(['site', 'api', 'device', 'dir', 'seconds', 'config']);
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const eq = a.indexOf('=');
      const k = eq < 0 ? a.slice(2) : a.slice(2, eq);
      if (eq >= 0) out.flags[k] = a.slice(eq + 1);
      else if (valued.has(k) && i + 1 < argv.length) out.flags[k] = argv[++i];
      else out.flags[k] = true;
    } else if (!out.command) out.command = a;
    else out.args.push(a);
  }
  return out;
}

/**
 * Rulează terminalul. `io`: {env, home, fs, fetch, WebSocket, stdout(text), stderr(text), ask(question) → string,
 * secret(question) → string, lines() → async iterator de rânduri (pentru sh), sleep(ms), now, signal}. Întoarce codul de ieșire.
 */
export async function run(argv, io) {
  const { command, args, flags } = parseArgs(argv);
  const out = s => io.stdout(s + '\n'), err = s => io.stderr(s + '\n');
  try {
    if (!command || command === 'help' || command === '--help' || command === '-h' || flags.help === true) { out(HELP); return 0; }
    const config = new Config({ file: flags.config || configPath(io.env, io.home), fs: io.fs });
    const siteUrl = flags.site || io.env.FORJA_SITE || config.data.site || DEFAULTS.site;
    const apiUrl = flags.api || io.env.FORJA_API || config.data.api || DEFAULTS.api;
    const firebase = new Firebase({ fetch: io.fetch });
    const site = new Site({ base: siteUrl, fetch: io.fetch, WebSocket: io.WebSocket, firebase, config, now: io.now });
    const device = async () => pickDevice((await site.devices()).devices, flags.device || config.data.device);

    if (command === 'login') {
      const email = args[0] || await io.ask('Email: ');
      if (!email) throw new CliError('Scrie adresa de email a contului.');
      const password = await io.secret('Parolă: ');
      const s = await firebase.signIn(email.trim(), password);
      config.save({ refresh: s.refresh, uid: s.uid, email: s.email, site: flags.site || config.data.site, api: flags.api || config.data.api });
      out('Conectat ca ' + s.email + '. Configurarea: ' + config.file);
      return 0;
    }
    if (command === 'logout') { config.save({ refresh: null, uid: null, email: null }); out('Contul a fost uitat.'); return 0; }
    if (command === 'whoami') { out((config.data.email ? 'Cont: ' + config.data.email + ' (' + config.data.uid + ')' : 'Neconectat.') + '\nSite: ' + siteUrl + '\nServer: ' + apiUrl + (config.data.device ? '\nTelefon implicit: ' + config.data.device : '') + '\nConfigurare: ' + config.file); return 0; }
    if (command === 'config') { out(JSON.stringify({ file: config.file, ...config.data, refresh: config.data.refresh ? '(păstrat)' : undefined, admin: config.data.admin ? '(păstrată)' : undefined }, null, 2)); return 0; }
    if (command === 'health') {
      const h = await site.health();
      out('site: ' + (h ? 'versiunea ' + h.version + (h.screen_mirror ? ' · ecranul pe site: da' : ' · ecranul pe site: NU (publică serverul 5.1)') : 'nu răspunde'));
      try { const r = await io.fetch(apiUrl + '/'); const a = await json(r); out('server: ' + (a?.ok ? 'online · mese: ' + a.meals + ' · audio: ' + a.audio : 'nu răspunde')); } catch { out('server: nu răspunde'); }
      return 0;
    }
    if (command === 'devices') { const d = await site.devices(); out(flags.json ? JSON.stringify(d, null, 2) : formatDevices(d.devices, io.now())); return 0; }
    if (command === 'use') { if (!args[0]) throw new CliError('Folosire: forja use <nume|id>'); const d = pickDevice((await site.devices()).devices, args[0]); config.save({ device: d.id }); out('Telefonul implicit: ' + d.name + ' (' + d.id + ')'); return 0; }
    if (command === 'end') { const d = await device(); await site.end(d.id); out('Legătura ecranului s-a închis pentru ' + d.name + '.'); return 0; }
    if (command === 'admin-key') { if (!args[0]) throw new CliError('Folosire: forja admin-key <cheie>'); config.save({ admin: args[0] }); out('Cheia de admin a fost păstrată.'); return 0; }
    if (command === 'admin') {
      const key = io.env.FORJA_ADMIN_KEY || config.data.admin;
      if (!key) throw new CliError('Lipsește cheia de admin: forja admin-key <cheie> sau FORJA_ADMIN_KEY (id-ul contului Cloudflare, ca în CI).');
      const r = await io.fetch(apiUrl + '/admin/api/cmd', { method: 'POST', headers: { 'X-Admin': key, 'content-type': 'text/plain' }, body: args.join(' ') || 'help' });
      const data = await json(r);
      if (!r.ok) throw new CliError(data?.error || ('Serverul a răspuns cu ' + r.status));
      out(data?.out ?? JSON.stringify(data));
      return 0;
    }
    // „adb exec-out screencap -p > x.jpg”: cadrul (JPEG) pe stdout, ca să poată fi redirecționat.
    if (command === 'exec-out' || (command === 'screencap' && args.includes('-p') && args.filter(a => !a.startsWith('-')).length === 0 && flags.stdout === true)) {
      if (command === 'exec-out' && (args[0] || '') !== 'screencap') throw new CliError('Folosire: forja exec-out screencap -p > fișier.jpg');
      const d = await device();
      const bytes = await viewerShot(site, d.id, io);
      if (typeof io.stdoutBytes !== 'function') throw new CliError('Terminalul nu poate scrie octeți pe stdout aici.');
      io.stdoutBytes(bytes);
      return 0;
    }
    if (command === 'shot' || command === 'screencap') {
      const d = await device(), file = args.find(a => !a.startsWith('-')) || shotName(new Date(io.now()));
      const bytes = await viewerShot(site, d.id, io);
      io.fs.writeFileSync(file, bytes);
      out(file + ' (' + Math.round(bytes.byteLength / 1024) + ' KB)');
      return 0;
    }
    if (command === 'watch') { return await watch(site, await device(), { dir: flags.dir, seconds: Number(flags.seconds) || 0 }, io, out); }
    if (command === 'sh') { return await shell(site, await device(), io, out, err); }
    const line = (command === 'run' || command === 'cmd') ? args.join(' ') : PHONE_VERBS.includes(command.toLowerCase()) ? [command, ...args].join(' ') : null;
    if (line !== null) {
      const d = await device();
      try {
        const r = await site.command(d.id, line);
        out(flags.json ? JSON.stringify(r, null, 2) : formatResult(r));
        return r.ok ? 0 : 1;
      } catch (e) {
        if (e.status === 409 && e.data?.phone === 'waiting') { err('Telefonul nu e conectat. L-am chemat: se conectează la următoarea bătaie (de obicei sub un minut, cu „Ecranul pe site” pornit). Reîncearcă.'); return 3; }
        if (e.status === 504) { err('✗ ' + (e.data?.text || e.message)); return 4; }
        throw e;
      }
    }
    throw new CliError('Comandă necunoscută: ' + command + '. Vezi: forja help');
  } catch (e) {
    if (e instanceof CliError) { err(e.message); return e.code; }
    err('Nu a mers: ' + (e?.message || e));
    return 1;
  }
}

/** Un cadru: deschide legătura, cere „shot”, salvează primul cadru primit după cerere. */
export function viewerShot(site, deviceId, io, timeoutMs = SHOT_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    let link = null, asked = false, done = false;
    const timer = setTimeout(() => finish(new CliError('Telefonul nu a trimis niciun cadru în ' + Math.round(timeoutMs / 1000) + ' s. E conectat? Pornește „Ecranul pe site” în FORJA → Profil → Telefonul meu.', 3)), timeoutMs);
    const finish = (e, v) => { if (done) return; done = true; clearTimeout(timer); link?.close(); e ? reject(e) : resolve(v); };
    site.open(deviceId, ev => {
      if (ev.type === 'open') { asked = true; link?.send('shot', 'shot'); }
      else if (ev.type === 'frame' && asked) finish(null, ev.bytes);
      else if (ev.type === 'result' && ev.result.id === 'shot' && !ev.result.ok) finish(new CliError('✗ ' + ev.result.text, 3));
      else if (ev.type === 'state' && ev.state.phone === 'waiting') io.stderr('Telefonul nu e conectat încă; l-am chemat (' + formatState(ev.state) + ').\n');
      else if (ev.type === 'error') finish(new CliError(ev.message));
      else if (ev.type === 'close' && !done) finish(new CliError('Legătura s-a închis (' + (ev.code || '') + ' ' + (ev.reason || '') + ').'));
    }).then(l => { link = l; if (asked) link.send('shot', 'shot'); }).catch(finish);
  });
}

async function watch(site, d, { dir, seconds }, io, out) {
  let n = 0, last = '';
  if (dir) io.fs.mkdirSync(dir, { recursive: true });
  const done = new Promise(resolve => {
    const stop = () => resolve(0);
    if (seconds > 0) setTimeout(stop, seconds * 1000);
    io.signal?.addEventListener?.('abort', stop, { once: true });
  });
  const link = await site.open(d.id, ev => {
    if (ev.type === 'state') { const s = formatState(ev.state); if (s !== last) { last = s; out(new Date(io.now()).toLocaleTimeString('ro-RO') + '  ' + s); } }
    else if (ev.type === 'frame') { n++; if (dir) { const f = frameName(dir, n); io.fs.writeFileSync(f, ev.bytes); out(f + ' (' + Math.round(ev.bytes.byteLength / 1024) + ' KB)'); } else out('cadru ' + n + ' · ' + Math.round(ev.bytes.byteLength / 1024) + ' KB'); }
    else if (ev.type === 'result') out(formatResult(ev.result));
    else if (ev.type === 'error') out('✗ ' + ev.message);
    else if (ev.type === 'close') out('Legătura s-a închis.');
  });
  out('Privesc ' + d.name + '. Ctrl-C oprește.' + (dir ? ' Cadrele merg în ' + dir : ''));
  const code = await done;
  link.close();
  return code;
}

/** Consola interactivă: fiecare rând e o comandă pentru telefon; `shot` salvează cadrul; `wait N`, `save F`, `exit`. */
async function shell(site, d, io, out, err) {
  let lastFrame = null, pending = null, wantShot = false, shots = 0;
  const link = await site.open(d.id, ev => {
    if (ev.type === 'state') out('· ' + formatState(ev.state));
    else if (ev.type === 'frame') { lastFrame = ev.bytes; if (wantShot) { wantShot = false; const f = shotName(new Date(io.now() + (shots++) * 1000)); io.fs.writeFileSync(f, ev.bytes); out('✓ ' + f + ' (' + Math.round(ev.bytes.byteLength / 1024) + ' KB)'); pending?.(); } }
    else if (ev.type === 'result') { if (!(ev.result.id === 'shot' && ev.result.ok)) out(formatResult(ev.result)); if (!(ev.result.id === 'shot' && ev.result.ok)) pending?.(); }
    else if (ev.type === 'error') { err('✗ ' + ev.message); pending?.(); }
    else if (ev.type === 'close') { out('Legătura s-a închis.'); pending?.(); }
  });
  out('Consola FORJA pentru ' + d.name + '. Scrie o comandă („help” le arată), „exit” iese.');
  for await (const raw of io.lines('forja> ')) {
    const line = String(raw).trim();
    if (!line) continue;
    const [verb, ...rest] = line.split(/\s+/);
    if (verb === 'exit' || verb === 'quit') break;
    if (verb === 'help') { out(HELP.split('\n').filter(l => /^  forja (tap|swipe|key|type|open|say|read|apps|scroll|info)/.test(l)).map(l => l.replace('  forja ', '  ')).join('\n') + '\n  shot · wait <s> · save <fișier.jpg> · exit'); continue; }
    if (verb === 'wait') { await io.sleep((Number(rest[0]) || 1) * 1000); continue; }
    if (verb === 'save') { if (!lastFrame) { err('Niciun cadru încă.'); continue; } const f = rest[0] || shotName(new Date(io.now())); io.fs.writeFileSync(f, lastFrame); out('✓ ' + f); continue; }
    const wait = new Promise(resolve => { pending = resolve; });
    const timer = setTimeout(() => pending?.(), 25000);
    if (verb === 'shot' || verb === 'poză' || verb === 'poza' || verb === 'captură' || verb === 'captura' || verb === 'cadru' || verb === 'screenshot') { wantShot = true; link.send('shot', 'shot'); }
    else link.send(line);
    await wait; clearTimeout(timer); pending = null;
  }
  link.close();
  return 0;
}
