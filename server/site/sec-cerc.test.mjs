// Secțiunea „cerc” (Teren + Camarazi) — mutată din site-api.test.mjs fără schimbări.
import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { resetSiteCache, SITE_RULES } from '../site-api.mjs';
import { applyUsageRollup, usageDays } from '../site-store.mjs';
import { localDate, localMidnight } from '../site-time.mjs';
import { fixture, seedCircle, Storage, NOW, MIN, HOUR, DAY, SIGNED } from './fixture.mjs';
import { applyLocationRollup, sweepLocation, dayView, thin, mergeStops, LOC_DAY_PREFIX, LOC_RULES } from '../site-location.mjs';

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
  assert.deepEqual(Object.keys(b), ['me', 'friends', 'family', 'recommended', 'routes', 'inviteCode', 'energy', 'agenda', 'mine', 'day', 'updated_at']);
  assert.deepEqual(b.me, { lat: 44.43, lng: 26.1, at: NOW - 2 * MIN, ghost: false, ghostUntil: null, state: 'walk', nowPlaying: { title: 'Fetele care ard', artist: 'Trupa', app: 'Spotify', at: NOW - 3 * MIN }, exploreCells: 89,
    placesCount: null, weekKm: null, last: null, speedMps: null, family: [{ uid: 'bob', name: 'Bogdan Ionescu' }], familyAt: null, private: null, go: null, bgShare: null, bgShareAt: null });
  assert.equal(b.inviteCode, 'K7Q2');
  const byUid = Object.fromEntries(b.friends.map(x => [x.uid, x]));
  assert.deepEqual(b.friends.map(x => x.name), ['Bogdan Ionescu', 'Carla', 'Dan']);
  assert.deepEqual(byUid.bob, { uid: 'bob', name: 'Bogdan Ionescu', initials: 'BI', lat: null, lng: null, at: null, state: 'ghost', ghost: true, viaFamily: false, nowPlaying: null, exploreCells: 40,
    placesCount: null, weekKm: null, last: null, inMyFamily: true, hasMeInFamily: true, since: NOW - 30 * DAY, fromAgenda: false });
  assert.equal(byUid.carol.viaFamily, true, 'the familyLoc point is flagged'); assert.equal(byUid.carol.hasMeInFamily, true); assert.equal(byUid.carol.inMyFamily, false);
  assert.equal(byUid.dan.hasMeInFamily, false, 'his familyLoc does not include you');
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
  assert(cold <= 1 + 10 + 1 + 1 + 10 + 5 + 40 + 5, 'cold read ' + cold);
  let before = f.fs.reads;
  await f.call('/insights/api/cerc', { now: NOW + 10000 });
  assert.equal(f.fs.reads - before, 0, 'same isolate within 20 s: memory');
  resetSiteCache(); before = f.fs.reads;
  await f.call('/insights/api/cerc', { now: NOW + 15000 });
  assert.equal(f.fs.reads - before, 0, 'another isolate within 20 s: the account DO answers');
  resetSiteCache(); before = f.fs.reads;
  await f.call('/insights/api/cerc', { now: NOW + 25000 });
  const live = f.fs.reads - before;
  assert.equal(live, 1 + 10 + 1 + 1, 'after 20 s only the live tier: me + 10 friends (one batchGet) + familyLoc + the live GO doc (she is walking)');
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

// ───────────────────────── Mirror, pachetul A: prietenii și locurile ─────────────────────────

test('cerc mirror: friend activity (km, last run, places), since, family both ways and a flagged familyLoc point', async () => {
  const f = fixture();
  seedCircle(f.fs);
  f.fs.set('users/bob', { ...f.fs.docs.get('users/bob'), weekKm: 12.4, lastActivityType: 'run', lastActivityKm: 5.02, lastActivityDurS: 1740, lastActivityAt: NOW - DAY, placesCount: 7, exploreCells: 120 });
  f.fs.set('familyLoc/carol', { lat: 44.5, lng: 26.2, locUpdatedAt: NOW - MIN, state: 'idle', allowed: ['alice'] });
  const b = (await f.call('/insights/api/cerc')).body;
  const bob = b.friends.find(x => x.uid === 'bob'), carol = b.friends.find(x => x.uid === 'carol');
  assert.equal(bob.weekKm, 12.4); assert.deepEqual(bob.last, { type: 'run', km: 5.02, durS: 1740, at: NOW - DAY }); assert.equal(bob.placesCount, 7);
  assert.equal(bob.since, NOW - 30 * DAY, 'friendships.since, the date on the card');
  assert.equal(bob.inMyFamily, true); assert.equal(bob.hasMeInFamily, false);
  assert.equal(carol.inMyFamily, false); assert.equal(carol.hasMeInFamily, true); assert.equal(carol.viaFamily, true); assert.equal(carol.lat, 44.5);
  assert.deepEqual(b.me.family, [{ uid: 'bob', name: 'Bogdan Ionescu' }], 'who sees you even in ghost');
  const masks = f.fs.requests.filter(q => q.url.endsWith(':batchGet')).map(q => q.body.mask.fieldPaths);
  assert(masks.every(m => !m.includes('familyUids') && !m.includes('contract')), 'friends are never read for their family or contract');
});

test('cerc mirror: in ghost you still see your own point (familyLoc) and the GO in progress; friends see nothing new', async () => {
  const f = fixture();
  seedCircle(f.fs, 1);
  f.fs.set('users/alice', { ...f.fs.docs.get('users/alice'), ghostUntil: NOW + HOUR, state: 'idle' });
  f.fs.set('familyLoc/alice', { lat: 44.401, lng: 26.051, locUpdatedAt: NOW - 90000, state: 'run', allowed: ['bob'] });
  const line = Array.from({ length: 900 }, (_, i) => `${(44.4 + i * 0.00005).toFixed(6)},${(26.05 + i * 0.00002).toFixed(6)}`).join(';');
  f.fs.set('users/alice/live/go', { sport: 'run', startedAt: NOW - 20 * MIN, distanceM: 3200, polyline: line, updatedAt: NOW - 20000 });
  const b = (await f.call('/insights/api/cerc')).body;
  assert.equal(b.me.lat, null, 'the public pin stays hidden');
  assert.deepEqual(b.me.private, { lat: 44.401, lng: 26.051, at: NOW - 90000, source: 'family' });
  assert.equal(b.me.go.sport, 'run'); assert.equal(b.me.go.distanceM, 3200); assert.equal(b.me.go.startedAt, NOW - 20 * MIN);
  assert(b.me.go.polyline.split(';').length <= 500, 'the live line is thinned');
  // A GO doc not updated for 10 minutes is a killed service, not a run in progress.
  resetSiteCache();
  f.fs.set('users/alice/live/go', { sport: 'run', startedAt: NOW - HOUR, distanceM: 3200, polyline: line, updatedAt: NOW - 11 * MIN });
  assert.equal((await f.call('/insights/api/cerc', { now: NOW + 21000 })).body.me.go, null);
  // Not signed: no GO, no agenda, no day.
  resetSiteCache();
  f.fs.set('users/alice', { ...f.fs.docs.get('users/alice'), contract: { version: 3, at: NOW - DAY, revokedAt: NOW - HOUR } });
  f.fs.set('users/alice/live/go', { sport: 'run', startedAt: NOW - 20 * MIN, distanceM: 3200, polyline: line, updatedAt: NOW - 20000 });
  f.fs.set('users/alice/settings/contacts', { syncedAt: NOW, status: 'ok', compared: 300, found: 1, mutual: 1, matches: [{ uid: 'bob', forjaName: 'Bogdan', mutual: true, verified: true }] });
  const off = (await f.call('/insights/api/cerc', { now: NOW + 42000 })).body;
  assert.equal(off.me.go, null); assert.equal(off.agenda, null); assert.equal(off.day, null);
});

test('cerc mirror: energy received and sent (newest first, names, today), my recommendations, background location, agenda', async () => {
  const f = fixture();
  seedCircle(f.fs);
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Bucharest' }).format(new Date(NOW));
  f.fs.set(`energy/alice_${today}_bob`, { to: 'alice', from: 'bob', fromName: 'Bogdan', day: today, at: NOW - HOUR });
  f.fs.set('energy/alice_2026-09-20_carol', { to: 'alice', from: 'carol', fromName: 'Carla', day: '2026-09-20', at: NOW - 8 * DAY });
  f.fs.set('energy/alice_2026-09-26_dan', { to: 'alice', from: 'dan', fromName: 'Dan', day: '2026-09-26', at: NOW - 2 * DAY });
  f.fs.set(`energy/bob_${today}_alice`, { to: 'bob', from: 'alice', fromName: 'Lana', day: today, at: NOW - 10 * MIN });
  f.fs.set('energy/carol_2026-09-27_zoe', { to: 'carol', from: 'zoe', fromName: 'Zoe', day: '2026-09-27', at: NOW - DAY });
  f.fs.set('places/mine1', { ownerUid: 'alice', ownerName: 'Lana', name: 'Terasa mea', stars: 5, note: '', lat: 44.42, lng: 26.09, visits: 3, at: NOW - DAY, visibleTo: ['bob', 'carol'] });
  f.fs.set('users/alice/settings/presence', { bgShare: true, updatedAt: NOW - HOUR });
  f.fs.set('users/alice/settings/contacts', { syncedAt: NOW - 2 * HOUR, status: 'ok', compared: 312, found: 2, mutual: 1,
    matches: [{ uid: 'bob', forjaName: 'Bogdan I.', mutual: true, verified: true }, { uid: 'eve', forjaName: 'Eva', mutual: false, verified: false }, { uid: 'bad uid!', forjaName: 'x' }] });
  const b = (await f.call('/insights/api/cerc')).body;
  assert.deepEqual(b.energy.received.map(r => r.uid), ['bob', 'dan', 'carol'], 'newest first, sorted in the worker');
  assert.deepEqual(b.energy.received[0], { uid: 'bob', name: 'Bogdan', at: NOW - HOUR, day: today });
  assert.equal(b.energy.today, 1); assert.equal(b.energy.week, 2);
  assert.deepEqual(b.energy.sent, [{ uid: 'bob', at: NOW - 10 * MIN, day: today, name: 'Bogdan Ionescu' }], 'only what she sent, named from the friend list');
  assert.deepEqual(b.energy.sentToday, ['bob']);
  assert(!JSON.stringify(b.energy).includes('zoe'), 'energy between other people never shows');
  assert.deepEqual(b.mine, [{ id: 'mine1', name: 'Terasa mea', stars: 5, lat: 44.42, lng: 26.09, seenBy: 2, at: NOW - DAY }]);
  assert.equal(b.me.bgShare, true); assert.equal(b.me.bgShareAt, NOW - HOUR);
  assert.deepEqual(b.agenda, { syncedAt: NOW - 2 * HOUR, status: 'ok', compared: 312, found: 2, mutual: 1,
    list: [{ uid: 'bob', name: 'Bogdan I.', mutual: true, verified: true, friend: true }, { uid: 'eve', name: 'Eva', mutual: false, verified: false, friend: false }] });
  assert.equal(b.friends.find(x => x.uid === 'bob').fromAgenda, true, 'the "din agendă" badge');
  // Slow tier: 10 minutes. A poll 30 s later reads none of these again.
  resetSiteCache();
  const before = f.fs.requests.length;
  await f.call('/insights/api/cerc', { now: NOW + 30000 });
  const again = f.fs.requests.slice(before).filter(r => ['energy', 'places'].includes(r.body?.structuredQuery?.from?.[0]?.collectionId) || /settings\/(presence|contacts)/.test(r.url));
  assert.equal(again.length, 0, 'energy, places, presence and agenda stay in the 10-minute tier');
});

test('cerc mirror: the day on the map comes from the account DO (24 h), with the names of your places', async t => {
  const f = fixture();
  seedCircle(f.fs, 1);
  t.mock.method(Date, 'now', () => NOW);
  const cell = { id: '1_1', min_lat: 44.43, min_lng: 26.09, max_lat: 44.431, max_lng: 26.091, first_at: NOW - DAY, last_at: NOW - HOUR, visits: 2 };
  const res = await f.socialCall('alice', 'explore/sync', 'POST', { device: randomUUID(), grid_m: 150, revision: 1, reset: false, cells: [cell],
    places: [{ id: 'home', lat: 44.4301, lng: 26.1001, first_at: NOW - 9 * DAY, last_at: NOW - HOUR, stay_ms: 36000000, name: 'Acasă', stars: 5, note: '', recommended: false, visible_to: [], updated_at: NOW - HOUR, deleted: false }] });
  assert.equal(res.status, 200);
  const storage = f.account('alice').ctx.storage;
  const start = NOW - 3 * HOUR;
  const locations = Array.from({ length: 120 }, (_, i) => ({ at: start + i * 10000, latitude: 44.43 + i * 0.0002, longitude: 26.1, accuracy_m: 10, segment: 0 }));
  await applyLocationRollup(storage, 's1', { locations, visits: [{ first_seen: NOW - 2 * HOUR, last_seen: NOW - HOUR, latitude: 44.43, longitude: 26.1, observed_ms: HOUR, samples: 200 }] }, NOW);
  const b = (await f.call('/insights/api/cerc')).body;
  assert.equal(b.day.stops.length, 1);
  assert.deepEqual(b.day.stops[0], { from: NOW - 2 * HOUR, to: NOW - HOUR, minutes: 60, lat: 44.43, lng: 26.1, name: 'Acasă' });
  assert.equal(b.day.track.length, 1); assert(b.day.km > 2 && b.day.km < 3, 'km ' + b.day.km);
  assert.equal(b.day.keep_hours, 24);
  assert.equal(b.day.last.at, start + 119 * 10000 - (119 * 10000) % 30000, 'the newest kept point');
});

test('location rollup: 1 point / 30 s, re-sent windows add nothing, bad fixes dropped, stops merged across snapshots', async () => {
  const s = new Storage();
  const base = NOW - 2 * HOUR;
  const fixes = (from, n, extra = {}) => Array.from({ length: n }, (_, i) => ({ at: from + i * 10000, latitude: 44.43 + i * 0.0001, longitude: 26.1, accuracy_m: 12, segment: 0, ...extra }));
  await applyLocationRollup(s, 's1', { locations: fixes(base, 300), visits: [] }, NOW);
  const date = [...(await s.list({ prefix: LOC_DAY_PREFIX })).keys()];
  assert.equal(date.length, 1);
  let row = await s.get(date[0]);
  assert.equal(row.pts.length, 100, '300 fixes 10 s apart → one every 30 s');
  // The next snapshot overlaps 50 minutes with the first one: only the new 10 minutes count.
  await applyLocationRollup(s, 's1', { locations: fixes(base + 10 * MIN, 300), visits: [] }, NOW);
  row = await s.get(date[0]);
  assert.equal(row.pts.length, 120);
  for (let i = 1; i < row.pts.length; i++) assert(row.pts[i][0] - row.pts[i - 1][0] >= LOC_RULES.step_ms);
  await applyLocationRollup(s, 's1', { locations: [{ at: base + 3 * HOUR - 1, latitude: 1, longitude: 1, accuracy_m: 900, segment: 0 }], visits: [] }, NOW);
  assert.equal((await s.get(date[0])).pts.length, 120, 'a 900 m fix is not a position');
  // The same stop comes back in each snapshot with a later last_seen; a second stop somewhere else stays separate.
  const visit = (first, last, lat = 44.44) => ({ first_seen: first, last_seen: last, latitude: lat, longitude: 26.1, observed_ms: last - first, samples: 20 });
  await applyLocationRollup(s, 's1', { locations: [], visits: [visit(base, base + 10 * MIN)] }, NOW);
  await applyLocationRollup(s, 's1', { locations: [], visits: [visit(base + 30000, base + 25 * MIN, 44.4401)] }, NOW);
  await applyLocationRollup(s, 's1', { locations: [], visits: [visit(base + 40 * MIN, base + 50 * MIN, 44.5)] }, NOW);
  row = await s.get(date[0]);
  assert.equal(row.stops.length, 2);
  assert.deepEqual([row.stops[0].from, row.stops[0].to], [base, base + 25 * MIN]);
  assert.equal(thin([[1, 0, 0], [1, 0, 0], [40000, 0, 0]]).length, 2, 'the same moment twice is one point');
  assert.equal(thin(Array.from({ length: 10 }, (_, i) => [i * 30000, 0, 0]), 30000, 4).length, 4, 'a full day is thinned evenly to the cap');
  assert.equal(mergeStops([{ from: 0, to: 10, lat: 44, lng: 26, samples: 1 }], [{ from: 5 * MIN, to: 20 * MIN, lat: 44, lng: 26, samples: 1 }]).length, 2, 'the same place hours apart is two stops');
});

test('location rollup: everything older than 24 h leaves, by write and by the alarm; revoke clears the prefix', async () => {
  const f = fixture();
  const account = f.account('alice'), s = account.ctx.storage;
  await applyLocationRollup(s, 's1', { locations: [{ at: NOW - 23 * HOUR, latitude: 44.4, longitude: 26.1, accuracy_m: 10, segment: 0 }, { at: NOW - HOUR, latitude: 44.41, longitude: 26.1, accuracy_m: 10, segment: 0 }],
    visits: [{ first_seen: NOW - 23 * HOUR, last_seen: NOW - 22 * HOUR, latitude: 44.4, longitude: 26.1, observed_ms: HOUR, samples: 50 }] }, NOW);
  assert.equal(s.alarm, NOW + HOUR, 'the alarm is set for the moment the oldest point turns 24 h');
  assert.equal(await applyLocationRollup(s, 's1', { locations: [{ at: NOW - 25 * HOUR, latitude: 1, longitude: 1, accuracy_m: 5, segment: 0 }], visits: [] }, NOW), 0, 'a point already older than 24 h is never stored');
  let next = await sweepLocation(s, NOW + 2 * HOUR + 1);
  let v = await dayView(s, NOW + 2 * HOUR + 1);
  assert.equal(v.points, 1); assert.equal(v.stops.length, 0, 'the stop that ended just over 24 h ago is gone');
  assert.equal(next, NOW - HOUR + 24 * HOUR);
  await sweepLocation(s, NOW + 24 * HOUR);
  assert.equal((await s.list({ prefix: LOC_DAY_PREFIX })).size, 0, 'rows with nothing left are deleted');
  assert.equal(await dayView(s, NOW + 24 * HOUR), null);
  // The DO's own alarm runs the sweep too.
  await applyLocationRollup(s, 's1', { locations: [{ at: NOW - HOUR, latitude: 44.41, longitude: 26.1, accuracy_m: 10, segment: 0 }], visits: [] }, NOW);
  await applyLocationRollup(s, 's1', { locations: [{ at: NOW - 30 * MIN, latitude: 44.41, longitude: 26.1, accuracy_m: 10, segment: 0 }], visits: [] }, NOW);
  assert.equal((await f.doCall('alice', '/v2/site/forget', 'POST')).status, 200);
  assert.equal((await s.list({ prefix: LOC_DAY_PREFIX })).size, 0, 'revoke deletes the day');
});

test('day view: a gap over 10 minutes cuts the line (no straight line through missing data); GPS noise on one spot adds no km', async () => {
  const s = new Storage();
  const pts = [];
  for (let i = 0; i < 20; i++) pts.push({ at: NOW - 3 * HOUR + i * 30000, latitude: 44.43 + i * 0.001, longitude: 26.1, accuracy_m: 8, segment: 0 });
  for (let i = 0; i < 200; i++) pts.push({ at: NOW - HOUR + i * 30000 - 50 * MIN, latitude: 44.46 + ((i % 3) - 1) * 0.0001, longitude: 26.1 + ((i % 2) ? 0.0001 : -0.0001), accuracy_m: 8, segment: 0 });
  await applyLocationRollup(s, 's1', { locations: pts, visits: [] }, NOW);
  const v = await dayView(s, NOW);
  assert.equal(v.track.length, 2, 'two pieces of line');
  assert.equal(v.gaps.length, 1); assert(v.gaps[0].to - v.gaps[0].from > LOC_RULES.gap_ms);
  assert(v.km > 1.9 && v.km < 2.3, 'only the real walk counts: ' + v.km);
});
