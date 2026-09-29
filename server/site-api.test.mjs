import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { isSiteApi, resetSiteCache, simplifyPolyline, initials, FirestoreReader } from './site-api.mjs';
import { firestoreValue } from './insights-ai.mjs';
import { fixture, NOW } from './site/fixture.mjs';

// Ruterul și ce e comun. Fiecare secțiune are testele ei în site/sec-*.test.mjs (aceleași dubluri, din site/fixture.mjs).
test.beforeEach(() => resetSiteCache());

test('route guard: only the ten site sections go to site-api; the worker wires it and reports /health v18', async () => {
  for (const p of ['/insights/api/azi', '/insights/api/cerc', '/insights/api/somn', '/insights/api/somn/s12', '/insights/api/somn/s12/chunk/0', '/insights/api/ratie', '/insights/api/mars', '/insights/api/muzica', '/insights/api/paza', '/insights/api/inventar', '/insights/api/concentrare', '/insights/api/cont'])
    assert.equal(isSiteApi(p), true, p);
  for (const p of ['/insights/api/state', '/insights/api/intake', '/insights/api/phones', '/insights/api/recommendations', '/insights/api/azimut', '/v2/files']) assert.equal(isSiteApi(p), false, p);
  const src = await readFile(new URL('./insights-worker.mjs', import.meta.url), 'utf8');
  assert.match(src, /import \{ handleSiteApi, isSiteApi \} from '\.\/site-api\.mjs';/);
  assert(src.indexOf('isSiteApi(path)') > 0 && src.indexOf('isSiteApi(path)') < src.indexOf("if (path.startsWith('/insights/api/')) return await handleInsights"), 'site sections are routed before the older insights handler');
  const health = /path === '\/health'\) return reply\((\{[^}]+\})\)/.exec(src)[1];
  const flags = Function('return ' + health)();
  assert.deepEqual(flags, { ok: true, service: 'forja-insights', version: 19, mirror: 1, organizer_jobs: 4, journey: 1, explore_sync: 2, map3d: 1, content_ai: 2, visual_ui: 1, sleep_audio: 1, lost_phone: 2, partners: 1, contacts: 2, social: 1,
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

test('errors: only GET, unknown sub-paths 404, Firestore down 503, always JSON with no-store', async () => {
  const f = fixture();
  let r = await f.call('/insights/api/azi', { method: 'POST' });
  assert.equal(r.status, 405); assert.equal(typeof r.body.error, 'string');
  for (const p of ['/insights/api/azi/x', '/insights/api/cerc/1', '/insights/api/somn/s1/chunk', '/insights/api/somn/s1/audio/0']) assert.equal((await f.call(p)).status, 404, p);
  f.fs.down = true;
  for (const p of ['azi', 'ratie', 'mars', 'muzica', 'inventar', 'cont', 'somn', 'concentrare']) {
    r = await f.call('/insights/api/' + p);
    assert.equal(r.status, 503, p); assert.equal(r.res.headers.get('cache-control'), 'no-store'); assert.match(r.body.error, /[ăâîșț]/, 'Romanian message');
  }
  assert.equal((await f.call('/insights/api/paza')).status, 200, 'Pază is served from the DO when Firestore is down');
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

// 30.09: în Workers, `fetch` apelat ca metodă a altui obiect aruncă „Illegal invocation” — toate secțiunile dădeau 503.
test('FirestoreReader calls the global fetch without rebinding this', async () => {
  const { FirestoreReader } = await import('./site-api.mjs');
  const real = globalThis.fetch;
  globalThis.fetch = function (url) {
    if (this !== undefined && this !== globalThis) throw new TypeError('Illegal invocation');
    return Promise.resolve(new Response(JSON.stringify({ name: 'projects/p/databases/(default)/documents/users/u', fields: {} }), { status: 200 }));
  };
  try {
    const fs = new FirestoreReader('u', 't');
    await fs.get('users/u', ['a']);
    assert.equal(fs.unreachable, false);
  } finally { globalThis.fetch = real; }
});
