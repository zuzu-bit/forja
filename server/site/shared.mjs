// FORJA 4.4 — ce folosesc toate secțiunile site-ului: cititorul Firestore (cu tokenul celui care cere), memoria scurtă,
// Durable Object-urile, regulile copiate din aplicație și poarta contractului. Nu are rute; site-api.mjs le reexportă.
import { firestoreFields, accountStub, internalRequest } from '../insights-ai.mjs';
import { localDate, epochDayDate, DAY } from '../site-time.mjs';
import { CERC_LIVE_MAX_MS } from '../site-store.mjs';

export const PROJECT = 'forja-65093';
export const DOCS = `projects/${PROJECT}/databases/(default)/documents`;
export const BASE = `https://firestore.googleapis.com/v1/${DOCS}`;
export const MIN = 60000, HOUR = 3600000;
export const SITE_RULES = Object.freeze({
  cerc_live_ms: 20000, cerc_slow_ms: 10 * MIN, friends_ms: 10 * MIN, azi_ms: 20000, now_playing_ms: 10 * MIN,
  contract_current: 4, contract_base: 3, friends_max: 100, routes: 30, route_points: 300, mini_route_points: 120, route_page: 5, route_bytes: 600 * 1024,
  routes_check_ms: DAY, mars_polylines: 8, mars_cached: 250, stale_max_ms: CERC_LIVE_MAX_MS,
  somn_days: [1, 60, 14], ratie_days: [1, 90, 30], mars_days: [1, 90, 30], inventar_runs: 20, events_max: 1000,
});

export const reply = (data, status = 200) => Response.json(data, { status, headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
export const failure = (error, status) => reply({ error }, status);
export const num = v => (typeof v === 'number' && Number.isFinite(v) ? v : null);
export const int = v => (num(v) === null ? null : Math.round(v));
export const str = (v, max = 200) => (typeof v === 'string' && v.trim() ? v.trim().slice(0, max) : null);
export const round1 = v => Math.round(v * 10) / 10;
export const time = v => (num(v) !== null && v > 0 ? v : null);
export const sum = (rows, f) => rows.reduce((n, r) => n + (num(f(r)) || 0), 0);

// ─────────────────────────────── Firestore REST, cu tokenul utilizatorului ───────────────────────────────
function encode(v) {
  if (typeof v === 'string') return { stringValue: v };
  if (Number.isSafeInteger(v)) return { integerValue: String(v) };
  if (typeof v === 'number') return { doubleValue: v };
  if (typeof v === 'boolean') return { booleanValue: v };
  // `IN` / `ARRAY_CONTAINS_ANY`: o listă de valori simple.
  if (Array.isArray(v)) return { arrayValue: { values: v.map(encode) } };
  return { nullValue: null };
}
// The document id is authoritative (s123, a45, m7, runId…), even if a field is also called `id`.
export const docOf = d => ({ ...firestoreFields(d.fields), id: d.name.split('/').pop() });
export const where = (field, op, value) => ({ fieldFilter: { field: { fieldPath: field }, op, value: encode(value) } });

const BATCH_MAX = 10;
export class FirestoreReader {
  constructor(uid, token, fetcher = (u, o) => fetch(u, o)) { this.uid = uid; this.token = token; this.fetcher = fetcher; this.reads = 0; this.calls = 0; this.okCalls = 0; this.failures = 0; this.codes = []; }
  /** Rules may refuse a read (403): that data is simply not visible. Network errors and 5xx count as failures. */
  async send(url, body) {
    this.calls++;
    let res;
    try {
      res = await this.fetcher(url, { method: body ? 'POST' : 'GET', headers: { Authorization: 'Bearer ' + this.token, ...(body ? { 'content-type': 'application/json' } : {}) },
        ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(10000) });
    } catch (e) { this.failures++; this.note(e?.name === 'TimeoutError' ? 'timeout' : 'net'); return { failed: true }; }
    if (res.status === 404 || res.status === 403) { this.okCalls++; return { absent: true, status: res.status }; }
    if (!res.ok) { this.failures++; let st = ''; try { st = (await res.json())?.error?.status || ''; } catch { } this.note(res.status + (st ? ':' + st : '')); return { failed: true }; }
    try { const data = await res.json(); this.okCalls++; return { data }; } catch { this.failures++; return { failed: true }; }
  }
  /** Codurile eșecurilor (fără date), pentru diagnostic: „401:UNAUTHENTICATED”, „429:RESOURCE_EXHAUSTED”, „timeout”. */
  note(c) { if (this.codes.length < 6 && !this.codes.includes(c)) this.codes.push(c); }
  get unreachable() { return this.calls > 0 && this.okCalls === 0; }
  /**
   * A checkpoint; `failedSince(mark)` is true when ANY call made after it failed (network, timeout, 5xx). get() and batchGet()
   * answer null for a failed document just like for a missing one, so this is how a caller tells "gone" from "not read".
   */
  mark() { return { failures: this.failures }; }
  failedSince(m) { return this.failures > m.failures; }
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
    // Regulile noi (FIRESTORE-RULES.md) fac un exists() pe prietenie pentru fiecare profil, iar o cerere cu mai multe
    // documente are voie la 20 de verificări: loturi de câte BATCH_MAX, fiecare cu propria revenire la 403.
    if (paths.length > BATCH_MAX) {
      for (let i = 0; i < paths.length; i += BATCH_MAX) {
        for (const [k, v] of await this.batchGet(paths.slice(i, i + BATCH_MAX), fields)) out.set(k, v);
      }
      return out;
    }
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
export function recall(key, maxAge, now) { const hit = memory.get(key); return hit && now - hit.at <= maxAge ? hit.value : null; }
export function remember(key, value, now) {
  memory.set(key, { at: now, value });
  if (memory.size > 500) memory.delete(memory.keys().next().value);
}
/** Tests only: forget every cached section. */
export function resetSiteCache() { memory.clear(); }

// ─────────────────────────────── Durable Objects (contul, graful social) ───────────────────────────────
export async function account(env, uid, path, method = 'GET', body) {
  if (!env.INSIGHTS) return null;
  try {
    const res = await accountStub(env, uid).fetch(internalRequest(uid, path, method, body));
    return res.ok ? await res.json() : null;
  } catch { return null; }
}
export const summaryOf = (env, uid) => account(env, uid, '/internal/site/summary');
export async function socialMeta(env, uid) {
  if (!env.SOCIAL) return null;
  try {
    const res = await env.SOCIAL.get(env.SOCIAL.idFromName('friends-v1')).fetch(new Request('https://internal/v2/social/site-meta', { headers: { 'x-forja-owner': uid } }));
    return res.ok ? await res.json() : null;
  } catch { return null; }
}

// ─────────────────────────────── reguli copiate din aplicație ───────────────────────────────
export const isGhost = (u, now) => u?.ghostUntil === -1 || (num(u?.ghostUntil) !== null && u.ghostUntil > now);
export function nowPlaying(np, now) {
  if (!np || typeof np !== 'object') return null;
  const title = str(np.title, 120), at = num(np.at);
  if (!title || at === null || now - at >= SITE_RULES.now_playing_ms) return null;
  return { title, artist: str(np.artist, 120), app: str(np.app, 40), at };
}
export function initials(name) {
  return String(name || '').trim().split(/\s+/).slice(0, 2).map(w => Array.from(w)[0] || '').join('').toLocaleUpperCase('ro') || '?';
}
export const position = u => (num(u?.lat) !== null && num(u?.lng) !== null ? { lat: u.lat, lng: u.lng } : null);
export const byName = (a, b) => a.name.localeCompare(b.name, 'ro');

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

export function dayParam(url, [min, max, fallback]) {
  const raw = url.searchParams.get('days');
  const n = raw === null || !/^\d{1,3}$/.test(raw) ? fallback : Number(raw);
  return Math.min(max, Math.max(min, n));
}
export const mealDate = m => (Number.isSafeInteger(m.epochDay) && m.epochDay > 0 ? epochDayDate(m.epochDay) : localDate(m.at));
export const latest = (...values) => values.reduce((m, v) => (time(v) && (!m || v > m) ? v : m), null);
export function contractOf(c) {
  const at = time(c?.at), revokedAt = time(c?.revokedAt);
  // A new signature is written with merge, so an older revokedAt stays next to it: a revoke before the signature is history.
  return { version: int(c?.version), at, revokedAt: revokedAt && (!at || revokedAt >= at) ? revokedAt : null, current: SITE_RULES.contract_current };
}
/** Semnat și nerevocat (o revocare mai veche decât semnătura e istorie). */
const signedOf = c => c.version !== null && !!c.at && (!c.revokedAt || c.revokedAt < c.at);
export function contractLink(c, now) {
  // „on” doar la versiunea curentă (v4); v3 semnat e „stale”: merge mai departe, dar are rânduri noi de semnat.
  const signed = signedOf(c);
  return { key: 'cont', state: !signed ? 'off' : c.version >= SITE_RULES.contract_current ? 'on' : 'stale', lastAt: signed ? c.at : c.revokedAt || null, count: null };
}
/**
 * Contract semnat cel puțin la versiunea `min` și nerevocat (users/{uid}.contract). Implicit v3: ce a pornit cu v3 — ținte,
 * antrenamente, topul muzicii, rulările Inventarului — rămâne pe site și după ce contractul curent a trecut la v4, până la
 * re-semnare. Ce pornește abia cu v4 (focus, detox, respirație, Casca, jurnalul de ascultare, jocuri, urma Găsirii, poza
 * mesei, coperțile dosarelor) întreabă contractGate(raw, 4). Revocat, nesemnat sau sub `min`: nu se arată, chiar dacă
 * documentele sunt încă în Firestore.
 */
export function contractGate(raw, min = SITE_RULES.contract_base) { const c = contractOf(raw); return signedOf(c) && c.version >= min; }
