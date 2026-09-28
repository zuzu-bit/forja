// Investigation helper (not part of CI): renders the site's "Hartă" page (server/insights.html + app.js) in headless
// Chromium with an account shaped like what a 4.3 phone actually sends: explore cells + places via explore/sync,
// NO site-graph friends (the app's friends live in Firestore), no browser journey, a lost-phone device without position.
// Usage: node scripts/site-shots/map-shot.cjs <out-dir>
// Nothing is deployed; every /v2 call is answered in-page by ux-fixture. Only map tiles go to the network (via proxy).
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.FORJA_PLAYWRIGHT || '/opt/node22/lib/node_modules/playwright');
const {server, createFixture, installMockFetch, clientSource} = require('../ux-fixture.cjs');

const out = path.resolve(process.argv[2] || 'map-shots');
fs.mkdirSync(out, {recursive: true});
const read = file => fs.readFileSync(path.join(server, file), 'utf8');

// Same 150 m Web-Mercator grid as ExploreGrid (app/src/main/java/com/forja/app/core/explore/ExploreTracker.kt:37-57).
function appPatch(fixture) {
  const now = Date.now(), R = 6378137, S = 150, rad = Math.PI / 180;
  const cellOf = (lat, lng) => [Math.floor(R * lng * rad / S), Math.floor(R * Math.log(Math.tan(Math.PI / 4 + lat * rad / 2)) / S)];
  const bounds = (x, y) => ({min_lng: x * S / R / rad, max_lng: (x + 1) * S / R / rad,
    min_lat: (2 * Math.atan(Math.exp(y * S / R)) - Math.PI / 2) / rad, max_lat: (2 * Math.atan(Math.exp((y + 1) * S / R)) - Math.PI / 2) / rad});
  const seen = new Map();
  // A few walks / runs / rides around Bucharest (Herăstrău → Piața Victoriei → Cișmigiu → Unirii).
  const legs = [[44.4700, 26.0820, 44.4530, 26.0860], [44.4530, 26.0860, 44.4380, 26.0930], [44.4380, 26.0930, 44.4270, 26.1020],
    [44.4700, 26.0820, 44.4760, 26.0950], [44.4760, 26.0950, 44.4640, 26.1050]];
  for (const [a, b, c, d] of legs) for (let t = 0; t <= 1; t += 0.01) {
    const [x, y] = cellOf(a + (c - a) * t, b + (d - b) * t); const id = x + '_' + y;
    if (!seen.has(id)) { const bb = bounds(x, y); seen.set(id, {id, ...bb, first_at: now - 86400000 * 3, last_at: now - 3600000, visits: 1 + (x % 3)}); }
  }
  const cells = [...seen.values()].map(c => ({type: 'Feature', geometry: {type: 'Polygon', coordinates: [[[c.min_lng, c.min_lat], [c.max_lng, c.min_lat], [c.max_lng, c.max_lat], [c.min_lng, c.max_lat], [c.min_lng, c.min_lat]]]},
    properties: {id: c.id, first_at: c.first_at, last_at: c.last_at, visits: c.visits, kind: 'explored'}}));
  fixture.explore = {owner: 'demo-owner', grid_m: 150, cells: {type: 'FeatureCollection', features: cells}, next_cursor: null, updated_at: now - 1800000,
    places: [
      {id: 'p1', lat: 44.4387, lon: 26.0936, lng: 26.0936, name: 'Acasă', stars: 5, note: '', recommended: false, stay_ms: 5 * 3600000 * 12, first_at: now - 86400000 * 20, last_at: now - 3600000, updated_at: now - 3600000},
      {id: 'p2', lat: 44.4705, lon: 26.0825, lng: 26.0825, name: 'Birou', stars: 3, note: 'cafea bună', recommended: false, stay_ms: 5 * 3600000 * 4, first_at: now - 86400000 * 10, last_at: now - 86400000, updated_at: now - 86400000},
      {id: 'p3', lat: 44.4290, lon: 26.1010, lng: 26.1010, name: '', stars: 0, note: '', recommended: false, stay_ms: 5.2 * 3600000, first_at: now - 86400000 * 2, last_at: now - 86400000 * 2, updated_at: now - 86400000 * 2}]};
  // What the site graph holds for a 4.3 account: nobody shares a /v2/social session, the app never calls /location or /profile.
  fixture.social.friends = [{id: 'contact-1', name: 'Prieten FORJA', mode: null, location: null, checkin: null}];
  fixture.social.groups = []; fixture.social.me.places = []; fixture.social.me.partner = null; fixture.social.me.session = null;
  fixture.social.me.location = null; fixture.social.me.explored = []; fixture.social.me.discoverable = true; fixture.social.me.name = 'Prieten FORJA';
  fixture.recovery = [{id: '00000000-0000-4000-8000-000000000020', name: 'SM-S911B', enabled: true, seen_at: now - 60000, online: true, status: 'ready', command: null, position: null}];
}

(async () => {
  console.error('launch');
  const browser = await chromium.launch({proxy: process.env.HTTPS_PROXY ? {server: process.env.HTTPS_PROXY} : undefined});
  const assets = {
    // app.js is a module: expose the two map objects to the harness only (the repo file is untouched).
    '/insights/app.js': [clientSource() + '\nwindow.__dbg={get socialUI(){return socialUI;},get journeyUI(){return journeyUI;}};', 'text/javascript'],
    '/insights/leaflet.js': [read('vendor/leaflet-1.9.4.js.txt'), 'text/javascript'],
    '/insights/leaflet.css': [read('vendor/leaflet-1.9.4.css.txt'), 'text/css'],
    '/insights/maplibre.js': [read('vendor/maplibre-5.10.0.js.txt'), 'text/javascript'],
    '/insights/maplibre.css': [read('vendor/maplibre-5.10.0.css.txt'), 'text/css'],
    '/insights/map-renderer.js': [read('map-renderer.js.txt'), 'text/javascript']
  };
  const report = {out, shots: [], tiles: {ok: 0, failed: 0}, logs: []};
  const context = await browser.newContext({viewport: {width: 1440, height: 900}, deviceScaleFactor: 1, ignoreHTTPSErrors: true, locale: 'ro-RO', timezoneId: 'Europe/Bucharest'});
  const page = await context.newPage();
  page.on('pageerror', e => report.logs.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') report.logs.push('console: ' + m.text().slice(0, 300)); });
  await page.route('**/*', async route => {
    const url = new URL(route.request().url());
    if (url.hostname === 'forja.test') {
      if (['/', '/insights', '/insights/'].includes(url.pathname)) return route.fulfill({body: read('insights.html'), contentType: 'text/html; charset=utf-8'});
      const asset = assets[url.pathname];
      if (asset) return route.fulfill({body: asset[0], contentType: asset[1] + '; charset=utf-8'});
      return route.fulfill({status: 404, body: 'not found'});
    }
    if (url.hostname.endsWith('openstreetmap.org') || url.hostname === 'tiles.openfreemap.org') {
      // The investigation container cannot reach tile hosts (egress policy): abort at once, overlays still render.
      report.tiles.failed++; return route.abort();
    }
    return route.abort();
  });
  await page.addInitScript(`window.__fixture=(${createFixture.toString()})();(${appPatch.toString()})(window.__fixture);(${installMockFetch.toString()})(window, window.__fixture);`);
  console.error('goto');
  await page.goto('https://forja.test/insights', {waitUntil: 'domcontentloaded'});
  console.error('loaded');
  await page.fill('#email', 'alex@example.test'); await page.fill('#password', 'local-demo-only');
  await page.click('#login-button');
  await page.waitForSelector('#app:not([hidden])');
  console.error('logged in');
  await page.click('.nav[data-page="social"]');
  await page.waitForTimeout(4000);
  const shot = async name => { console.error('shot', name); const file = 'map-' + name + '.png'; await page.screenshot({path: path.join(out, file), fullPage: false}); report.shots.push(file); };
  await shot('01-first-view');
  report.first = await page.evaluate(() => ({zoom: __dbg.socialUI.map.getZoom(), center: __dbg.socialUI.map.getCenter(), cells: __dbg.journeyUI.explore?.cells.features.length,
    summary: document.getElementById('social-map-summary').textContent, state: document.getElementById('social-state').textContent,
    explored: document.getElementById('social-explored').textContent, rules: document.getElementById('journey-rules').textContent,
    fitDisabled: document.getElementById('social-map-fit').disabled}));
  // What a user has to do by hand: zoom to their own territory.
  await page.evaluate(() => __dbg.socialUI.map.setView([44.452, 26.09], 14, {animate: false}));
  await page.waitForTimeout(3500);
  await shot('02-zoomed-2d');
  await page.click('#map-dimension');
  await page.waitForTimeout(6000);
  await shot('03-after-3d-click');
  report.after3d = await page.evaluate(() => ({threeD: __dbg.journeyUI.threeD, vectorReady: __dbg.journeyUI.vectorReady, status: document.getElementById('map-render-status').textContent,
    error: document.getElementById('map-render-status').dataset.error || null,
}));
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({path: path.join(out, 'map-04-full-page.png'), fullPage: true}); report.shots.push('map-04-full-page.png');
  await context.close();
  await browser.close();
  fs.writeFileSync(path.join(out, 'map-report.json'), JSON.stringify(report, null, 1));
  console.log(JSON.stringify(report, null, 1));
})().catch(e => { console.error(e); process.exitCode = 1; });
