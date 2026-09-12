import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, randomUUID, createHash } from 'node:crypto';
import { mkdtemp, rm, readdir, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { createResearchServer, MAX_BYTES, RETENTION_MS } from './server.mjs';

function sample() {
  return { schema_version: 1, run_id: randomUUID(),
    source: { app: 'com.forja.app.research', version: '3.7-research', data_mode: 'synthetic' },
    consent: { sleep: true, meals: true, activities: true, diagnostics: true },
    data: {
      sleep: [{ start_at: 1700000000000, end_at: 1700028800000, score: 84, movements: 7,
        deep_min: 95, light_min: 285, rem_min: 100, event_count: 4 }],
      meals: [{ recorded_at: 1700030000000, meal_type: 0, kcal: 450, protein_g: 25, carbs_g: 55, fat_g: 14, grams: 350 }],
      activities: [{ start_at: 1700031000000, end_at: 1700032800000, type: 'walk', distance_m: 2200.5, duration_s: 1800, kcal: 140 }],
      diagnostics: { sdk_int: 35, permissions: { camera: false, microphone: false, fine_location: false, background_location: false, images: false } }
    }
  };
}
async function lab(t) {
  const dataDir = await mkdtemp(join(tmpdir(), 'forja-research-test-'));
  const token = randomBytes(32).toString('hex'); let clock = 1700040000000;
  const server = await createResearchServer({ token, dataDir, now: () => clock });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); await new Promise(r => server.close(r)); await rm(dataDir, { recursive: true, force: true }); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const post = (payload, extra = {}) => fetch(`${base}/v1/research-export`, {
    method: 'POST', headers, body: typeof payload === 'string' ? payload : JSON.stringify(payload), ...extra });
  return { dataDir, base, headers, post, advance: ms => { clock += ms; } };
}

test('exact consented bytes arrive, can be retrieved and deleted', async t => {
  const l = await lab(t); const raw = JSON.stringify(sample(), null, 2);
  const response = await l.post(raw); assert.equal(response.status, 201);
  const receipt = await response.json();
  assert.equal(receipt.sha256, createHash('sha256').update(raw).digest('hex'));
  assert.equal(receipt.bytes, Buffer.byteLength(raw));
  assert.deepEqual(receipt.counts, { sleep: 1, meals: 1, activities: 1, diagnostics: 1 });
  const url = `${l.base}/v1/research-export/${receipt.receipt_id}`;
  const received = await fetch(url, { headers: l.headers }); assert.equal(await received.text(), raw);
  const mode = (await stat(join(l.dataDir, `${receipt.receipt_id}.json`))).mode & 0o777;
  assert.equal(mode, 0o600);
  assert.equal((await fetch(url, { method: 'DELETE', headers: l.headers })).status, 200);
  assert.equal((await fetch(url, { headers: l.headers })).status, 404);
});
test('missing or incorrect token cannot upload, read or delete', async t => {
  const l = await lab(t);
  for (const headers of [{ 'Content-Type': 'application/json' }, { 'Content-Type': 'application/json', Authorization: 'Bearer wrong' }])
    assert.equal((await l.post(sample(), { headers })).status, 401);
  const receipt = await (await l.post(sample())).json();
  for (const method of ['GET', 'DELETE']) assert.equal((await fetch(`${l.base}/v1/research-export/${receipt.receipt_id}`, { method })).status, 401);
  assert.equal((await readdir(l.dataDir)).length, 1);
});
test('unselected data and empty selection are rejected without storage', async t => {
  const l = await lab(t); const p = sample(); p.consent.sleep = false;
  assert.equal((await l.post(p)).status, 400);
  for (const k of Object.keys(p.consent)) p.consent[k] = false;
  p.data = {}; assert.equal((await l.post(p)).status, 400);
  assert.deepEqual(await readdir(l.dataDir), []);
});
test('a selected category must be present; one selected category is sufficient', async t => {
  const l = await lab(t); const p = sample(); delete p.data.meals;
  assert.equal((await l.post(p)).status, 400);
  p.consent = { sleep: true, meals: false, activities: false, diagnostics: false };
  p.data = { sleep: p.data.sleep }; assert.equal((await l.post(p)).status, 201);
});
test('unexpected phone data and free text are rejected', async t => {
  const l = await lab(t);
  for (const add of [p => { p.contacts = ['fake']; }, p => { p.data.sleep[0].transcript = 'fake'; },
    p => { p.data.activities[0].polyline = '0,0'; }, p => { p.data.meals[0].photoPath = '/fake'; },
    p => { p.data.diagnostics.permissions.contacts = true; }]) {
    const p = sample(); add(p); assert.equal((await l.post(p)).status, 400);
  }
  assert.deepEqual(await readdir(l.dataDir), []);
});
test('bounds, data types and invalid intervals are enforced', async t => {
  const l = await lab(t);
  for (const mutate of [p => { p.data.sleep[0].score = 101; }, p => { p.data.activities[0].distance_m = -1; },
    p => { p.data.meals[0].kcal = '450'; }, p => { p.data.sleep[0].end_at = 0; },
    p => { p.consent.sleep = 'true'; }, p => { p.data.meals = Array(51).fill(p.data.meals[0]); },
    p => { p.run_id = '../../outside'; }, p => { p.source.app = 'com.forja.app'; }]) {
    const p = sample(); mutate(p); assert.equal((await l.post(p)).status, 400);
  }
  assert.deepEqual(await readdir(l.dataDir), []);
});
test('malformed, oversized and non-JSON bodies are rejected', async t => {
  const l = await lab(t);
  assert.equal((await l.post('{')).status, 400);
  assert.equal((await l.post('x'.repeat(MAX_BYTES + 1))).status, 413);
  assert.equal((await l.post(sample(), { headers: { ...l.headers, 'Content-Type': 'text/plain' } })).status, 415);
  assert.deepEqual(await readdir(l.dataDir), []);
});
test('expired exports cannot be retrieved and are removed', async t => {
  const l = await lab(t); const receipt = await (await l.post(sample())).json();
  l.advance(RETENTION_MS + 1);
  assert.equal((await fetch(`${l.base}/v1/research-export/${receipt.receipt_id}`, { headers: l.headers })).status, 404);
  assert.deepEqual(await readdir(l.dataDir), []);
});
test('health endpoint reveals no data and unsupported routes are rejected', async t => {
  const l = await lab(t);
  assert.deepEqual(await (await fetch(`${l.base}/health`)).json(), { status: 'ok', schema_version: 1 });
  assert.equal((await fetch(`${l.base}/v1/research-export`, { headers: l.headers })).status, 404);
});
