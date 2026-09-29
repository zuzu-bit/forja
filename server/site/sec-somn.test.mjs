// Secțiunea „somn”: nopțile, cronologia, sunetul (mutate din site-api.test.mjs) și oglinda nopții (pachetul B).
import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { resetSiteCache, SITE_RULES } from '../site-api.mjs';
import { applyUsageRollup, usageDays } from '../site-store.mjs';
import { localDate, localMidnight } from '../site-time.mjs';
import { fixture, seedCircle, Storage, NOW, MIN, HOUR, DAY, SIGNED } from './fixture.mjs';
import { somnSigned, signedChunkUrl, phasesOf, AUDIO_URL_MS } from './sec-somn.mjs';

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
  assert.deepEqual(byId.s41, { id: 's41', startAt: NOW - 10 * HOUR, endAt: NOW - 2 * HOUR, minutes: 480, score: 80, deepMin: 90, lightMin: 250, remMin: 100, snoreMin: 14, talkCount: 2, coverageMin: 480, summary: 'Ai vorbit de două ori.', audio: 'ready',
    state: 'done', movements: null, snoreEvents: null, soundEvents: null, alarm: null, sounds: [], bedtime: null, upload: null, audioExpiresAt: NOW + 7 * DAY });
  assert.deepEqual([byId.s40.audio, byId.s39.audio, byId.s38.audio, byId.s37.audio, byId.s36.audio], ['pending', 'pending', 'ready', 'none', 'none']);
  assert.equal(f.sleep.lists, 1, 'one R2 listing for the whole list');
  assert.equal(b.timeline, false, 'no contract, no timeline');
  assert.equal(b.live, null);
  assert.equal((await f.call('/insights/api/somn?days=60')).body.nights.length, 7);
  assert.equal((await f.call('/insights/api/somn?days=abc')).body.nights.length, 6, 'a bad days value falls back to 14');
});
test('somn/<id>: timeline events on the clock, with the chunk offset to play each one', async () => {
  const f = fixture();
  const startAt = await seedNight(f);
  const r = await f.call('/insights/api/somn/s41');
  assert.equal(r.status, 200);
  assert.deepEqual({ id: r.body.id, summary: r.body.summary, events: r.body.events, chunks: r.body.chunks }, {
    id: 's41', summary: 'Ai vorbit de două ori.',
    events: [
      { t: startAt + 600000, kind: 'snore', label: 'Sforăit · puternic', text: null, chunk: 0, offsetMs: 600000, durationMs: 300000, source: 'server' },
      { t: startAt + 1900000, kind: 'talk', label: 'Vorbit', text: 'Nu acum.', chunk: 1, offsetMs: 100000, durationMs: 3000, source: 'server' },
      { t: startAt + 2000000, kind: 'cough', label: 'Tuse · redus', text: null, chunk: 1, offsetMs: 200000, durationMs: 1000, source: 'server' },
    ],
    chunks: [{ i: 0, startAt, durationMs: 1800000 }, { i: 1, startAt: startAt + 1800000, durationMs: 1800000 }],
  });
  assert.equal(r.body.urlExpiresAt, null, 'without SLEEP_URL_KEY there are no signed URLs');
  assert.deepEqual([r.body.state, r.body.audioStartAt, r.body.timeline, r.body.phases, r.body.sleep], ['done', startAt, false, [], null]);
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
  assert.deepEqual(b.events[0], { t: T0 + 60000, kind: 'talk', label: 'Vorbit', text: 'Da.', chunk: 0, offsetMs: 60000, durationMs: 2000, source: 'server' });
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

// ── Oglinda nopții (pachetul B) ──
const V4 = { version: 4, at: NOW - 2 * DAY };
test('somn: the night in progress, an interrupted one, upload waiting for Wi-Fi and a sound that expired', async () => {
  const f = fixture();
  f.fs.set('users/alice', { name: 'Lana', contract: V4 });
  f.fs.set('users/alice/sleep/s60', { startAt: NOW - 3 * HOUR, endAt: 0, state: 'recording', alarm: { target: NOW + 4 * HOUR, windowMin: 30, snoozes: 0 }, sounds: [{ sound: 'rain', minutes: 20 }] });
  f.fs.set('users/alice/sleep/s59', { startAt: NOW - 2 * DAY, endAt: 0, state: 'recording' });
  f.fs.set('users/alice/sleep/s58', { startAt: NOW - 3 * DAY, endAt: NOW - 3 * DAY + 7 * HOUR, state: 'interrupted', score: 0 });
  f.fs.set('users/alice/sleep/s57', { startAt: NOW - 1.5 * DAY, endAt: NOW - 1.5 * DAY + 8 * HOUR, state: 'done',
    audio: { chunks: 16, uploaded: 3, state: 'waiting_wifi', audioStartAt: NOW - 1.5 * DAY + 5000 },
    alarm: { target: NOW - 1.5 * DAY + 8 * HOUR, windowMin: 30, firedAt: NOW - 1.5 * DAY + 7.5 * HOUR, reason: 'cycle', snoozes: 1 },
    sounds: [{ sound: 'rain', minutes: 30 }, { sound: 'lava', minutes: 9 }, { sound: 'fire', minutes: 0 }], bedtime: { minute: 23 * 60, reminder: true },
    latencyMin: 14, awakenings: 3, awakeMin: 12, scoreLines: [{ delta: 84, reason: '7 h 10 dormite' }, { delta: -8, reason: '3 treziri' }, { reason: 'fără delta' }] });
  // bucățile care n-au urcat în 3 zile s-au șters de pe telefon; bucățile urcate, cu analiza oprită, au expirat din R2
  f.fs.set('users/alice/sleep/s56', { startAt: NOW - 5 * DAY, endAt: NOW - 5 * DAY + 8 * HOUR, audio: { chunks: 16, uploaded: 0, state: 'waiting_wifi' } });
  f.fs.set('users/alice/sleep/s55', { startAt: NOW - 9 * DAY, endAt: NOW - 9 * DAY + 8 * HOUR, audio: { chunks: 16, uploaded: 16, state: 'failed', lastError: 'serverul nu a mai avansat în 20 de minute' } });
  f.fs.set('users/alice/sleep/s54', { startAt: NOW - 10 * DAY, endAt: NOW - 10 * DAY + 8 * HOUR, audio: { chunks: 16, uploaded: 0, state: 'failed' } });
  f.fs.set('users/alice/sleep/s50', { startAt: NOW - 12 * DAY, endAt: NOW - 12 * DAY + 8 * HOUR, audio: { chunks: 16, uploaded: 16, state: 'done', recordedUntil: NOW - 5 * DAY } });
  const b = (await f.call('/insights/api/somn?days=14')).body;
  assert.deepEqual(b.live, { id: 's60', startAt: NOW - 3 * HOUR, alarm: { target: NOW + 4 * HOUR, windowMin: 30, firedAt: null, reason: null, snoozes: 0 }, sounds: [{ sound: 'rain', minutes: 20 }] });
  const byId = Object.fromEntries(b.nights.map(n => [n.id, n]));
  assert.ok(!byId.s60, 'the live night is not a finished night');
  assert.equal(byId.s59.state, 'interrupted', 'a recording older than 16 h was never closed');
  assert.equal(byId.s58.state, 'interrupted');
  assert.equal(byId.s57.audio, 'waiting', 'waiting for Wi-Fi is not "no recording"');
  assert.deepEqual(byId.s57.upload, { state: 'waiting_wifi', chunks: 16, uploaded: 3, lastError: null, audioStartAt: NOW - 1.5 * DAY + 5000, recordedUntil: null });
  assert.deepEqual(byId.s57.alarm, { target: NOW - 1.5 * DAY + 8 * HOUR, windowMin: 30, firedAt: NOW - 1.5 * DAY + 7.5 * HOUR, reason: 'cycle', snoozes: 1 });
  assert.deepEqual(byId.s57.sounds, [{ sound: 'rain', minutes: 30 }], 'unknown sounds and zero minutes are dropped');
  assert.deepEqual(byId.s57.bedtime, { minute: 1380, reminder: true });
  assert.equal(byId.s50.audio, 'expired');
  assert.equal(byId.s56.audio, 'failed', 'waiting for 5 days: the phone deleted the chunks');
  assert.equal(byId.s55.audio, 'expired', 'uploaded, then R2 expired: not "no upload"');
  assert.equal(byId.s54.audio, 'failed');
  assert.equal(b.timeline, true);
  // the last finished night carries the staging numbers from the journal, with or without v4
  const last = b.nights.reduce((m, n) => (n.startAt > m.startAt ? n : m));
  assert.equal(last.id, 's57');
  assert.deepEqual(last.sleep, { latencyMin: 14, awakenings: 3, awakeMin: 12, scoreLines: [{ delta: 84, reason: '7 h 10 dormite' }, { delta: -8, reason: '3 treziri' }] });
  assert.equal(byId.s58.sleep, undefined);
  // v3 (or revoked): the journal stays (with the staging numbers), the timeline does not.
  for (const contract of [{ version: 3, at: NOW - DAY }, { version: 4, at: NOW - 3 * DAY, revokedAt: NOW - DAY }, undefined]) {
    const g = fixture();
    for (const [k, v] of f.fs.docs) g.fs.set(k, v);
    g.fs.set('users/alice', { name: 'Lana', ...(contract ? { contract } : {}) });
    const gb = (await g.call('/insights/api/somn')).body;
    assert.equal(gb.timeline, false);
    assert.deepEqual(gb.nights.find(n => n.id === 's57').sleep, last.sleep);
    assert.ok(!g.fs.requests.some(r => r.url.includes('sleepEvents')), 'no timeline read without v4');
  }
});
test('somn/<id>: audioStartAt is the clock base, phases become a hypnogram, analysis stats and limits come through', async () => {
  const f = fixture();
  f.fs.set('users/alice', { contract: V4 });
  const startAt = NOW - 10 * HOUR, audioStart = startAt + 40 * MIN;
  f.fs.set('users/alice/sleep/s70', { startAt, endAt: startAt + 8 * HOUR, state: 'done', summary: 'Liniște.', audio: { chunks: 2, uploaded: 2, state: 'done', audioStartAt: audioStart },
    phases: '0,14,awake;14,90,light;90,130,deep;bad;130,120,rem;130,160,dream', latencyMin: 14, awakenings: 1, awakeMin: 3, scoreLines: [{ delta: 90, reason: '7 h 40 dormite' }] });
  f.fs.set('users/alice/sleepEvents/s70', { talkSummary: 'Ai spus două cuvinte.',
    events: [{ kind: 'snore', at: audioStart + 31 * MIN, dur: 12000, intensity: 0.85, source: 'phone', pid: 4 }, { kind: 'talk', at: audioStart + 70 * MIN, dur: 3000, text: 'Păstrat', source: 'server' },
      { kind: 'talk', at: audioStart + 5 * MIN + 1000, dur: 5000, intensity: 0.3, source: 'phone', pid: 5 }] });
  for (let i = 0; i < 2; i++) await f.sleep.put(`alice/s70/chunk_${i}.m4a`, new Uint8Array(1000), { customMetadata: { from: String(i * 30 * MIN), dur: String(30 * MIN), at: String(NOW), ttl: String(7 * DAY) } });
  await f.sleep.put('alice/s70/analysis.json', JSON.stringify({ status: 'complete', progress: { done: 2, failed: 0, total: 2 },
    events: [{ type: 'talk', from: 5 * MIN, to: 5 * MIN + 2000, chunk: 0, transcript: 'Da.' }],
    stats: { snoreMinutes: 4, snoreEpisodes: 2, coughs: 1, noises: 0, longestSnore: { from: 40 * MIN, to: 43 * MIN, minutes: 3 } },
    coverage: { analyzedMs: 60 * MIN, totalMs: 480 * MIN }, limitari: ['Fără cheie Gemini: doar transcriere Whisper cu timpi; sforăitul nu poate fi detectat din chunk-uri.'], sources: ['groq/whisper'] }),
    { customMetadata: { at: String(NOW), status: 'complete' } });
  const b = (await f.call('/insights/api/somn/s70')).body;
  assert.equal(b.audioStartAt, audioStart);
  assert.deepEqual(b.chunks.map(c => c.startAt), [audioStart, audioStart + 30 * MIN], 'chunks on the audio clock, not the vigil start');
  assert.deepEqual(b.events.map(e => [e.t, e.source, e.chunk, e.offsetMs]), [[audioStart + 5 * MIN, 'server', 0, 5 * MIN], [audioStart + 31 * MIN, 'phone', 1, MIN]],
    'phone moments merge in and find their chunk; kept server moments stay out while R2 has the analysis');
  assert.equal(b.events[0].phoneClip, true, 'the phone clip of the same moment is folded into the server moment, not listed twice');
  assert.deepEqual(b.phases, [{ from: startAt, to: startAt + 14 * MIN, stage: 'awake' }, { from: startAt + 14 * MIN, to: startAt + 90 * MIN, stage: 'light' }, { from: startAt + 90 * MIN, to: startAt + 130 * MIN, stage: 'deep' }]);
  assert.deepEqual(b.sleep, { latencyMin: 14, awakenings: 1, awakeMin: 3, scoreLines: [{ delta: 90, reason: '7 h 40 dormite' }] });
  assert.equal(b.talkSummary, 'Ai spus două cuvinte.');
  assert.deepEqual(b.analysis, { status: 'complete', fallback: false, progress: { done: 2, failed: 0, total: 2 }, coverage: { analyzedMin: 60, totalMin: 480 },
    stats: { snoreMin: 4, snoreEpisodes: 2, coughs: 1, noises: 0, longestSnore: { t: audioStart + 40 * MIN, minutes: 3 } },
    limits: ['Fără cheie Gemini: doar transcriere Whisper cu timpi; sforăitul nu poate fi detectat din chunk-uri.'], sources: ['groq/whisper'] });
  assert.equal(b.audioExpiresAt, NOW + 7 * DAY);
  // v3: the hypnogram and the score lines stay (journal); the kept timeline and the talk summary do not
  f.fs.set('users/alice', { contract: { version: 3, at: NOW - DAY } });
  const v3 = (await f.call('/insights/api/somn/s70')).body;
  assert.deepEqual([v3.timeline, v3.phases.length, v3.sleep, v3.talkSummary], [false, 3, b.sleep, null]);
  assert.deepEqual(v3.events.map(e => e.source), ['server'], 'without v4 the phone moments are not read');
});
test('somn/<id>: after the 7 days the kept timeline (v4) stays readable, without sound; revoked hides it', async () => {
  const f = fixture();
  f.fs.set('users/alice', { contract: V4 });
  const startAt = NOW - 9 * DAY;
  f.fs.set('users/alice/sleep/s80', { startAt, endAt: startAt + 8 * HOUR, audio: { chunks: 16, uploaded: 16, state: 'done', audioStartAt: startAt, recordedUntil: startAt + 7 * DAY } });
  f.fs.set('users/alice/sleepEvents/s80', { events: [{ kind: 'talk', at: startAt + HOUR, dur: 2000, text: 'Mâine.', source: 'server' }, { kind: 'whistle', at: startAt + 2 * HOUR, dur: 0, intensity: 0.2, source: 'phone' }],
    limits: ['2 chunk-uri n-au putut fi analizate.'], stats: { snoreEpisodes: 3, coughCount: 1, coverageMin: 400, totalMin: 480 }, analysis: 'done' });
  const b = (await f.call('/insights/api/somn/s80')).body;
  assert.deepEqual(b.chunks, []);
  assert.deepEqual(b.events.map(e => [e.kind, e.text, e.chunk, e.source, e.label]), [['talk', 'Mâine.', null, 'server', 'Vorbit'], ['noise', null, null, 'phone', 'Zgomot · redus']]);
  assert.equal(b.analysis.fallback, true);
  assert.deepEqual(b.analysis.coverage, { analyzedMin: 400, totalMin: 480 });
  assert.deepEqual(b.analysis.limits, ['2 chunk-uri n-au putut fi analizate.']);
  f.fs.set('users/alice', { contract: { ...V4, revokedAt: NOW - HOUR } });
  const r = (await f.call('/insights/api/somn/s80')).body;
  assert.deepEqual([r.events, r.timeline, r.analysis, r.phases], [[], false, null, []]);
});
test('somn audio: signed URLs play with Range, and are bound to the account, the night, the chunk and 10 minutes', async () => {
  const f = fixture();
  f.env.SLEEP_URL_KEY = 'k'.repeat(40);
  await seedNight(f);
  const b = (await f.call('/insights/api/somn/s41')).body;
  assert.equal(b.urlExpiresAt, NOW + AUDIO_URL_MS);
  const url = b.chunks[1].url;
  assert.match(url, /^\/insights\/audio\/somn\/alice\/s41\/1\?exp=\d+&sig=[A-Za-z0-9_-]{43}$/);
  const get = (u, { now = NOW, headers = {}, method = 'GET', env = f.env } = {}) => somnSigned(new Request('https://forja.test' + u, { method, headers }), env, now);
  let r = await get(url, { headers: { range: 'bytes=10-19' } });
  assert.equal(r.status, 206); assert.equal(r.headers.get('content-range'), 'bytes 10-19/1001');
  assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], Array(10).fill(2));
  r = await get(url, { method: 'HEAD' });
  assert.equal(r.status, 200); assert.equal(r.headers.get('content-length'), '1001'); assert.equal(r.body, null);
  assert.equal((await get(url, { now: NOW + AUDIO_URL_MS + 1 })).status, 403, 'expired');
  assert.equal((await get(url.replace('/alice/', '/bob/'))).status, 403, 'another account');
  assert.equal((await get(url.replace('/s41/1', '/s41/0'))).status, 403, 'another chunk');
  assert.equal((await get(url.replace('/s41/', '/s40/'))).status, 403, 'another night');
  assert.equal((await get(url.replace(/exp=\d+/, 'exp=' + (NOW + AUDIO_URL_MS + 5)))).status, 403, 'a changed expiry breaks the signature');
  assert.equal((await get(url.replace(/sig=[^&]+/, 'sig=AAAA'))).status, 403);
  assert.equal((await get(url, { method: 'POST' })).status, 405);
  assert.equal((await get(url, { env: { SLEEP: f.sleep } })).status, 404, 'no key, no signed audio');
  assert.equal((await get('/insights/audio/somn/alice/s41/x?exp=1&sig=a')).status, 404);
  // A far-future expiry signed with the right key is still refused (the server only mints 10 minutes).
  const far = await signedChunkUrl(f.env, 'alice', 's41', 1, NOW + DAY);
  assert.equal((await get(far)).status, 403);
  // Past the 7 days the signed URL finds nothing, like the API route.
  const old = fixture(); old.env.SLEEP_URL_KEY = f.env.SLEEP_URL_KEY;
  await seedNight(old, 'alice', 's41', { age: 8 * DAY });
  assert.equal((await somnSigned(new Request('https://forja.test' + (await signedChunkUrl(old.env, 'alice', 's41', 0, NOW))), old.env, NOW)).status, 404);
});
test('phasesOf: only well-formed segments inside a day, in order', () => {
  assert.deepEqual(phasesOf('30,40,rem;0,30,light;x;5,5,deep;1500,1600,deep', 1000), [{ from: 1000, to: 1000 + 30 * MIN, stage: 'light' }, { from: 1000 + 30 * MIN, to: 1000 + 40 * MIN, stage: 'rem' }]);
  assert.deepEqual(phasesOf(null, 1000), []);
});
