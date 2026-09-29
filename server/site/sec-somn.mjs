// FORJA 4.4 — secțiunea „somn”: nopțile din Firestore, cronologia și sunetul din R2 `forja-sleep` (chei numai din uid-ul verificat).
// Oglinda (pachetul B): starea nopții (în curs, întreruptă), fazele, trezirile, latența, liniile scorului, alarma, sunetele,
// stingerea, urcarea sunetului (jurnal, fără contract) și cronologia fără sunet din users/{uid}/sleepEvents/s{id} (momentele, vorbele — doar cu
// contractul v4, contractGate(me.contract, 4); rămâne și după ce sunetul expiră în 7 zile). Sunetul se ascultă prin URL-uri
// semnate scurt (HMAC pe uid + noapte + bucată + expirare, 10 min, cheia SLEEP_URL_KEY), ca <audio> să poată derula cu Range.
import { SITE_RULES, MIN, HOUR, reply, failure, num, int, str, time, where, dayParam, contractGate } from './shared.mjs';
import { DAY } from '../site-time.mjs';

const SLEEP_ID = /^s\d{1,19}$/;
const LABELS = { talk: 'Vorbit', snore: 'Sforăit', cough: 'Tuse', breath: 'Respirație' };
/** O noapte „în curs” mai veche de atât e o veghe pe care telefonul n-a mai închis-o: se arată ca întreruptă. */
export const LIVE_MAX_MS = 16 * HOUR;
/** Cât trăiește un URL semnat de bucată (și cât îl mai acceptăm cu ceasul puțin decalat). */
export const AUDIO_URL_MS = 10 * MIN;
/** Cât ține telefonul bucățile care n-au urcat (SleepTrackService.cleanupRecordings). */
const LOCAL_KEEP_MS = 3 * DAY;
/** Un moment de pe telefon și unul al serverului de același fel, la cel mult atât distanță, sunt același moment. */
const SAME_EVENT_MS = 5000;
const UPLOAD_STATES = new Set(['none', 'waiting_wifi', 'waiting_net', 'waiting_battery', 'uploading', 'analyzing', 'done', 'failed']);
const STAGES = new Set(['deep', 'light', 'rem', 'awake']);
const SOUND_KEYS = new Set(['rain', 'storm', 'wind', 'stream', 'fire', 'forest']);
const ALARM_REASONS = new Set(['cycle', 'movement', 'deadline', 'snooze']);

function eventLabel(e) {
  const name = LABELS[e.type] || 'Zgomot', i = num(e.intensity) || 0;
  const word = e.type === 'talk' ? '' : i >= 0.7 ? 'puternic' : i >= 0.4 ? 'moderat' : i > 0 ? 'redus' : '';
  return word ? `${name} · ${word}` : name;
}
const expiryOf = o => { const at = Number(o.customMetadata?.at) || 0, ttl = Number(o.customMetadata?.ttl) || 7 * DAY; return at > 0 ? at + ttl : null; };
const expired = (o, now) => { const e = expiryOf(o); return e !== null && now > e; };
async function listAll(bucket, prefix, pages = 5) {
  const out = [];
  let cursor;
  for (let i = 0; i < pages; i++) {
    const page = await bucket.list({ prefix, cursor, limit: 1000, include: ['customMetadata'] });
    out.push(...(page.objects || []));
    if (!page.truncated) break;
    cursor = page.cursor;
  }
  return out;
}
/** Map(sid → {chunks, analysis, status, expiresAt}) for every night still in R2 (7 days), from ONE listing of the user's prefix. */
async function sleepIndex(env, uid, now) {
  const index = new Map();
  if (!env.SLEEP) return index;
  let objects = [];
  try { objects = await listAll(env.SLEEP, uid + '/'); } catch { return index; }
  for (const o of objects) {
    const m = /^[^/]+\/(s\d{1,19})\/(chunk_(\d{1,3})\.m4a|analysis\.json)$/.exec(o.key);
    if (!m || expired(o, now)) continue;
    const row = index.get(m[1]) || { chunks: 0, analysis: false, status: null, expiresAt: null };
    if (m[3] !== undefined) {
      row.chunks++;
      const e = expiryOf(o);
      if (e !== null && (row.expiresAt === null || e < row.expiresAt)) row.expiresAt = e;
    } else { row.analysis = true; row.status = o.customMetadata?.status || null; }
    index.set(m[1], row);
  }
  return index;
}

// ─────────────────────────────── ce a scris telefonul ───────────────────────────────
/** Urcarea sunetului, cum a scris-o telefonul (SleepNightDoc.audioMap). */
function uploadOf(a) {
  if (!a || typeof a !== 'object') return null;
  const state = UPLOAD_STATES.has(a.state) ? a.state : null;
  if (!state) return null;
  return { state, chunks: int(a.chunks) ?? 0, uploaded: int(a.uploaded) ?? 0, lastError: str(a.lastError, 120), audioStartAt: time(a.audioStartAt), recordedUntil: time(a.recordedUntil) };
}
function alarmOf(a) {
  if (!a || typeof a !== 'object' || !time(a.target)) return null;
  return { target: a.target, windowMin: int(a.windowMin), firedAt: time(a.firedAt), reason: ALARM_REASONS.has(a.reason) ? a.reason : null, snoozes: Math.max(0, int(a.snoozes) ?? 0) };
}
function soundsOf(list) {
  return (Array.isArray(list) ? list : []).filter(s => s && SOUND_KEYS.has(s.sound) && int(s.minutes) > 0).slice(0, 6).map(s => ({ sound: s.sound, minutes: int(s.minutes) }));
}
function bedtimeOf(b) {
  if (!b || typeof b !== 'object') return null;
  const minute = int(b.minute);
  return minute === null || minute < 0 || minute >= 1440 ? null : { minute, reminder: b.reminder === true };
}
/**
 * Sunetul nopții, într-un cuvânt: ready · pending (analiză) · waiting (bucățile nu au urcat: Wi-Fi, baterie) ·
 * failed · expired (a fost, cele 7 zile au trecut) · none (fără microfon). R2 are ultimul cuvânt când bucățile sunt acolo.
 */
export function audioState(row, upload, endAt, now) {
  if (row?.chunks) return row.analysis && row.status !== 'processing' ? 'ready' : 'pending';
  const s = upload?.state, age = endAt ? now - endAt : 0, sent = (upload?.uploaded || 0) > 0;
  if (s === 'waiting_wifi' || s === 'waiting_net' || s === 'waiting_battery' || s === 'uploading') {
    // telefonul șterge după 3 zile bucățile care n-au urcat: ce a urcat a expirat, restul nu mai urcă
    if (age > LOCAL_KEEP_MS) return sent ? 'expired' : 'failed';
    return 'waiting';
  }
  if (s === 'analyzing') return age > 7 * DAY ? 'expired' : 'pending';
  // bucățile au ajuns pe server (analiza s-a putut opri), dar nu mai sunt în R2: au expirat, nu „n-au urcat”
  if (s === 'failed') return sent ? 'expired' : 'failed';
  if (s === 'done' || (upload?.recordedUntil && upload.recordedUntil < now) || (upload?.chunks && endAt && now - endAt > 7 * DAY)) return 'expired';
  return 'none';
}
/** Starea veghei: live (în curs) · interrupted (telefonul a oprit-o sau n-a mai închis-o) · done. */
function stateOf(r, now) {
  const endAt = time(r.endAt) && r.endAt > r.startAt ? r.endAt : null;
  if (r.state === 'recording' && !endAt) return now - r.startAt <= LIVE_MAX_MS ? 'live' : 'interrupted';
  if (r.state === 'interrupted') return 'interrupted';
  return endAt ? 'done' : 'interrupted';
}
/** „startMin,endMin,tip;…” (minute de la începutul veghei) → [{from, to, stage}] pe ceas, pentru hipnogramă. */
export function phasesOf(raw, startAt) {
  if (typeof raw !== 'string' || !time(startAt)) return [];
  const out = [];
  for (const seg of raw.split(';').slice(0, 400)) {
    const [a, b, stage] = seg.split(',');
    const from = Number(a), to = Number(b);
    if (!Number.isInteger(from) || !Number.isInteger(to) || from < 0 || to <= from || to > 24 * 60 || !STAGES.has(stage)) continue;
    out.push({ from: startAt + from * MIN, to: startAt + to * MIN, stage });
  }
  return out.sort((x, y) => x.from - y.from);
}
function scoreLinesOf(list) {
  return (Array.isArray(list) ? list : []).filter(l => l && int(l.delta) !== null && str(l.reason, 80)).slice(0, 8).map(l => ({ delta: int(l.delta), reason: str(l.reason, 80) }));
}
/** Cifrele stadiilor (jurnalul, fără contract) care încap pe cardul nopții. */
function sleepDetail(t) {
  if (!t || (t.latencyMin === undefined && t.awakenings === undefined && t.scoreLines === undefined)) return null;
  return { latencyMin: int(t.latencyMin), awakenings: int(t.awakenings), awakeMin: int(t.awakeMin), scoreLines: scoreLinesOf(t.scoreLines) };
}

const STAGING_FIELDS = ['awakeMin', 'latencyMin', 'awakenings', 'scoreLines'];
const LIST_FIELDS = ['startAt', 'endAt', 'score', 'deepMin', 'lightMin', 'remMin', 'snoreMin', 'talkCount', 'coverageMin', 'summary',
  'movements', 'snoreEvents', 'soundEvents', 'state', 'alarm', 'sounds', 'bedtime', 'audio', ...STAGING_FIELDS];
const NIGHT_FIELDS = [...LIST_FIELDS, 'phases'];
// fazele / cifrele stau acum în jurnal; din cronologie se citesc doar pentru nopțile scrise înainte (v4)
const TIMELINE_FIELDS = ['audioStartAt', 'phases', ...STAGING_FIELDS, 'events', 'talkSummary', 'limits', 'stats', 'analysis'];

export async function somn({ env, fs, uid, now, url }) {
  const days = dayParam(url, SITE_RULES.somn_days);
  const [rows, index, me] = await Promise.all([
    fs.query(`users/${uid}`, 'sleep', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', now - days * DAY)], orderBy: 'startAt', limit: 100, select: LIST_FIELDS }),
    sleepIndex(env, uid, now),
    fs.get(`users/${uid}`, ['contract']),
  ]);
  const timeline = contractGate(me?.contract, 4);
  let live = null;
  const nights = [];
  for (const r of rows || []) {
    if (!SLEEP_ID.test(r.id) || !time(r.startAt)) continue;
    const state = stateOf(r, now), alarm = alarmOf(r.alarm);
    if (state === 'live') { if (!live || r.startAt > live.startAt) live = { id: r.id, startAt: r.startAt, alarm, sounds: soundsOf(r.sounds) }; continue; }
    const endAt = time(r.endAt) && r.endAt > r.startAt ? r.endAt : null, row = index.get(r.id), upload = uploadOf(r.audio);
    nights.push({ id: r.id, startAt: r.startAt, endAt, minutes: endAt ? Math.round((endAt - r.startAt) / MIN) : 0, score: int(r.score), deepMin: int(r.deepMin), lightMin: int(r.lightMin),
      remMin: int(r.remMin), snoreMin: int(r.snoreMin), talkCount: int(r.talkCount), coverageMin: int(r.coverageMin), summary: str(r.summary, 2000), audio: audioState(row, upload, endAt, now),
      state, movements: int(r.movements), snoreEvents: int(r.snoreEvents), soundEvents: int(r.soundEvents), alarm, sounds: soundsOf(r.sounds), bedtime: bedtimeOf(r.bedtime),
      upload, audioExpiresAt: row?.chunks ? row.expiresAt : null });
  }
  // Adormirea, trezirile și liniile scorului vin din jurnal (fără contract), pentru ultima noapte.
  const last = nights.reduce((m, n) => (!m || n.startAt > m.startAt ? n : m), null);
  const detail = last && sleepDetail((rows || []).find(r => r.id === last.id));
  if (detail) last.sleep = detail;
  return { nights, live, days, timeline };
}

// ─────────────────────────────── URL-urile semnate ale sunetului ───────────────────────────────
const b64url = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
function unb64url(s) {
  try { const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/')); return Uint8Array.from(bin, c => c.charCodeAt(0)); } catch { return null; }
}
const keyCache = new Map();
async function hmacKey(secret) {
  if (!keyCache.has(secret)) keyCache.set(secret, crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']));
  return keyCache.get(secret);
}
const payload = (uid, id, i, exp) => new TextEncoder().encode(`somn.v1.${uid}.${id}.${i}.${exp}`);
/** `/insights/audio/somn/{uid}/{sid}/{i}?exp=&sig=` — null fără cheie (site-ul redă atunci bucata ca înainte, prin API). */
export async function signedChunkUrl(env, uid, id, i, now) {
  if (typeof env.SLEEP_URL_KEY !== 'string' || env.SLEEP_URL_KEY.length < 16) return null;
  const exp = now + AUDIO_URL_MS;
  const sig = b64url(await crypto.subtle.sign('HMAC', await hmacKey(env.SLEEP_URL_KEY), payload(uid, id, i, exp)));
  return `/insights/audio/somn/${encodeURIComponent(uid)}/${id}/${i}?exp=${exp}&sig=${sig}`;
}
const SIGNED_PATH = /^\/insights\/audio\/somn\/([A-Za-z0-9_-]{1,128})\/(s\d{1,19})\/(\d{1,3})$/;
/** True for the signed audio route (answered before the Firebase token check: the signature is the credential). */
export const isSignedAudio = path => path.startsWith('/insights/audio/');
/**
 * GET/HEAD pe URL-ul semnat: aceeași bucată cu Range ca somnChunk, fără token. Semnătura leagă uid-ul, noaptea, bucata și
 * expirarea; o semnătură expirată, alterată sau pentru alt cont întoarce 403, fără să atingă R2.
 */
export async function somnSigned(request, env, now = Date.now()) {
  const url = new URL(request.url), m = SIGNED_PATH.exec(url.pathname);
  if (!m) return failure('Sunetul nu există.', 404);
  if (request.method !== 'GET' && request.method !== 'HEAD') return failure('Metodă nepermisă.', 405);
  const [, uid, id, index] = m, i = Number(index);
  const exp = Number(url.searchParams.get('exp')), sig = unb64url(url.searchParams.get('sig') || '');
  if (typeof env.SLEEP_URL_KEY !== 'string' || env.SLEEP_URL_KEY.length < 16) return failure('Sunetul nu este disponibil.', 404);
  if (!Number.isSafeInteger(exp) || exp < now || exp > now + AUDIO_URL_MS + MIN || !sig || sig.length !== 32) return failure('Linkul sunetului a expirat. Redeschide noaptea.', 403);
  const ok = await crypto.subtle.verify('HMAC', await hmacKey(env.SLEEP_URL_KEY), sig, payload(uid, id, i, exp));
  if (!ok) return failure('Linkul sunetului a expirat. Redeschide noaptea.', 403);
  const res = await serveChunk(env, uid, id, i, now, request);
  return request.method === 'HEAD' ? new Response(null, { status: res.status, headers: res.headers }) : res;
}

// ─────────────────────────────── noaptea ───────────────────────────────
/** Bucata în care cade un moment de ceas (pentru momentele de pe telefon, care nu știu de bucăți). */
function chunkAt(chunks, t) {
  for (const c of chunks) if (t >= c.startAt && (!c.durationMs || t < c.startAt + c.durationMs)) return c;
  return null;
}
export async function somnNight({ env, fs, uid, now }, id) {
  if (!SLEEP_ID.test(id)) return failure('Noaptea nu există.', 404);
  const [night, me] = await Promise.all([fs.get(`users/${uid}/sleep/${id}`, NIGHT_FIELDS), fs.get(`users/${uid}`, ['contract'])]);
  if (!night || !time(night.startAt)) return failure(fs.unreachable ? 'Datele nu răspund acum. Reîncearcă puțin mai târziu.' : 'Noaptea nu există.', fs.unreachable ? 503 : 404);
  const v4 = contractGate(me?.contract, 4);
  const tl = v4 ? await fs.get(`users/${uid}/sleepEvents/${id}`, TIMELINE_FIELDS) : null;
  const upload = uploadOf(night.audio);
  // Timpii bucăților și ai evenimentelor sunt de la începutul ÎNREGISTRĂRII (manifest.startedAt), nu al veghei: o veghe
  // reluată pornește microfonul din nou. Fără audioStartAt (clienți vechi) rămâne startAt; un epoch absolut rămâne ca atare.
  const base = upload?.audioStartAt || time(tl?.audioStartAt) || night.startAt, clockOf = v => (v > 1e12 ? v : base + v);
  let analysis = null, objects = [];
  if (env.SLEEP) {
    try {
      const [obj, list] = await Promise.all([env.SLEEP.get(`${uid}/${id}/analysis.json`), listAll(env.SLEEP, `${uid}/${id}/chunk_`, 1)]);
      objects = list;
      if (obj && !expired(obj, now)) analysis = JSON.parse(await obj.text());
    } catch { analysis = null; }
  }
  const known = new Map((Array.isArray(analysis?.state?.chunks) ? analysis.state.chunks : []).filter(c => Number.isInteger(c?.index)).map(c => [c.index, c]));
  const chunks = [];
  let audioExpiresAt = null;
  for (const o of objects) {
    const m = /\/chunk_(\d{1,3})\.m4a$/.exec(o.key);
    if (!m || expired(o, now)) continue;
    const i = Number(m[1]), meta = o.customMetadata || {};
    const from = Number.isFinite(Number(meta.from)) && meta.from !== undefined ? Number(meta.from) : num(known.get(i)?.from);
    const dur = Number.isFinite(Number(meta.dur)) && meta.dur !== undefined ? Number(meta.dur) : num(known.get(i)?.dur);
    if (from === null) continue;
    const e = expiryOf(o);
    if (e !== null && (audioExpiresAt === null || e < audioExpiresAt)) audioExpiresAt = e;
    chunks.push({ i, startAt: clockOf(from), durationMs: dur, from });
  }
  chunks.sort((a, b) => a.i - b.i);
  const fromOf = new Map(chunks.map(c => [c.i, c.from]));
  for (const c of chunks) delete c.from;
  for (const [i, c] of known) if (!fromOf.has(i) && num(c.from) !== null) fromOf.set(i, c.from);
  let events = (Array.isArray(analysis?.events) ? analysis.events : []).slice(0, SITE_RULES.events_max).filter(e => e && num(e.from) !== null).map(e => {
    const chunk = Number.isInteger(e.chunk) ? e.chunk : null, start = chunk === null ? null : fromOf.get(chunk);
    return { t: clockOf(e.from), kind: String(e.type || 'noise'), label: eventLabel(e), text: str(e.transcript, 400), chunk,
      offsetMs: start === undefined || start === null ? null : Math.max(0, e.from - start), durationMs: num(e.to) !== null ? Math.max(0, e.to - e.from) : 0, source: 'server' };
  });
  // Cronologia păstrată (v4): momentele de pe telefon mereu; ale serverului doar când analiza din R2 a expirat.
  const kept = Array.isArray(tl?.events) ? tl.events.slice(0, 200).filter(e => e && time(e.at)) : [];
  const fallback = !analysis && kept.some(e => e.source === 'server');
  // întâi momentele serverului, apoi ale telefonului, ca un moment prins de amândouă să rămână unul singur
  for (const e of [...kept.filter(e => e.source !== 'phone'), ...kept.filter(e => e.source === 'phone')]) {
    if (e.source !== 'phone' && analysis) continue;
    const c = chunkAt(chunks, e.at), kind = (LABELS[e.kind] || e.kind === 'noise') ? String(e.kind) : 'noise';
    if (e.source === 'phone') {
      const dur = Math.max(0, int(e.dur) || 0);
      const twin = events.find(x => x.source === 'server' && x.kind === kind && e.at <= x.t + x.durationMs + SAME_EVENT_MS && e.at + dur >= x.t - SAME_EVENT_MS);
      if (twin) { twin.phoneClip = true; continue; }
    }
    events.push({ t: e.at, kind, label: eventLabel({ type: kind, intensity: e.intensity }), text: str(e.text, 400), chunk: c ? c.i : null,
      offsetMs: c ? Math.max(0, e.at - c.startAt) : null, durationMs: Math.max(0, int(e.dur) || 0), source: e.source === 'phone' ? 'phone' : 'server' });
  }
  events.sort((a, b) => a.t - b.t);
  if (events.length > SITE_RULES.events_max) events = events.slice(0, SITE_RULES.events_max);
  for (const c of chunks) { const u = await signedChunkUrl(env, uid, id, c.i, now); if (u) c.url = u; }
  const st = analysis?.stats && typeof analysis.stats === 'object' ? analysis.stats : null, keptStats = tl?.stats && typeof tl.stats === 'object' ? tl.stats : null;
  const cov = analysis?.coverage && typeof analysis.coverage === 'object' ? analysis.coverage : null;
  const limits = (Array.isArray(analysis?.limitari) ? analysis.limitari : Array.isArray(tl?.limits) ? tl.limits : []).map(l => str(l, 200)).filter(Boolean).slice(0, 6);
  const endAt = time(night.endAt) && night.endAt > night.startAt ? night.endAt : null;
  return reply({
    id, summary: str(night.summary, 2000), events, chunks,
    startAt: night.startAt, endAt, state: stateOf(night, now), audioStartAt: base, audioExpiresAt, urlExpiresAt: chunks.some(c => c.url) ? now + AUDIO_URL_MS : null,
    upload, alarm: alarmOf(night.alarm), sounds: soundsOf(night.sounds), bedtime: bedtimeOf(night.bedtime),
    analysis: analysis || keptStats ? {
      status: str(analysis?.status, 20) || str(tl?.analysis, 20), fallback,
      progress: analysis?.progress && typeof analysis.progress === 'object' ? { done: int(analysis.progress.done) ?? 0, failed: int(analysis.progress.failed) ?? 0, total: int(analysis.progress.total) ?? 0 } : null,
      coverage: cov ? { analyzedMin: Math.round((num(cov.analyzedMs) || 0) / MIN), totalMin: Math.round((num(cov.totalMs) || 0) / MIN) } : keptStats ? { analyzedMin: int(keptStats.coverageMin) ?? 0, totalMin: int(keptStats.totalMin) ?? 0 } : null,
      stats: st ? { snoreMin: int(st.snoreMinutes), snoreEpisodes: int(st.snoreEpisodes), coughs: int(st.coughs), noises: int(st.noises),
        longestSnore: st.longestSnore && num(st.longestSnore.from) !== null ? { t: clockOf(st.longestSnore.from), minutes: int(st.longestSnore.minutes) } : null }
        : keptStats ? { snoreMin: null, snoreEpisodes: int(keptStats.snoreEpisodes), coughs: int(keptStats.coughCount), noises: null, longestSnore: null } : null,
      limits, sources: (Array.isArray(analysis?.sources) ? analysis.sources : []).map(s => str(s, 80)).filter(Boolean).slice(0, 4),
    } : null,
    timeline: v4, phases: phasesOf(night.phases ?? tl?.phases, night.startAt), sleep: sleepDetail(night) || sleepDetail(tl), talkSummary: str(tl?.talkSummary, 600),
  });
}

// ─────────────────────────────── bucata de sunet ───────────────────────────────
async function serveChunk(env, uid, id, i, now, request) {
  if (!env.SLEEP) return failure('Sunetul nopților nu este disponibil pe site.', 404);
  const key = `${uid}/${id}/chunk_${i}.m4a`;
  const head = await env.SLEEP.head(key);
  if (!head || expired(head, now)) return failure('Sunetul a expirat. Se păstrează 7 zile.', 404);
  const size = head.size;
  const headers = { 'content-type': head.httpMetadata?.contentType || 'audio/mp4', 'accept-ranges': 'bytes', 'cache-control': 'private, no-store', 'x-content-type-options': 'nosniff' };
  const range = /^bytes=(\d*)-(\d*)$/.exec(request.headers.get('range') || '');
  if (range && (range[1] || range[2])) {
    const start = range[1] ? Number(range[1]) : Math.max(0, size - Number(range[2]));
    const end = range[1] && range[2] ? Math.min(Number(range[2]), size - 1) : size - 1;
    if (!Number.isFinite(start) || !Number.isFinite(end) || start > end || start >= size) return new Response(null, { status: 416, headers: { 'content-range': `bytes */${size}`, 'cache-control': 'no-store' } });
    const obj = await env.SLEEP.get(key, { range: { offset: start, length: end - start + 1 } });
    if (!obj) return failure('Sunetul a expirat. Se păstrează 7 zile.', 404);
    return new Response(obj.body, { status: 206, headers: { ...headers, 'content-range': `bytes ${start}-${end}/${size}`, 'content-length': String(end - start + 1) } });
  }
  const obj = await env.SLEEP.get(key);
  if (!obj) return failure('Sunetul a expirat. Se păstrează 7 zile.', 404);
  return new Response(obj.body, { headers: { ...headers, 'content-length': String(size) } });
}
/** One ≤ 35-min AAC chunk of the caller's own night, with Range (206) for seeking; 404 once the 7 days are over. */
export async function somnChunk({ env, uid, now, request }, id, index) {
  if (!SLEEP_ID.test(id) || !/^\d{1,3}$/.test(index)) return failure('Sunetul nu există.', 404);
  return serveChunk(env, uid, id, Number(index), now, request);
}
