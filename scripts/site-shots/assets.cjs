// Offline stand-ins for everything the site loads from third parties or from the phone:
//  - raster tiles for Leaflet (tile.openstreetmap.org)  → deterministic SVG "city" tiles
//  - OpenFreeMap vector style + TileJSON + glyph ranges  → a small local MapLibre style (streets, parks, water,
//    extruded blocks) built from GeoJSON, an empty vector tile source and empty glyph PBFs
//  - photo thumbnails / previews                        → flat illustrated PNG scenes drawn once in Chromium
//  - PDF / audio                                        → a tiny valid PDF, server/fixtures/two-minutes-silence.m4a
// Tile hosts are unreachable from the investigation container; the placeholders keep every map UI state renderable.
const fs = require('node:fs');
const path = require('node:path');

const hash = (...n) => { let h = 2166136261; for (const v of n) { h ^= v & 0xffffffff; h = Math.imul(h, 16777619); h ^= h >>> 13; } return ((h >>> 0) % 10000) / 10000; };

// ——— Leaflet raster tiles: a street grid in world-pixel space, so lines continue across tiles ———
function rasterTile(z, x, y) {
  const size = 256, step = z >= 15 ? 110 : z >= 13 ? 72 : z >= 10 ? 48 : 36, ox = x * size, oy = y * size;
  const parts = [`<rect width="256" height="256" fill="#f1efe8"/>`];
  const i0 = Math.floor(ox / step) - 1, i1 = Math.ceil((ox + size) / step) + 1, j0 = Math.floor(oy / step) - 1, j1 = Math.ceil((oy + size) / step) + 1;
  for (let i = i0; i < i1; i++) for (let j = j0; j < j1; j++) {
    const r = hash(z, i, j), bx = i * step - ox + 7, by = j * step - oy + 7, w = step - 14;
    if (r < 0.1) parts.push(`<rect x="${bx}" y="${by}" width="${w}" height="${w}" rx="6" fill="#cfe5bf"/>`);
    else if (r < 0.13) parts.push(`<rect x="${bx}" y="${by}" width="${w}" height="${w}" rx="18" fill="#b9d6ee"/>`);
    else if (z >= 14) for (let k = 0; k < 4; k++) { const q = hash(z, i, j, k); parts.push(`<rect x="${bx + (k % 2) * w / 2 + 3}" y="${by + Math.floor(k / 2) * w / 2 + 3}" width="${w / 2 - 6}" height="${w / 2 - 6 - q * 8}" fill="#e3ddd2"/>`); }
  }
  for (let i = i0; i < i1; i++) { const major = ((i % 4) + 4) % 4 === 0, xw = i * step - ox; parts.push(`<rect x="${xw - (major ? 4 : 2)}" y="0" width="${major ? 8 : 4}" height="256" fill="${major ? '#fbe3a6' : '#ffffff'}"/>`); }
  for (let j = j0; j < j1; j++) { const major = ((j % 4) + 4) % 4 === 0, yw = j * step - oy; parts.push(`<rect x="0" y="${yw - (major ? 4 : 2)}" width="256" height="${major ? 8 : 4}" fill="${major ? '#fbe3a6' : '#ffffff'}"/>`); }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">${parts.join('')}</svg>`;
}

// ——— MapLibre offline style (served in place of https://tiles.openfreemap.org/styles/liberty) ———
function offlineStyle() {
  const streets = [], blocks = [], parks = [], water = [];
  const lat0 = 44.395, lat1 = 44.495, lon0 = 26.03, lon1 = 26.17, d = 0.0032;
  for (let lon = lon0; lon <= lon1; lon += d) streets.push({type: 'Feature', properties: {major: Math.round((lon - lon0) / d) % 4 === 0}, geometry: {type: 'LineString', coordinates: [[lon, lat0], [lon, lat1]]}});
  for (let lat = lat0; lat <= lat1; lat += d * 0.72) streets.push({type: 'Feature', properties: {major: Math.round((lat - lat0) / (d * 0.72)) % 4 === 0}, geometry: {type: 'LineString', coordinates: [[lon0, lat], [lon1, lat]]}});
  let n = 0;
  for (let lon = lon0; lon < lon1; lon += d) for (let lat = lat0; lat < lat1; lat += d * 0.72) {
    const r = hash(Math.round(lon * 1e4), Math.round(lat * 1e4)), a = lon + d * 0.12, b = lat + d * 0.09, c = lon + d * 0.88, e = lat + d * 0.72 - d * 0.09;
    const poly = [[[a, b], [c, b], [c, e], [a, e], [a, b]]];
    if (r < 0.08) parks.push({type: 'Feature', properties: {}, geometry: {type: 'Polygon', coordinates: poly}});
    else if (r < 0.1) water.push({type: 'Feature', properties: {}, geometry: {type: 'Polygon', coordinates: poly}});
    else { const h = 9 + Math.round(hash(n++, 7) * 48); for (let k = 0; k < 2; k++) { const s = a + (c - a) * k / 2 + d * 0.03, t = s + (c - a) / 2 - d * 0.06; blocks.push({type: 'Feature', properties: {h: h + k * 6}, geometry: {type: 'Polygon', coordinates: [[[s, b], [t, b], [t, e], [s, e], [s, b]]]}}); } }
  }
  const src = features => ({type: 'geojson', data: {type: 'FeatureCollection', features}});
  return {version: 8, name: 'FORJA harness offline placeholder (not OpenFreeMap)', glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
    sources: {streets: src(streets), blocks: src(blocks), parks: src(parks), water: src(water)},
    layers: [
      {id: 'background', type: 'background', paint: {'background-color': '#f1efe8'}},
      {id: 'water', type: 'fill', source: 'water', paint: {'fill-color': '#b9d6ee'}},
      {id: 'parks', type: 'fill', source: 'parks', paint: {'fill-color': '#cfe5bf'}},
      {id: 'blocks', type: 'fill-extrusion', source: 'blocks', paint: {'fill-extrusion-color': '#ddd6ca', 'fill-extrusion-height': ['get', 'h'], 'fill-extrusion-opacity': 0.9}},
      {id: 'streets', type: 'line', source: 'streets', filter: ['!', ['get', 'major']], paint: {'line-color': '#ffffff', 'line-width': ['interpolate', ['linear'], ['zoom'], 11, 0.5, 16, 6]}},
      {id: 'streets-major', type: 'line', source: 'streets', filter: ['get', 'major'], paint: {'line-color': '#fbe3a6', 'line-width': ['interpolate', ['linear'], ['zoom'], 11, 1.5, 16, 10]}}]};
}
const tileJson = () => ({tilejson: '3.0.0', name: 'harness-empty-planet', tiles: ['https://tiles.openfreemap.org/harness-empty/{z}/{x}/{y}.pbf'], minzoom: 0, maxzoom: 14, vector_layers: [{id: 'building', fields: {render_height: 'Number'}}]});

// ——— Photo scenes, drawn in the browser (serialized into page.evaluate) ———
function drawScenes(names) {
  const out = {};
  for (const name of names) {
    const c = document.createElement('canvas'); c.width = 480; c.height = 360; const g = c.getContext('2d');
    const sky = (a, b) => { const s = g.createLinearGradient(0, 0, 0, 360); s.addColorStop(0, a); s.addColorStop(1, b); g.fillStyle = s; g.fillRect(0, 0, 480, 360); };
    const poly = (color, pts) => { g.fillStyle = color; g.beginPath(); g.moveTo(pts[0], pts[1]); for (let i = 2; i < pts.length; i += 2) g.lineTo(pts[i], pts[i + 1]); g.closePath(); g.fill(); };
    const circle = (color, x, y, r) => { g.fillStyle = color; g.beginPath(); g.arc(x, y, r, 0, Math.PI * 2); g.fill(); };
    if (name === 'mountain') { sky('#8fb8d8', '#e8eef0'); circle('#fff3c4', 380, 70, 34); poly('#6f7f8f', [0, 260, 120, 110, 220, 230, 320, 90, 480, 250, 480, 360, 0, 360]); poly('#f4f6f8', [120, 110, 95, 145, 140, 140, 320, 90, 290, 130, 345, 128]); poly('#3f5d3a', [0, 300, 480, 280, 480, 360, 0, 360]); for (let i = 0; i < 9; i++) poly('#2f4a2c', [20 + i * 52, 300, 40 + i * 52, 240, 60 + i * 52, 300]); g.fillStyle = '#8a5a3b'; g.fillRect(300, 250, 70, 50); poly('#6b3f28', [290, 252, 335, 220, 380, 252]); }
    else if (name === 'forest') { sky('#cfe3d0', '#7fa37e'); for (let i = 0; i < 14; i++) { const x = (i * 37) % 480; poly(i % 2 ? '#2f5a36' : '#3e7045', [x, 360, x + 30, 60 + (i * 23) % 90, x + 60, 360]); } g.fillStyle = '#d8eef5'; g.fillRect(220, 40, 40, 320); g.fillStyle = '#ffffff88'; g.fillRect(232, 40, 8, 320); }
    else if (name === 'sea') { sky('#f6b38c', '#fde7c7'); circle('#fff1b8', 240, 190, 44); g.fillStyle = '#4f8fb3'; g.fillRect(0, 200, 480, 160); g.fillStyle = '#ffffff55'; for (let i = 0; i < 8; i++) g.fillRect(40 + i * 50, 220 + i * 14, 90, 3); poly('#e9d3a6', [0, 330, 480, 300, 480, 360, 0, 360]); }
    else if (name === 'cake') { sky('#f7e6ef', '#f1d3e0'); g.fillStyle = '#ffffff'; g.fillRect(90, 290, 300, 16); g.fillStyle = '#b86b77'; g.fillRect(130, 190, 220, 100); g.fillStyle = '#fbeff2'; g.fillRect(130, 185, 220, 18); for (let i = 0; i < 5; i++) { g.fillStyle = '#f5d76e'; g.fillRect(160 + i * 40, 140, 8, 45); circle('#ffb347', 164 + i * 40, 132, 8); } }
    else if (name === 'food') { sky('#e9dcc6', '#d6c3a3'); circle('#ffffff', 240, 185, 130); circle('#f3ead8', 240, 185, 105); circle('#e0b04e', 200, 160, 34); circle('#c43c3c', 270, 150, 22); circle('#5a8f3c', 285, 220, 30); circle('#f0e3b2', 215, 230, 28); }
    else if (name === 'city') { sky('#111a33', '#2b3a66'); circle('#f7f1d5', 400, 60, 18); for (let i = 0; i < 12; i++) { const x = i * 42, h = 120 + (i * 53) % 150; g.fillStyle = '#1c2340'; g.fillRect(x, 360 - h, 38, h); g.fillStyle = '#f6d67a'; for (let w = 0; w < 18; w++) if ((w * 7 + i) % 3) g.fillRect(x + 6 + (w % 3) * 11, 370 - h + Math.floor(w / 3) * 18, 6, 8); } }
    else if (name === 'screenshot') { sky('#f5f6f8', '#f5f6f8'); g.fillStyle = '#1e5bd6'; g.fillRect(0, 0, 480, 56); g.fillStyle = '#ffffff'; g.font = 'bold 22px sans-serif'; g.fillText('CFR Călători · bilet', 20, 36); g.fillStyle = '#222'; g.font = '18px sans-serif'; ['București Nord → Sinaia', 'Sâmbătă · 07:40', 'Vagon 4 · loc 57', 'Total: 64,50 lei'].forEach((t, i) => g.fillText(t, 24, 110 + i * 44)); g.fillStyle = '#111'; for (let i = 0; i < 16; i++) g.fillRect(330 + (i % 4) * 28, 100 + Math.floor(i / 4) * 28, (i * 7) % 3 ? 22 : 12, 22); }
    else if (name === 'cat') { sky('#b7c9d9', '#e7dccb'); g.fillStyle = '#c9b79c'; g.fillRect(0, 250, 480, 110); circle('#5b4a3d', 240, 210, 70); circle('#5b4a3d', 240, 130, 48); poly('#5b4a3d', [200, 110, 205, 60, 230, 95]); poly('#5b4a3d', [280, 110, 275, 60, 250, 95]); circle('#d9f27a', 222, 128, 7); circle('#d9f27a', 258, 128, 7); }
    else { sky('#ddd', '#bbb'); }
    out[name] = c.toDataURL('image/png').split(',')[1];
  }
  return out;
}
const SCENES = ['mountain', 'forest', 'sea', 'cake', 'food', 'city', 'screenshot', 'cat'];

// ——— A minimal valid one-page PDF (standard Helvetica, ASCII text) ———
function pdf(lines) {
  const ascii = s => s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^\x20-\x7e]/g, '-').replace(/[()\\]/g, '\\$&');
  const text = lines.map((l, i) => `BT /F1 ${i ? 14 : 22} Tf 60 ${740 - i * 34 - (i ? 20 : 0)} Td (${ascii(l)}) Tj ET`).join('\n');
  const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>', `<< /Length ${Buffer.byteLength(text)} >>\nstream\n${text}\nendstream`];
  let body = '%PDF-1.4\n'; const offsets = [];
  objects.forEach((o, i) => { offsets.push(Buffer.byteLength(body)); body += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const xref = Buffer.byteLength(body);
  body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n` + offsets.map(o => String(o).padStart(10, '0') + ' 00000 n \n').join('') + `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(body, 'latin1');
}

async function createAssets(browser, server) {
  const page = await browser.newPage();
  const scenes = await page.evaluate(drawScenes, SCENES);
  await page.close();
  const png = Object.fromEntries(Object.entries(scenes).map(([k, v]) => [k, Buffer.from(v, 'base64')]));
  const audio = fs.readFileSync(path.join(server, 'fixtures/two-minutes-silence.m4a'));
  const style = JSON.stringify(offlineStyle()), tiles = JSON.stringify(tileJson());
  return {
    scene: name => png[name] || png.mountain, pdf, audio: () => audio,
    // Map hosts. Returns a route.fulfill() payload or null.
    thirdParty(u) {
      if (u.hostname === 'tile.openstreetmap.org') { const m = /^\/(\d+)\/(\d+)\/(\d+)\.png$/.exec(u.pathname); return m ? {status: 200, contentType: 'image/svg+xml', body: rasterTile(+m[1], +m[2], +m[3])} : {status: 404, body: ''}; }
      if (u.hostname === 'tiles.openfreemap.org') {
        if (u.pathname.startsWith('/styles/')) return {status: 200, contentType: 'application/json', body: style};
        if (u.pathname === '/planet') return {status: 200, contentType: 'application/json', body: tiles};
        if (u.pathname.startsWith('/harness-empty/') || u.pathname.startsWith('/fonts/')) return {status: 200, contentType: 'application/x-protobuf', body: Buffer.alloc(0)};
        return {status: 404, body: ''};
      }
      return null;
    }
  };
}

module.exports = {createAssets, rasterTile, offlineStyle, pdf};
