// Run after npm ci --prefix server: node scripts/ux-ui-test.cjs
// FORJA_JSDOM may override the package path for an isolated local test runtime.
// Exercises the shipped frontend against synthetic responses, never a live account.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {server, createFixture, installMockFetch, clientSource} = require('./ux-fixture.cjs');
let JSDOM;
try { ({JSDOM} = require(process.env.FORJA_JSDOM || require.resolve('jsdom', {paths: [server]}))); }
catch { throw Error('Run npm ci --prefix server, or set FORJA_JSDOM to an installed jsdom package path.'); }
const html = fs.readFileSync(path.join(server, 'insights.html'), 'utf8');
const dom = new JSDOM(html, {url: 'https://forja.test', runScripts: 'outside-only', pretendToBeVisual: true});
const w = dom.window;
const errors = [];
w.addEventListener('error', event => errors.push(event.error || event.message));
w.addEventListener('unhandledrejection', event => errors.push(event.reason));
w.IntersectionObserver = class {observe() {} unobserve() {} disconnect() {}};
w.HTMLElement.prototype.scrollIntoView = () => {};
w.scrollTo = () => {};
w.HTMLDialogElement.prototype.showModal = function () {this.open = true;};
w.HTMLDialogElement.prototype.close = function () {this.open = false; this.dispatchEvent(new w.Event('close'));};
w.HTMLMediaElement.prototype.pause = () => {};
w.HTMLMediaElement.prototype.load = () => {};
w.crypto.randomUUID = randomUUID;
w.URL.createObjectURL = () => 'blob:local-fixture';
w.URL.revokeObjectURL = () => {};
w.AbortController = AbortController;
w.Response = Response;
w.matchMedia = () => ({matches: false, addEventListener() {}, removeEventListener() {}});
w.confirm = () => true;
w.prompt = () => null;
const mapLayers = [];
const map = {
  center: {lat: 44.414, lng: 26.095}, views: [], animations: [],
  setView(point) {this.center = {lat: point[0], lng: point[1]}; this.views.push(point); return this;},
  flyTo(point, zoom, options) {this.animations.push(options); return this.setView(point);},
  fitBounds() {return this;}, getCenter() {return this.center;},
  invalidateSize() {}, remove() {}, on() {return this;}, zoomControl: {setPosition() {}}
};
w.L = {
  map: () => map, tileLayer: () => ({addTo() {return this;}}),
  control: {zoom: () => ({addTo() {return this;}})},
  layerGroup: () => ({items: [], addTo() {mapLayers.push(this); return this;}, clearLayers() {this.items = [];}, remove() {}, removeLayer(layer) {this.items = this.items.filter(item => item !== layer);}}),
  divIcon: value => value, latLngBounds: points => ({points}),
  marker: (point, options) => ({point, options, addTo(layer) {layer.items.push(this); return this;}, bindPopup(popup) {this.popup = popup; return this;}, getPopup() {return this.popup;}, setPopupContent(popup) {this.popup = popup; return this;}, setLatLng(point) {this.point = point; return this;}, setIcon(icon) {this.options.icon = icon; return this;}, openPopup() {return this;}, on() {return this;}}),
  circle: (point, options) => ({point, options, addTo(layer) {layer.items.push(this); return this;}, setLatLng(point) {this.point = point; return this;}, setRadius() {return this;}, setStyle() {return this;}})
};
const fixture = createFixture();
installMockFetch(w, fixture);
w.eval(clientSource() + '\nwindow.__ux = {page, logout, refresh, refreshSocial, socialChat, expireSocialMarkers, socialUI, refreshRecovery, recoveryUI, refreshPhones, refreshSleepReports, openSleepReport, localTimeInput, phoneWindows, phoneDurations, sleepUI};');
const $ = id => {const node = w.document.getElementById(id); assert(node, `Missing #${id}`); return node;};
const tick = () => new Promise(resolve => setTimeout(resolve, 15));
const checks = [];
async function check(name, fn) {await fn(); assert.equal(errors.length, 0, errors.join('\n')); checks.push(name);}
async function submit(id) {$(id).dispatchEvent(new w.Event('submit', {bubbles: true, cancelable: true})); await tick();}
async function clickText(container, pattern) {
  const button = [...container.querySelectorAll('button')].find(b => pattern.test(b.textContent));
  assert(button, `Missing action ${pattern}`); button.click(); await tick(); return button;
}
const callFor = (suffix, method) => fixture.calls.findLast(c => c.route.endsWith(suffix) && c.method === method);

(async () => {
  await check('unique IDs and linked form labels', () => {
    const ids = [...w.document.querySelectorAll('[id]')].map(n => n.id);
    assert.equal(ids.length, new Set(ids).size, 'Duplicate HTML IDs');
    for (const label of w.document.querySelectorAll('label[for]')) assert(w.document.getElementById(label.htmlFor), `Broken label for ${label.htmlFor}`);
    assert(w.document.querySelector('meta[name="viewport"]'));
    const generatedIds = new Set([...clientSource().matchAll(/\.id\s*=\s*['"]([^'"]+)['"]/g)].map(m => m[1]));
    const references = new Set([...clientSource().matchAll(/\$\(['"]([^'"]+)['"]\)/g)].map(m => m[1]));
    for (const id of references) assert(w.document.getElementById(id) || generatedIds.has(id), `Unbound DOM reference #${id}`);
  });
  await check('privacy is accessible before login and returns to login', async () => {
    w.location.hash = '#privacy-location'; await tick();
    assert(!$('privacy').hidden); assert($('privacy-location').open);
    assert($('app').hidden); assert($('login').hidden);
    $('privacy-back').click(); await tick(); assert($('privacy').hidden); assert(!$('login').hidden);
  });
  await check('real login flow uses mocked Firebase response', async () => {
    assert($('app').hidden);
    $('email').value = 'alex@example.test'; $('password').value = 'local-demo-only';
    await submit('login-form');
    assert(!$('app').hidden); assert($('login').hidden); assert.equal($('password').value, '');
    assert.equal($('stats').children.length, 4);
    assert(fixture.calls.some(c => c.url.includes('signInWithPassword')));
    assert(fixture.calls.filter(c => c.route.startsWith('/v2/') || c.route.startsWith('/insights/api/')).every(c => c.headers.Authorization === 'Bearer local-demo-token'));
  });
  await check('every existing feature remains reachable by navigation', async () => {
    for (const name of ['overview', 'data', 'ai', 'content', 'control', 'files', 'social']) {
      const button = w.document.querySelector(`[data-page="${name}"]`); assert(button, `Missing ${name} nav`);
      button.click(); await tick();
      assert(!$('page-' + name).hidden, `Failed opening ${name}`);
      for (const other of ['overview','data','ai','content','control','files','social'].filter(p => p !== name)) assert($('page-' + other).hidden, `Page ${other} leaked into ${name}`);
    }
    assert.equal($('vault-grid').children.length, 2);
    assert($('organizer-requests').querySelector('form'));
    assert($('cleanup-devices').querySelector('form'));
    assert($('phone-list').children.length > 0);
  });
  await check('privacy topic preserves authenticated navigation', async () => {
    w.__ux.page('files'); await tick();
    w.location.hash = '#privacy-files'; await tick();
    assert(!$('privacy').hidden); assert($('privacy-files').open); assert($('app').hidden);
    $('privacy-back').click(); await tick();
    assert($('privacy').hidden); assert(!$('app').hidden); assert(!$('page-files').hidden);
    $('open-organizer').click(); assert($('organizer-settings').open);
  });
  await check('mobile overflow menu closes after selection and Escape', async () => {
    const matchMedia = w.matchMedia;
    w.matchMedia = query => ({matches: query.includes('max-width'), addEventListener() {}, removeEventListener() {}});
    $('nav-more').open = true; w.document.querySelector('[data-page="control"]').click(); await tick();
    assert(!$('nav-more').open); assert(!$('page-control').hidden);
    $('nav-more').open = true; w.document.dispatchEvent(new w.KeyboardEvent('keydown', {key:'Escape', bubbles:true}));
    assert(!$('nav-more').open); assert(w.document.querySelector('#nav-more a[href="#privacy"]'));
    w.matchMedia = matchMedia;
  });
  await check('social tabs support keyboard focus and progressive disclosure', async () => {
    w.__ux.page('social'); await tick();
    $('social-people-tab').focus(); $('social-people-tab').dispatchEvent(new w.KeyboardEvent('keydown', {key:'ArrowRight', bubbles:true}));
    assert.equal(w.document.activeElement, $('social-plans-tab')); assert(!$('social-tab-plans').hidden); assert($('social-tab-people').hidden);
    assert.equal($('social-plans-tab').getAttribute('aria-selected'), 'true');
    const plan = $('social-tab-plans').querySelector('[data-map-selection]'); plan.open = true; await tick();
    assert(w.document.querySelector('.social-map-wrap').classList.contains('is-selecting'));
    $('social-plans-tab').dispatchEvent(new w.KeyboardEvent('keydown', {key:'Home', bubbles:true}));
    assert.equal(w.document.activeElement, $('social-people-tab')); assert(!w.document.querySelector('.social-map-wrap').classList.contains('is-selecting'));
    $('social-add-friend').click(); assert($('social-invite-details').open); assert.equal(w.document.activeElement, $('social-invite'));
  });
  await check('journal and audio tabs preserve controls', async () => {
    w.__ux.page('data');
    for (const name of ['journals', 'audio', 'sessions']) {
      w.document.querySelector(`[data-tab="${name}"]`).click(); assert(!$(name).hidden);
    }
    w.document.querySelector('[data-tab="audio"]').click(); $('audio-control').click(); await tick();
    assert(!$('page-control').hidden);
  });
  await check('unknown sleep scores and audio intervals do not become invented sleep measurements', async () => {
    const original = fixture.state.journals.sleep.records, today = new Date(); today.setHours(0,0,0,0);
    const yesterday = new Date(today); yesterday.setDate(yesterday.getDate()-1);
    fixture.state.journals.sleep.records = [
      {startAt:yesterday.getTime()+60000,endAt:null,score:null},
      {startAt:today.getTime()+60000,endAt:today.getTime()+120000,score:-1},
      {startAt:today.getTime()+120000,endAt:today.getTime()+180000,score:95,measurement:'recording_interval'},
      {startAt:today.getTime()+180000,endAt:today.getTime()+240000,score:82}
    ];
    try {
      await w.__ux.refresh();
      const rows = [...$('journals').querySelectorAll('.panel:first-child tbody tr')].map(row => [...row.querySelectorAll('td')].map(cell => cell.textContent));
      assert.equal(rows.length,4); assert.equal(rows[0][1],'—');
      assert.deepEqual(rows.map(row => row[3]),['—','—','—','82']);
      assert.equal(rows[2][2],'Înregistrare audio'); assert.equal(rows[2][1],'1 min');
      const bars = [...$('sleep-chart').querySelectorAll('.sleep-bar')];
      assert.equal(bars.length,7); assert(bars.slice(0,6).every(bar => bar.hidden)); assert(!bars[6].hidden);
    } finally {fixture.state.journals.sleep.records=original; await w.__ux.refresh();}
  });
  await check('social invitations preserve intended request payload', async () => {
    w.__ux.page('social'); await tick();
    const code = '12345678-1234-4234-8234-123456789012';
    $('social-invite').value = code; await submit('social-invite-form');
    assert.equal(callFor('/invite', 'POST').body.code, code);
    assert.equal(fixture.calls.filter(c => c.route === '/v2/social/session' && c.method === 'POST').length, 0, 'Navigation must not enable location sharing');
  });
  await check('saved places and group invites use current map center', async () => {
    map.setView([44.412, 26.093]); $('social-place-name').value = 'Locul nostru'; await submit('social-place-form');
    assert.deepEqual(callFor('/place','POST').body, {name: 'Locul nostru', lat: 44.412, lon: 26.093});
    $('social-group-name').value = 'La plimbare'; $('social-group-mode').value = 'cycle';
    $('social-group-at').value = '2026-09-22T17:00'; $('social-group-place').value = 'Parc';
    $('social-group-members').querySelector('input').checked = true; await submit('social-group-form');
    assert.equal(callFor('/group','POST').body.mode, 'cycle');
    assert.deepEqual(callFor('/group','POST').body.friends, ['demo-ana']);
  });
  await check('contact discovery can still be disabled explicitly', async () => {
    await clickText($('social-partner'), /Oprește.*găsirea|Oprește.*număr|Dezactivează.*găsirea|Nu mă mai găsi|Oprește descoperirea/i);
    assert(callFor('/contacts/discovery', 'DELETE'));
  });
  await check('a timed sharing session expires locally even with a fresh position', async () => {
    fixture.social.me.session = {mode:'walk', until:Date.now() - 1};
    fixture.social.me.location.at = Date.now(); await w.__ux.refreshSocial(); w.__ux.expireSocialMarkers();
    assert.equal(w.__ux.socialUI.data.me.session, null); assert.equal(w.__ux.socialUI.data.me.location, null);
    assert(!w.__ux.socialUI.markers.has(fixture.social.me.id)); assert($('social-ghost').hidden);
    fixture.social.me.session = {mode:'walk', until:Date.now() + 3600000}; await w.__ux.refreshSocial();
  });
  await check('stopping sharing during a poll refreshes the final server state', async () => {
    const fetch = w.fetch; let resolve;
    const before = structuredClone(fixture.social);
    w.fetch = (url, options) => String(url).endsWith('/v2/social/state') ? new Promise(r => {resolve = r;}) : fetch(url, options);
    const poll = w.__ux.refreshSocial(); await tick(); $('social-ghost').click(); await tick();
    assert.equal(fixture.social.me.session, null);
    w.fetch = fetch; resolve(Response.json(before)); await poll; await tick();
    assert.equal(w.__ux.socialUI.data.me.session, null); assert($('social-ghost').hidden);
    fixture.social.me.session = {mode:'walk', until:Date.now() + 3600000}; await w.__ux.refreshSocial();
  });
  await check('friend and message text remain escaped', async () => {
    fixture.social.friends[0].name = '<img src=x onerror=alert(1)>';
    fixture.messages[0].text = '<script>steal()</script>';
    await w.__ux.refreshSocial();
    assert($('social-friends').textContent.includes('<img'));
    assert.equal($('social-friends').querySelectorAll('img').length, 0);
    await w.__ux.socialChat(fixture.social.friends[0]);
    assert($('social-messages').textContent.includes('<script>'));
    assert.equal($('social-messages').querySelectorAll('script').length, 0);
  });
  await check('ghost control stops sharing and stale friend positions disappear', async () => {
    $('social-ghost').click(); await tick(); assert(callFor('/session', 'DELETE'));
    for (const person of [w.__ux.socialUI.data.me, ...w.__ux.socialUI.data.friends]) if (person.location) person.location.at = Date.now() - 120001;
    w.__ux.expireSocialMarkers(); assert.equal(w.__ux.socialUI.layer.items.length, 0);
  });
  await check('recovery start and stop retain explicit commands', async () => {
    await clickText($('recovery-devices'), /Localizează|Găsește telefonul|Caută telefonul/i);
    const start = callFor('/command','POST'); assert(start); assert.equal(start.body.minutes, 15);
    assert.match(start.body.id, /^[a-f\d-]{36}$/); assert(!('owner' in start.body));
    assert.match($('recovery-devices').textContent, /aștept|trimis/i);
    await clickText($('recovery-devices'), /Oprește căutarea|Oprește/i); assert(callFor('/command','DELETE'));
    fixture.recovery[0].position = {lat:44.415,lon:26.095,accuracy:28,battery:67,at:Date.now()};
    await w.__ux.refreshRecovery(); assert.equal(w.__ux.recoveryUI.layer.items.length, 2);
    fixture.recovery[0].position.at = Date.now() - 120001;
    await w.__ux.refreshRecovery(); assert.match($('recovery-devices').textContent, /Ultima poziție cunoscută/);
    assert.equal(w.__ux.recoveryUI.layer.items.length, 2);
    fixture.recovery[0].position.at = Date.now() - 86400001;
    await w.__ux.refreshRecovery(); assert.equal(w.__ux.recoveryUI.layer.items.length, 0);
  });
  await check('recovery duration survives polling and active state is explicit', async () => {
    const select = $('recovery-devices').querySelector('select'); select.value = '30'; select.dispatchEvent(new w.Event('change'));
    await w.__ux.refreshRecovery(); assert.equal($('recovery-devices').querySelector('select').value, '30');
    await clickText($('recovery-devices'), /Localizează/); assert.equal(callFor('/command','POST').body.minutes, 30);
    fixture.recovery[0].command.phase = 'active'; await w.__ux.refreshRecovery();
    assert.match($('recovery-devices').textContent, /Căutare activă/);
    await clickText($('recovery-devices'), /Oprește căutarea/);
  });
  await check('a recovery command during a poll refreshes the queued result', async () => {
    const fetch = w.fetch; let resolve;
    const before = structuredClone(fixture.recovery);
    w.fetch = (url, options) => String(url).endsWith('/v2/recovery/devices') ? new Promise(r => {resolve = r;}) : fetch(url, options);
    const poll = w.__ux.refreshRecovery(); await tick();
    await clickText($('recovery-devices'), /Localizează/);
    assert(fixture.recovery[0].command);
    w.fetch = fetch; resolve(Response.json({devices: before})); await poll; await tick();
    assert.equal(w.__ux.recoveryUI.data[0].command.phase, 'queued');
    assert.match($('recovery-devices').textContent, /Așteptăm confirmarea/);
    await clickText($('recovery-devices'), /Oprește căutarea/);
  });
  await check('map actions respect reduced motion', async () => {
    const matchMedia = w.matchMedia; w.matchMedia = () => ({matches: true, addEventListener() {}, removeEventListener() {}});
    await clickText($('social-places'), /Pe hartă/); assert.equal(map.animations.at(-1).animate, false);
    w.matchMedia = matchMedia;
  });
  await check('AI stays opt in and campaign save stays separate from publish', async () => {
    w.__ux.page('ai'); assert($('recommend').disabled);
    $('ai-consent').checked = true; $('ai-consent').dispatchEvent(new w.Event('change')); $('recommend').click(); await tick();
    assert.equal(callFor('/recommendations','POST').body.consent, true); assert.equal($('recommendations').children.length, 1);
    w.__ux.page('content'); await tick();
    $('campaign-sponsor').value = 'Demo'; $('campaign-title').value = 'Weekend'; $('campaign-body').value = 'O idee simplă.';
    $('campaign-save').click(); await tick(); assert.equal(callFor('/campaigns','POST').body.content.published, false);
  });
  await check('legacy phone controls remain bounded to one hour', async () => {
    w.__ux.page('control'); await tick();
    const durations = [...$('phone-list').querySelector('select').options].map(o => Number(o.value));
    assert.equal(Math.max(...durations), 60);
    assert.equal($('sleep-reports').querySelectorAll(':scope>details').length, 0);
  });
  await check('sleep start sends an explicit 12 hour command and awaits confirmation', async () => {
    const phone = fixture.phones[0]; phone.sleep_capable = true; phone.sleep_analysis_allowed = true;
    await w.__ux.refreshPhones();
    const select = $('phone-list').querySelector('select'); select.value = '720'; select.dispatchEvent(new w.Event('change'));
    await clickText($('phone-list'), /^Pornește acum$/);
    const request = fixture.calls.findLast(c => c.route === '/insights/api/phones/' + phone.id + '/command');
    assert.equal(request.body.purpose, 'sleep'); assert.equal(request.body.minutes, 720);
    assert.match(request.body.sleep_id, /^[a-f\d-]{36}$/); assert.match(request.body.id, /^[a-f\d-]{36}$/);
    assert.match($('phone-list').textContent, /așteaptă confirmarea/i);
    assert(!$('phone-list').textContent.includes('● Se înregistrează'));
    phone.command = null; await w.__ux.refreshPhones();
  });
  await check('a daytime nap can be scheduled then replaced with new hours', async () => {
    const phone = fixture.phones[0];
    const start = new Date(); start.setHours(14, 0, 0, 0); if (start.getTime() <= Date.now()) start.setDate(start.getDate() + 1);
    const end = new Date(start.getTime() + 90 * 60000);
    function setTime(key, date) {const field = $('phone-' + phone.id + '-' + key); field.value = w.__ux.localTimeInput(date.getTime()); field.dispatchEvent(new w.Event('input'));}
    setTime('start', start); setTime('end', end);
    await clickText($('phone-list'), /^Programează intervalul$/);
    const first = fixture.calls.findLast(c => c.route === '/insights/api/phones/' + phone.id + '/command');
    assert.equal(first.body.purpose, 'sleep'); assert.equal(first.body.start_at, start.getTime()); assert.equal(first.body.stop_at, end.getTime());
    assert.equal(first.body.minutes, 90); assert.match($('phone-list').textContent, /Programat/);
    setTime('start', new Date(start.getTime() + 60000)); setTime('end', new Date(end.getTime() + 60000));
    await clickText($('phone-list'), /^Salvează orele$/);
    const replacement = fixture.calls.findLast(c => c.route === '/insights/api/phones/' + phone.id + '/command');
    assert.equal(replacement.body.replace_command, first.body.id); assert.notEqual(replacement.body.sleep_id, first.body.sleep_id);
    assert.equal(replacement.body.start_at, start.getTime() + 60000);
    phone.command = null; await w.__ux.refreshPhones();
  });
  await check('unready and actively recording phones cannot start another sleep session', async () => {
    const phone = fixture.phones[0]; phone.audio_ready = false; await w.__ux.refreshPhones();
    let start = [...$('phone-list').querySelectorAll('button')].find(b => b.textContent === 'Pornește acum'); assert(start.disabled);
    phone.audio_ready = true; phone.sleep_session = {state:'recording'}; await w.__ux.refreshPhones();
    start = [...$('phone-list').querySelectorAll('button')].find(b => b.textContent === 'Pornește acum'); assert(start.disabled);
    assert.match($('phone-list').textContent, /Se înregistrează/); phone.sleep_session = null;
  });
  await check('sleep reports escape transcripts and retain retry, pagination and privacy actions', async () => {
    const id = randomUUID(), chunk = randomUUID();
    const result = {id, started_at:Date.now()-7200000, state:'needs_retry'};
    fixture.sleep.sessions = [result];
    fixture.sleep.reports[id] = {id, recorded_ms:120000, analyzed_ms:60000, analysis_consent:true, snoring:{status:'unavailable'},
      chunks:[{id:chunk,item_id:randomUUID(),recorded_from:Date.now()-7200000,duration_ms:60000,state:'failed',result:{transcript:'<img src=x onerror=alert(1)> Text demo',topics:[{title:'<script>injected()</script>'}],limitations:['limited']}}],
      next_cursor:'page-two', pages:{'page-two':{id,analysis_consent:true,chunks:[{id:randomUUID(),item_id:randomUUID(),recorded_from:Date.now()-7140000,duration_ms:60000,state:'complete',result:{transcript_status:'empty'}}],next_cursor:null}}
    };
    await w.__ux.refreshSleepReports(); const report = $('sleep-reports').querySelector(':scope>details'); report.open = true; await tick();
    assert(report.textContent.includes('<img')); assert.equal(report.querySelectorAll('img,script').length, 0);
    assert(report.querySelector('a[href="#privacy-sleep"]')); assert.match(report.textContent, /2 min primit · 1 min analizat/);
    await clickText(report, /^Reîncearcă analiza$/);
    assert.equal(callFor('/chunks','POST').body.recording_session_id, chunk);
    await clickText(report, /^Mai multe fragmente$/);
    assert.equal(report.querySelectorAll('.file-card').length, 2); assert.match(report.textContent, /Nu s-a distins vorbire/);
    await clickText(report, /^Șterge raportul$/); assert(callFor('/' + id,'DELETE')); assert.equal($('sleep-reports').querySelectorAll(':scope>details').length, 0);
  });
  await check('open sleep reports update automatically without interrupting active playback', async () => {
    const id = randomUUID(), chunk = {id:randomUUID(),item_id:randomUUID(),recorded_from:Date.now()-60000,duration_ms:60000,state:'pending'};
    fixture.sleep.sessions = [{id,started_at:Date.now()-60000,state:'analyzing',chunk_count:1,analyzed_ms:0,snoring:{status:'unavailable'}}];
    fixture.sleep.reports[id] = {id,recorded_ms:60000,analyzed_ms:0,analysis_consent:true,snoring:{status:'unavailable'},chunks:[chunk],next_cursor:null};
    await w.__ux.refreshSleepReports(); const report = $('sleep-reports').querySelector(':scope>details'); report.open = true; await tick();
    assert.match(report.querySelector('.sleep-report-body').textContent, /În așteptare/);
    fixture.sleep.sessions[0].state = 'complete'; fixture.sleep.sessions[0].analyzed_ms = 60000;
    fixture.sleep.reports[id].analyzed_ms = 60000; chunk.state = 'complete'; chunk.result = {transcript:'Text nou, disponibil automat.'};
    await w.__ux.refreshSleepReports(); await tick(); assert(report.textContent.includes('Text nou, disponibil automat.'));
    const audio = w.document.createElement('audio'); let playing = true;
    Object.defineProperty(audio,'paused',{get:()=>!playing}); Object.defineProperty(audio,'ended',{get:()=>false});
    report.querySelector('.file-card').append(audio);
    fixture.sleep.sessions[0].chunk_count = 2; chunk.result.transcript = 'Actualizare după ascultare.';
    await w.__ux.refreshSleepReports(); await tick();
    assert(audio.isConnected); assert(!report.textContent.includes('Actualizare după ascultare.'));
    playing = false; await w.__ux.refreshSleepReports(); await tick();
    assert(report.textContent.includes('Actualizare după ascultare.')); assert(!audio.isConnected);
  });
  await check('late sleep detail responses cannot overwrite the newer report', async () => {
    const report = $('sleep-reports').querySelector(':scope>details'), id = report.dataset.sleep;
    const body = report.querySelector('.sleep-report-body'), fetch = w.fetch, pending = [];
    w.fetch = (url, options) => String(url) === '/v2/sleep/sessions/' + id ? new Promise(r => pending.push(r)) : fetch(url, options);
    try {
      const first = w.__ux.openSleepReport(id,body); await tick();
      const second = w.__ux.openSleepReport(id,body); await tick(); assert.equal(pending.length, 2);
      const old = structuredClone(fixture.sleep.reports[id]), recent = structuredClone(old);
      old.chunks[0].result.transcript = 'Stare veche, primită târziu.';
      recent.chunks[0].result.transcript = 'Raport recent, afișat corect.';
      pending[1](Response.json(recent)); await second;
      pending[0](Response.json(old)); await first;
      assert(report.textContent.includes('Raport recent, afișat corect.'));
      assert(!report.textContent.includes('Stare veche, primită târziu.'));
    } finally {w.fetch = fetch;}
  });
  await check('deleting a sleep report while audio loads cannot start detached playback', async () => {
    const report = $('sleep-reports').querySelector(':scope>details');
    assert(report?.open);
    const fetch = w.fetch, createURL = w.URL.createObjectURL, play = w.HTMLMediaElement.prototype.play;
    let resolve, created = 0, played = 0;
    w.fetch = (url, options) => /\/v2\/sessions\/[^/]+\/items\//.test(String(url)) ? new Promise(r => {resolve = r;}) : fetch(url, options);
    w.URL.createObjectURL = () => {created++; return 'blob:delayed-sleep';};
    w.HTMLMediaElement.prototype.play = async () => {played++;};
    try {
      await clickText(report, /^Ascultă fragmentul$/); assert(resolve, 'Audio fetch must be in flight');
      await clickText(report, /^Șterge raportul$/); assert(!report.isConnected);
      resolve(new Response(new Uint8Array([1,2,3]), {headers:{'content-type':'audio/mp4'}})); await tick();
      assert.equal(created, 0, 'Detached cards must not allocate media URLs');
      assert.equal(played, 0, 'Deleted reports must not begin playback');
    } finally {w.fetch = fetch; w.URL.createObjectURL = createURL; w.HTMLMediaElement.prototype.play = play;}
  });
  await check('logout clears private content and pending social data', async () => {
    w.__ux.page('social'); await tick();
    const fetch = w.fetch; let resolve, resolveSleep;
    w.fetch = (url, options) => String(url).endsWith('/v2/recovery/devices') ? new Promise(r => {resolve = r;}) : String(url).endsWith('/v2/sleep/sessions') ? new Promise(r => {resolveSleep = r;}) : fetch(url, options);
    const pending = w.__ux.refreshRecovery(), pendingSleep = w.__ux.refreshSleepReports(); await tick(); w.__ux.logout();
    resolve(Response.json({devices: fixture.recovery})); await pending;
    resolveSleep(Response.json({sessions:[{id:randomUUID(),state:'complete',started_at:Date.now()}]})); await pendingSleep;
    w.fetch = fetch;
    assert($('app').hidden); assert(!$('login').hidden);
    assert.equal(w.__ux.socialUI.data, null); assert.equal(w.__ux.recoveryUI.data, null);
    assert.equal($('social-friends').childNodes.length, 0); assert.equal($('recovery-devices').childNodes.length, 0);
    assert.equal($('vault-grid').childNodes.length, 0);
    assert.equal($('sleep-reports').childNodes.length, 0); assert.equal(w.__ux.sleepUI.owner, null);
  });
  await check('privacy opened during sign in stays the only visible screen', async () => {
    const fetch = w.fetch; let resolve;
    w.fetch = (url, options) => String(url).includes('signInWithPassword') ? new Promise(r => {resolve = r;}) : fetch(url, options);
    $('email').value = 'alex@example.test'; $('password').value = 'local-demo-only';
    await submit('login-form'); w.location.hash = '#privacy-ai'; await tick();
    assert(!$('privacy').hidden); assert($('app').hidden);
    w.fetch = fetch;
    resolve(Response.json({idToken:'local-demo-token', refreshToken:'local-demo-refresh', expiresIn:'3600', email:'alex@example.test'}));
    await tick(); assert(!$('privacy').hidden); assert($('app').hidden); assert($('login').hidden);
    $('privacy-back').click(); await tick(); assert($('privacy').hidden); assert(!$('app').hidden);
    w.__ux.logout();
  });
  await check('sleep deep link waits for login and opens the linked controls', async () => {
    w.location.hash = '#sleep'; await tick();
    assert(!$('login').hidden); assert($('app').hidden); assert($('privacy').hidden);
    $('email').value = 'alex@example.test'; $('password').value = 'local-demo-only'; await submit('login-form');
    assert(!$('app').hidden); assert(!$('page-control').hidden); assert.equal(w.location.hash, '#sleep');
    w.document.querySelector('[data-page="data"]').click(); await tick();
    assert(!$('page-data').hidden); assert.equal(w.location.hash, '');
    w.location.hash = '#sleep'; await tick(); assert(!$('page-control').hidden);
    w.__ux.logout();
  });
  console.log(JSON.stringify({passed: checks.length, checks, live_backend: false, live_browser: false}, null, 2));
})().catch(error => {console.error(error); process.exitCode = 1;}).finally(() => w.close());
