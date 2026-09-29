import {handleSleepStore,sweepSleep} from './sleep-store.mjs';
import {handleRecovery,sweepRecovery} from './lost-phone.mjs';
import {handleOrganizer,sweepOrganizer} from './organizer.mjs';
import {handleOrganizerJobs,sweepOrganizerJobs} from './organizer-jobs.mjs';
import {handleCleanup,sweepCleanup} from './cleanup-schedule.mjs';
import { handleFiles, sweepFiles, FILE_MAX_BYTES, THUMB_MAX_BYTES } from './files-vault.mjs';
import { checkRecording, RECORDING_MAX_BYTES } from './recording-schema.mjs';
import { handlePhoneControl } from './phone-control.mjs';
import { handleAppContent, defaultIntake } from './app-content.mjs';
import { TTL, idPattern, categories, bad, keys, n, validatePhoneData } from './phone-schema.mjs';
import { handleSiteStore, applyUsageRollup, sweepSite } from './site-store.mjs';
import { applyLocationRollup, LOC_DAY_PREFIX, handleSiteLocation, sweepLocation } from './site-location.mjs';
import { eraseFile } from './files-vault.mjs';

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
  async fetch(request) {
    let upload = false, fileUpload = false;
    try {
      const isRecording = request.method === 'POST' && /^\/v2\/sessions\/[^/]+\/recording$/.test(new URL(request.url).pathname);
      if (isRecording) { if (this.uploadingRecording || this.uploadingFile) bad('Retry shortly',429); this.uploadingRecording = true; upload = true; }
      const isFile = request.method === 'PUT' && /^\/v2\/files\/[0-9a-f-]+(?:\/thumbnail)?$/.test(new URL(request.url).pathname);
      if (isFile) { if (this.uploadingFile || this.uploadingRecording) bad('Retry shortly',429); this.uploadingFile=true; fileUpload=true; }
      const bytes = isRecording ? await readBytes(request, RECORDING_MAX_BYTES) : isFile ? await readBytes(request, new URL(request.url).pathname.endsWith('/thumbnail') ? THUMB_MAX_BYTES : FILE_MAX_BYTES) : null;
      return await this.ctx.blockConcurrencyWhile(() => this.handle(request, bytes));
    } catch(e) { return reply({ error:e.status?e.message:'Storage operation failed' },e.status||500); }
    finally { if(upload) this.uploadingRecording=false; if(fileUpload) this.uploadingFile=false; }
  }
  async remove(record) {
    let cursor;
    do {
      const page = await this.env.RECORDS.list({ prefix: record.prefix, cursor });
      if (page.objects.length) await this.env.RECORDS.delete(page.objects.map(o => o.key));
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
    await this.ctx.storage.delete(['session:' + record.session_id, 'data:' + record.session_id, 'usage-last:' + record.session_id]);
  }
  /**
   * POST /v2/site/forget: rollup-ul timpului pe ecran (`usage-day:*`, `usage-last:*`), ziua pe hartă (`loc-day:*`), copiile
   * galeriei (rândurile `cloud-file:*` / `file-staging:*`, cu tombstone ca o reîncercare întârziată să nu le readucă) și tot
   * din R2 sub `_insights/{uid}/files/` și `_insights/{uid}/inventory/` (coperțile dosarelor, pachetul C). Sesiunile se
   * șterg separat (DELETE /v2/sessions/{id}). Idempotent: a doua cerere întoarce zerouri.
   */
  async forgetSite(uid) {
    const s = this.ctx.storage, bucket = this.env.RECORDS, out = { usageDays: 0, locDays: 0, caches: 0, files: 0, objects: 0 };
    const drop = async prefix => {
      const keys = [...(await s.list({ prefix })).keys()];
      for (let i = 0; i < keys.length; i += 128) await s.delete(keys.slice(i, i + 128));
      return keys.length;
    };
    out.usageDays = await drop('usage-day:');
    await drop('usage-last:');
    out.locDays = await drop(LOC_DAY_PREFIX);
    // Copiile Cercului: ziua pe hartă (cerc-live.day), agenda, numele locurilor, energia; altfel un răspuns „stale” le-ar mai servi.
    out.caches = await drop('site-cache:cerc-');
    for (const item of (await s.list({ prefix: 'cloud-file:' })).values()) {
      if (bucket) await eraseFile(s, bucket, item); else await s.delete('cloud-file:' + item.id);
      out.files++;
    }
    for (const [key, item] of await s.list({ prefix: 'file-staging:' })) {
      await s.put('file-gone:' + key.slice('file-staging:'.length), Date.now() + 7 * TTL);
      if (bucket && item?.key) await bucket.delete([item.key, item.key + '.thumb']);
      await s.delete(key);
    }
    if (bucket) for (const prefix of [`_insights/${uid}/files/`, `_insights/${uid}/inventory/`]) {
      let cursor;
      do {
        const page = await bucket.list({ prefix, cursor });
        if (page.objects.length) { await bucket.delete(page.objects.map(o => o.key)); out.objects += page.objects.length; }
        cursor = page.truncated ? page.cursor : undefined;
      } while (cursor);
    }
    return { forgotten: out };
  }
  async sweep() {
    for (const [key, until] of await this.ctx.storage.list({ prefix: 'deleted:' })) {
      if (until <= Date.now()) await this.ctx.storage.delete(key);
    }
    const records = await this.ctx.storage.list({ prefix: 'session:' });
    let next = Math.min(await sweepSleep(this.ctx.storage),await sweepRecovery(this.ctx.storage),await sweepFiles(this.ctx.storage,this.env.RECORDS),await sweepCleanup(this.ctx.storage),await sweepOrganizer(this.ctx.storage,Date.now()),await sweepOrganizerJobs(this.ctx.storage),await sweepSite(this.ctx.storage,Date.now()),await sweepLocation(this.ctx.storage,Date.now()));
    for (const r of records.values()) {
      if (r.expires_at <= Date.now()) await this.remove(r); else next = Math.min(next, r.expires_at);
    }
    if (Number.isFinite(next)) await this.ctx.storage.setAlarm(next); else await this.ctx.storage.deleteAlarm();
  }
  async alarm() { await this.ctx.blockConcurrencyWhile(async () => {
    try { await this.sweep(); } catch(error) { await this.ctx.storage.setAlarm(Date.now()+60000); throw error; }
  }); }
  publicRecord(r) { const { prefix, ...rest } = r; return rest; }
  async handle(request, recordingBytes = null) {
    const uid = request.headers.get('x-forja-owner');
    if (!uid || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) bad('Invalid owner', 403);
    const owner = await this.ctx.storage.get('owner');
    if (owner && owner !== uid) bad('Wrong owner', 403);
    if (!owner) await this.ctx.storage.put('owner', uid);
    // Revocarea (CollectionSettings.disableAll în aplicație): se șterge ce ține de site și nu expiră singur la timp.
    if (new URL(request.url).pathname === '/v2/site/forget') {
      if (request.method !== 'POST') bad('Method not allowed', 405);
      return reply(await this.forgetSite(uid));
    }
    const recoveryResponse = await handleRecovery(request,this,readJSON);
    if(recoveryResponse)return recoveryResponse;
    const dayResponse = await handleSiteLocation(request, this.ctx.storage);
    if (dayResponse) return dayResponse;
    const siteResponse = await handleSiteStore(request, this, readJSON);
    if (siteResponse) return siteResponse;
    const contentResponse = await handleAppContent(request, this.ctx.storage, readJSON);
    if (contentResponse) return contentResponse;
    const phoneResponse = await handlePhoneControl(request, this.ctx.storage, readJSON);
    if (phoneResponse) return phoneResponse;
    if (!this.env.RECORDS) bad('Storage unavailable', 503);
    await this.sweep();
    const sleepResponse=await handleSleepStore(request,this,readJSON);
    if(sleepResponse)return sleepResponse;
    const jobsResponse=await handleOrganizerJobs(request,this,readJSON);
    if(jobsResponse)return jobsResponse;
    const organizerResponse=await handleOrganizer(request,this,readJSON);
    if(organizerResponse)return organizerResponse;
    const cleanupResponse = await handleCleanup(request,this,readJSON);
    if (cleanupResponse) return cleanupResponse;
    const filesResponse = await handleFiles(request,this,uid,recordingBytes,readJSON);
    if (filesResponse) return filesResponse;
    const url = new URL(request.url); const path = url.pathname;
    // Server intake is independent of phone permissions and cannot start a sensor.
    if (request.method === 'POST' && (path === '/v2/sessions' || /^\/v2\/sessions\/[^/]+\/(data|items|recording)$/.test(path))) {
      const intake = await this.ctx.storage.get('intake') || defaultIntake();
      if (!intake.accepting) return reply({ error: 'Primirea datelor este oprită din panoul web.', code: 'intake_paused' }, 423);
    }
    if (['/internal/ai-budget','/internal/organizer-ai-budget'].includes(path) && request.method === 'POST') {
      const organizer=path==='/internal/organizer-ai-budget',budgetKey=organizer?'organizer-ai-budget':'ai-budget';
      const {value:aiRequest}=await readJSON(request,1024);const units=aiRequest.units??1;if(!Number.isInteger(units)||units<1||units>(organizer?10:6))bad('Invalid AI budget');
      const day = Math.floor(Date.now() / TTL); const old = await this.ctx.storage.get(budgetKey);
      const budget = old?.day === day ? old : { day, used: 0, last: 0 };
      if (budget.used + units > (organizer?300:30) || !organizer&&Date.now() - budget.last < 10000) bad(organizer?'Limita zilnică de analiză a fost atinsă. Progresul este păstrat; continuarea este disponibilă mâine.':'Așteaptă puțin înainte de o nouă analiză (maximum 30 pe zi).', 429);
      await this.ctx.storage.put(budgetKey, { day, used: budget.used + units, last: Date.now() });
      return reply({ ok: true });
    }
    if (path === '/v2/sessions') {
      const existing = [...(await this.ctx.storage.list({ prefix: 'session:' })).values()];
      if (request.method === 'GET') return reply({ sessions: existing.sort((a,b) => b.created_at-a.created_at).map(r => this.publicRecord(r)) });
      if (request.method !== 'POST') bad('Method not allowed', 405);
      const { value } = await readJSON(request, 4096);
      keys(value, ['session_id', 'consent', 'mode'], ['session_id', 'consent']); keys(value.consent, categories);
      if (value.mode !== undefined && !['automatic','recording'].includes(value.mode)) bad('Invalid session mode');
      if (!idPattern.test(value.session_id) || categories.some(k => typeof value.consent[k] !== 'boolean') || !categories.some(k => value.consent[k])) bad('Invalid consent');
      if (await this.ctx.storage.get('deleted:' + value.session_id)) bad('Session was deleted', 410);
      if (value.mode === 'recording' && categories.some(k => value.consent[k] !== (k === 'audio'))) bad('Audio-only recording consent required');
      const same = existing.find(r => r.session_id === value.session_id);
      if (same) {
        if (['automatic','recording'].includes(same.mode) && same.mode === value.mode && categories.every(k => same.consent[k] === value.consent[k])) return reply(this.publicRecord(same));
        bad('Session already exists', 409);
      }
      const reservation=await this.ctx.storage.get('sleep-reservation:'+value.session_id);
      const sleep=reservation?await this.ctx.storage.get('sleep:'+reservation.sleep_id):null;
      if(reservation&&(!sleep||sleep.expires_at<=Date.now()||reservation.expires_at<=Date.now()||value.mode!=='recording'))bad('Invalid sleep reservation',409);
      if (!reservation&&existing.filter(r=>!r.sleep_session_id).length >= 20) bad('Delete an older session first (maximum 20).', 429);
      if(reservation&&existing.filter(r=>r.sleep_session_id).length>=365)bad('Maximum 365 retained sleep chunks',429);
      const now = Date.now();
      const record = { ...value, ...(reservation?{sleep_session_id:reservation.sleep_id}:{}), created_at: now, expires_at: now + TTL, bytes: 0, items: [], data: null, observations: [], prefix: `_insights/${uid}/${value.session_id}/` };
      await this.ctx.storage.put('session:' + record.session_id, record); await this.sweep();
      return reply(this.publicRecord(record), 201);
    }
    const match = /^\/v2\/sessions\/([^/]+)(?:\/(data|items|observation|recording)(?:\/([^/]+))?)?$/.exec(path);
    if (!match || !idPattern.test(match[1])) bad('Not found', 404);
    const id = match[1]; const record = await this.ctx.storage.get('session:' + id);
    if (!record) bad('Session not found', 404);
    if (!match[2]) {
      if (request.method === 'GET') return reply(this.publicRecord(record));
      if (request.method === 'DELETE') {
        await this.ctx.storage.put('deleted:' + id, Date.now() + 7 * TTL);
        await this.remove(record); return reply({ deleted: true });
      }
    }
    if (match[2] === 'data' && !match[3]) {
      if (request.method === 'GET') {
        const bytes = await this.ctx.storage.get('data:' + id); if (!bytes) bad('No metrics', 404);
        return new Response(bytes, { headers: { 'content-type': 'application/json', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
      }
      if (request.method === 'POST') {
        if (record.mode === 'recording') bad('Complete recording endpoint required');
        if (record.data && record.mode !== 'automatic') bad('Metrics already uploaded', 409);
        const { bytes, value } = await readJSON(request); validatePhoneData(value, record.consent);
        const receipt = await digest(bytes); record.bytes += bytes.length - (record.data?.bytes || 0); record.data = receipt; record.updated_at = Date.now();
        if (record.bytes > MAX_SESSION) bad('Session limit', 413);
        await this.ctx.storage.put({ ['data:' + id]: bytes, ['session:' + id]: record });
        // 4.4 „Post de pază” on the site: a 14-day daily rollup survives the 24 h session. A rollup error never loses the upload.
        if (value.app_usage) { try { await applyUsageRollup(this.ctx.storage, id, value, record.updated_at); } catch {} }
        // Mirror: ziua pe hartă (loc-day, 24 h), la fel de separată de încărcare (site-location.mjs).
        if (value.locations || value.visits) { try { await applyLocationRollup(this.ctx.storage, id, value, record.updated_at); } catch {} }
        return reply(receipt, 201);
      }
    }
    if(match[2]==='recording' && !match[3] && request.method==='POST') {
      if(record.mode!=='recording' || !record.consent.audio) bad('Recording session required',403);
      if(request.headers.get('content-type')?.split(';')[0]!=='audio/mp4') bad('M4A required',415);
      const from=Number(request.headers.get('x-recorded-from')),to=Number(request.headers.get('x-recorded-to'));
      const bytes=recordingBytes ?? await readBytes(request,RECORDING_MAX_BYTES),duration_ms=checkRecording(bytes,from,to),receipt=await digest(bytes);
      if(record.items.length) { const old=record.items[0];if(old.sha256===receipt.sha256 && old.recorded_from===from && old.recorded_to===to)return reply(old);bad('Recording already uploaded',409); }
      if(record.sleep_session_id){
        const sleep=await this.ctx.storage.get('sleep:'+record.sleep_session_id);
        if(!sleep||sleep.expires_at<=Date.now())bad('Sleep session unavailable',410);
        if(duration_ms>300000||bytes.length>2*1024*1024)bad('Sleep chunk exceeds five minutes or 2 MiB',413);
        if(from<sleep.started_at-5000||to>(sleep.ended_at||sleep.planned_stop_at)+5000)bad('Recording outside sleep interval');
        const retained=[...(await this.ctx.storage.list({prefix:'session:'})).values()].filter(r=>r.sleep_session_id).reduce((sum,r)=>sum+r.bytes,0);
        if(retained+bytes.length>384*1024*1024)bad('Sleep storage limit (384 MiB)',413);
      }
      const item={item_id:crypto.randomUUID(),kind:'audio',sequence:0,name:'FORJA-'+new Date(from).toISOString().replace(/[:.]/g,'-')+'.m4a',media_type:'audio/mp4',recorded_from:from,recorded_to:to,duration_ms,...receipt,received_at:Date.now()};
      await this.env.RECORDS.put(record.prefix+item.item_id,bytes,{httpMetadata:{contentType:'application/octet-stream'}});
      record.items.push(item);record.bytes=bytes.length;record.updated_at=Date.now();await this.ctx.storage.put('session:'+id,record);return reply(item,201);
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
        if(record.mode==='recording')bad('Complete recording endpoint required');
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
