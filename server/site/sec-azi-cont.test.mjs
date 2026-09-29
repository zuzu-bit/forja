// „azi” și „cont”, plus poarta contractului — mutate din site-api.test.mjs.
import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { resetSiteCache, SITE_RULES, contractGate } from '../site-api.mjs';
import { applyUsageRollup, usageDays } from '../site-store.mjs';
import { localDate, localMidnight } from '../site-time.mjs';
import { fixture, seedCircle, Storage, NOW, MIN, HOUR, DAY, SIGNED } from './fixture.mjs';

test.beforeEach(() => resetSiteCache());

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
  assert.deepEqual(L.cont, { key: 'cont', state: 'stale', lastAt: NOW - DAY, count: null }, 'v3 keeps running, v4 is still to be signed');
  resetSiteCache();
  f.fs.set('users/alice', { ...f.fs.docs.get('users/alice'), contract: { version: 4, at: NOW - DAY } });
  assert.deepEqual((await f.call('/insights/api/azi')).body.links.at(-1), { key: 'cont', state: 'on', lastAt: NOW - DAY, count: null }, 'v4 is the current contract');
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
  assert.deepEqual(b.contract, { version: 3, at: NOW - 3 * DAY, revokedAt: null, current: 4 });
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
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 4, at: NOW - DAY, revokedAt: NOW - 5 * DAY } });
  assert.deepEqual((await f.call('/insights/api/cont')).body.contract, { version: 4, at: NOW - DAY, revokedAt: null, current: 4 });
  assert.equal((await f.call('/insights/api/azi')).body.links.at(-1).state, 'on');
  resetSiteCache();
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 4, at: NOW - 5 * DAY, revokedAt: NOW - DAY } });
  assert.deepEqual((await f.call('/insights/api/cont')).body.contract, { version: 4, at: NOW - 5 * DAY, revokedAt: NOW - DAY, current: 4 });
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
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 4, at: NOW - HOUR } });
  assert.deepEqual(await read(), shown, 'v4 covers everything v3 did');
});

test('contractGate(raw, min = 3): v3 keeps every v3 feature on after the bump to v4; v4-only features ask for min 4', () => {
  assert.equal(SITE_RULES.contract_current, 4);
  const v3 = { version: 3, at: NOW - DAY }, v4 = { version: 4, at: NOW - DAY };
  assert.equal(contractGate(v3), true); assert.equal(contractGate(v3, 3), true); assert.equal(contractGate(v3, 4), false);
  assert.equal(contractGate(v4), true); assert.equal(contractGate(v4, 4), true);
  assert.equal(contractGate({ version: 2, at: NOW - DAY }), false);
  assert.equal(contractGate({ ...v4, revokedAt: NOW - HOUR }, 4), false, 'revoked after signing');
  assert.equal(contractGate({ ...v4, revokedAt: NOW - 2 * DAY }, 4), true, 'an older revoke is history');
  assert.equal(contractGate(undefined), false); assert.equal(contractGate({ version: 4 }), false, 'no signing time');
});
