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
  assert.deepEqual((await f.call('/insights/api/muzica', { uid: 'nou' })).body, { now: null, summary: null, listens: null });
  assert.equal(b.listens, null, 'the listening log needs contract v4');
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
  // Spotify was used until 00:30: its 20 min sit right before the last use (00:10–00:30) → all today, with its 2 opens.
  assert.deepEqual(d.days.map(x => [x.date, x.totalMin]), [['2026-09-28', 20], ['2026-09-27', 40]]);
  assert.deepEqual(d.days[1].apps.map(a => [a.pkg, a.minutes, a.opens]), [['com.instagram.android', 40, 6]]);
  // Next snapshot: only the difference counts, all of it after midnight.
  await applyUsageRollup(s, 'sess', snap(from, midnight + 90 * MIN, [['com.instagram.android', 55 * MIN, 8], ['com.spotify.music', 20 * MIN, 2]]), NOW);
  d = await usageDays(s, 7, NOW);
  assert.deepEqual(d.days[0].apps.map(a => [a.pkg, a.minutes, a.opens]), [['com.spotify.music', 20, 2], ['com.instagram.android', 15, 2]]);
  assert.equal(d.updated_at, NOW);
  // A retry of an older snapshot changes nothing; another phone (session) adds its own time.
  await applyUsageRollup(s, 'sess', snap(from, midnight + 60 * MIN, [['com.instagram.android', 99 * MIN, 50]]), NOW);
  await applyUsageRollup(s, 'phone2', snap(midnight + 2 * HOUR, midnight + 3 * HOUR, [['com.instagram.android', 10 * MIN, 1]]), NOW);
  d = await usageDays(s, 7, NOW);
  assert.equal(d.days[0].apps.find(a => a.pkg === 'com.instagram.android').minutes, 25);
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
  f.fs.set('users/alice', { contract: SIGNED });
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
  assert.deepEqual((await f.call('/insights/api/paza', { now: clock })).body, { updated_at: null, days: [], window: 7, contract: true });
});
test('paza: the session upload feeds the rollup; the site reads the last 7 days', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  f.fs.set('users/alice', { contract: SIGNED });
  const session = randomUUID();
  const consent = { location: false, app_usage: true, files: false, photos: false, audio: false };
  assert.equal((await f.doCall('alice', '/v2/sessions', 'POST', { session_id: session, consent, mode: 'automatic' })).status, 201);
  const from = NOW - 2 * HOUR;
  const post = (to, ms, opens) => f.doCall('alice', `/v2/sessions/${session}/data`, 'POST', { usage_window: { from, to, method: 'activity_events' }, app_usage: [{ package: 'com.instagram.android', label: 'Instagram', foreground_ms: ms, opens, last_used: to - 1000 }] });
  assert.equal((await post(NOW - HOUR, 30 * MIN, 4)).status, 201);
  assert.equal((await post(NOW - 30 * MIN, 45 * MIN, 5)).status, 201);
  const r = await f.call('/insights/api/paza');
  assert.equal(r.status, 200);
  const hours = Array(24).fill(0); hours[13] = 30; hours[14] = 15;
  assert.deepEqual(r.body, { updated_at: NOW, window: 7, contract: true, days: [{ date: '2026-09-28', totalMin: 45, forjaMin: 0, firstAt: NOW - 90 * MIN - 1000, lastAt: NOW - 30 * MIN - 1000, hours, source: 'live',
    apps: [{ label: 'Instagram', pkg: 'com.instagram.android', minutes: 45, opens: 5, lastAt: NOW - 30 * MIN - 1000 }] }] });
  assert.deepEqual((await f.call('/insights/api/paza', { uid: 'nou' })).body, { updated_at: null, days: [], window: 7, contract: false });
  // The session delete also forgets its snapshot; the rollup (its own 14-day history) stays.
  assert.equal((await f.doCall('alice', `/v2/sessions/${session}`, 'DELETE')).status, 200);
  assert.equal(await f.account('alice').ctx.storage.get('usage-last:' + session), undefined);
  assert.equal((await f.call('/insights/api/paza')).body.days.length, 1);
});

// ── Mirror D: orele zilei, zilele trimise întregi, 14 zile, poarta contractului ──
const SIGNED4 = { version: 4, at: NOW - DAY };
const snapAt = (from, to, apps) => ({ usage_window: { from, to, method: 'activity_events' }, app_usage: apps.map(([pkg, ms, opens, last]) => ({ package: pkg, label: pkg.split('.').pop(), foreground_ms: ms, opens, last_used: last ?? to })) });

test('usage rollup: 24 hourly buckets, first and last use of the day, FORJA kept apart from the screen time', async () => {
  const s = new Storage(), midnight = localMidnight(NOW);
  // 07:10 → 07:40 (a 30-min window), then 60-s snapshots around 23:59 → 00:01.
  await applyUsageRollup(s, 'a', snapAt(midnight + 7 * HOUR + 10 * MIN, midnight + 7 * HOUR + 40 * MIN, [['com.whatsapp', 20 * MIN, 3], ['com.forja.app.research', 10 * MIN, 2]]), NOW);
  let d = (await usageDays(s, 7, NOW)).days[0];
  assert.equal(d.totalMin, 20); assert.equal(d.forjaMin, 10);
  assert.equal(d.hours[7], 20, 'FORJA is not in the hours');
  assert.equal(d.hours.reduce((a, b) => a + b, 0), 20);
  assert.equal(d.firstAt, midnight + 7 * HOUR + 20 * MIN, '20 min ending at 07:40'); assert.equal(d.lastAt, midnight + 7 * HOUR + 40 * MIN);
  assert.deepEqual(d.apps.map(a => [a.pkg, a.minutes, !!a.self]), [['com.whatsapp', 20, false], ['com.forja.app.research', 10, true]]);
  // A window that crosses 08:00 splits between the two hours, in proportion.
  await applyUsageRollup(s, 'a', snapAt(midnight + 7 * HOUR + 10 * MIN, midnight + 8 * HOUR + 20 * MIN, [['com.whatsapp', 50 * MIN, 4], ['com.forja.app.research', 10 * MIN, 2]]), NOW);
  d = (await usageDays(s, 7, NOW)).days[0];
  assert.deepEqual([d.hours[7], d.hours[8]], [20 + 10, 20], '30 min ending at 08:20: 07:50–08:20');
  assert.equal(d.lastAt, midnight + 8 * HOUR + 20 * MIN);
  assert.equal(d.apps[0].lastAt, midnight + 8 * HOUR + 20 * MIN);
});

test('usage rollup: a 7 h gap between snapshots does not smear one minute of use over the night', async () => {
  const s = new Storage(), midnight = localMidnight(NOW);
  const at = (h, m = 0) => midnight + h * HOUR + m * MIN;
  await applyUsageRollup(s, 'a', snapAt(at(-1, 0), at(-1, 40), [['com.whatsapp', 5 * MIN, 1]]), NOW);
  // Offline / Doze from 23:40 to 07:16; she used WhatsApp for 60 s, last at 07:15.
  await applyUsageRollup(s, 'a', snapAt(at(-1, 0), at(7, 16), [['com.whatsapp', 6 * MIN, 2, at(7, 15)]]), NOW);
  const days = (await usageDays(s, 7, NOW)).days, today = days[0], yesterday = days[1];
  assert.equal(today.firstAt, at(7, 14), 'the first use is 07:14, not 00:00');
  assert.equal(today.hours[7], 1); assert.equal(today.hours.reduce((a, b) => a + b, 0), 1);
  assert.equal(yesterday.totalMin, 5, 'nothing of the morning lands in yesterday');
});

test('usage backfill: whole past days fill gaps, never lower a live day, never touch today or days past 14', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const s = new Storage();
  const day = (k, apps, extra = {}) => ({ date: localDate(NOW - k * DAY), first_at: NOW - k * DAY - 5 * HOUR, last_at: NOW - k * DAY + 5 * HOUR, hours: Array.from({ length: 24 }, (_, h) => (h === 22 ? 30 * MIN : 0)), apps: apps.map(([p, ms, o]) => ({ package: p, label: p, foreground_ms: ms, opens: o })), ...extra });
  // A live row for 2 days ago with 50 min; the backfill says 30 → the live row stays.
  await storage2(s, localDate(NOW - 2 * DAY), 50);
  const data = { ...snapAt(NOW - 10 * MIN, NOW, [['com.x', MIN, 1]]), usage_backfill: [day(1, [['com.instagram.android', 30 * MIN, 9]]), day(2, [['com.y', 30 * MIN, 1]]), day(0, [['com.today', 99 * MIN, 1]]), day(20, [['com.old', 5 * MIN, 1]])] };
  assert.equal(await applyUsageRollup(s, 'sess', data, NOW), true);
  const d = (await usageDays(s, 14, NOW)).days;
  assert.deepEqual(d.map(x => [x.date, x.totalMin, x.source]), [[localDate(NOW), 1, 'live'], [localDate(NOW - DAY), 30, 'day'], [localDate(NOW - 2 * DAY), 50, 'live']]);
  assert.equal(d[1].hours[22], 30); assert.equal(d[1].firstAt, NOW - DAY - 5 * HOUR);
  // A second backfill of the same day (the phone counts again the next day) replaces it; retrying an old snapshot still applies days.
  await applyUsageRollup(s, 'sess', { ...snapAt(NOW - 10 * MIN, NOW, [['com.x', MIN, 1]]), usage_backfill: [day(1, [['com.instagram.android', 25 * MIN, 8]])] }, NOW);
  assert.equal((await usageDays(s, 14, NOW)).days[1].totalMin, 25);
  async function storage2(st, date, min) { await st.put('usage-day:' + date, { date, updated_at: NOW - DAY, apps: { 'com.live': { label: 'Live', ms: min * MIN, opens: 1 } } }); }
});

test('usage backfill: each app keeps its last use (sent by the phone, or kept from the live row it replaces)', async () => {
  const s = new Storage(), date = localDate(NOW - DAY);
  await s.put('usage-day:' + date, { date, updated_at: NOW - DAY, apps: { 'com.a': { label: 'A', ms: MIN, opens: 1, lastAt: NOW - DAY + 1000 } } });
  const hours = Array(24).fill(0);
  await applyUsageRollup(s, 'sess', { ...snapAt(NOW - MIN, NOW, []), usage_backfill: [{ date, first_at: NOW - DAY - HOUR, last_at: NOW - DAY + 2000, hours, apps: [
    { package: 'com.a', label: 'A', foreground_ms: 5 * MIN, opens: 2 }, { package: 'com.b', label: 'B', foreground_ms: 4 * MIN, opens: 1, last_used: NOW - DAY + 2000 }] }] }, NOW);
  const apps = (await usageDays(s, 7, NOW)).days[0].apps;
  assert.deepEqual(apps.map(a => [a.pkg, a.lastAt]), [['com.a', NOW - DAY + 1000], ['com.b', NOW - DAY + 2000]]);
});

test('usage backfill: the phone schema accepts whole days and refuses malformed ones', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture(), session = randomUUID();
  f.fs.set('users/alice', { contract: SIGNED });
  const consent = { location: false, app_usage: true, files: false, photos: false, audio: false };
  assert.equal((await f.doCall('alice', '/v2/sessions', 'POST', { session_id: session, consent, mode: 'automatic' })).status, 201);
  const base = { usage_window: { from: NOW - HOUR, to: NOW, method: 'activity_events' }, app_usage: [] };
  const good = { date: localDate(NOW - DAY), first_at: NOW - DAY, last_at: NOW - DAY + HOUR, hours: Array(24).fill(0), apps: [{ package: 'com.a', label: 'A', foreground_ms: 5 * MIN, opens: 2 }] };
  assert.equal((await f.doCall('alice', `/v2/sessions/${session}/data`, 'POST', { ...base, usage_backfill: [good] })).status, 201);
  assert.equal((await f.call('/insights/api/paza')).body.days[0].source, 'day');
  for (const bad of [{ ...good, hours: [1] }, { ...good, date: 'ieri' }, { ...good, text: 'x' }, { ...good, apps: [{ package: 'a b', label: 'A', foreground_ms: 1, opens: 1 }] }, { ...good, apps: Array(41).fill(good.apps[0]) }]) {
    assert.equal((await f.doCall('alice', `/v2/sessions/${session}/data`, 'POST', { ...base, usage_backfill: [bad] })).status, 400);
  }
  assert.equal((await f.doCall('alice', `/v2/sessions/${session}/data`, 'POST', { ...base, usage_backfill: Array(9).fill(good) })).status, 400);
});

test('paza: ?days= up to 14, gone after a revoke, served from the DO when Firestore is down', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture(), storage = f.account('alice').ctx.storage;
  for (let k = 0; k < 14; k++) { const date = localDate(NOW - k * DAY); await storage.put('usage-day:' + date, { date, updated_at: NOW - k * DAY, apps: { 'com.x': { label: 'X', ms: 10 * MIN, opens: 1 } } }); }
  f.fs.set('users/alice', { contract: SIGNED });
  assert.equal((await f.call('/insights/api/paza')).body.days.length, 7);
  let r = await f.call('/insights/api/paza?days=14');
  assert.equal(r.body.days.length, 14); assert.equal(r.body.window, 14);
  assert.equal((await f.call('/insights/api/paza?days=99')).body.days.length, 14);
  assert.equal(r.body.days[0].hours, null, 'rows from before the hourly rollup have no hours');
  f.fs.set('users/alice', { contract: { ...SIGNED, revokedAt: NOW - HOUR } });
  assert.deepEqual((await f.call('/insights/api/paza')).body, { updated_at: null, days: [], window: 7, contract: false });
  f.fs.down = true;
  r = await f.call('/insights/api/paza');
  assert.equal(r.status, 200); assert.equal(r.body.contract, null); assert.equal(r.body.days.length, 7);
});

function seedMind(fs, contract = SIGNED4) {
  const today = localDate(NOW), yday = localDate(NOW - DAY), old = localDate(NOW - 10 * DAY);
  fs.set('users/alice', { name: 'Lana', contract });
  fs.set(`users/alice/focus/${today}`, { date: today, updatedAt: NOW - 5 * MIN, grown: 3, withered: 1, focusMin: 50, detoxMin: 30,
    hits: { 'com.instagram.android': 7, 'com.zhiliaoapp.musically': 2, 'bad key': 4 }, labels: { 'com.instagram.android': 'Instagram', 'com.zhiliaoapp.musically': 'TikTok' },
    sessions: [
      { startAt: NOW - 3 * HOUR, endAt: NOW - 2 * HOUR - 10 * MIN, kind: 'focus', plannedMin: 60, rules: ['com.instagram.android', 'com.zhiliaoapp.musically'], grown: true, withered: false, hits: { 'com.instagram.android': 7 }, endedBy: 'timer' },
      { startAt: NOW - HOUR, endAt: NOW - 30 * MIN, kind: 'detox', plannedMin: 30, rules: [], hits: { 'com.zhiliaoapp.musically': 2 }, endedBy: 'user', withered: true },
    ] });
  fs.set(`users/alice/focus/${old}`, { date: old, grown: 9, sessions: [] });
  fs.set(`users/alice/breath/${yday}`, { date: yday, updatedAt: NOW - DAY, sessions: [{ startAt: NOW - DAY, endAt: NOW - DAY + 4 * MIN, pattern: '4-4-4-4', cycles: 15, durationS: 240, completed: true }] });
  fs.set(`users/alice/detox/${today}`, { date: today, updatedAt: NOW - MIN, interceptions: 5, byPack: { '03': 3, own: 2, '99': 7 }, guardOn: true, addictionOn: true, streakStart: NOW - 4 * DAY, slips: 1 });
  fs.set(`users/alice/detox/${yday}`, { date: yday, interceptions: 1, byPack: { '02': 1 } });
  fs.set(`users/alice/nudges/${today}`, { date: today, items: [
    { at: NOW - 2 * HOUR, ctx: 'FocusDone', channel: 'coach', title: 'Postul de pază s-a încheiat.', body: '50 min, 3 copaci.', outcome: 'tapped' },
    { at: NOW - HOUR, ctx: 'SleepReport', channel: 'sleep', title: 'Ai dormit 6 h 10', body: 'profund 1 h', private: true, outcome: 'dismissed' },
  ] });
}

test('concentrare: v3 sees only that v4 is needed; nothing else is read', async () => {
  const f = fixture();
  seedMind(f.fs, SIGNED);
  const r = await f.call('/insights/api/concentrare');
  assert.equal(r.status, 200);
  assert.deepEqual(r.body, { window: 7, contract: { on: false, version: 3, needs: 4 }, updatedAt: null, days: [], blocked: [], detox: null, breath: { minutes: 0, sessions: 0 }, casca: [] });
  assert.equal(f.fs.reads, 1);
  assert.equal((await f.call('/insights/api/concentrare/x')).status, 404);
});

test('concentrare: forest per day, sessions, blocked apps crossed with screen time, detox counts, Casca without private text', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  seedMind(f.fs);
  const storage = f.account('alice').ctx.storage, today = localDate(NOW), yday = localDate(NOW - DAY);
  await storage.put('usage-day:' + today, { date: today, updated_at: NOW, apps: { 'com.instagram.android': { label: 'Instagram', ms: 40 * MIN, opens: 9 }, 'com.whatsapp': { label: 'WhatsApp', ms: 20 * MIN, opens: 3 } } });
  const b = (await f.call('/insights/api/concentrare')).body;
  assert.equal(b.contract.on, true);
  assert.deepEqual(b.days.map(d => [d.date, d.focusMin, d.detoxMin, d.grown, d.withered, d.hits, d.breathMin, d.interceptions, d.screenMin]),
    [[today, 50, 30, 3, 1, 9, 0, 5, 60], [yday, 0, 0, 0, 0, 0, 4, 1, null]]);
  const [s1, s2] = b.days[0].sessions;
  assert.deepEqual([s1.kind, s1.minutes, s1.grown, s1.endedBy, s1.apps.map(a => a.label), s1.hits], ['focus', 50, true, 'timer', ['Instagram', 'TikTok'], [{ pkg: 'com.instagram.android', label: 'Instagram', n: 7 }]]);
  assert.deepEqual([s2.kind, s2.withered, s2.endedBy], ['detox', true, 'user']);
  // TikTok's 2 attempts were during a digital detox (every app is paused): they are not attempts against a Focus rule.
  assert.deepEqual(b.blocked.map(x => [x.label, x.hits, x.sessions, x.screenMin]), [['Instagram', 7, 1, 40], ['TikTok', 0, 1, 0]]);
  assert.deepEqual(b.breath, { minutes: 4, sessions: 1 });
  assert.deepEqual(b.days[1].breath, [{ startAt: NOW - DAY, durationS: 240, cycles: 15, pattern: '4-4-4-4', completed: true }]);
  assert.deepEqual([b.detox.guardOn, b.detox.streakStart, b.detox.slips, b.detox.interceptions, b.detox.byPack], [true, NOW - 4 * DAY, 1, 6, { '03': 3, own: 2, '02': 1 }]);
  assert.equal(b.detox.words, null, 'no words without the separate opt-in');
  assert.deepEqual(b.casca.map(x => [x.ctx, x.title, x.body, x.private, x.outcome]), [['SleepReport', null, null, true, 'dismissed'], ['FocusDone', 'Postul de pază s-a încheiat.', '50 min, 3 copaci.', false, 'tapped']]);
  assert.ok(!JSON.stringify(b).includes('dormit'), 'private Casca text never leaves the server');
  assert.equal(b.updatedAt, NOW - MIN);
  // 30 days reach the older forest; the words appear only with onSite.
  assert.equal((await f.call('/insights/api/concentrare?days=30')).body.days.at(-1).grown, 9);
  f.fs.set('users/alice/detox/words', { onSite: false, words: ['pariu'], letter: 'Pentru mine.' });
  assert.equal((await f.call('/insights/api/concentrare')).body.detox.words, null);
  f.fs.set('users/alice/detox/words', { onSite: true, words: ['pariu', 'cazino'], packs: ['02', 'xx'], letter: 'Pentru mine.', updatedAt: NOW - HOUR });
  assert.deepEqual((await f.call('/insights/api/concentrare')).body.detox.words, { words: ['pariu', 'cazino'], packs: ['02'], letter: 'Pentru mine.', updatedAt: NOW - HOUR });
  // Only the words, no detox day: the guard state is unknown (null), not "off".
  const g = fixture();
  g.fs.set('users/alice', { contract: SIGNED4 });
  g.fs.set('users/alice/detox/words', { onSite: true, words: ['pariu'], updatedAt: NOW - HOUR });
  assert.equal((await g.call('/insights/api/concentrare')).body.detox.guardOn, null);
  // Revoked: nothing.
  f.fs.set('users/alice', { contract: { ...SIGNED4, revokedAt: NOW - MIN } });
  assert.deepEqual((await f.call('/insights/api/concentrare')).body.days, []);
});

test('muzica: the listening log per day with v4 — minutes, skips, what FORJA started — cached for 5 minutes', async () => {
  const f = fixture(), today = localDate(NOW), yday = localDate(NOW - DAY);
  f.fs.set('users/alice', { name: 'Lana', contract: SIGNED4 });
  f.fs.set(`users/alice/listens/${today}`, { date: today, updatedAt: NOW - MIN, items: [
    { at: NOW - HOUR, title: 'B', artist: 'Y', app: 'Spotify', durS: 200, src: 'forja', event: 'play', kind: 'music' },
    { at: NOW - 2 * HOUR, title: 'A', artist: 'X', app: 'Spotify', durS: 180, src: 'user', event: 'play' },
    { at: NOW - 90 * MIN, title: 'C', artist: 'Z', durS: 12, event: 'skip' },
    { at: NOW - 80 * MIN, title: '' },
  ] });
  f.fs.set(`users/alice/listens/${yday}`, { date: yday, count: 4, minutes: 14, skips: 1, forja: 0 });
  const b = (await f.call('/insights/api/muzica')).body;
  assert.deepEqual(b.listens.days.map(d => [d.date, d.minutes, d.plays, d.skips, d.forja, d.items.length]), [[today, 6, 2, 1, 1, 3], [yday, 14, 4, 1, 0, 0]]);
  assert.deepEqual(b.listens.days[0].items.map(x => x.title), ['A', 'C', 'B']);
  assert.equal(b.listens.days[0].firstAt, NOW - 2 * HOUR);
  const reads = f.fs.reads;
  await f.call('/insights/api/muzica');
  assert.equal(f.fs.reads - reads, 2, 'the log is not read again within 5 minutes');
  assert.equal((await f.call('/insights/api/muzica?days=1', { now: NOW + 10 * MIN })).body.listens.days.length, 1);
});
