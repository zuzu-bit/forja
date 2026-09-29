// Run after npm ci --prefix server: node scripts/ux-ui-test.cjs
// FORJA_JSDOM may override the package path for an isolated local test runtime.
// Exercises the shipped 4.4 site (server/insights.html + the concatenated site-*.js.txt) in jsdom against the same synthetic
// data and endpoint mock as the screenshot harness — never a live account. One section per ability (DESIGN-4.4 §3.1).
// The fixture buckets days in Europe/Bucharest (like the phone and the server); the site uses the process timezone. Pin it
// before any Date exists, or CI (UTC) sees yesterday's meals as today's between 21:00 and 24:00 UTC.
process.env.TZ = 'Europe/Bucharest';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {server, clients, createFixture, installMockFetch, clientSource} = require('./ux-fixture.cjs');
let JSDOM;
try { ({JSDOM} = require(process.env.FORJA_JSDOM || require.resolve('jsdom', {paths: [server]}))); }
catch { throw Error('Run npm ci --prefix server, or set FORJA_JSDOM to an installed jsdom package path.'); }
const html = fs.readFileSync(path.join(server, 'insights.html'), 'utf8');
const source = clientSource();
const SECTIONS = ['azi', 'teren', 'camarazi', 'gasire', 'inventar', 'somn', 'ratie', 'mars', 'muzica', 'paza', 'concentrare', 'cont'];
const tick = (ms = 20) => new Promise(resolve => setTimeout(resolve, ms));
async function until(fn, label, ms = 3000) { const start = Date.now(); while (Date.now() - start < ms) { if (fn()) return; await tick(10); } throw Error('Timed out: ' + label); }

/** A fresh page: jsdom + stubs + mocked network + the real client. `renderers` records the MapLibre stand-in. */
function page({hash = '', storage = {}, profile = 'rich', fail = [], visible = true} = {}) {
  const dom = new JSDOM(html, {url: 'https://forja.test/insights' + (hash ? '#' + hash : ''), runScripts: 'outside-only', pretendToBeVisual: true});
  const w = dom.window, errors = [], renderers = [];
  w.addEventListener('error', e => errors.push(e.error || e.message));
  w.addEventListener('unhandledrejection', e => errors.push(e.reason));
  w.IntersectionObserver = class { observe() {} unobserve() {} disconnect() {} };
  w.HTMLElement.prototype.scrollIntoView = () => {};
  w.scrollTo = () => {};
  w.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  w.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new w.Event('close')); };
  w.HTMLMediaElement.prototype.pause = () => {};
  w.HTMLMediaElement.prototype.load = () => {};
  w.crypto.randomUUID = randomUUID;
  w.URL.createObjectURL = () => 'blob:local-fixture';
  w.URL.revokeObjectURL = () => {};
  w.AbortController = AbortController;
  w.Response = Response;
  w.matchMedia = q => ({matches: /min-width: 1024px/.test(q), media: q, addEventListener() {}, removeEventListener() {}});
  w.confirm = () => { throw Error('native confirm() must not be used'); };
  w.prompt = () => { throw Error('native prompt() must not be used'); };
  w.alert = () => { throw Error('native alert() must not be used'); };
  Object.defineProperty(w.navigator, 'clipboard', {value: {writeText: async text => { w.__copied = text; }}});
  let visibility = visible ? 'visible' : 'hidden';
  Object.defineProperty(w.document, 'visibilityState', {configurable: true, get: () => visibility});
  Object.defineProperty(w.document, 'hidden', {configurable: true, get: () => visibility !== 'visible'});
  // MapLibre stand-in: the page never loads the vendor script when these globals exist.
  w.maplibregl = {};
  w.ForjaMapRenderer = {create(container, opts) {
    const r = {container, opts, data: [], modes: [], fits: 0, focused: [], ready: false, threeD: false, layers: {}, setData(d) { this.data.push(d); }, setMode(m) { this.modes.push(m); },
      set3D(on) { this.threeD = on; return true; }, setLayers(l) { Object.assign(this.layers, l); }, setTheme(t) { this.theme = t; }, fitOwn() { this.fits++; return true; }, fitDevices() { this.deviceFits = (this.deviceFits || 0) + 1; return true; },
      focus(p) { this.focused.push(p); return true; }, fitLine() { return true; }, resize() {}, destroy() { this.destroyed = true; }};
    renderers.push(r);
    setTimeout(() => { r.ready = true; opts.onReady(r); }, 5);
    return r;
  }};
  for (const [k, v] of Object.entries(storage)) w.localStorage.setItem(k, v);
  const fixture = createFixture(profile, Date.now(), {fail});
  installMockFetch(w, fixture);
  w.eval(source + '\nwindow.__ux = {Site, Auth, Poll, MapHost, Circle, Explore, Gasire, Teren, Inventar, Somn, Cont, Camarazi, Azi, signOut, route, parseHash, fmt, plural, fill};');
  const $ = id => { const n = w.document.getElementById(id); assert(n, `Missing #${id}`); return n; };
  const call = (suffix, method = 'GET') => fixture.calls.findLast(c => c.route.endsWith(suffix) && c.method === method);
  return {dom, w, $, fixture, errors, renderers, call, ux: w.__ux, setVisible(v) { visibility = v ? 'visible' : 'hidden'; w.document.dispatchEvent(new w.Event('visibilitychange')); }};
}
async function login(p, password = 'local-demo-only') {
  p.$('login-email').value = 'lana@example.test'; p.$('login-password').value = password;
  p.$('login-form').dispatchEvent(new p.w.Event('submit', {bubbles: true, cancelable: true}));
  await tick(40);
}
async function open(p, hash) { p.w.location.hash = '#' + hash; await tick(40); }
const visibleText = p => p.w.document.body.textContent;
const checks = [];
async function check(name, fn) { await fn(); checks.push(name); }
function clean(p) { assert.equal(p.errors.length, 0, p.errors.join('\n')); }

(async () => {
  await check('one page shell: unique ids, linked labels, viewport, every $() reference exists', () => {
    const p = page();
    const ids = [...p.w.document.querySelectorAll('[id]')].map(n => n.id);
    assert.equal(ids.length, new Set(ids).size, 'Duplicate HTML IDs');
    for (const label of p.w.document.querySelectorAll('label[for]')) assert(p.w.document.getElementById(label.htmlFor), `Broken label for ${label.htmlFor}`);
    assert(p.w.document.querySelector('meta[name="viewport"][content*="viewport-fit=cover"]'));
    const generated = new Set([...source.matchAll(/\bid: ['"]([^'"]+)['"]/g)].map(m => m[1]));
    for (const [, id] of source.matchAll(/\$\(['"]([^'"]+)['"]\)/g)) assert(p.w.document.getElementById(id) || generated.has(id), `Unbound DOM reference #${id}`);
    for (const id of SECTIONS) assert(p.w.document.getElementById('s-' + id), 'section ' + id);
    clean(p);
  });

  await check('the served script list, the harness and the deploy check agree; Leaflet and map-frame are gone', () => {
    const staticSrc = fs.readFileSync(path.join(server, 'site-static.mjs'), 'utf8');
    const listed = JSON.parse(/CLIENT_FILES = (\[[^\]]+\])/.exec(staticSrc)[1].replace(/'/g, '"'));
    assert.deepEqual(listed, clients);
    const verify = fs.readFileSync(path.join(server, 'verify-live.mjs'), 'utf8');
    assert.deepEqual(JSON.parse(/const scripts = (\[[^\]]+\])/.exec(verify)[1].replace(/'/g, '"')), clients);
    const worker = fs.readFileSync(path.join(server, 'insights-worker.mjs'), 'utf8');
    for (const gone of ['leaflet', 'map-frame', 'insights-client.js.txt', 'journey-client', 'organizer-client']) { assert(!worker.includes(gone), gone); assert(!staticSrc.includes(gone), gone); }
    for (const f of ['map-frame.html', 'map-frame-client.js.txt', 'vendor/leaflet-1.9.4.js.txt', 'insights-client.js.txt', 'social-client.js.txt']) assert(!fs.existsSync(path.join(server, f)), f);
    const csp = /PAGE_CSP = "([^"]+)"/.exec(staticSrc)[1];
    assert.match(csp, /font-src 'self'/); assert.match(csp, /script-src 'self';/); assert.doesNotMatch(csp, /unsafe-eval|openstreetmap|frame-src/);
    assert.match(csp, /connect-src 'self' https:\/\/identitytoolkit\.googleapis\.com https:\/\/securetoken\.googleapis\.com https:\/\/tiles\.openfreemap\.org;/);
    assert(!/<script(?![^>]*\bsrc=)[^>]*>/.test(html), 'no inline script');
  });

  await check('retired surfaces are not on the page: social graph, organizer, cleanup, Campanii, web mic, Pentru tine, browser journey', () => {
    const everything = html + source;
    for (const gone of ['/v2/social/chat', '/v2/social/group', '/v2/social/partner', '/v2/social/visibility', '/v2/social/journey', '/v2/organizer', '/v2/cleanup', '/insights/api/campaign', '/insights/api/phones', '/v2/sleep/', '/insights/api/recommendations', '/insights/api/state', '/insights/map-frame', 'Campanii', 'Pentru tine', 'Organizează pe telefon', 'Curățenie automată', 'Cine mă vede', 'Explorează'])
      assert(!everything.includes(gone), 'still present: ' + gone);
  });

  await check('login screen: voice, reset link, no remembered password; wrong password is a Romanian message', async () => {
    const p = page();
    await tick(20);
    assert(!p.$('login').hidden); assert(p.$('app').hidden);
    assert.match(p.$('login-submit').textContent, /^Intră$/);
    assert.equal(p.$('login-forgot').textContent, 'Ai uitat parola?');
    await login(p, 'gresit');
    assert(!p.$('login').hidden);
    assert.equal(p.$('login-message').textContent, 'Email sau parolă greșite.');
    assert.equal(p.w.localStorage.getItem('forja.auth.v1'), null);
    clean(p);
  });

  await check('“Ai uitat parola?” sends the Firebase reset email for the typed address', async () => {
    const p = page();
    await tick(20);
    p.$('login-forgot').click(); await tick(20);
    assert.match(p.$('login-message').textContent, /adresa de email/);
    p.$('login-email').value = 'lana@example.test';
    p.$('login-forgot').click(); await tick(40);
    const c = p.fixture.calls.find(x => x.url.includes('accounts:sendOobCode'));
    assert(c); assert.deepEqual(JSON.parse(c.body), {requestType: 'PASSWORD_RESET', email: 'lana@example.test'});
    assert.match(p.$('login-message').textContent, /Verifică emailul/);
    clean(p);
  });

  await check('login persists only the refresh token under forja.auth.v1 and every API call carries the Bearer token', async () => {
    const p = page();
    await tick(20); await login(p);
    assert(!p.$('app').hidden); assert.equal(p.$('login-password').value, '');
    const saved = JSON.parse(p.w.localStorage.getItem('forja.auth.v1'));
    assert.deepEqual(Object.keys(saved).sort(), ['refresh', 'v'], 'only the refresh token: no email, uid or ID token in the browser');
    assert.equal(saved.refresh, 'local-harness-refresh'); assert.equal(saved.v, 1);
    assert(!JSON.stringify(saved).includes('eyJ') && !JSON.stringify(saved).includes('@'), 'the ID token and the email stay in memory');
    assert.equal(p.$('rail-email').textContent, 'lana@example.test');
    await until(() => p.call('/insights/api/azi'), 'azi');
    const apiCalls = p.fixture.calls.filter(c => c.route.startsWith('/v2/') || c.route.startsWith('/insights/api/'));
    assert(apiCalls.length && apiCalls.every(c => /^Bearer [\w-]+\.[\w-]+\.[\w-]+$/.test(c.headers.Authorization)));
    clean(p);
  });

  await check('a deep link survives the login: /insights#gasire → login → Găsire', async () => {
    const p = page({hash: 'gasire'});
    await tick(20);
    assert(!p.$('login-target').hidden); assert.match(p.$('login-target').textContent, /Găsire/);
    await login(p);
    assert.equal(p.w.location.hash, '#gasire');
    assert(!p.$('s-gasire').hidden); assert(p.$('s-azi').hidden);
    assert.equal(p.w.document.title, 'FORJA · Găsire');
    await until(() => p.$('gasire-panel').querySelector('.device'), 'device card');
    clean(p);
  });

  await check('a saved session opens straight into the linked section; the detail after “/” is decoded (Uri.encode)', async () => {
    const p = page({hash: 'inventar/inv-20260925-docs', storage: {'forja.auth.v1': JSON.stringify({v: 1, refresh: 'local-harness-refresh', uid: 'demo-owner', email: 'old@example.test'})}});
    await until(() => !p.$('app').hidden, 'app');
    assert(p.$('login').hidden);
    assert.equal(p.$('rail-email').textContent, 'lana@example.test', 'the email comes from the renewed ID token, not from storage');
    assert.deepEqual(JSON.parse(p.w.localStorage.getItem('forja.auth.v1')), {v: 1, refresh: 'local-harness-refresh'}, 'an old entry is rewritten without uid/email');
    assert(p.fixture.calls.some(c => c.url.includes('securetoken.googleapis.com')));
    assert(!p.fixture.calls.some(c => c.url.includes('signInWithPassword')));
    await until(() => /Download\/Organizate/.test(p.$('inventar-runs').textContent), 'run detail');
    assert.match(p.$('inventar-runs').textContent, /Facturi/);
    assert.deepEqual({...p.ux.parseHash('#teren/44.43550%2C26.10160')}, {id: 'teren', detail: '44.43550,26.10160'});
    clean(p);
  });

  await check('a revoked saved session falls back to the login with the link kept and the key removed', async () => {
    const p = page({hash: 'somn', storage: {'forja.auth.v1': JSON.stringify({v: 1, refresh: 'revoked-refresh'})}});
    await until(() => !p.$('login').hidden, 'login');
    assert.equal(p.w.localStorage.getItem('forja.auth.v1'), null);
    assert.match(p.$('login-message').textContent, /Sesiunea a expirat/);
    assert.equal(p.w.location.hash, '#somn');
    clean(p);
  });

  await check('a busy securetoken (429) keeps the saved session: the site opens and retries, she is not signed out', async () => {
    const p = page({hash: 'azi', storage: {'forja.auth.v1': JSON.stringify({v: 1, refresh: 'busy-refresh'})}});
    await until(() => !p.$('app').hidden, 'app');
    assert(p.$('login').hidden);
    assert.deepEqual(JSON.parse(p.w.localStorage.getItem('forja.auth.v1')), {v: 1, refresh: 'busy-refresh'});
    await until(() => p.$('body-azi').querySelector('.state-error'), 'retryable error');
    assert.match(p.$('body-azi').textContent, /Conectarea nu răspunde acum/);
    assert(p.ux.Auth.session, 'still signed in');
    clean(p);
  });

  await check('navigation: 12 sections by hash, sidebar groups, phone bar Azi · Teren · Găsire · Mai mult, unknown hash → #azi', async () => {
    const p = page();
    await tick(20); await login(p);
    const groups = [...p.w.document.querySelectorAll('#rail-nav .nav-label')].map(n => n.textContent);
    assert.deepEqual(groups, ['ZIUA', 'LUMEA', 'CORPUL', 'TELEFONUL']);
    assert.deepEqual([...p.w.document.querySelectorAll('#tabbar-items .tab-item')].map(n => n.textContent.trim()), ['Azi', 'Teren', 'Găsire', 'Mai mult']);
    assert.deepEqual([...p.w.document.querySelectorAll('#more-grid a')].map(a => a.getAttribute('href')), ['#camarazi', '#somn', '#ratie', '#mars', '#inventar', '#muzica', '#paza', '#concentrare', '#cont'], 'the sheet follows the sidebar groups');
    for (const id of SECTIONS) {
      await open(p, id);
      for (const other of SECTIONS) assert.equal(p.$('s-' + other).hidden, other !== id, `${other} while on ${id}`);
      assert(p.$('s-' + id).querySelector('.stamp'), 'stamp on ' + id);
      assert(p.$('s-' + id).querySelector('.infodot'), '“i” on ' + id);
      assert(p.$('chip-' + id), 'connection chip on ' + id);
      assert.equal(p.w.document.querySelector(`#rail-nav [aria-current="page"], #rail-cont [aria-current="page"]`)?.dataset.nav, id);
    }
    await open(p, 'privacy');
    assert.equal(p.w.location.hash, '#azi'); assert(!p.$('s-azi').hidden);
    p.$('tab-more').click(); assert(!p.$('more-sheet').hidden);
    p.w.document.dispatchEvent(new p.w.KeyboardEvent('keydown', {key: 'Escape'})); assert(p.$('more-sheet').hidden);
    clean(p);
  });

  await check('Azi: today, last night, one Legături tile per section, the mascot and the day quote', async () => {
    const p = page();
    await tick(20); await login(p);
    await until(() => p.$('body-azi').querySelector('.links'), 'azi');
    assert.equal(p.$('body-azi').querySelectorAll('.link-tile').length, 10);
    assert.match(p.$('body-azi').textContent, /1 540/);
    assert.match(p.$('line-azi').textContent, /^Raport de (dimineață|amiază|seară), Lana\.$/);
    assert(p.$('body-azi').querySelector('.mascot .bubble')); assert(p.$('body-azi').querySelector('.quote blockquote'));
    assert.equal(p.$('chip-azi').textContent, '9 DIN 10 LA POST');
    assert.equal(p.w.document.querySelector('[data-link-dot="paza"]').className, 'dot stale');
    clean(p);
  });

  await check('Teren: MapLibre host, explore pages + since, first fit on her own data, poll only while visible', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'teren');
    await until(() => p.renderers[0]?.ready && p.renderers[0].fits > 0, 'map + fit');
    const r = p.renderers[0];
    assert.equal(p.renderers.length, 1);
    assert(p.fixture.calls.some(c => c.route === '/v2/social/explore/state' && c.search.includes('cursor=')), 'paged explore');
    const last = r.data.at(-1);
    assert.equal(last.cells.features.length, p.fixture.data.explore.features.length);
    assert.equal(last.places.length, 5); assert.equal(last.friends.length, 5); assert.equal(last.routes.length, 6); assert.equal(last.devices.length, 1);
    assert(r.modes.includes('teren'));
    assert(p.ux.Poll.jobs.has('teren:cerc') && p.ux.Poll.jobs.has('teren:explore'));
    const fits = r.fits;
    await p.ux.Explore.load();
    assert.match(p.fixture.calls.findLast(c => c.route === '/v2/social/explore/state').search, /^\?since=\d+$/);
    await p.ux.Circle.load(); await tick(20);
    assert.equal(r.fits, fits, 'polls update data without moving the camera again');
    const before = p.fixture.calls.length;
    p.setVisible(false); p.ux.Poll.wake(); p.ux.Poll.jobs.get('teren:cerc').kick(); await tick(30);
    assert.equal(p.fixture.calls.length, before, 'a hidden tab does not poll');
    p.setVisible(true);
    await open(p, 'azi');
    assert(!p.ux.Poll.jobs.has('teren:cerc') && !p.ux.Poll.jobs.has('teren:explore'), 'leaving Teren stops its polls');
    clean(p);
  });

  await check('Teren: a place is edited in the panel (name, stars, note) through PATCH explore/places', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'teren');
    await until(() => p.$('teren-panel').querySelector('.tabs'), 'panel');
    p.$('teren-tab-locuri').click();
    const row = p.$('teren-panel').querySelector('.place-row'); assert(row); row.click(); await tick(20);
    const name = p.$('place-name'); assert.equal(name.value, 'Herăstrău · debarcader');
    name.value = 'Debarcaderul';
    p.$('teren-panel').querySelectorAll('.stars .star')[2].click();
    p.$('place-note').value = 'Joi, 7:00.';
    [...p.$('teren-panel').querySelectorAll('button')].find(b => b.textContent === 'Salvează').click();
    await tick(40);
    const c = p.call('/v2/social/explore/places/p1', 'PATCH');
    assert.deepEqual(JSON.parse(c.body), {name: 'Debarcaderul', stars: 3, note: 'Joi, 7:00.'});
    assert.match(p.$('toast').textContent, /Salvat/);
    clean(p);
  });

  await check('Teren: a place being edited survives the 30 s / 60 s polls (text, stars, focus)', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'teren');
    await until(() => p.$('teren-panel').querySelector('.tabs'), 'panel');
    p.$('teren-tab-locuri').click();
    p.$('teren-panel').querySelector('.place-row').click(); await tick(20);
    const name = p.$('place-name'), note = p.$('place-note');
    name.focus(); name.value = 'Debarcaderul nou'; name.dispatchEvent(new p.w.Event('input', {bubbles: true}));
    p.$('teren-panel').querySelectorAll('.stars .star')[3].click();
    name.focus();
    await p.ux.Circle.load(); await tick(20);
    await p.ux.Teren.refreshExplore(); await tick(20);
    assert.equal(p.$('place-name'), name, 'the same input: not rebuilt by the poll');
    assert.equal(p.$('place-name').value, 'Debarcaderul nou');
    assert.equal(p.w.document.activeElement, name, 'focus (and the phone keyboard) stays');
    assert.equal(p.$('teren-panel').querySelectorAll('.stars .star.on').length, 4);
    assert.equal(p.$('teren-panel').querySelectorAll('.place-card').length, 1);
    assert(p.$('teren-panel').querySelector('.tabs') && p.$('teren-panel').querySelector('.place-list'), 'tabs and list are still redrawn');
    name.blur(); note.value = 'Joi.'; note.dispatchEvent(new p.w.Event('input', {bubbles: true}));
    await p.ux.Circle.load(); await tick(20);
    assert.equal(p.$('place-note').value, 'Joi.', 'an unsaved change survives even without focus');
    [...p.$('teren-panel').querySelectorAll('button')].find(b => b.textContent === 'Salvează').click(); await tick(40);
    assert.deepEqual(JSON.parse(p.call('/v2/social/explore/places/p1', 'PATCH').body), {name: 'Debarcaderul nou', stars: 4, note: 'Joi.'});
    await p.ux.Circle.load(); await tick(20);
    assert.notEqual(p.$('place-name'), name, 'after Salvează the card is rebuilt from the saved place');
    assert.equal(p.$('place-name').value, 'Debarcaderul nou');
    clean(p);
  });

  await check('Teren deep link #teren/<lat>,<lng> drops a pin and focuses once', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'teren/44.43550,26.10160');
    await until(() => p.renderers[0]?.focused.length, 'focus');
    const r = p.renderers[0];
    assert.deepEqual({...r.focused[0]}, {lat: 44.4355, lng: 26.1016});
    assert.deepEqual({...r.data.at(-1).pin}, {lat: 44.4355, lng: 26.1016});
    await p.ux.Circle.load(); await tick(20);
    assert.equal(r.focused.length, 1, 'later polls do not pull the camera back');
    clean(p);
  });

  await check('Camarazi: the app friends, family, ghost hides position and song, invite code, Din agendă state (set on the phone)', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'camarazi');
    await until(() => p.$('camarazi-list').querySelectorAll('.friend').length, 'friends');
    const cards = [...p.$('camarazi-list').querySelectorAll('.friend')];
    assert.equal(cards.length, 6, '2 family + 4 friends (Radu is family and friend: shown once)');
    const ghost = cards.find(c => /Ioana/.test(c.textContent));
    assert.equal(ghost.tagName, 'DIV'); assert.match(ghost.textContent, /mod fantomă/); assert(!ghost.querySelector('.friend-music'));
    const radu = cards.find(c => /Radu/.test(c.textContent));
    assert.match(radu.textContent, /fantomă · te vede familia/, 'a ghost friend who has you in his family');
    assert.equal(radu.getAttribute('href'), '#teren/44.41950,26.08200', 'his family position opens on Teren');
    await open(p, 'teren'); await until(() => p.$('teren-panel').querySelector('.tabs'), 'teren panel');
    p.$('teren-tab-camarazi').click();
    const rows = [...p.$('teren-panel').querySelectorAll('.place-row')], raduRow = rows.find(r => /Radu/.test(r.textContent));
    assert.equal(rows.filter(r => /Radu/.test(r.textContent)).length, 1, 'Radu once in the Teren list');
    assert(!raduRow.disabled, 'the family position makes him selectable on Teren'); assert.match(raduRow.textContent, /fantomă · te vede familia/);
    raduRow.click(); assert.deepEqual({...p.renderers[0].focused.at(-1)}, {lat: 44.4195, lng: 26.082});
    await open(p, 'camarazi'); await until(() => p.$('camarazi-list').querySelector('.friend'), 'back to camarazi');
    const ana = cards.find(c => /Ana Ionescu/.test(c.textContent));
    assert.equal(ana.getAttribute('href'), '#teren/44.44620,26.09850'); assert.match(ana.textContent, /Fetele care ard/);
    assert.equal(p.w.document.querySelector('.invite-code').textContent, 'FORJA-K7Q2XM');
    [...p.$('camarazi-side').querySelectorAll('button')].find(b => /Copiază/.test(b.textContent)).click(); await tick(20);
    assert.equal(p.w.__copied, 'FORJA-K7Q2XM');
    await until(() => /Te găsesc după număr · până la/.test(p.$('camarazi-disc-sub').textContent), 'discovery');
    assert.match(p.$('camarazi-disc-note').textContent, /Se schimbă din FORJA, la Profil\.$/);
    assert(!p.$('camarazi-side').querySelector('.switch'), 'no switch: the phone owns the choice and the contract that covers it');
    assert(!p.call('/v2/social/contacts/discovery', 'POST') && !p.call('/v2/social/contacts/discovery', 'DELETE'), 'the site never changes the listing');
    clean(p);
  });

  await check('Găsire v2: Sună 60 s, Urmărește 10 min, +10, Oprește keeps the last point, rename, Scoate telefonul', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'gasire');
    await until(() => p.$('gasire-panel').querySelector('.device'), 'device');
    const btn = re => [...p.$('gasire-panel').querySelectorAll('button')].find(b => re.test(b.textContent));
    assert.match(p.$('gasire-panel').textContent, /ÎN GARDĂ/); assert.match(p.$('gasire-panel').textContent, /±12 m/);
    btn(/^Sună$/).click(); await tick(60);
    const ring = JSON.parse(p.call('/v2/recovery/devices/' + p.fixture.data.devices[0].id + '/command', 'POST').body);
    assert.equal(ring.kind, 'ring'); assert.equal(ring.seconds, 60); assert.match(ring.id, /^[0-9a-f-]{36}$/);
    assert.match(p.$('gasire-panel').textContent, /SUNĂ/);
    assert.equal(p.ux.Poll.jobs.get('gasire:devices').ms, 5000, 'fast poll while a command is active');
    btn(/Oprește/).click(); await tick(60);
    assert.equal(p.call('/command', 'DELETE').search, '?id=' + ring.id);
    assert.match(p.$('gasire-panel').textContent, /±12 m/, 'the last position stays');
    btn(/Urmărește 10 min/).click(); await tick(60);
    const locate = JSON.parse(p.call('/command', 'POST').body); assert.equal(locate.kind, 'locate'); assert.equal(locate.minutes, 10);
    btn(/\+10 min/).click(); await tick(60);
    assert.deepEqual(JSON.parse(p.call('/extend', 'POST').body), {command: locate.id, minutes: 10});
    p.$('gasire-panel').querySelector('.device-head .icon-btn').click(); await tick(20);
    [...p.$('sheet-body').querySelectorAll('button')].find(b => /Redenumește/.test(b.textContent)).click(); await tick(30);
    p.$('device-name').value = 'Telefonul Lanei';
    p.$('sheet-body').querySelector('form').dispatchEvent(new p.w.Event('submit', {bubbles: true, cancelable: true})); await tick(60);
    assert.deepEqual(JSON.parse(p.call('/v2/recovery/devices/' + p.fixture.data.devices[0].id, 'PATCH').body), {name: 'Telefonul Lanei'});
    p.$('gasire-panel').querySelector('.device-head .icon-btn').click(); await tick(20);
    [...p.$('sheet-body').querySelectorAll('button')].find(b => /Scoate telefonul/.test(b.textContent)).click(); await tick(30);
    [...p.$('sheet-body').querySelectorAll('button')].find(b => b.textContent === 'Scoate telefonul').click(); await tick(60);
    assert(p.call('/grant', 'DELETE'));
    // Contract signed, phone removed: it comes back only from the phone („Probă”), never by itself.
    await until(() => /Deschide FORJA → Profil → Telefonul meu → Probă\./.test(p.$('gasire-panel').textContent), 'empty state');
    clean(p);
  });

  await check('Găsire: leaving while “Sună” is in flight does not restart the poll or move the map', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'teren');
    await until(() => p.renderers[0]?.ready, 'map');
    await open(p, 'gasire');
    await until(() => p.$('gasire-panel').querySelector('.device'), 'device');
    const fetch = p.w.fetch;
    p.w.fetch = async (url, o = {}) => { if (/\/command$/.test(String(url)) && o.method === 'POST') await tick(80); return fetch(url, o); };
    [...p.$('gasire-panel').querySelectorAll('button')].find(b => /^Sună$/.test(b.textContent)).click();
    await open(p, 'teren'); await tick(160);
    assert(p.call('/command', 'POST'), 'the ring was sent');
    assert(!p.ux.Poll.jobs.has('gasire:devices'), 'no Găsire poll outside Găsire');
    assert.equal(p.ux.MapHost.slot, p.$('teren-map'), 'the shared map stays in Teren');
    clean(p);
  });

  await check('Inventar: last run with folders, De aruncat, destination; older runs; gallery copies with preview and delete', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'inventar');
    await until(() => p.$('inventar-runs').querySelector('.run-card') && p.$('inventar-vault').querySelector('.thumb'), 'inventar');
    const card = p.$('inventar-runs').querySelector('.run-card');
    assert.equal(card.querySelectorAll('.folder:not(.trash)').length, 8, 'top 8 folders on the overview');
    assert.match(card.textContent, /De aruncat/); assert.match(card.textContent, /Pictures\/FORJA/); assert.match(card.textContent, /3 fișiere nu s-au mutat/);
    assert.equal(p.$('inventar-runs').querySelectorAll('.run-row').length, 2);
    assert.equal(p.$('inventar-vault').querySelectorAll('.thumb').length, 10);
    assert(!/null|undefined/.test(p.$('inventar-vault').textContent));
    p.$('inventar-vault').querySelector('.thumb').click(); await tick(60);
    assert(p.$('preview-dialog').open);
    [...p.$('preview-actions').querySelectorAll('button')].find(b => /Șterge copia/.test(b.textContent)).click(); await tick(30);
    [...p.$('sheet-body').querySelectorAll('button')].find(b => b.textContent === 'Șterge copia').click(); await tick(60);
    assert(p.fixture.calls.some(c => c.method === 'DELETE' && /^\/v2\/files\/[0-9a-f-]+$/.test(c.route)), 'DELETE /v2/files/<id>');
    assert.equal(p.$('inventar-vault').querySelectorAll('.thumb').length, 9);
    clean(p);
  });

  await check('Somn: 14 nights, chart, night timeline with playable moments from the chunk', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'somn');
    await until(() => p.$('body-somn').querySelectorAll('.night-row').length, 'nights');
    assert.equal(p.$('body-somn').querySelectorAll('.night-row').length, 14);
    assert(p.w.document.body.classList.contains('night'), 'night palette');
    await open(p, 'somn/s31');
    await until(() => p.$('body-somn').querySelector('.events'), 'timeline');
    assert.equal(p.$('body-somn').querySelectorAll('.event').length, 7);
    assert.match(p.$('body-somn').textContent, /Nu, lasă, mâine dimineață/);
    assert.match(p.$('body-somn').textContent, /ANALIZĂ CU MODEL/);
    p.$('body-somn').querySelector('.play-btn').click(); await tick(40);
    assert(p.call('/insights/api/somn/s31/chunk/0'));
    clean(p);
  });

  await check('Somn mirror: night in progress, waiting upload, days, hypnogram, alarm, limits and the signed night player', async () => {
    const p = page();
    const d = p.fixture.data;
    d.somn.live = {id: 's32', startAt: Date.now() - 2 * 3600000, alarm: {target: Date.now() + 5 * 3600000, windowMin: 30, firedAt: null, reason: null, snoozes: 0}, sounds: [{sound: 'rain', minutes: 20}]};
    await tick(20); await login(p); await open(p, 'somn');
    await until(() => p.$('body-somn').querySelectorAll('.night-row').length, 'nights');
    const text = p.$('body-somn').textContent;
    assert.match(text, /Stingerea e activă de la \d\d:\d\d/); assert.match(text, /Ploaie la adormire/);
    assert.match(text, /Adormit în 14 min · o trezire/);
    assert.match(text, /Alarma a sunat la \d\d:\d\d, la final de ciclu, amânată o dată/);
    assert.match(text, /ÎN AȘTEPTARE/, 'waiting for Wi-Fi is not "no recording"');
    const seg = [...p.$('body-somn').querySelectorAll('.seg-btn')];
    assert.deepEqual(seg.map(b => b.textContent), ['14 z', '30 z', '60 z']);
    seg[1].click(); await tick(40);
    assert(p.fixture.calls.some(c => c.route === '/insights/api/somn' && c.search === '?days=30'), 'days=30');
    // The waiting night explains itself.
    const waiting = d.somn.nights.find(n => n.audio === 'waiting');
    await open(p, 'somn/' + waiting.id); await until(() => p.$('body-somn').querySelector('.timeline'), 'waiting night');
    assert.match(p.$('body-somn').textContent, /Urcarea așteaptă Wi-Fi · 3 din 13/);
    assert.match(p.$('body-somn').textContent, /Sunetul încă n-a urcat de pe telefon/);
    // s30: hypnogram, real limits instead of the fixed badge; signed URLs make one player for the whole night.
    d.somnDetail.s30.chunks.forEach(c => { c.url = '/insights/audio/somn/demo/s30/' + c.i + '?exp=1&sig=x'; });
    d.somnDetail.s30.urlExpiresAt = Date.now() + 600000;
    await open(p, 'somn/s30'); await until(() => p.$('body-somn').querySelector('.listen'), 'player');
    const b = p.$('body-somn');
    assert(b.querySelector('.hyp-svg path.hyp-line'), 'hypnogram'); assert.match(b.textContent, /scor = 84 \(7 h 10 dormite\) − 6 \(puțin somn profund\)/);
    assert.match(b.textContent, /ANALIZĂ CU LIMITE/); assert.match(b.textContent, /Gemini n-a răspuns la 1 chunk-uri/);
    assert.match(b.textContent, /SUNETUL SE ȘTERGE (MÂINE|ÎN \d+ ZILE)/);
    assert.match(b.textContent, /Ascultat \d+ h/);
    b.querySelector('.listen .play-btn').click(); await tick(20);
    assert.equal(new p.w.URL(p.ux.Somn.audio.src).pathname, '/insights/audio/somn/demo/s30/0', 'plays from the signed URL, no Blob download');
    b.querySelectorAll('.event .play-btn')[1].click(); await tick(20);
    assert.equal(new p.w.URL(p.ux.Somn.audio.src).pathname, '/insights/audio/somn/demo/s30/2', 'an event jumps inside the same player');
    assert(!p.call('/insights/api/somn/s30/chunk/0') && !p.call('/insights/api/somn/s30/chunk/2'));
    clean(p);
  });

  await check('Rație, Marș, Muzică, Pază render their contract data with honest labels', async () => {
    const p = page();
    await tick(20); await login(p);
    await open(p, 'ratie'); await until(() => p.$('body-ratie').querySelector('.meal'), 'meals');
    assert.equal(p.$('body-ratie').querySelectorAll('.meal').length, 4);
    for (const label of ['ESTIMAT', 'EXACT · COD DE BARE', 'MANUAL']) assert(p.$('body-ratie').textContent.includes(label), label);
    assert.match(p.$('body-ratie').textContent, /Mai ai 560 kcal/);
    await open(p, 'mars'); await until(() => p.$('body-mars').querySelector('.act'), 'mars');
    assert.equal(p.$('body-mars').querySelectorAll('.act').length, 7); assert.equal(p.$('body-mars').querySelectorAll('.work').length, 3);
    assert(p.$('body-mars').querySelector('.route-sketch path'), 'mini route');
    await open(p, 'muzica'); await until(() => p.$('body-muzica').querySelector('.top-row'), 'muzica');
    assert.equal(p.$('body-muzica').querySelectorAll('.top-row').length, 10); assert.match(p.$('body-muzica').textContent, /Vama Veche/);
    assert(p.ux.Poll.jobs.has('muzica:data'));
    await open(p, 'paza'); await until(() => p.$('body-paza').querySelector('.app-row'), 'paza');
    assert.equal(p.$('body-paza').querySelectorAll('.app-row').length, 7);
    clean(p);
  });

  await check('Pază before today’s report: the latest day, and a chip that says where it leads', async () => {
    const p = page();
    const today = p.ux.fmt.keyOf(Date.now());
    p.fixture.data.paza.days = p.fixture.data.paza.days.filter(d => d.date !== today);
    await tick(20); await login(p); await open(p, 'paza');
    await until(() => p.$('body-paza').querySelector('.app-row'), 'paza');
    const hero = () => p.$('body-paza').querySelector('.paza-hero');
    assert(!hero().querySelector('.chip-btn'), 'no “Azi” chip that would lead back to yesterday');
    assert.doesNotMatch(hero().querySelector('.night-top .mono').textContent, /^AZI$/);
    const older = [...hero().querySelectorAll('.bar-col')].filter(b => !b.disabled && !b.classList.contains('sel'))[0];
    older.click(); await tick(20);
    assert.equal(hero().querySelector('.chip-btn').textContent, 'Ultima zi');
    hero().querySelector('.chip-btn').click(); await tick(20);
    assert(!hero().querySelector('.chip-btn'));
    assert.equal(hero().querySelector('.bar-col.sel')?.title.split(' · ')[0], p.ux.fmt.day(p.ux.fmt.dateKey(p.fixture.data.paza.days.map(d => d.date).sort().at(-1))));
    clean(p);
  });

  await check('an empty ring draws no arc (no stray dot at 12 o’clock)', async () => {
    const p = page({profile: 'empty'});
    await tick(20); await login(p); await open(p, 'ratie');
    await until(() => /Prima masă apare aici/.test(p.$('s-ratie').textContent), 'ratie');
    await open(p, 'azi'); await until(() => p.$('body-azi').querySelector('.ring'), 'azi ring');
    for (const r of p.w.document.querySelectorAll('#app .ring svg')) assert.equal(r.querySelectorAll('circle').length, r.querySelector('.ring-arc') ? 2 : 1);
    assert(![...p.w.document.querySelectorAll('#app .ring-arc')].some(c => /^0 /.test(c.getAttribute('stroke-dasharray'))), 'no zero-length arc');
    clean(p);
  });

  await check('Cont: contract v3, ten pipes with “ultima dată”, intake pause with revision, privacy, Ieși', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'cont');
    await until(() => p.$('body-cont').querySelector('.pipe'), 'cont');
    assert.equal(p.$('body-cont').querySelectorAll('.pipe').length, 10);
    assert.equal(p.$('chip-cont').textContent, 'CONTRACT v3');
    assert(!/\.\./.test(p.$('body-cont').textContent), 'no double full stop');
    p.$('body-cont').querySelector('.intake .switch').click(); await tick(60);
    assert.deepEqual(JSON.parse(p.call('/insights/api/intake', 'POST').body), {accepting: false, revision: 4});
    assert.match(p.$('cont-intake-sub').textContent, /În pauză/);
    p.$('cont-logout').click(); await tick(20);
    assert(!p.$('login').hidden); assert(p.$('app').hidden);
    assert.equal(p.w.localStorage.getItem('forja.auth.v1'), null);
    assert.equal(p.ux.Poll.jobs.size, 0);
    assert.equal(p.ux.Auth.session, null);
    for (const id of SECTIONS) assert.equal(p.$('s-' + id).childElementCount, 0, id + ' emptied');
    clean(p);
  });

  await check('empty account: every section shows an honest empty state, never an error', async () => {
    const p = page({profile: 'empty'});
    await tick(20); await login(p);
    const expect = {azi: /FĂRĂ SEMNAL|0 DIN 10/, camarazi: /Niciun camarad încă/, gasire: /Semnează contractul în FORJA\. Telefonul apare aici singur\./, inventar: /Niciun inventar încă/, somn: /Prima noapte apare aici/, ratie: /Prima masă apare aici/, mars: /Prima tură apare aici/, muzica: /Liniște pe post/, paza: /Niciun raport de pază/, concentrare: /Nicio sesiune de concentrare/, cont: /Nesemnat/};
    for (const [id, re] of Object.entries(expect)) {
      await open(p, id);
      await until(() => re.test(p.$('s-' + id).textContent), id, 3000);
      assert(!p.$('s-' + id).querySelector('.state-error'), id + ' shows an error');
    }
    await open(p, 'teren');
    await until(() => !p.$('teren-empty').hidden, 'teren empty');
    assert.match(p.$('teren-empty').textContent, /Semnează contractul în FORJA/);
    clean(p);
  });

  await check('server errors become a retryable, Romanian error state', async () => {
    const p = page({fail: ['/insights/api/muzica']});
    await tick(20); await login(p); await open(p, 'muzica');
    await until(() => p.$('body-muzica').querySelector('.state-error'), 'error');
    assert.match(p.$('body-muzica').textContent, /Nu a mers\. Încearcă din nou\./);
    assert.match(p.$('body-muzica').textContent, /Serverul nu răspunde acum\./);
    assert([...p.$('body-muzica').querySelectorAll('button')].some(b => b.textContent === 'Reîncearcă'));
    assert.equal(p.ux.fmt.dec(1.25, 1), '1,3'); assert.equal(p.ux.plural(21, 'zonă', 'zone'), '21 de zone'); assert.equal(p.ux.plural(1, 'zonă', 'zone'), '1 zonă');
    clean(p);
  });

  await check('untrusted text is rendered as text (no HTML injection from names, titles or notes)', async () => {
    const p = page();
    p.fixture.data.cerc.friends[0].name = '<img src=x onerror=alert(1)>';
    p.fixture.data.cerc.friends[0].nowPlaying.title = '<b>bold</b>';
    p.fixture.data.inventar.runs[0].folders[0].name = '<script>x()</script>';
    await tick(20); await login(p);
    await open(p, 'camarazi'); await until(() => p.$('camarazi-list').querySelector('.friend'), 'friends');
    await open(p, 'inventar'); await until(() => p.$('inventar-runs').querySelector('.run-card'), 'runs');
    assert.equal(p.w.document.querySelectorAll('#app img[src="x"], #app script').length, 0);
    assert(![...p.w.document.querySelectorAll('#app b')].some(b => b.textContent === 'bold'));
    assert.match(p.$('s-camarazi').textContent, /<img src=x onerror=alert\(1\)>/);
    assert.match(p.$('s-inventar').textContent, /<script>x\(\)<\/script>/);
    clean(p);
  });

  await check('copy: Romanian comma-below diacritics, no “!” in visible text, buttons ≤ 18 characters', async () => {
    const p = page();
    await tick(20); await login(p);
    for (const id of SECTIONS) { await open(p, id); await tick(60); }
    await open(p, 'gasire'); await until(() => p.$('gasire-panel').querySelector('.device'), 'device');
    const text = visibleText(p);
    assert(!/[şţŞŢ]/.test(text + source + html), 'cedilla ş/ţ found');
    const bang = [...p.w.document.querySelectorAll('#app *, #login *')].filter(n => n.children.length === 0 && /!/.test(n.textContent));
    assert.equal(bang.length, 0, 'exclamation marks: ' + bang.map(n => n.textContent).join(' | '));
    // Butoanele de acțiune (nu cardurile apăsabile, care poartă un nume de fișier sau de loc).
    const long = [...p.w.document.querySelectorAll('.btn, .map-btn, .chip-btn, .seg-btn, .tab, .tab-item, .link-btn, .menu-row, #login-submit')].map(b => b.textContent.trim()).filter(t => t.length > 18);
    assert.deepEqual(long, []);
    clean(p);
  });

  console.log(JSON.stringify({ok: true, checks}, null, 1));
  process.exit(0);
})().catch(error => { console.error(error); process.exit(1); });
