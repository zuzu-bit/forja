// Secțiunea „cerc” (Teren + Camarazi) — mutată din site-api.test.mjs fără schimbări.
import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { resetSiteCache, SITE_RULES } from '../site-api.mjs';
import { applyUsageRollup, usageDays } from '../site-store.mjs';
import { localDate, localMidnight } from '../site-time.mjs';
import { fixture, seedCircle, Storage, NOW, MIN, HOUR, DAY, SIGNED } from './fixture.mjs';

test.beforeEach(() => resetSiteCache());

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
