// Secțiunea „somn” — mutată din site-api.test.mjs fără schimbări.
import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { resetSiteCache, SITE_RULES } from '../site-api.mjs';
import { applyUsageRollup, usageDays } from '../site-store.mjs';
import { localDate, localMidnight } from '../site-time.mjs';
import { fixture, seedCircle, Storage, NOW, MIN, HOUR, DAY, SIGNED } from './fixture.mjs';

test.beforeEach(() => resetSiteCache());

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
