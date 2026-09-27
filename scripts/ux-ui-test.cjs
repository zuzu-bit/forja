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
w.eval(clientSource() + '\nwindow.__ux = {page, logout, refresh, refreshCleanup, refreshOrganizerJobs, openOrganizerJobItems, sendOrganizerCommand, approveOrganizerItems, deleteOrganizerJob, organizerV4, refreshFiles, moveVaultFile, vault, refreshSocial, socialChat, expireSocialMarkers, socialUI, refreshRecovery, recoveryUI, refreshPhones, refreshSleepReports, openSleepReport, localTimeInput, phoneWindows, phoneDurations, sleepUI, openVisibility, journeyShared, journeyUI, startJourney, stopJourney, journeyFlush};');
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
  await check('v4 organizes the whole gallery with an explicit grant and a queued phone command', async () => {
    const d=fixture.cleanup.devices[0];d.protocol=4;d.grant.sources=[{id:randomUUID(),source:'files',folder:'Documente',label:'Documentele mele'}];
    w.__ux.page('files');await tick();await w.__ux.refreshCleanup(true);await tick();
    assert(!$('organizer-workspace').hidden);assert.equal($('organizer-requests').children.length,0,'v4 must not duplicate the legacy form');
    $('organizer-workspace').querySelector('[data-organizer-count="0"]').click();
    $('organizer-start').click();await tick();await tick();
    const create=fixture.calls.findLast(c=>c.route.endsWith('/jobs')&&c.method==='POST');
    assert.equal(create.body.source,'photos');assert.equal(create.body.scope.folder,'');assert.equal(create.body.auto_apply,true);assert.equal(create.body.ai_consent,false);
    const command=callFor('/command','POST');assert.equal(command.body.count,0);assert.equal(command.body.action,'continue');
    assert.match($('organizer-jobs').textContent,/Așteaptă telefonul/);assert(!$('organizer-jobs').textContent.includes('Gata'));
    assert(!$('organizer-jobs').querySelector('progress'),'Unknown/empty inventory must not show invented progress');
    assert.equal([...$('organizer-jobs').querySelectorAll('button')].filter(b=>/^Continuă$|^Următoarele/.test(b.textContent)).length,0);
  });
  await check('v4 document selection preserves the authorized tree and exact next-N count', async () => {
    $('organizer-workspace').querySelector('[data-organizer-source="files"]').click();
    assert.equal($('organizer-source-id').selectedOptions[0].textContent,'Documentele mele');
    $('organizer-scope').value='folder';$('organizer-scope').dispatchEvent(new w.Event('change'));
    $('organizer-folder').value='Facturi';$('organizer-folder').dispatchEvent(new w.Event('input'));
    $('organizer-workspace').querySelector('[data-organizer-count="-1"]').click();$('organizer-count').value='75';$('organizer-count').dispatchEvent(new w.Event('input'));
    $('organizer-start').click();await tick();await tick();
    const create=fixture.calls.findLast(c=>c.route.endsWith('/jobs')&&c.method==='POST');
    assert.equal(create.body.source,'files');assert.equal(create.body.source_id,fixture.cleanup.devices[0].grant.sources[0].id);assert.equal(create.body.scope.folder,'Facturi');
    assert.equal(callFor('/command','POST').body.count,75);
    const before=fixture.calls.filter(c=>c.route.endsWith('/jobs')&&c.method==='POST').length;
    $('organizer-mode').value='online';$('organizer-mode').dispatchEvent(new w.Event('change'));$('organizer-start').click();await tick();
    assert.equal(fixture.calls.filter(c=>c.route.endsWith('/jobs')&&c.method==='POST').length,before,'Online content consent is required');
    $('organizer-mode').value='local';$('organizer-mode').dispatchEvent(new w.Event('change'));
  });
  await check('v4 progress and verified copies stay distinct from moved originals', async () => {
    const job=fixture.organizer.jobs[0];job.state='partial';job.inventory_unavailable=2;job.counters={total:8,moved:2,uploaded:1,needs_review:1,failed_retryable:1,copied_pending_removal:1};
    const id=randomUUID(),file={...fixture.files.items[0],id:randomUUID(),expires_at:Date.now()+86400000};
    fixture.organizer.items[job.id]={items:[{id,name:'<img onerror=bad()> factură',state:'uploaded',destination:'FORJA/Facturi',reason:'<script>bad()</script>',received:true,file,file_id:file.id},{id:randomUUID(),name:'Original mutat',state:'moved',destination:'FORJA/Poze',received:false,file:null,file_id:randomUUID()}],next_cursor:null,total:2};
    await w.__ux.refreshOrganizerJobs();const card=$('organizer-jobs').querySelector('[data-organizer-job="'+job.id+'"]');
    assert.match(card.textContent,/2 din 8 mutate/);assert.match(card.textContent,/2 inaccesibile pe telefon/);assert.match(card.textContent,/originalele încă așteaptă/);
    const detail=card.querySelector('.organizer-job-detail');detail.open=true;await tick();
    assert.equal(detail.querySelectorAll('img,script').length,0);assert.equal([...detail.querySelectorAll('button')].filter(b=>b.textContent==='Vezi copia').length,1);
    assert.match(detail.textContent,/Progresul originalului este păstrat/);
    const checkbox=detail.querySelector('input[type=checkbox]');checkbox.checked=true;checkbox.dispatchEvent(new w.Event('change'));
    const before=fixture.calls.filter(c=>c.route.endsWith('/approve')).length;
    await clickText(detail,/^Aprobă mutările$/);assert.equal(fixture.calls.filter(c=>c.route.endsWith('/approve')).length,before,'Move approval is separate');
    const confirm=[...detail.querySelectorAll('input[type=checkbox]')].at(-1);confirm.checked=true;await clickText(detail,/^Aprobă mutările$/);
    assert.equal(callFor('/approve','POST').body.items[0].id,id);assert.equal(callFor('/approve','POST').body.confirm,true);
  });
  await check('v4 command response defeats an older overlapping poll', async () => {
    const job=fixture.organizer.jobs[0];job.state='paused';await w.__ux.refreshOrganizerJobs();
    const old=structuredClone(fixture.organizer.jobs),fetch=w.fetch;let resolve;
    w.fetch=(url,options)=>String(url).endsWith('/jobs')&&(!options?.method||options.method==='GET')?new Promise(r=>resolve=r):fetch(url,options);
    try{const poll=w.__ux.refreshOrganizerJobs();await tick();await w.__ux.sendOrganizerCommand(w.__ux.organizerV4.jobs.get(job.id),'continue',50);resolve(Response.json({jobs:old}));await poll;
      assert.equal(w.__ux.organizerV4.jobs.get(job.id).state,'awaiting_phone');assert.equal(callFor('/command','POST').body.count,50);
    }finally{w.fetch=fetch;}
  });
  await check('v4 lost command responses retry the same request and original revision', async () => {
    const job=fixture.organizer.jobs[0];job.state='paused';await w.__ux.refreshOrganizerJobs();
    const fetch=w.fetch;let failed=false;w.fetch=async(url,options)=>{const response=await fetch(url,options);if(String(url).endsWith('/command')&&!failed){failed=true;throw new Error('Connection lost after server acceptance');}return response;};
    try{await assert.rejects(w.__ux.sendOrganizerCommand(w.__ux.organizerV4.jobs.get(job.id),'continue',100));await w.__ux.refreshOrganizerJobs();await w.__ux.sendOrganizerCommand(w.__ux.organizerV4.jobs.get(job.id),'continue',100);
      const requests=fixture.calls.filter(c=>c.route.endsWith('/command')&&c.body.count===100).slice(-2);assert.equal(requests.length,2);assert.deepEqual(requests[0].body,requests[1].body);
    }finally{w.fetch=fetch;}
  });
  await check('v4 completed finite batches offer the next N without pretending the job finished', async () => {
    const job=fixture.organizer.jobs[0];job.state='running';job.command={action:'continue',count:50,status:'complete',selected:50,finished:50};await w.__ux.refreshOrganizerJobs();
    const card=$('organizer-jobs').querySelector('[data-organizer-job="'+job.id+'"]'),choice=card.querySelector('select[aria-label="Mărimea următorului lot"]');assert(choice);
    choice.value='100';choice.dispatchEvent(new w.Event('change'));await clickText(card,/^Organizează următorul lot$/);
    assert.equal(callFor('/command','POST').body.count,100);assert.match(card.textContent,/Așteaptă telefonul/);
  });
  await check('local analyzed items permit explicit approval without claiming a cloud copy', async () => {
    const job=fixture.organizer.jobs[0];job.mode='local';job.state='partial';job.updated_at=Date.now();const id=randomUUID();
    fixture.organizer.items[job.id]={items:[{id,version:'local-1',sha256:'a'.repeat(64),name:'Document local',state:'analyzed',destination:'FORJA/Facturi',received:false,file:null}],total:1,next_cursor:null};
    await w.__ux.refreshOrganizerJobs();const body=$('organizer-jobs').querySelector('[data-organizer-job="'+job.id+'"] .organizer-items');await w.__ux.openOrganizerJobItems(job.id,body);
    assert.equal([...body.querySelectorAll('button')].filter(b=>b.textContent==='Vezi copia').length,0);const choice=body.querySelector('input[type=checkbox]');assert(!choice.disabled);choice.checked=true;choice.dispatchEvent(new w.Event('change'));
    const fetch=w.fetch;let lost=false;w.fetch=async(url,options)=>{const response=await fetch(url,options);if(String(url).endsWith('/approve')&&!lost){lost=true;throw Error('Lost approval response');}return response;};
    try{await assert.rejects(w.__ux.approveOrganizerItems(job.id,body,true));await w.__ux.refreshOrganizerJobs();await w.__ux.approveOrganizerItems(job.id,body,true);
      const requests=fixture.calls.filter(c=>c.route.endsWith('/approve')).slice(-2);assert.deepEqual(requests[0].body,requests[1].body);
    }finally{w.fetch=fetch;}
  });
  await check('original moves require confirmation and retain queued status until a phone receipt', async () => {
    const job=fixture.organizer.jobs[0],item=fixture.files.items[0];Object.assign(item,{device_id:job.device,organizer_job:job.id,organizer_item:randomUUID(),folder:'Documente'});await w.__ux.refreshFiles();
    const oldPrompt=w.prompt,oldConfirm=w.confirm;w.prompt=()=> 'FORJA/Facturi';w.confirm=()=>false;
    try{const before=fixture.calls.length;await w.__ux.moveVaultFile(w.__ux.vault.rows.get(item.id));assert(!fixture.calls.slice(before).some(c=>c.method==='PATCH'));
      w.confirm=()=>true;await w.__ux.moveVaultFile(w.__ux.vault.rows.get(item.id));let card=$('vault-grid').querySelector('[data-file="'+item.id+'"]');assert.equal(card.querySelector('.vault-folder').textContent,'Documente');assert.match(card.textContent,/Mutare cerută → FORJA\/Facturi/);
      await w.__ux.refreshFiles(false,true);assert.match(card.textContent,/așteaptă telefonul/);
      item.folder='FORJA/Facturi';delete item.pending_folder;item.sync_state='applied';await w.__ux.refreshFiles(false,true);assert.equal(card.querySelector('.vault-folder').textContent,'FORJA/Facturi');assert(card.querySelector('.vault-sync').hidden);assert(card.querySelector('[data-vault-move]').disabled);
    }finally{w.prompt=oldPrompt;w.confirm=oldConfirm;}
  });
  await check('lost original-move responses preserve their approval request and revision', async () => {
    const item=fixture.files.items[0],job=fixture.organizer.jobs[0],fetch=w.fetch,oldPrompt=w.prompt;w.prompt=()=> 'FORJA/Arhivă';let lost=false;
    w.fetch=async(url,options)=>{const response=await fetch(url,options);if(options?.method==='PATCH'&&!lost){lost=true;job.revision++;throw Error('Lost move response');}return response;};
    try{await assert.rejects(w.__ux.moveVaultFile(w.__ux.vault.rows.get(item.id)));await w.__ux.refreshFiles(false,true);await w.__ux.moveVaultFile(w.__ux.vault.rows.get(item.id));const requests=fixture.calls.filter(c=>c.route.endsWith('/'+item.id)&&c.method==='PATCH').slice(-2);assert.deepEqual(requests[0].body,requests[1].body);}
    finally{w.fetch=fetch;w.prompt=oldPrompt;}
  });
  await check('a stale gallery poll cannot undo a newly queued original move', async () => {
    const item=fixture.files.items[0],fetch=w.fetch,old=structuredClone(fixture.files),oldPrompt=w.prompt;let resolve;w.prompt=()=> 'FORJA/Documente';
    w.fetch=(url,options)=>String(url).startsWith('/v2/files?')?new Promise(r=>resolve=r):fetch(url,options);
    try{const poll=w.__ux.refreshFiles(false,true);await tick();await w.__ux.moveVaultFile(w.__ux.vault.rows.get(item.id));resolve(Response.json(old));await poll;assert.equal(w.__ux.vault.rows.get(item.id).pending_folder,'FORJA/Documente');assert.match($('vault-grid').querySelector('[data-file="'+item.id+'"] .vault-sync').textContent,/FORJA\/Documente/);}
    finally{w.fetch=fetch;w.prompt=oldPrompt;}
  });
  await check('AI evidence is escaped and review-only deletion never selects or deletes originals', async () => {
    const job=fixture.organizer.jobs[0],entry=fixture.organizer.items[job.id].items[0];entry.analysis={coverage:{status:'partial',pages_processed:1,pages_total:4},evidence:[{id:'e1',kind:'text',quote:'<img src=x onerror=bad()>',page:1}],deletion:{suggested:true,basis:'low_information',reason:'Scanare fără informație lizibilă.',evidence_ids:['e1'],requires_confirmation:true,review_only:true}};job.updated_at=Date.now();
    await w.__ux.refreshOrganizerJobs();const body=$('organizer-jobs').querySelector('[data-organizer-job="'+job.id+'"] .organizer-items');await w.__ux.openOrganizerJobItems(job.id,body);
    assert.match(body.textContent,/1 din 4 pagini analizate/);assert.match(body.textContent,/De verificat pentru ștergere/);assert.equal(body.querySelectorAll('img,script').length,0);assert.equal([...body.querySelectorAll('button')].filter(b=>/^Șterge original/.test(b.textContent)).length,0);
  });
  await check('forgetting a job preserves its copies and removes only returned progress', async () => {
    const job=fixture.organizer.jobs.at(-1),count=fixture.files.items.length;await w.__ux.deleteOrganizerJob(job.id);assert.equal(fixture.files.items.length,count);assert(!w.__ux.organizerV4.jobs.has(job.id));assert(!$('organizer-jobs').querySelector('[data-organizer-job="'+job.id+'"]'));
  });
  await check('visibility scopes remain independent per friend and need explicit consent', async () => {
    w.__ux.page('social');await tick();await w.__ux.openVisibility();const form=$('social-visibility-form'),save=[...form.querySelectorAll('button')].find(b=>b.textContent==='Salvează');assert(save.disabled);
    const first=[...form.querySelector('fieldset').querySelectorAll('input')];assert(first[1].disabled);first[0].checked=true;first[0].dispatchEvent(new w.Event('change'));assert(!first[1].disabled);first[1].checked=true;first[2].checked=true;
    const second=[...form.querySelectorAll('fieldset')][1].querySelectorAll('input');second[2].checked=true;
    const consent=[...form.querySelectorAll(':scope>label input')].at(-1);consent.checked=true;consent.dispatchEvent(new w.Event('change'));await clickText(form,/^Salvează$/);
    const request=callFor('/visibility','POST');assert.deepEqual(request.body.grants[0],{id:fixture.social.friends[0].id,current:true,ghost:true,history:true});assert.deepEqual(request.body.grants[1],{id:fixture.social.friends[1].id,current:false,ghost:false,history:true});assert.equal(request.body.consent,true);
    await w.__ux.openVisibility();await clickText($('social-visibility-form'),/^Oprește toate partajările$/);assert(callFor('/social/session','DELETE'));assert.equal(fixture.visibility.grants.length,0);
  });
  await check('stale visibility consent cannot restore sharing after stop-all', async () => {
    fixture.visibility.grants=[{id:fixture.social.friends[0].id,current:true,ghost:true,history:true}];await w.__ux.openVisibility();const form=$('social-visibility-form');const consent=[...form.querySelectorAll(':scope>label input')].at(-1);consent.checked=true;consent.dispatchEvent(new w.Event('change'));
    fixture.visibility={ghost:true,grants:[],updated_at:Date.now(),revision:fixture.visibility.revision+1};const before=fixture.calls.filter(c=>c.route.endsWith('/visibility')&&c.method==='POST').length;
    await clickText(form,/^Salvează$/);await tick();assert($('visibility-dialog').open);assert([...form.querySelectorAll('fieldset input')].every(input=>!input.checked));assert(![...form.querySelectorAll(':scope>label input')].at(-1).checked);assert([...form.querySelectorAll('button')].find(b=>b.textContent==='Salvează').disabled);
    assert.equal(fixture.calls.filter(c=>c.route.endsWith('/visibility')&&c.method==='POST').length,before+1,'Never retry previously granted scopes automatically');$('visibility-dialog').close();
  });
  await check('exploration starts only after consent and remains separate from social sharing', async () => {
    let watched=0,cleared=0;Object.defineProperty(w.navigator,'geolocation',{configurable:true,value:{watchPosition(){watched++;return 42;},clearWatch(){cleared++;}}});
    $('journey-start').click();assert($('journey-consent-dialog').open);assert($('journey-confirm').disabled);assert.equal(watched,0);
    $('journey-consent').checked=true;$('journey-consent').dispatchEvent(new w.Event('change'));$('journey-confirm').click();await tick();assert.equal(watched,1);assert.equal(callFor('/journey/session','POST').body.consent,true);assert.equal(fixture.visibility.grants.length,0);
    await w.__ux.stopJourney();assert(cleared>0);assert(callFor('/journey/session','DELETE'));assert.equal(w.__ux.journeyUI.recording,null);
  });
  await check('exploration queue survives history switching and stop waits for the active upload', async () => {
    await w.__ux.startJourney();const id=w.__ux.journeyUI.recording;assert(id);w.__ux.journeyUI.queue.push({id:randomUUID(),session:id,points:[{at:Date.now(),lat:44.4,lon:26.1,accuracy:10,speed:0}]});
    const fetch=w.fetch;let resolve;w.fetch=(url,options)=>String(url).endsWith('/journey/samples')?new Promise(r=>resolve=r):fetch(url,options);
    try{const flush=w.__ux.journeyFlush();await tick();await w.__ux.journeyShared(fixture.social.friends[0].id);assert(w.__ux.journeyUI.sending);const before=fixture.calls.filter(c=>c.route.endsWith('/journey/session')&&c.method==='DELETE').length;const stop=w.__ux.stopJourney();await tick();assert.equal(fixture.calls.filter(c=>c.route.endsWith('/journey/session')&&c.method==='DELETE').length,before);
      resolve(Response.json({ok:true}));await flush;await stop;assert.equal(w.__ux.journeyUI.queue.length,0);assert.equal(w.__ux.journeyUI.sending,false);assert.equal(w.__ux.journeyUI.recording,null);assert.equal(fixture.calls.filter(c=>c.route.endsWith('/journey/session')&&c.method==='DELETE').length,before+1);
    }finally{w.fetch=fetch;await w.__ux.journeyShared(null);}
  });
  await check('denied shared history clears prior map data and place details', async () => {
    const visit={id:randomUUID(),name:'Loc privat',lat:44.4,lon:26.1,observed_ms:19000000};fixture.journey.visits=[visit];await w.__ux.journeyShared(fixture.social.friends[0].id);assert.match($('journey-visits').textContent,/Loc privat/);
    await clickText($('journey-visits'),/^Vezi locul$/);assert.match($('journey-selection').textContent,/Loc privat/);
    const fetch=w.fetch;w.fetch=(url,options)=>String(url).includes('/journey/state')?Promise.resolve(Response.json({error:'Istoric indisponibil.'},{status:403})):fetch(url,options);
    try{await w.__ux.journeyShared(fixture.social.friends[1].id);assert.equal(w.__ux.journeyUI.data,null);assert.equal($('journey-selection').textContent,'');assert.equal($('journey-visits').textContent,'');}
    finally{w.fetch=fetch;fixture.journey.visits=[];await w.__ux.journeyShared(null);}
  });
  await check('logout clears private content and pending social data', async () => {
    w.__ux.page('social'); await tick();
    const fetch = w.fetch; let resolve, resolveSleep, resolveJobs;
    w.fetch = (url, options) => String(url).endsWith('/v2/recovery/devices') ? new Promise(r => {resolve = r;}) : String(url).endsWith('/v2/sleep/sessions') ? new Promise(r => {resolveSleep = r;}) : String(url).endsWith('/jobs') ? new Promise(r=>resolveJobs=r) : fetch(url, options);
    const pending = w.__ux.refreshRecovery(), pendingSleep = w.__ux.refreshSleepReports(), pendingJobs=w.__ux.refreshOrganizerJobs(); await tick(); w.__ux.logout();
    resolve(Response.json({devices: fixture.recovery})); await pending;
    resolveSleep(Response.json({sessions:[{id:randomUUID(),state:'complete',started_at:Date.now()}]})); await pendingSleep;
    resolveJobs(Response.json({jobs:fixture.organizer.jobs}));await pendingJobs;
    w.fetch = fetch;
    assert($('app').hidden); assert(!$('login').hidden);
    assert.equal(w.__ux.socialUI.data, null); assert.equal(w.__ux.recoveryUI.data, null);
    assert.equal($('social-friends').childNodes.length, 0); assert.equal($('recovery-devices').childNodes.length, 0);
    assert.equal($('vault-grid').childNodes.length, 0);
    assert.equal($('sleep-reports').childNodes.length, 0); assert.equal(w.__ux.sleepUI.owner, null);
    assert.equal($('organizer-jobs').childNodes.length,0);assert.equal(w.__ux.organizerV4.device,null);
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
