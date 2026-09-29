import test from 'node:test';
import assert from 'node:assert/strict';
import { route } from './worker.js';
import { cleanEvent, userTag, MUSIC_DIAG } from './music-diag.mjs';

class Bucket {
  files = new Map();
  async put(key, value, opts = {}) { this.files.set(key, { text: typeof value === 'string' ? value : new TextDecoder().decode(value), httpMetadata: opts.httpMetadata || {} }); }
  async get(key) { const f = this.files.get(key); return f ? { text: async () => f.text } : null; }
  async head(key) { return this.files.has(key) ? { key, size: this.files.get(key).text.length } : null; }
  async delete(key) { this.files.delete(key); }
  async list({ prefix = '' } = {}) { return { objects: [...this.files.keys()].filter(k => k.startsWith(prefix)).map(key => ({ key, size: this.files.get(key).text.length })), truncated: false }; }
}
const NOW = Date.now();
const event = (patch = {}) => ({ at: NOW - 5000, want: 'mymusic', rung: 'S_TOP', pkg: 'com.spotify.music', ver: '9.0.62', kind: 'music', result: 'ok', ms: 1180, err: null, ...patch });
function call(env, path, { method = 'POST', body, uid = 'lana', headers = {} } = {}) {
  const init = { method, headers: { 'content-type': 'application/json', ...headers } };
  if (body !== undefined) init.body = typeof body === 'string' ? body : JSON.stringify(body);
  const request = new Request('https://api.forja.test' + path, init);
  return route(request, env, new URL(request.url), null, async () => uid);
}
const stored = async (media, uid = 'lana') => JSON.parse(media.files.get('_admin/music/' + await userTag(uid) + '.json').text);

test('diag/music: the account uploads start attempts; only whitelisted fields are kept, never titles', async () => {
  const media = new Bucket(), env = { MEDIA: media };
  const r = await call(env, '/v1/diag/music', { body: { device: { model: 'SM-S911B', sdk: 35, oneui: '70000', title: 'Fetele care ard' }, app: '4.4 (66)',
    events: [event(), event({ want: 'workout', rung: 'V_PFS_TOP', result: 'needs_tap', kind: null, pkg: null, ms: 250.4, title: 'Fetele care ard', artist: 'Trupa', mediaId: 'spotify:track:abc' })] } });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true, stored: 2, dropped: 0 });
  const doc = await stored(media);
  assert.equal(doc.u, await userTag('lana'));
  assert.equal(doc.u.length, 16); assert(!media.files.keys().next().value.includes('lana'), 'the key is a tag, not the uid');
  assert.equal(doc.events.length, 2);
  assert.deepEqual(Object.keys(doc.events[1]).sort(), ['app', 'at', 'device', 'err', 'kind', 'ms', 'pkg', 'result', 'rung', 'rx', 'ver', 'want']);
  assert.deepEqual(doc.events[1].device, { model: 'SM-S911B', sdk: 35, oneui: '70000' }, 'no song fields hide in the device either');
  assert.equal(doc.events[1].ms, 250); assert.equal(doc.events[1].app, '4.4 (66)');
  const text = JSON.stringify(doc);
  for (const secret of ['Fetele', 'Trupa', 'spotify:track', 'artist', 'mediaId', 'title']) assert(!text.includes(secret), secret);
});

test('diag/music: device metadata cannot smuggle long text; invalid events are dropped, bad bodies refused', async () => {
  const media = new Bucket(), env = { MEDIA: media };
  let r = await call(env, '/v1/diag/music', { body: { device: 'x'.repeat(500) + '\n', app: { v: 66, 'bad key': 'x' }, events: [event(), event({ result: 'great' }), event({ want: 'shuffle' }), event({ at: NOW - 40 * 86400000 }), event({ rung: 'S TOP' }), event({ pkg: 'com.spotify/../x' }), event({ kind: 'podcast' }), event({ ms: -1 })] } });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true, stored: 1, dropped: 7 });
  const doc = await stored(media);
  assert.equal(doc.events[0].device.length, 120); assert.deepEqual(doc.events[0].app, { v: 66 });
  // Well formed, but nothing usable (too old, P5's empty `want` fallback): 200, so the phone drops the batch from its queue.
  const before = (await stored(media)).events.length;
  r = await call(env, '/v1/diag/music', { body: { events: [event({ result: 'nope' }), event({ at: NOW - 31 * 86400000 }), event({ want: '' })] } });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true, stored: 0, dropped: 3 });
  assert.equal((await stored(media)).events.length, before, 'nothing is written for an empty batch');
  assert.equal((await call(env, '/v1/diag/music', { body: { events: 'x' } })).status, 400);
  assert.equal((await call(env, '/v1/diag/music', { body: { events: [] } })).status, 400);
  assert.equal((await call(env, '/v1/diag/music', { body: { events: Array.from({ length: 51 }, () => event()) } })).status, 400);
  assert.equal((await call(env, '/v1/diag/music', { body: '{nu e json' })).status, 400);
  assert.equal((await call(env, '/v1/diag/music', { body: { events: [event({ err: 'x'.repeat(20000) })] } })).status, 413);
  assert.equal((await call(env, '/v1/diag/music', { method: 'GET' })).status, 405);
  assert.equal((await call(env, '/v1/diag/music', { body: { events: [event()] }, uid: null })).status, 401);
  assert.equal((await call({}, '/v1/diag/music', { body: { events: [event()] } })).status, 503);
  assert.equal(cleanEvent(event({ err: 'linia 1\nlinia 2' })).err, 'linia 1 linia 2');
  assert.equal(cleanEvent(event({ ms: 9e9 })).ms, 600000);
});

test('diag/music keeps the last 500 events per account, accounts apart; admin "music [n]" prints them', async () => {
  const media = new Bucket(), env = { MEDIA: media, ADMIN_KEY: 'k' };
  for (let b = 0; b < 11; b++) {
    const r = await call(env, '/v1/diag/music', { body: { device: 'SM-S911B', app: '4.4', events: Array.from({ length: 50 }, (_, i) => event({ at: NOW - 600000 + b * 50 + i, ms: b * 50 + i })) } });
    assert.equal(r.status, 200);
  }
  let doc = await stored(media);
  assert.equal(doc.events.length, MUSIC_DIAG.keep); assert.equal(doc.events[0].ms, 50, 'the oldest 50 left');
  await call(env, '/v1/diag/music', { uid: 'ana', body: { device: 'Pixel 8', events: [event({ want: 'probe', rung: 'K_PLAY', result: 'refused', err: 'no session' })] } });
  assert.equal((await stored(media, 'ana')).events.length, 1);
  assert.equal((await stored(media)).events.length, 500);
  const admin = async line => (await (await route(new Request('https://api.forja.test/admin/api/cmd', { method: 'POST', headers: { 'X-Admin': 'k' }, body: line }), env, new URL('https://api.forja.test/admin/api/cmd'))).json()).out;
  assert.match(await admin('help'), /music \[n\]/);
  const out = await admin('music 3');
  const lines = out.split('\n');
  assert.equal(lines[0], 'Muzică: 3 încercări (ora României) · 2 reușite');
  assert.match((await admin('music 1')).split('\n')[0], /^Muzică: 1 încercare \(ora României\) · 0 reușite$/);
  assert.match((await admin('music 25')).split('\n')[0], /^Muzică: 25 de încercări /);
  assert.equal(lines.length, 4);
  assert.match(lines.at(-1), /probe\s+K_PLAY .*refused .*Pixel 8 · no session$/);
  assert(!out.includes('lana') && !out.includes('ana '), 'no uid in the admin output');
  assert.equal((await (await route(new Request('https://api.forja.test/admin/api/cmd', { method: 'POST', headers: { 'X-Admin': 'wrong' }, body: 'music' }), env, new URL('https://api.forja.test/admin/api/cmd'))).status), 403);
  assert.equal(await admin('music'), (await admin('music 30')));
  assert.equal(await (async () => { const e = { MEDIA: new Bucket(), ADMIN_KEY: 'k' }; return (await (await route(new Request('https://api.forja.test/admin/api/cmd', { method: 'POST', headers: { 'X-Admin': 'k' }, body: 'music' }), e, new URL('https://api.forja.test/admin/api/cmd'))).json()).out; })(), 'Nicio încercare de muzică primită încă.');
});

test('diag/music: the 4.4.1 ENV, RET and QUEUE rows pass the existing validator and are stored as sent (no server change)', async () => {
  const media = new Bucket(), env = { MEDIA: media, ADMIN_KEY: 'k' };
  const env1 = 'v1 la:0 pi:1 inst:sp ses:yt key:yt hist:sp last:sp pref:sp top:q idsh:spotify:track:22 fg:1 ex:-';
  const rows = [
    event({ at: NOW - 9000, want: 'workout', rung: 'ENV', pkg: null, ver: null, kind: null, result: 'skipped', ms: 212, err: env1 }),
    event({ at: NOW - 8000, want: 'workout', rung: 'V_LIKED_PLAY', kind: 'music', result: 'ok', ms: 1450, err: 'sub woke' }),
    event({ at: NOW - 7000, want: 'workout', rung: 'RET', kind: null, result: 'ok', ms: 4600, err: 'auto' }),
    event({ at: NOW - 6000, want: 'workout', rung: 'RET', kind: null, result: 'timeout', ms: 3900, err: 'none cancel:60' }),
    event({ at: NOW - 5000, want: 'mymusic', rung: 'RET', kind: null, result: 'ok', ms: 15200, err: 'back' }),
    event({ at: NOW - 4000, want: 'workout', rung: 'QUEUE', kind: 'music', result: 'refused', ms: 0, err: 'n:4 refused' }),
    event({ at: NOW - 3000, want: 'workout', rung: 'QUEUE', kind: 'music', result: 'ok', ms: 0, err: 'n:12 workout-end' }),
  ];
  assert(env1.length <= 120, 'the ENV line fits the app limit');
  const r = await call(env, '/v1/diag/music', { body: { device: 'samsung SM-S911B · sdk 36 · oneui 170500', app: '4.4.1 (67)', events: rows } });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true, stored: rows.length, dropped: 0 });
  const doc = await stored(media);
  assert.deepEqual(doc.events.map(e => [e.rung, e.pkg, e.result, e.ms, e.err]), rows.map(e => [e.rung, e.pkg, e.result, e.ms, e.err]));
  // The admin report (`music 150`) shows them, with the ENV codes intact.
  const out = (await (await route(new Request('https://api.forja.test/admin/api/cmd', { method: 'POST', headers: { 'X-Admin': 'k' }, body: 'music 10' }), env, new URL('https://api.forja.test/admin/api/cmd'))).json()).out;
  assert.match(out, /workout ENV .*skipped .*· v1 la:0 pi:1 inst:sp ses:yt key:yt .*ex:-$/m);
  assert.match(out, /RET .*timeout .*· none cancel:60$/m);
  assert.match(out, /QUEUE .*refused .*· n:4 refused$/m);
});
