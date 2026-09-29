// FORJA 4.4 — secțiunea „somn”: nopțile din Firestore, cronologia și sunetul din R2 `forja-sleep` (chei numai din uid-ul verificat).
import { SITE_RULES, MIN, reply, failure, num, int, str, time, where, dayParam } from './shared.mjs';
import { DAY } from '../site-time.mjs';

const SLEEP_ID = /^s\d{1,19}$/;
const LABELS = { talk: 'Vorbit', snore: 'Sforăit', cough: 'Tuse', breath: 'Respirație' };
function eventLabel(e) {
  const name = LABELS[e.type] || 'Zgomot', i = num(e.intensity) || 0;
  const word = e.type === 'talk' ? '' : i >= 0.7 ? 'puternic' : i >= 0.4 ? 'moderat' : i > 0 ? 'redus' : '';
  return word ? `${name} · ${word}` : name;
}
const expired = (o, now) => { const at = Number(o.customMetadata?.at) || 0, ttl = Number(o.customMetadata?.ttl) || 7 * DAY; return at > 0 && now - at > ttl; };
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
/** Map(sid → {chunks, analysis, status}) for every night still in R2 (7 days), from ONE listing of the user's prefix. */
async function sleepIndex(env, uid, now) {
  const index = new Map();
  if (!env.SLEEP) return index;
  let objects = [];
  try { objects = await listAll(env.SLEEP, uid + '/'); } catch { return index; }
  for (const o of objects) {
    const m = /^[^/]+\/(s\d{1,19})\/(chunk_(\d{1,3})\.m4a|analysis\.json)$/.exec(o.key);
    if (!m || expired(o, now)) continue;
    const row = index.get(m[1]) || { chunks: 0, analysis: false, status: null };
    if (m[3] !== undefined) row.chunks++; else { row.analysis = true; row.status = o.customMetadata?.status || null; }
    index.set(m[1], row);
  }
  return index;
}
const audioState = row => (!row || !row.chunks ? 'none' : row.analysis && row.status !== 'processing' ? 'ready' : 'pending');
export async function somn({ env, fs, uid, now, url }) {
  const days = dayParam(url, SITE_RULES.somn_days);
  const [rows, index] = await Promise.all([
    fs.query(`users/${uid}`, 'sleep', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', now - days * DAY)], orderBy: 'startAt', limit: 100,
      select: ['startAt', 'endAt', 'score', 'deepMin', 'lightMin', 'remMin', 'snoreMin', 'talkCount', 'coverageMin', 'summary'] }),
    sleepIndex(env, uid, now),
  ]);
  return { nights: (rows || []).filter(r => SLEEP_ID.test(r.id) && time(r.startAt)).map(r => {
    const endAt = time(r.endAt) && r.endAt > r.startAt ? r.endAt : null;
    return { id: r.id, startAt: r.startAt, endAt, minutes: endAt ? Math.round((endAt - r.startAt) / MIN) : 0, score: int(r.score), deepMin: int(r.deepMin), lightMin: int(r.lightMin),
      remMin: int(r.remMin), snoreMin: int(r.snoreMin), talkCount: int(r.talkCount), coverageMin: int(r.coverageMin), summary: str(r.summary, 2000), audio: audioState(index.get(r.id)) };
  }) };
}
export async function somnNight({ env, fs, uid, now }, id) {
  if (!SLEEP_ID.test(id)) return failure('Noaptea nu există.', 404);
  const night = await fs.get(`users/${uid}/sleep/${id}`, ['startAt', 'endAt', 'summary']);
  if (!night || !time(night.startAt)) return failure(fs.unreachable ? 'Datele nu răspund acum. Reîncearcă puțin mai târziu.' : 'Noaptea nu există.', fs.unreachable ? 503 : 404);
  // The app sends chunk and event times relative to the start of the recording; an absolute epoch (older clients, tests) is kept as is.
  const base = night.startAt, clockOf = v => (v > 1e12 ? v : base + v);
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
  for (const o of objects) {
    const m = /\/chunk_(\d{1,3})\.m4a$/.exec(o.key);
    if (!m || expired(o, now)) continue;
    const i = Number(m[1]), meta = o.customMetadata || {};
    const from = Number.isFinite(Number(meta.from)) && meta.from !== undefined ? Number(meta.from) : num(known.get(i)?.from);
    const dur = Number.isFinite(Number(meta.dur)) && meta.dur !== undefined ? Number(meta.dur) : num(known.get(i)?.dur);
    if (from === null) continue;
    chunks.push({ i, startAt: clockOf(from), durationMs: dur, from });
  }
  chunks.sort((a, b) => a.i - b.i);
  const fromOf = new Map(chunks.map(c => [c.i, c.from]));
  for (const c of chunks) delete c.from;
  for (const [i, c] of known) if (!fromOf.has(i) && num(c.from) !== null) fromOf.set(i, c.from);
  const events = (Array.isArray(analysis?.events) ? analysis.events : []).slice(0, SITE_RULES.events_max).filter(e => e && num(e.from) !== null).map(e => {
    const chunk = Number.isInteger(e.chunk) ? e.chunk : null, start = chunk === null ? null : fromOf.get(chunk);
    return { t: clockOf(e.from), kind: String(e.type || 'noise'), label: eventLabel(e), text: str(e.transcript, 400), chunk,
      offsetMs: start === undefined || start === null ? null : Math.max(0, e.from - start), durationMs: num(e.to) !== null ? Math.max(0, e.to - e.from) : 0 };
  });
  return reply({ id, summary: str(night.summary, 2000), events, chunks });
}
/** One ≤ 35-min AAC chunk of the caller's own night, with Range (206) for seeking; 404 once the 7 days are over. */
export async function somnChunk({ env, uid, now, request }, id, index) {
  if (!SLEEP_ID.test(id) || !/^\d{1,3}$/.test(index)) return failure('Sunetul nu există.', 404);
  if (!env.SLEEP) return failure('Sunetul nopților nu este disponibil pe site.', 404);
  const key = `${uid}/${id}/chunk_${Number(index)}.m4a`;
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
