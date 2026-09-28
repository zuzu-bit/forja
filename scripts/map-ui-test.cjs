// Run after npm ci --prefix server: node scripts/map-ui-test.cjs
// The site map end to end in jsdom: the real client (site-*.js.txt) + the real renderer (map-renderer.js.txt) over a MapLibre
// stand-in. Checks what Lana saw as "the map is not updated": one MapLibre map, ForjaStyle Night, fitted on her own data, data
// updates through setData only, 3D as pitch on the same map, tolerant of tile errors, polling only while visible, Găsire mode.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {server, createFixture, installMockFetch, clientSource} = require('./ux-fixture.cjs');
let JSDOM;
try { ({JSDOM} = require(process.env.FORJA_JSDOM || require.resolve('jsdom', {paths: [server]}))); }
catch { throw Error('Run npm ci --prefix server, or set FORJA_JSDOM to an installed jsdom package path.'); }
const html = fs.readFileSync(path.join(server, 'insights.html'), 'utf8');
const renderer = fs.readFileSync(path.join(server, 'map-renderer.js.txt'), 'utf8');
const tick = (ms = 20) => new Promise(resolve => setTimeout(resolve, ms));
async function until(fn, label, ms = 3000) { const start = Date.now(); while (Date.now() - start < ms) { if (fn()) return; await tick(10); } throw Error('Timed out: ' + label); }

function page({profile = 'rich', styleFails = false} = {}) {
  const dom = new JSDOM(html, {url: 'https://forja.test/insights', runScripts: 'outside-only', pretendToBeVisual: true});
  const w = dom.window, errors = [], maps = [];
  w.addEventListener('error', e => errors.push(e.error || e.message));
  w.addEventListener('unhandledrejection', e => errors.push(e.reason));
  w.IntersectionObserver = class { observe() {} unobserve() {} disconnect() {} };
  w.HTMLElement.prototype.scrollIntoView = () => {};
  w.scrollTo = () => {};
  w.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  w.HTMLDialogElement.prototype.close = function () { this.open = false; };
  w.crypto.randomUUID = randomUUID;
  w.HTMLCanvasElement.prototype.getContext = () => null; // jsdom fără canvas: pinii nu se desenează, restul hărții da
  w.AbortController = AbortController; w.Response = Response;
  w.URL.createObjectURL = () => 'blob:x'; w.URL.revokeObjectURL = () => {};
  w.matchMedia = q => ({matches: /reduce/.test(q) || /min-width: 1024px/.test(q), addEventListener() {}, removeEventListener() {}});
  class Map {
    constructor(options) {
      this.options = options; this.handlers = {}; this.sources = {}; this.paint = {}; this.layout = {}; this.images = {};
      this.style = ['background', 'water', 'park', 'building', 'building-3d', 'road_minor', 'label_city'].map(id => ({id, type: id === 'building-3d' ? 'fill-extrusion' : id === 'label_city' ? 'symbol' : 'fill'}));
      this.added = 0; this.canvas = {style: {}}; maps.push(this);
      setTimeout(() => styleFails ? this.fire('error', {error: Error('Failed to fetch style')}) : this.fire('load'), 5);
    }
    on(name, a, b) { const fn = typeof a === 'function' ? a : b; (this.handlers[name] ||= []).push(fn); return this; }
    fire(name, e = {}) { for (const fn of this.handlers[name] || []) fn(e); }
    getLayer(id) { return this.style.find(l => l.id === id); }
    getStyle() { return {layers: this.style}; }
    addLayer(l, before) { this.added++; const i = before ? this.style.findIndex(x => x.id === before) : -1; if (i >= 0) this.style.splice(i, 0, l); else this.style.push(l); }
    addSource(id, spec) { this.sources[id] = {...spec, sets: 0, setData(d) { this.data = d; this.sets++; }}; }
    getSource(id) { return this.sources[id]; }
    setPaintProperty(id, k, v) { (this.paint[id] ||= {})[k] = v; }
    setLayoutProperty(id, k, v) { (this.layout[id] ||= {})[k] = v; }
    setFilter() {} setLayerZoomRange() {}
    hasImage(id) { return !!this.images[id]; } addImage(id, d) { this.images[id] = d; }
    easeTo(v) { this.ease = v; } fitBounds(b, o) { this.fit = {b, o}; } jumpTo(v) { this.jump = v; } getZoom() { return 12; }
    getCanvas() { return this.canvas; } resize() {} remove() { this.removed = true; }
  }
  w.maplibregl = {Map};
  w.eval(renderer);
  const fixture = createFixture(profile, Date.now());
  installMockFetch(w, fixture);
  w.eval(clientSource() + '\nwindow.__ux = {Poll, MapHost, Teren, Gasire, Circle, Explore};');
  const $ = id => { const n = w.document.getElementById(id); assert(n, 'Missing #' + id); return n; };
  return {w, $, maps, fixture, errors, ux: w.__ux};
}
async function login(p) {
  p.$('login-email').value = 'lana@example.test'; p.$('login-password').value = 'x';
  p.$('login-form').dispatchEvent(new p.w.Event('submit', {bubbles: true, cancelable: true}));
  await tick(40);
}
async function open(p, hash) { p.w.location.hash = '#' + hash; await tick(40); }
const checks = [];
async function check(name, fn) { await fn(); checks.push(name); }

(async () => {
  await check('one MapLibre map on OpenFreeMap liberty, recoloured Night, fitted on her own territory', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'teren');
    await until(() => p.$('map-host').dataset.ready === '1' && p.maps[0]?.fit, 'fit');
    const map = p.maps[0];
    assert.equal(p.maps.length, 1);
    assert.equal(map.options.style, 'https://tiles.openfreemap.org/styles/liberty');
    assert.equal(map.paint.background['background-color'], '#141517');
    const cells = p.fixture.data.explore.features.flatMap(f => f.geometry.coordinates[0]);
    const lngs = cells.map(c => c[0]), lats = cells.map(c => c[1]);
    assert(map.fit.b[0][0] <= Math.min(...lngs) && map.fit.b[1][0] >= Math.max(...lngs), 'bounds cover every cell (lng)');
    assert(map.fit.b[0][1] <= Math.min(...lats) && map.fit.b[1][1] >= Math.max(...lats), 'bounds cover every cell (lat)');
    assert.equal(map.fit.o.padding.right, 420, 'the side panel does not hide the territory');
    assert.equal(map.sources['forja-cells'].data.features.length, p.fixture.data.explore.features.length);
    assert.equal(map.sources['forja-friends'].data.features.length, 5, '4 visible friends + Mama (Radu once)');
    assert.equal(map.sources['forja-streets'].data.features.length, 6);
    assert.equal(map.sources['forja-me'].data.features.length, 1);
    assert.equal(p.errors.length, 0, p.errors.join('\n'));
  });

  await check('updates arrive through setData on the same sources; no layer is rebuilt; the camera stays', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'teren');
    await until(() => p.maps[0]?.fit, 'fit');
    const map = p.maps[0], added = map.added, sets = map.sources['forja-friends'].sets, fit = map.fit;
    p.fixture.data.cerc.friends[1].lat += 0.01;
    await p.ux.Circle.load(); await tick(30);
    assert.equal(map.added, added, 'no new layers');
    assert(map.sources['forja-friends'].sets > sets, 'friends updated by setData');
    assert.equal(map.fit, fit, 'no refit on a poll');
  });

  await check('3D is pitch on the same map; the layers popover hides layers without removing them', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'teren');
    await until(() => p.$('map-host').dataset.ready === '1', 'ready');
    const map = p.maps[0];
    p.$('teren-3d').click();
    assert.equal(map.ease.pitch, 55); assert.equal(p.$('teren-3d').getAttribute('aria-pressed'), 'true'); assert.equal(p.$('teren-3d').textContent, '2D');
    assert.equal(map.layout['building-3d'].visibility, 'visible');
    p.$('teren-3d').click();
    assert.equal(map.ease.pitch, 0);
    p.$('teren-layers-btn').click(); assert(!p.$('teren-layers').hidden);
    const heat = [...p.$('teren-layers').querySelectorAll('.switch')][0];
    heat.click(); await tick(20);
    assert.equal(map.layout['forja-heat'].visibility, 'none');
    assert(map.getLayer('forja-heat'), 'still installed');
    assert.equal(p.maps.length, 1);
  });

  await check('tile errors after load leave the map alive; a style failure shows an honest status and the panel stays usable', async () => {
    let p = page();
    await tick(20); await login(p); await open(p, 'teren');
    await until(() => p.$('map-host').dataset.ready === '1', 'ready');
    p.maps[0].fire('error', {error: Error('tile 404'), sourceId: 'openmaptiles', tile: {}});
    assert.equal(p.$('map-host').dataset.failed, undefined); assert(p.$('map-status').hidden);
    p = page({styleFails: true});
    await tick(20); await login(p); await open(p, 'teren');
    await until(() => p.$('map-host').dataset.failed === '1', 'failed');
    assert(!p.$('map-status').hidden); assert.match(p.$('map-status').textContent, /Harta nu se poate desena aici/);
    await until(() => p.$('teren-panel').querySelector('.terr'), 'panel still renders');
  });

  await check('the map is shared with Găsire: phone mode, fitted on the device, fast polling only during a command', async () => {
    const p = page();
    await tick(20); await login(p); await open(p, 'gasire');
    await until(() => p.maps[0] && p.$('map-host').dataset.ready === '1' && p.maps[0].sources['forja-devices'].data?.features?.length, 'device on map');
    const map = p.maps[0];
    assert.equal(map.layout['forja-cells-fill'].visibility, 'none'); assert.equal(map.layout['forja-accuracy-fill'].visibility, 'visible');
    assert.equal(p.$('gasire-map').querySelector('#map-host') !== null, true);
    assert.equal(p.ux.Poll.jobs.get('gasire:devices').ms, 30000);
    await open(p, 'teren');
    assert(p.$('teren-map').querySelector('#map-host'), 'the same host moved to Teren');
    assert.equal(map.layout['forja-cells-fill'].visibility, 'visible');
    assert.equal(p.maps.length, 1);
    assert(!p.ux.Poll.jobs.has('gasire:devices'), 'Găsire stops polling when hidden');
  });

  console.log(JSON.stringify({ok: true, checks}, null, 1));
  process.exit(0);
})().catch(error => { console.error(error); process.exit(1); });
