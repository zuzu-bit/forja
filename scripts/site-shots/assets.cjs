// Offline stand-ins for everything the site loads from third parties or from the phone:
//  - OpenFreeMap vector style + TileJSON + glyph ranges → a local MapLibre style for Bucharest built from GeoJSON, with the
//    SAME layer ids as OpenFreeMap "liberty" (background, water, park, landuse_residential, building, building-3d, road_*,
//    waterway_river…), so the site's ForjaStyle port recolours it exactly as it recolours liberty live
//  - photo thumbnails / previews                         → flat illustrated PNG scenes drawn once in Chromium
//  - PDF / audio                                         → a tiny valid PDF, server/fixtures/two-minutes-silence.m4a
// Tile hosts are unreachable from the investigation container; the placeholders keep every map UI state renderable.
// Labels of the base map do not render (empty glyph PBFs): the FORJA pins draw their own labels on canvas.
const fs = require('node:fs');
const path = require('node:path');

const hash = (...n) => { let h = 2166136261; for (const v of n) { h ^= v & 0xffffffff; h = Math.imul(h, 16777619); h ^= h >>> 13; } return ((h >>> 0) % 10000) / 10000; };

// ——— MapLibre offline style (served in place of https://tiles.openfreemap.org/styles/liberty) ———
function offlineStyle() {
  const minor = [], secondary = [], primary = [], blocks = [], parks = [], residential = [];
  const lat0 = 44.385, lat1 = 44.500, lon0 = 26.02, lon1 = 26.19, d = 0.0032, dl = d * 0.72;
  const line = (coords, props = {}) => ({type: 'Feature', properties: props, geometry: {type: 'LineString', coordinates: coords}});
  let i = 0;
  for (let lon = lon0; lon <= lon1; lon += d, i++) { const f = line([[lon, lat0], [lon + 0.004 * Math.sin(i), lat1]]); (i % 6 === 0 ? primary : i % 3 === 0 ? secondary : minor).push(f); }
  i = 0;
  for (let lat = lat0; lat <= lat1; lat += dl, i++) { const f = line([[lon0, lat], [lon1, lat + 0.003 * Math.cos(i)]]); (i % 7 === 0 ? primary : i % 3 === 0 ? secondary : minor).push(f); }
  // Two diagonal boulevards, like Kiseleff and Calea Victoriei.
  primary.push(line([[26.0820, 44.4760], [26.0870, 44.4530], [26.0960, 44.4380], [26.1020, 44.4270], [26.1060, 44.4100]]));
  secondary.push(line([[26.0600, 44.4450], [26.0930, 44.4400], [26.1300, 44.4480], [26.1700, 44.4260]]));
  let n = 0;
  for (let lon = lon0; lon < lon1; lon += d) for (let lat = lat0; lat < lat1; lat += dl) {
    const r = hash(Math.round(lon * 1e4), Math.round(lat * 1e4)), a = lon + d * 0.1, b = lat + dl * 0.1, c = lon + d * 0.9, e = lat + dl * 0.9;
    const poly = [[[a, b], [c, b], [c, e], [a, e], [a, b]]];
    if (r < 0.07) { parks.push({type: 'Feature', properties: {}, geometry: {type: 'Polygon', coordinates: poly}}); continue; }
    residential.push({type: 'Feature', properties: {}, geometry: {type: 'Polygon', coordinates: poly}});
    for (let k = 0; k < 4; k++) {
      const s0 = a + (c - a) * (k % 2) / 2 + d * 0.02, s1 = s0 + (c - a) / 2 - d * 0.05, t0 = b + (e - b) * Math.floor(k / 2) / 2 + dl * 0.02, t1 = t0 + (e - b) / 2 - dl * 0.05;
      blocks.push({type: 'Feature', properties: {render_height: 8 + Math.round(hash(n++, 7) * 46), hide_3d: false}, geometry: {type: 'Polygon', coordinates: [[[s0, t0], [s1, t0], [s1, t1], [s0, t1], [s0, t0]]]}});
    }
  }
  const lake = {type: 'Feature', properties: {}, geometry: {type: 'Polygon', coordinates: [[[26.0760, 44.4735], [26.0830, 44.4790], [26.0930, 44.4800], [26.0990, 44.4755], [26.0930, 44.4715], [26.0820, 44.4712], [26.0760, 44.4735]]]}};
  const park = pts => ({type: 'Feature', properties: {}, geometry: {type: 'Polygon', coordinates: [pts]}});
  parks.push(park([[26.0735, 44.4690], [26.0990, 44.4700], [26.1000, 44.4830], [26.0740, 44.4820], [26.0735, 44.4690]]), park([[26.0905, 44.4365], [26.0960, 44.4365], [26.0960, 44.4400], [26.0905, 44.4400], [26.0905, 44.4365]]), park([[26.0930, 44.4130], [26.1000, 44.4130], [26.1000, 44.4185], [26.0930, 44.4185], [26.0930, 44.4130]]));
  const river = line([[26.02, 44.4390], [26.06, 44.4330], [26.09, 44.4300], [26.105, 44.4290], [26.13, 44.4260], [26.19, 44.4180]]);
  const src = features => ({type: 'geojson', data: {type: 'FeatureCollection', features}});
  const roadW = (a, b) => ['interpolate', ['exponential', 1.4], ['zoom'], 10, a, 18, b];
  return {version: 8, name: 'FORJA harness offline placeholder (liberty layer ids, not OpenFreeMap)', glyphs: 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf',
    sources: {minor: src(minor), secondary: src(secondary), primary: src(primary), blocks: src(blocks), parks: src(parks), residential: src(residential), water: src([lake]), river: src([river])},
    layers: [
      {id: 'background', type: 'background', paint: {'background-color': '#f3efe6'}},
      {id: 'landuse_residential', type: 'fill', source: 'residential', paint: {'fill-color': 'hsla(35,30%,90%,0.5)'}},
      {id: 'park', type: 'fill', source: 'parks', paint: {'fill-color': '#d9e3c4'}},
      {id: 'water', type: 'fill', source: 'water', paint: {'fill-color': '#b9cde4'}},
      {id: 'waterway_river', type: 'line', source: 'river', paint: {'line-color': '#a9bfd9', 'line-width': roadW(3, 26)}},
      {id: 'road_minor', type: 'line', source: 'minor', minzoom: 12, layout: {'line-cap': 'round'}, paint: {'line-color': '#ffffff', 'line-width': roadW(0.4, 9)}},
      {id: 'road_secondary_tertiary', type: 'line', source: 'secondary', layout: {'line-cap': 'round'}, paint: {'line-color': '#f9edcb', 'line-width': roadW(0.8, 14)}},
      {id: 'road_trunk_primary', type: 'line', source: 'primary', layout: {'line-cap': 'round'}, paint: {'line-color': '#f6e5b8', 'line-width': roadW(1.2, 20)}},
      {id: 'building', type: 'fill', source: 'blocks', minzoom: 13, maxzoom: 14, paint: {'fill-color': '#e6e1d6', 'fill-outline-color': '#d5cfc2'}},
      {id: 'building-3d', type: 'fill-extrusion', source: 'blocks', minzoom: 14, paint: {'fill-extrusion-color': '#ece7dc', 'fill-extrusion-height': ['get', 'render_height'], 'fill-extrusion-opacity': 0.9}}]};
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

module.exports = {createAssets, offlineStyle, pdf};
