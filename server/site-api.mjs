// FORJA 4.4 — API-ul de citire al site-ului: o secțiune pe fiecare abilitate a aplicației (DESIGN-4.4 §3.1–§3.2).
//
// Toate rutele sunt GET, sub /insights/api/*, după verificarea tokenului Firebase în insights-worker.mjs. Datele telefonului
// se citesc din Firestore prin REST CU TOKENUL CELUI CARE CERE (regulile Firestore se aplică exact ca în aplicație), iar ce
// ține de site (sesiuni, vault, telefoane, pauză, timp pe ecran) vine din Durable Object-ul contului. Nopțile (audio + analiză)
// vin din R2 `forja-sleep` (binding SLEEP), cu chei construite NUMAI din uid-ul verificat.
// Reguli copiate din aplicație: fantoma ascunde lat/lng/nowPlaying (FriendsRepository.kt:173-201); nowPlaying doar sub 10 min;
// poziția familiei din familyLoc (citibilă doar când ești în `allowed`). Nicio rută nu întoarce emailul altcuiva.
//
// Bugetul Firestore (Spark: 50 000 de citiri pe zi pentru tot proiectul). O citire = un document întors; o interogare fără
// rezultate costă tot 1. N = prieteni, F = documente familyLoc vizibile, R = locuri recomandate ție.
//   cerc      live (≤ 20 s, memorie + DO):  1 (eu) + N (batchGet) + max(1,F)                  ≈ 12 citiri cu 10 prieteni
//             lista de prieteni (≤ 10 min, DO): max(1,N)                                         ≈ 10 / 10 min
//             lent (≤ 10 min, DO): max(1,R) locuri + 1 (alergări mai noi decât ultima cunoscută)     ≈ 6 / 10 min
//             traseele se construiesc o singură dată (pagini de 5, ≤ 600 KB de polilinii pe cerere) și rămân în DO
//             → la un poll de 30 s (doar cât Teren/Camarazi e vizibil): ≈ 120×12 + 6×10 + 6×6 ≈ 1 540 de citiri pe oră de
//               site deschis, adică ~32 de ore de privit continuu înainte de plafonul zilnic.
//   azi       3 (eu, ținte, muzică) + mesele de azi + ≤ 60 activități și ≤ 60 antrenamente pe 7 zile + 2 nopți + 1 inventar
//             (+ N dacă lista de prieteni nu e în DO)                                            ≈ 15–25, memorie 20 s
//   somn      nopțile din `days` (≤ 100) + 1 listare R2 · somn/<id>: 1 + 1 citire și 1 listare R2 · chunk: 0 (doar R2)
//   ratie     1 (ținte) + mesele din `days` (≤ 800)                                              ≈ 60–120 pe 30 de zile
//   mars      activitățile (fără polilinii) + antrenamentele din `days` (≤ 200 + ≤ 200) + ≤ 8 polilinii lipsă din DO
//   muzica    2 · paza 0 · inventar ≤ 20 · cont 7
// Secțiunile în afară de Teren/Camarazi se citesc la deschidere, nu în buclă.
import { firestoreFields, accountStub, internalRequest } from './insights-ai.mjs';
import { localDate, localMidnight, epochDayDate, DAY } from './site-time.mjs';

const PROJECT = 'forja-65093';
const DOCS = `projects/${PROJECT}/databases/(default)/documents`;
const BASE = `https://firestore.googleapis.com/v1/${DOCS}`;
const MIN = 60000, HOUR = 3600000;
export const SITE_RULES = Object.freeze({
  cerc_live_ms: 20000, cerc_slow_ms: 10 * MIN, friends_ms: 10 * MIN, azi_ms: 20000, now_playing_ms: 10 * MIN,
  contract_current: 3, friends_max: 100, routes: 30, route_points: 300, mini_route_points: 120, route_page: 5, route_bytes: 600 * 1024, mars_polylines: 8,
  somn_days: [1, 60, 14], ratie_days: [1, 90, 30], mars_days: [1, 90, 30], inventar_runs: 20, events_max: 1000,
});
const SECTIONS = ['azi', 'cerc', 'somn', 'ratie', 'mars', 'muzica', 'paza', 'inventar', 'cont'];
const SITE_PATH = new RegExp('^/insights/api/(' + SECTIONS.join('|') + ')(?:/(.*))?$');
/** True for the 4.4 section routes this module answers (the older /insights/api/* routes stay in insights-ai.mjs). */
export function isSiteApi(path) { return SITE_PATH.test(path); }

const reply = (data, status = 200) => Response.json(data, { status, headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
const failure = (error, status) => reply({ error }, status);
const num = v => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const int = v => (num(v) === null ? null : Math.round(v));
const str = (v, max = 200) => (typeof v === 'string' && v.trim() ? v.trim().slice(0, max) : null);
const round1 = v => Math.round(v * 10) / 10;
const time = v => (num(v) !== null && v > 0 ? v : null);
const sum = (rows, f) => rows.reduce((n, r) => n + (num(f(r)) || 0), 0);

// ─────────────────────────────── Firestore REST, cu tokenul utilizatorului ───────────────────────────────
function encode(v) {
  if (typeof v === 'string') return { stringValue: v };
  if (Number.isSafeInteger(v)) return { integerValue: String(v) };
  if (typeof v === 'number') return { doubleValue: v };
  if (typeof v === 'boolean') return { booleanValue: v };
  return { nullValue: null };
}
// The document id is authoritative (s123, a45, m7, runId…), even if a field is also called `id`.
const docOf = d => ({ ...firestoreFields(d.fields), id: d.name.split('/').pop() });
export const where = (field, op, value) => ({ fieldFilter: { field: { fieldPath: field }, op, value: encode(value) } });

export class FirestoreReader {
  constructor(uid, token, fetcher = fetch) { this.uid = uid; this.token = token; this.fetcher = fetcher; this.reads = 0; this.calls = 0; this.okCalls = 0; }
  /** Rules may refuse a read (403): that data is simply not visible. Network errors and 5xx count as failures. */
  async send(url, body) {
    this.calls++;
    let res;
    try {
      res = await this.fetcher(url, { method: body ? 'POST' : 'GET', headers: { Authorization: 'Bearer ' + this.token, ...(body ? { 'content-type': 'application/json' } : {}) },
        ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(10000) });
    } catch { return { failed: true }; }
    if (res.status === 404 || res.status === 403) { this.okCalls++; return { absent: true, status: res.status }; }
    if (!res.ok) return { failed: true };
    try { const data = await res.json(); this.okCalls++; return { data }; } catch { return { failed: true }; }
  }
  get unreachable() { return this.calls > 0 && this.okCalls === 0; }
  /** A checkpoint; `failedSince(mark)` is true when every call made after it failed (and there was at least one). */
  mark() { return { calls: this.calls, ok: this.okCalls }; }
  failedSince(m) { return this.calls > m.calls && this.okCalls === m.ok; }
  /** One document (1 read) with a field mask; null when missing, unreadable or unreachable. */
  async get(path, fields) {
    const qs = fields.map(f => 'mask.fieldPaths=' + encodeURIComponent(f)).join('&');
    const r = await this.send(`${BASE}/${path}${qs ? '?' + qs : ''}`);
    this.reads++;
    return r.data?.name ? docOf(r.data) : null;
  }
  /** Several documents in ONE request (1 read each). Map(path → doc|null). If the rules refuse one, each is read alone. */
  async batchGet(paths, fields) {
    const out = new Map(paths.map(p => [p, null]));
    if (!paths.length) return out;
    const r = await this.send(`${BASE}:batchGet`, { documents: paths.map(p => `${DOCS}/${p}`), mask: { fieldPaths: fields } });
    this.reads += paths.length;
    if (r.status === 403) {
      this.reads -= paths.length;
      const docs = await Promise.all(paths.map(p => this.get(p, fields)));
      paths.forEach((p, i) => out.set(p, docs[i]));
      return out;
    }
    for (const row of Array.isArray(r.data) ? r.data : []) {
      if (!row.found?.name) continue;
      const path = row.found.name.slice(DOCS.length + 1);
      if (out.has(path)) out.set(path, docOf(row.found));
    }
    return out;
  }
  /** A structured query on `collection` under `parent` ('' = root). Costs max(1, results) reads. */
  async query(parent, collection, { filters = [], orderBy = null, direction = 'DESCENDING', limit = 100, select = null } = {}) {
    const structuredQuery = { from: [{ collectionId: collection }], limit };
    if (select) structuredQuery.select = { fields: select.map(fieldPath => ({ fieldPath })) };
    if (filters.length === 1) structuredQuery.where = filters[0];
    if (filters.length > 1) structuredQuery.where = { compositeFilter: { op: 'AND', filters } };
    if (orderBy) structuredQuery.orderBy = [{ field: { fieldPath: orderBy }, direction }];
    const r = await this.send(`${BASE}${parent ? '/' + parent : ''}:runQuery`, { structuredQuery });
    const docs = (Array.isArray(r.data) ? r.data : []).filter(x => x.document?.name).map(x => docOf(x.document));
    this.reads += Math.max(1, docs.length);
    return r.failed ? null : docs;
  }
}

// ─────────────────────────────── memorie scurtă per uid (izolatul Workerului) ───────────────────────────────
const memory = new Map();
function recall(key, maxAge, now) { const hit = memory.get(key); return hit && now - hit.at <= maxAge ? hit.value : null; }
function remember(key, value, now) {
  memory.set(key, { at: now, value });
  if (memory.size > 500) memory.delete(memory.keys().next().value);
}
/** Tests only: forget every cached section. */
export function resetSiteCache() { memory.clear(); }

// ─────────────────────────────── Durable Objects (contul, graful social) ───────────────────────────────
async function account(env, uid, path, method = 'GET', body) {
  if (!env.INSIGHTS) return null;
  try {
    const res = await accountStub(env, uid).fetch(internalRequest(uid, path, method, body));
    return res.ok ? await res.json() : null;
  } catch { return null; }
}
const summaryOf = (env, uid) => account(env, uid, '/internal/site/summary');
async function socialMeta(env, uid) {
  if (!env.SOCIAL) return null;
  try {
    const res = await env.SOCIAL.get(env.SOCIAL.idFromName('friends-v1')).fetch(new Request('https://internal/v2/social/site-meta', { headers: { 'x-forja-owner': uid } }));
    return res.ok ? await res.json() : null;
  } catch { return null; }
}

// ─────────────────────────────── reguli copiate din aplicație ───────────────────────────────
const isGhost = (u, now) => u?.ghostUntil === -1 || (num(u?.ghostUntil) !== null && u.ghostUntil > now);
function nowPlaying(np, now) {
  if (!np || typeof np !== 'object') return null;
  const title = str(np.title, 120), at = num(np.at);
  if (!title || at === null || now - at >= SITE_RULES.now_playing_ms) return null;
  return { title, artist: str(np.artist, 120), app: str(np.app, 40), at };
}
export function initials(name) {
  return String(name || '').trim().split(/\s+/).slice(0, 2).map(w => Array.from(w)[0] || '').join('').toLocaleUpperCase('ro') || '?';
}
const position = u => (num(u?.lat) !== null && num(u?.lng) !== null ? { lat: u.lat, lng: u.lng } : null);
const byName = (a, b) => a.name.localeCompare(b.name, 'ro');

/**
 * "lat,lng;lat,lng…" (the app's polyline, often one point a second) → at most `max` points: an even pre-thinning to 4×max,
 * then Douglas–Peucker at ~5 m, then even thinning. Cheap enough for the Workers CPU budget (a 1 h run ≈ 1 ms).
 */
export function simplifyPolyline(polyline, max) {
  if (typeof polyline !== 'string' || polyline.length < 7) return null;
  const total = (polyline.match(/;/g)?.length || 0) + 1, stride = Math.max(1, Math.floor(total / (4 * max)));
  const lat = [], lng = [];
  let at = 0, n = 0;
  while (at < polyline.length) {
    let semi = polyline.indexOf(';', at);
    if (semi < 0) semi = polyline.length;
    if (n % stride === 0 || semi === polyline.length) {
      const comma = polyline.indexOf(',', at);
      if (comma > at && comma < semi) {
        const a = +polyline.slice(at, comma), b = +polyline.slice(comma + 1, semi);
        if (Number.isFinite(a) && Number.isFinite(b) && Math.abs(a) <= 90 && Math.abs(b) <= 180) { lat.push(a); lng.push(b); }
      }
    }
    n++; at = semi + 1;
  }
  const count = lat.length;
  if (count < 2) return null;
  const k = Math.cos(lat[0] * Math.PI / 180) * 111320, m = 110540;
  const keep = new Uint8Array(count); keep[0] = keep[count - 1] = 1;
  const stack = [0, count - 1];
  while (stack.length) {
    const j = stack.pop(), i = stack.pop();
    const ax = lng[i] * k, ay = lat[i] * m, dx = lng[j] * k - ax, dy = lat[j] * m - ay, len = Math.hypot(dx, dy) || 1;
    let far = -1, best = 5 * len;
    for (let t = i + 1; t < j; t++) {
      const d = Math.abs(dy * (lng[t] * k - ax) - dx * (lat[t] * m - ay));
      if (d > best) { best = d; far = t; }
    }
    if (far > 0) { keep[far] = 1; stack.push(i, far, far, j); }
  }
  let out = [];
  for (let i = 0; i < count; i++) if (keep[i]) out.push(i);
  if (out.length > max) { const step = (out.length - 1) / (max - 1); out = Array.from({ length: max }, (_, i) => out[Math.round(i * step)]); }
  return out.map(i => lat[i].toFixed(5) + ',' + lng[i].toFixed(5)).join(';');
}

// ─────────────────────────────── cerc: Teren + Camarazi ───────────────────────────────
const FRIEND_FIELDS = ['name', 'lat', 'lng', 'locUpdatedAt', 'state', 'ghostUntil', 'nowPlaying', 'exploreCells'];
async function friendUids(fs, uid) {
  const rows = await fs.query('', 'friendships', { filters: [where('members', 'ARRAY_CONTAINS', uid)], select: ['members'], limit: 200 });
  if (!rows) return null;
  const set = new Set();
  for (const r of rows) for (const m of Array.isArray(r.members) ? r.members : []) if (typeof m === 'string' && m !== uid && /^[A-Za-z0-9_-]{1,128}$/.test(m)) set.add(m);
  return [...set].slice(0, SITE_RULES.friends_max);
}
async function cachedFriends(env, fs, uid, now, cache) {
  const hit = cache?.friends;
  if (hit && now - hit.at <= SITE_RULES.friends_ms && Array.isArray(hit.uids)) return { uids: hit.uids, write: null, failed: false };
  const uids = await friendUids(fs, uid);
  return { uids: uids || (Array.isArray(hit?.uids) ? hit.uids : []), write: uids ? { at: now, uids } : null, failed: !uids };
}
async function cercLive(fs, uid, friends, now) {
  const mark = fs.mark();
  const [meDoc, docs, familyRows] = await Promise.all([
    fs.get(`users/${uid}`, [...FRIEND_FIELDS, 'inviteCode']),
    fs.batchGet(friends.map(f => `users/${f}`), FRIEND_FIELDS),
    fs.query('', 'familyLoc', { filters: [where('allowed', 'ARRAY_CONTAINS', uid)], select: ['lat', 'lng', 'locUpdatedAt', 'state'], limit: 60 }),
  ]);
  const familyLoc = new Map((familyRows || []).filter(r => position(r)).map(r => [r.id, r]));
  let me = null;
  if (meDoc) {
    const ghost = isGhost(meDoc, now), p = ghost ? null : position(meDoc);
    me = { lat: p?.lat ?? null, lng: p?.lng ?? null, at: p ? time(meDoc.locUpdatedAt) : null, ghost,
      ghostUntil: meDoc.ghostUntil === -1 || (num(meDoc.ghostUntil) !== null && meDoc.ghostUntil > now) ? meDoc.ghostUntil : null,
      state: ghost ? 'ghost' : str(meDoc.state, 20), nowPlaying: ghost ? null : nowPlaying(meDoc.nowPlaying, now), exploreCells: int(meDoc.exploreCells) };
  }
  const out = [], family = [];
  for (const f of friends) {
    const u = docs.get(`users/${f}`);
    if (!u) continue;
    const name = str(u.name, 60) || 'Prieten', ghost = isGhost(u, now), fam = familyLoc.get(f);
    let p = ghost ? null : position(u), at = p ? time(u.locUpdatedAt) : null;
    // He has you in his family: familyLoc is written in the background every 2 min, often fresher than his public pin.
    if (!ghost && fam && (time(fam.locUpdatedAt) || 0) > (at || 0)) { p = position(fam); at = time(fam.locUpdatedAt); }
    out.push({ uid: f, name, initials: initials(name), lat: p?.lat ?? null, lng: p?.lng ?? null, at, state: ghost ? 'ghost' : str(u.state, 20),
      ghost, viaFamily: false, nowPlaying: ghost ? null : nowPlaying(u.nowPlaying, now), exploreCells: int(u.exploreCells) });
    // Ghost for everyone else, visible to you as family (MapScreen.kt:138-147): the position comes only through familyLoc.
    if (ghost && fam) family.push({ uid: f, name, initials: initials(name), lat: fam.lat, lng: fam.lng, at: time(fam.locUpdatedAt) });
  }
  return { me, friends: out.sort(byName), family: family.sort(byName), inviteCode: str(meDoc?.inviteCode, 40), failed: fs.failedSince(mark) };
}
const ROUTE_FIELDS = ['type', 'startAt', 'distanceM', 'durationS', 'polyline'];
/**
 * "Străzile tale": the newest 30 activities that have a route, simplified once and kept in the DO. A run is written once and
 * never edited, so after the first build each refresh only asks for activities newer than the newest known one (1 read).
 * The first build goes back 5 activities at a time and stops at ~600 KB of polylines per request (the free Workers CPU budget);
 * the next polls continue it until 30 routes or the oldest activity.
 */
async function updateRoutes(fs, uid, prev) {
  const st = { routes: [...(prev?.routes || [])], none: [...(prev?.none || [])], newest: prev?.newest ?? null, oldest: prev?.oldest ?? null, done: prev?.done === true };
  let bytes = 0, failed = false;
  const take = rows => {
    for (const a of rows) {
      bytes += typeof a.polyline === 'string' ? a.polyline.length : 0;
      if (!time(a.startAt)) continue;
      st.newest = st.newest === null ? a.startAt : Math.max(st.newest, a.startAt);
      st.oldest = st.oldest === null ? a.startAt : Math.min(st.oldest, a.startAt);
      const polyline = simplifyPolyline(a.polyline, SITE_RULES.route_points);
      if (polyline && !st.routes.some(r => r.id === a.id)) st.routes.push({ id: a.id, type: str(a.type, 20), startAt: a.startAt, distanceM: num(a.distanceM), durationS: num(a.durationS), polyline });
      // Activities without a route are remembered too, so Marș never downloads them again.
      if (!polyline && !st.none.includes(a.id)) st.none = [...st.none, a.id].slice(-100);
    }
  };
  // Newer than what we have, oldest first, so a burst of new runs never leaves a gap.
  for (let page = 0; st.newest !== null && page < 4 && bytes < SITE_RULES.route_bytes; page++) {
    const rows = await fs.query(`users/${uid}`, 'activities', { filters: [where('startAt', 'GREATER_THAN', st.newest)], orderBy: 'startAt', direction: 'ASCENDING', limit: SITE_RULES.route_page, select: ROUTE_FIELDS });
    if (!rows) { failed = true; break; }
    take(rows);
    if (rows.length < SITE_RULES.route_page) break;
  }
  // Older ones, newest first, until 30 routes, the oldest activity, or the byte budget of this request.
  while (!failed && !st.done && st.routes.length < SITE_RULES.routes && bytes < SITE_RULES.route_bytes) {
    const rows = await fs.query(`users/${uid}`, 'activities', { filters: st.oldest === null ? [] : [where('startAt', 'LESS_THAN', st.oldest)], orderBy: 'startAt', limit: SITE_RULES.route_page, select: ROUTE_FIELDS });
    if (!rows) { failed = true; break; }
    take(rows);
    if (rows.length < SITE_RULES.route_page) st.done = true;
  }
  st.routes.sort((a, b) => b.startAt - a.startAt);
  st.routes = st.routes.slice(0, SITE_RULES.routes);
  if (st.routes.length >= SITE_RULES.routes) st.done = true;
  return { ...st, failed };
}
async function recommendedPlaces(fs, uid) {
  const places = await fs.query('', 'places', { filters: [where('visibleTo', 'ARRAY_CONTAINS', uid)], select: ['ownerUid', 'ownerName', 'name', 'stars', 'note', 'lat', 'lng', 'visits', 'at'], limit: 100 });
  if (!places) return null;
  return places.filter(p => p.ownerUid !== uid && typeof p.ownerUid === 'string' && position(p)).sort((a, b) => (num(b.at) || 0) - (num(a.at) || 0))
    .map(p => ({ id: p.id, ownerUid: p.ownerUid, ownerName: str(p.ownerName, 60) || 'Un prieten', name: str(p.name, 80) || '', stars: int(p.stars) || 0, note: str(p.note, 300) || '', lat: p.lat, lng: p.lng, visits: int(p.visits) > 0 ? int(p.visits) : null }));
}
async function cerc(ctx) {
  const { env, fs, uid, now } = ctx;
  const key = uid + ':cerc', hit = recall(key, SITE_RULES.cerc_live_ms, now);
  if (hit) return hit;
  const cache = await account(env, uid, '/internal/site/cache?names=cerc-live,cerc-slow,friends');
  let live = cache?.['cerc-live'], slow = cache?.['cerc-slow'];
  const writes = {};
  if (!live || now - live.at > SITE_RULES.cerc_live_ms) {
    const friends = await cachedFriends(env, fs, uid, now, cache);
    if (friends.write) writes.friends = friends.write;
    const fresh = await cercLive(fs, uid, friends.uids, now);
    // A failed refresh never overwrites what we had: an older answer (with its own updated_at) beats an empty map.
    if (fresh.failed && live) ctx.stale = true;
    else {
      live = { at: now, me: fresh.me, friends: fresh.friends, family: fresh.family, inviteCode: fresh.inviteCode };
      if (!fresh.failed && !friends.failed) writes['cerc-live'] = live;
    }
  }
  const due = !slow || now - slow.at > SITE_RULES.cerc_slow_ms, building = slow && !slow.done;
  if (due || building) {
    const recommended = due ? await recommendedPlaces(fs, uid) : slow.recommended;
    const routes = await updateRoutes(fs, uid, slow);
    if (recommended === null && routes.failed && slow) ctx.stale = true;
    else {
      const { failed, ...kept } = routes;
      slow = { at: due ? now : slow.at, recommended: recommended ?? slow?.recommended ?? [], ...kept };
      if (!failed && recommended !== null) writes['cerc-slow'] = slow;
    }
  }
  if (Object.keys(writes).length) await account(env, uid, '/internal/site/cache', 'POST', writes);
  const out = { me: live.me, friends: live.friends, family: live.family, recommended: slow.recommended, routes: slow.routes, inviteCode: live.inviteCode, updated_at: live.at };
  if (!fs.unreachable && !ctx.stale) remember(key, out, now);
  return out;
}

// ─────────────────────────────── somn: nopțile, cronologia, sunetul ───────────────────────────────
const SLEEP_ID = /^s\d{1,19}$/;
const LABELS = { talk: 'Vorbit', snore: 'Sforăit', cough: 'Tuse', breath: 'Respirație' };
function eventLabel(e) {
  const name = LABELS[e.type] || 'Zgomot', i = num(e.intensity) || 0;
  const word = e.type === 'talk' ? '' : i >= 0.7 ? 'puternic' : i >= 0.4 ? 'moderat' : i > 0 ? 'redus' : '';
  return word ? `${name} · ${word}` : name;
}
const expired = (o, now) => { const at = Number(o.customMetadata?.at) || 0, ttl = Number(o.customMetadata?.ttl) || 7 * DAY; return at > 0 && now - at > ttl; };
async function listAll(bucket, prefix, pages = 5) {
  const out = [];
  let cursor;
  for (let i = 0; i < pages; i++) {
    const page = await bucket.list({ prefix, cursor, limit: 1000, include: ['customMetadata'] });
    out.push(...(page.objects || []));
    if (!page.truncated) break;
    cursor = page.cursor;
  }
  return out;
}
/** Map(sid → {chunks, analysis, status}) for every night still in R2 (7 days), from ONE listing of the user's prefix. */
async function sleepIndex(env, uid, now) {
  const index = new Map();
  if (!env.SLEEP) return index;
  let objects = [];
  try { objects = await listAll(env.SLEEP, uid + '/'); } catch { return index; }
  for (const o of objects) {
    const m = /^[^/]+\/(s\d{1,19})\/(chunk_(\d{1,3})\.m4a|analysis\.json)$/.exec(o.key);
    if (!m || expired(o, now)) continue;
    const row = index.get(m[1]) || { chunks: 0, analysis: false, status: null };
    if (m[3] !== undefined) row.chunks++; else { row.analysis = true; row.status = o.customMetadata?.status || null; }
    index.set(m[1], row);
  }
  return index;
}
const audioState = row => (!row || !row.chunks ? 'none' : row.analysis && row.status !== 'processing' ? 'ready' : 'pending');
async function somn({ env, fs, uid, now, url }) {
  const days = dayParam(url, SITE_RULES.somn_days);
  const [rows, index] = await Promise.all([
    fs.query(`users/${uid}`, 'sleep', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', now - days * DAY)], orderBy: 'startAt', limit: 100,
      select: ['startAt', 'endAt', 'score', 'deepMin', 'lightMin', 'remMin', 'snoreMin', 'talkCount', 'coverageMin', 'summary'] }),
    sleepIndex(env, uid, now),
  ]);
  return { nights: (rows || []).filter(r => SLEEP_ID.test(r.id) && time(r.startAt)).map(r => {
    const endAt = time(r.endAt) && r.endAt > r.startAt ? r.endAt : null;
    return { id: r.id, startAt: r.startAt, endAt, minutes: endAt ? Math.round((endAt - r.startAt) / MIN) : 0, score: int(r.score), deepMin: int(r.deepMin), lightMin: int(r.lightMin),
      remMin: int(r.remMin), snoreMin: int(r.snoreMin), talkCount: int(r.talkCount), coverageMin: int(r.coverageMin), summary: str(r.summary, 2000), audio: audioState(index.get(r.id)) };
  }) };
}
async function somnNight({ env, fs, uid, now }, id) {
  if (!SLEEP_ID.test(id)) return failure('Noaptea nu există.', 404);
  const night = await fs.get(`users/${uid}/sleep/${id}`, ['startAt', 'endAt', 'summary']);
  if (!night || !time(night.startAt)) return failure(fs.unreachable ? 'Datele nu răspund acum. Reîncearcă puțin mai târziu.' : 'Noaptea nu există.', fs.unreachable ? 503 : 404);
  // The app sends chunk and event times relative to the start of the recording; an absolute epoch (older clients, tests) is kept as is.
  const base = night.startAt, clockOf = v => (v > 1e12 ? v : base + v);
  let analysis = null, objects = [];
  if (env.SLEEP) {
    try {
      const [obj, list] = await Promise.all([env.SLEEP.get(`${uid}/${id}/analysis.json`), listAll(env.SLEEP, `${uid}/${id}/chunk_`, 1)]);
      objects = list;
      if (obj && !expired(obj, now)) analysis = JSON.parse(await obj.text());
    } catch { analysis = null; }
  }
  const known = new Map((Array.isArray(analysis?.state?.chunks) ? analysis.state.chunks : []).filter(c => Number.isInteger(c?.index)).map(c => [c.index, c]));
  const chunks = [];
  for (const o of objects) {
    const m = /\/chunk_(\d{1,3})\.m4a$/.exec(o.key);
    if (!m || expired(o, now)) continue;
    const i = Number(m[1]), meta = o.customMetadata || {};
    const from = Number.isFinite(Number(meta.from)) && meta.from !== undefined ? Number(meta.from) : num(known.get(i)?.from);
    const dur = Number.isFinite(Number(meta.dur)) && meta.dur !== undefined ? Number(meta.dur) : num(known.get(i)?.dur);
    if (from === null) continue;
    chunks.push({ i, startAt: clockOf(from), durationMs: dur, from });
  }
  chunks.sort((a, b) => a.i - b.i);
  const fromOf = new Map(chunks.map(c => [c.i, c.from]));
  for (const c of chunks) delete c.from;
  for (const [i, c] of known) if (!fromOf.has(i) && num(c.from) !== null) fromOf.set(i, c.from);
  const events = (Array.isArray(analysis?.events) ? analysis.events : []).slice(0, SITE_RULES.events_max).filter(e => e && num(e.from) !== null).map(e => {
    const chunk = Number.isInteger(e.chunk) ? e.chunk : null, start = chunk === null ? null : fromOf.get(chunk);
    return { t: clockOf(e.from), kind: String(e.type || 'noise'), label: eventLabel(e), text: str(e.transcript, 400), chunk,
      offsetMs: start === undefined || start === null ? null : Math.max(0, e.from - start), durationMs: num(e.to) !== null ? Math.max(0, e.to - e.from) : 0 };
  });
  return reply({ id, summary: str(night.summary, 2000), events, chunks });
}
/** One ≤ 35-min AAC chunk of the caller's own night, with Range (206) for seeking; 404 once the 7 days are over. */
async function somnChunk({ env, uid, now, request }, id, index) {
  if (!SLEEP_ID.test(id) || !/^\d{1,3}$/.test(index)) return failure('Sunetul nu există.', 404);
  if (!env.SLEEP) return failure('Sunetul nopților nu este disponibil pe site.', 404);
  const key = `${uid}/${id}/chunk_${Number(index)}.m4a`;
  const head = await env.SLEEP.head(key);
  if (!head || expired(head, now)) return failure('Sunetul a expirat. Se păstrează 7 zile.', 404);
  const size = head.size;
  const headers = { 'content-type': head.httpMetadata?.contentType || 'audio/mp4', 'accept-ranges': 'bytes', 'cache-control': 'private, no-store', 'x-content-type-options': 'nosniff' };
  const range = /^bytes=(\d*)-(\d*)$/.exec(request.headers.get('range') || '');
  if (range && (range[1] || range[2])) {
    const start = range[1] ? Number(range[1]) : Math.max(0, size - Number(range[2]));
    const end = range[1] && range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
    if (!Number.isFinite(start) || !Number.isFinite(end) || start > end || start >= size) return new Response(null, { status: 416, headers: { 'content-range': `bytes */${size}`, 'cache-control': 'no-store' } });
    const obj = await env.SLEEP.get(key, { range: { offset: start, length: end - start + 1 } });
    if (!obj) return failure('Sunetul a expirat. Se păstrează 7 zile.', 404);
    return new Response(obj.body, { status: 206, headers: { ...headers, 'content-range': `bytes ${start}-${end}/${size}`, 'content-length': String(end - start + 1) } });
  }
  const obj = await env.SLEEP.get(key);
  if (!obj) return failure('Sunetul a expirat. Se păstrează 7 zile.', 404);
  return new Response(obj.body, { headers: { ...headers, 'content-length': String(size) } });
}

// ─────────────────────────────── rație, marș, muzică, pază, inventar ───────────────────────────────
function dayParam(url, [min, max, fallback]) {
  const raw = url.searchParams.get('days');
  const n = raw === null || !/^\d{1,3}$/.test(raw) ? fallback : Number(raw);
  return Math.min(max, Math.max(min, n));
}
const mealDate = m => (Number.isSafeInteger(m.epochDay) && m.epochDay > 0 ? epochDayDate(m.epochDay) : localDate(m.at));
const MEAL_FIELDS = ['name', 'kcal', 'protein', 'carbs', 'fat', 'grams', 'mealType', 'source', 'confidence', 'epochDay', 'at'];
function targetsOf(t) {
  if (!t || [t.kcal, t.protein, t.carbs, t.fat].every(v => num(v) === null)) return null;
  return { kcal: int(t.kcal), protein: num(t.protein), carbs: num(t.carbs), fat: num(t.fat) };
}
async function ratie({ fs, uid, now, url }) {
  const days = dayParam(url, SITE_RULES.ratie_days), from = localMidnight(now) - (days - 1) * DAY;
  const [targets, meals] = await Promise.all([
    fs.get(`users/${uid}/settings/targets`, ['kcal', 'protein', 'carbs', 'fat']),
    fs.query(`users/${uid}`, 'meals', { filters: [where('at', 'GREATER_THAN_OR_EQUAL', from - 12 * HOUR)], orderBy: 'at', limit: 800, select: MEAL_FIELDS }),
  ]);
  const oldest = localDate(from), groups = new Map();
  for (const m of meals || []) {
    if (!time(m.at)) continue;
    const date = mealDate(m);
    if (date < oldest) continue;
    if (!groups.has(date)) groups.set(date, []);
    groups.get(date).push({ id: m.id, at: m.at, name: str(m.name, 120) || 'Masă', kcal: int(m.kcal) ?? 0, protein: round1(num(m.protein) || 0), carbs: round1(num(m.carbs) || 0),
      fat: round1(num(m.fat) || 0), grams: int(m.grams), mealType: str(m.mealType, 30), source: str(m.source, 30), confidence: num(m.confidence) ?? str(m.confidence, 30) });
  }
  const list = [...groups].sort((a, b) => (a[0] < b[0] ? 1 : -1)).map(([date, rows]) => {
    rows.sort((a, b) => a.at - b.at);
    return { date, kcal: Math.round(sum(rows, r => r.kcal)), protein: round1(sum(rows, r => r.protein)), carbs: round1(sum(rows, r => r.carbs)), fat: round1(sum(rows, r => r.fat)), meals: rows };
  });
  return { targets: targetsOf(targets), days: list };
}
const ACTIVITY_FIELDS = ['type', 'startAt', 'endAt', 'distanceM', 'durationS', 'kcal', 'polyline'];
const WORKOUT_FIELDS = ['startAt', 'endAt', 'durationS', 'title', 'kind', 'sets', 'volumeKg', 'kcal'];
function weekOf(activities, workouts, now) {
  const since = now - 7 * DAY, a = activities.filter(x => x.startAt >= since), w = workouts.filter(x => x.startAt >= since);
  return { km: round1(sum(a, x => x.distanceM) / 1000), minutes: Math.round((sum(a, x => x.durationS) + sum(w, x => x.durationS)) / 60), sessions: a.length + w.length };
}
/**
 * Mini route maps: from the routes already simplified for Teren (DO), else read for at most 8 activities per request
 * (the polylines are the heavy part of an activity); the rest stays null until Teren has built its routes.
 */
async function mars({ env, fs, uid, now, url }) {
  const days = dayParam(url, SITE_RULES.mars_days), since = now - days * DAY;
  const [acts, works, cache] = await Promise.all([
    fs.query(`users/${uid}`, 'activities', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', since)], orderBy: 'startAt', limit: 200, select: ACTIVITY_FIELDS.filter(f => f !== 'polyline') }),
    fs.query(`users/${uid}`, 'workouts', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', since)], orderBy: 'startAt', limit: 200, select: WORKOUT_FIELDS }),
    account(env, uid, '/internal/site/cache?names=cerc-slow'),
  ]);
  const known = new Map((cache?.['cerc-slow']?.routes || []).map(r => [r.id, r.polyline]));
  for (const id of cache?.['cerc-slow']?.none || []) known.set(id, null);
  const missing = (acts || []).filter(a => time(a.startAt) && !known.has(a.id)).slice(0, SITE_RULES.mars_polylines);
  if (missing.length) {
    const docs = await fs.batchGet(missing.map(a => `users/${uid}/activities/${a.id}`), ['polyline']);
    for (const a of missing) known.set(a.id, docs.get(`users/${uid}/activities/${a.id}`)?.polyline ?? null);
  }
  const activities = (acts || []).filter(a => time(a.startAt)).map(a => ({ id: a.id, type: str(a.type, 20), startAt: a.startAt, endAt: time(a.endAt), distanceM: num(a.distanceM) ?? 0,
    durationS: num(a.durationS) ?? 0, kcal: int(a.kcal), polyline: known.has(a.id) ? simplifyPolyline(known.get(a.id), SITE_RULES.mini_route_points) : null }));
  const workouts = (works || []).filter(w => time(w.startAt)).map(w => ({ id: w.id, startAt: w.startAt, endAt: time(w.endAt), durationS: num(w.durationS) ?? 0, title: str(w.title, 80),
    kind: str(w.kind, 30), sets: int(w.sets) ?? 0, volumeKg: num(w.volumeKg), kcal: int(w.kcal) }));
  return { activities, workouts, week: weekOf(activities, workouts, now) };
}
function musicSummary(m) {
  if (!m || !Array.isArray(m.top)) return null;
  const top = m.top.filter(t => t && str(t.title, 120)).slice(0, 10).map(t => ({ title: str(t.title, 120), artist: str(t.artist, 120), plays: int(t.plays) ?? 0, minutes: int(t.minutes) ?? 0, app: str(t.app, 40) }));
  return { updatedAt: time(m.updatedAt), windowDays: int(m.windowDays) ?? 7, totalMinutes: int(m.totalMinutes) ?? 0, top };
}
async function muzica({ fs, uid, now }) {
  const docs = await fs.batchGet([`users/${uid}`, `users/${uid}/settings/music`], ['nowPlaying', 'updatedAt', 'windowDays', 'totalMinutes', 'top']);
  return { now: nowPlaying(docs.get(`users/${uid}`)?.nowPlaying, now), summary: musicSummary(docs.get(`users/${uid}/settings/music`)) };
}
async function paza({ env, uid }) {
  const s = await summaryOf(env, uid);
  return { updated_at: s?.usage?.updated_at ?? null, days: s?.usage?.days || [] };
}
async function inventar({ env, fs, uid }) {
  const [runs, s] = await Promise.all([
    fs.query(`users/${uid}`, 'inventory', { orderBy: 'finishedAt', limit: SITE_RULES.inventar_runs }),
    summaryOf(env, uid),
  ]);
  return { runs: runs || [], vault: { total: s?.vault?.total ?? 0, latestAt: s?.vault?.latestAt ?? null } };
}

// ─────────────────────────────── azi + cont: legăturile fiecărei secțiuni ───────────────────────────────
function tokenEmail(request) {
  try {
    const part = (request.headers.get('Authorization') || '').slice(7).split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const claims = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(part + '='.repeat((4 - part.length % 4) % 4)), c => c.charCodeAt(0))));
    return typeof claims.email === 'string' ? claims.email : null;
  } catch { return null; }
}
const latest = (...values) => values.reduce((m, v) => (time(v) && (!m || v > m) ? v : m), null);
async function lastOf(fs, uid, collection, field, select) {
  const rows = await fs.query(`users/${uid}`, collection, { orderBy: field, limit: 1, select });
  return rows?.[0] || null;
}
function contractOf(c) {
  return { version: int(c?.version), at: time(c?.at), revokedAt: time(c?.revokedAt), current: SITE_RULES.contract_current };
}
/** Freshness window per section: "on" when the pipe delivered within it, "stale" when older, "off" when never. */
const WINDOWS = { teren: DAY, camarazi: DAY, gasire: 20 * MIN, inventar: 30 * DAY, somn: 2 * DAY, ratie: DAY, mars: 7 * DAY, muzica: DAY, paza: 2 * HOUR };
function link(key, lastAt, count, now) {
  return { key, state: !lastAt ? 'off' : now - lastAt <= WINDOWS[key] ? 'on' : 'stale', lastAt: lastAt || null, count: count ?? null };
}
function contractLink(c, now) {
  const signed = c.version !== null && c.at && (!c.revokedAt || c.revokedAt < c.at);
  return { key: 'cont', state: !signed ? 'off' : c.version >= SITE_RULES.contract_current ? 'on' : 'stale', lastAt: signed ? c.at : c.revokedAt || null, count: null };
}
async function azi({ env, fs, uid, now, request }) {
  const key = uid + ':azi', hit = recall(key, SITE_RULES.azi_ms, now);
  if (hit) return hit;
  const today = localDate(now), midnight = localMidnight(now);
  const cache = await account(env, uid, '/internal/site/cache?names=friends');
  const [docs, meals, acts, works, nights, inv, friends, summary, social] = await Promise.all([
    fs.batchGet([`users/${uid}`, `users/${uid}/settings/targets`, `users/${uid}/settings/music`], ['name', 'contract', 'locUpdatedAt', 'nowPlaying', 'kcal', 'updatedAt']),
    fs.query(`users/${uid}`, 'meals', { filters: [where('at', 'GREATER_THAN_OR_EQUAL', midnight - 12 * HOUR)], orderBy: 'at', limit: 80, select: ['kcal', 'protein', 'carbs', 'fat', 'epochDay', 'at'] }),
    fs.query(`users/${uid}`, 'activities', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', now - 7 * DAY)], orderBy: 'startAt', limit: 60, select: ['startAt', 'endAt', 'distanceM', 'durationS'] }),
    fs.query(`users/${uid}`, 'workouts', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', now - 7 * DAY)], orderBy: 'startAt', limit: 60, select: ['startAt', 'endAt', 'durationS'] }),
    fs.query(`users/${uid}`, 'sleep', { orderBy: 'startAt', limit: 2, select: ['startAt', 'endAt', 'score', 'summary'] }),
    lastOf(fs, uid, 'inventory', 'finishedAt', ['finishedAt']),
    cachedFriends(env, fs, uid, now, cache),
    summaryOf(env, uid),
    socialMeta(env, uid),
  ]);
  if (friends.write) await account(env, uid, '/internal/site/cache', 'POST', { friends: friends.write });
  const me = docs.get(`users/${uid}`), targets = docs.get(`users/${uid}/settings/targets`), music = docs.get(`users/${uid}/settings/music`);
  const todays = (meals || []).filter(m => time(m.at) && mealDate(m) === today);
  const lastMeal = (meals || []).reduce((m, r) => latest(m, r.at), null) || (await lastOf(fs, uid, 'meals', 'at', ['at']))?.at || null;
  const activities = (acts || []).filter(a => time(a.startAt)), workouts = (works || []).filter(w => time(w.startAt));
  const aToday = activities.filter(a => localDate(a.startAt) === today), wToday = workouts.filter(w => localDate(w.startAt) === today);
  const lastNight = (nights || []).find(n => time(n.endAt) && n.endAt > n.startAt) || null;
  const night = lastNight && now - lastNight.endAt <= 36 * HOUR ? { id: lastNight.id, startAt: lastNight.startAt, endAt: lastNight.endAt,
    minutes: Math.round((lastNight.endAt - lastNight.startAt) / MIN), score: int(lastNight.score), summary: str(lastNight.summary, 2000) } : null;
  const sleepAt = latest(...(nights || []).map(n => n.endAt), ...(nights || []).map(n => n.startAt));
  const moveAt = latest(...activities.map(a => a.endAt || a.startAt), ...workouts.map(w => w.endAt || w.startAt));
  const devices = summary?.recovery || [];
  const contract = contractOf(me?.contract);
  const out = {
    me: { uid, name: str(me?.name, 80), email: tokenEmail(request) },
    today: { date: today, kcal: Math.round(sum(todays, m => m.kcal)), kcalTarget: int(targets?.kcal), protein: round1(sum(todays, m => m.protein)), carbs: round1(sum(todays, m => m.carbs)),
      fat: round1(sum(todays, m => m.fat)), meals: todays.length, moveMin: Math.round((sum(aToday, a => a.durationS) + sum(wToday, w => w.durationS)) / 60),
      km: round1(sum(aToday, a => a.distanceM) / 1000), workouts: wToday.length },
    night,
    links: [
      link('teren', time(social?.explore?.updated_at), social ? social.explore.cells : null, now),
      link('camarazi', time(me?.locUpdatedAt), friends.uids.length, now),
      link('gasire', latest(...devices.map(d => d.seen_at)), devices.length, now),
      link('inventar', time(inv?.finishedAt), null, now),
      link('somn', sleepAt, null, now),
      link('ratie', lastMeal, todays.length, now),
      link('mars', moveAt, activities.length + workouts.length, now),
      link('muzica', latest(music?.updatedAt, me?.nowPlaying?.at), null, now),
      link('paza', latest(summary?.usage?.updated_at, summary?.sessions?.updated_at), summary?.usage?.days?.[0]?.date === today ? summary.usage.days[0].apps.length : null, now),
      contractLink(contract, now),
    ],
    updated_at: now,
  };
  if (!fs.unreachable) remember(key, out, now);
  return out;
}
async function cont({ env, fs, uid, request }) {
  const [docs, night, meal, act, work, inv, summary, social] = await Promise.all([
    fs.batchGet([`users/${uid}`, `users/${uid}/settings/music`], ['name', 'contract', 'nowPlaying', 'updatedAt']),
    lastOf(fs, uid, 'sleep', 'startAt', ['startAt', 'endAt']),
    lastOf(fs, uid, 'meals', 'at', ['at']),
    lastOf(fs, uid, 'activities', 'startAt', ['startAt', 'endAt']),
    lastOf(fs, uid, 'workouts', 'startAt', ['startAt', 'endAt']),
    lastOf(fs, uid, 'inventory', 'finishedAt', ['finishedAt']),
    summaryOf(env, uid),
    socialMeta(env, uid),
  ]);
  const me = docs.get(`users/${uid}`), music = docs.get(`users/${uid}/settings/music`);
  const until = time(social?.contacts?.until);
  return {
    me: { uid, name: str(me?.name, 80), email: tokenEmail(request) },
    contract: contractOf(me?.contract),
    pipes: [
      { key: 'sesiune', lastAt: time(summary?.sessions?.updated_at) },
      { key: 'galerie', lastAt: time(summary?.vault?.latestAt) },
      { key: 'explorare', lastAt: time(social?.explore?.updated_at) },
      // The agenda listing lives 30 days and is renewed at most once a day by a sync: renewal time = until − 30 days.
      { key: 'agenda', lastAt: social?.contacts?.discoverable && until ? until - 30 * DAY : null },
      { key: 'somn', lastAt: latest(night?.endAt, night?.startAt) },
      { key: 'gasire', lastAt: latest(...(summary?.recovery || []).map(d => d.seen_at)) },
      { key: 'mese', lastAt: time(meal?.at) },
      { key: 'miscare', lastAt: latest(act?.endAt, act?.startAt, work?.endAt, work?.startAt) },
      { key: 'muzica', lastAt: latest(music?.updatedAt, me?.nowPlaying?.at) },
      { key: 'inventar', lastAt: time(inv?.finishedAt) },
    ],
    intake: { paused: summary?.intake?.paused === true },
  };
}

// ─────────────────────────────── intrarea ───────────────────────────────
/**
 * Routes one verified request. `deps.fetcher` / `deps.now` exist for the tests (mocked Firestore, fixed clock).
 * Missing data is null/[]; only a Firestore that answers nothing at all is an error (503).
 */
export async function handleSiteApi(request, env, uid, deps = {}) {
  const url = new URL(request.url), m = SITE_PATH.exec(url.pathname);
  if (!m) return failure('Secțiune necunoscută.', 404);
  if (request.method !== 'GET') return failure('Metodă nepermisă.', 405);
  const [, section, rest = ''] = m;
  const token = (request.headers.get('Authorization') || '').slice(7);
  const ctx = { request, env, uid, url, now: deps.now ?? Date.now(), fs: new FirestoreReader(uid, token, deps.fetcher || fetch) };
  const parts = rest ? rest.split('/') : [];
  try {
    let data;
    if (section === 'somn' && parts.length === 1) return await somnNight(ctx, parts[0]);
    if (section === 'somn' && parts.length === 3 && parts[1] === 'chunk') return await somnChunk(ctx, parts[0], parts[2]);
    if (parts.length) return failure('Secțiune necunoscută.', 404);
    if (section === 'azi') data = await azi(ctx);
    else if (section === 'cerc') data = await cerc(ctx);
    else if (section === 'somn') data = await somn(ctx);
    else if (section === 'ratie') data = await ratie(ctx);
    else if (section === 'mars') data = await mars(ctx);
    else if (section === 'muzica') data = await muzica(ctx);
    else if (section === 'paza') data = await paza(ctx);
    else if (section === 'inventar') data = await inventar(ctx);
    else data = await cont(ctx);
    if (ctx.fs.unreachable && !ctx.stale) return failure('Datele din FORJA nu răspund acum. Reîncearcă peste un minut.', 503);
    return reply(data);
  } catch {
    return failure('Datele nu sunt disponibile acum.', 500);
  }
}
