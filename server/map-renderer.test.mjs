// Harta site-ului 4.4 (map-renderer.js.txt): un singur motor MapLibre, paleta ForjaStyle, stiva de straturi a aplicației,
// actualizări prin setData, 3D prin înclinare, toleranță la erorile de dale. MapLibre e înlocuit cu un dublu în vm.
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import {createRequire} from 'node:module';

const source = fs.readFileSync(new URL('./map-renderer.js.txt', import.meta.url), 'utf8');
/** Obiectele vin din alt context vm: comparăm structura, nu prototipurile. */
const same = (actual, expected, msg) => assert.deepEqual(JSON.parse(JSON.stringify(actual)), expected, msg);
const LIBERTY = ['background', 'park', 'water', 'building', 'building-3d', 'road_minor', 'road_trunk_primary', 'label_city', 'poi_r20', 'highway-name-major'];

function fixture({reduced = false, constructorError = null, layers = LIBERTY} = {}) {
  const maps = [], timers = [];
  class Map {
    constructor(options) {
      if (constructorError) throw constructorError;
      this.options = options; this.events = {}; this.layerEvents = []; this.sources = {}; this.images = {}; this.paint = {}; this.layout = {}; this.filters = {}; this.zoomRanges = {};
      this.style = layers.map(id => ({id, type: id === 'building-3d' ? 'fill-extrusion' : /label|name|poi/.test(id) ? 'symbol' : 'fill', minzoom: id === 'building' ? 13 : undefined}));
      this.added = []; this.zoom = 5.6; maps.push(this);
    }
    on(name, a, b) { if (typeof a === 'string') this.layerEvents.push([name, a]); else this.events[name] = a; return this; }
    emit(name, e = {}) { this.events[name]?.(e); }
    getLayer(id) { return this.style.find(l => l.id === id); }
    getStyle() { return {layers: this.style}; }
    addLayer(layer, before) { this.added.push([layer.id, before]); const i = before ? this.style.findIndex(l => l.id === before) : -1; if (i >= 0) this.style.splice(i, 0, layer); else this.style.push(layer); }
    addSource(id, spec) { const src = {...spec, sets: 0, setData(d) { this.data = d; this.sets++; }}; this.sources[id] = src; }
    getSource(id) { return this.sources[id]; }
    setPaintProperty(id, k, v) { (this.paint[id] ||= {})[k] = v; }
    setLayoutProperty(id, k, v) { (this.layout[id] ||= {})[k] = v; }
    getLayoutProperty(id, k) { return this.layout[id]?.[k]; }
    setFilter(id, f) { this.filters[id] = f; }
    setLayerZoomRange(id, a, b) { this.zoomRanges[id] = [a, b]; }
    hasImage(id) { return !!this.images[id]; }
    addImage(id, data) { this.images[id] = data; }
    easeTo(v) { this.lastEase = v; if (v.zoom) this.zoom = v.zoom; }
    fitBounds(b, o) { this.lastFit = {b, o}; }
    jumpTo(v) { this.lastJump = v; }
    getZoom() { return this.zoom; }
    getCanvas() { return {style: {}}; }
    resize() { this.resized = true; }
    remove() { this.removed = true; }
  }
  const container = {dataset: {}};
  const global = {maplibregl: {Map}, matchMedia: () => ({matches: reduced}), setTimeout(fn) { timers.push(fn); return timers.length; }, clearTimeout() {}};
  vm.runInNewContext(source, global);
  return {api: global.ForjaMapRenderer, maps, timers, container};
}
const cell = (x, y, mode) => {
  const w = 0.002, a = 26 + x * w, b = 44 + y * w;
  return {type: 'Feature', geometry: {type: 'Polygon', coordinates: [[[a, b], [a + w, b], [a + w, b + w], [a, b + w], [a, b]]]}, properties: {id: `${x}_${y}`, visits: 2, ...(mode ? {mode} : {})}};
};
const cells = {type: 'FeatureCollection', features: [cell(0, 0, 'run'), cell(1, 0), cell(0, 1, 'ride')]};

test('the pinned MapLibre is the only engine and exports Map', () => {
  const library = createRequire(import.meta.url)('./vendor/maplibre-5.10.0.js.txt');
  assert.equal(library.getVersion(), '5.10.0'); assert.equal(typeof library.Map, 'function');
  assert.ok(!fs.existsSync(new URL('./vendor/leaflet-1.9.4.js.txt', import.meta.url)), 'Leaflet a ieșit din pachet');
  assert.ok(!/L\.map\(|L\.tileLayer|tile\.openstreetmap/.test(source));
});

test('no WebGL engine throws an honest Romanian error; a constructor failure propagates', () => {
  const vmNoGl = {matchMedia: () => ({matches: false})};
  vm.runInNewContext(source, vmNoGl);
  assert.throws(() => vmNoGl.ForjaMapRenderer.create({}), /nu este disponibilă/);
  assert.throws(() => fixture({constructorError: Error('Failed to initialize WebGL')}).api.create({dataset: {}}), /Failed to initialize WebGL/);
});

test('ForjaStyle Night recolours liberty in place, keeps missing layers harmless, labels in Romanian', () => {
  const f = fixture(), r = f.api.create(f.container, {}), map = f.maps[0];
  assert.equal(map.options.style, 'https://tiles.openfreemap.org/styles/liberty');
  map.emit('load');
  assert.equal(r.ready, true);
  assert.equal(map.paint.background['background-color'], '#141517');
  assert.equal(map.paint.water['fill-color'], '#0f1a26');
  assert.equal(map.paint.road_minor['line-color'], '#2b2c30');
  assert.equal(map.paint['building-3d']['fill-extrusion-color'], '#22232a');
  same(map.zoomRanges.building, [13, 24]);
  assert.match(JSON.stringify(map.filters['building-3d']), /hide_3d.*render_height/);
  assert.equal(map.layout.poi_r20.visibility, 'none');
  same(map.layout.label_city['text-field'], ['coalesce', ['get', 'name'], ['get', 'name:latin'], ['get', 'name_en']]);
  assert.equal(map.paint.park_outline, undefined, 'stratul lipsă nu primește nimic și nu aruncă');
  r.setTheme('day');
  assert.equal(map.paint.background['background-color'], '#f3efe6');
});

test('the FORJA stack is installed once: ground paint under buildings, symbols on top, data only through setData', () => {
  const f = fixture(), r = f.api.create(f.container, {}), map = f.maps[0];
  r.setData({cells});
  map.emit('load');
  const ids = map.added.map(([id]) => id);
  for (const id of ['forja-heat', 'forja-cells-fill', 'forja-cells-line', 'forja-streets-casing', 'forja-streets', 'forja-halos', 'forja-accuracy-fill', 'forja-places', 'forja-rec', 'forja-friends', 'forja-me', 'forja-devices']) assert.ok(ids.includes(id), id);
  assert.equal(map.added.find(([id]) => id === 'forja-cells-fill')[1], 'building');
  assert.equal(map.added.find(([id]) => id === 'forja-friends')[1], undefined);
  assert.equal(map.sources['forja-cells'].data.features.length, 3);
  same(map.sources['forja-cells'].data.features.map(x => x.properties.mode), ['run', 'walk', 'ride'], 'fără mod → pe jos, ca în aplicație');
  const layerCount = map.added.length;
  r.setData({cells: {type: 'FeatureCollection', features: [cell(5, 5)]}, routes: [{id: 'a1', type: 'run', polyline: '44.1,26.1;44.2,26.2;bad;44.3,26.3'}]});
  assert.equal(map.added.length, layerCount, 'nicio reconstruire de straturi');
  assert.equal(map.sources['forja-cells'].sets, 2);
  assert.equal(map.sources['forja-streets'].data.features[0].geometry.coordinates.length, 3);
  assert.equal(f.container.dataset.idle, '0');
  map.emit('idle');
  assert.equal(f.container.dataset.idle, '1');
});

test('FORJA data goes in as soon as the style is ready, not after the last slow tile; a later load changes nothing', () => {
  let readyCalls = 0;
  const f = fixture(), r = f.api.create(f.container, {onReady: () => readyCalls++}), map = f.maps[0];
  r.setData({cells});
  map.emit('style.load');
  assert.equal(r.ready, true);
  assert.equal(map.sources['forja-cells'].data.features.length, 3);
  const layers = map.added.length, clicks = map.layerEvents.length;
  map.emit('load');
  assert.equal(map.added.length, layers, 'no second install');
  assert.equal(map.layerEvents.length, clicks, 'click handlers bound once');
  assert.equal(readyCalls, 1);
});

test('territory outline keeps only outer edges; heat is one point per cell', () => {
  const {api} = fixture();
  const square = {type: 'FeatureCollection', features: [cell(0, 0), cell(1, 0), cell(0, 1), cell(1, 1)]};
  const c = api.geo.cells(square), edges = api.geo.edges(c);
  assert.equal(edges.features[0].geometry.coordinates.length, 8, '2×2 celule → 8 laturi exterioare, nu 16');
  assert.equal(api.geo.heat(c).features.length, 4);
  same(api.geo.parsePolyline('44.5,26.1; x ;91,1;44.6,26.2'), [[44.5, 26.1], [44.6, 26.2]]);
  const ring = api.geo.circle(44.4, 26.1, 100).geometry.coordinates[0];
  assert.equal(ring.length, 57);
  assert.ok(Math.abs(ring[0][1] - 44.4 - 100 / 111320) < 1e-4);
});

test('first fit uses her own cells, places and routes; else her position; else friends', () => {
  const f = fixture(), r = f.api.create(f.container, {}), map = f.maps[0];
  map.emit('load');
  assert.equal(r.fitOwn(), false, 'nimic încă');
  r.setData({friends: [{uid: 'a', name: 'Ana', lat: 45, lng: 25}, {uid: 'b', name: 'Bo', lat: 45.1, lng: 25.1}]});
  assert.equal(r.fitOwn(), true); same(map.lastFit.b, [[25, 45], [25.1, 45.1]]);
  r.setData({me: {lat: 44.43, lng: 26.09, at: 1}});
  r.fitOwn(); same(map.lastJump.center, [26.09, 44.43]);
  r.setData({cells, places: [{id: 'p', lat: 44.01, lng: 26.01, name: 'Acasă', stars: 4}]});
  r.fitOwn({top: 1, right: 400, bottom: 1, left: 1});
  same(map.lastFit.b[0], [26, 44]);
  assert.equal(map.lastFit.o.maxZoom, 15.5);
  same(map.lastFit.o.padding, {top: 1, right: 400, bottom: 1, left: 1});
});

test('ghost friends stay off the map, family shows at 0.55, the ♪ badge follows nowPlaying', () => {
  const f = fixture(), r = f.api.create(f.container, {}), map = f.maps[0];
  map.emit('load');
  r.setData({me: {lat: 44.4, lng: 26.1}, friends: [{uid: 'a', name: 'Ana Pop', lat: 44.41, lng: 26.1, state: 'run', nowPlaying: {title: 'X'}}, {uid: 'g', name: 'Ghost', lat: 1, lng: 1, ghost: true}], family: [{uid: 'm', name: 'Mama', lat: 44.42, lng: 26.12}]});
  const feats = map.sources['forja-friends'].data.features;
  same(feats.map(x => x.properties.id), ['a', 'm']);
  assert.equal(feats[1].properties.alpha, 0.55);
  assert.match(feats[0].properties.icon, /\|1\|0$/, 'insigna ♪ intră în cheia imaginii');
});

test('tile, glyph and source errors after load are tolerated; style failure before load and WebGL loss are fatal', () => {
  let f = fixture(), errors = [];
  let r = f.api.create(f.container, {onError: (e, fatal) => errors.push(fatal)}), map = f.maps[0];
  map.emit('load');
  map.emit('error', {error: Error('Tile could not be loaded'), sourceId: 'openmaptiles', tile: {}});
  map.emit('error', {error: Error('Could not load glyph range')});
  assert.equal(r.ready, true); same(errors, []);
  map.emit('webglcontextlost');
  assert.equal(r.ready, false); same(errors, [true]);
  f = fixture(); errors = [];
  r = f.api.create(f.container, {onError: (e, fatal) => errors.push(fatal)}); map = f.maps[0];
  map.emit('error', {error: Error('Failed to fetch style')});
  same(errors, [true]); assert.equal(r.ready, false);
  f = fixture(); errors = [];
  r = f.api.create(f.container, {onError: (e, fatal) => errors.push(fatal)});
  f.timers[0]();
  same(errors, [false], 'întârzierea nu închide harta: dacă stilul vine mai târziu, pornește');
  f.maps[0].emit('load'); assert.equal(r.ready, true);
});

test('3D is only pitch on the same map, with liberty extrusions; reduced motion has no animation', () => {
  const f = fixture({reduced: true}), r = f.api.create(f.container, {}), map = f.maps[0];
  assert.equal(r.set3D(true), false, 'nu înainte de încărcare');
  map.emit('load');
  assert.equal(map.layout['building-3d'].visibility, 'none');
  assert.equal(r.set3D(true), true);
  assert.equal(map.lastEase.pitch, 55); assert.equal(map.lastEase.duration, 0);
  assert.equal(map.layout['building-3d'].visibility, 'visible'); assert.equal(map.paint.building['fill-opacity'], 0.35);
  r.set3D(false);
  assert.equal(map.lastEase.pitch, 0); assert.equal(map.layout['building-3d'].visibility, 'none'); assert.equal(map.paint.building['fill-opacity'], 1);
  assert.equal(f.maps.length, 1, 'un singur motor');
});

test('Găsire mode shows the phone and its accuracy only; layer toggles hide without removing', () => {
  const f = fixture(), r = f.api.create(f.container, {}), map = f.maps[0];
  map.emit('load');
  r.setData({devices: [{id: 'd', name: 'S23', last: {lat: 44.4, lon: 26.1, accuracy: 30, at: 1}}]});
  assert.equal(map.sources['forja-devices'].data.features.length, 1);
  assert.equal(map.sources['forja-accuracy'].data.features.length, 1);
  assert.equal(map.layout['forja-accuracy-fill'].visibility, 'none', 'pe Teren telefonul e doar un ⌖');
  r.setMode('gasire');
  assert.equal(map.layout['forja-accuracy-fill'].visibility, 'visible');
  assert.equal(map.layout['forja-cells-fill'].visibility, 'none');
  assert.equal(map.layout['forja-friends'].visibility, 'none');
  r.setMode('teren'); r.setLayers({heat: false});
  assert.equal(map.layout['forja-heat'].visibility, 'none');
  assert.equal(map.layout['forja-cells-fill'].visibility, 'visible');
  assert.equal(r.fitDevices(), true);
  r.destroy(); assert.equal(map.removed, true);
});
