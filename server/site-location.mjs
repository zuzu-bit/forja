// FORJA mirror (pachetul A) — ziua pe hartă: pe unde a fost și unde a stat, din sesiunea contractului (AutomaticCollectionService).
// Telefonul trimite la ~60 s ultimele ~300 de poziții (≈ 50 min, 10 s între ele) și opririle calculate pe telefon
// (SyncMath.visits: 75 m / 120 s). DO-ul sesiunii păstrează doar ultima fotografie (`data:{id}`, suprascrisă), așa că fără
// rollup ziua s-ar pierde. Aici se adună în `loc-day:YYYY-MM-DD` (ziua locală, ora României):
//  · pozițiile, subțiate la cel mult una la 30 s (o retrimitere sau o fereastră suprapusă nu dublează nimic), fără fix-uri
//    mai proaste de 150 m, cel mult 3 000 pe zi;
//  · opririle, fără dubluri: aceeași oprire revine în fiecare fotografie cu `last_seen` crescut, deci se unește cu cea
//    păstrată (început apropiat sau intervale suprapuse, la cel mult 150 m).
// Contractul spune „Pozițiile și opririle pe site: 24 de ore”: orice punct mai vechi de 24 h se șterge la scriere și de
// alarma DO-ului (sweepLocation), chiar dacă telefonul nu mai trimite nimic. Revocarea (/v2/site/forget) șterge prefixul.
// Doar proprietarul vede ziua (DO-ul contului, prin /internal/site/day); prietenii nu primesc nimic de aici.
import { localDate } from './site-time.mjs';

/** Prefixul cheilor din DO; revocarea (POST /v2/site/forget) le șterge pe toate. */
export const LOC_DAY_PREFIX = 'loc-day:';
export const LOC_RULES = Object.freeze({
  keep_ms: 24 * 3600000, step_ms: 30000, max_accuracy_m: 150, points_per_day: 3000, stops_per_day: 200,
  stop_merge_m: 150, stop_merge_start_ms: 3 * 60000, gap_ms: 10 * 60000, min_stop_ms: 3 * 60000, move_m: 40,
  view_points: 1500, place_match_m: 120, future_ms: 5 * 60000,
});

const rad = Math.PI / 180;
export function metres(aLat, aLng, bLat, bLng) {
  const dLat = (bLat - aLat) * rad, dLng = (bLng - aLng) * rad;
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(aLat * rad) * Math.cos(bLat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(x)));
}
const fin = v => typeof v === 'number' && Number.isFinite(v);
const round5 = v => Math.round(v * 1e5) / 1e5;

/** Pozițiile sortate după timp, cel mult una la `step` ms (prima din fiecare pas rămâne; aceeași clipă nu intră de două ori). */
export function thin(points, step = LOC_RULES.step_ms, cap = LOC_RULES.points_per_day) {
  const sorted = [...points].sort((a, b) => a[0] - b[0]);
  let out = [];
  for (const p of sorted) if (!out.length || p[0] - out[out.length - 1][0] >= step) out.push(p);
  // Peste plafon: aceeași subțiere, uniform, cu ultimul punct păstrat (poziția cea mai nouă contează).
  if (out.length > cap) { const k = (out.length - 1) / (cap - 1); out = Array.from({ length: cap }, (_, i) => out[Math.round(i * k)]); }
  return out;
}

/** O oprire nouă se unește cu una păstrată dacă e același loc (≤ 150 m) și același moment (început apropiat sau intervale suprapuse). */
export function mergeStops(kept, incoming, cap = LOC_RULES.stops_per_day) {
  const out = kept.map(s => ({ ...s }));
  for (const v of incoming) {
    const hit = out.find(s => metres(s.lat, s.lng, v.lat, v.lng) <= LOC_RULES.stop_merge_m &&
      (Math.abs(s.from - v.from) <= LOC_RULES.stop_merge_start_ms || (v.from <= s.to + 60000 && v.to >= s.from - 60000)));
    if (!hit) { out.push({ ...v }); continue; }
    const w = hit.samples + v.samples;
    hit.lat = round5((hit.lat * hit.samples + v.lat * v.samples) / w); hit.lng = round5((hit.lng * hit.samples + v.lng * v.samples) / w);
    hit.samples = Math.min(100000, Math.max(hit.samples, v.samples));
    hit.from = Math.min(hit.from, v.from); hit.to = Math.max(hit.to, v.to);
  }
  out.sort((a, b) => a.from - b.from);
  return out.slice(-cap);
}

const trimRow = (row, cutoff) => ({ ...row, pts: (row.pts || []).filter(p => p[0] > cutoff), stops: (row.stops || []).filter(s => s.to > cutoff) });
const oldestOf = row => Math.min(...(row.pts || []).map(p => p[0]), ...(row.stops || []).map(s => s.to));

async function scheduleSweep(storage, at) {
  if (!fin(at) || typeof storage.getAlarm !== 'function') return;
  const current = await storage.getAlarm();
  if (current === null || current === undefined || current > at) await storage.setAlarm(at);
}

/**
 * `data` = corpul validat al sesiunii (validatePhoneData): `locations[]` și `visits[]` când consimțământul include locația.
 * Întoarce numărul de zile atinse (0 când nu era nimic nou de păstrat). O eroare de aici nu pierde niciodată încărcarea
 * (apelantul o prinde).
 */
export async function applyLocationRollup(storage, sessionId, data, now = Date.now()) {
  void sessionId;
  const cutoff = now - LOC_RULES.keep_ms, future = now + LOC_RULES.future_ms;
  const pts = [], stops = [];
  for (const p of Array.isArray(data?.locations) ? data.locations : []) {
    if (!fin(p?.at) || !fin(p.latitude) || !fin(p.longitude) || p.at <= cutoff || p.at > future) continue;
    if (fin(p.accuracy_m) && p.accuracy_m > LOC_RULES.max_accuracy_m) continue;
    pts.push([Math.min(p.at, now), round5(p.latitude), round5(p.longitude), fin(p.accuracy_m) ? Math.round(p.accuracy_m) : null]);
  }
  for (const v of Array.isArray(data?.visits) ? data.visits : []) {
    if (!fin(v?.first_seen) || !fin(v.last_seen) || !fin(v.latitude) || !fin(v.longitude) || v.last_seen <= cutoff || v.first_seen > future) continue;
    stops.push({ from: Math.max(v.first_seen, cutoff), to: Math.min(v.last_seen, now), lat: round5(v.latitude), lng: round5(v.longitude), samples: fin(v.samples) ? Math.max(1, Math.round(v.samples)) : 1 });
  }
  if (!pts.length && !stops.length) return 0;
  const byDate = new Map();
  const bucket = date => { if (!byDate.has(date)) byDate.set(date, { pts: [], stops: [] }); return byDate.get(date); };
  for (const p of pts) bucket(localDate(p[0])).pts.push(p);
  for (const s of stops) bucket(localDate(s.from)).stops.push(s);
  let next = Infinity;
  for (const [date, add] of byDate) {
    const key = LOC_DAY_PREFIX + date, old = trimRow((await storage.get(key)) || { date, pts: [], stops: [] }, cutoff);
    const row = { date, updated_at: now, pts: thin([...old.pts, ...add.pts]), stops: mergeStops(old.stops, add.stops) };
    await storage.put(key, row);
    next = Math.min(next, oldestOf(row) + LOC_RULES.keep_ms);
  }
  // Zilele vechi pe care nu le-a atins nimeni (telefonul a tăcut o zi) pleacă tot acum, nu doar la alarmă.
  next = Math.min(next, await sweepLocation(storage, now, new Set([...byDate.keys()].map(d => LOC_DAY_PREFIX + d))));
  await scheduleSweep(storage, next);
  return byDate.size;
}

/** Alarma DO-ului: taie tot ce e mai vechi de 24 h. Întoarce următorul moment în care ceva trebuie șters (Infinity: nimic). */
export async function sweepLocation(storage, now = Date.now(), skip = new Set()) {
  const cutoff = now - LOC_RULES.keep_ms;
  let next = Infinity;
  for (const [key, row] of await storage.list({ prefix: LOC_DAY_PREFIX })) {
    if (skip.has(key)) continue;
    const kept = trimRow(row || {}, cutoff);
    if (!kept.pts.length && !kept.stops.length) { await storage.delete(key); continue; }
    if (kept.pts.length !== (row.pts || []).length || kept.stops.length !== (row.stops || []).length) await storage.put(key, kept);
    next = Math.min(next, oldestOf(kept) + LOC_RULES.keep_ms);
  }
  return next;
}

/**
 * Ultimele 24 h, gata de desenat: traseul pe bucăți (o pauză de peste 10 min taie linia, nu se unește în linie dreaptă),
 * pauzele, km (doar deplasări de peste 40 m, ca zgomotul GPS al unei zile pe loc să nu adune kilometri), opririle de cel
 * puțin 3 minute cu numele locului tău din apropiere (`places` = [{lat, lng, name}]), ultima poziție. null: nimic.
 */
export async function dayView(storage, now = Date.now(), places = []) {
  const cutoff = now - LOC_RULES.keep_ms;
  const rows = [...(await storage.list({ prefix: LOC_DAY_PREFIX })).values()];
  let pts = rows.flatMap(r => r?.pts || []).filter(p => p[0] > cutoff && p[0] <= now + LOC_RULES.future_ms).sort((a, b) => a[0] - b[0]);
  const stops = mergeStops([], rows.flatMap(r => r?.stops || []).filter(s => s.to > cutoff), 500)
    .filter(s => s.to - s.from >= LOC_RULES.min_stop_ms);
  if (!pts.length && !stops.length) return null;
  const segments = [], gaps = [];
  let km = 0, current = [], anchor = null;
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i], prev = pts[i - 1];
    if (prev && p[0] - prev[0] > LOC_RULES.gap_ms) { if (current.length) segments.push(current); current = []; anchor = null; gaps.push({ from: prev[0], to: p[0] }); }
    current.push(p);
    if (!anchor) anchor = p;
    else { const d = metres(anchor[1], anchor[2], p[1], p[2]); if (d >= LOC_RULES.move_m) { km += d / 1000; anchor = p; } }
  }
  if (current.length) segments.push(current);
  // Cel mult 1 500 de puncte în tot răspunsul, împărțite pe bucăți după lungimea lor.
  const total = pts.length, budget = LOC_RULES.view_points;
  const track = segments.filter(s => s.length >= 2).map(s => {
    const want = total > budget ? Math.max(2, Math.round(s.length * budget / total)) : s.length;
    const k = (s.length - 1) / (want - 1);
    const pick = want >= s.length ? s : Array.from({ length: want }, (_, i) => s[Math.round(i * k)]);
    return { from: s[0][0], to: s[s.length - 1][0], polyline: pick.map(p => p[1].toFixed(5) + ',' + p[2].toFixed(5)).join(';') };
  });
  const named = (Array.isArray(places) ? places : []).filter(p => fin(p?.lat) && fin(p?.lng) && typeof p.name === 'string' && p.name.trim());
  const out = stops.map(s => {
    let best = null, bestM = LOC_RULES.place_match_m;
    for (const p of named) { const d = metres(s.lat, s.lng, p.lat, p.lng); if (d <= bestM) { best = p; bestM = d; } }
    return { from: s.from, to: s.to, minutes: Math.round((s.to - s.from) / 60000), lat: s.lat, lng: s.lng, name: best ? best.name.trim().slice(0, 80) : null };
  });
  const last = pts[pts.length - 1];
  return {
    from: Math.min(pts[0]?.[0] ?? Infinity, out[0]?.from ?? Infinity), to: Math.max(last?.[0] ?? 0, out[out.length - 1]?.to ?? 0),
    km: Math.round(km * 10) / 10, points: pts.length, track, gaps, stops: out,
    last: last ? { lat: last[1], lng: last[2], at: last[0], accuracy: last[3] } : null,
    updated_at: rows.reduce((m, r) => Math.max(m, r?.updated_at || 0), 0) || null, keep_hours: 24,
  };
}

/** GET /internal/site/day (doar din Worker, după legarea proprietarului). Numele locurilor vin ca JSON în `places`. */
export async function handleSiteLocation(request, storage) {
  const url = new URL(request.url);
  if (url.pathname !== '/internal/site/day') return null;
  if (request.method !== 'POST' && request.method !== 'GET') return Response.json({ error: 'Method not allowed' }, { status: 405 });
  let places = [];
  if (request.method === 'POST') { try { const v = await request.json(); places = Array.isArray(v?.places) ? v.places.slice(0, 1000) : []; } catch { places = []; } }
  return Response.json({ day: await dayView(storage, Date.now(), places) }, { headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
}
