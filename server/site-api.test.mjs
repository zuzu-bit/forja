import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID, randomBytes } from 'node:crypto';
import { handleSiteApi, isSiteApi, resetSiteCache, simplifyPolyline, initials, SITE_RULES, FirestoreReader } from './site-api.mjs';
import { applyUsageRollup, usageDays } from './site-store.mjs';
import { firestoreValue } from './insights-ai.mjs';
import { InsightsAccount } from './insights-store.mjs';
import { SocialGraph } from './social.mjs';
import { localDate, localMidnight } from './site-time.mjs';

// ── a small Firestore REST emulator: documents, runQuery (filters, orderBy, limit, select), batchGet, masks, read counting ──
const DOCS = 'projects/forja-65093/databases/(default)/documents';
const BASE = 'https://firestore.googleapis.com/v1/' + DOCS;
function encode(v) {
  if (v === null || v === undefined) return { nullValue: null };
  if (typeof v === 'string') return { stringValue: v };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  if (Array.isArray(v)) return { arrayValue: v.length ? { values: v.map(encode) } : {} };
  return { mapValue: { fields: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, encode(x)])) } };
}
class FakeFirestore {
  docs = new Map(); reads = 0; requests = []; deny = new Set(); down = false; failWhen = null;
  set(path, data) { this.docs.set(path, structuredClone(data)); }
  doc(path, mask) {
    const data = this.docs.get(path);
    const fields = Object.fromEntries(Object.entries(data).filter(([k]) => !mask || mask.includes(k)).map(([k, v]) => [k, encode(v)]));
    return { name: DOCS + '/' + path, fields };
  }
  match(doc, filter) {
    if (!filter) return true;
    if (filter.compositeFilter) return filter.compositeFilter.filters.every(f => this.match(doc, f));
    const { field, op, value } = filter.fieldFilter, v = doc[field.fieldPath], want = firestoreValue(value);
    if (op === 'ARRAY_CONTAINS') return Array.isArray(v) && v.includes(want);
    if (v === undefined) return false;
    if (op === 'EQUAL') return v === want;
    if (op === 'GREATER_THAN_OR_EQUAL') return v >= want;
    if (op === 'GREATER_THAN') return v > want;
    if (op === 'LESS_THAN') return v < want;
    throw Error('unsupported op ' + op);
  }
  fetcher = async (url, init = {}) => {
    this.requests.push({ url, body: init.body ? JSON.parse(init.body) : null, auth: init.headers?.Authorization });
    if (this.down) throw new TypeError('network down');
    const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json' } });
    if (this.failWhen?.(url, init.body ? JSON.parse(init.body) : null)) return json({ error: { status: 'UNAVAILABLE' } }, 503);
    if (!url.startsWith(BASE)) throw Error('unexpected host ' + url);
    const rest = url.slice(BASE.length);
    if (init.method === 'GET' || !init.method) {
      const u = new URL('https://x' + rest.replace(/^\//, '/')), path = decodeURIComponent(u.pathname.slice(1)), mask = u.searchParams.getAll('mask.fieldPaths');
      this.reads++;
      if (this.deny.has(path)) return json({ error: { status: 'PERMISSION_DENIED' } }, 403);
      return this.docs.has(path) ? json(this.doc(path, mask.length ? mask : null)) : json({ error: { status: 'NOT_FOUND' } }, 404);
    }
    const body = JSON.parse(init.body);
    if (rest === ':batchGet') {
      const paths = body.documents.map(d => d.slice(DOCS.length + 1));
      if (paths.some(p => this.deny.has(p))) return json({ error: { status: 'PERMISSION_DENIED' } }, 403);
      this.reads += paths.length;
      return json(paths.map(p => (this.docs.has(p) ? { found: this.doc(p, body.mask?.fieldPaths) } : { missing: DOCS + '/' + p })));
    }
    const q = /^(?:\/(.+))?:runQuery$/.exec(rest);
    if (!q) throw Error('unexpected call ' + url);
    const parent = q[1] ? q[1] + '/' : '', sq = body.structuredQuery, coll = sq.from[0].collectionId;
    let rows = [...this.docs].filter(([p]) => p.startsWith(parent + coll + '/') && !p.slice((parent + coll + '/').length).includes('/')).map(([p, d]) => ({ p, d }));
    rows = rows.filter(r => this.match(r.d, sq.where));
    if (sq.orderBy) { const { field, direction } = sq.orderBy[0]; rows = rows.filter(r => r.d[field.fieldPath] !== undefined).sort((a, b) => (a.d[field.fieldPath] - b.d[field.fieldPath]) * (direction === 'DESCENDING' ? -1 : 1)); }
    rows = rows.slice(0, sq.limit ?? 1000);
    this.reads += Math.max(1, rows.length);
    const select = sq.select?.fields.map(f => f.fieldPath);
    return json(rows.length ? rows.map(r => ({ document: this.doc(r.p, select), readTime: 'now' })) : [{ readTime: 'now' }]);
  };
}

// ── Durable Object and R2 doubles ──
class Storage {
  m = new Map(); alarm = null;
  async get(k) { return structuredClone(this.m.get(k)); }
  async put(k, v) { if (typeof k === 'object') { for (const [a, b] of Object.entries(k)) this.m.set(a, structuredClone(b)); } else this.m.set(k, structuredClone(v)); }
  async delete(k) { for (const key of Array.isArray(k) ? k : [k]) this.m.delete(key); }
  async list({ prefix = '', startAfter = '', limit = 1e9 } = {}) { return new Map([...this.m].filter(([k]) => k.startsWith(prefix) && k > startAfter).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).slice(0, limit).map(([k, v]) => [k, structuredClone(v)])); }
  async transaction(fn) { return fn(this); }
  async getAlarm() { return this.alarm; } async setAlarm(v) { this.alarm = v; } async deleteAlarm() { this.alarm = null; }
}
class Bucket {
  files = new Map(); lists = 0;
  async put(key, value, opts = {}) { const bytes = typeof value === 'string' ? new TextEncoder().encode(value) : new Uint8Array(value); this.files.set(key, { bytes, httpMetadata: opts.httpMetadata || {}, customMetadata: opts.customMetadata || {} }); }
  async head(key) { const f = this.files.get(key); return f ? { key, size: f.bytes.length, httpMetadata: f.httpMetadata, customMetadata: f.customMetadata } : null; }
  async get(key, opts = {}) {
    const f = this.files.get(key); if (!f) return null;
    const bytes = opts.range ? f.bytes.slice(opts.range.offset, opts.range.offset + opts.range.length) : f.bytes;
    return { key, size: f.bytes.length, body: bytes, httpMetadata: f.httpMetadata, customMetadata: f.customMetadata, text: async () => new TextDecoder().decode(bytes) };
  }
  async delete(key) { for (const k of Array.isArray(key) ? key : [key]) this.files.delete(k); }
  async list({ prefix = '' } = {}) { this.lists++; return { objects: [...this.files].filter(([k]) => k.startsWith(prefix)).map(([key, f]) => ({ key, size: f.bytes.length, customMetadata: f.customMetadata })), truncated: false }; }
}
function queue() { let q = Promise.resolve(); return fn => { const job = q.then(fn); q = job.catch(() => {}); return job; }; }
function fixture() {
  const fs = new FakeFirestore(), sleep = new Bucket(), records = new Bucket();
  const accounts = new Map(), socialStorage = new Storage();
  const social = new SocialGraph({ storage: socialStorage, blockConcurrencyWhile: queue() });
  const account = uid => {
    if (!accounts.has(uid)) accounts.set(uid, new InsightsAccount({ storage: new Storage(), blockConcurrencyWhile: queue() }, { RECORDS: records }));
    return accounts.get(uid);
  };
  let doRequests = 0;
  const env = {
    SLEEP: sleep,
    INSIGHTS: { idFromName: n => n, get: id => ({ fetch: r => { doRequests++; return account(id.slice('account:'.length)).fetch(r); } }) },
    SOCIAL: { idFromName: n => n, get: () => ({ fetch: r => social.fetch(r) }) },
  };
  const token = uid => ['e30', Buffer.from(JSON.stringify({ sub: uid, email: uid + '@example.com' })).toString('base64url'), 'sig'].join('.');
  const call = async (path, { uid = 'alice', now = NOW, method = 'GET', headers = {} } = {}) => {
    const res = await handleSiteApi(new Request('https://forja.test' + path, { method, headers: { Authorization: 'Bearer ' + token(uid), ...headers } }), env, uid, { fetcher: fs.fetcher, now });
    const type = res.headers.get('content-type') || '';
    return { res, status: res.status, body: type.includes('json') ? await res.json() : null };
  };
  const doCall = (uid, path, method = 'GET', body) => account(uid).fetch(new Request('https://forja.test' + path, { method, headers: { 'x-forja-owner': uid, 'content-type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) }));
  const socialCall = (uid, path, method = 'GET', body) => social.fetch(new Request('https://forja.test/v2/social/' + path, { method, headers: { 'x-forja-owner': uid, 'content-type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) }));
  return { fs, sleep, env, call, doCall, socialCall, account, doRequests: () => doRequests };
}
// 28 Sept 2026, 15:00 in Bucharest (12:00 UTC).
const NOW = Date.UTC(2026, 8, 28, 12, 0);
const MIN = 60000, HOUR = 3600000, DAY = 86400000;
test.beforeEach(() => resetSiteCache());

function seedCircle(fs, n = 3) {
  fs.set('users/alice', { name: 'Lana Popescu', email: 'alice@example.com', lat: 44.43, lng: 26.1, locUpdatedAt: NOW - 2 * MIN, state: 'walk', ghostUntil: 0, inviteCode: 'K7Q2', exploreCells: 89,
    nowPlaying: { title: 'Fetele care ard', artist: 'Trupa', app: 'Spotify', at: NOW - 3 * MIN }, familyUids: ['bob'], contract: { version: 3, at: NOW - DAY } });
  const friends = ['bob', 'carol', 'dan', ...Array.from({ length: Math.max(0, n - 3) }, (_, i) => 'f' + i)].slice(0, n);
  for (const f of friends) {
    fs.set(`friendships/${['alice', f].sort().join('_')}`, { members: ['alice', f].sort(), since: NOW - 30 * DAY });
    fs.set(`users/${f}`, { name: f === 'bob' ? 'Bogdan Ionescu' : f === 'carol' ? 'Carla' : 'Dan ' + f, email: f + '@secret.example', lat: 44.44, lng: 26.11, locUpdatedAt: NOW - 5 * MIN, state: 'run', ghostUntil: 0, exploreCells: 12, inviteCode: 'SECRET' + f });
  }
  return friends;
}

test('route guard: only the nine 4.4 sections go to site-api; the worker wires it and reports /health v18', async () => {
  for (const p of ['/insights/api/azi', '/insights/api/cerc', '/insights/api/somn', '/insights/api/somn/s12', '/insights/api/somn/s12/chunk/0', '/insights/api/ratie', '/insights/api/mars', '/insights/api/muzica', '/insights/api/paza', '/insights/api/inventar', '/insights/api/cont'])
    assert.equal(isSiteApi(p), true, p);
  for (const p of ['/insights/api/state', '/insights/api/intake', '/insights/api/phones', '/insights/api/recommendations', '/insights/api/azimut', '/v2/files']) assert.equal(isSiteApi(p), false, p);
  const src = await readFile(new URL('./insights-worker.mjs', import.meta.url), 'utf8');
  assert.match(src, /import \{ handleSiteApi, isSiteApi \} from '\.\/site-api\.mjs';/);
  assert(src.indexOf('isSiteApi(path)') > 0 && src.indexOf('isSiteApi(path)') < src.indexOf("if (path.startsWith('/insights/api/')) return await handleInsights"), 'site sections are routed before the older insights handler');
  const health = /path === '\/health'\) return reply\((\{[^}]+\})\)/.exec(src)[1];
  const flags = Function('return ' + health)();
  assert.deepEqual(flags, { ok: true, service: 'forja-insights', version: 18, organizer_jobs: 4, journey: 1, explore_sync: 2, map3d: 1, content_ai: 2, visual_ui: 1, sleep_audio: 1, lost_phone: 2, partners: 1, contacts: 2, social: 1,
    organizer_modes: 1, files_sync: 1, cleanup_schedule: 1, background_audio: 1, organizer: 1, site_sections: 1, inventory_runs: 1, music_summary: 1 });
  const toml = await readFile(new URL('./wrangler.insights.toml', import.meta.url), 'utf8');
  assert.match(toml, /\[\[r2_buckets\]\]\s*\nbinding = "SLEEP"\s*\nbucket_name = "forja-sleep"/);
});

test('firestoreValue decodes maps, arrays, null, timestamps and doubles', () => {
  assert.deepEqual(firestoreValue({ mapValue: { fields: { title: { stringValue: 'X' }, at: { integerValue: '5' }, tags: { arrayValue: { values: [{ stringValue: 'a' }, { nullValue: null }] } } } } }), { title: 'X', at: 5, tags: ['a', null] });
  assert.deepEqual(firestoreValue({ arrayValue: {} }), []);
  assert.deepEqual(firestoreValue({ mapValue: {} }), {});
  assert.equal(firestoreValue({ timestampValue: '2026-09-28T12:00:00Z' }), NOW);
  assert.equal(firestoreValue({ doubleValue: 1.5 }), 1.5);
  assert.equal(firestoreValue({ booleanValue: false }), false);
  assert.equal(firestoreValue({ nullValue: null }), null);
});

test('cerc: friends from the Firestore graph with the app rules — ghost, 10-minute music, family, no email', async () => {
  const f = fixture();
  seedCircle(f.fs);
  f.fs.set('users/bob', { name: 'Bogdan Ionescu', email: 'bob@secret.example', lat: 45.1, lng: 25.1, locUpdatedAt: NOW - 3 * HOUR, state: 'run', ghostUntil: -1, nowPlaying: { title: 'Ascuns', artist: 'A', app: 'Spotify', at: NOW - MIN }, exploreCells: 40 });
  f.fs.set('users/carol', { name: 'Carla', lat: 44.45, lng: 26.12, locUpdatedAt: NOW - 20 * MIN, state: 'idle', ghostUntil: NOW - MIN, nowPlaying: { title: 'Vechi', artist: 'B', app: 'YT Music', at: NOW - 11 * MIN } });
  f.fs.set('users/dan', { name: 'Dan', lat: 44.46, lng: 26.13, locUpdatedAt: NOW - 30 * MIN, state: 'ride', ghostUntil: NOW + HOUR, nowPlaying: { title: 'Nu', artist: 'C', app: 'Spotify', at: NOW - MIN } });
  f.fs.set('familyLoc/bob', { lat: 45.2, lng: 25.2, locUpdatedAt: NOW - MIN, state: 'walk', allowed: ['alice'] });
  f.fs.set('familyLoc/carol', { lat: 44.47, lng: 26.14, locUpdatedAt: NOW - 2 * MIN, state: 'walk', allowed: ['alice'] });
  f.fs.set('familyLoc/dan', { lat: 1, lng: 1, locUpdatedAt: NOW - MIN, state: 'walk', allowed: ['zoe'] });
  f.fs.set('users/carol', { ...f.fs.docs.get('users/carol'), nowPlaying: { title: 'Acum', artist: 'Trupa', app: 'YT Music', at: NOW - 5 * MIN } });
  const r = await f.call('/insights/api/cerc');
  assert.equal(r.status, 200);
  assert.equal(r.res.headers.get('cache-control'), 'no-store');
  const b = r.body;
  assert.deepEqual(Object.keys(b), ['me', 'friends', 'family', 'recommended', 'routes', 'inviteCode', 'updated_at']);
  assert.deepEqual(b.me, { lat: 44.43, lng: 26.1, at: NOW - 2 * MIN, ghost: false, ghostUntil: null, state: 'walk', nowPlaying: { title: 'Fetele care ard', artist: 'Trupa', app: 'Spotify', at: NOW - 3 * MIN }, exploreCells: 89 });
  assert.equal(b.inviteCode, 'K7Q2');
  const byUid = Object.fromEntries(b.friends.map(x => [x.uid, x]));
  assert.deepEqual(b.friends.map(x => x.name), ['Bogdan Ionescu', 'Carla', 'Dan']);
  assert.deepEqual(byUid.bob, { uid: 'bob', name: 'Bogdan Ionescu', initials: 'BI', lat: null, lng: null, at: null, state: 'ghost', ghost: true, viaFamily: false, nowPlaying: null, exploreCells: 40 });
  assert.equal(byUid.carol.ghost, false, 'an expired ghostUntil is not ghost');
  assert.deepEqual([byUid.carol.lat, byUid.carol.lng, byUid.carol.at], [44.47, 26.14, NOW - 2 * MIN], 'a fresher familyLoc point wins for a visible friend who has you in family');
  assert.deepEqual(byUid.carol.nowPlaying, { title: 'Acum', artist: 'Trupa', app: 'YT Music', at: NOW - 5 * MIN });
  assert.equal(byUid.dan.ghost, true); assert.equal(byUid.dan.lat, null); assert.equal(byUid.dan.nowPlaying, null, 'ghost hides the music too');
  assert.deepEqual(b.family, [{ uid: 'bob', name: 'Bogdan Ionescu', initials: 'BI', lat: 45.2, lng: 25.2, at: NOW - MIN }], 'only a ghost friend who put you in his family shows through familyLoc');
  const text = JSON.stringify(b);
  for (const secret of ['@secret.example', 'SECRET', 'alice@example.com', 'zoe']) assert(!text.includes(secret), 'leaked ' + secret);
  for (const req of f.fs.requests) assert.equal(req.auth, 'Bearer ' + ['e30', Buffer.from(JSON.stringify({ sub: 'alice', email: 'alice@example.com' })).toString('base64url'), 'sig'].join('.'), 'every Firestore read uses the caller token');
  const masks = f.fs.requests.filter(q => q.url.endsWith(':batchGet')).map(q => q.body.mask.fieldPaths);
  assert(masks.length && masks.every(m => !m.includes('email') && !m.includes('inviteCode')), 'friends are read with a mask without email or invite code');
  // After an 11-minute-old song, the badge disappears (music is "now" for 10 minutes).
  resetSiteCache();
  f.fs.set('users/carol', { ...f.fs.docs.get('users/carol'), nowPlaying: { title: 'Acum', artist: 'Trupa', app: 'YT Music', at: NOW - 11 * MIN } });
  const later = await f.call('/insights/api/cerc', { now: NOW + 21000 });
  assert.equal(later.body.friends.find(x => x.uid === 'carol').nowPlaying, null);
});

test('cerc: own ghost hides own position and music; recommended places and routes', async () => {
  const f = fixture();
  seedCircle(f.fs, 1);
  f.fs.set('users/alice', { ...f.fs.docs.get('users/alice'), ghostUntil: -1 });
  f.fs.set('places/r1', { ownerUid: 'bob', ownerName: 'Bogdan', name: 'Terasa', stars: 5, note: 'Cafea bună', lat: 44.4, lng: 26.0, visits: 3, at: NOW - DAY, visibleTo: ['alice', 'carol'] });
  f.fs.set('places/r2', { ownerUid: 'bob', ownerName: 'Bogdan', name: 'Parc', stars: 4, note: '', lat: 44.41, lng: 26.01, visits: 0, at: NOW - HOUR, visibleTo: ['alice'] });
  f.fs.set('places/mine', { ownerUid: 'alice', ownerName: 'Lana', name: 'Al meu', stars: 3, note: '', lat: 1, lng: 1, at: NOW, visibleTo: ['alice'] });
  f.fs.set('places/other', { ownerUid: 'bob', ownerName: 'Bogdan', name: 'Secret', stars: 3, note: '', lat: 1, lng: 1, at: NOW, visibleTo: ['carol'] });
  const long = Array.from({ length: 2000 }, (_, i) => `${(44.4 + i * 0.0001).toFixed(6)},${(26.1 + Math.sin(i / 30) * 0.001).toFixed(6)}`).join(';');
  for (let i = 0; i < 35; i++) f.fs.set(`users/alice/activities/a${i}`, { type: 'run', startAt: NOW - (i + 1) * DAY, endAt: NOW - (i + 1) * DAY + HOUR, distanceM: 5000, durationS: 1800, kcal: 300, polyline: i === 2 ? '' : i === 0 ? long : '44.4,26.1;44.41,26.11;44.42,26.1' });
  const b = (await f.call('/insights/api/cerc')).body;
  assert.deepEqual([b.me.ghost, b.me.lat, b.me.lng, b.me.at, b.me.nowPlaying, b.me.state, b.me.ghostUntil], [true, null, null, null, null, 'ghost', -1]);
  assert.deepEqual(b.recommended, [
    { id: 'r2', ownerUid: 'bob', ownerName: 'Bogdan', name: 'Parc', stars: 4, note: '', lat: 44.41, lng: 26.01, visits: null },
    { id: 'r1', ownerUid: 'bob', ownerName: 'Bogdan', name: 'Terasa', stars: 5, note: 'Cafea bună', lat: 44.4, lng: 26.0, visits: 3 },
  ]);
  assert.equal(b.routes.length, 30, 'newest 30 activities that have a route');
  assert.equal(b.routes[0].id, 'a0'); assert(!b.routes.some(r => r.id === 'a2'), 'no empty polylines');
  assert.deepEqual(Object.keys(b.routes[1]), ['id', 'type', 'startAt', 'distanceM', 'durationS', 'polyline']);
  const pts = b.routes[0].polyline.split(';');
  assert(pts.length <= SITE_RULES.route_points && pts.length > 20, 'long routes are simplified, not dropped: ' + pts.length);
  assert.equal(pts[0], '44.40000,26.10000');
});

test('cerc routes are built once, 600 KB of polylines per request, then only newer runs are read', async () => {
  const f = fixture();
  seedCircle(f.fs, 1);
  const heavy = i => Array.from({ length: 5400 }, (_, k) => `${(44.4 + k * 0.00001 + i * 0.001).toFixed(9)},${(26.1 + Math.sin(k / 50) * 0.001).toFixed(9)}`).join(';');
  for (let i = 0; i < 12; i++) f.fs.set(`users/alice/activities/a${i}`, { type: 'run', startAt: NOW - (i + 1) * DAY, distanceM: 8000, durationS: 5400, polyline: heavy(i) });
  const bytesOf = () => f.fs.requests.filter(r => r.body?.structuredQuery?.from?.[0]?.collectionId === 'activities').length;
  let r = (await f.call('/insights/api/cerc')).body;
  assert(r.routes.length >= 3 && r.routes.length < 12, 'the first request stops at the byte budget: ' + r.routes.length);
  assert.deepEqual(r.routes.map(x => x.id), r.routes.map((_, i) => 'a' + i), 'newest first, no gaps');
  let t = NOW, polls = 1;
  while (r.routes.length < 12 && polls < 10) { t += 30000; polls++; resetSiteCache(); r = (await f.call('/insights/api/cerc', { now: t })).body; }
  assert.equal(r.routes.length, 12, 'the next polls finish the build');
  assert(polls <= 5, 'built within a few polls: ' + polls);
  const before = f.fs.reads, queries = bytesOf();
  t += 30000; resetSiteCache(); await f.call('/insights/api/cerc', { now: t });
  assert.equal(bytesOf(), queries, 'a finished build does not read activities before the 10-minute refresh');
  f.fs.set('users/alice/activities/a99', { type: 'walk', startAt: NOW + 5 * MIN, distanceM: 900, durationS: 600, polyline: '44.5,26.2;44.51,26.21' });
  t += 11 * MIN; resetSiteCache(); r = (await f.call('/insights/api/cerc', { now: t })).body;
  assert.equal(r.routes[0].id, 'a99'); assert.equal(r.routes.length, 13);
  const q = f.fs.requests.filter(x => x.body?.structuredQuery?.from?.[0]?.collectionId === 'activities').slice(queries);
  assert.equal(q.length, 1, 'one query for newer runs'); assert.equal(q[0].body.structuredQuery.where.fieldFilter.op, 'GREATER_THAN');
  assert(f.fs.reads - before < 20, 'refresh reads: ' + (f.fs.reads - before));
});

test('cerc: one read budget — 20 s memory, DO tiers, and a 30 s poll for an hour with 10 friends stays far below 50k/day', async () => {
  const f = fixture();
  seedCircle(f.fs, 10);
  for (let i = 0; i < 5; i++) f.fs.set(`places/p${i}`, { ownerUid: 'bob', ownerName: 'B', name: 'L' + i, stars: 3, note: '', lat: 44, lng: 26, at: NOW, visibleTo: ['alice'] });
  for (let i = 0; i < 40; i++) f.fs.set(`users/alice/activities/a${i}`, { type: 'walk', startAt: NOW - i * HOUR, distanceM: 1000, durationS: 600, polyline: '44.4,26.1;44.41,26.11' });
  const first = f.fs.reads;
  await f.call('/insights/api/cerc');
  const cold = f.fs.reads - first;
  assert(cold <= 1 + 10 + 1 + 10 + 5 + 40, 'cold read ' + cold);
  let before = f.fs.reads;
  await f.call('/insights/api/cerc', { now: NOW + 10000 });
  assert.equal(f.fs.reads - before, 0, 'same isolate within 20 s: memory');
  resetSiteCache(); before = f.fs.reads;
  await f.call('/insights/api/cerc', { now: NOW + 15000 });
  assert.equal(f.fs.reads - before, 0, 'another isolate within 20 s: the account DO answers');
  resetSiteCache(); before = f.fs.reads;
  await f.call('/insights/api/cerc', { now: NOW + 25000 });
  const live = f.fs.reads - before;
  assert.equal(live, 1 + 10 + 1, 'after 20 s only the live tier: me + 10 friends (one batchGet) + familyLoc');
  assert(!f.fs.requests.slice(-3).some(r => r.body?.structuredQuery?.from?.[0]?.collectionId === 'friendships'), 'friend list comes from the 10-minute tier');
  // One hour of the site open on Teren, polling every 30 s, each poll on a cold isolate (worst case).
  before = f.fs.reads;
  for (let i = 1; i <= 120; i++) { resetSiteCache(); await f.call('/insights/api/cerc', { now: NOW + 25000 + i * 30000 }); }
  const hour = f.fs.reads - before;
  assert(hour < 2000, 'reads per hour of site open: ' + hour);
  assert(hour * 24 < 50000, 'even 24 h of continuous viewing fits the free tier: ' + hour * 24);
});

test('cerc: Firestore down → the last answer from the DO, or 503 when there is none; a refused friend is skipped', async () => {
  const f = fixture();
  seedCircle(f.fs);
  f.fs.down = true;
  let r = await f.call('/insights/api/cerc');
  assert.equal(r.status, 503); assert.match(r.body.error, /nu răspund/);
  f.fs.down = false;
  r = await f.call('/insights/api/cerc');
  assert.equal(r.status, 200);
  resetSiteCache(); f.fs.down = true;
  r = await f.call('/insights/api/cerc', { now: NOW + 60000 });
  assert.equal(r.status, 200, 'the stale tier is served instead of an empty map');
  assert.equal(r.body.updated_at, NOW); assert.equal(r.body.friends.length, 3);
  resetSiteCache();
  assert.equal((await f.call('/insights/api/cerc', { now: NOW + 11 * MIN })).status, 503, 'never older than 10 minutes');
  f.fs.down = false; resetSiteCache();
  f.fs.deny.add('users/carol');
  r = await f.call('/insights/api/cerc', { now: NOW + 11 * MIN });
  assert.deepEqual(r.body.friends.map(x => x.uid), ['bob', 'dan'], 'a friend the rules refuse is left out, the others stay');
});

test('cerc: one failed read (friends batchGet, familyLoc) keeps the last full answer instead of an empty map', async () => {
  const f = fixture();
  seedCircle(f.fs);
  f.fs.set('users/bob', { ...f.fs.docs.get('users/bob'), ghostUntil: -1 });
  f.fs.set('familyLoc/bob', { allowed: ['alice'], lat: 44.45, lng: 26.12, locUpdatedAt: NOW - MIN, state: 'walk' });
  let r = await f.call('/insights/api/cerc');
  assert.deepEqual([r.body.friends.length, r.body.family.length], [3, 1]);
  const storage = f.account('alice').ctx.storage;
  // Only the friends' batchGet answers 503 (the own doc and familyLoc work).
  resetSiteCache();
  f.fs.failWhen = url => url.endsWith(':batchGet');
  r = await f.call('/insights/api/cerc', { now: NOW + 30000 });
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.friends.map(x => x.uid), ['bob', 'carol', 'dan'], 'friends do not vanish');
  assert.equal(r.body.updated_at, NOW, 'served with its own time');
  assert.equal((await storage.get('site-cache:cerc-live')).at, NOW, 'the DO copy is not overwritten');
  // Only familyLoc fails.
  resetSiteCache();
  f.fs.failWhen = (url, body) => body?.structuredQuery?.from?.[0]?.collectionId === 'familyLoc';
  r = await f.call('/insights/api/cerc', { now: NOW + 60000 });
  assert.deepEqual([r.body.family.map(x => x.uid), r.body.updated_at], [['bob'], NOW]);
  // Recovered: a normal refresh.
  resetSiteCache();
  f.fs.failWhen = null;
  r = await f.call('/insights/api/cerc', { now: NOW + 90000 });
  assert.deepEqual([r.body.friends.length, r.body.family.length, r.body.updated_at], [3, 1, NOW + 90000]);
  // A first visit with the batchGet failing: the partial answer is served once, kept neither in the DO nor in memory.
  resetSiteCache();
  const g = fixture();
  seedCircle(g.fs);
  g.fs.failWhen = url => url.endsWith(':batchGet');
  r = await g.call('/insights/api/cerc');
  assert.equal(r.status, 200); assert.deepEqual(r.body.friends, []);
  assert.equal(await g.account('alice').ctx.storage.get('site-cache:cerc-live'), undefined);
  g.fs.failWhen = null;
  r = await g.call('/insights/api/cerc', { now: NOW + 1000 });
  assert.equal(r.body.friends.length, 3, 'the next poll reads again');
});

test('cerc: the memory copy never outlives 20 s from the time of its data; the DO copy leaves after 10 min', async t => {
  let clock = NOW;
  t.mock.method(Date, 'now', () => clock);
  const f = fixture();
  seedCircle(f.fs);
  await f.call('/insights/api/cerc');
  resetSiteCache();
  let r = await f.call('/insights/api/cerc', { now: NOW + 15000 });
  assert.equal(r.body.updated_at, NOW, 'another isolate takes the 15 s old copy from the DO');
  f.fs.set('users/bob', { ...f.fs.docs.get('users/bob'), ghostUntil: -1 });
  r = await f.call('/insights/api/cerc', { now: NOW + 25000 });
  assert.equal(r.body.updated_at, NOW + 25000, 'same isolate, 25 s after the data: read again');
  assert.equal(r.body.friends.find(x => x.uid === 'bob').ghost, true, 'a friend who turned ghost loses his pin within 20 s');
  const storage = f.account('alice').ctx.storage;
  assert.equal(storage.alarm, NOW + 10 * MIN, 'friends positions have an alarm');
  clock = NOW + 25000 + 10 * MIN;
  await f.account('alice').alarm();
  assert.equal(await storage.get('site-cache:cerc-live'), undefined, 'removed 10 min after the last read');
  assert(await storage.get('site-cache:cerc-slow'), 'own routes and places stay');
});

test('cerc: a run that reaches Firestore late, with an older date, joins "Străzile tale" within a day', async () => {
  const f = fixture();
  seedCircle(f.fs, 1);
  const line = i => `44.4,26.${10 + i};44.41,26.${11 + i};44.42,26.${10 + i}`;
  for (let i = 0; i < 10; i++) f.fs.set(`users/alice/activities/a${i}`, { type: 'run', startAt: NOW - (i + 1) * DAY, distanceM: 1000, durationS: 600, polyline: line(i) });
  let r = (await f.call('/insights/api/cerc')).body;
  assert.equal(r.routes.length, 10);
  // The phone's offline queue delivers a walk from 3.5 days ago after the newer runs were indexed.
  f.fs.set('users/alice/activities/late', { type: 'walk', startAt: NOW - 3.5 * DAY, distanceM: 700, durationS: 900, polyline: line(20) });
  resetSiteCache();
  r = (await f.call('/insights/api/cerc', { now: NOW + 11 * MIN })).body;
  assert(!r.routes.some(x => x.id === 'late'), 'the 10-minute refresh only asks for newer runs');
  resetSiteCache();
  const before = f.fs.reads;
  r = (await f.call('/insights/api/cerc', { now: NOW + DAY + 12 * MIN })).body;
  assert.deepEqual(r.routes.slice(0, 4).map(x => x.id), ['a0', 'a1', 'a2', 'late']);
  assert.equal(r.routes.length, 11);
  assert(f.fs.reads - before < 40, 'the daily check is cheap: ' + (f.fs.reads - before));
  resetSiteCache();
  const q = f.fs.requests.length;
  await f.call('/insights/api/cerc', { now: NOW + DAY + 23 * MIN });
  assert(!f.fs.requests.slice(q).some(x => x.url.endsWith(':batchGet') && x.body.mask.fieldPaths.includes('polyline')), 'checked once a day, not every refresh');
});

test('azi: today, last night, and one link per section with on / stale / off', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  seedCircle(f.fs, 2);
  const midnight = localMidnight(NOW), today = localDate(NOW), epochToday = Math.floor(Date.UTC(2026, 8, 28) / DAY);
  f.fs.set('users/alice/settings/targets', { kcal: 2100, protein: 120, carbs: 250, fat: 70, updatedAt: NOW - DAY });
  f.fs.set('users/alice/settings/music', { updatedAt: NOW - 2 * HOUR, windowDays: 7, totalMinutes: 300, top: [] });
  f.fs.set('users/alice/meals/m1', { name: 'Omletă', kcal: 420, protein: 28.5, carbs: 3, fat: 30, epochDay: epochToday, at: midnight + 8 * HOUR });
  f.fs.set('users/alice/meals/m2', { name: 'Ciorbă', kcal: 380.4, protein: 20, carbs: 30.25, fat: 12, epochDay: epochToday, at: midnight + 13 * HOUR });
  f.fs.set('users/alice/meals/m0', { name: 'Cină ieri', kcal: 900, protein: 40, carbs: 80, fat: 30, epochDay: epochToday - 1, at: midnight - 2 * HOUR });
  f.fs.set('users/alice/activities/a1', { type: 'run', startAt: midnight + 7 * HOUR, endAt: midnight + 7.5 * HOUR, distanceM: 5230, durationS: 1800, polyline: '' });
  f.fs.set('users/alice/activities/a0', { type: 'walk', startAt: NOW - 3 * DAY, endAt: NOW - 3 * DAY + HOUR, distanceM: 4000, durationS: 3600, polyline: '' });
  f.fs.set('users/alice/workouts/w1', { startAt: midnight + 10 * HOUR, endAt: midnight + 11 * HOUR, durationS: 2700, title: 'Forță', kind: 'gym', sets: 12, volumeKg: 3200, kcal: null, source: 'instructie' });
  f.fs.set('users/alice/sleep/s41', { startAt: NOW - 18 * HOUR, endAt: NOW - 10 * HOUR, score: 82, summary: 'Somn liniștit.' });
  f.fs.set('users/alice/sleep/s42', { startAt: NOW - 20 * MIN, endAt: 0, score: 0, summary: '' });
  f.fs.set('users/alice/inventory/r1', { id: 'r1', kind: 'photos', startedAt: NOW - 40 * DAY, finishedAt: NOW - 40 * DAY + MIN });
  const device = randomUUID(), secret = randomBytes(32).toString('hex');
  await f.doCall('alice', `/v2/recovery/devices/${device}/grant`, 'POST', { name: 'Galaxy S23', secret, basis: 'contract', contract_version: 3 });
  await f.doCall('alice', `/v2/recovery/devices/${device}/beat`, 'POST', { secret, status: 'ready', battery: 64 });
  await f.socialCall('alice', 'explore/sync', 'POST', { device: randomUUID(), cells: [{ id: '1_1', min_lat: 44.43, min_lng: 26.09, max_lat: 44.431, max_lng: 26.091, first_at: NOW - DAY, last_at: NOW - HOUR, visits: 2, mode: 'walk' }] });
  const r = await f.call('/insights/api/azi');
  assert.equal(r.status, 200);
  const b = r.body;
  assert.deepEqual(Object.keys(b), ['me', 'today', 'night', 'links', 'updated_at']);
  assert.deepEqual(b.me, { uid: 'alice', name: 'Lana Popescu', email: 'alice@example.com' }, 'own email from the verified token');
  assert.deepEqual(b.today, { date: today, kcal: 800, kcalTarget: 2100, protein: 48.5, carbs: 33.3, fat: 42, meals: 2, moveMin: 75, km: 5.2, workouts: 1 });
  assert.deepEqual(b.night, { id: 's41', startAt: NOW - 18 * HOUR, endAt: NOW - 10 * HOUR, minutes: 480, score: 82, summary: 'Somn liniștit.' }, 'the night in progress is skipped');
  assert.deepEqual(b.links.map(l => l.key), ['teren', 'camarazi', 'gasire', 'inventar', 'somn', 'ratie', 'mars', 'muzica', 'paza', 'cont']);
  const L = Object.fromEntries(b.links.map(l => [l.key, l]));
  assert.deepEqual(L.teren, { key: 'teren', state: 'on', lastAt: NOW, count: 1 });
  assert.deepEqual(L.camarazi, { key: 'camarazi', state: 'on', lastAt: NOW - 2 * MIN, count: 2 });
  assert.deepEqual(L.gasire, { key: 'gasire', state: 'on', lastAt: NOW, count: 1 });
  assert.deepEqual(L.inventar, { key: 'inventar', state: 'stale', lastAt: NOW - 40 * DAY + MIN, count: null });
  assert.equal(L.somn.state, 'on'); assert.equal(L.ratie.count, 2); assert.equal(L.ratie.lastAt, midnight + 13 * HOUR);
  assert.deepEqual(L.mars, { key: 'mars', state: 'on', lastAt: midnight + 11 * HOUR, count: 3 });
  assert.deepEqual(L.muzica, { key: 'muzica', state: 'on', lastAt: NOW - 3 * MIN, count: null }, 'the fresher of the weekly top and the live song');
  assert.deepEqual(L.paza, { key: 'paza', state: 'off', lastAt: null, count: null });
  assert.deepEqual(L.cont, { key: 'cont', state: 'on', lastAt: NOW - DAY, count: null });
  // contract v2 → stale (re-sign), revoked → off
  resetSiteCache();
  f.fs.set('users/alice', { ...f.fs.docs.get('users/alice'), contract: { version: 2, at: NOW - 9 * DAY } });
  assert.equal((await f.call('/insights/api/azi')).body.links.at(-1).state, 'stale');
  resetSiteCache();
  f.fs.set('users/alice', { ...f.fs.docs.get('users/alice'), contract: { version: 3, at: NOW - 9 * DAY, revokedAt: NOW - DAY } });
  assert.deepEqual((await f.call('/insights/api/azi')).body.links.at(-1), { key: 'cont', state: 'off', lastAt: NOW - DAY, count: null });
});

test('azi on an empty account: nulls and zeros, every link off', async () => {
  const f = fixture();
  const b = (await f.call('/insights/api/azi', { uid: 'nou' })).body;
  assert.deepEqual(b.me, { uid: 'nou', name: null, email: 'nou@example.com' });
  assert.deepEqual(b.today, { date: localDate(NOW), kcal: 0, kcalTarget: null, protein: 0, carbs: 0, fat: 0, meals: 0, moveMin: 0, km: 0, workouts: 0 });
  assert.equal(b.night, null);
  assert(b.links.every(l => l.state === 'off' && l.lastAt === null), JSON.stringify(b.links));
  assert.equal(b.links.find(l => l.key === 'camarazi').count, 0);
});

// ── Stingerea ──
async function seedNight(f, uid = 'alice', id = 's41', { status = 'complete', chunks = 2, age = 0 } = {}) {
  const startAt = NOW - 10 * HOUR;
  f.fs.set(`users/${uid}/sleep/${id}`, { startAt, endAt: startAt + 8 * HOUR, score: 80, deepMin: 90, lightMin: 250, remMin: 100, snoreMin: 14, talkCount: 2, coverageMin: 480, summary: 'Ai vorbit de două ori.' });
  const at = String(NOW - age);
  for (let i = 0; i < chunks; i++) await f.sleep.put(`${uid}/${id}/chunk_${i}.m4a`, new Uint8Array(1000 + i).fill(i + 1), { httpMetadata: { contentType: 'audio/mp4' }, customMetadata: { from: String(i * 1800000), dur: String(1800000), at, ttl: String(7 * DAY) } });
  if (status) await f.sleep.put(`${uid}/${id}/analysis.json`, JSON.stringify({ session: id, status, events: [
    { type: 'snore', from: 600000, to: 900000, chunk: 0, intensity: 0.8, confidence: 0.9 },
    { type: 'talk', from: 1900000, to: 1903000, chunk: 1, transcript: 'Nu acum.', intensity: 0, confidence: 0.7 },
    { type: 'cough', from: 2000000, to: 2001000, chunk: 1, intensity: 0.3, confidence: 0.6 },
  ], state: { chunks: Array.from({ length: chunks }, (_, i) => ({ index: i, from: i * 1800000, dur: 1800000 })) } }), { httpMetadata: { contentType: 'application/json' }, customMetadata: { at, ttl: String(7 * DAY), ...(status === 'legacy' ? {} : { status }) } });
  return startAt;
}
test('somn: nights from Firestore joined to R2 by the doc id; audio ready, pending or none', async () => {
  const f = fixture();
  await seedNight(f, 'alice', 's41');
  await seedNight(f, 'alice', 's40', { status: 'processing' });
  await seedNight(f, 'alice', 's39', { status: null });
  await seedNight(f, 'alice', 's38', { status: 'legacy' });
  await seedNight(f, 'alice', 's37', { chunks: 0, status: null });
  await seedNight(f, 'alice', 's36', { age: 8 * DAY });
  f.fs.set('users/alice/sleep/s10', { startAt: NOW - 30 * DAY, endAt: NOW - 30 * DAY + 7 * HOUR, score: 70 });
  const b = (await f.call('/insights/api/somn?days=14')).body;
  const byId = Object.fromEntries(b.nights.map(n => [n.id, n]));
  assert.deepEqual(Object.keys(byId).sort(), ['s36', 's37', 's38', 's39', 's40', 's41']);
  assert.deepEqual(byId.s41, { id: 's41', startAt: NOW - 10 * HOUR, endAt: NOW - 2 * HOUR, minutes: 480, score: 80, deepMin: 90, lightMin: 250, remMin: 100, snoreMin: 14, talkCount: 2, coverageMin: 480, summary: 'Ai vorbit de două ori.', audio: 'ready' });
  assert.deepEqual([byId.s40.audio, byId.s39.audio, byId.s38.audio, byId.s37.audio, byId.s36.audio], ['pending', 'pending', 'ready', 'none', 'none']);
  assert.equal(f.sleep.lists, 1, 'one R2 listing for the whole list');
  assert.equal((await f.call('/insights/api/somn?days=60')).body.nights.length, 7);
  assert.equal((await f.call('/insights/api/somn?days=abc')).body.nights.length, 6, 'a bad days value falls back to 14');
});
test('somn/<id>: timeline events on the clock, with the chunk offset to play each one', async () => {
  const f = fixture();
  const startAt = await seedNight(f);
  const r = await f.call('/insights/api/somn/s41');
  assert.equal(r.status, 200);
  assert.deepEqual(r.body, {
    id: 's41', summary: 'Ai vorbit de două ori.',
    events: [
      { t: startAt + 600000, kind: 'snore', label: 'Sforăit · puternic', text: null, chunk: 0, offsetMs: 600000, durationMs: 300000 },
      { t: startAt + 1900000, kind: 'talk', label: 'Vorbit', text: 'Nu acum.', chunk: 1, offsetMs: 100000, durationMs: 3000 },
      { t: startAt + 2000000, kind: 'cough', label: 'Tuse · redus', text: null, chunk: 1, offsetMs: 200000, durationMs: 1000 },
    ],
    chunks: [{ i: 0, startAt, durationMs: 1800000 }, { i: 1, startAt: startAt + 1800000, durationMs: 1800000 }],
  });
  assert.equal((await f.call('/insights/api/somn/s99')).status, 404);
  for (const bad of ['x41', 's41x', '..', 's41%2F..']) assert.equal((await f.call('/insights/api/somn/' + bad)).status, 404, bad);
  // Keys come from the verified uid only: bob's night with the same id is never read for alice.
  await seedNight(f, 'bob', 's77');
  f.fs.set('users/alice/sleep/s77', { startAt, endAt: startAt + HOUR, summary: null });
  const mine = (await f.call('/insights/api/somn/s77')).body;
  assert.deepEqual([mine.events, mine.chunks], [[], []]);
  // After 7 days (or without analysis) the journal row stays, without sound.
  const old = fixture();
  await seedNight(old, 'alice', 's41', { age: 8 * DAY });
  assert.deepEqual((await old.call('/insights/api/somn/s41')).body.chunks, []);
});
test('somn/<id>: absolute chunk times (older clients) stay on the clock, offsets inside the chunk stay right', async () => {
  const f = fixture();
  const startAt = NOW - 9 * HOUR, T0 = startAt + 5000;
  f.fs.set('users/alice/sleep/s50', { startAt, endAt: startAt + 8 * HOUR, summary: null });
  await f.sleep.put('alice/s50/chunk_0.m4a', new Uint8Array(1200), { customMetadata: { from: String(T0), dur: String(1800000), at: String(NOW), ttl: String(7 * DAY) } });
  await f.sleep.put('alice/s50/analysis.json', JSON.stringify({ status: 'complete', events: [{ type: 'talk', from: T0 + 60000, to: T0 + 62000, chunk: 0, transcript: 'Da.' }], state: { chunks: [{ index: 0, from: T0, dur: 1800000 }] } }), { customMetadata: { at: String(NOW), status: 'complete' } });
  const b = (await f.call('/insights/api/somn/s50')).body;
  assert.deepEqual(b.chunks, [{ i: 0, startAt: T0, durationMs: 1800000 }]);
  assert.deepEqual(b.events[0], { t: T0 + 60000, kind: 'talk', label: 'Vorbit', text: 'Da.', chunk: 0, offsetMs: 60000, durationMs: 2000 });
});
test('somn chunk: audio with Range (206), suffix ranges, 416 and 404 after 7 days', async () => {
  const f = fixture();
  await seedNight(f);
  let r = await f.call('/insights/api/somn/s41/chunk/1');
  assert.equal(r.status, 200);
  assert.equal(r.res.headers.get('content-type'), 'audio/mp4'); assert.equal(r.res.headers.get('accept-ranges'), 'bytes'); assert.equal(r.res.headers.get('content-length'), '1001');
  assert.equal((await r.res.arrayBuffer()).byteLength, 1001);
  r = await f.call('/insights/api/somn/s41/chunk/1', { headers: { range: 'bytes=10-19' } });
  assert.equal(r.status, 206); assert.equal(r.res.headers.get('content-range'), 'bytes 10-19/1001');
  assert.deepEqual([...new Uint8Array(await r.res.arrayBuffer())], Array(10).fill(2));
  r = await f.call('/insights/api/somn/s41/chunk/0', { headers: { range: 'bytes=-100' } });
  assert.equal(r.status, 206); assert.equal(r.res.headers.get('content-range'), 'bytes 900-999/1000');
  r = await f.call('/insights/api/somn/s41/chunk/0', { headers: { range: 'bytes=5000-' } });
  assert.equal(r.status, 416); assert.equal(r.res.headers.get('content-range'), 'bytes */1000');
  assert.equal((await f.call('/insights/api/somn/s41/chunk/7')).status, 404);
  assert.equal((await f.call('/insights/api/somn/s41/chunk/x')).status, 404);
  assert.equal((await f.call('/insights/api/somn/s41/chunk/0', { uid: 'bob' })).status, 404, 'another account builds another key');
  const old = fixture();
  await seedNight(old, 'alice', 's41', { age: 8 * DAY });
  const gone = await old.call('/insights/api/somn/s41/chunk/0');
  assert.equal(gone.status, 404); assert.match(gone.body.error, /7 zile/);
});

// ── Rație, Marș, Muzică ──
const SIGNED = { version: 3, at: NOW - 3 * DAY };
test('ratie: targets and days newest first with meals in order, source badges kept', async () => {
  const f = fixture();
  const epoch = Math.floor(Date.UTC(2026, 8, 28) / DAY), midnight = localMidnight(NOW);
  f.fs.set('users/alice', { name: 'Lana', contract: SIGNED });
  f.fs.set('users/alice/settings/targets', { kcal: 2000, protein: 110, carbs: 240, fat: 65, updatedAt: NOW });
  // mealType as the app writes it (Entities.kt: an Int, 0 mic dejun · 1 prânz · 2 cină · 3 gustare).
  f.fs.set('users/alice/meals/m3', { name: 'Prânz', kcal: 650, protein: 35, carbs: 60, fat: 20, grams: 450, mealType: 1, source: 'ESTIMAT', confidence: 0.7, epochDay: epoch, at: midnight + 13 * HOUR });
  f.fs.set('users/alice/meals/m2', { name: 'Iaurt', kcal: 150, protein: 10, carbs: 12, fat: 5, grams: 150, mealType: 0, source: 'COD DE BARE', confidence: 1, epochDay: epoch, at: midnight + 8 * HOUR });
  f.fs.set('users/alice/meals/m1', { name: 'Cină', kcal: 700, protein: 40, carbs: 70, fat: 25, grams: 500, mealType: 2, source: 'MANUAL', confidence: 'mare', epochDay: epoch - 1, at: midnight - 3 * HOUR });
  f.fs.set('users/alice/meals/m0', { name: 'Veche', kcal: 100, epochDay: epoch - 40, at: NOW - 40 * DAY });
  const b = (await f.call('/insights/api/ratie?days=30')).body;
  assert.deepEqual(b.targets, { kcal: 2000, protein: 110, carbs: 240, fat: 65 });
  assert.deepEqual(b.days.map(d => [d.date, d.kcal, d.meals.map(m => m.id)]), [['2026-09-28', 800, ['m2', 'm3']], ['2026-09-27', 700, ['m1']]]);
  assert.deepEqual(b.days[0].meals[0], { id: 'm2', at: midnight + 8 * HOUR, name: 'Iaurt', kcal: 150, protein: 10, carbs: 12, fat: 5, grams: 150, mealType: 0, source: 'COD DE BARE', confidence: 1 });
  assert.deepEqual(b.days.flatMap(d => d.meals.map(m => m.mealType)), [0, 1, 2], 'the integer index reaches the site (MIC DEJUN, PRÂNZ, CINĂ)');
  assert.equal(b.days[1].meals[0].confidence, 'mare');
  assert.deepEqual(Object.keys(b.days[0]), ['date', 'kcal', 'protein', 'carbs', 'fat', 'meals']);
  assert.deepEqual((await f.call('/insights/api/ratie?days=1')).body.days.map(d => d.date), ['2026-09-28']);
  const empty = (await f.call('/insights/api/ratie', { uid: 'nou' })).body;
  assert.deepEqual(empty, { targets: null, days: [] });
  // Anything else is not a meal type: no label rather than a wrong one.
  for (const bad of ['lunch', 4, -1, 1.5, null]) {
    f.fs.set('users/alice/meals/m3', { ...f.fs.docs.get('users/alice/meals/m3'), mealType: bad });
    assert.equal((await f.call('/insights/api/ratie?days=1')).body.days[0].meals[1].mealType, null, String(bad));
  }
});
test('mars: activities with mini routes, workouts, and the 7-day totals', async () => {
  const f = fixture();
  const long = Array.from({ length: 900 }, (_, i) => `${44.4 + i * 0.0002},${26.1 + (i % 7) * 0.0003}`).join(';');
  f.fs.set('users/alice/activities/a2', { type: 'run', startAt: NOW - DAY, endAt: NOW - DAY + 1800000, distanceM: 5000, durationS: 1800, kcal: 320.6, polyline: long });
  f.fs.set('users/alice/activities/a1', { type: 'ride', startAt: NOW - 10 * DAY, endAt: NOW - 10 * DAY + HOUR, distanceM: 20000, durationS: 3600, kcal: 500, polyline: '' });
  f.fs.set('users/alice/workouts/w1', { startAt: NOW - 2 * DAY, endAt: NOW - 2 * DAY + 2700000, durationS: 2700, title: 'Piept și spate', kind: 'gym', sets: 14, volumeKg: 4200.5, kcal: null, source: 'instructie' });
  f.fs.set('users/alice', { name: 'Lana', contract: SIGNED });
  const b = (await f.call('/insights/api/mars?days=30')).body;
  assert.deepEqual(b.activities.map(a => a.id), ['a2', 'a1']);
  assert.deepEqual(Object.keys(b.activities[0]), ['id', 'type', 'startAt', 'endAt', 'distanceM', 'durationS', 'kcal', 'polyline']);
  assert.equal(b.activities[0].kcal, 321); assert.equal(b.activities[1].polyline, null);
  assert(b.activities[0].polyline.split(';').length <= SITE_RULES.mini_route_points);
  assert.deepEqual(b.workouts, [{ id: 'w1', startAt: NOW - 2 * DAY, endAt: NOW - 2 * DAY + 2700000, durationS: 2700, title: 'Piept și spate', kind: 'gym', sets: 14, volumeKg: 4200.5, kcal: null }]);
  assert.deepEqual(b.week, { km: 5, minutes: 75, sessions: 2 });
  const polylineReads = f.fs.requests.filter(r => r.url.endsWith(':batchGet') && r.body.mask.fieldPaths.includes('polyline')).length;
  assert.equal(polylineReads, 1, 'without Teren routes, the few missing polylines come in one batchGet');
  assert(f.fs.requests.filter(r => r.body?.structuredQuery?.from?.[0]?.collectionId === 'activities').every(r => !r.body.structuredQuery.select.fields.some(x => x.fieldPath === 'polyline')), 'the list itself never downloads polylines');
  await f.call('/insights/api/cerc');
  const again = (await f.call('/insights/api/mars?days=30')).body;
  assert(again.activities[0].polyline && again.activities[0].polyline.split(';').length <= SITE_RULES.mini_route_points);
  assert.equal(again.activities[1].polyline, null);
  assert.equal(f.fs.requests.filter(r => r.url.endsWith(':batchGet') && r.body.mask.fieldPaths.includes('polyline')).length, polylineReads, 'with Teren routes in the DO, no polyline is read again');
});
test('mars: runs beyond Teren\'s newest 30 get their mini maps over a few opens, each polyline read once; week is always 7 days', async () => {
  const f = fixture();
  for (let i = 0; i < 45; i++) f.fs.set(`users/alice/activities/a${i}`, { type: 'run', startAt: NOW - (i + 1) * 12 * HOUR, endAt: NOW - (i + 1) * 12 * HOUR + 600000, distanceM: 1000, durationS: 600, polyline: `44.4,26.1;44.41,26.1${i % 10};44.42,26.1` });
  f.fs.set('users/alice/workouts/w1', { startAt: NOW - 5 * DAY, endAt: NOW - 5 * DAY + 1800000, durationS: 1800, title: 'Picioare', kind: 'gym', sets: 12 });
  f.fs.set('users/alice', { name: 'Lana', contract: SIGNED });
  await f.call('/insights/api/cerc');
  const reads = () => f.fs.requests.filter(r => r.url.endsWith(':batchGet') && r.body.mask.fieldPaths.join() === 'polyline').map(r => r.body.documents.length);
  let b = (await f.call('/insights/api/mars?days=30')).body;
  assert.equal(b.activities.length, 45);
  assert.equal(b.activities.filter(a => a.polyline).length, 30 + 8, 'Teren\'s 30 + the next 8');
  b = (await f.call('/insights/api/mars?days=30')).body;
  assert.equal(b.activities.filter(a => a.polyline).length, 45, 'the next open reads the rest');
  b = (await f.call('/insights/api/mars?days=30')).body;
  assert.equal(b.activities.filter(a => a.polyline).length, 45);
  assert.deepEqual(reads(), [8, 7], 'every polyline is read once, then kept in the DO');
  const short = (await f.call('/insights/api/mars?days=3')).body;
  assert.deepEqual(short.activities.map(a => a.id), ['a0', 'a1', 'a2', 'a3', 'a4', 'a5']);
  assert.deepEqual(short.workouts, []);
  assert.deepEqual(short.week, b.week, '?days=3 still gets the 7-day totals');
  assert.deepEqual(b.week, { km: 14, minutes: 14 * 10 + 30, sessions: 15 });
});
test('muzica: the live song only while fresh, the weekly top from settings/music', async () => {
  const f = fixture();
  f.fs.set('users/alice', { name: 'Lana', contract: SIGNED, nowPlaying: { title: 'Fetele care ard', artist: 'Trupa', app: 'Spotify', at: NOW - 4 * MIN } });
  f.fs.set('users/alice/settings/music', { updatedAt: NOW - HOUR, windowDays: 7, totalMinutes: 412, top: Array.from({ length: 12 }, (_, i) => ({ title: 'Piesa ' + i, artist: 'Artist', plays: 12 - i, minutes: 40 - i, app: i % 2 ? null : 'Spotify' })) });
  let b = (await f.call('/insights/api/muzica')).body;
  assert.deepEqual(b.now, { title: 'Fetele care ard', artist: 'Trupa', app: 'Spotify', at: NOW - 4 * MIN });
  assert.equal(b.summary.top.length, 10); assert.deepEqual(b.summary.top[1], { title: 'Piesa 1', artist: 'Artist', plays: 11, minutes: 39, app: null });
  assert.deepEqual([b.summary.updatedAt, b.summary.windowDays, b.summary.totalMinutes], [NOW - HOUR, 7, 412]);
  b = (await f.call('/insights/api/muzica', { now: NOW + 7 * MIN })).body;
  assert.equal(b.now, null);
  assert.deepEqual((await f.call('/insights/api/muzica', { uid: 'nou' })).body, { now: null, summary: null });
});

// ── Pază: the daily rollup in the account DO ──
test('usage rollup: cumulative snapshots become per-day minutes, split at local midnight, 14 days kept', async () => {
  const s = new Storage();
  const midnight = localMidnight(NOW);
  const snap = (from, to, apps) => ({ usage_window: { from, to, method: 'activity_events' }, app_usage: apps.map(([pkg, ms, opens, last]) => ({ package: pkg, label: pkg.split('.').pop(), foreground_ms: ms, opens, last_used: last ?? to })) });
  // A session that started at 23:00 yesterday: 40 min of Instagram, the last use at 23:50 → all yesterday.
  const from = midnight - HOUR;
  await applyUsageRollup(s, 'sess', snap(from, midnight + 30 * MIN, [['com.instagram.android', 40 * MIN, 6, midnight - 10 * MIN], ['com.spotify.music', 20 * MIN, 2]]), NOW);
  let d = await usageDays(s, 7, NOW);
  // Spotify was used until 00:30: its 20 min spread over 23:00–00:30 → 13 min yesterday, 7 min today (+ its 2 opens today).
  assert.deepEqual(d.days.map(x => [x.date, x.totalMin]), [['2026-09-28', 7], ['2026-09-27', 53]]);
  assert.deepEqual(d.days[1].apps.map(a => [a.pkg, a.minutes, a.opens]), [['com.instagram.android', 40, 6], ['com.spotify.music', 13, 0]]);
  // Next snapshot: only the difference counts, all of it after midnight.
  await applyUsageRollup(s, 'sess', snap(from, midnight + 90 * MIN, [['com.instagram.android', 55 * MIN, 8], ['com.spotify.music', 20 * MIN, 2]]), NOW);
  d = await usageDays(s, 7, NOW);
  assert.deepEqual(d.days[0].apps.map(a => [a.pkg, a.minutes, a.opens]), [['com.instagram.android', 15, 2], ['com.spotify.music', 7, 2]]);
  assert.equal(d.updated_at, NOW);
  // A retry of an older snapshot changes nothing; another phone (session) adds its own time.
  await applyUsageRollup(s, 'sess', snap(from, midnight + 60 * MIN, [['com.instagram.android', 99 * MIN, 50]]), NOW);
  await applyUsageRollup(s, 'phone2', snap(midnight + 2 * HOUR, midnight + 3 * HOUR, [['com.instagram.android', 10 * MIN, 1]]), NOW);
  d = await usageDays(s, 7, NOW);
  assert.equal(d.days[0].apps[0].minutes, 25);
  // 14 days kept.
  for (let i = 1; i <= 20; i++) await applyUsageRollup(s, 'old' + i, snap(NOW - i * DAY - HOUR, NOW - i * DAY, [['a.b', 10 * MIN, 1]]), NOW);
  const all = [...(await s.list({ prefix: 'usage-day:' })).keys()].sort();
  assert.equal(all.length, 14); assert.equal(all[0], 'usage-day:' + localDate(NOW - 13 * DAY));
  assert.equal((await usageDays(s, 7, NOW)).days.length, 7);
});
test('paza: days older than the window are neither shown nor kept, even when the phone stops uploading', async t => {
  let clock = NOW;
  t.mock.method(Date, 'now', () => clock);
  const f = fixture(), account = f.account('alice'), storage = account.ctx.storage;
  const put = (k, min) => { const date = localDate(NOW - k * DAY); return storage.put('usage-day:' + date, { date, updated_at: NOW - k * DAY, apps: { 'com.x': { label: 'X', ms: min * MIN, opens: 1 } } }); };
  for (const k of [20, 15, 9, 8]) await put(k, 10);
  await put(3, 30);
  const r = await f.call('/insights/api/paza');
  assert.deepEqual(r.body.days.map(x => x.date), [localDate(NOW - 3 * DAY)], 'weeks-old days are never served as "the last 7"');
  assert.equal(r.body.updated_at, NOW - 3 * DAY);
  await account.alarm();
  assert.deepEqual([...(await storage.list({ prefix: 'usage-day:' })).keys()].sort(), [9, 8, 3].map(k => 'usage-day:' + localDate(NOW - k * DAY)), 'the alarm keeps 14 days');
  assert.equal(storage.alarm, localMidnight(NOW + 5 * DAY), 'the next alarm is when the oldest kept day leaves the window');
  clock = NOW + 20 * DAY;
  await account.alarm();
  assert.equal((await storage.list({ prefix: 'usage-day:' })).size, 0, 'no upload for 20 days: nothing left');
  assert.equal(storage.alarm, null);
  assert.deepEqual((await f.call('/insights/api/paza', { now: clock })).body, { updated_at: null, days: [] });
});
test('paza: the session upload feeds the rollup; the site reads the last 7 days', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  const session = randomUUID();
  const consent = { location: false, app_usage: true, files: false, photos: false, audio: false };
  assert.equal((await f.doCall('alice', '/v2/sessions', 'POST', { session_id: session, consent, mode: 'automatic' })).status, 201);
  const from = NOW - 2 * HOUR;
  const post = (to, ms, opens) => f.doCall('alice', `/v2/sessions/${session}/data`, 'POST', { usage_window: { from, to, method: 'activity_events' }, app_usage: [{ package: 'com.instagram.android', label: 'Instagram', foreground_ms: ms, opens, last_used: to - 1000 }] });
  assert.equal((await post(NOW - HOUR, 30 * MIN, 4)).status, 201);
  assert.equal((await post(NOW - 30 * MIN, 45 * MIN, 5)).status, 201);
  const r = await f.call('/insights/api/paza');
  assert.equal(r.status, 200);
  assert.deepEqual(r.body, { updated_at: NOW, days: [{ date: '2026-09-28', totalMin: 45, apps: [{ label: 'Instagram', pkg: 'com.instagram.android', minutes: 45, opens: 5 }] }] });
  assert.deepEqual((await f.call('/insights/api/paza', { uid: 'nou' })).body, { updated_at: null, days: [] });
  // The session delete also forgets its snapshot; the rollup (its own 14-day history) stays.
  assert.equal((await f.doCall('alice', `/v2/sessions/${session}`, 'DELETE')).status, 200);
  assert.equal(await f.account('alice').ctx.storage.get('usage-last:' + session), undefined);
  assert.equal((await f.call('/insights/api/paza')).body.days.length, 1);
});

// ── Inventar, Livret ──
test('inventar: the last 20 runs newest first, and the gallery vault in the account', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  for (let i = 0; i < 22; i++) f.fs.set(`users/alice/inventory/run${i}`, { id: 'run' + i, kind: i % 2 ? 'docs' : 'photos', startedAt: NOW - (i + 1) * DAY, finishedAt: NOW - (i + 1) * DAY + 5 * MIN, appVersion: '4.4',
    scope: { mode: 'last', n: 500, label: 'Ultimele 500' }, dest: { label: 'Galerie · FORJA', path: 'PICTURES/FORJA' }, folders: [{ name: 'Munte', count: 40, bytes: 120000000 }], trash: { count: 3, bytes: 900000 }, moved: 40, failed: 0, freedBytes: null });
  f.fs.set('users/alice', { name: 'Lana', contract: SIGNED });
  const storage = f.account('alice').ctx.storage;
  await f.doCall('alice', '/v2/files'); // binds the owner
  await storage.put('cloud-file:1', { id: '1', received_at: NOW - HOUR, expires_at: NOW + 23 * HOUR, bytes: 10 });
  await storage.put('cloud-file:2', { id: '2', received_at: NOW - 2 * HOUR, expires_at: NOW + 22 * HOUR, bytes: 10 });
  await storage.put('cloud-file:3', { id: '3', received_at: NOW - 30 * HOUR, expires_at: NOW - 6 * HOUR, bytes: 10 });
  const b = (await f.call('/insights/api/inventar')).body;
  assert.equal(b.runs.length, 20);
  assert.equal(b.runs[0].id, 'run0'); assert.equal(b.runs[19].id, 'run19');
  assert.deepEqual(b.runs[0].dest, { label: 'Galerie · FORJA', path: 'PICTURES/FORJA' });
  assert.deepEqual(b.runs[0].folders, [{ name: 'Munte', count: 40, bytes: 120000000 }]);
  assert.equal(b.runs[0].freedBytes, null);
  assert.deepEqual(b.vault, { total: 2, latestAt: NOW - HOUR });
  assert.deepEqual((await f.call('/insights/api/inventar', { uid: 'nou' })).body, { runs: [], vault: { total: 0, latestAt: null } });
});
test('cont: contract, one "last time" per pipe, and the intake pause', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  f.fs.set('users/alice', { name: 'Lana', email: 'alt@example.com', contract: { version: 3, at: NOW - 3 * DAY, revokedAt: null } });
  f.fs.set('users/alice/sleep/s5', { startAt: NOW - 12 * HOUR, endAt: NOW - 4 * HOUR });
  f.fs.set('users/alice/meals/m5', { at: NOW - 2 * HOUR, kcal: 100 });
  f.fs.set('users/alice/activities/a5', { startAt: NOW - DAY, endAt: NOW - DAY + HOUR });
  f.fs.set('users/alice/workouts/w5', { startAt: NOW - 3 * HOUR, endAt: NOW - 2.5 * HOUR });
  f.fs.set('users/alice/settings/music', { updatedAt: NOW - 5 * HOUR, top: [] });
  f.fs.set('users/alice/inventory/r5', { finishedAt: NOW - 6 * HOUR });
  await f.doCall('alice', '/v2/files');
  const intake = await (await f.env.INSIGHTS.get('account:alice').fetch(new Request('https://internal/internal/intake', { headers: { 'x-forja-owner': 'alice' } }))).json();
  await f.env.INSIGHTS.get('account:alice').fetch(new Request('https://internal/internal/intake', { method: 'POST', headers: { 'x-forja-owner': 'alice', 'content-type': 'application/json' }, body: JSON.stringify({ accepting: false, revision: intake.revision }) }));
  await f.socialCall('alice', 'explore/sync', 'POST', { device: randomUUID(), places: [{ id: 'p1', lat: 44.4, lng: 26.1, updated_at: NOW - HOUR }] });
  const b = (await f.call('/insights/api/cont')).body;
  assert.deepEqual(Object.keys(b), ['me', 'contract', 'pipes', 'intake']);
  assert.deepEqual(b.me, { uid: 'alice', name: 'Lana', email: 'alice@example.com' }, 'the email comes from the token, not from the public profile');
  assert.deepEqual(b.contract, { version: 3, at: NOW - 3 * DAY, revokedAt: null, current: 3 });
  assert.deepEqual(b.pipes.map(p => p.key), ['sesiune', 'galerie', 'explorare', 'agenda', 'somn', 'gasire', 'mese', 'miscare', 'muzica', 'inventar']);
  const P = Object.fromEntries(b.pipes.map(p => [p.key, p.lastAt]));
  assert.deepEqual(P, { sesiune: null, galerie: null, explorare: NOW, agenda: null, somn: NOW - 4 * HOUR, gasire: null, mese: NOW - 2 * HOUR, miscare: NOW - 2.5 * HOUR, muzica: NOW - 5 * HOUR, inventar: NOW - 6 * HOUR });
  assert.deepEqual(b.intake, { paused: true });
  assert(f.fs.reads <= 7, 'Livret costs at most 7 reads: ' + f.fs.reads);
});

test('contract: a re-signature after a revoke is signed everywhere (Livret and Azi agree); a later revoke is revoked', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  // signContract writes {version, at} with merge: the old revokedAt stays in the document.
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 3, at: NOW - DAY, revokedAt: NOW - 5 * DAY } });
  assert.deepEqual((await f.call('/insights/api/cont')).body.contract, { version: 3, at: NOW - DAY, revokedAt: null, current: 3 });
  assert.equal((await f.call('/insights/api/azi')).body.links.at(-1).state, 'on');
  resetSiteCache();
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 3, at: NOW - 5 * DAY, revokedAt: NOW - DAY } });
  assert.deepEqual((await f.call('/insights/api/cont')).body.contract, { version: 3, at: NOW - 5 * DAY, revokedAt: NOW - DAY, current: 3 });
  assert.equal((await f.call('/insights/api/azi')).body.links.at(-1).state, 'off');
});

test('contract gate: targets, workouts, the music top and Inventar runs only while contract v3 is signed; journals always', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  const midnight = localMidnight(NOW);
  f.fs.set('users/alice/settings/targets', { kcal: 2000, protein: 110, carbs: 240, fat: 65, updatedAt: NOW });
  f.fs.set('users/alice/meals/m1', { name: 'Iaurt', kcal: 150, mealType: 0, at: midnight + 8 * HOUR });
  f.fs.set('users/alice/activities/a1', { type: 'run', startAt: NOW - DAY, endAt: NOW - DAY + 1800000, distanceM: 5000, durationS: 1800, polyline: '' });
  f.fs.set('users/alice/workouts/w1', { startAt: NOW - 2 * DAY, endAt: NOW - 2 * DAY + 2700000, durationS: 2700, title: 'Forță', kind: 'forta', sets: 14 });
  f.fs.set('users/alice/settings/music', { updatedAt: NOW - HOUR, windowDays: 7, totalMinutes: 60, top: [{ title: 'Piesa', artist: 'Artist', plays: 3, minutes: 10 }] });
  f.fs.set('users/alice/inventory/r1', { id: 'r1', kind: 'photos', startedAt: NOW - DAY, finishedAt: NOW - DAY + MIN });
  const read = async () => {
    resetSiteCache();
    const [ratie, mars, muzica, inventar, azi] = await Promise.all(['ratie', 'mars', 'muzica', 'inventar', 'azi'].map(s => f.call('/insights/api/' + s).then(r => r.body)));
    const L = Object.fromEntries(azi.links.map(l => [l.key, l]));
    return { targets: !!ratie.targets, meals: ratie.days.length, workouts: mars.workouts.length, activities: mars.activities.length, top: !!muzica.summary, runs: inventar.runs.length,
      kcalTarget: azi.today.kcalTarget, aziWorkouts: L.mars.count, inventarLink: L.inventar.state, muzicaLink: L.muzica.state };
  };
  const shown = { targets: true, meals: 1, workouts: 1, activities: 1, top: true, runs: 1, kcalTarget: 2000, aziWorkouts: 2, inventarLink: 'on', muzicaLink: 'on' };
  const hidden = { targets: false, meals: 1, workouts: 0, activities: 1, top: false, runs: 0, kcalTarget: null, aziWorkouts: 1, inventarLink: 'off', muzicaLink: 'off' };
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 3, at: NOW - 3 * DAY } });
  assert.deepEqual(await read(), shown, 'signed v3');
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 3, at: NOW - 3 * DAY, revokedAt: NOW - HOUR } });
  assert.deepEqual(await read(), hidden, 'revoked: the documents may still be in Firestore, the site does not show them');
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 2, at: NOW - 3 * DAY } });
  assert.deepEqual(await read(), hidden, 'v2, not re-signed');
  f.fs.set('users/alice', { name: 'Lana' });
  assert.deepEqual(await read(), hidden, 'never signed');
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 3, at: NOW - HOUR, revokedAt: NOW - 3 * DAY } });
  assert.deepEqual(await read(), shown, 'signed again after an old revoke');
});

test('errors: only GET, unknown sub-paths 404, Firestore down 503, always JSON with no-store', async () => {
  const f = fixture();
  let r = await f.call('/insights/api/azi', { method: 'POST' });
  assert.equal(r.status, 405); assert.equal(typeof r.body.error, 'string');
  for (const p of ['/insights/api/azi/x', '/insights/api/cerc/1', '/insights/api/somn/s1/chunk', '/insights/api/somn/s1/audio/0']) assert.equal((await f.call(p)).status, 404, p);
  f.fs.down = true;
  for (const p of ['azi', 'ratie', 'mars', 'muzica', 'inventar', 'cont', 'somn']) {
    r = await f.call('/insights/api/' + p);
    assert.equal(r.status, 503, p); assert.equal(r.res.headers.get('cache-control'), 'no-store'); assert.match(r.body.error, /[ăâîșț]/, 'Romanian message');
  }
  assert.equal((await f.call('/insights/api/paza')).status, 200, 'Pază needs no Firestore');
  for (const text of [JSON.stringify(r.body)]) assert(!/!/.test(text));
});

test('helpers: initials and polyline simplification', () => {
  assert.equal(initials('Lana Popescu'), 'LP'); assert.equal(initials('  ștefan  '), 'Ș'); assert.equal(initials(''), '?'); assert.equal(initials('Ana Maria Ionescu'), 'AM');
  assert.equal(simplifyPolyline('', 10), null); assert.equal(simplifyPolyline('44.4,26.1', 10), null); assert.equal(simplifyPolyline('x,y;1,2', 10), null);
  const straight = Array.from({ length: 500 }, (_, i) => `${44 + i * 0.0001},26`).join(';');
  assert.equal(simplifyPolyline(straight, 100).split(';').length, 2, 'a straight street keeps its two ends');
  const zigzag = Array.from({ length: 500 }, (_, i) => `${44 + i * 0.0001},${26 + (i % 2) * 0.001}`).join(';');
  assert.equal(simplifyPolyline(zigzag, 100).split(';').length, 100);
});

test('batchGet splits more than 10 documents into batches of 10 (the new rules allow 20 exists() per request)', async () => {
  const sizes = [];
  const fetcher = async (url, init) => {
    const body = JSON.parse(init.body);
    sizes.push(body.documents.length);
    return new Response(JSON.stringify(body.documents.map(name => ({ found: { name, fields: { name: { stringValue: name.split('/').pop() } } } }))), { status: 200 });
  };
  const fs = new FirestoreReader('me', 'tok', fetcher);
  const paths = Array.from({ length: 25 }, (_, i) => `users/f${i}`);
  const out = await fs.batchGet(paths, ['name']);
  assert.deepEqual(sizes, [10, 10, 5]);
  assert.equal(out.size, 25);
  assert.equal(out.get('users/f24').name, 'f24');
});
