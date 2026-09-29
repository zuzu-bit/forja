// FORJA 4.4 — secțiunile corpului: „ratie” (mesele, ținta) și „mars” (activitățile cu mini-hărți, antrenamentele).
// Mirror (pachetul C): mesele cu ce a găsit analiza (componente, scor, sfat) și poza mesei (contract v4, R2 RECORDS sub
// `_insights/{uid}/meals/`, rămâne cât ai contul, ca jurnalul), antrenamentele cu exercițiile, seriile și muzica, și istoria
// mai veche de 90 de zile, în pagini (`?before=`).
import { SITE_RULES, HOUR, num, int, str, round1, time, sum, where, account, simplifyPolyline, dayParam, mealDate, contractGate, reply, failure } from './shared.mjs';
import { localDate, localMidnight, DAY } from '../site-time.mjs';

/** The app writes the meal type as an Int (Entities.kt: 0 mic dejun · 1 prânz · 2 cină · 3 gustare); the site labels by index. */
const mealType = v => (Number.isInteger(v) && v >= 0 && v <= 3 ? v : null);
const MEAL_FIELDS = ['name', 'kcal', 'protein', 'carbs', 'fat', 'grams', 'mealType', 'source', 'confidence', 'epochDay', 'at', 'items', 'score', 'tip', 'photo'];
export const MEAL_PHOTO_MAX = 200 * 1024;
const PHOTO_ID = /^[A-Za-z0-9_-]{1,64}$/;
function targetsOf(t) {
  if (!t || [t.kcal, t.protein, t.carbs, t.fat].every(v => num(v) === null)) return null;
  return { kcal: int(t.kcal), protein: num(t.protein), carbs: num(t.carbs), fat: num(t.fat) };
}
/** Componentele farfuriei cum le scrie telefonul (CloudSync.meal): cel mult 12, fiecare cu gramele și macro-urile ei. */
function itemsOf(v) {
  if (!Array.isArray(v)) return null;
  const out = v.slice(0, 12).filter(x => x && typeof x === 'object' && str(x.name, 80)).map(x => ({ name: str(x.name, 80), grams: int(x.grams), kcal: int(x.kcal),
    protein: num(x.protein) === null ? null : round1(x.protein), carbs: num(x.carbs) === null ? null : round1(x.carbs), fat: num(x.fat) === null ? null : round1(x.fat) }));
  return out.length ? out : null;
}
function scoreOf(v) {
  const value = int(v?.value);
  return value !== null && value >= 1 && value <= 10 ? { value, reason: str(v.reason, 200) } : null;
}
/**
 * Fereastra cerută: ultimele `days` zile, sau — cu `?before=YYYY-MM-DD` (butonul „Mai vechi”) — `days` zile care se termină
 * în ziua celei mai noi mese de dinainte de `before`, ca o pagină mai veche să nu fie niciodată goală când există istorie.
 */
export async function ratie({ fs, uid, now, url }) {
  const days = dayParam(url, SITE_RULES.ratie_days), rawBefore = url.searchParams.get('before');
  const b = /^\d{4}-\d{2}-\d{2}$/.test(rawBefore || '') ? Date.parse(rawBefore + 'T12:00:00Z') : NaN;
  const probe = at => fs.query(`users/${uid}`, 'meals', { filters: [where('at', 'LESS_THAN', at)], orderBy: 'at', limit: 1, select: ['at', 'epochDay'] });
  let end = null;
  if (Number.isFinite(b)) {
    const newest = (await probe(localMidnight(b)))?.[0];
    end = newest && time(newest.at) ? localMidnight(newest.at) + DAY : localMidnight(b);
  }
  const from = end !== null ? localMidnight(end - days * DAY + 12 * HOUR) : localMidnight(now) - (days - 1) * DAY;
  const filters = [where('at', 'GREATER_THAN_OR_EQUAL', from - 12 * HOUR)];
  if (end !== null) filters.push(where('at', 'LESS_THAN', end + 12 * HOUR));
  const [me, targets, meals, older] = await Promise.all([
    fs.get(`users/${uid}`, ['contract']),
    fs.get(`users/${uid}/settings/targets`, ['kcal', 'protein', 'carbs', 'fat']),
    fs.query(`users/${uid}`, 'meals', { filters, orderBy: 'at', limit: 800, select: MEAL_FIELDS }),
    // „Mai vechi” apare doar când chiar există ceva mai vechi (1 citire).
    probe(from - 12 * HOUR),
  ]);
  const oldest = localDate(from), newest = end !== null ? localDate(end - DAY) : null, groups = new Map();
  for (const m of meals || []) {
    if (!time(m.at)) continue;
    const date = mealDate(m);
    if (date < oldest || (newest && date > newest)) continue;
    if (!groups.has(date)) groups.set(date, []);
    const row = { id: m.id, at: m.at, name: str(m.name, 120) || 'Masă', kcal: int(m.kcal) ?? 0, protein: round1(num(m.protein) || 0), carbs: round1(num(m.carbs) || 0),
      fat: round1(num(m.fat) || 0), grams: int(m.grams), mealType: mealType(m.mealType), source: str(m.source, 30), confidence: num(m.confidence) ?? str(m.confidence, 30) };
    // Ce a găsit analiza și poza mesei, doar când există (mesele manuale și cele mai vechi nu le au).
    const items = itemsOf(m.items), score = scoreOf(m.score), tip = str(m.tip, 300);
    if (items) row.items = items;
    if (score) row.score = score;
    if (tip) row.tip = tip;
    if (m.photo === true) row.photo = true;
    groups.get(date).push(row);
  }
  const list = [...groups].sort((a, b) => (a[0] < b[0] ? 1 : -1)).map(([date, rows]) => {
    rows.sort((a, b) => a.at - b.at);
    return { date, kcal: Math.round(sum(rows, r => r.kcal)), protein: round1(sum(rows, r => r.protein)), carbs: round1(sum(rows, r => r.carbs)), fat: round1(sum(rows, r => r.fat)), meals: rows };
  });
  // The meals are a journal; the calorie target went up only with contract v3.
  return { targets: contractGate(me?.contract) ? targetsOf(targets) : null, days: list, from: oldest, more: (older || []).length > 0 };
}

/**
 * Poza mesei: PUT (telefonul, după salvarea mesei, doar cu contractul v4 semnat), GET (site-ul), DELETE (masa ștearsă).
 * Cheia R2 se face NUMAI din uid-ul verificat și id-ul stabil al mesei; JPEG ≤ 200 KB (512 px pe telefon).
 */
export async function ratiePhoto({ request, env, fs, uid }, id) {
  if (!PHOTO_ID.test(id)) return failure('Poză necunoscută.', 404);
  const bucket = env.RECORDS;
  if (!bucket) return failure('Pozele nu sunt disponibile acum.', 503);
  const key = `_insights/${uid}/meals/${id}.jpg`;
  if (request.method === 'GET') {
    const o = await bucket.get(key);
    if (!o) return failure('Poza nu mai este aici.', 404);
    return new Response(o.body, { headers: { 'content-type': 'image/jpeg', 'cache-control': 'private, max-age=86400', 'x-content-type-options': 'nosniff', 'content-security-policy': "default-src 'none'; sandbox" } });
  }
  if (request.method === 'DELETE') { await bucket.delete(key); return reply({ deleted: true }); }
  const me = await fs.get(`users/${uid}`, ['contract']);
  if (!contractGate(me?.contract, 4)) return failure('Poza mesei cere contractul v4 semnat.', 403);
  if (Number(request.headers.get('content-length')) > MEAL_PHOTO_MAX) return failure('Poza e prea mare.', 413);
  const bytes = new Uint8Array(await request.arrayBuffer());
  if (bytes.length > MEAL_PHOTO_MAX) return failure('Poza e prea mare.', 413);
  if (bytes.length < 4 || bytes[0] !== 255 || bytes[1] !== 216 || bytes[2] !== 255) return failure('Poza mesei vine ca JPEG.', 400);
  await bucket.put(key, bytes, { httpMetadata: { contentType: 'image/jpeg' } });
  return reply({ ok: true, bytes: bytes.length }, 201);
}

const ACTIVITY_FIELDS = ['type', 'startAt', 'endAt', 'distanceM', 'durationS', 'kcal', 'polyline'];
const WORKOUT_FIELDS = ['startAt', 'endAt', 'durationS', 'title', 'kind', 'sets', 'volumeKg', 'kcal', 'source', 'completed', 'plannedSets', 'exercises', 'music'];
function weekOf(activities, workouts, now) {
  const since = now - 7 * DAY, a = activities.filter(x => x.startAt >= since), w = workouts.filter(x => x.startAt >= since);
  return { km: round1(sum(a, x => x.distanceM) / 1000), minutes: Math.round((sum(a, x => x.durationS) + sum(w, x => x.durationS)) / 60), sessions: a.length + w.length };
}
/** Exercițiile unui antrenament (SitePayloads.workout): nume + seriile (repetări, greutatea scrisă, kg când e număr). */
function exercisesOf(v) {
  if (!Array.isArray(v)) return null;
  const out = v.slice(0, 30).filter(e => e && str(e.name, 80)).map(e => ({ name: str(e.name, 80),
    sets: (Array.isArray(e.sets) ? e.sets : []).slice(0, 20).map(s => ({ reps: int(s?.reps) ?? 0, load: str(s?.load, 20), kg: num(s?.kg), at: time(s?.at) })) }));
  return out.length ? out : null;
}
function musicOf(v) {
  if (!Array.isArray(v)) return null;
  const out = v.slice(0, 30).filter(m => m && str(m.title, 120)).map(m => ({ title: str(m.title, 120), artist: str(m.artist, 120), at: time(m.at) }));
  return out.length ? out : null;
}
function workoutRow(w) {
  const row = { id: w.id, startAt: w.startAt, endAt: time(w.endAt), durationS: num(w.durationS) ?? 0, title: str(w.title, 80), kind: str(w.kind, 30), sets: int(w.sets) ?? 0, volumeKg: num(w.volumeKg), kcal: int(w.kcal) };
  // Detaliile plecate din 4.5 (payload v2); antrenamentele vechi rămân cum erau.
  const exercises = exercisesOf(w.exercises), music = musicOf(w.music);
  if (str(w.source, 20)) row.source = str(w.source, 20);
  if (typeof w.completed === 'boolean') row.completed = w.completed;
  if (int(w.plannedSets) !== null) row.plannedSets = int(w.plannedSets);
  if (exercises) row.exercises = exercises;
  if (music) row.music = music;
  return row;
}
/**
 * Mini route maps, cheapest source first: the routes already simplified for Teren (DO `cerc-slow`, newest 30), then Marș's own
 * cache (DO `mars-routes`: id → 120-point polyline or null, the last 250 read), and only then Firestore, at most 8 polylines per
 * open (they are the heavy part of an activity). Each open reads the next 8 missing ones, so a long list fills in over a few opens
 * and nothing is read twice. `?before=<ms>` pages back in time (`days` before that moment); the week totals stay the last 7 days.
 */
export async function mars({ env, fs, uid, now, url }) {
  const days = dayParam(url, SITE_RULES.mars_days), rawBefore = url.searchParams.get('before');
  let before = /^\d{1,15}$/.test(rawBefore || '') && Number(rawBefore) < now ? Number(rawBefore) : null;
  const probe = (col, at) => fs.query(`users/${uid}`, col, { filters: [where('startAt', 'LESS_THAN', at)], orderBy: 'startAt', limit: 1, select: ['startAt'] });
  if (before !== null) {
    // Pagina mai veche se termină la cea mai nouă tură sau antrenament de dinainte de `before` (niciodată goală când există).
    const [a, w] = await Promise.all([probe('activities', before), probe('workouts', before)]);
    const newest = Math.max(time(a?.[0]?.startAt) || 0, time(w?.[0]?.startAt) || 0);
    if (newest) before = Math.min(before, newest + 1);
  }
  const until = before ?? Infinity, since = (before ?? now) - days * DAY;
  // `week` is always the last 7 days, even when the list asks for fewer.
  const from = before === null ? Math.min(since, now - 7 * DAY) : since;
  const range = [where('startAt', 'GREATER_THAN_OR_EQUAL', from)];
  if (before !== null) range.push(where('startAt', 'LESS_THAN', before));
  const [me, acts, works, cache, olderA, olderW] = await Promise.all([
    fs.get(`users/${uid}`, ['contract']),
    fs.query(`users/${uid}`, 'activities', { filters: range, orderBy: 'startAt', limit: 200, select: ACTIVITY_FIELDS.filter(f => f !== 'polyline') }),
    fs.query(`users/${uid}`, 'workouts', { filters: range, orderBy: 'startAt', limit: 200, select: WORKOUT_FIELDS }),
    account(env, uid, '/internal/site/cache?names=cerc-slow,mars-routes'),
    probe('activities', since),
    probe('workouts', since),
  ]);
  const teren = new Map((cache?.['cerc-slow']?.routes || []).map(r => [r.id, r.polyline]));
  for (const id of cache?.['cerc-slow']?.none || []) teren.set(id, null);
  const own = new Map(Array.isArray(cache?.['mars-routes']?.routes) ? cache['mars-routes'].routes.filter(r => Array.isArray(r) && typeof r[0] === 'string') : []);
  const allActs = (acts || []).filter(a => time(a.startAt) && a.startAt < until), inDays = allActs.filter(a => a.startAt >= since);
  const missing = inDays.filter(a => !teren.has(a.id) && !own.has(a.id)).slice(0, SITE_RULES.mars_polylines);
  if (missing.length) {
    const mark = fs.mark();
    const docs = await fs.batchGet(missing.map(a => `users/${uid}/activities/${a.id}`), ['polyline']);
    const read = missing.map(a => [a.id, simplifyPolyline(docs.get(`users/${uid}/activities/${a.id}`)?.polyline, SITE_RULES.mini_route_points)]);
    for (const [id, polyline] of read) own.set(id, polyline);
    // A failed read is not remembered as "no route": it is tried again on the next open.
    if (!fs.failedSince(mark)) await account(env, uid, '/internal/site/cache', 'POST', { 'mars-routes': { at: now, routes: [...own].slice(-SITE_RULES.mars_cached) } });
  }
  const polylineOf = id => (teren.has(id) ? simplifyPolyline(teren.get(id), SITE_RULES.mini_route_points) : own.get(id) ?? null);
  const activities = allActs.map(a => ({ id: a.id, type: str(a.type, 20), startAt: a.startAt, endAt: time(a.endAt), distanceM: num(a.distanceM) ?? 0,
    durationS: num(a.durationS) ?? 0, kcal: int(a.kcal), polyline: a.startAt >= since ? polylineOf(a.id) : null }));
  // Activities are a journal; workouts went up only with contract v3.
  const workouts = (contractGate(me?.contract) ? works || [] : []).filter(w => time(w.startAt) && w.startAt < until).map(workoutRow);
  const olderWorkouts = contractGate(me?.contract) && (olderW || []).length > 0;
  return { activities: activities.filter(a => a.startAt >= since), workouts: workouts.filter(w => w.startAt >= since), week: before === null ? weekOf(activities, workouts, now) : null,
    from: since, more: (olderA || []).length > 0 || olderWorkouts };
}
