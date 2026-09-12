import { TTL, idPattern, categories, bad, keys, n, validatePhoneData } from './phone-schema.mjs';

const MAX_SESSION = 32 * 1024 * 1024;
export const reply = (data, status = 200) => Response.json(data, { status, headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
export async function readBytes(request, max) {
  if (request.headers.has('content-encoding')) bad('Compressed upload unsupported', 415);
  if (Number(request.headers.get('content-length')) > max) bad('Payload too large', 413);
  const parts = []; let size = 0;
  if (!request.body) return new Uint8Array();
  const reader = request.body.getReader();
  try {
    while (true) {
      const { done, value } = await reader.read(); if (done) break;
      size += value.length; if (size > max) { await reader.cancel(); bad('Payload too large', 413); }
      parts.push(value);
    }
  } finally { reader.releaseLock(); }
  const out = new Uint8Array(size); let offset = 0;
  for (const part of parts) { out.set(part, offset); offset += part.length; }
  return out;
}
export async function readJSON(request, max = 256 * 1024) {
  if (request.headers.get('content-type')?.split(';')[0] !== 'application/json') bad('JSON required', 415);
  const bytes = await readBytes(request, max);
  try { return { bytes, value: JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) }; }
  catch { bad('Invalid JSON'); }
}
const digest = async bytes => ({ bytes: bytes.length, sha256: [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(v => v.toString(16).padStart(2, '0')).join('') });
function checkWav(b) {
  const t = (a,z) => new TextDecoder().decode(b.slice(a,z));
  if (b.length < 46 || b.length > 320044 || b.length % 2) bad('Invalid WAV');
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  if (t(0,4)!=='RIFF' || t(8,16)!=='WAVEfmt ' || t(36,40)!=='data' || v.getUint32(4,true)!==b.length-8 ||
      v.getUint32(16,true)!==16 || v.getUint16(20,true)!==1 || v.getUint16(22,true)!==1 ||
      v.getUint32(24,true)!==16000 || v.getUint32(28,true)!==32000 || v.getUint16(32,true)!==2 ||
      v.getUint16(34,true)!==16 || v.getUint32(40,true)!==b.length-44) bad('Expected mono 16kHz PCM16 WAV');
}

/** One Durable Object per verified Firebase UID. The Worker replaces the internal owner header. */
export class InsightsAccount {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }
  fetch(request) {
    // External R2 I/O must finish before another mutation may change the metadata.
    return this.ctx.blockConcurrencyWhile(async () => {
      try { return await this.handle(request); }
      catch (e) { return reply({ error: e.status ? e.message : 'Storage operation failed' }, e.status || 500); }
    });
  }
  async remove(record) {
    let cursor;
    do {
      const page = await this.env.RECORDS.list({ prefix: record.prefix, cursor });
      if (page.objects.length) await this.env.RECORDS.delete(page.objects.map(o => o.key));
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
    await this.ctx.storage.delete(['session:' + record.session_id, 'data:' + record.session_id]);
  }
  async sweep() {
    const records = await this.ctx.storage.list({ prefix: 'session:' });
    let next = Infinity;
    for (const r of records.values()) {
      if (r.expires_at <= Date.now()) await this.remove(r); else next = Math.min(next, r.expires_at);
    }
    if (Number.isFinite(next)) await this.ctx.storage.setAlarm(next); else await this.ctx.storage.deleteAlarm();
  }
  async alarm() { await this.ctx.blockConcurrencyWhile(() => this.sweep()); }
  publicRecord(r) { const { prefix, ...rest } = r; return rest; }
  async handle(request) {
    const uid = request.headers.get('x-forja-owner');
    if (!uid || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) bad('Invalid owner', 403);
    const owner = await this.ctx.storage.get('owner');
    if (owner && owner !== uid) bad('Wrong owner', 403);
    if (!owner) await this.ctx.storage.put('owner', uid);
    if (!this.env.RECORDS) bad('Storage unavailable', 503);
    await this.sweep();
    const url = new URL(request.url); const path = url.pathname;
    if (path === '/internal/ai-budget' && request.method === 'POST') {
      const day = Math.floor(Date.now() / TTL); const old = await this.ctx.storage.get('ai-budget');
      const budget = old?.day === day ? old : { day, used: 0, last: 0 };
      if (budget.used >= 30 || Date.now() - budget.last < 10000) bad('Așteaptă puțin înainte de o nouă analiză (maximum 30 pe zi).', 429);
      await this.ctx.storage.put('ai-budget', { day, used: budget.used + 1, last: Date.now() });
      return reply({ ok: true });
    }
    if (path === '/v2/sessions') {
      const existing = [...(await this.ctx.storage.list({ prefix: 'session:' })).values()];
      if (request.method === 'GET') return reply({ sessions: existing.sort((a,b) => b.created_at-a.created_at).map(r => this.publicRecord(r)) });
      if (request.method !== 'POST') bad('Method not allowed', 405);
      const { value } = await readJSON(request, 4096);
      keys(value, ['session_id', 'consent', 'mode'], ['session_id', 'consent']); keys(value.consent, categories);
      if (value.mode !== undefined && value.mode !== 'automatic') bad('Invalid session mode');
      if (!idPattern.test(value.session_id) || categories.some(k => typeof value.consent[k] !== 'boolean') || !categories.some(k => value.consent[k])) bad('Invalid consent');
      const same = existing.find(r => r.session_id === value.session_id);
      if (same) {
        if (same.mode === 'automatic' && value.mode === 'automatic' && categories.every(k => same.consent[k] === value.consent[k])) return reply(this.publicRecord(same));
        bad('Session already exists', 409);
      }
      if (existing.length >= 20) bad('Delete an older session first (maximum 20).', 429);
      const now = Date.now();
      const record = { ...value, created_at: now, expires_at: now + TTL, bytes: 0, items: [], data: null, observations: [], prefix: `_insights/${uid}/${value.session_id}/` };
      await this.ctx.storage.put('session:' + record.session_id, record); await this.sweep();
      return reply(this.publicRecord(record), 201);
    }
    const match = /^\/v2\/sessions\/([^/]+)(?:\/(data|items|observation)(?:\/([^/]+))?)?$/.exec(path);
    if (!match || !idPattern.test(match[1])) bad('Not found', 404);
    const id = match[1]; const record = await this.ctx.storage.get('session:' + id);
    if (!record) bad('Session not found', 404);
    if (!match[2]) {
      if (request.method === 'GET') return reply(this.publicRecord(record));
      if (request.method === 'DELETE') { await this.remove(record); return reply({ deleted: true }); }
    }
    if (match[2] === 'data' && !match[3]) {
      if (request.method === 'GET') {
        const bytes = await this.ctx.storage.get('data:' + id); if (!bytes) bad('No metrics', 404);
        return new Response(bytes, { headers: { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
      }
      if (request.method === 'POST') {
        if (record.data && record.mode !== 'automatic') bad('Metrics already uploaded', 409);
        const { bytes, value } = await readJSON(request); validatePhoneData(value, record.consent);
        const receipt = await digest(bytes); record.bytes += bytes.length - (record.data?.bytes || 0); record.data = receipt; record.updated_at = Date.now();
        if (record.bytes > MAX_SESSION) bad('Session limit', 413);
        await this.ctx.storage.put({ ['data:' + id]: bytes, ['session:' + id]: record });
        return reply(receipt, 201);
      }
    }
    // This path is only forwarded by the Worker's authenticated AI handler.
    if (match[2] === 'observation' && request.method === 'POST' && !match[3]) {
      const { value } = await readJSON(request, 4096);
      keys(value, ['item_id', 'text', 'model']);
      if (!record.items.some(i => i.item_id === value.item_id) || typeof value.text !== 'string' || value.text.length > 1200 || typeof value.model !== 'string' || value.model.length > 100) bad('Invalid observation');
      record.observations = record.observations.filter(o => o.item_id !== value.item_id);
      record.observations.push({ ...value, at: Date.now(), source: 'AI interpretation; confirm before relying on it' });
      await this.ctx.storage.put('session:' + id, record); return reply({ ok: true });
    }
    if (match[2] === 'items') {
      if (request.method === 'GET' && match[3]) {
        const item = record.items.find(i => i.item_id === match[3]); if (!item) bad('Not found', 404);
        const object = await this.env.RECORDS.get(record.prefix + item.item_id); if (!object) bad('Not found', 404);
        return new Response(object.body, { headers: { 'content-type': 'application/octet-stream', 'content-disposition': `attachment; filename="${item.item_id}.bin"`, 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
      }
      if (request.method === 'POST' && !match[3]) {
        const kind = url.searchParams.get('kind'); const flag = { file: 'files', photo: 'photos', audio: 'audio' }[kind];
        if (!flag || !record.consent[flag]) bad('No consent for this item');
        if (!/^\d+$/.test(url.searchParams.get('sequence') || '')) bad('Sequence required');
        const sequence = Number(url.searchParams.get('sequence')); n(sequence, 0, kind === 'audio' ? 23 : 4);
        const previous = record.items.find(i => i.kind === kind && i.sequence === sequence);
        if (previous && record.mode !== 'automatic') bad('Duplicate item', 409);
        if (!previous && kind !== 'audio' && record.items.filter(i => i.kind !== 'audio').length >= 5) bad('Maximum five selected files/photos', 413);
        if (record.mode !== 'automatic' && kind === 'audio' && Date.now() - record.created_at > 180000) bad('Audio session closed', 410);
        const bytes = await readBytes(request, kind === 'audio' ? 320044 : 5 * 1024 * 1024);
        if (!bytes.length) bad('Empty file'); if (kind === 'audio') checkWav(bytes);
        if (record.bytes + bytes.length - (previous?.bytes || 0) > MAX_SESSION) bad('Session limit', 413);
        let name; try { name = decodeURIComponent(request.headers.get('x-file-name') || 'Selected item'); } catch { bad('Invalid name'); }
        const media_type = request.headers.get('x-media-type') || 'application/octet-stream';
        if (name.length > 200 || /[\u0000-\u001f\u007f]/.test(name) || media_type.length > 100 || !/^[A-Za-z0-9.+-]+\/[A-Za-z0-9.+-]+$/.test(media_type)) bad('Invalid metadata');
        const item = { item_id: crypto.randomUUID(), kind, sequence, name, media_type, ...await digest(bytes), received_at: Date.now() };
        await this.env.RECORDS.put(record.prefix + item.item_id, bytes, { customMetadata: { at: String(record.created_at) }, httpMetadata: { contentType: 'application/octet-stream' } });
        if (previous) {
          await this.env.RECORDS.delete(record.prefix + previous.item_id);
          record.items = record.items.filter(i => i.item_id !== previous.item_id);
          record.observations = record.observations.filter(o => o.item_id !== previous.item_id);
        }
        record.items.push(item); record.bytes += bytes.length - (previous?.bytes || 0); record.updated_at = Date.now();
        await this.ctx.storage.put('session:' + id, record); return reply(item, 201);
      }
    }
    bad('Not found', 404);
  }
}
