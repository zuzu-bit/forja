// „muzica” și „paza” (+ rollup-ul timpului pe ecran) — mutate din site-api.test.mjs.
import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { resetSiteCache, SITE_RULES } from '../site-api.mjs';
import { applyUsageRollup, usageDays } from '../site-store.mjs';
import { localDate, localMidnight } from '../site-time.mjs';
import { fixture, seedCircle, Storage, NOW, MIN, HOUR, DAY, SIGNED } from './fixture.mjs';

test.beforeEach(() => resetSiteCache());

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
