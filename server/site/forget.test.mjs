// Revocarea: POST /v2/site/forget șterge din DO și R2 ce ține de site (timpul pe ecran, ziua pe hartă, copiile galeriei,
// coperțile Inventarului), doar pentru contul care cere. Plus cusătura site-location.mjs.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { resetSiteCache } from '../site-api.mjs';
import { localDate } from '../site-time.mjs';
import { applyLocationRollup, LOC_DAY_PREFIX } from '../site-location.mjs';
import { fixture, NOW, HOUR, DAY } from './fixture.mjs';

test.beforeEach(() => resetSiteCache());

test('site forget: usage-day, loc-day, gallery copies and inventory covers go; other accounts and sessions stay', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  await f.doCall('alice', '/v2/files'); // binds the owner
  const storage = f.account('alice').ctx.storage;
  const today = localDate(NOW), yesterday = localDate(NOW - DAY);
  for (const date of [today, yesterday]) await storage.put('usage-day:' + date, { date, updated_at: NOW - HOUR, apps: { 'com.x': { label: 'X', ms: 600000, opens: 2 } } });
  await storage.put('usage-last:sess', { at: NOW });
  await storage.put(LOC_DAY_PREFIX + today, { date: today, track: [] });
  await storage.put('cloud-file:1', { id: '1', key: '_insights/alice/files/1', received_at: NOW - HOUR, expires_at: NOW + 23 * HOUR, bytes: 10 });
  await storage.put('file-staging:2', { id: '2', key: '_insights/alice/files/2', expires_at: NOW + HOUR });
  await f.records.put('_insights/alice/files/1', 'a'); await f.records.put('_insights/alice/files/1.thumb', 't');
  await f.records.put('_insights/alice/files/2', 'b');
  await f.records.put('_insights/alice/inventory/run1/f1-0.jpg', 'c');
  await f.records.put('_insights/alice/sess/item', 'session item');
  await f.records.put('_insights/bob/files/9', 'other account');
  assert.equal((await f.call('/insights/api/paza')).body.days.length, 2);
  assert.equal((await f.call('/insights/api/inventar')).body.vault.total, 1);

  assert.equal((await f.doCall('alice', '/v2/site/forget')).status, 405, 'POST only');
  const r = await f.doCall('alice', '/v2/site/forget', 'POST');
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { forgotten: { usageDays: 2, locDays: 1, files: 1, objects: 1 } });
  for (const prefix of ['usage-day:', 'usage-last:', LOC_DAY_PREFIX, 'cloud-file:', 'file-staging:']) assert.equal((await storage.list({ prefix })).size, 0, prefix);
  assert.ok(await storage.get('file-gone:1'), 'a delayed retry cannot bring the copy back');
  assert.ok(await storage.get('file-gone:2'));
  assert.deepEqual([...f.records.files.keys()].sort(), ['_insights/alice/sess/item', '_insights/bob/files/9'], 'sessions go through their own DELETE; other accounts untouched');
  resetSiteCache();
  assert.deepEqual((await f.call('/insights/api/paza')).body, { updated_at: null, days: [] });
  assert.deepEqual((await f.call('/insights/api/inventar')).body.vault, { total: 0, latestAt: null });
  assert.deepEqual(await (await f.doCall('alice', '/v2/site/forget', 'POST')).json(), { forgotten: { usageDays: 0, locDays: 0, files: 0, objects: 0 } }, 'idempotent');
  assert.equal((await f.doCall('bob', '/v2/site/forget', 'POST')).status, 200, 'each account forgets only its own DO');
});

test('site forget is routed by the worker to the account DO; site-location ignores malformed rows', async () => {
  const src = await readFile(new URL('../insights-worker.mjs', import.meta.url), 'utf8');
  assert(src.includes("path !== '/v2/site/forget'"), 'the /v2 allowlist lets the route through to the account DO');
  const store = await readFile(new URL('../insights-store.mjs', import.meta.url), 'utf8');
  assert(store.indexOf('applyLocationRollup(this.ctx.storage') > store.indexOf('applyUsageRollup(this.ctx.storage'), 'called from the session data handler');
  const writes = [];
  const storage = { put: (...a) => writes.push(a), get: async () => undefined, list: async () => new Map(), delete: async () => {} };
  assert.equal(await applyLocationRollup(storage, 'sess', { locations: [{ lat: 44.4, lng: 26.1, at: NOW }], visits: [] }, NOW), 0, 'a row without latitude/longitude is not a position (pachetul A: site/sec-cerc.test.mjs)');
  assert.equal(writes.length, 0);
});
