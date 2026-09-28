// forja-api · POST /v1/diag/music — încercările telefonului de a porni muzica, ca „Pornește muzica” să poată fi reparat din date,
// nu din presupuneri. Fără titluri, artiști sau id-uri de piese: doar aplicația (pachetul), treapta încercată, tipul sesiunii,
// rezultatul și durata. Se păstrează ultimele 500 de evenimente per cont în R2 (MEDIA, sub _admin/, invizibil public),
// sub o etichetă = primele 16 caractere hex din SHA-256(uid). Comanda de admin `music [n]` le afișează.

export const MUSIC_DIAG = Object.freeze({
  max_events: 50, keep: 500, max_body: 16384,
  wants: ['resume', 'mymusic', 'top', 'workout', 'probe'],
  kinds: ['music', 'spoken', 'video', 'unknown'],
  results: ['ok', 'refused', 'timeout', 'wrong_kind', 'wrong_track', 'error', 'skipped', 'needs_tap'],
});
const PREFIX = '_admin/music/';
const DAY = 86400000;
const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' } });
const plain = (v, max) => (typeof v === 'string' ? v.replace(/[\u0000-\u001f\u007f]+/g, ' ').trim().slice(0, max) : null);

/** The stored owner tag: not the uid itself. */
export async function userTag(uid) {
  const bytes = new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode('music|' + uid)));
  return [...bytes.slice(0, 8)].map(b => b.toString(16).padStart(2, '0')).join('');
}

/** `device` / `app` of the request: a short line, or a flat object of a few primitive fields (model, sdk, oneui…). */
function meta(v) {
  if (typeof v === 'string') return plain(v, 120) || null;
  if (!v || typeof v !== 'object' || Array.isArray(v)) return null;
  const out = {};
  for (const [k, x] of Object.entries(v).slice(0, 8)) {
    if (!/^[A-Za-z_][A-Za-z0-9_]{0,23}$/.test(k) || /title|artist|track|album|song|media|name/i.test(k)) continue;
    if (typeof x === 'number' && Number.isFinite(x)) out[k] = x;
    else if (typeof x === 'boolean') out[k] = x;
    else if (typeof x === 'string') out[k] = plain(x, 60);
  }
  return Object.keys(out).length ? out : null;
}

/** One event, whitelisted field by field (anything else, titles included, is dropped); null when it is not usable. */
export function cleanEvent(e, now = Date.now()) {
  if (!e || typeof e !== 'object' || Array.isArray(e)) return null;
  if (!Number.isSafeInteger(e.at) || e.at < now - 30 * DAY || e.at > now + DAY) return null;
  if (!MUSIC_DIAG.wants.includes(e.want) || !MUSIC_DIAG.results.includes(e.result)) return null;
  if (typeof e.rung !== 'string' || !/^[A-Za-z0-9_:.-]{1,40}$/.test(e.rung)) return null;
  if (e.pkg !== undefined && e.pkg !== null && (typeof e.pkg !== 'string' || !/^[A-Za-z0-9_.:-]{1,120}$/.test(e.pkg))) return null;
  if (e.kind !== undefined && e.kind !== null && !MUSIC_DIAG.kinds.includes(e.kind)) return null;
  if (typeof e.ms !== 'number' || !Number.isFinite(e.ms) || e.ms < 0) return null;
  return { at: e.at, want: e.want, rung: e.rung, pkg: e.pkg ?? null, ver: plain(e.ver, 40) || null, kind: e.kind ?? null, result: e.result,
    ms: Math.min(600000, Math.round(e.ms)), err: plain(e.err, 160) || null };
}

async function readEvents(bucket, key) {
  try { const o = await bucket.get(key); const d = o ? JSON.parse(await o.text()) : null; return Array.isArray(d?.events) ? d.events : []; } catch { return []; }
}

/** POST /v1/diag/music (Firebase-authenticated like every /v1 route). */
export async function handleMusicDiag(request, env, uid, now = Date.now()) {
  if (!env.MEDIA) return json({ error: 'Jurnalul de diagnoză nu e configurat.' }, 503);
  if (Number(request.headers.get('content-length') || 0) > MUSIC_DIAG.max_body) return json({ error: 'Lot prea mare.' }, 413);
  const text = await request.text();
  if (text.length > MUSIC_DIAG.max_body) return json({ error: 'Lot prea mare.' }, 413);
  let body;
  try { body = JSON.parse(text); } catch { return json({ error: 'Cerere invalidă.' }, 400); }
  if (!body || typeof body !== 'object' || !Array.isArray(body.events) || !body.events.length) return json({ error: 'Lipsesc evenimentele.' }, 400);
  if (body.events.length > MUSIC_DIAG.max_events) return json({ error: 'Cel mult 50 de evenimente pe cerere.' }, 400);
  const device = meta(body.device), app = meta(body.app);
  const events = body.events.map(e => cleanEvent(e, now)).filter(Boolean);
  if (!events.length) return json({ error: 'Niciun eveniment valid.' }, 400);
  const u = await userTag(uid), key = PREFIX + u + '.json';
  const kept = [...await readEvents(env.MEDIA, key), ...events.map(e => ({ ...e, device, app, rx: now }))].slice(-MUSIC_DIAG.keep);
  await env.MEDIA.put(key, JSON.stringify({ u, updatedAt: now, events: kept }), { httpMetadata: { contentType: 'application/json' } });
  return json({ ok: true, stored: events.length, dropped: body.events.length - events.length });
}

const clock = new Intl.DateTimeFormat('ro-RO', { timeZone: 'Europe/Bucharest', day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
const deviceText = d => (!d ? '' : typeof d === 'string' ? d : Object.values(d).join(' '));
/** Admin `music [n]`: the last n attempts of every account, newest last, in Romanian time. */
export async function musicReport(env, n = 30) {
  if (!env.MEDIA) return 'Jurnal indisponibil (media R2 neconfigurată).';
  const rows = [];
  let cursor;
  do {
    const page = await env.MEDIA.list({ prefix: PREFIX, cursor, limit: 1000 });
    for (const o of page.objects) {
      const u = o.key.slice(PREFIX.length).replace(/\.json$/, '').slice(0, 8);
      for (const e of await readEvents(env.MEDIA, o.key)) rows.push({ ...e, u });
    }
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  if (!rows.length) return 'Nicio încercare de muzică primită încă.';
  rows.sort((a, b) => a.at - b.at);
  const last = rows.slice(-Math.min(Math.max(n, 1), MUSIC_DIAG.keep));
  const ok = last.filter(e => e.result === 'ok').length;
  return [
    `Muzică: ${last.length} încercări (ora României) · ${ok} reușite`,
    ...last.map(e => '  ' + [clock.format(new Date(e.at)).replace(',', ''), 'u' + e.u, e.want.padEnd(7), e.rung.padEnd(14), String(e.pkg || '—').padEnd(24), String(e.kind || '—').padEnd(7),
      e.result.padEnd(11), (e.ms + 'ms').padEnd(8), e.ver || '', deviceText(e.device), e.err ? '· ' + e.err : ''].join(' ').trimEnd()),
  ].join('\n');
}
