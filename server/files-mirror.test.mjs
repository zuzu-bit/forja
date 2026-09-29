// Oglinda galeriei și a documentelor (pachetul C): urcarea pe părți, albumele, contorul, paginile, mutarea, ștergerea,
// revocarea; plus miniatura seifului de 24 h și secțiunea „inventar” cu v4.
import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { resetSiteCache } from './site-api.mjs';
import { fixture, NOW, MIN, HOUR, DAY, SIGNED } from './site/fixture.mjs';
import { MIRROR_FILE_MAX, R2_FREE_BYTES, MIRROR_CAP_DEFAULT, previewOf, budgetCall, BUDGET_PATH } from './files-mirror.mjs';

test.beforeEach(() => resetSiteCache());

const DEVICE = '11111111-2222-4333-8444-555555555555';
const sha = b => createHash('sha256').update(b).digest('hex');
const jpeg = (n, seed = 1) => { const b = new Uint8Array(n).fill(seed); b.set([255, 216, 255, 224]); return b; };
function put(f, uid, path, bytes, headers = {}) {
  return f.account(uid).fetch(new Request('https://forja.test' + path, { method: 'PUT', body: bytes, headers: {
    'x-forja-owner': uid, 'x-device-id': DEVICE, 'x-file-sha256': sha(bytes), 'content-type': 'application/octet-stream', ...headers } }));
}
const meta = (kind, name, album, takenAt, extra = {}) => ({ 'x-mirror-kind': kind, 'x-file-name': encodeURIComponent(name), 'x-file-album': encodeURIComponent(album),
  'x-media-type': kind === 'photo' ? 'image/jpeg' : kind === 'video' ? 'video/mp4' : 'application/pdf', 'x-taken-at': String(takenAt), ...extra });
async function photo(f, name, album, takenAt, uid = 'alice') {
  const id = randomUUID();
  assert.equal((await put(f, uid, `/v2/mirror/${id}`, jpeg(4000, takenAt % 200), meta('photo', name, album, takenAt, { 'x-width': '2048', 'x-height': '1536', 'x-orig-bytes': '3500000' }))).status, 201);
  assert.equal((await put(f, uid, `/v2/mirror/${id}/thumb`, jpeg(900, 3), meta('photo', name, album, takenAt))).status, 200);
  return id;
}
const get = async (f, path, uid = 'alice') => { const r = await f.doCall(uid, path); return { status: r.status, body: r.headers.get('content-type')?.includes('json') ? await r.json() : new Uint8Array(await r.arrayBuffer()), r }; };
const on = f => f.doCall('alice', '/v2/mirror/consent', 'POST', { on: true, contract: 4 });

test('mirror: the phone must turn it on with contract v4; parts are validated; an upload is idempotent', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture(), id = randomUUID();
  assert.equal((await put(f, 'alice', `/v2/mirror/${id}`, jpeg(100), meta('photo', 'a.jpg', 'Camera', NOW))).status, 403, 'no consent yet');
  assert.equal((await f.doCall('alice', '/v2/mirror/consent', 'POST', { on: true })).status, 400, 'on needs the contract version');
  assert.equal((await f.doCall('alice', '/v2/mirror/consent', 'POST', { on: true, contract: 3 })).status, 400);
  assert.equal((await on(f)).status, 200);
  assert.equal((await put(f, 'alice', `/v2/mirror/${id}`, new Uint8Array([1, 2, 3]), meta('photo', 'a.jpg', 'Camera', NOW))).status, 400, 'a photo comes as JPEG');
  assert.equal((await put(f, 'alice', `/v2/mirror/${id}`, jpeg(100), { ...meta('photo', 'a.jpg', 'Camera', NOW), 'x-file-sha256': 'x' })).status, 422);
  assert.equal((await put(f, 'alice', `/v2/mirror/${id}/poster`, jpeg(100), meta('photo', 'a.jpg', 'Camera', NOW))).status, 400, 'only videos have posters');
  assert.equal((await put(f, 'alice', `/v2/mirror/${id}/thumb`, jpeg(200 * 1024), meta('photo', 'a.jpg', 'Camera', NOW))).status, 413, 'the DO reads at most the thumbnail limit');
  assert.equal((await put(f, 'alice', `/v2/mirror/${id}`, jpeg(100), meta('photo', 'a.jpg', '', NOW))).status, 400, 'an album is required');
  const first = await put(f, 'alice', `/v2/mirror/${id}`, jpeg(100), meta('photo', 'a.jpg', 'Camera', NOW - HOUR));
  assert.equal(first.status, 201);
  const again = await put(f, 'alice', `/v2/mirror/${id}`, jpeg(100), meta('photo', 'a.jpg', 'Camera', NOW - HOUR));
  assert.equal(again.status, 200);
  assert.equal((await put(f, 'alice', `/v2/mirror/${id}`, jpeg(100), meta('video', 'a.mp4', 'Camera', NOW))).status, 409, 'an id keeps its kind');
  const s = (await get(f, '/v2/mirror/summary')).body;
  assert.equal(s.stats.items, 1); assert.equal(s.stats.bytes, 100); assert.equal(s.stats.count.photo, 1);
  assert.deepEqual(s.meter, { used: 100, cap: MIRROR_CAP_DEFAULT, free_tier: R2_FREE_BYTES, covers: 0, meals: null, server: null, server_limit: null }, 'no shared ledger in this account: the site says only what it knows');
  assert.ok(f.records.files.has(`_insights/alice/files/m/${id}`), 'under files/ so the revoke purge takes it');
});

test('mirror: albums like the phone folders, newest first by date taken, kind filter, search, pages', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture(); await on(f);
  const ids = [];
  for (let i = 0; i < 7; i++) ids.push(await photo(f, `IMG_${i}.jpg`, i < 4 ? 'Camera' : 'WhatsApp Images', NOW - (i + 1) * DAY));
  const vid = randomUUID();
  // A long video: only the poster and the thumbnail go up (the file is over 25 MB).
  assert.equal((await put(f, 'alice', `/v2/mirror/${vid}/poster`, jpeg(5000), meta('video', 'VID_1.mp4', 'Camera', NOW - 2 * HOUR, { 'x-duration-ms': '600000', 'x-orig-bytes': String(90e6) }))).status, 201);
  assert.equal((await put(f, 'alice', `/v2/mirror/${vid}/thumb`, jpeg(800), meta('video', 'VID_1.mp4', 'Camera', NOW - 2 * HOUR))).status, 200);
  const doc = randomUUID(), pdf = new TextEncoder().encode('%PDF-1.7 factura');
  assert.equal((await put(f, 'alice', `/v2/mirror/${doc}`, pdf, meta('file', 'Factura mai.pdf', 'Documents/Organizate/Facturi', NOW - 3 * DAY - HOUR))).status, 201);

  const s = (await get(f, '/v2/mirror/summary')).body;
  assert.deepEqual(s.albums.map(a => [a.group, a.album, a.count]), [['gallery', 'Camera', 5], ['docs', 'Documents/Organizate/Facturi', 1], ['gallery', 'WhatsApp Images', 3]]);
  assert.equal(s.albums[0].cover, vid, 'the cover is the newest item with a thumbnail');
  assert.deepEqual(s.stats.count, { photo: 7, video: 1, file: 1 });

  const all = (await get(f, '/v2/mirror?limit=4')).body;
  assert.deepEqual(all.items.map(i => i.name), ['VID_1.mp4', 'IMG_0.jpg', 'IMG_1.jpg', 'IMG_2.jpg']);
  assert.equal(all.total, 9);
  const v = all.items[0];
  assert.deepEqual([v.kind, v.file, v.poster, v.thumb, v.preview, v.orig_bytes, v.duration_ms], ['video', false, true, true, 'poster', 90e6, 600000]);
  assert.equal(all.items[1].width, 2048); assert.equal(all.items[1].preview, 'image');
  assert.ok(!('device_id' in all.items[0]) && !('parts' in all.items[0]), 'no internal fields');
  const page2 = (await get(f, '/v2/mirror?limit=4&after=' + encodeURIComponent(all.next))).body;
  assert.deepEqual(page2.items.map(i => i.name), ['Factura mai.pdf', 'IMG_3.jpg', 'IMG_4.jpg', 'IMG_5.jpg']);
  const page3 = (await get(f, '/v2/mirror?limit=4&after=' + encodeURIComponent(page2.next))).body;
  assert.deepEqual(page3.items.map(i => i.name), ['IMG_6.jpg']); assert.equal(page3.next, null);

  const wa = (await get(f, '/v2/mirror?group=gallery&album=' + encodeURIComponent('WhatsApp Images'))).body;
  assert.deepEqual(wa.items.map(i => i.name), ['IMG_4.jpg', 'IMG_5.jpg', 'IMG_6.jpg']); assert.equal(wa.total, 3);
  assert.deepEqual((await get(f, '/v2/mirror?kind=file')).body.items.map(i => [i.name, i.preview]), [['Factura mai.pdf', 'pdf']]);
  assert.deepEqual((await get(f, '/v2/mirror?group=docs')).body.items.map(i => i.name), ['Factura mai.pdf']);
  assert.deepEqual((await get(f, '/v2/mirror?q=factura')).body.items.map(i => i.name), ['Factura mai.pdf'], 'search is case-insensitive over name and album');
  assert.deepEqual((await get(f, '/v2/mirror?q=whatsapp&limit=2')).body.next, '2');
  assert.equal((await get(f, '/v2/mirror?kind=nope')).status, 400);
  assert.equal((await get(f, '/v2/mirror?album=Camera')).status, 400, 'an album needs its group');

  const file = await get(f, `/v2/mirror/${doc}`);
  assert.equal(file.status, 200); assert.deepEqual([...file.body], [...pdf], 'documents are byte-exact');
  assert.match(file.r.headers.get('content-disposition'), /attachment/);
  assert.equal((await get(f, `/v2/mirror/${vid}`)).status, 404, 'no file for an oversize video');
  assert.equal((await get(f, `/v2/mirror/${vid}/poster`)).r.headers.get('content-type'), 'image/jpeg');
  assert.equal((await get(f, `/v2/mirror/${doc}`, 'bob')).status, 404, 'another account has its own DO');
});

test('mirror: Inventar moves a photo (PATCH album), a deleted copy does not come back, the cap is honest, revoke purges', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture(); await on(f);
  const a = await photo(f, 'a.jpg', 'Camera', NOW - DAY), b = await photo(f, 'b.jpg', 'Camera', NOW - 2 * DAY);
  const moved = await f.account('alice').fetch(new Request(`https://forja.test/v2/mirror/${a}`, { method: 'PATCH', headers: { 'x-forja-owner': 'alice', 'x-device-id': DEVICE, 'content-type': 'application/json' }, body: JSON.stringify({ album: 'Munte', claim: true }) }));
  assert.equal(moved.status, 200);
  let s = (await get(f, '/v2/mirror/summary')).body;
  assert.deepEqual(s.albums.map(x => [x.album, x.count, x.cover]), [['Munte', 1, a], ['Camera', 1, b]], 'the Camera cover falls back to the next newest');
  assert.deepEqual((await get(f, '/v2/mirror?group=gallery&album=Munte')).body.items.map(i => i.id), [a]);
  const ids = (await f.account('alice').fetch(new Request('https://forja.test/v2/mirror/ids', { headers: { 'x-forja-owner': 'alice', 'x-device-id': DEVICE } }))).json();
  assert.deepEqual((await ids).ids.map(r => r.slice(1)).sort(), [['Camera', 'photo', 'ft', 1], ['Munte', 'photo', 'ft', 1]]);

  assert.equal((await f.doCall('alice', `/v2/mirror/${b}`, 'DELETE')).status, 200);
  assert.equal((await put(f, 'alice', `/v2/mirror/${b}`, jpeg(4000), meta('photo', 'b.jpg', 'Camera', NOW - 2 * DAY))).status, 410, 'a copy deleted from the site does not come back');
  s = (await get(f, '/v2/mirror/summary')).body;
  assert.deepEqual(s.albums.map(x => x.album), ['Munte']); assert.equal(s.stats.items, 1); assert.equal(s.stats.bytes, 4900);

  f.account('alice').env.MIRROR_CAP_BYTES = '10000';
  assert.equal((await put(f, 'alice', `/v2/mirror/${randomUUID()}`, jpeg(6000), meta('photo', 'c.jpg', 'Camera', NOW))).status, 507, 'full: the phone stops and says so');
  assert.equal((await get(f, '/v2/mirror/summary')).body.meter.cap, 10000);
  delete f.account('alice').env.MIRROR_CAP_BYTES;

  await f.records.put('_insights/alice/inventory/r1/f-0.jpg', 'cover');
  const r = await (await f.doCall('alice', '/v2/site/forget', 'POST')).json();
  assert.equal(r.forgotten.files, 1, 'the mirror row counts among the copies');
  assert.deepEqual([...f.records.files.keys()].filter(k => k.startsWith('_insights/alice/')), []);
  assert.equal((await f.account('alice').ctx.storage.list({ prefix: 'mf' })).size, 0);
  assert.equal((await put(f, 'alice', `/v2/mirror/${randomUUID()}`, jpeg(100), meta('photo', 'd.jpg', 'Camera', NOW))).status, 403, 'after revoke the phone must sign again');
});

test('mirror: folder covers for Inventar runs under inventory/, and the worker lets every mirror route through', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture(); await on(f);
  assert.equal((await put(f, 'alice', '/v2/mirror/cover/r1abc/c1f2e3-0', jpeg(3000))).status, 201);
  assert.equal((await put(f, 'alice', '/v2/mirror/cover/r1abc/c1f2e3-1', new Uint8Array(10))).status, 400);
  assert.ok(f.records.files.has('_insights/alice/inventory/r1abc/c1f2e3-0.jpg'));
  assert.equal((await get(f, '/v2/mirror/cover/r1abc/c1f2e3-0')).status, 200);
  assert.deepEqual(await (await f.doCall('alice', '/v2/mirror/cover/r1abc', 'DELETE')).json(), { deleted: 1 });
  assert.equal((await get(f, '/v2/mirror/cover/r1abc/c1f2e3-0')).status, 404);
  const src = await readFile(new URL('./insights-worker.mjs', import.meta.url), 'utf8');
  const allow = new RegExp(/\/\^\\\/v2\\\/mirror(.*?)\$\//.exec(src)[0].slice(1, -1));
  for (const p of ['/v2/mirror', '/v2/mirror/summary', '/v2/mirror/ids', '/v2/mirror/consent', `/v2/mirror/${randomUUID()}`, `/v2/mirror/${randomUUID()}/thumb`, `/v2/mirror/${randomUUID()}/poster`, '/v2/mirror/cover/r1/c-0', '/v2/mirror/cover/r1'])
    assert.ok(allow.test(p), p);
  assert.ok(!allow.test('/v2/mirror/../files'));
  assert.equal(previewOf({ kind: 'file', media_type: 'application/zip', parts: { file: {} } }), 'download');
  assert.ok(MIRROR_FILE_MAX === 25 * 1024 * 1024);
});

test('24 h vault: a small JPEG gallery copy is its own thumbnail (the tiles show the photo)', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture(), dev = DEVICE, id = randomUUID();
  await f.doCall('alice', `/v2/files/settings/${dev}`, 'POST', { enabled: true, photos: true, files: false });
  const bytes = jpeg(20000);
  const r = await put(f, 'alice', `/v2/files/${id}`, bytes, { 'x-file-kind': 'photo', 'x-file-name': 'a.jpg', 'x-file-folder': 'Galerie/Camera', 'x-media-type': 'image/jpeg' });
  assert.equal(r.status, 201);
  const item = await r.json();
  assert.equal(item.thumbnail, true); assert.ok(!('thumb_self' in item));
  const t2 = await get(f, `/v2/files/${id}/thumbnail`);
  assert.equal(t2.status, 200); assert.equal(t2.body.length, 20000); assert.equal(t2.r.headers.get('content-type'), 'image/jpeg');
  const big = randomUUID();
  const r2 = await put(f, 'alice', `/v2/files/${big}`, jpeg(200 * 1024), { 'x-file-kind': 'photo', 'x-file-name': 'b.jpg', 'x-file-folder': 'Galerie/Camera', 'x-media-type': 'image/jpeg' });
  assert.equal((await r2.json()).thumbnail, false, 'a large copy still needs its own thumbnail');
});

test('inventar section: v4 adds the mirror (albums, meter), the phone census and the games; v3 keeps the old reply', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture(); await on(f);
  await photo(f, 'a.jpg', 'Camera', NOW - DAY);
  f.fs.set('users/alice', { name: 'Lana', contract: SIGNED });
  f.fs.set('users/alice/settings/storage', { photos: { count: 12480, bytes: 38e9 }, videos: { count: 210, bytes: 9e9 }, docs: { loose: 214, organized: 96, bytes: 2e8, folders: 7 },
    gallery: { total: 12690, mirrored: 3200, waiting: 9490, state: 'wifi', cellular: false, lastAt: NOW - HOUR }, updatedAt: NOW - HOUR });
  f.fs.set('users/alice/games/zid', { levels: 15, unlocked: 6, cleared: 5, starsTotal: 12, endlessBest: 0, stars: { 1: 3, 2: 2, 3: 9 }, playedS: 5400, playedToday: 600, lastAt: NOW - HOUR,
    plays: [{ at: NOW - HOUR, level: 5, outcome: 'won', stars: 2, score: 1400, durationS: 180 }, { level: 1 }] });
  const v3 = (await f.call('/insights/api/inventar')).body;
  assert.deepEqual(Object.keys(v3), ['runs', 'vault']);
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 4, at: NOW - DAY } });
  resetSiteCache();
  const b = (await f.call('/insights/api/inventar')).body;
  assert.equal(b.mirror.stats.items, 1); assert.deepEqual(b.mirror.albums.map(a => a.album), ['Camera']);
  assert.deepEqual(b.mirror.meter, { used: 4900, cap: MIRROR_CAP_DEFAULT, free_tier: R2_FREE_BYTES, covers: 0, meals: null, server: null, server_limit: null }); assert.equal(b.mirror.consent.on, true);
  assert.deepEqual(b.storage.photos, { count: 12480, bytes: 38e9 }); assert.equal(b.storage.gallery.waiting, 9490); assert.equal(b.storage.docs.folders, 7);
  assert.deepEqual(b.games.map(g => [g.id, g.unlocked, g.cleared, g.starsTotal, g.playedS, g.plays.length]), [['zid', 6, 5, 12, 5400, 1]]);
  assert.deepEqual(b.games[0].stars, { 1: 3, 2: 2, 3: 3 }, 'stars are 0..3');
  f.fs.set('users/alice', { name: 'Lana', contract: { version: 4, at: NOW - DAY, revokedAt: NOW - MIN } });
  resetSiteCache();
  assert.deepEqual(Object.keys((await f.call('/insights/api/inventar')).body), ['runs', 'vault'], 'revoked: nothing of v4');
});

test('mirror: a deletion from the phone leaves no tombstone (a restored photo comes back); only this phone may do it', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture(); await on(f);
  const a = await photo(f, 'a.jpg', 'Camera', NOW - DAY);
  const del = (id, device) => f.account('alice').fetch(new Request(`https://forja.test/v2/mirror/${id}?from=phone`, { method: 'DELETE', headers: { 'x-forja-owner': 'alice', ...(device ? { 'x-device-id': device } : {}) } }));
  assert.equal((await del(a)).status, 400, 'the phone names itself');
  assert.equal((await del(a, '99999999-2222-4333-8444-555555555555')).status, 409, 'another phone cannot delete this copy');
  assert.equal((await del(a, DEVICE)).status, 200);
  assert.equal((await get(f, '/v2/mirror/summary')).body.stats.items, 0);
  const ids = await (await f.account('alice').fetch(new Request('https://forja.test/v2/mirror/ids', { headers: { 'x-forja-owner': 'alice', 'x-device-id': DEVICE } }))).json();
  assert.deepEqual(ids.gone, [], 'no tombstone');
  assert.equal((await put(f, 'alice', `/v2/mirror/${a}`, jpeg(4000), meta('photo', 'a.jpg', 'Camera', NOW - DAY))).status, 201, 'restored from the trash: it uploads again');
});

test('mirror: one shared R2 budget for every account (mirror, covers, meal photos); 507 past it; revoke gives the space back', async t => {
  t.mock.method(Date, 'now', () => NOW);
  const f = fixture();
  for (const uid of ['alice', 'bob']) {
    f.account(uid).env.INSIGHTS = f.env.INSIGHTS;
    assert.equal((await f.doCall(uid, '/v2/mirror/consent', 'POST', { on: true, contract: 4 })).status, 200);
  }
  const ledger = f.env.INSIGHTS.get(f.env.INSIGHTS.idFromName('r2-budget'));
  const probe = await ledger.fetch(new Request('https://internal' + BUDGET_PATH, { method: 'POST', headers: { 'x-forja-r2-budget': '1', 'x-forja-owner': 'alice' }, body: '{"uid":"alice"}' }));
  assert.equal(probe.status, 404, 'the ledger never answers an owner request');
  (await budgetCall(f.env, { uid: 'x' })); // creates the ledger instance
  const budgetDo = f.account('r2-budget'.slice('account:'.length)); // the fixture maps a DO name to account(name without „account:”)
  budgetDo.env.R2_BUDGET_BYTES = '14000';
  await photo(f, 'a.jpg', 'Camera', NOW - DAY, 'alice');
  await photo(f, 'b.jpg', 'Camera', NOW - DAY, 'bob');
  assert.equal((await put(f, 'alice', '/v2/mirror/cover/r1abc/c1f2e3-0', jpeg(3000))).status, 201);
  let b = await budgetCall(f.env, { uid: 'alice' });
  assert.equal(b.total, 4900 + 4900 + 3000); assert.deepEqual(b.mine, { mirror: 4900, covers: 3000, meals: 0 });
  const full = await put(f, 'bob', `/v2/mirror/${randomUUID()}`, jpeg(4000), meta('photo', 'c.jpg', 'Camera', NOW));
  assert.equal(full.status, 507, 'bob is far from his own cap, but the server is full');
  const s = (await get(f, '/v2/mirror/summary')).body.meter;
  assert.deepEqual([s.used, s.covers, s.meals, s.server, s.server_limit], [4900, 3000, 0, 12800, 14000]);
  assert.deepEqual(await (await f.doCall('alice', '/v2/mirror/cover/r1abc', 'DELETE')).json(), { deleted: 1 });
  assert.equal((await budgetCall(f.env, { uid: 'alice' })).mine.covers, 0);
  assert.equal((await f.doCall('alice', '/v2/site/forget', 'POST')).status, 200);
  b = await budgetCall(f.env, { uid: 'bob' });
  assert.equal(b.total, 4900, 'revoke gives the space back');
  assert.equal((await put(f, 'bob', `/v2/mirror/${randomUUID()}`, jpeg(4000), meta('photo', 'c.jpg', 'Camera', NOW))).status, 201);
});
