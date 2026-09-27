import { mkdir, readFile, writeFile, readdir, rm, rename } from 'node:fs/promises';
import { join } from 'node:path';
import { randomUUID, createHash } from 'node:crypto';

export const SESSION_LIMIT = 32 * 1024 * 1024;
export const ITEM_LIMIT = 5 * 1024 * 1024;
import { TTL, idPattern, categories, bad, keys, n, validatePhoneData } from '../server/phone-schema.mjs';
export { validatePhoneData } from '../server/phone-schema.mjs';
async function body(req, max) {
  if (req.headers['content-encoding']) bad('Compressed bodies are not supported', 415);
  if (Number(req.headers['content-length']) > max) bad('Payload too large', 413);
  const parts = []; let size = 0;
  for await (const part of req) { size += part.length; if (size > max) bad('Payload too large', 413); parts.push(part); }
  return Buffer.concat(parts);
}
async function jsonBody(req) {
  if (req.headers['content-type']?.split(';')[0].trim() !== 'application/json') bad('JSON required', 415);
  const bytes = await body(req, 256 * 1024);
  try { return { bytes, value: JSON.parse(new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)) }; }
  catch { bad('Invalid JSON'); }
}
function wav(bytes) {
  if (bytes.length < 46 || bytes.length > 320044 || bytes.toString('ascii', 0, 4) !== 'RIFF' ||
      bytes.toString('ascii', 8, 12) !== 'WAVE' || bytes.toString('ascii', 12, 16) !== 'fmt ' ||
      bytes.readUInt32LE(16) !== 16 || bytes.readUInt16LE(20) !== 1 || bytes.readUInt16LE(22) !== 1 ||
      bytes.readUInt32LE(24) !== 16000 || bytes.readUInt32LE(28) !== 32000 ||
      bytes.readUInt16LE(32) !== 2 || bytes.readUInt16LE(34) !== 16 || bytes.toString('ascii', 36, 40) !== 'data' ||
      bytes.readUInt32LE(40) !== bytes.length - 44 || bytes.readUInt32LE(4) !== bytes.length - 8 || bytes.length % 2) bad('Expected mono 16 kHz PCM16 WAV, up to 10 seconds');
}

export async function sessionReceiver({ dataDir, now }) {
  const root = join(dataDir, 'sessions'); await mkdir(root, { recursive: true, mode: 0o700 });
  const busy = new Set();
  const path = id => join(root, id);
  const metadata = id => join(path(id), 'session.json');
  async function save(record) {
    const temp = join(path(record.session_id), 'session.tmp');
    await writeFile(temp, JSON.stringify(record), { mode: 0o600 }); await rename(temp, metadata(record.session_id));
  }
  async function get(id) {
    let value;
    try { value = JSON.parse(await readFile(metadata(id), 'utf8')); }
    catch (e) { if (e.code === 'ENOENT') bad('Session not found', 404); throw e; }
    if (value.expires_at <= now()) { if (!busy.has(id)) await rm(path(id), { recursive: true, force: true }); bad('Session expired', 404); }
    return value;
  }
  async function purge() {
    for (const id of await readdir(root)) {
      if (!idPattern.test(id) || busy.has(id)) continue;
      try { await get(id); } catch (e) { if (e.status !== 404) throw e; }
    }
  }
  async function handle(req, res, json) {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/v2/sessions' && req.method === 'GET') {
      const list = [];
      for (const id of await readdir(root)) {
        if (!idPattern.test(id)) continue;
        try { const r = await get(id); list.push(r); } catch (e) { if (e.status !== 404) throw e; }
      }
      return json(200, { sessions: list.sort((a,b) => b.created_at - a.created_at) });
    }
    if (url.pathname === '/v2/sessions' && req.method === 'POST') {
      const { value } = await jsonBody(req);
      keys(value, ['session_id', 'consent']); keys(value.consent, categories);
      if (typeof value.session_id !== 'string' || !idPattern.test(value.session_id) || categories.some(k => typeof value.consent[k] !== 'boolean') || !categories.some(k => value.consent[k])) bad('Invalid session consent');
      try { await mkdir(path(value.session_id), { mode: 0o700 }); } catch (e) { if (e.code === 'EEXIST') bad('Session already exists', 409); throw e; }
      const r = { ...value, created_at: now(), expires_at: now() + TTL, items: [], bytes: 0, data: null };
      await save(r); return json(201, r);
    }
    const match = /^\/v2\/sessions\/([^/]+)(?:\/(data|items)(?:\/([^/]+))?)?$/.exec(url.pathname);
    if (!match || !idPattern.test(match[1])) return json(404, { error: 'Not found' });
    const id = match[1];
    if (req.method === 'GET') {
      const r = await get(id);
      if (!match[2]) return json(200, r);
      if (match[2] === 'data' && !match[3] && r.data) {
        res.writeHead(200, { 'Content-Type': 'application/json' }); return res.end(await readFile(join(path(id), 'data.json')));
      }
      const item = match[2] === 'items' && r.items.find(i => i.item_id === match[3]);
      if (!item) return json(404, { error: 'Not found' });
      res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Disposition': `attachment; filename="${item.item_id}.bin"` });
      return res.end(await readFile(join(path(id), `${item.item_id}.bin`)));
    }
    if (busy.has(id)) bad('Session busy; no change made', 409);
    busy.add(id);
    try {
      const r = await get(id);
      if (req.method === 'DELETE' && !match[2]) {
        await rm(path(id), { recursive: true, force: true }); return json(200, { deleted: true });
      }
      if (req.method === 'POST' && match[2] === 'data' && !match[3]) {
        if (r.data) bad('Metrics already uploaded', 409);
        const { bytes, value } = await jsonBody(req); validatePhoneData(value, r.consent);
        if (r.bytes + bytes.length > SESSION_LIMIT) bad('Session size limit', 413);
        const receipt = { bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
        await writeFile(join(path(id), 'data.json'), bytes, { mode: 0o600, flag: 'wx' });
        r.data = receipt; r.bytes += bytes.length; await save(r); return json(201, receipt);
      }
      if (req.method === 'POST' && match[2] === 'items' && !match[3]) {
        const kind = url.searchParams.get('kind');
        const permission = { file: 'files', photo: 'photos', audio: 'audio' }[kind];
        if (!permission || !r.consent[permission]) bad('No consent for this item');
        if (!/^\d+$/.test(url.searchParams.get('sequence') || '')) bad('Sequence is required');
        const sequence = Number(url.searchParams.get('sequence'));
        n(sequence, 0, kind === 'audio' ? 23 : 4);
        if (r.items.some(i => i.kind === kind && i.sequence === sequence)) bad('Item already uploaded', 409);
        if (kind === 'audio' && (now() - r.created_at > 180000)) bad('Audio session closed', 410);
        const bytes = await body(req, kind === 'audio' ? 320044 : ITEM_LIMIT);
        if (!bytes.length) bad('Empty item');
        if (kind === 'audio') wav(bytes);
        if (r.bytes + bytes.length > SESSION_LIMIT) bad('Session size limit', 413);
        let name;
        try { name = decodeURIComponent(req.headers['x-file-name'] || 'Selected item'); } catch { bad('Invalid file name'); }
        if (name.length > 200 || /[\u0000-\u001f\u007f]/.test(name)) bad('Invalid file name');
        const media_type = req.headers['x-media-type'] || 'application/octet-stream';
        if (!/^[A-Za-z0-9.+-]+\/[A-Za-z0-9.+-]+$/.test(media_type) || media_type.length > 100) bad('Invalid media type');
        const item = { item_id: randomUUID(), kind, sequence, name, media_type, bytes: bytes.length,
          sha256: createHash('sha256').update(bytes).digest('hex'), received_at: now() };
        await writeFile(join(path(id), `${item.item_id}.bin`), bytes, { mode: 0o600, flag: 'wx' });
        r.items.push(item); r.bytes += bytes.length; await save(r); return json(201, item);
      }
      return json(404, { error: 'Not found' });
    } finally { busy.delete(id); }
  }
  return { handle, purge };
}
