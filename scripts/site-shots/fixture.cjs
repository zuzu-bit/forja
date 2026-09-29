// FIXTURE DATA for the local site preview harness (scripts/site-shots/shots.cjs). Synthetic, Romanian, Bucharest.
// Shapes follow DESIGN-4.4.md §3.2 (site read API), §3.3 (Firestore docs surfaced by the API) and §3.4 (finder v2)
// EXACTLY, plus the existing routes the 4.4 site keeps (/v2/files*, /v2/social/explore/*, /v2/social/contacts/discovery,
// /insights/api/intake). Times are ms epoch numbers; missing data is null / [].
// Profiles:
//   rich  — every component populated (design review of every state)
//   lana  — a realistic 4.4 account for Lana (contract v3 signed yesterday, 3 camarazi + Mama, 89 zones, 2 Inventar runs,
//           a few nights / meals / tours, phone in guard with a last position)
//   empty — a brand-new account (every empty state)
// `blobs` maps file ids / sleep chunks to binary content produced by the harness (scene images, text, PDF, audio).

const NOW = Date.parse('2026-09-28T19:40:00+03:00'); // Monday evening, Bucharest
const MIN = 60000, HOUR = 3600000, DAY = 86400000;
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const DEVICE = id(20);

// Midnight in Bucharest for `now` (the page runs with timezoneId Europe/Bucharest; node may run in UTC).
function midnight(now) {
  const [h, m, s] = new Intl.DateTimeFormat('en-GB', {timeZone: 'Europe/Bucharest', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'}).format(now).split(':').map(Number);
  return now - ((h * 60 + m) * 60 + s) * 1000 - (now % 1000);
}
function dateKey(ms) { return new Intl.DateTimeFormat('en-CA', {timeZone: 'Europe/Bucharest', year: 'numeric', month: '2-digit', day: '2-digit'}).format(ms); }

// The app's 150 m Web-Mercator grid (ExploreGrid): id "x_y".
function grid(size = 150) {
  const R = 6378137, rad = Math.PI / 180;
  const cellOf = (lat, lng) => [Math.floor(R * lng * rad / size), Math.floor(R * Math.log(Math.tan(Math.PI / 4 + lat * rad / 2)) / size)];
  const bounds = (x, y) => ({min_lng: x * size / R / rad, max_lng: (x + 1) * size / R / rad,
    min_lat: (2 * Math.atan(Math.exp(y * size / R)) - Math.PI / 2) / rad, max_lat: (2 * Math.atan(Math.exp((y + 1) * size / R)) - Math.PI / 2) / rad});
  return {cellOf, bounds};
}
/** Cells along legs [lat0,lng0,lat1,lng1,mode]; `modes:false` leaves `mode` out (cells synced before explore v2). */
function cellsAlong(legs, now, {modes = true, width = 0} = {}) {
  const {cellOf, bounds} = grid(), seen = new Map();
  legs.forEach(([a, b, c, d, mode], leg) => {
    for (let t = 0; t <= 1.0001; t += 0.004) {
      const [x0, y0] = cellOf(a + (c - a) * t, b + (d - b) * t);
      for (let dx = -width; dx <= width; dx++) for (let dy = -width; dy <= width; dy++) {
        const x = x0 + dx, y = y0 + dy, key = x + '_' + y;
        if (seen.has(key)) continue;
        const props = {id: key, first_at: now - DAY * (3 + leg), last_at: now - HOUR * (1 + leg), visits: 1 + (Math.abs(x + y) % 5), kind: 'explored'};
        if (modes && mode) props.mode = mode;
        const bb = bounds(x, y);
        seen.set(key, {type: 'Feature', geometry: {type: 'Polygon', coordinates: [[[bb.min_lng, bb.min_lat], [bb.max_lng, bb.min_lat], [bb.max_lng, bb.max_lat], [bb.min_lng, bb.max_lat], [bb.min_lng, bb.min_lat]]]}, properties: props});
      }
    }
  });
  return [...seen.values()];
}
/** Polyline "lat,lng;lat,lng;…" along waypoints, densified: it meanders like a walk on streets (≈100 m either side,
 *  still inside the explored cells) and carries a little GPS wobble. */
function polyline(points, step = 0.0006) {
  const out = [];
  for (let i = 0; i < points.length - 1; i++) {
    const [a, b] = points[i], [c, d] = points[i + 1], len = Math.hypot(c - a, d - b), n = Math.max(2, Math.ceil(len / step));
    const [pa, pb] = len ? [-(d - b) / len, (c - a) / len] : [0, 0];
    for (let k = 0; k < n; k++) {
      const t = k / n, bend = Math.sin(t * Math.PI * (2 + i)) * 0.0008 + Math.sin(t * Math.PI * 9) * 0.00025, w = Math.sin((i * 31 + k) * 1.7) * 0.00006;
      out.push([(a + (c - a) * t + pa * bend + w).toFixed(6), (b + (d - b) * t + pb * bend - w).toFixed(6)].join(','));
    }
  }
  const last = points.at(-1); out.push(last[0].toFixed(6) + ',' + last[1].toFixed(6));
  return out.join(';');
}

// Bucharest landmarks: Herăstrău → Victoriei → Cișmigiu → Unirii → Tineretului, plus Floreasca and Titan.
const P = {
  herastrau: [44.4700, 26.0820], herastrauE: [44.4760, 26.0950], floreasca: [44.4640, 26.1050], victoriei: [44.4530, 26.0860],
  romana: [44.4465, 26.0975], cismigiu: [44.4380, 26.0930], universitate: [44.4355, 26.1016], unirii: [44.4270, 26.1020],
  tineretului: [44.4125, 26.1060], carol: [44.4155, 26.0965], titan: [44.4240, 26.1720], obor: [44.4500, 26.1260], home: [44.4387, 26.0936]
};
const LEGS = [
  [...P.herastrau, ...P.victoriei, 'run'], [...P.victoriei, ...P.cismigiu, 'walk'], [...P.cismigiu, ...P.unirii, 'walk'],
  [...P.herastrau, ...P.herastrauE, 'run'], [...P.herastrauE, ...P.floreasca, 'run'], [...P.unirii, ...P.tineretului, 'ride'],
  [...P.tineretului, ...P.carol, 'walk'], [...P.romana, ...P.obor, 'ride'], [...P.cismigiu, ...P.universitate, 'walk']
];

function links(now, spec) {
  const keys = ['teren', 'camarazi', 'gasire', 'inventar', 'somn', 'ratie', 'mars', 'muzica', 'paza', 'cont'];
  return keys.map(key => { const [state, ago, count] = spec[key] || ['off', null, null]; return {key, state, lastAt: ago === null ? null : now - ago, count: count ?? null}; });
}
function pipes(now, spec) {
  return ['sesiune', 'galerie', 'explorare', 'agenda', 'somn', 'gasire', 'mese', 'miscare', 'muzica', 'inventar'].map(key => ({key, lastAt: spec[key] === undefined || spec[key] === null ? null : now - spec[key]}));
}
function night(n, startAt, minutes, score, audio, extra = {}) {
  const deep = Math.round(minutes * (0.18 + (n % 3) * 0.02)), rem = Math.round(minutes * 0.22), light = minutes - deep - rem;
  return {id: 's' + n, startAt, endAt: startAt + minutes * MIN, minutes, score, deepMin: deep, lightMin: light, remMin: rem, snoreMin: audio === 'ready' ? 2 : (n * 7) % 23, talkCount: audio === 'ready' ? 2 : n % 4, coverageMin: audio === 'none' ? 0 : minutes - 12, summary: null, audio, ...extra};
}
function nightDetail(nt) {
  const chunkMs = 30 * MIN, chunks = [];
  for (let i = 0, t = nt.startAt; t < nt.endAt; i++, t += chunkMs) chunks.push({i, startAt: t, durationMs: Math.min(chunkMs, nt.endAt - t)});
  const ev = (mins, kind, label, text, dur) => { const t = nt.startAt + mins * MIN, chunk = Math.floor(mins / 30); return {t, kind, label, text, chunk, offsetMs: (mins - chunk * 30) * MIN + 12000, durationMs: dur}; };
  return {id: nt.id, summary: nt.summary, chunks, events: [
    ev(24, 'snore', 'Sforăit', null, 38000), ev(71, 'talk', 'Vorbit', 'Nu, lasă, mâine dimineață.', 4200), ev(118, 'snore', 'Sforăit', null, 52000),
    ev(196, 'cough', 'Tuse', null, 3000), ev(262, 'talk', 'Vorbit', 'Unde e harta?', 2600), ev(305, 'noise', 'Zgomot', null, 5000), ev(388, 'snore', 'Sforăit', null, 41000)]};
}
function meal(at, mealType, name, kcal, p, c, f, grams, source, confidence) { return {id: 'm' + Math.round(at / 1000), at, name, kcal, protein: p, carbs: c, fat: f, grams, mealType, source, confidence}; }
function dayTotals(date, meals) { const sum = k => meals.reduce((a, m) => a + (m[k] || 0), 0); return {date, kcal: sum('kcal'), protein: sum('protein'), carbs: sum('carbs'), fat: sum('fat'), meals}; }
function vaultItems(now, list, blobs) {
  return list.map(([n, name, scene, kind, preview, media, bytes, agoMin]) => {
    const fid = id(100 + n);
    if (scene) blobs['file:' + fid] = {scene}; else if (preview === 'pdf') blobs['file:' + fid] = {pdf: ['FORJA · document', name, 'Pagina de test a harnașamentului.']}; else blobs['file:' + fid] = {text: 'Listă pentru weekend\n\n· apă\n· hartă\n· o carte bună'};
    return {id: fid, name, kind, preview, media_type: media, bytes, received_at: now - agoMin * MIN, expires_at: now - agoMin * MIN + DAY, folder: null, thumbnail: !!scene, device_id: DEVICE};
  });
}

function rich(now = NOW) {
  const day0 = midnight(now), blobs = {};
  const cells = cellsAlong(LEGS, now, {width: 1});
  const places = [
    {id: 'p1', lat: 44.4705, lng: 26.0826, name: 'Herăstrău · debarcader', stars: 5, note: 'Alergarea de joi, cu Ana.', recommended: true, stay_ms: 5.5 * HOUR, first_at: now - 30 * DAY, last_at: now - 2 * DAY, visits: 14},
    {id: 'p2', lat: 44.4385, lng: 26.0934, name: 'Acasă', stars: 4, note: '', recommended: false, stay_ms: 90 * HOUR, first_at: now - 60 * DAY, last_at: now - 20 * MIN, visits: 58},
    {id: 'p3', lat: 44.4352, lng: 26.1012, name: 'Sala Forța', stars: 4, note: 'Luni, miercuri, vineri.', recommended: false, stay_ms: 9 * HOUR, first_at: now - 40 * DAY, last_at: now - DAY, visits: 11},
    {id: 'p4', lat: 44.4150, lng: 26.0972, name: 'Parcul Carol', stars: 3, note: '', recommended: false, stay_ms: 6 * HOUR, first_at: now - 20 * DAY, last_at: now - 4 * DAY, visits: 5},
    {id: 'p5', lat: 44.4535, lng: 26.0868, name: '', stars: 0, note: '', recommended: false, stay_ms: 5.2 * HOUR, first_at: now - 9 * DAY, last_at: now - 9 * DAY, visits: 1}
  ].map(p => ({...p, lon: p.lng, updated_at: now - 3 * DAY}));
  const routeDefs = [
    ['a41', 'run', [P.herastrau, P.victoriei], 2], ['a40', 'walk', [P.victoriei, P.romana, P.cismigiu], 5], ['a39', 'ride', [P.unirii, P.tineretului, P.carol], 26],
    ['a38', 'run', [P.herastrau, P.herastrauE, P.floreasca], 50], ['a37', 'walk', [P.cismigiu, P.universitate, P.unirii], 74], ['a36', 'ride', [P.romana, P.obor], 98]];
  const routes = routeDefs.map(([rid, type, pts, agoH], i) => ({id: rid, type, startAt: now - agoH * HOUR, distanceM: [5100, 3300, 9800, 6400, 2700, 7200][i], durationS: [1920, 2700, 2280, 2400, 2100, 1500][i], polyline: polyline(pts)}));
  const nights = [
    night(31, day0 - 40 * MIN, 444, 86, 'ready', {summary: 'Somn bun. Ai sforăit scurt de trei ori și ai vorbit de două ori prin somn.'}),
    night(30, day0 - DAY + 23.6 * HOUR - DAY, 402, 78, 'ready'), night(29, day0 - 2 * DAY - 70 * MIN, 431, 81, 'ready'), night(28, day0 - 3 * DAY - 20 * MIN, 468, 90, 'ready'),
    night(27, day0 - 4 * DAY + 10 * MIN, 385, 72, 'pending'), night(26, day0 - 5 * DAY - 50 * MIN, 452, 84, 'none'), night(25, day0 - 6 * DAY - 30 * MIN, 420, 80, 'none'),
    night(24, day0 - 7 * DAY - 45 * MIN, 398, 76, 'none'), night(23, day0 - 8 * DAY - 15 * MIN, 476, 91, 'none'), night(22, day0 - 9 * DAY - 60 * MIN, 410, 79, 'none'),
    night(21, day0 - 10 * DAY - 25 * MIN, 440, 85, 'none'), night(20, day0 - 11 * DAY - 35 * MIN, 365, 68, 'none'), night(19, day0 - 12 * DAY - 5 * MIN, 455, 87, 'none'), night(18, day0 - 13 * DAY - 40 * MIN, 430, 82, 'none')];
  nights[1].startAt = day0 - DAY - 25 * MIN; nights[1].endAt = nights[1].startAt + 402 * MIN;
  const somnDetail = Object.fromEntries(nights.filter(n => n.audio === 'ready').map(n => [n.id, nightDetail(n)]));
  nights.filter(n => n.audio === 'ready').forEach(n => somnDetail[n.id].chunks.forEach(c => { blobs['chunk:' + n.id + ':' + c.i] = {audio: true}; }));
  const today = [meal(day0 + 8.2 * HOUR, 0, 'Ovăz cu fructe de pădure', 410, 16, 62, 11, 320, 'ESTIMAT', 0.78), meal(day0 + 13.1 * HOUR, 1, 'Ciorbă de legume și pâine', 560, 21, 74, 18, 520, 'ESTIMAT', 0.7),
    meal(day0 + 16.4 * HOUR, 3, 'Iaurt grecesc 2%', 160, 17, 8, 4, 170, 'EXACT · COD DE BARE', 1), meal(day0 + 19.1 * HOUR, 2, 'Somon cu orez', 410, 38, 24, 21, 380, 'MANUAL', 1)];
  const ratieDays = [dayTotals(dateKey(now), today)];
  for (let i = 1; i < 30; i++) {
    if (i % 9 === 4) continue;
    const base = day0 - i * DAY, k = 1700 + ((i * 137) % 700);
    ratieDays.push(dayTotals(dateKey(base + 12 * HOUR), [meal(base + 8 * HOUR, 0, 'Mic dejun', Math.round(k * 0.25), 20, 50, 12, 300, 'ESTIMAT', 0.7), meal(base + 13 * HOUR, 1, 'Prânz', Math.round(k * 0.4), 35, 80, 22, 450, 'MANUAL', 1), meal(base + 19 * HOUR, 2, 'Cină', Math.round(k * 0.35), 30, 60, 20, 400, 'ESTIMAT', 0.6)]));
  }
  const files = vaultItems(now, [[1, 'IMG_20260928_181204.jpg', 'mountain', 'photo', 'image', 'image/png', 2412000, 25], [2, 'IMG_20260928_132210.jpg', 'food', 'photo', 'image', 'image/png', 1980000, 60], [3, 'IMG_20260928_090112.jpg', 'city', 'photo', 'image', 'image/png', 2804000, 120],
    [4, 'IMG_20260927_201515.jpg', 'sea', 'photo', 'image', 'image/png', 3104000, 300], [5, 'IMG_20260927_192045.jpg', 'cake', 'photo', 'image', 'image/png', 1504000, 330], [6, 'Screenshot_bilet_CFR.png', 'screenshot', 'photo', 'image', 'image/png', 402000, 400],
    [7, 'IMG_20260927_171002.jpg', 'forest', 'photo', 'image', 'image/png', 2604000, 420], [8, 'IMG_20260927_160033.jpg', 'cat', 'photo', 'image', 'image/png', 1604000, 480],
    [9, 'Contract închiriere.pdf', null, 'file', 'pdf', 'application/pdf', 254000, 90], [10, 'Lista pentru weekend.txt', null, 'file', 'text', 'text/plain', 1200, 200]], blobs);
  return {
    now, blobs, profile: 'rich',
    azi: {me: {uid: 'demo-owner', name: 'Lana Popescu', email: 'lana@example.test'},
      today: {date: dateKey(now), kcal: 1540, kcalTarget: 2100, protein: 92, carbs: 168, fat: 54, meals: 4, moveMin: 62, km: 6.8, workouts: 1},
      night: {id: nights[0].id, startAt: nights[0].startAt, endAt: nights[0].endAt, minutes: nights[0].minutes, score: nights[0].score, summary: nights[0].summary},
      links: links(now, {teren: ['on', 25 * MIN, 214], camarazi: ['on', 2 * MIN, 5], gasire: ['on', MIN, 1], inventar: ['on', 5 * HOUR, 4], somn: ['on', 12 * HOUR, 14], ratie: ['on', 40 * MIN, 4], mars: ['on', 2 * HOUR, 8], muzica: ['on', 3 * MIN, 10], paza: ['stale', 30 * HOUR, 7], cont: ['on', 3 * DAY, null]}),
      updated_at: now - 20000},
    cerc: {me: {lat: P.home[0], lng: P.home[1], at: now - 3 * MIN, ghost: false, ghostUntil: null, state: 'walk', nowPlaying: {title: 'Vama Veche', artist: 'Vama', app: 'Spotify', at: now - 2 * MIN}, exploreCells: cells.length},
      friends: [
        {uid: 'f-ana', name: 'Ana Ionescu', initials: 'AI', lat: 44.4462, lng: 26.0985, at: now - 2 * MIN, state: 'run', ghost: false, viaFamily: false, nowPlaying: {title: 'Fetele care ard', artist: 'Carla’s Dreams', app: 'Spotify', at: now - 4 * MIN}, exploreCells: 268},
        {uid: 'f-mihai', name: 'Mihai Dobre', initials: 'MD', lat: 44.4291, lng: 26.1102, at: now - 6 * MIN, state: 'ride', ghost: false, viaFamily: false, nowPlaying: null, exploreCells: 190},
        // Radu e fantomă pentru toți, dar te are în familie: P1 îl trimite și în friends (ghost, fără poziție), și în family
        // (poziția din familyLoc) — exact cazul pe care site-ul trebuie să-l arate „fantomă · te vede familia”.
        {uid: 'f-radu', name: 'Radu Stan', initials: 'RS', lat: null, lng: null, at: null, state: 'ghost', ghost: true, viaFamily: false, nowPlaying: null, exploreCells: 102},
        {uid: 'f-ioana', name: 'Ioana Matei', initials: 'IM', lat: null, lng: null, at: null, state: null, ghost: true, viaFamily: false, nowPlaying: null, exploreCells: 77},
        {uid: 'f-vlad', name: 'Vlad Georgescu', initials: 'VG', lat: 44.4520, lng: 26.1280, at: now - 3 * HOUR, state: 'idle', ghost: false, viaFamily: false, nowPlaying: null, exploreCells: 45}],
      family: [{uid: 'fam-mama', name: 'Mama', initials: 'M', lat: 44.4222, lng: 26.1330, at: now - 9 * MIN}, {uid: 'f-radu', name: 'Radu Stan', initials: 'RS', lat: 44.4195, lng: 26.0820, at: now - 40 * MIN}],
      recommended: [{id: 'r1', ownerUid: 'f-ana', ownerName: 'Ana Ionescu', name: 'Cafeneaua din Cotroceni', stars: 5, note: 'Cea mai bună cafea de după alergare.', lat: 44.4318, lng: 26.0765, visits: 6},
        {id: 'r2', ownerUid: 'f-mihai', ownerName: 'Mihai Dobre', name: 'Pista din Tineretului', stars: 4, note: '', lat: 44.4102, lng: 26.1098, visits: null}],
      routes, inviteCode: 'K7Q2XM', updated_at: now - 15000},
    explore: {owner: 'demo-owner', grid_m: 150, features: cells, places, updated_at: now - 25 * MIN},
    discovery: {discoverable: true, discovery_until: now + 24 * DAY},
    somn: {nights}, somnDetail,
    ratie: {targets: {kcal: 2100, protein: 120, carbs: 230, fat: 70}, days: ratieDays},
    mars: {activities: routes.map(r => ({id: r.id, type: r.type, startAt: r.startAt, endAt: r.startAt + r.durationS * 1000, distanceM: r.distanceM, durationS: r.durationS, kcal: Math.round(r.distanceM / 14), polyline: r.polyline}))
      .concat([{id: 'a35', type: 'walk', startAt: now - 8 * DAY, endAt: now - 8 * DAY + 1800000, distanceM: 2100, durationS: 1800, kcal: 120, polyline: ''}]),
      workouts: [{id: 'w12', startAt: now - 5 * HOUR, endAt: now - 4.2 * HOUR, durationS: 2880, title: 'Forță · picioare', kind: 'forta', sets: 16, volumeKg: 5840, kcal: 310},
        {id: 'w11', startAt: now - 2 * DAY, endAt: now - 2 * DAY + 2400000, durationS: 2400, title: 'Împins și tras', kind: 'forta', sets: 14, volumeKg: 4210, kcal: 260},
        {id: 'w10', startAt: now - 4 * DAY, endAt: now - 4 * DAY + 900000, durationS: 900, title: 'Mobilitate', kind: 'acasa', sets: 6, volumeKg: null, kcal: 70}],
      week: {km: 27.1, minutes: 312, sessions: 7}},
    muzica: {now: {title: 'Vama Veche', artist: 'Vama', app: 'Spotify', at: now - 2 * MIN},
      summary: {updatedAt: now - 3 * HOUR, windowDays: 7, totalMinutes: 612, top: [
        {title: 'Fetele care ard', artist: 'Carla’s Dreams', plays: 23, minutes: 81, app: 'Spotify'}, {title: 'Vama Veche', artist: 'Vama', plays: 17, minutes: 60, app: 'Spotify'},
        {title: 'Ploaia în luna lui Marte', artist: 'Nicu Alifantis', plays: 12, minutes: 49, app: 'YouTube Music'}, {title: 'Anotimpul', artist: 'Subcarpați', plays: 11, minutes: 42, app: 'Spotify'},
        {title: 'Tot ce vreau', artist: 'Holograf', plays: 9, minutes: 36, app: 'Spotify'}, {title: 'Dacă ploaia s-ar opri', artist: 'Cargo', plays: 8, minutes: 38, app: 'Spotify'},
        {title: 'Omul negru', artist: 'Phoenix', plays: 7, minutes: 31, app: null}, {title: 'Nu e ușor', artist: 'Șuie Paparude', plays: 6, minutes: 24, app: 'Spotify'},
        {title: 'Iarna pe uliță', artist: 'Taraful din Clejani', plays: 4, minutes: 12, app: 'YouTube Music'}, {title: 'Marinarul', artist: 'Timpuri Noi', plays: 3, minutes: 11, app: 'Spotify'}]}},
    paza: {updated_at: now - 12 * MIN, days: Array.from({length: 7}, (_, i) => {
      const date = dateKey(now - i * DAY), f = 1 - i * 0.07;
      const apps = [['WhatsApp', 'com.whatsapp', 64, 41], ['FORJA', 'com.forja.app.research', 38, 12], ['Instagram', 'com.instagram.android', 52, 27], ['Spotify', 'com.spotify.music', 18, 6], ['Chrome', 'com.android.chrome', 31, 15], ['Maps', 'com.google.android.apps.maps', 12, 4], ['YouTube', 'com.google.android.youtube', 27, 5]]
        .map(([label, pkg, m, o]) => ({label, pkg, minutes: Math.round(m * f * (1 + ((i * pkg.length) % 5) / 10)), opens: Math.round(o * f)}));
      return {date, totalMin: apps.reduce((a, x) => a + x.minutes, 0), apps};
    })},
    inventar: {runs: [
      {id: 'inv-20260928-photos', kind: 'photos', startedAt: now - 5.4 * HOUR, finishedAt: now - 5 * HOUR, appVersion: '4.4', scope: {mode: 'last', n: 1000, label: 'Ultimele 1 000'}, dest: {label: 'Galerie · FORJA', path: 'Pictures/FORJA'},
        folders: [['Munte · Sinaia', 214, 812e6], ['Mâncare', 96, 240e6], ['Capturi · bilete', 61, 38e6], ['Oraș noaptea', 58, 190e6], ['Pisici', 44, 150e6], ['Mare · Vama Veche', 41, 180e6], ['Aniversări', 33, 120e6], ['Documente scanate', 19, 30e6], ['Pădure', 12, 60e6], ['Diverse · 2026', 9, 12e6]].map(([name, count, bytes]) => ({name, count, bytes})),
        trash: {count: 212, bytes: 1.2e9}, moved: 587, failed: 3, freedBytes: 1.2e9},
      {id: 'inv-20260925-docs', kind: 'docs', startedAt: now - 3.2 * DAY, finishedAt: now - 3.19 * DAY, appVersion: '4.4', scope: {mode: 'folder', n: null, label: 'Download'}, dest: {label: 'În folderul ales', path: 'Download/Organizate'},
        folders: [['Facturi', 34, 18e6], ['Contracte', 12, 22e6], ['Bilete și rezervări', 9, 4e6], ['Cărți', 5, 60e6]].map(([name, count, bytes]) => ({name, count, bytes})), trash: {count: 27, bytes: 310e6}, moved: 60, failed: 0, freedBytes: null},
      {id: 'inv-20260918-photos', kind: 'photos', startedAt: now - 10 * DAY, finishedAt: now - 10 * DAY + 40 * MIN, appVersion: '4.3', scope: {mode: 'all', n: null, label: 'Toată galeria'}, dest: {label: 'Galerie · FORJA', path: 'Pictures/FORJA'},
        folders: [['Vacanța din iulie', 320, 1.4e9], ['Familie', 150, 600e6], ['Mâncare', 88, 200e6]].map(([name, count, bytes]) => ({name, count, bytes})), trash: {count: 140, bytes: 800e6}, moved: 558, failed: 0, freedBytes: 800e6}],
      vault: {total: files.length, latestAt: now - 25 * MIN}},
    files,
    cont: {me: {uid: 'demo-owner', name: 'Lana Popescu', email: 'lana@example.test'}, contract: {version: 3, at: now - 3 * DAY, revokedAt: null, current: 3},
      pipes: pipes(now, {sesiune: 2 * MIN, galerie: 25 * MIN, explorare: 25 * MIN, agenda: 20 * HOUR, somn: 12 * HOUR, gasire: MIN, mese: 40 * MIN, miscare: 2 * HOUR, muzica: 3 * HOUR, inventar: 5 * HOUR}), intake: {paused: false}},
    intake: {accepting: true, revision: 4},
    devices: [{id: DEVICE, name: 'Galaxy S23', basis: 'contract', seen_at: now - 50000, online: true, status: 'ready', battery: 64, charging: false,
      last: {lat: 44.43868, lon: 26.09372, accuracy: 12, at: now - 3 * MIN}, position: null, command: null}]
  };
}

function lana(now = NOW) {
  const day0 = midnight(now), blobs = {};
  const legs = LEGS.slice(0, 3).map(l => [...l.slice(0, 4), 'walk']).concat([[...P.cismigiu, ...P.universitate, 'walk']]);
  const cells = cellsAlong(legs, now, {modes: false}).slice(0, 89).map((c, i) => (i % 7 === 0 ? {...c, properties: {...c.properties, mode: 'run'}} : c));
  const places = [
    {id: 'p1', lat: 44.4386, lng: 26.0935, name: 'Acasă', stars: 5, note: '', recommended: false, stay_ms: 80 * HOUR, first_at: now - 30 * DAY, last_at: now - 30 * MIN, visits: 21},
    {id: 'p2', lat: 44.4352, lng: 26.1012, name: 'Sala', stars: 4, note: '', recommended: false, stay_ms: 6 * HOUR, first_at: now - 20 * DAY, last_at: now - 2 * DAY, visits: 6},
    {id: 'p3', lat: 44.4531, lng: 26.0862, name: '', stars: 0, note: '', recommended: false, stay_ms: 5.4 * HOUR, first_at: now - 6 * DAY, last_at: now - 6 * DAY, visits: 1},
    {id: 'p4', lat: 44.4288, lng: 26.1015, name: '', stars: 0, note: '', recommended: false, stay_ms: 5.1 * HOUR, first_at: now - 3 * DAY, last_at: now - 3 * DAY, visits: 1}].map(p => ({...p, lon: p.lng, updated_at: now - DAY}));
  const routes = [['a12', 'walk', [P.cismigiu, P.universitate, P.unirii], 26], ['a11', 'run', [P.herastrau, P.victoriei], 76], ['a10', 'walk', [P.victoriei, P.romana, P.cismigiu], 150]]
    .map(([rid, type, pts, agoH], i) => ({id: rid, type, startAt: now - agoH * HOUR, distanceM: [2700, 5100, 3300][i], durationS: [2100, 1980, 2700][i], polyline: polyline(pts)}));
  // Azi arată doar noaptea încheiată în ultimele 36 h (site-api.mjs), deci ultima noapte e cea de azi-noapte.
  const nights = [night(4, day0 - 35 * MIN, 412, 79, 'ready', {summary: 'Ai dormit bine. Ai vorbit de două ori prin somn, scurt.'}), night(3, day0 - 2 * DAY - 10 * MIN, 385, 74, 'ready'), night(2, day0 - 3 * DAY - 70 * MIN, 450, 83, 'none'), night(1, day0 - 5 * DAY - 20 * MIN, 398, 71, 'none')];
  const somnDetail = Object.fromEntries(nights.filter(n => n.audio === 'ready').map(n => [n.id, nightDetail(n)]));
  nights.filter(n => n.audio === 'ready').forEach(n => somnDetail[n.id].chunks.forEach(c => { blobs['chunk:' + n.id + ':' + c.i] = {audio: true}; }));
  const today = [meal(day0 + 9 * HOUR, 0, 'Omletă cu legume', 380, 24, 10, 26, 250, 'ESTIMAT', 0.74), meal(day0 + 14 * HOUR, 1, 'Paste cu pui', 690, 42, 82, 19, 420, 'ESTIMAT', 0.66)];
  const files = vaultItems(now, [[1, 'IMG_20260928_174410.jpg', 'city', 'photo', 'image', 'image/png', 2412000, 110], [2, 'IMG_20260928_131002.jpg', 'food', 'photo', 'image', 'image/png', 1980000, 390],
    [3, 'IMG_20260928_100515.jpg', 'cat', 'photo', 'image', 'image/png', 1804000, 560], [4, 'IMG_20260927_205011.jpg', 'sea', 'photo', 'image', 'image/png', 3104000, 1300], [5, 'Screenshot_20260927.png', 'screenshot', 'photo', 'image', 'image/png', 402000, 1400]], blobs);
  return {
    now, blobs, profile: 'lana',
    azi: {me: {uid: 'lana-uid', name: 'Lana', email: 'lana@example.test'},
      today: {date: dateKey(now), kcal: 1070, kcalTarget: 1900, protein: 66, carbs: 92, fat: 45, meals: 2, moveMin: 35, km: 2.7, workouts: 0},
      night: {id: nights[0].id, startAt: nights[0].startAt, endAt: nights[0].endAt, minutes: nights[0].minutes, score: nights[0].score, summary: nights[0].summary},
      links: links(now, {teren: ['on', 70 * MIN, 89], camarazi: ['on', 4 * MIN, 3], gasire: ['on', 2 * MIN, 1], inventar: ['on', 26 * HOUR, 2], somn: ['on', 13 * HOUR, 4], ratie: ['on', 5.5 * HOUR, 2], mars: ['stale', 26 * HOUR, 3], muzica: ['on', 5 * HOUR, 7], paza: ['on', 40 * MIN, 3], cont: ['on', DAY, null]}),
      updated_at: now - 30000},
    cerc: {me: {lat: 44.43872, lng: 26.09358, at: now - 4 * MIN, ghost: false, ghostUntil: null, state: 'idle', nowPlaying: null, exploreCells: cells.length},
      friends: [
        {uid: 'f-andrei', name: 'Andrei', initials: 'A', lat: 44.4471, lng: 26.0999, at: now - 6 * MIN, state: 'walk', ghost: false, viaFamily: false, nowPlaying: {title: 'Anotimpul', artist: 'Subcarpați', app: 'Spotify', at: now - 5 * MIN}, exploreCells: 140},
        {uid: 'f-bianca', name: 'Bianca Radu', initials: 'BR', lat: 44.4262, lng: 26.1182, at: now - 25 * MIN, state: 'idle', ghost: false, viaFamily: false, nowPlaying: null, exploreCells: 61},
        {uid: 'f-cristi', name: 'Cristi', initials: 'C', lat: null, lng: null, at: null, state: null, ghost: true, viaFamily: false, nowPlaying: null, exploreCells: null}],
      family: [{uid: 'fam-mama', name: 'Mama', initials: 'M', lat: 44.4210, lng: 26.1290, at: now - 12 * MIN}],
      recommended: [{id: 'r1', ownerUid: 'f-andrei', ownerName: 'Andrei', name: 'Terasa din Grădina Icoanei', stars: 4, note: 'Liniște după 18:00.', lat: 44.4449, lng: 26.1036, visits: 3}],
      routes, inviteCode: 'LNA4P2', updated_at: now - 20000},
    explore: {owner: 'lana-uid', grid_m: 150, features: cells, places, updated_at: now - 70 * MIN},
    discovery: {discoverable: true, discovery_until: now + 27 * DAY},
    somn: {nights}, somnDetail,
    ratie: {targets: {kcal: 1900, protein: 100, carbs: 200, fat: 65}, days: [dayTotals(dateKey(now), today), dayTotals(dateKey(now - DAY), [meal(day0 - DAY + 9 * HOUR, 0, 'Iaurt cu granola', 330, 15, 42, 11, 250, 'EXACT · COD DE BARE', 1), meal(day0 - DAY + 13 * HOUR, 1, 'Salată cu ton', 520, 34, 28, 29, 380, 'ESTIMAT', 0.7), meal(day0 - DAY + 20 * HOUR, 2, 'Supă cremă de linte', 460, 22, 58, 12, 450, 'MANUAL', 1)]),
      dayTotals(dateKey(now - 2 * DAY), [meal(day0 - 2 * DAY + 13 * HOUR, 1, 'Sarmale', 780, 32, 48, 44, 400, 'ESTIMAT', 0.6), meal(day0 - 2 * DAY + 19 * HOUR, 2, 'Omletă', 420, 26, 6, 30, 250, 'MANUAL', 1)])]},
    mars: {activities: routes.map(r => ({id: r.id, type: r.type, startAt: r.startAt, endAt: r.startAt + r.durationS * 1000, distanceM: r.distanceM, durationS: r.durationS, kcal: Math.round(r.distanceM / 15), polyline: r.polyline})),
      workouts: [{id: 'w3', startAt: now - 2 * DAY, endAt: now - 2 * DAY + 2700000, durationS: 2700, title: 'Instrucție · tot corpul', kind: 'forta', sets: 15, volumeKg: 3120, kcal: 240}, {id: 'w2', startAt: now - 5 * DAY, endAt: now - 5 * DAY + 2400000, durationS: 2400, title: 'Împins', kind: 'forta', sets: 12, volumeKg: 2480, kcal: 200}],
      week: {km: 11.1, minutes: 158, sessions: 5}},
    muzica: {now: null, summary: {updatedAt: now - 5 * HOUR, windowDays: 7, totalMinutes: 204, top: [
      {title: 'Anotimpul', artist: 'Subcarpați', plays: 9, minutes: 33, app: 'Spotify'}, {title: 'Fetele care ard', artist: 'Carla’s Dreams', plays: 8, minutes: 29, app: 'Spotify'}, {title: 'Tot ce vreau', artist: 'Holograf', plays: 6, minutes: 24, app: 'Spotify'},
      {title: 'Vama Veche', artist: 'Vama', plays: 5, minutes: 18, app: 'Spotify'}, {title: 'Marinarul', artist: 'Timpuri Noi', plays: 4, minutes: 15, app: 'Spotify'}, {title: 'Nu e ușor', artist: 'Șuie Paparude', plays: 3, minutes: 12, app: 'Spotify'}, {title: 'Omul negru', artist: 'Phoenix', plays: 2, minutes: 9, app: null}]}},
    paza: {updated_at: now - 40 * MIN, days: [0, 1, 2].map(i => { const apps = [['WhatsApp', 'com.whatsapp', 48 - i * 5, 33], ['Instagram', 'com.instagram.android', 41 + i * 3, 22], ['FORJA', 'com.forja.app.research', 22, 9], ['Spotify', 'com.spotify.music', 14, 4], ['Chrome', 'com.android.chrome', 19, 11]].map(([label, pkg, minutes, opens]) => ({label, pkg, minutes, opens})); return {date: dateKey(now - i * DAY), totalMin: apps.reduce((a, x) => a + x.minutes, 0), apps}; })},
    inventar: {runs: [
      {id: 'inv-lana-docs', kind: 'docs', startedAt: now - 26.4 * HOUR, finishedAt: now - 26 * HOUR, appVersion: '4.4', scope: {mode: 'folder', n: null, label: 'Download'}, dest: {label: 'În folderul ales', path: 'Download/Organizate'},
        folders: [['Facturi', 9, 4e6], ['Acte', 5, 12e6], ['Bilete', 3, 1e6], ['Cursuri', 2, 18e6]].map(([name, count, bytes]) => ({name, count, bytes})), trash: {count: 6, bytes: 42e6}, moved: 19, failed: 0, freedBytes: null},
      {id: 'inv-lana-photos', kind: 'photos', startedAt: now - 3 * DAY, finishedAt: now - 3 * DAY + 50 * MIN, appVersion: '4.3', scope: {mode: 'last', n: 500, label: 'Ultimele 500'}, dest: {label: 'Galerie · FORJA', path: 'Pictures/FORJA'},
        folders: [['Prieteni', 88, 310e6], ['Mâncare', 54, 150e6], ['Capturi', 47, 30e6], ['Pisica', 31, 90e6], ['Oraș', 22, 70e6]].map(([name, count, bytes]) => ({name, count, bytes})), trash: {count: 64, bytes: 410e6}, moved: 242, failed: 2, freedBytes: 410e6}],
      vault: {total: files.length, latestAt: now - 110 * MIN}},
    files,
    cont: {me: {uid: 'lana-uid', name: 'Lana', email: 'lana@example.test'}, contract: {version: 3, at: now - DAY, revokedAt: null, current: 3},
      pipes: pipes(now, {sesiune: 3 * MIN, galerie: 110 * MIN, explorare: 70 * MIN, agenda: 22 * HOUR, somn: 30 * HOUR, gasire: 2 * MIN, mese: 5.5 * HOUR, miscare: 26 * HOUR, muzica: 5 * HOUR, inventar: 26 * HOUR}), intake: {paused: false}},
    intake: {accepting: true, revision: 1},
    devices: [{id: DEVICE, name: 'Galaxy S23', basis: 'contract', seen_at: now - 2 * MIN, online: true, status: 'ready', battery: 71, charging: false, last: {lat: 44.43871, lon: 26.09359, accuracy: 16, at: now - 4 * MIN}, position: null, command: null}]
  };
}

function empty(now = NOW) {
  return {
    now, blobs: {}, profile: 'empty',
    azi: {me: {uid: 'new-uid', name: 'Alex', email: 'alex@example.test'}, today: {date: dateKey(now), kcal: 0, kcalTarget: null, protein: 0, carbs: 0, fat: 0, meals: 0, moveMin: 0, km: 0, workouts: 0}, night: null, links: links(now, {}), updated_at: now},
    cerc: {me: null, friends: [], family: [], recommended: [], routes: [], inviteCode: null, updated_at: now},
    explore: {owner: 'new-uid', grid_m: 150, features: [], places: [], updated_at: 0},
    discovery: {discoverable: false, discovery_until: null},
    somn: {nights: []}, somnDetail: {},
    ratie: {targets: null, days: []},
    mars: {activities: [], workouts: [], week: {km: 0, minutes: 0, sessions: 0}},
    muzica: {now: null, summary: null},
    paza: {updated_at: null, days: []},
    inventar: {runs: [], vault: {total: 0, latestAt: null}},
    files: [],
    cont: {me: {uid: 'new-uid', name: 'Alex', email: 'alex@example.test'}, contract: {version: null, at: null, revokedAt: null, current: 3}, pipes: pipes(now, {}), intake: {paused: false}},
    intake: {accepting: true, revision: 0},
    devices: []
  };
}

// Mirror (pachetul C): Lana cu contractul v4 — oglinda galeriei și a documentelor, jocurile, mesele cu analiză și poză,
// antrenamentele pe exerciții. Profilul „mirror”; celelalte rămân cum erau.
function mirror(now = NOW) {
  const f = lana(now), day0 = midnight(now), blobs = f.blobs;
  const scenes = ['mountain', 'forest', 'sea', 'cake', 'food', 'city', 'cat', 'screenshot'];
  const albums = [['Camera', 46], ['WhatsApp Images', 18], ['Munte · Sinaia', 12], ['Screenshots', 9], ['Pisica', 7]];
  const items = [];
  let n = 0;
  for (const [album, count] of albums) for (let i = 0; i < count; i++, n++) {
    const video = album === 'Camera' && i % 9 === 4, fid = id(500 + n), taken = now - (n * 7.3 + (album === 'Camera' ? 0 : 40)) * HOUR;
    blobs['mirror:' + fid] = {scene: scenes[(n * 3) % scenes.length]};
    items.push({id: fid, kind: video ? 'video' : 'photo', name: (video ? 'VID_' : 'IMG_') + dateKey(taken).replace(/-/g, '') + '_' + String(100000 + n * 713).slice(0, 6) + (video ? '.mp4' : '.jpg'), album, group: 'gallery',
      media_type: video ? 'video/mp4' : 'image/jpeg', taken_at: taken, received_at: now - n * MIN, width: video ? 1920 : 2048, height: video ? 1080 : 1536, duration_ms: video ? 14000 + n * 1000 : null,
      orig_bytes: video ? 48e6 : 3.1e6, bytes: video ? 420000 : 380000, file: !video, thumb: true, poster: video, preview: video ? 'poster' : 'image'});
  }
  for (const [i, [name, album]] of [['Factura Enel septembrie.pdf', 'Documents/Organizate/Facturi'], ['Factura Digi august.pdf', 'Documents/Organizate/Facturi'], ['Contract chirie 2026.pdf', 'Documents/Organizate/Contracte'], ['Lista de cumpărături.txt', 'Download'], ['Bilet CFR Sinaia.pdf', 'Documents/Organizate/Bilete']].entries()) {
    const fid = id(900 + i);
    blobs['mirror:' + fid] = name.endsWith('.txt') ? {text: 'Listă\n\n· apă\n· hartă\n· o carte bună'} : {pdf: ['FORJA · document', name, 'Copia exactă din telefon.']};
    items.push({id: fid, kind: 'file', name, album, group: 'docs', media_type: name.endsWith('.txt') ? 'text/plain' : 'application/pdf', taken_at: now - (i + 2) * DAY, received_at: now - HOUR, width: null, height: null, duration_ms: null,
      orig_bytes: 180000 + i * 40000, bytes: 180000 + i * 40000, file: true, thumb: false, poster: false, preview: name.endsWith('.txt') ? 'text' : 'pdf'});
  }
  const byAlbum = new Map();
  for (const it of items) { const k = it.group + '/' + it.album; const a = byAlbum.get(k) || {album: it.album, group: it.group, count: 0, bytes: 0, latestAt: 0, cover: null, kinds: {photo: 0, video: 0, file: 0}}; a.count++; a.bytes += it.bytes; a.kinds[it.kind]++; if (it.taken_at > a.latestAt) { a.latestAt = it.taken_at; if (it.thumb) a.cover = it.id; } byAlbum.set(k, a); }
  const count = {photo: items.filter(i => i.kind === 'photo').length, video: items.filter(i => i.kind === 'video').length, file: items.filter(i => i.kind === 'file').length};
  f.mirrorItems = items.sort((a, b) => b.taken_at - a.taken_at);
  f.profile = 'mirror';
  f.inventar = {...f.inventar, runs: f.inventar.runs.map((r, i) => i === 0 ? {...r, state: 'done', provider: 'Gemini', updatedAt: r.finishedAt,
    folders: r.folders.map((x, j) => ({...x, theme: ['facturi de utilități, 2025–2026', 'acte de identitate și contracte', 'bilete de tren și rezervări', 'cursuri și notițe'][j] || null})),
    trash: {...r.trash, byReason: {duplicate: 4, temp: 2}}} : {...r, state: 'done', provider: 'Gemini', trash: {...r.trash, byReason: {duplicate: 31, similar: 18, blurry: 9, old_screenshot: 6}, expiresAt: r.finishedAt + 30 * DAY},
    failures: {owned: 2}, folders: r.folders.map((x, j) => ({...x, theme: ['prieteni la terasă și la munte', 'farfurii de acasă', 'capturi de ecran cu bilete', 'pisica acasă', 'străzi din centru'][j], covers: ['c0', 'c1', 'c2', 'c3']}))}),
    mirror: {stats: {items: items.length, bytes: items.reduce((a, i) => a + i.bytes, 0) + 3.1e9, count: {photo: count.photo + 7400, video: count.video + 88, file: count.file + 91}, latestAt: now - MIN, updatedAt: now - MIN},
      albums: [...byAlbum.values()].sort((a, b) => b.latestAt - a.latestAt), meter: {used: items.reduce((a, i) => a + i.bytes, 0) + 3.1e9, cap: 8e9, free_tier: 1e10, covers: 2.1e6, meals: 1.6e7, server: 5.4e9, server_limit: 9e9}, consent: {on: true, at: now - DAY}},
    storage: {photos: {count: 12480, bytes: 38e9}, videos: {count: 210, bytes: 9.4e9}, docs: {loose: 214, organized: 96, bytes: 2.1e8, folders: 7}, gallery: {total: 12690, mirrored: 7550, waiting: 5140, held: 31, state: 'wifi', cellular: false, lastAt: now - 20 * MIN}, updatedAt: now - 20 * MIN},
    games: [{id: 'zid', label: 'ZID', levels: 15, unlocked: 7, cleared: 6, starsTotal: 14, endlessBest: 0, stars: {1: 3, 2: 3, 3: 2, 4: 2, 5: 3, 6: 1}, playedS: 7680, playedToday: 900, lastAt: now - 3 * HOUR,
      plays: [{at: now - 3 * HOUR, level: 6, outcome: 'won', stars: 1, score: 2140, durationS: 260}, {at: now - 3.2 * HOUR, level: 6, outcome: 'lost', stars: 0, score: 980, durationS: 190}, {at: now - DAY, level: 5, outcome: 'won', stars: 3, score: 3020, durationS: 240}]},
      {id: 'asalt', label: 'ASALT', levels: 12, unlocked: 3, cleared: 2, starsTotal: 5, endlessBest: null, stars: {1: 3, 2: 2}, playedS: 1500, playedToday: 0, lastAt: now - 2 * DAY, plays: [{at: now - 2 * DAY, level: 3, outcome: 'lost', stars: 0, score: 410, durationS: 150}]}]};
  blobs['cover:c0'] = {scene: 'city'}; blobs['cover:c1'] = {scene: 'food'}; blobs['cover:c2'] = {scene: 'cat'}; blobs['cover:c3'] = {scene: 'sea'};
  const d0 = f.ratie.days[0];
  d0.meals[1] = {...d0.meals[1], photo: true, items: [{name: 'Paste', grams: 250, kcal: 390, protein: 13, carbs: 72, fat: 4}, {name: 'Piept de pui', grams: 120, kcal: 200, protein: 29, carbs: 0, fat: 9}, {name: 'Sos de roșii', grams: 50, kcal: 100, protein: 0, carbs: 10, fat: 6}],
    score: {value: 7, reason: 'Proteine bune, puține legume.'}, tip: 'Pune o salată lângă, data viitoare.'};
  blobs['meal:' + d0.meals[1].id] = {scene: 'food'};
  f.ratie.more = true; f.ratie.from = dateKey(now - 29 * DAY);
  f.mars.workouts = [{id: 'wk-1', startAt: now - DAY, endAt: now - DAY + 2900000, durationS: 2900, title: 'Forță · tot corpul', kind: 'forta', sets: 11, volumeKg: 3860, kcal: null, source: 'instructie', completed: false, plannedSets: 12,
    exercises: [{name: 'Genuflexiuni', sets: [8, 8, 8].map((r, i) => ({reps: r, load: '62,5', kg: 62.5, at: now - DAY + (i + 1) * 180000}))}, {name: 'Împins la piept', sets: [10, 10, 8].map((r, i) => ({reps: r, load: '40', kg: 40, at: now - DAY + 900000 + i * 150000}))},
      {name: 'Ramat cu gantera', sets: [12, 12, 12].map((r, i) => ({reps: r, load: '2×14', kg: 28, at: now - DAY + 1600000 + i * 120000}))}, {name: 'Flotări', sets: [15, 12].map((r, i) => ({reps: r, load: 'corp', kg: null, at: now - DAY + 2300000 + i * 90000}))}],
    music: [{title: 'Anotimpul', artist: 'Subcarpați', at: now - DAY + 60000}, {title: 'Fetele care ard', artist: 'Carla’s Dreams', at: now - DAY + 300000}, {title: 'Tot ce vreau', artist: 'Holograf', at: now - DAY + 600000}]},
    ...f.mars.workouts.map(w => ({...w, completed: true, source: 'instructie'}))];
  f.mars.more = true; f.mars.from = now - 30 * DAY;
  return f;
}

const profiles = {rich, lana, empty, mirror};
function buildFixture(profile = 'rich', now = NOW) {
  const make = profiles[profile];
  if (!make) throw Error('Unknown profile ' + profile + ' (known: ' + Object.keys(profiles).join(', ') + ')');
  return make(now);
}
module.exports = {buildFixture, profiles, NOW, DEVICE, MIN, HOUR, DAY, grid, polyline};
