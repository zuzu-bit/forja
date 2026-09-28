// Investigation helper (not part of CI): renders THE SITE (server/insights.html + the concatenated app.js)
// in headless Chromium against the synthetic ux-fixture, and saves one screenshot per page, desktop + phone.
// Usage: node scripts/site-shots/shoot.cjs <out-dir> [fixture|reality]
//   fixture = the rich synthetic account used by ux-ui-test.cjs
//   reality = an approximation of what a 4.3 app account sends today (see site-ia.md, section "What the 4.3 app feeds")
// Nothing is deployed and no live backend is contacted: every /v2 and /insights/api call is answered in-page by ux-fixture.
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.FORJA_PLAYWRIGHT || '/opt/node22/lib/node_modules/playwright');
const {server, createFixture, installMockFetch, clientSource} = require('../ux-fixture.cjs');

const out = path.resolve(process.argv[2] || 'site-shots');
const mode = process.argv[3] || 'fixture';
fs.mkdirSync(out, {recursive: true});
const read = file => fs.readFileSync(path.join(server, file), 'utf8');

// 4.3 reality (inference from app code): contract session (location + app usage) + gallery copies + protocol-4
// organizer device; no site friends/phones/sleep reports/campaigns; explore "Și pe site" off by default.
function realityPatch(fixture) {
  const now = Date.now();
  const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
  fixture.state.sessions = [{session_id: id(1), created_at: now - 300000, expires_at: now + 86000000, bytes: 184000, consent: {location: true, app_usage: true, photos: true}, items: [], observations: [], data: true}];
  fixture.social.friends = []; fixture.social.groups = []; fixture.social.me.places = []; fixture.social.me.partner = null;
  fixture.social.me.session = null; fixture.social.me.location = null; fixture.social.me.explored = []; fixture.social.me.discoverable = true;
  fixture.phones = [];
  fixture.cleanup.devices = [{id: id(20), label: 'SM-S911B', last_seen: now, protocol: 4, revision: 3,
    grant: {id: id(10), enabled: true, photos: true, files: true, organize: true, sources: [{id: id(30), source: 'files', label: 'Documents'}]},
    schedule: {enabled: false, photos: true, files: false, wifi_only: true, count: 25, timezone: 'Europe/Bucharest', times: [], days: []}}];
  fixture.organizer.jobs = [{id: id(40), device: id(20), source: 'files', scope: {folder: '', recursive: true}, destination: 'FORJA', mode: 'local', auto_apply: true,
    revision: 4, state: 'complete', command: {status: 'complete', count: 50}, counters: {total: 4, pending: 0, uploaded: 4, moved: 4, needs_review: 0, failed_retryable: 0, copied_pending_removal: 0},
    created_at: now - 900000, updated_at: now - 600000}];
  fixture.recovery = [{id: id(20), name: 'SM-S911B', enabled: true, seen_at: now - 60000, online: true, status: 'ready', command: null, position: null}];
  fixture.state.journals.sleep.records = fixture.state.journals.sleep.records.slice(0, 2);
  // The signed contract turns on explore "Și pe site" (CollectionSettings.enableAll): 150 m cells + places arrive.
  const cell = (lat, lon) => ({type: 'Feature', properties: {id: lat + ':' + lon}, geometry: {type: 'Polygon', coordinates: [[[lon, lat], [lon + 0.0019, lat], [lon + 0.0019, lat + 0.00135], [lon, lat + 0.00135], [lon, lat]]]}});
  fixture.explore = {owner: 'self', grid_m: 150, cells: {type: 'FeatureCollection', features: [cell(44.4132, 26.0938), cell(44.41455, 26.0938), cell(44.4132, 26.0957)]},
    places: [{id: 'p1', name: 'Acasă', lat: 44.4139, lon: 26.0947, stars: 0, note: '', stay_ms: 36000000}], next_cursor: null, updated_at: now};
}

(async () => {
  const browser = await chromium.launch({proxy: process.env.HTTPS_PROXY ? {server: process.env.HTTPS_PROXY} : undefined});
  const assets = {
    '/insights/app.js': [clientSource(), 'text/javascript'],
    '/insights/leaflet.js': [read('vendor/leaflet-1.9.4.js.txt'), 'text/javascript'],
    '/insights/leaflet.css': [read('vendor/leaflet-1.9.4.css.txt'), 'text/css'],
    '/insights/maplibre.js': [read('vendor/maplibre-5.10.0.js.txt'), 'text/javascript'],
    '/insights/maplibre.css': [read('vendor/maplibre-5.10.0.css.txt'), 'text/css'],
    '/insights/map-renderer.js': [read('map-renderer.js.txt'), 'text/javascript']
  };
  const shots = [], metrics = {};
  for (const [label, viewport] of [['desktop', {width: 1440, height: 900}], ['phone', {width: 390, height: 844}]]) {
    const context = await browser.newContext({viewport, deviceScaleFactor: 1, ignoreHTTPSErrors: true, locale: 'ro-RO', timezoneId: 'Europe/Bucharest'});
    const page = await context.newPage();
    const logs = [];
    page.on('pageerror', e => logs.push('pageerror: ' + e.message));
    await page.route('**/*', async route => {
      const url = new URL(route.request().url());
      if (url.hostname === 'forja.test') {
        if (['/', '/insights', '/insights/'].includes(url.pathname)) return route.fulfill({body: read('insights.html'), contentType: 'text/html; charset=utf-8'});
        const asset = assets[url.pathname];
        if (asset) return route.fulfill({body: asset[0], contentType: asset[1] + '; charset=utf-8'});
        return route.fulfill({status: 404, body: 'not found'});
      }
      if (url.hostname.endsWith('openstreetmap.org') || url.hostname === 'tiles.openfreemap.org') {
        try { return await route.continue(); } catch { return route.abort(); }
      }
      return route.abort();
    });
    await page.addInitScript(`window.__fixture=(${createFixture.toString()})();(${mode === 'reality' ? realityPatch.toString() : '()=>{}'})(window.__fixture);(${installMockFetch.toString()})(window, window.__fixture);`);
    await page.goto('https://forja.test/insights');
    await page.waitForTimeout(400);
    await page.screenshot({path: path.join(out, `${mode}-${label}-00-login.png`), fullPage: true});
    await page.fill('#email', 'alex@example.test'); await page.fill('#password', 'local-demo-only');
    await page.click('#login-button');
    await page.waitForSelector('#app:not([hidden])');
    await page.waitForTimeout(800);
    const pages = [['overview', 'Acasă'], ['social', 'Hartă'], ['files', 'Fișiere'], ['ai', 'Pentru tine'], ['data', 'Jurnale'], ['control', 'Somn și audio'], ['content', 'Campanii']];
    for (const [i, [name]] of pages.entries()) {
      await page.evaluate(n => { const more = document.getElementById('nav-more'); if (['data', 'control', 'content'].includes(n)) more.open = true; }, name);
      await page.click(`.nav[data-page="${name}"]`);
      await page.waitForTimeout(name === 'social' ? 2500 : 900);
      await page.evaluate(() => { document.getElementById('nav-more').open = false; window.scrollTo(0, 0); });
      const file = `${mode}-${label}-${String(i + 1).padStart(2, '0')}-${name}.png`;
      await page.screenshot({path: path.join(out, file), fullPage: true});
      shots.push(file);
      // Visible density of the rendered page: words, controls and collapsed disclosures (static + client-rendered).
      metrics[`${label}:${name}`] = await page.evaluate(n => {
        const root = document.getElementById('page-' + n), visible = e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
        const count = sel => [...root.querySelectorAll(sel)].filter(visible).length;
        return {words: (root.innerText || '').split(/\s+/).filter(Boolean).length, buttons: count('button'), inputs: count('input,select,textarea'),
          details: count('details'), height: Math.round(document.documentElement.scrollHeight)};
      }, name);
    }
    await page.goto('https://forja.test/insights#privacy');
    await page.waitForTimeout(500);
    await page.screenshot({path: path.join(out, `${mode}-${label}-08-privacy.png`), fullPage: true});
    fs.writeFileSync(path.join(out, `${mode}-${label}-console.txt`), logs.join('\n'));
    await context.close();
  }
  await browser.close();
  fs.writeFileSync(path.join(out, `${mode}-metrics.json`), JSON.stringify(metrics, null, 1));
  console.log(JSON.stringify({out, mode, shots, metrics}, null, 1));
})().catch(e => { console.error(e); process.exitCode = 1; });
