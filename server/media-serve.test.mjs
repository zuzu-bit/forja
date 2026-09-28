import test from 'node:test';
import assert from 'node:assert/strict';
import { mediaType, mediaCacheControl, mediaGetOptions, mediaResponse } from './media-serve.mjs';

const req = (range) => new Request('https://x/media/k', { headers: range ? { range } : {} });
const obj = (size, range) => ({ size, range, body: new Uint8Array(range ? (range.length ?? range.suffix ?? size - range.offset) : size) });

test('media: tipul după extensie și cache-ul — manifestul JSON 5 min, restul 7 zile imuabil', () => {
  assert.equal(mediaType('short_px_1.mp4'), 'video/mp4');
  assert.equal(mediaType('shorts_manifest.json'), 'application/json; charset=utf-8');
  assert.equal(mediaType('nut_scan.jpg'), 'image/jpeg');
  assert.equal(mediaType('fara_extensie'), 'image/jpeg');
  assert.equal(mediaCacheControl('shorts_manifest.json'), 'public, max-age=300');
  assert.equal(mediaCacheControl('short_px_1.mp4'), 'public, max-age=604800, immutable');
});

test('media: fără Range → 200 cu Content-Length; cu Range → 206 cu Content-Range (offset/lungime și sufix)', () => {
  assert.equal(mediaGetOptions(req()), undefined);
  assert.ok(mediaGetOptions(req('bytes=0-99')).range);
  let r = mediaResponse(obj(1000), 'short_a.mp4', req());
  assert.equal(r.status, 200);
  assert.equal(r.headers.get('content-length'), '1000');
  assert.equal(r.headers.get('accept-ranges'), 'bytes');
  r = mediaResponse(obj(1000, { offset: 100, length: 200 }), 'short_a.mp4', req('bytes=100-299'));
  assert.equal(r.status, 206);
  assert.equal(r.headers.get('content-range'), 'bytes 100-299/1000');
  assert.equal(r.headers.get('content-length'), '200');
  r = mediaResponse(obj(1000, { offset: 900 }), 'short_a.mp4', req('bytes=900-'));
  assert.equal(r.headers.get('content-range'), 'bytes 900-999/1000');
  r = mediaResponse(obj(1000, { suffix: 50 }), 'short_a.mp4', req('bytes=-50'));
  assert.equal(r.headers.get('content-range'), 'bytes 950-999/1000');
  assert.equal(r.headers.get('content-type'), 'video/mp4');
});
