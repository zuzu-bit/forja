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

test('diag/music also keeps the Inventar consent journal (want "consent"): admin "consent [n]" lists it, "music [n]" only counts it', async () => {
  const media = new Bucket(), env = { MEDIA: media, ADMIN_KEY: 'k' };
  const consent = (rung, result, ms, err) => event({ want: 'consent', rung, result, ms, err, kind: null, ver: null, pkg: 'com.google.android.providers.media.module' });
  const r = await call(env, '/v1/diag/music', { body: { device: 'SM-S911B · sdk 36 · oneui 170500', app: '4.4.1 (67)', events: [
    consent('APPLY_START', 'ok', 0, 'moves=9 trash=1'), consent('W_ASK', 'ok', 0, null), consent('W_GATE', 'ok', 900, 'r1a0 host=RESUMED entry=STARTED'),
    consent('W_SHOWN', 'ok', 140, 'r1a0'), consent('W_RESULT', 'ok', 4200, 'r1a0'), consent('T_RESULT', 'skipped', 2100, 'r2a0 stale'), event(),
  ] } });
  assert.deepEqual(await r.json(), { ok: true, stored: 7, dropped: 0 });
  const doc = await stored(media);
  assert.equal(doc.events.filter(e => e.want === 'consent').length, 6);
  assert.equal(doc.events[5].err, 'r2a0 stale');
  const admin = async line => (await (await route(new Request('https://api.forja.test/admin/api/cmd', { method: 'POST', headers: { 'X-Admin': 'k' }, body: line }), env, new URL('https://api.forja.test/admin/api/cmd'))).json()).out;
  // A large apply writes dozens of consent rows: the music list keeps showing the music attempts.
  const music = (await admin('music 3')).split('\n');
  assert.equal(music[0], 'Muzică: 1 încercare (ora României) · 1 reușită · acord Inventar: 6 rânduri («consent»)');
  assert.equal(music.length, 2);
  assert.match(music[1], /mymusic S_TOP .*com\.spotify\.music/);
  const out = (await admin('consent 6')).split('\n');
  assert.equal(out[0], 'Acord Inventar: 6 rânduri (ora României) · 1 aplicare');
  assert.equal(out.length, 7);
  assert.match(out[3], /consent W_GATE .*r1a0 host=RESUMED entry=STARTED$/);
  assert.equal(await admin('consent'), await admin('consent 30'));
  assert.match(await admin('help'), /consent \[n\]/);
  const empty = { MEDIA: new Bucket(), ADMIN_KEY: 'k' };
  assert.equal((await (await route(new Request('https://api.forja.test/admin/api/cmd', { method: 'POST', headers: { 'X-Admin': 'k' }, body: 'consent' }), empty, new URL('https://api.forja.test/admin/api/cmd'))).json()).out, 'Niciun rând de acord primit încă.');
  const bad = await call(env, '/v1/diag/music', { body: { events: [consent('W RESULT', 'ok', 1, null), event({ want: 'consentx' })] } });
  assert.deepEqual(await bad.json(), { ok: true, stored: 0, dropped: 2 }, 'rung shape and want are still whitelisted');
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
