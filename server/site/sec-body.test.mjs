// „ratie” și „mars” — mutate din site-api.test.mjs; mirror C: detaliile mesei, poza, antrenamentele pe exerciții, paginile mai vechi.
import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { resetSiteCache, SITE_RULES } from '../site-api.mjs';
import { applyUsageRollup, usageDays } from '../site-store.mjs';
import { localDate, localMidnight } from '../site-time.mjs';
import { fixture, seedCircle, Storage, NOW, MIN, HOUR, DAY, SIGNED } from './fixture.mjs';

test.beforeEach(() => resetSiteCache());

// ── Rație, Marș, Muzică ──

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
  assert.deepEqual(empty, { targets: null, days: [], from: '2026-08-30', more: false });
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
  assert.deepEqual(b.workouts, [{ id: 'w1', startAt: NOW - 2 * DAY, endAt: NOW - 2 * DAY + 2700000, durationS: 2700, title: 'Piept și spate', kind: 'gym', sets: 14, volumeKg: 4200.5, kcal: null, source: 'instructie' }]);
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

// ── mirror C ──
const jpeg = n => { const b = new Uint8Array(n); b.set([255, 216, 255, 224]); return b; };
test('ratie: analysis details and the photo flag reach the site only when the meal has them; older pages via ?before', async () => {
  const f = fixture();
  const midnight = localMidnight(NOW), epoch = Math.floor(Date.UTC(2026, 8, 28) / DAY);
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 4, at: NOW - DAY } });
  f.fs.set('users/alice/meals/c-1', { name: 'Paste', kcal: 640, protein: 22, carbs: 90, fat: 18, grams: 420, mealType: 1, source: 'ESTIMARE AI · POZĂ', confidence: 'medie', epochDay: epoch, at: midnight + 13 * HOUR,
    items: [{ name: 'Paste', grams: 300, kcal: 480, protein: 15, carbs: 80, fat: 8 }, { name: 'Sos', grams: 120, kcal: 160, protein: 7, carbs: 10, fat: 10 }, { nume: 'fără nume' }],
    score: { value: 7, reason: 'Echilibrat, puține fibre' }, tip: 'Adaugă o salată.', photo: true });
  f.fs.set('users/alice/meals/c-2', { name: 'Măr', kcal: 80, epochDay: epoch, at: midnight + 16 * HOUR, score: { value: 42 }, photo: 'da' });
  for (let i = 0; i < 5; i++) f.fs.set(`users/alice/meals/old${i}`, { name: 'Veche ' + i, kcal: 100 + i, epochDay: epoch - 100 - i, at: midnight - (100 + i) * DAY + 12 * HOUR });
  const b = (await f.call('/insights/api/ratie?days=30')).body;
  const [paste, mar] = b.days[0].meals;
  assert.deepEqual(paste.items, [{ name: 'Paste', grams: 300, kcal: 480, protein: 15, carbs: 80, fat: 8 }, { name: 'Sos', grams: 120, kcal: 160, protein: 7, carbs: 10, fat: 10 }]);
  assert.deepEqual(paste.score, { value: 7, reason: 'Echilibrat, puține fibre' });
  assert.equal(paste.tip, 'Adaugă o salată.'); assert.equal(paste.photo, true);
  assert.deepEqual(Object.keys(mar).sort(), ['at', 'carbs', 'confidence', 'fat', 'grams', 'id', 'kcal', 'mealType', 'name', 'protein', 'source'].sort(), 'a score out of 1..10 and a non-boolean photo are dropped');
  assert.equal(b.more, true, 'there is history older than 30 days');
  const older = (await f.call('/insights/api/ratie?days=30&before=' + b.from)).body;
  assert.deepEqual(older.days.map(d => d.meals[0].name), ['Veche 0', 'Veche 1', 'Veche 2', 'Veche 3', 'Veche 4'], 'the older page starts at the newest older meal, never empty');
  assert(older.days.every(d => d.date < b.from));
  assert.equal(older.more, false);
  const three = (await f.call('/insights/api/ratie?days=3&before=' + b.from)).body;
  assert.deepEqual(three.days.map(d => d.meals[0].name), ['Veche 0', 'Veche 1', 'Veche 2']);
  assert.equal(three.more, true);
  const rest = (await f.call('/insights/api/ratie?days=3&before=' + three.from)).body;
  assert.deepEqual(rest.days.map(d => d.meals[0].name), ['Veche 3', 'Veche 4']);
});

test('ratie photo: PUT needs contract v4 and a JPEG, the key comes from the verified uid, GET and DELETE', async () => {
  const f = fixture();
  f.fs.set('users/alice', { name: 'Lana', contract: SIGNED });
  f.env.RECORDS = f.records;
  const put = (uid, body, id = 'c-1') => handle(f, uid, '/insights/api/ratie/photo/' + id, 'PUT', body);
  assert.equal((await put('alice', jpeg(1000))).status, 403, 'v3 does not keep meal photos');
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 4, at: NOW - DAY } });
  assert.equal((await put('alice', new Uint8Array([1, 2, 3, 4]))).status, 400);
  assert.equal((await put('alice', jpeg(300 * 1024))).status, 413);
  assert.equal((await put('alice', jpeg(2000))).status, 201);
  assert.ok(f.records.files.has('_insights/alice/meals/c-1.jpg'));
  assert.equal((await put('alice', jpeg(10), 'a.b')).status, 404);
  const got = await handle(f, 'alice', '/insights/api/ratie/photo/c-1');
  assert.equal(got.status, 200); assert.equal(got.headers.get('content-type'), 'image/jpeg');
  assert.equal((await handle(f, 'bob', '/insights/api/ratie/photo/c-1')).status, 404, 'another account never reads it');
  assert.equal((await handle(f, 'alice', '/insights/api/ratie/photo/c-1', 'DELETE')).status, 200);
  assert.equal((await handle(f, 'alice', '/insights/api/ratie/photo/c-1')).status, 404);
  assert.equal((await handle(f, 'alice', '/insights/api/ratie', 'PUT', jpeg(10))).status, 405, 'only the photo route takes a PUT');
  await f.records.put('_insights/alice/meals/c-9.jpg', jpeg(10));
  assert.equal((await f.doCall('alice', '/v2/site/forget', 'POST')).status, 200);
  assert.ok(f.records.files.has('_insights/alice/meals/c-9.jpg'), 'revoking keeps meal photos: they stay with the journal while the account exists');
});

test('mars: workout details (exercises, sets, music, completed), older pages and no week on a page back', async () => {
  const f = fixture();
  f.fs.set('users/alice', { name: 'Lana', contract: SIGNED });
  f.fs.set('users/alice/workouts/wk-a', { startAt: NOW - DAY, endAt: NOW - DAY + 3000000, durationS: 3000, title: 'Forță A', kind: 'forta', sets: 3, volumeKg: 1500, kcal: null, source: 'instructie', completed: false, plannedSets: 12,
    exercises: [{ name: 'Genuflexiuni', sets: [{ reps: 8, load: '62,5', kg: 62.5, at: NOW - DAY + 60000 }, { reps: 8, load: '62,5', kg: 62.5, at: NOW - DAY + 240000 }] }, { name: 'Flotări', sets: [{ reps: 15, load: 'corp', kg: null, at: NOW - DAY + 600000 }] }, { sets: [] }],
    music: [{ title: 'Ploaia', artist: 'Trupa', at: NOW - DAY + 30000 }, { artist: 'fără titlu' }] });
  f.fs.set('users/alice/activities/old', { type: 'walk', startAt: NOW - 50 * DAY, endAt: NOW - 50 * DAY + HOUR, distanceM: 4000, durationS: 3600, kcal: 200, polyline: '' });
  const b = (await f.call('/insights/api/mars?days=30')).body;
  const w = b.workouts[0];
  assert.equal(w.completed, false); assert.equal(w.plannedSets, 12); assert.equal(w.source, 'instructie');
  assert.deepEqual(w.exercises.map(e => [e.name, e.sets.length]), [['Genuflexiuni', 2], ['Flotări', 1]]);
  assert.deepEqual(w.exercises[1].sets[0], { reps: 15, load: 'corp', kg: null, at: NOW - DAY + 600000 });
  assert.deepEqual(w.music, [{ title: 'Ploaia', artist: 'Trupa', at: NOW - DAY + 30000 }]);
  assert.equal(b.more, true);
  const back = (await f.call('/insights/api/mars?days=30&before=' + b.from)).body;
  assert.deepEqual(back.activities.map(a => a.id), ['old']);
  assert.equal(back.week, null);
  assert.equal(back.more, false);
});

async function handle(f, uid, path, method = 'GET', body) {
  const { handleSiteApi } = await import('../site-api.mjs');
  const token = ['e30', Buffer.from(JSON.stringify({ sub: uid })).toString('base64url'), 'sig'].join('.');
  return handleSiteApi(new Request('https://forja.test' + path, { method, headers: { Authorization: 'Bearer ' + token }, ...(body ? { body } : {}) }), f.env, uid, { fetcher: f.fs.fetcher, now: NOW });
}
