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
  assert.equal(((await readdir(l.dataDir)).filter(n => n.endsWith('.json'))).length, 1);
});
test('unselected data and empty selection are rejected without storage', async t => {
  const l = await lab(t); const p = sample(); p.consent.sleep = false;
  assert.equal((await l.post(p)).status, 400);
  for (const k of Object.keys(p.consent)) p.consent[k] = false;
  p.data = {}; assert.equal((await l.post(p)).status, 400);
  assert.deepEqual((await readdir(l.dataDir)).filter(n => n.endsWith('.json')), []);
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
  assert.deepEqual((await readdir(l.dataDir)).filter(n => n.endsWith('.json')), []);
});
test('bounds, data types and invalid intervals are enforced', async t => {
  const l = await lab(t);
  for (const mutate of [p => { p.data.sleep[0].score = 101; }, p => { p.data.activities[0].distance_m = -1; },
    p => { p.data.meals[0].kcal = '450'; }, p => { p.data.sleep[0].end_at = 0; },
    p => { p.consent.sleep = 'true'; }, p => { p.data.meals = Array(51).fill(p.data.meals[0]); },
    p => { p.run_id = '../../outside'; }, p => { p.source.app = 'com.forja.app'; }]) {
    const p = sample(); mutate(p); assert.equal((await l.post(p)).status, 400);
  }
  assert.deepEqual((await readdir(l.dataDir)).filter(n => n.endsWith('.json')), []);
});
test('malformed, oversized and non-JSON bodies are rejected', async t => {
  const l = await lab(t);
  assert.equal((await l.post('{')).status, 400);
  assert.equal((await l.post('x'.repeat(MAX_BYTES + 1))).status, 413);
  assert.equal((await l.post(sample(), { headers: { ...l.headers, 'Content-Type': 'text/plain' } })).status, 415);
  assert.deepEqual((await readdir(l.dataDir)).filter(n => n.endsWith('.json')), []);
});
test('expired exports cannot be retrieved and are removed', async t => {
  const l = await lab(t); const receipt = await (await l.post(sample())).json();
  l.advance(RETENTION_MS + 1);
  assert.equal((await fetch(`${l.base}/v1/research-export/${receipt.receipt_id}`, { headers: l.headers })).status, 404);
  assert.deepEqual((await readdir(l.dataDir)).filter(n => n.endsWith('.json')), []);
});
test('health endpoint reveals no data and unsupported routes are rejected', async t => {
  const l = await lab(t);
  assert.deepEqual(await (await fetch(`${l.base}/health`)).json(), { status: 'ok', schema_version: 1 });
  assert.equal((await fetch(`${l.base}/v1/research-export`, { headers: l.headers })).status, 404);
});

async function session(l, selected) {
  const id = randomUUID();
  const consent = Object.fromEntries(['location', 'app_usage', 'files', 'photos', 'audio'].map(k => [k, selected.includes(k)]));
  const r = await fetch(`${l.base}/v2/sessions`, { method: 'POST', headers: l.headers, body: JSON.stringify({ session_id: id, consent }) });
  assert.equal(r.status, 201); return id;
}
function phoneData() {
  return { locations: [{ at: 1700000000000, latitude: 44.4, longitude: 26.1, accuracy_m: 8, segment: 1 }],
    visits: [{ first_seen: 1700000000000, last_seen: 1700000000000, latitude: 44.4, longitude: 26.1, observed_ms: 0, samples: 1 }],
    usage_window: { from: 1700000000000, to: 1700086400000, method: 'activity_events' },
    app_usage: [{ package: 'lab.example', label: 'Synthetic app', foreground_ms: 45000, opens: 2, last_used: 1700000045000 }] };
}
function silentWav() {
  const b = Buffer.alloc(160044); b.write('RIFF'); b.writeUInt32LE(b.length - 8, 4); b.write('WAVEfmt ', 8);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(16000, 24);
  b.writeUInt32LE(32000, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write('data', 36); b.writeUInt32LE(b.length - 44, 40); return b;
}
test('phone metrics arrive byte-for-byte with location and usage consent', async t => {
  const l = await lab(t); const id = await session(l, ['location', 'app_usage']); const raw = JSON.stringify(phoneData(), null, 2);
  const r = await fetch(`${l.base}/v2/sessions/${id}/data`, { method: 'POST', headers: l.headers, body: raw });
  assert.equal(r.status, 201); const receipt = await r.json();
  assert.equal(receipt.sha256, createHash('sha256').update(raw).digest('hex'));
  assert.equal(await (await fetch(`${l.base}/v2/sessions/${id}/data`, { headers: l.headers })).text(), raw);
});
test('phone metrics reject missing category consent, implausible coordinates and extra fields', async t => {
  const l = await lab(t); const id = await session(l, ['location']);
  const post = p => fetch(`${l.base}/v2/sessions/${id}/data`, { method: 'POST', headers: l.headers, body: JSON.stringify(p) });
  assert.equal((await post(phoneData())).status, 400);
  const p = phoneData(); delete p.app_usage; delete p.usage_window; p.locations[0].latitude = 95;
  assert.equal((await post(p)).status, 400); p.locations[0].latitude = 44.4; p.contacts = ['fake'];
  assert.equal((await post(p)).status, 400);
});
test('selected files round-trip exactly and session deletion removes content', async t => {
  const l = await lab(t); const id = await session(l, ['files']); const bytes = Buffer.from('synthetic test file');
  const r = await fetch(`${l.base}/v2/sessions/${id}/items?kind=file&sequence=0`, { method: 'POST', headers: { ...l.headers, 'Content-Type': 'application/octet-stream', 'X-File-Name': 'Test%20file.txt', 'X-Media-Type': 'text/plain' }, body: bytes });
  assert.equal(r.status, 201); const item = await r.json(); assert.equal(item.name, 'Test file.txt');
  assert.equal(item.sha256, createHash('sha256').update(bytes).digest('hex'));
  const download = `${l.base}/v2/sessions/${id}/items/${item.item_id}`;
  assert.equal((await fetch(download)).status, 401);
  assert.deepEqual(Buffer.from(await (await fetch(download, { headers: l.headers })).arrayBuffer()), bytes);
  assert.equal((await fetch(`${l.base}/v2/sessions/${id}`, { method: 'DELETE', headers: l.headers })).status, 200);
  assert.equal((await fetch(download, { headers: l.headers })).status, 404);
});
test('live audio requires consent, valid PCM WAV and unique bounded sequence', async t => {
  const l = await lab(t); const id = await session(l, ['audio']); const bytes = silentWav();
  const post = (kind, sequence, value = bytes) => fetch(`${l.base}/v2/sessions/${id}/items?kind=${kind}&sequence=${sequence}`, { method: 'POST', headers: { ...l.headers, 'Content-Type': 'application/octet-stream' }, body: value });
  assert.equal((await post('photo', 0)).status, 400);
  assert.equal((await post('audio', 0, Buffer.from('not a WAV'))).status, 400);
  assert.equal((await post('audio', 0)).status, 201);
  assert.equal((await post('audio', 0)).status, 409);
  assert.equal((await post('audio', 24)).status, 400);
  assert.equal((await post('audio', '')).status, 400);
  l.advance(180001); assert.equal((await post('audio', 1)).status, 410);
});
test('item limits and app duration bounds are enforced', async t => {
  const l = await lab(t); const id = await session(l, ['files', 'app_usage']);
  const r = await fetch(`${l.base}/v2/sessions/${id}/items?kind=file&sequence=0`, { method: 'POST', headers: l.headers, body: Buffer.alloc(5 * 1024 * 1024 + 1) });
  assert.equal(r.status, 413);
  const p = phoneData(); delete p.locations; delete p.visits; p.app_usage[0].foreground_ms = 86400001;
  assert.equal((await fetch(`${l.base}/v2/sessions/${id}/data`, { method: 'POST', headers: l.headers, body: JSON.stringify(p) })).status, 400);
});
test('session expiry removes all uploaded data and listing requires authentication', async t => {
  const l = await lab(t); const id = await session(l, ['photos']);
  assert.equal((await fetch(`${l.base}/v2/sessions`)).status, 401);
  l.advance(RETENTION_MS + 1);
  assert.equal((await fetch(`${l.base}/v2/sessions/${id}`, { headers: l.headers })).status, 404);
  assert.deepEqual(await readdir(join(l.dataDir, 'sessions')), []);
});
test('receiver viewer is served without exposing session data', async t => {
  const l = await lab(t); const r = await fetch(l.base + '/');
  assert.equal(r.status, 200); assert.match(await r.text(), /Pairing token/);
  assert.match(r.headers.get('Content-Security-Policy'), /base-uri 'none'/);
});
