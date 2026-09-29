// Secțiunea „inventar” — mutată din site-api.test.mjs fără schimbări.
import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { resetSiteCache, SITE_RULES } from '../site-api.mjs';
import { applyUsageRollup, usageDays } from '../site-store.mjs';
import { localDate, localMidnight } from '../site-time.mjs';
import { fixture, seedCircle, Storage, NOW, MIN, HOUR, DAY, SIGNED } from './fixture.mjs';

test.beforeEach(() => resetSiteCache());

// ── Inventar, Livret ──
test('inventar: the last 20 runs newest first, and the gallery vault in the account', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  for (let i = 0; i < 22; i++) f.fs.set(`users/alice/inventory/run${i}`, { id: 'run' + i, kind: i % 2 ? 'docs' : 'photos', startedAt: NOW - (i + 1) * DAY, finishedAt: NOW - (i + 1) * DAY + 5 * MIN, appVersion: '4.4',
    scope: { mode: 'last', n: 500, label: 'Ultimele 500' }, dest: { label: 'Galerie · FORJA', path: 'PICTURES/FORJA' }, folders: [{ name: 'Munte', count: 40, bytes: 120000000 }], trash: { count: 3, bytes: 900000 }, moved: 40, failed: 0, freedBytes: null });
  f.fs.set('users/alice', { name: 'Lana', contract: SIGNED });
  const storage = f.account('alice').ctx.storage;
  await f.doCall('alice', '/v2/files'); // binds the owner
  await storage.put('cloud-file:1', { id: '1', received_at: NOW - HOUR, expires_at: NOW + 23 * HOUR, bytes: 10 });
  await storage.put('cloud-file:2', { id: '2', received_at: NOW - 2 * HOUR, expires_at: NOW + 22 * HOUR, bytes: 10 });
  await storage.put('cloud-file:3', { id: '3', received_at: NOW - 30 * HOUR, expires_at: NOW - 6 * HOUR, bytes: 10 });
  const b = (await f.call('/insights/api/inventar')).body;
  assert.equal(b.runs.length, 20);
  assert.equal(b.runs[0].id, 'run0'); assert.equal(b.runs[19].id, 'run19');
  assert.deepEqual(b.runs[0].dest, { label: 'Galerie · FORJA', path: 'PICTURES/FORJA' });
  assert.deepEqual(b.runs[0].folders, [{ name: 'Munte', count: 40, bytes: 120000000 }]);
  assert.equal(b.runs[0].freedBytes, null);
  assert.deepEqual(b.vault, { total: 2, latestAt: NOW - HOUR });
  assert.deepEqual((await f.call('/insights/api/inventar', { uid: 'nou' })).body, { runs: [], vault: { total: 0, latestAt: null } });
});
