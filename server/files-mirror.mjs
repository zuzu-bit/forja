// FORJA mirror (pachetul C) — oglinda galeriei și a documentelor, cât ai contul (contract v4).
//
// Spre deosebire de seiful de 24 h (files-vault.mjs), aici stau copiile reale: fiecare poză ca JPEG de ~2048 px pe latura
// lungă (data EXIF păstrată de telefon), fiecare video ca poster + fișierul însuși când are cel mult 25 MB, fiecare document
// octet cu octet (tot ≤ 25 MB). Albumele sunt dosarele din telefon (BUCKET_DISPLAY_NAME la galerie, calea din folderul ales la
// documente). Nimic nu expiră singur: revocarea (POST /v2/site/forget) șterge tot, pentru că R2 ține totul sub
// `_insights/{uid}/files/m/` iar rândurile `mf*` se șterg acolo. Coperțile Inventarului stau sub `_insights/{uid}/inventory/`.
//
// Rândurile din DO (un cont = un DO, proprietarul verificat):
//   mf:{id}                          rândul (metadate, părțile, octeții)
//   mf-t:{inv}:{id}                  indexul „cele mai noi primele” (inv = 9999999999999 − data făcută, 13 cifre)
//   mf-k:{kind}:{inv}:{id}           același index, pe fel (photo · video · file)
//   mf-a:{albumKey}:{inv}:{id}       același index, pe album (albumKey = 16 hex din SHA-256 de „grup/album”)
//   mf-gone:{id}                     o copie ștearsă (de pe site sau de pe telefon) nu revine dintr-o reîncercare întârziată
//   mf-stats · mf-albums · mf-consent · mf-budget
// Contorul de spațiu e cinstit: octeții tuturor părților urcate (fișier + miniatură + poster), față de plafonul oglinzii
// (MIRROR_CAP_BYTES, implicit 8 GB) și de cei 10 GB gratuiți ai Cloudflare R2, care sunt ai întregului cont Cloudflare FORJA.
import { bad, keys, idPattern } from './phone-schema.mjs';

export const MIRROR_FILE_MAX = 25 * 1024 * 1024;
export const MIRROR_THUMB_MAX = 96 * 1024;
export const MIRROR_POSTER_MAX = 2 * 1024 * 1024;
export const MIRROR_COVER_MAX = 64 * 1024;
export const MIRROR_CAP_DEFAULT = 8e9;
export const R2_FREE_BYTES = 10e9;
export const MIRROR_DAILY_PARTS = 8000;
export const MIRROR_PAGE = 60;
const PARTS = ['file', 'thumb', 'poster'];
const KINDS = ['photo', 'video', 'file'];
const INV_BASE = 9999999999999;
const DAY_MS = 86400000;

const json = (data, status = 200) => Response.json(data, { status, headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
const hex = buf => [...new Uint8Array(buf)].map(v => v.toString(16).padStart(2, '0')).join('');
const sha256 = async bytes => hex(await crypto.subtle.digest('SHA-256', bytes));
const cleanText = (s, max = 200) => typeof s === 'string' && s.length <= max && !/[\u0000-\u001f\u007f]/.test(s);
const isJpeg = b => b.length > 3 && b[0] === 255 && b[1] === 216 && b[2] === 255;
const inv = takenAt => String(INV_BASE - Math.max(0, Math.min(INV_BASE, Math.floor(takenAt)))).padStart(13, '0');
export const groupOf = kind => (kind === 'file' ? 'docs' : 'gallery');
export async function albumKey(group, album) { return (await sha256(new TextEncoder().encode(group + '/' + album))).slice(0, 16); }
export const mirrorCap = env => { const v = Number(env?.MIRROR_CAP_BYTES); return Number.isFinite(v) && v > 0 ? v : MIRROR_CAP_DEFAULT; };
const r2Key = (uid, id, part) => `_insights/${uid}/files/m/${id}${part === 'file' ? '' : '.' + part}`;

function header(request, name, fallback = '') {
  let v;
  try { v = decodeURIComponent(request.headers.get(name) ?? fallback); } catch { bad('Metadate invalide.'); }
  if (!cleanText(v)) bad('Metadate invalide.');
  return v;
}
function intHeader(request, name, min = 0, max = 253402300799999) {
  const raw = request.headers.get(name);
  if (raw === null || raw === '') return null;
  if (!/^\d{1,15}$/.test(raw)) bad('Metadate invalide.');
  const n = Number(raw);
  if (n < min || n > max) bad('Metadate invalide.');
  return n;
}

/** Rândul fără cheile interne (R2, dispozitiv). */
export function publicItem(r) {
  return { id: r.id, kind: r.kind, name: r.name, album: r.album, group: r.group, media_type: r.media_type, taken_at: r.taken_at, received_at: r.received_at,
    width: r.width ?? null, height: r.height ?? null, duration_ms: r.duration_ms ?? null, orig_bytes: r.orig_bytes ?? null, bytes: r.bytes,
    file: !!r.parts.file, thumb: !!r.parts.thumb, poster: !!r.parts.poster, preview: previewOf(r) };
}
/** Ce poate deschide vizualizatorul site-ului: image · video · pdf · text · docx · download (restul se descarcă). */
export function previewOf(r) {
  const m = r.media_type || '';
  if (r.kind === 'photo') return r.parts.file ? 'image' : 'thumb';
  if (r.kind === 'video') return r.parts.file ? 'video' : 'poster';
  if (/^image\/(jpeg|png|webp|gif|avif|bmp)$/.test(m)) return 'image';
  if (m === 'application/pdf') return 'pdf';
  if (['text/plain', 'text/csv', 'text/markdown', 'application/json'].includes(m)) return 'text';
  if (m === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document') return 'docx';
  return 'download';
}

async function stats(storage) { return (await storage.get('mf-stats')) || { items: 0, bytes: 0, count: { photo: 0, video: 0, file: 0 }, latestAt: null, updatedAt: null }; }
async function albums(storage) { return (await storage.get('mf-albums')) || {}; }

/** Adună (sign = +1) sau scade (sign = −1) un rând din totaluri și din albumul lui; ține coperta pe cea mai nouă poză. */
async function tally(storage, row, sign, now) {
  const s = await stats(storage), a = await albums(storage), k = row.group + '/' + row.album;
  s.items = Math.max(0, s.items + sign); s.bytes = Math.max(0, s.bytes + sign * row.bytes);
  s.count[row.kind] = Math.max(0, (s.count[row.kind] || 0) + sign); s.updatedAt = now;
  if (sign > 0) s.latestAt = Math.max(s.latestAt || 0, row.received_at);
  const e = a[k] || { album: row.album, group: row.group, count: 0, bytes: 0, latestAt: 0, cover: null, coverAt: 0, kinds: { photo: 0, video: 0, file: 0 } };
  e.count = Math.max(0, e.count + sign); e.bytes = Math.max(0, e.bytes + sign * row.bytes); e.kinds[row.kind] = Math.max(0, (e.kinds[row.kind] || 0) + sign);
  if (sign > 0) {
    e.latestAt = Math.max(e.latestAt, row.taken_at);
    if (row.parts.thumb && row.taken_at >= (e.coverAt || 0)) { e.cover = row.id; e.coverAt = row.taken_at; }
  } else if (e.cover === row.id) { e.cover = null; e.coverAt = 0; }
  if (e.count > 0) a[k] = e; else delete a[k];
  await storage.put({ 'mf-stats': s, 'mf-albums': a });
}
async function indexKeys(row) {
  const t = inv(row.taken_at);
  return [`mf-t:${t}:${row.id}`, `mf-k:${row.kind}:${t}:${row.id}`, `mf-a:${await albumKey(row.group, row.album)}:${t}:${row.id}`];
}
/** După o scoatere din album: data celei mai noi copii și coperta (cea mai nouă cu miniatură) se refac din indexul albumului. */
async function refillCover(storage, group, album) {
  const a = await albums(storage), k = group + '/' + album, e = a[k];
  if (!e) return;
  const idx = [...(await storage.list({ prefix: `mf-a:${await albumKey(group, album)}:`, limit: 40 })).keys()];
  e.latestAt = 0; e.cover = null; e.coverAt = 0;
  for (const key of idx) {
    const row = await storage.get('mf:' + key.split(':').pop());
    if (!row || row.album !== album || row.group !== group) continue;
    if (!e.latestAt) e.latestAt = row.taken_at;
    if (row.parts?.thumb) { e.cover = row.id; e.coverAt = row.taken_at; break; }
  }
  await storage.put('mf-albums', a);
}
export async function eraseMirrorItem(storage, bucket, uid, row, now = Date.now()) {
  await storage.put('mf-gone:' + row.id, now);
  if (bucket) await bucket.delete(PARTS.filter(p => row.parts[p]).map(p => r2Key(uid, row.id, p)));
  await storage.delete([...(await indexKeys(row)), 'mf:' + row.id]);
  await tally(storage, row, -1, now);
  await refillCover(storage, row.group, row.album);
}

/** Pentru /v2/site/forget: fiecare rând și index `mf*` (R2 se golește acolo, după prefix). Întoarce câte copii erau. */
export async function forgetMirror(storage) {
  let items = 0;
  for (const prefix of ['mf:', 'mf-t:', 'mf-k:', 'mf-a:', 'mf-gone:']) {
    const list = [...(await storage.list({ prefix })).keys()];
    if (prefix === 'mf:') items = list.length;
    for (let i = 0; i < list.length; i += 128) await storage.delete(list.slice(i, i + 128));
  }
  await storage.delete(['mf-stats', 'mf-albums', 'mf-consent', 'mf-budget']);
  return items;
}

export async function mirrorSummary(storage, env) {
  const s = await stats(storage), a = await albums(storage), consent = await storage.get('mf-consent');
  const list = Object.values(a).map(e => ({ album: e.album, group: e.group, count: e.count, bytes: e.bytes, latestAt: e.latestAt || null, cover: e.cover || null, kinds: e.kinds }))
    .sort((x, y) => (y.latestAt || 0) - (x.latestAt || 0) || y.count - x.count);
  return { stats: s, albums: list, meter: { used: s.bytes, cap: mirrorCap(env), free_tier: R2_FREE_BYTES }, consent: { on: !!consent?.on, at: consent?.at || null } };
}

/** Pagini din index (cele mai noi primele). Fără `q` citește doar cheile paginii; cu `q` caută în toate rândurile. */
async function listItems(storage, url) {
  const kind = url.searchParams.get('kind') || 'all', group = url.searchParams.get('group') || '', album = url.searchParams.get('album');
  const q = (url.searchParams.get('q') || '').trim().toLocaleLowerCase('ro'), after = url.searchParams.get('after') || '';
  const limit = Math.min(120, Math.max(1, Number(url.searchParams.get('limit')) || MIRROR_PAGE));
  if (!['all', ...KINDS].includes(kind) || !['', 'gallery', 'docs'].includes(group) || q.length > 120 || after.length > 200 || (album !== null && !cleanText(album, 160))) bad('Filtru invalid.');
  if (album !== null && !group) bad('Filtru invalid.');
  const match = r => (kind === 'all' || r.kind === kind) && (!group || r.group === group) && (album === null || r.album === album);
  if (q) {
    const rows = [...(await storage.list({ prefix: 'mf:' })).values()]
      .filter(r => match(r) && `${r.name} ${r.album}`.toLocaleLowerCase('ro').includes(q))
      .sort((x, y) => y.taken_at - x.taken_at || (x.id < y.id ? -1 : 1));
    const start = /^\d{1,6}$/.test(after) ? Number(after) : 0, page = rows.slice(start, start + limit);
    return { items: page.map(publicItem), next: start + limit < rows.length ? String(start + limit) : null, total: rows.length };
  }
  const prefix = album !== null ? `mf-a:${await albumKey(group, album)}:` : kind !== 'all' ? `mf-k:${kind}:` : 'mf-t:';
  if (after && !after.startsWith(prefix)) bad('Filtru invalid.');
  const out = [];
  let cursor = after, more = false;
  // Albumul sau felul filtrează deja indexul; `group` fără album poate sări peste rânduri: citim în pagini de 200.
  scan: for (let round = 0; round < 10; round++) {
    const page = [...(await storage.list({ prefix, startAfter: cursor || undefined, limit: 200 })).keys()];
    for (const key of page) {
      if (out.length >= limit) { more = true; break scan; }
      cursor = key;
      const row = await storage.get('mf:' + key.split(':').pop());
      if (row && match(row)) out.push(row);
    }
    if (page.length < 200) break;
    if (round === 9) more = true;
  }
  const next = more ? cursor : null;
  const s = await stats(storage), a = await albums(storage);
  const total = album !== null ? a[group + '/' + album]?.count ?? out.length : kind !== 'all' ? s.count[kind] ?? 0 : group ? null : s.items;
  return { items: out.map(publicItem), next, total };
}

/** The caller (InsightsAccount.handle) has bound the verified owner. Null for any other path. */
export async function handleMirror(request, account, uid, bytes, readJSON) {
  const url = new URL(request.url), path = url.pathname;
  if (!path.startsWith('/v2/mirror')) return null;
  const storage = account.ctx.storage, bucket = account.env.RECORDS, now = Date.now();
  if (path === '/v2/mirror/summary') {
    if (request.method !== 'GET') bad('Method not allowed', 405);
    return json({ ...(await mirrorSummary(storage, account.env)), server_at: now });
  }
  if (path === '/v2/mirror/consent') {
    if (request.method === 'GET') return json((await storage.get('mf-consent')) || { on: false });
    if (request.method !== 'POST') bad('Method not allowed', 405);
    const { value } = await readJSON(request, 1024); keys(value, ['on', 'contract'], ['on']);
    if (typeof value.on !== 'boolean' || (value.contract !== undefined && (!Number.isInteger(value.contract) || value.contract < 4 || value.contract > 99))) bad('Acord invalid.');
    if (value.on && value.contract === undefined) bad('Oglinda cere contractul v4.');
    const next = { on: value.on, contract: value.contract ?? null, at: now };
    await storage.put('mf-consent', next); return json(next);
  }
  if (path === '/v2/mirror/ids') {
    // Registrul telefonului: [id, album, fel, părți, dispozitiv?] pentru fiecare copie (telefonul decide ce urcă, mută sau șterge).
    if (request.method !== 'GET') bad('Method not allowed', 405);
    const device = request.headers.get('x-device-id');
    const rows = [...(await storage.list({ prefix: 'mf:' })).values()].map(r => [r.id, r.album, r.kind, PARTS.filter(p => r.parts[p]).map(p => p[0]).join(''), r.device_id === device ? 1 : 0]);
    return json({ ids: rows, gone: [...(await storage.list({ prefix: 'mf-gone:' })).keys()].map(k => k.slice(8)), server_at: now });
  }
  if (path === '/v2/mirror') {
    if (request.method !== 'GET') bad('Method not allowed', 405);
    return json({ ...(await listItems(storage, url)), server_at: now });
  }
  const cover = /^\/v2\/mirror\/cover\/([A-Za-z0-9_-]{1,40})(?:\/([A-Za-z0-9_-]{1,40}))?$/.exec(path);
  if (cover) {
    const [, run, name] = cover, prefix = `_insights/${uid}/inventory/${run}/`;
    if (request.method === 'DELETE' && !name) {
      let n = 0, c;
      do { const page = await bucket.list({ prefix, cursor: c }); if (page.objects.length) { await bucket.delete(page.objects.map(o => o.key)); n += page.objects.length; } c = page.truncated ? page.cursor : undefined; } while (c);
      return json({ deleted: n });
    }
    if (!name) bad('Not found', 404);
    if (request.method === 'GET') {
      const o = await bucket.get(prefix + name + '.jpg'); if (!o) bad('Coperta nu mai este aici.', 404);
      return new Response(o.body, { headers: { 'content-type': 'image/jpeg', 'cache-control': 'private, max-age=3600', 'x-content-type-options': 'nosniff', 'content-security-policy': "default-src 'none'; sandbox" } });
    }
    if (request.method !== 'PUT') bad('Method not allowed', 405);
    if (!(await storage.get('mf-consent'))?.on) bad('Oglinda nu este pornită pe telefon.', 403);
    if (!bytes?.length || bytes.length > MIRROR_COVER_MAX || !isJpeg(bytes)) bad('Copertă JPEG invalidă.');
    await bucket.put(prefix + name + '.jpg', bytes, { httpMetadata: { contentType: 'image/jpeg' } });
    return json({ ok: true, bytes: bytes.length }, 201);
  }
  const match = /^\/v2\/mirror\/([0-9a-f-]{36})(?:\/(thumb|poster))?$/.exec(path);
  if (!match || !idPattern.test(match[1])) bad('Not found', 404);
  const id = match[1], part = match[2] || 'file', old = await storage.get('mf:' + id);
  if (request.method === 'GET') {
    if (!old || !old.parts[part]) bad('Copia nu mai este aici.', 404);
    const o = await bucket.get(r2Key(uid, id, part)); if (!o) bad('Copia nu mai este aici.', 404);
    const image = part !== 'file';
    return new Response(o.body, { headers: {
      'content-type': image ? 'image/jpeg' : 'application/octet-stream',
      'content-disposition': image ? 'inline' : `attachment; filename="file"; filename*=UTF-8''${encodeURIComponent(old.name)}`,
      // Miniaturile nu se schimbă sub același id (o poză editată are alt id pe telefon): pot sta în memoria browserului.
      'cache-control': image ? 'private, max-age=86400' : 'private, no-store, max-age=0',
      'x-content-type-options': 'nosniff', 'content-security-policy': "default-src 'none'; sandbox" } });
  }
  if (request.method === 'DELETE' && part === 'file') {
    if (old) await eraseMirrorItem(storage, bucket, uid, old, now); else await storage.put('mf-gone:' + id, now);
    return json({ deleted: true, phone_original_unchanged: true });
  }
  if (request.method === 'PATCH' && part === 'file') {
    if (!old) bad('Copia nu mai este aici.', 404);
    const { value } = await readJSON(request, 2048); keys(value, ['album', 'name', 'claim'], []);
    if ((value.album !== undefined && (!cleanText(value.album, 160) || !value.album.trim())) || (value.name !== undefined && (!cleanText(value.name) || !value.name.trim())) || (value.claim !== undefined && value.claim !== true)) bad('Metadate invalide.');
    const device = request.headers.get('x-device-id');
    if (value.claim && (!device || !idPattern.test(device))) bad('Dispozitiv invalid.');
    const next = { ...old, album: value.album?.trim() ?? old.album, name: value.name?.trim() ?? old.name, device_id: value.claim ? device : old.device_id, updated_at: now };
    if (next.album !== old.album) {
      await storage.delete(await indexKeys(old)); await tally(storage, old, -1, now);
      for (const k of await indexKeys(next)) await storage.put(k, 1);
      await storage.put('mf:' + id, next); await tally(storage, next, +1, now);
      await refillCover(storage, old.group, old.album);
    } else await storage.put('mf:' + id, next);
    return json(publicItem(next));
  }
  if (request.method !== 'PUT') bad('Method not allowed', 405);
  // ── urcarea unei părți ──
  const intake = await storage.get('intake'); if (intake?.accepting === false) bad('Primirea datelor este oprită din site.', 423);
  if (!(await storage.get('mf-consent'))?.on) bad('Oglinda nu este pornită pe telefon.', 403);
  const device = request.headers.get('x-device-id'); if (!device || !idPattern.test(device)) bad('Dispozitiv invalid.');
  if (await storage.get('mf-gone:' + id)) bad('Copia a fost ștearsă de pe site.', 410);
  if (!bytes?.length) bad('Fișier gol.');
  const kind = request.headers.get('x-mirror-kind'); if (!KINDS.includes(kind)) bad('Fel invalid.');
  if (old && old.kind !== kind) bad('Identificator folosit pentru alt fișier.', 409);
  const mime = (request.headers.get('x-media-type') || 'application/octet-stream').toLowerCase();
  if (mime.length > 120 || !/^[a-z0-9.+-]+\/[a-z0-9.+-]+$/.test(mime)) bad('Metadate invalide.');
  if (part === 'thumb' && (bytes.length > MIRROR_THUMB_MAX || !isJpeg(bytes))) bad('Miniatură JPEG invalidă.');
  if (part === 'poster' && (kind !== 'video' || bytes.length > MIRROR_POSTER_MAX || !isJpeg(bytes))) bad('Poster JPEG invalid.');
  if (part === 'file') {
    if (bytes.length > MIRROR_FILE_MAX) bad('Fișier mai mare de 25 MB.', 413);
    if (kind === 'photo' && (mime !== 'image/jpeg' || !isJpeg(bytes))) bad('Poza vine ca JPEG.');
    if (kind === 'video' && !mime.startsWith('video/')) bad('Video invalid.');
  }
  const sha = await sha256(bytes);
  if (request.headers.get('x-file-sha256') !== sha) bad('Transfer incomplet: amprenta fișierului nu corespunde.', 422);
  if (old?.parts[part]?.sha256 === sha) return json(publicItem(old)); // idempotent
  const name = header(request, 'x-file-name', old?.name || 'Fișier').trim(), album = header(request, 'x-file-album', old?.album || '').trim();
  if (!name || !album || album.length > 160) bad('Metadate invalide.');
  const takenAt = intHeader(request, 'x-taken-at') ?? old?.taken_at ?? now;
  const width = intHeader(request, 'x-width', 0, 100000), height = intHeader(request, 'x-height', 0, 100000);
  const duration = intHeader(request, 'x-duration-ms', 0, 7 * DAY_MS), origBytes = intHeader(request, 'x-orig-bytes', 0, 1e13);
  const delta = bytes.length - (old?.parts[part]?.bytes || 0), s = await stats(storage);
  if (delta > 0 && s.bytes + delta > mirrorCap(account.env)) bad('Oglinda a atins plafonul de spațiu.', 507);
  const day = Math.floor(now / DAY_MS), budget = await storage.get('mf-budget'), used = budget?.day === day ? budget.used : 0;
  if (used >= MIRROR_DAILY_PARTS) bad('Limita zilnică a oglinzii a fost atinsă. Continuă mâine.', 429);
  await bucket.put(r2Key(uid, id, part), bytes, { httpMetadata: { contentType: part === 'file' ? 'application/octet-stream' : 'image/jpeg' } });
  await storage.put('mf-budget', { day, used: used + 1 });
  const base = old || { id, kind, group: groupOf(kind), parts: {}, bytes: 0, received_at: now, device_id: device };
  const next = { ...base, name: old && part !== 'file' ? old.name : name, album: old && part !== 'file' ? old.album : album,
    media_type: part === 'file' || !old ? mime : old.media_type,
    taken_at: takenAt, width: width ?? old?.width ?? null, height: height ?? old?.height ?? null, duration_ms: duration ?? old?.duration_ms ?? null,
    orig_bytes: origBytes ?? old?.orig_bytes ?? null, updated_at: now, device_id: device,
    parts: { ...base.parts, [part]: { bytes: bytes.length, sha256: sha } } };
  next.bytes = PARTS.reduce((n, p) => n + (next.parts[p]?.bytes || 0), 0);
  if (old) { await storage.delete(await indexKeys(old)); await tally(storage, old, -1, now); }
  for (const k of await indexKeys(next)) await storage.put(k, 1);
  await storage.put('mf:' + id, next);
  await tally(storage, next, +1, now);
  if (old && (old.album !== next.album || old.group !== next.group)) await refillCover(storage, old.group, old.album);
  return json(publicItem(next), old ? 200 : 201);
}
