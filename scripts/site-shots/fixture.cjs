// FIXTURE DATA for the local site preview harness (scripts/site-shots/shots.cjs). Synthetic, Romanian, Bucharest.
// One file, three profiles:
//   rich  — every ability of the site populated (design review of every component and state)
//   lana  — what a 4.3 phone account most plausibly sends today (contract session, gallery copies, protocol-4 organizer
//           job on "Documents", explore cells + places via "Și pe site", lost-phone device without position; NO site
//           friends, NO web-mic phone, NO sleep reports, NO campaigns). Inference from app code, see site-ia.md §7.
//   empty — a brand-new account (every empty state)
// Shapes follow the server code (insights-store.mjs, files-vault.mjs, organizer-jobs.mjs, social.mjs,
// social-journey.mjs, lost-phone.mjs, phone-schema.mjs) so the client renders exactly what it would render live.
// `blobs` maps file / item ids to binary content produced by the harness (scene images, text, PDF, audio).

const NOW = Date.parse('2026-09-28T19:40:00+03:00'); // Monday evening, Bucharest
const MIN = 60000, HOUR = 3600000, DAY = 86400000;
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const DEVICE = id(20);

// Same 150 m Web-Mercator grid as the app's ExploreGrid; 200 m for the browser journey grid.
function grid(size) {
  const R = 6378137, rad = Math.PI / 180;
  const cellOf = (lat, lng) => [Math.floor(R * lng * rad / size), Math.floor(R * Math.log(Math.tan(Math.PI / 4 + lat * rad / 2)) / size)];
  const bounds = (x, y) => ({min_lng: x * size / R / rad, max_lng: (x + 1) * size / R / rad,
    min_lat: (2 * Math.atan(Math.exp(y * size / R)) - Math.PI / 2) / rad, max_lat: (2 * Math.atan(Math.exp((y + 1) * size / R)) - Math.PI / 2) / rad});
  return {cellOf, bounds};
}
function cellsAlong(legs, size, now, kind = 'explored') {
  const {cellOf, bounds} = grid(size), seen = new Map();
  legs.forEach(([a, b, c, d], leg) => { for (let t = 0; t <= 1.0001; t += 0.01) {
    const [x, y] = cellOf(a + (c - a) * t, b + (d - b) * t), key = x + '_' + y;
    if (!seen.has(key)) seen.set(key, {id: key, ...bounds(x, y), first_at: now - DAY * (3 + leg), last_at: now - HOUR * (1 + leg), visits: 1 + ((x + y) % 4)});
  } });
  return [...seen.values()].map(c => ({type: 'Feature', geometry: {type: 'Polygon', coordinates: [[[c.min_lng, c.min_lat], [c.max_lng, c.min_lat], [c.max_lng, c.max_lat], [c.min_lng, c.max_lat], [c.min_lng, c.min_lat]]]},
    properties: {id: c.id, first_at: c.first_at, last_at: c.last_at, visits: c.visits, kind}}));
}
const line = (fid, points, props = {}) => ({type: 'Feature', geometry: {type: 'LineString', coordinates: points.map(([lat, lon]) => [lon, lat])}, properties: {id: fid, ...props}});

// Walks / runs around Bucharest: Herăstrău → Piața Victoriei → Cișmigiu → Unirii, plus a loop in Herăstrău.
const LEGS = [[44.4700, 26.0820, 44.4530, 26.0860], [44.4530, 26.0860, 44.4380, 26.0930], [44.4380, 26.0930, 44.4270, 26.1020],
  [44.4700, 26.0820, 44.4760, 26.0950], [44.4760, 26.0950, 44.4640, 26.1050]];
const HOME = {lat: 44.4387, lon: 26.0936};

function rich(now = NOW) {
  const position = (lat, lon, battery = 78, ago = 40000, speed = 1.3) => ({lat, lon, battery, accuracy: 12, speed, at: now - ago});
  const S = {contract: id(1), audio: id(2), photos: id(3)};
  const blobs = {};

  // ——— Sessions (contract: location + app usage; a web-mic recording; a photo selection) ———
  const audioItems = [0, 1].map(i => ({item_id: id(60 + i), kind: 'audio', name: `Înregistrare ${i ? '02:14' : '01:12'}.m4a`, media_type: 'audio/mp4', bytes: 1840000 + i * 90000,
    received_at: now - HOUR * (18 - i), sha256: 'a'.repeat(64), recorded_from: now - HOUR * (18.2 - i), recorded_to: now - HOUR * (18.2 - i) + 2 * MIN, duration_ms: 2 * MIN}));
  audioItems.forEach(i => blobs['session:' + i.item_id] = {audio: true});
  const photoItems = [['Sinaia, cabana.jpg', 'mountain'], ['Mic dejun.jpg', 'food']].map(([name, scene], i) => ({item_id: id(70 + i), kind: 'photo', name, media_type: 'image/jpeg',
    bytes: 2400000 + i * 310000, received_at: now - HOUR * 3, sha256: 'b'.repeat(64)}));
  blobs['session:' + id(70)] = {scene: 'mountain'}; blobs['session:' + id(71)] = {scene: 'food'};
  const sessions = [
    {session_id: S.contract, created_at: now - 25 * MIN, expires_at: now + DAY - 25 * MIN, bytes: 184000, consent: {location: true, app_usage: true, files: false, photos: false, audio: false}, items: [], observations: [], data: true},
    {session_id: S.photos, created_at: now - 3 * HOUR, expires_at: now + 21 * HOUR, bytes: 5110000, consent: {location: false, app_usage: false, files: false, photos: true, audio: false}, items: photoItems,
      observations: [{item_id: id(70), text: 'Peisaj montan, o cabană și brazi: pare o drumeție de weekend.'}], data: null},
    {session_id: S.audio, created_at: now - 18.3 * HOUR, expires_at: now + 5.7 * HOUR, bytes: 3770000, consent: {location: false, app_usage: false, files: false, photos: false, audio: true}, mode: 'recording', items: audioItems, observations: [], data: null}
  ];
  const sessionData = {[S.contract]: {
    locations: [[44.4705, 26.0825], [44.4640, 26.0842], [44.4560, 26.0858], [44.4530, 26.0862], [44.4465, 26.0890], [44.4410, 26.0918], [44.4387, 26.0936]]
      .map(([latitude, longitude], i) => ({at: now - (7 - i) * 3 * MIN, latitude, longitude, accuracy_m: 9 + i, segment: i < 4 ? 0 : 1})),
    visits: [{first_seen: now - 9 * HOUR, last_seen: now - 1.2 * HOUR, latitude: 44.4705, longitude: 26.0825, observed_ms: 7.1 * HOUR, samples: 180},
      {first_seen: now - 40 * MIN, last_seen: now - 2 * MIN, latitude: 44.4387, longitude: 26.0936, observed_ms: 36 * MIN, samples: 24}],
    usage_window: {from: now - DAY, to: now, method: 'activity_events'},
    app_usage: [['com.forja.app.research', 'FORJA', 74, 21], ['com.whatsapp', 'WhatsApp', 52, 38], ['com.spotify.music', 'Spotify', 96, 6], ['com.google.android.apps.maps', 'Maps', 18, 4], ['com.instagram.android', 'Instagram', 41, 17]]
      .map(([pkg, label, minutes, opens], i) => ({package: pkg, label, foreground_ms: minutes * MIN, opens, last_used: now - (i + 1) * 40 * MIN}))
  }};

  // ——— Journals (Firestore mirrors: sleep / activities / meals, last 7 days) ———
  const nights = [[23.3, 7.1, 86], [23.8, 6.9, 81], [0.4, 7.4, 78], [23.1, 7.0, 90], [23.6, 6.4, 72], [0.2, 8.1, 84], [23.4, 7.2, 88]];
  // Midnight in Bucharest (the page runs with timezoneId Europe/Bucharest; node may run in UTC).
  const [h, m, sec] = new Intl.DateTimeFormat('en-GB', {timeZone: 'Europe/Bucharest', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'}).format(now).split(':').map(Number);
  const day0 = new Date(now - ((h * 60 + m) * 60 + sec) * 1000 - (now % 1000));
  const journals = {
    sleep: {records: nights.map(([bed, hours, score], ago) => { const start = day0.getTime() - ago * DAY + (bed > 12 ? bed - 24 : bed) * HOUR; return {startAt: start, endAt: start + hours * HOUR, score}; })},
    activities: {records: [['Alergare', 1920, 5100, 1], ['Plimbare', 2700, 3300, 0.2], ['Bicicletă', 3600, 14200, 2], ['Sală · forță', 3300, 0, 3], ['Plimbare', 1800, 2100, 5]]
      .map(([type, durationS, distanceM, ago]) => ({startAt: now - ago * DAY - 2 * HOUR, type, durationS, distanceM}))},
    meals: {records: [['Mic dejun · ovăz cu fructe', 320, 410, 12], ['Prânz · ciorbă de legume și pâine', 520, 560, 7], ['Gustare · iaurt grecesc', 170, 160, 4], ['Cină · somon cu orez', 430, 640, 1.5]]
      .map(([name, grams, kcal, ago]) => ({at: now - ago * HOUR, name, grams, kcal}))}
  };

  // ——— Social graph (site friends), journey (browser) and explore (app cells + places) ———
  const social = {
    me: {id: 'demo-owner', name: 'Lana', code: id(9), session: {id: id(8), mode: 'walk', until: now + 50 * MIN, continuous: false, audience: null},
      location: position(44.4392, 26.0941, 64, 25000), places: [{id: id(4), name: 'Parcul Cișmigiu', lat: 44.4371, lon: 26.0911}, {id: id(5), name: 'Cafeneaua de pe Popa Nan', lat: 44.4318, lon: 26.1178}],
      history: [], explored: ['a', 'b', 'c', 'd', 'e'], discoverable: true, discovery_until: now + 20 * DAY, blocked: [],
      partner: {id: 'demo-andrei', name: 'Andrei Marin', state: 'accepted'},
      visibility: {ghost: false, grants: [{id: 'demo-ana', current: true, ghost: false, history: true}, {id: 'demo-andrei', current: true, ghost: true, history: true}], configured: true, revision: 3, updated_at: now - DAY}},
    friends: [
      {id: 'demo-andrei', name: 'Andrei Marin', mode: 'walk', location: position(44.4402, 26.0969, 81, 50000), checkin: null},
      {id: 'demo-ana', name: 'Ana Popescu', mode: 'walk', location: position(44.4712, 26.0835, 55, 70000), checkin: {name: 'Parcul Herăstrău'}},
      {id: 'demo-mihai', name: 'Mihai Ionescu', mode: 'cycle', location: position(44.4521, 26.1043, 38, 30000, 5.4), checkin: null},
      {id: 'demo-ioana', name: 'Ioana Matei', mode: null, location: null, checkin: null}
    ],
    incoming: [{id: 'demo-radu', name: 'Radu Stan'}], outgoing: 1,
    groups: [{id: id(6), name: 'Tură de seară prin Herăstrău', mode: 'walk', at: now + DAY + 22 * HOUR - 19.67 * HOUR, place: 'Intrarea de la Arcul de Triumf', lat: 44.4672, lon: 26.0781,
      going: ['demo-owner', 'demo-ana'], members: ['demo-owner', 'demo-ana', 'demo-mihai', 'demo-andrei']},
      {id: id(7), name: 'Bicicletă la Văcărești', mode: 'cycle', at: now + 5 * DAY, place: 'Parcul Natural Văcărești', lat: 44.4000, lon: 26.1310, going: ['demo-mihai'], members: ['demo-owner', 'demo-mihai']}]
  };
  const chat = {'demo-ana': [
    {id: id(90), from: 'demo-ana', text: 'Ieșim mâine seară la o tură prin parc? 🌿', at: now - 3 * HOUR},
    {id: id(91), from: 'demo-owner', text: 'Da! Pe la 22, de la Arcul de Triumf.', at: now - 2.8 * HOUR},
    {id: id(92), from: 'demo-ana', text: 'Perfect, ne vedem acolo.', at: now - 2.7 * HOUR, place: {name: 'Arcul de Triumf', lat: 44.4672, lon: 26.0781}}]};
  const journey = {owner: 'self', session: null, last_sample_at: null,
    routes: {type: 'FeatureCollection', features: [
      line('r1', [[44.4700, 26.0820], [44.4655, 26.0835], [44.4590, 26.0851], [44.4530, 26.0860], [44.4470, 26.0889], [44.4410, 26.0915], [44.4380, 26.0930]], {session: id(30), started_at: now - 2 * DAY}),
      line('r2', [[44.4700, 26.0820], [44.4735, 26.0870], [44.4760, 26.0950], [44.4700, 26.1010], [44.4640, 26.1050]], {session: id(31), started_at: now - 4 * DAY})]},
    zones: {type: 'FeatureCollection', features: cellsAlong(LEGS.slice(0, 2), 200, now, 'zone').slice(0, 30)},
    visits: [{id: 'v1', lat: 44.4705, lon: 26.0825, name: 'Birou', observed_ms: 7.4 * HOUR, rating: 4}, {id: 'v2', lat: 44.4387, lon: 26.0936, name: '', observed_ms: 11.2 * HOUR, rating: null}],
    next_cursor: null, rules: {gap_ms: 300000, radius_m: 100, accuracy_m: 50, grid_m: 200, visit_ms: 18000000}};
  const explore = {owner: 'self', grid_m: 150, cells: {type: 'FeatureCollection', features: cellsAlong(LEGS, 150, now)}, next_cursor: null, updated_at: now - 30 * MIN,
    places: [
      {id: 'p1', lat: HOME.lat, lon: HOME.lon, name: 'Acasă', stars: 5, note: '', recommended: false, stay_ms: 60 * HOUR, updated_at: now - HOUR},
      {id: 'p2', lat: 44.4705, lon: 26.0825, name: 'Birou', stars: 3, note: 'Cafea bună la parter.', recommended: false, stay_ms: 20 * HOUR, updated_at: now - DAY},
      {id: 'p3', lat: 44.4290, lon: 26.1010, name: '', stars: 0, note: '', recommended: false, stay_ms: 5.2 * HOUR, updated_at: now - 2 * DAY},
      {id: 'p4', lat: 44.4760, lon: 26.0950, name: 'Terasa de pe lac', stars: 4, note: 'Apus frumos, rezervă din timp.', recommended: true, stay_ms: 5.6 * HOUR, updated_at: now - 3 * DAY}]};
  const visibility = {ghost: false, grants: social.me.visibility.grants, configured: true, revision: 3, updated_at: now - DAY};
  const recovery = [{id: DEVICE, name: 'samsung SM-S911B', enabled: true, seen_at: now - 40000, online: true, status: 'ready', command: null,
    position: {lat: 44.4389, lon: 26.0934, accuracy: 18, battery: 64, at: now - 6 * MIN}}];

  // ——— Files: online copies (24 h), some from the organizer job ———
  const job1 = id(40), job2 = id(41);
  const fileRows = [
    ['Sinaia, cabana.jpg', 'photo', 'image/jpeg', 'image', 2400000, 'Vacanță · Sinaia 2026', 'mountain', 0.4],
    ['Sinaia, cascada Urlătoarea.jpg', 'photo', 'image/jpeg', 'image', 2710000, 'Vacanță · Sinaia 2026', 'forest', 0.4],
    ['Vama Veche, răsărit.jpg', 'photo', 'image/jpeg', 'image', 3100000, 'Mare · august', 'sea', 1.1],
    ['Tort aniversare.jpg', 'photo', 'image/jpeg', 'image', 1980000, 'Aniversări', 'cake', 1.3],
    ['Mic dejun.jpg', 'photo', 'image/jpeg', 'image', 1650000, '', 'food', 2.2],
    ['București noaptea.jpg', 'photo', 'image/jpeg', 'image', 2200000, 'Oraș', 'city', 2.6],
    ['Captură · bilet tren.png', 'photo', 'image/png', 'image', 420000, 'Capturi de ecran', 'screenshot', 3.1],
    ['Pisica pe pervaz.jpg', 'photo', 'image/jpeg', 'image', 2050000, 'Acasă', 'cat', 4.5],
    ['Factură Enel septembrie.pdf', 'file', 'application/pdf', 'pdf', 184000, 'Facturi', 'pdf:Factură Enel · septembrie 2026|Total de plată: 184,32 lei|Scadență: 15.10.2026', 5],
    ['Plan de antrenament.pdf', 'file', 'application/pdf', 'pdf', 250000, 'Sport', 'pdf:Plan de antrenament · 4 săptămâni|Luni: alergare ușoară 30 min|Miercuri: forță, trei serii|Vineri: intervale 6 × 400 m', 6],
    ['Lista pentru weekend.txt', 'file', 'text/plain', 'text', 1200, 'Personal', 'text:Sinaia, sâmbătă–duminică\n- bocanci și geacă de ploaie\n- apă, termos\n- bilete tren 07:40\n- aparat foto', 7],
    ['Contract închiriere.docx', 'file', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'docx', 58000, 'Acte', 'download', 9]
  ];
  const files = fileRows.map(([name, kind, media_type, preview, bytes, folder, scene, agoHours], i) => {
    const row = {id: id(100 + i), device_id: DEVICE, kind, name, folder, media_type, preview, sha256: String(i).repeat(64).slice(0, 64), bytes,
      received_at: now - agoHours * HOUR, expires_at: now + (24 - agoHours) * HOUR, thumbnail: kind === 'photo'};
    if (scene.startsWith('pdf:')) blobs['file:' + row.id] = {pdf: scene.slice(4).split('|')};
    else if (scene.startsWith('text:')) blobs['file:' + row.id] = {text: scene.slice(5)};
    else if (scene !== 'download') blobs['file:' + row.id] = {scene};
    return row;
  });
  Object.assign(files[0], {organizer_job: job1, organizer_item: id(200), sync_state: 'applied'});
  Object.assign(files[1], {organizer_job: job1, organizer_item: id(201), pending_folder: 'Vacanță · Sinaia 2026', sync_state: 'awaiting_phone'});
  Object.assign(files[8], {organizer_job: job2, organizer_item: id(210)});

  // ——— Organizer (protocol 4 device; one legacy run for the old panel) ———
  const grant = {id: id(10), enabled: true, photos: true, files: true, organize: true,
    sources: [{id: id(11), source: 'files', label: 'Documents'}, {id: id(12), source: 'files', label: 'Download'}, {id: id(13), source: 'photos', label: 'DCIM/Camera'}]};
  const cleanup = {devices: [{id: DEVICE, label: 'SM-S911B', last_seen: now - 40000, protocol: 4, revision: 7, grant,
      schedule: {enabled: true, photos: true, files: false, wifi_only: true, count: 25, timezone: 'Europe/Bucharest', times: ['21:30'], days: [1, 3, 5]}}],
    runs: [{id: id(50), device: DEVICE, at: now - 2 * DAY, phase: 'complete', analyzed: 25, duplicates: 3, uploaded: 25, total: 25, message: '',
      proposals: [{name: 'IMG_20260921_101512.jpg', destination: 'Vacanță · Sinaia 2026', reason: 'Munte, cabană, aceeași zi ca alte 11 poze.'},
        {name: 'Screenshot_20260920-0812.png', destination: 'Capturi de ecran', reason: 'Captură de ecran cu un bilet de tren.'}]}]};
  const counters = (o) => ({total: 0, pending: 0, analyzed: 0, upload_pending: 0, uploaded: 0, ready: 0, applying: 0, moved: 0, copied_pending_removal: 0, needs_review: 0, failed_retryable: 0, skipped: 0, ...o});
  const organizerJobs = [
    {id: job1, device: DEVICE, source: 'photos', source_id: null, scope: {folder: '', recursive: true}, destination: 'FORJA', mode: 'online', auto_apply: true, ai_consent: true, revision: 12, sync_revision: 12,
      state: 'partial', command: {status: 'complete', action: 'continue', count: 50, revision: 12, selected: 50}, inventory_total: 1240, inventory_complete: true,
      counters: counters({total: 50, moved: 41, uploaded: 3, needs_review: 4, failed_retryable: 1, skipped: 1}), created_at: now - 26 * HOUR, updated_at: now - 25 * MIN},
    {id: job2, device: DEVICE, source: 'files', source_id: id(11), scope: {folder: '', recursive: true}, destination: 'FORJA', mode: 'local', auto_apply: false, ai_consent: false, revision: 5, sync_revision: 5,
      state: 'running', command: {status: 'pending', action: 'continue', count: 100, revision: 5}, inventory_total: 312, inventory_complete: true,
      counters: counters({total: 100, pending: 58, uploaded: 18, ready: 6, moved: 12, needs_review: 6}), message: 'Telefonul lucrează în fundal.', created_at: now - 3 * HOUR, updated_at: now - 4 * MIN}];
  const fileView = f => ({id: f.id, name: f.name, media_type: f.media_type, preview: f.preview, expires_at: f.expires_at, thumbnail: f.thumbnail, sha256: f.sha256, bytes: f.bytes, folder: f.folder});
  const organizerItems = {
    [job1]: [
      {id: id(200), job_id: job1, name: 'IMG_20260921_101512.jpg', source: 'photos', state: 'moved', destination: 'Vacanță · Sinaia 2026', reason: 'Munte și cabană, aceeași zi ca alte 11 poze.', received: true, file: fileView(files[0]), version: 1, sha256: files[0].sha256,
        analysis: {status: 'complete', coverage: {status: 'complete'}, evidence: [{id: 'e1', kind: 'visual', observation: 'Cabană de lemn, brazi, cer senin.'}]}},
      {id: id(201), job_id: job1, name: 'IMG_20260921_113040.jpg', source: 'photos', state: 'ready', destination: 'Vacanță · Sinaia 2026', reason: 'Cascadă, pădure; aceeași excursie.', received: true, file: fileView(files[1]), version: 1, sha256: files[1].sha256},
      {id: id(202), job_id: job1, name: 'IMG_20260915_190233.jpg', source: 'photos', state: 'needs_review', destination: 'De aruncat', reason: 'Imagine neclară, aproape identică cu cea de la 19:02:31.', received: false, file_id: id(299), version: 1, sha256: 'c'.repeat(64),
        analysis: {status: 'complete', coverage: {status: 'complete'}, evidence: [{id: 'e2', kind: 'visual', observation: 'Mișcare puternică, subiect neclar.'}],
          deletion: {suggested: true, review_only: true, requires_confirmation: true, reason: 'Poză mișcată; există o variantă clară la 2 secunde distanță.', evidence_ids: ['e2']}}},
      {id: id(203), job_id: job1, name: 'IMG_20260915_190231.jpg', source: 'photos', state: 'needs_review', destination: 'Aniversări', reason: 'Tort și lumânări.', received: true, file: fileView(files[3]), version: 1, sha256: files[3].sha256,
        duplicate: {kind: 'exact_bytes', is_keeper: true, verified_received_bytes: true, expires_at: files[3].expires_at}},
      {id: id(204), job_id: job1, name: 'IMG_20260902_080411.jpg', source: 'photos', state: 'failed_retryable', destination: 'Mare · august', reason: 'Android a refuzat mutarea; reîncearcă din telefon.', received: false, version: 1, sha256: 'd'.repeat(64)}],
    [job2]: [
      {id: id(210), job_id: job2, name: 'Factură Enel septembrie.pdf', source: 'files', state: 'ready', destination: 'Facturi/2026', reason: 'Factură de energie, septembrie 2026.', received: true, file: fileView(files[8]), version: 1, sha256: files[8].sha256,
        analysis: {status: 'complete', coverage: {status: 'complete', pages_processed: 2, pages_total: 2}, evidence: [{id: 'e3', kind: 'text', page: 1, quote: 'Total de plată: 184,32 lei'}]}},
      {id: id(211), job_id: job2, name: 'Scan_0042.pdf', source: 'files', state: 'needs_review', destination: 'Acte', reason: 'Document scanat, text citit parțial.', received: false, version: 1, sha256: 'e'.repeat(64), partial: true,
        analysis: {status: 'partial', coverage: {status: 'partial', pages_processed: 1, pages_total: 3}, evidence: []}},
      {id: id(212), job_id: job2, name: 'CV_Lana_2025.docx', source: 'files', state: 'moved', destination: 'Carieră', reason: 'CV, actualizat în 2025.', received: false, version: 1, sha256: 'f'.repeat(64)}]
  };

  // ——— Web control of the phone microphone + sleep reports ———
  const phones = [{id: DEVICE, name: 'samsung SM-S911B', online: true, seen_at: now - 20000, audio_allowed: true, audio_background_capable: true, audio_ready: true, foreground: false,
    collection_enabled: true, state: 'idle', revision: 4, sleep_capable: true, sleep_analysis_allowed: true, command: null, sleep_session: null,
    recording_transfer: {state: 'uploaded', pending_count: 0}}];
  const sleepSessions = [
    {id: id(80), started_at: now - 20.5 * HOUR, state: 'complete', chunk_count: 3, analyzed_ms: 6 * MIN, analysis: 'complete', snoring: {status: 'complete', possible_intervals: 2}},
    {id: id(81), started_at: now - 2 * DAY - 20 * HOUR, state: 'analyzing', chunk_count: 2, analyzed_ms: 2 * MIN, analysis: 'running', snoring: {status: 'complete', possible_intervals: 0}}];
  const sleepReports = {
    [id(80)]: {recorded_ms: 6 * MIN, analyzed_ms: 6 * MIN, analysis_consent: true, snoring: {status: 'complete', possible_intervals: 2, analyzed_ms: 6 * MIN}, next_cursor: null,
      chunks: [
        {id: S.audio, item_id: id(60), recorded_from: now - 20.4 * HOUR, duration_ms: 2 * MIN, state: 'complete', acoustic: {status: 'complete', events: [{start_ms: 34000, end_ms: 51000}]},
          result: {transcript: '…nu, lasă geamul deschis… mâine la șapte.', topics: [{title: 'Program de dimineață'}], transcript_status: 'complete', limitations: ['Transcriere automată, poate greși.']}},
        {id: S.audio, item_id: id(61), recorded_from: now - 19.1 * HOUR, duration_ms: 2 * MIN, state: 'complete', acoustic: {status: 'complete', events: []}, result: {transcript_status: 'empty', limitations: []}},
        {id: S.audio, item_id: id(61), recorded_from: now - 17.9 * HOUR, duration_ms: 2 * MIN, state: 'failed', acoustic: {status: 'unavailable', events: []}, result: {error: {message: 'Serviciul de transcriere nu a răspuns. Poți reîncerca.'}}}]},
    [id(81)]: {recorded_ms: 4 * MIN, analyzed_ms: 2 * MIN, analysis_consent: true, snoring: {status: 'complete', possible_intervals: 0, analyzed_ms: 4 * MIN}, next_cursor: null,
      chunks: [{id: S.audio, item_id: id(60), recorded_from: now - 2 * DAY - 19.8 * HOUR, duration_ms: 2 * MIN, state: 'analyzing', acoustic: {status: 'complete', events: []}, result: null}]}
  };

  const campaigns = [
    {id: id(95), revision: 3, published: true, sponsor: 'Atelierul de ceramică Lut', title: 'Un weekend cu mâinile în lut', body: 'Atelier de două ore, sâmbătă, pentru începători. Lutul și cuptorul sunt incluse.', cta: 'Rezervă un loc', url: 'https://example.ro/lut'},
    {id: id(96), revision: 1, published: false, sponsor: 'Clubul de alergare Herăstrău', title: 'Alergăm împreună, joi seara', body: '5 km în ritm de conversație. Plecăm la 19:00 de la Arcul de Triumf.', cta: '', url: ''}];
  const recommendations = {generated_at: now - 2 * MIN, model: 'gemini-2.5-flash',
    evidence: [{id: 'sleep-regularity', text: 'Ora de culcare a variat cu până la 70 de minute în ultimele 7 nopți.'}, {id: 'activity-coverage', text: '5 activități înregistrate în ultimele 7 zile.'},
      {id: 'meal-notes', text: 'Mese înregistrate: ovăz cu fructe; ciorbă de legume; iaurt grecesc; somon cu orez.'}],
    recommendations: [
      {category: 'sleep', confidence: 'medium', title: 'O oră fixă de culcare, 23:15', why: 'Nopțile cu ora de culcare constantă au avut scoruri mai bune.', next_step: 'Pune o alarmă de „pregătire” la 22:45 patru seri la rând.', evidence_ids: ['sleep-regularity']},
      {category: 'activities', confidence: 'medium', title: 'Tură ușoară prin Herăstrău, miercuri', why: 'Ai alergat luni și ai mers cu bicicleta sâmbătă; miercuri e liberă.', next_step: 'Invită-o pe Ana: 30 de minute, ritm de conversație.', evidence_ids: ['activity-coverage']},
      {category: 'food', confidence: 'low', title: 'Proteine la gustarea de după-amiază', why: 'Gustările înregistrate sunt mici, iar cina e cea mai mare masă.', next_step: 'Încearcă iaurt cu nuci sau hummus cu legume.', evidence_ids: ['meal-notes']}]};

  return {now, device: DEVICE,
    state: {sessions, journals}, sessionData, social, chat, journey, explore, visibility, recovery,
    files, fileSettings: {devices: [{id: DEVICE, photos: true, files: true, enabled: true}]}, cleanup, organizerJobs, organizerItems, legacyPlans: [],
    intake: {accepting: true, revision: 2}, phones, sleepSessions, sleepReports, campaigns, recommendations, blobs};
}

function lana(now = NOW) {
  const f = rich(now);
  // 4.3 phone: contract session (location + app usage) only; journals from Firestore; no web-mic recordings.
  f.state.sessions = f.state.sessions.filter(s => s.data);
  f.state.journals.sleep.records = f.state.journals.sleep.records.slice(0, 3);
  f.state.journals.activities.records = f.state.journals.activities.records.slice(0, 2);
  // The app's friends / family / live position live in Firestore: the site graph has only a contacts-matched stub.
  f.social.friends = []; f.social.incoming = []; f.social.outgoing = 0; f.social.groups = []; f.chat = {};
  Object.assign(f.social.me, {name: 'Prieten FORJA', session: null, location: null, places: [], explored: [], partner: null, visibility: {ghost: false, grants: [], configured: false, revision: 0, updated_at: 0}});
  f.visibility = {ghost: false, grants: [], configured: false, revision: 0, updated_at: 0};
  f.journey = {...f.journey, routes: {type: 'FeatureCollection', features: []}, zones: {type: 'FeatureCollection', features: []}, visits: []};
  // Explore "Și pe site" is on after the contract: cells + places arrive (places without the 4.x names/notes, see site-map.md R2).
  f.explore.places = f.explore.places.map(p => ({...p, name: '', note: '', stars: 0, recommended: false}));
  // Lost phone: granted, no position requested yet.
  f.recovery = [{...f.recovery[0], position: null}];
  // Gallery copies from the contract uploader; organizer job from Inventar on "Documents" (auto-apply, destination FORJA).
  f.files = f.files.filter(x => x.kind === 'photo').map(({organizer_job, organizer_item, pending_folder, sync_state, ...x}) => ({...x, folder: ''}));
  f.cleanup.devices[0].schedule = {...f.cleanup.devices[0].schedule, enabled: false, times: [], days: []};
  f.cleanup.devices[0].grant.sources = [f.cleanup.devices[0].grant.sources[0]];
  f.cleanup.runs = [];
  const job = f.organizerJobs[1];
  f.organizerJobs = [{...job, mode: 'local', auto_apply: true, state: 'complete', message: undefined, command: {status: 'complete', action: 'continue', count: 50, revision: 5},
    counters: {total: 4, pending: 0, analyzed: 0, upload_pending: 0, uploaded: 0, ready: 0, applying: 0, moved: 4, copied_pending_removal: 0, needs_review: 0, failed_retryable: 0, skipped: 0}, inventory_total: 4}];
  f.organizerItems = {[job.id]: f.organizerItems[job.id].map(i => ({...i, state: 'moved', received: false, file: null, analysis: null}))};
  f.phones = []; f.sleepSessions = []; f.sleepReports = {}; f.campaigns = [];
  return f;
}

function empty(now = NOW) {
  const f = rich(now);
  f.state = {sessions: [], journals: {sleep: {records: []}, activities: {records: []}, meals: {records: []}}};
  f.sessionData = {};
  f.social = {me: {id: 'demo-owner', name: 'Prieten FORJA', code: id(9), session: null, location: null, places: [], history: [], explored: [], discoverable: false, discovery_until: null, blocked: [], partner: null,
    visibility: {ghost: false, grants: [], configured: false, revision: 0, updated_at: 0}}, friends: [], incoming: [], outgoing: 0, groups: []};
  f.chat = {}; f.visibility = {ghost: false, grants: [], configured: false, revision: 0, updated_at: 0};
  f.journey = {...f.journey, routes: {type: 'FeatureCollection', features: []}, zones: {type: 'FeatureCollection', features: []}, visits: []};
  f.explore = {owner: 'self', grid_m: 150, cells: {type: 'FeatureCollection', features: []}, places: [], next_cursor: null, updated_at: 0};
  f.recovery = []; f.files = []; f.fileSettings = {devices: []}; f.cleanup = {devices: [], runs: []}; f.organizerJobs = []; f.organizerItems = {};
  f.phones = []; f.sleepSessions = []; f.sleepReports = {}; f.campaigns = [];
  f.recommendations = {generated_at: now, model: 'gemini-2.5-flash', evidence: [], recommendations: []};
  return f;
}

const profiles = {rich, lana, empty};
function buildFixture(profile = 'rich', now = NOW) {
  if (!profiles[profile]) throw Error(`Unknown fixture profile "${profile}". Use one of: ${Object.keys(profiles).join(', ')}`);
  return profiles[profile](now);
}
module.exports = {NOW, DEVICE, profiles: Object.keys(profiles), buildFixture};
