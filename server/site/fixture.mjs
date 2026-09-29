// Dublurile comune testelor site-ului (site-api.test.mjs și site/sec-*.test.mjs): un emulator Firestore REST, DO și R2,
// ceasul fix și conturile de start. Nu e un fișier de test.
import { handleSiteApi } from '../site-api.mjs';
import { InsightsAccount } from '../insights-store.mjs';
import { SocialGraph } from '../social.mjs';
import { firestoreValue } from '../insights-ai.mjs';

// ── a small Firestore REST emulator: documents, runQuery (filters, orderBy, limit, select), batchGet, masks, read counting ──
const DOCS = 'projects/forja-65093/databases/(default)/documents';
const BASE = 'https://firestore.googleapis.com/v1/' + DOCS;
export function encode(v) {
  if (v === null || v === undefined) return { nullValue: null };
  if (typeof v === 'string') return { stringValue: v };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  if (Array.isArray(v)) return { arrayValue: v.length ? { values: v.map(encode) } : {} };
  return { mapValue: { fields: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, encode(x)])) } };
}
export class FakeFirestore {
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
export class Storage {
  m = new Map(); alarm = null;
  async get(k) { return structuredClone(this.m.get(k)); }
  async put(k, v) { if (typeof k === 'object') { for (const [a, b] of Object.entries(k)) this.m.set(a, structuredClone(b)); } else this.m.set(k, structuredClone(v)); }
  async delete(k) { for (const key of Array.isArray(k) ? k : [k]) this.m.delete(key); }
  async list({ prefix = '', startAfter = '', limit = 1e9 } = {}) { return new Map([...this.m].filter(([k]) => k.startsWith(prefix) && k > startAfter).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)).slice(0, limit).map(([k, v]) => [k, structuredClone(v)])); }
  async transaction(fn) { return fn(this); }
  async getAlarm() { return this.alarm; } async setAlarm(v) { this.alarm = v; } async deleteAlarm() { this.alarm = null; }
}
export class Bucket {
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
export function queue() { let q = Promise.resolve(); return fn => { const job = q.then(fn); q = job.catch(() => {}); return job; }; }
export function fixture() {
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
  return { fs, sleep, records, env, call, doCall, socialCall, account, doRequests: () => doRequests };
}
// 28 Sept 2026, 15:00 in Bucharest (12:00 UTC).
export const NOW = Date.UTC(2026, 8, 28, 12, 0);
export const MIN = 60000, HOUR = 3600000, DAY = 86400000;

export function seedCircle(fs, n = 3) {
  fs.set('users/alice', { name: 'Lana Popescu', email: 'alice@example.com', lat: 44.43, lng: 26.1, locUpdatedAt: NOW - 2 * MIN, state: 'walk', ghostUntil: 0, inviteCode: 'K7Q2', exploreCells: 89,
    nowPlaying: { title: 'Fetele care ard', artist: 'Trupa', app: 'Spotify', at: NOW - 3 * MIN }, familyUids: ['bob'], contract: { version: 3, at: NOW - DAY } });
  const friends = ['bob', 'carol', 'dan', ...Array.from({ length: Math.max(0, n - 3) }, (_, i) => 'f' + i)].slice(0, n);
  for (const f of friends) {
    fs.set(`friendships/${['alice', f].sort().join('_')}`, { members: ['alice', f].sort(), since: NOW - 30 * DAY });
    fs.set(`users/${f}`, { name: f === 'bob' ? 'Bogdan Ionescu' : f === 'carol' ? 'Carla' : 'Dan ' + f, email: f + '@secret.example', lat: 44.44, lng: 26.11, locUpdatedAt: NOW - 5 * MIN, state: 'run', ghostUntil: 0, exploreCells: 12, inviteCode: 'SECRET' + f });
  }
  return friends;
}

export const SIGNED = { version: 3, at: NOW - 3 * DAY };
