// FORJA 4.4 — secțiunile corpului: „ratie” (mesele, ținta) și „mars” (activitățile cu mini-hărți, antrenamentele).
import { SITE_RULES, HOUR, num, int, str, round1, time, sum, where, account, simplifyPolyline, dayParam, mealDate, contractGate } from './shared.mjs';
import { localDate, localMidnight, DAY } from '../site-time.mjs';

/** The app writes the meal type as an Int (Entities.kt: 0 mic dejun · 1 prânz · 2 cină · 3 gustare); the site labels by index. */
const mealType = v => (Number.isInteger(v) && v >= 0 && v <= 3 ? v : null);
const MEAL_FIELDS = ['name', 'kcal', 'protein', 'carbs', 'fat', 'grams', 'mealType', 'source', 'confidence', 'epochDay', 'at'];
function targetsOf(t) {
  if (!t || [t.kcal, t.protein, t.carbs, t.fat].every(v => num(v) === null)) return null;
  return { kcal: int(t.kcal), protein: num(t.protein), carbs: num(t.carbs), fat: num(t.fat) };
}
export async function ratie({ fs, uid, now, url }) {
  const days = dayParam(url, SITE_RULES.ratie_days), from = localMidnight(now) - (days - 1) * DAY;
  const [me, targets, meals] = await Promise.all([
    fs.get(`users/${uid}`, ['contract']),
    fs.get(`users/${uid}/settings/targets`, ['kcal', 'protein', 'carbs', 'fat']),
    fs.query(`users/${uid}`, 'meals', { filters: [where('at', 'GREATER_THAN_OR_EQUAL', from - 12 * HOUR)], orderBy: 'at', limit: 800, select: MEAL_FIELDS }),
  ]);
  const oldest = localDate(from), groups = new Map();
  for (const m of meals || []) {
    if (!time(m.at)) continue;
    const date = mealDate(m);
    if (date < oldest) continue;
    if (!groups.has(date)) groups.set(date, []);
    groups.get(date).push({ id: m.id, at: m.at, name: str(m.name, 120) || 'Masă', kcal: int(m.kcal) ?? 0, protein: round1(num(m.protein) || 0), carbs: round1(num(m.carbs) || 0),
      fat: round1(num(m.fat) || 0), grams: int(m.grams), mealType: mealType(m.mealType), source: str(m.source, 30), confidence: num(m.confidence) ?? str(m.confidence, 30) });
  }
  const list = [...groups].sort((a, b) => (a[0] < b[0] ? 1 : -1)).map(([date, rows]) => {
    rows.sort((a, b) => a.at - b.at);
    return { date, kcal: Math.round(sum(rows, r => r.kcal)), protein: round1(sum(rows, r => r.protein)), carbs: round1(sum(rows, r => r.carbs)), fat: round1(sum(rows, r => r.fat)), meals: rows };
  });
  // The meals are a journal; the calorie target went up only with contract v3.
  return { targets: contractGate(me?.contract) ? targetsOf(targets) : null, days: list };
}
const ACTIVITY_FIELDS = ['type', 'startAt', 'endAt', 'distanceM', 'durationS', 'kcal', 'polyline'];
const WORKOUT_FIELDS = ['startAt', 'endAt', 'durationS', 'title', 'kind', 'sets', 'volumeKg', 'kcal'];
function weekOf(activities, workouts, now) {
  const since = now - 7 * DAY, a = activities.filter(x => x.startAt >= since), w = workouts.filter(x => x.startAt >= since);
  return { km: round1(sum(a, x => x.distanceM) / 1000), minutes: Math.round((sum(a, x => x.durationS) + sum(w, x => x.durationS)) / 60), sessions: a.length + w.length };
}
/**
 * Mini route maps, cheapest source first: the routes already simplified for Teren (DO `cerc-slow`, newest 30), then Marș's own
 * cache (DO `mars-routes`: id → 120-point polyline or null, the last 250 read), and only then Firestore, at most 8 polylines per
 * open (they are the heavy part of an activity). Each open reads the next 8 missing ones, so a long list fills in over a few opens
 * and nothing is read twice.
 */
export async function mars({ env, fs, uid, now, url }) {
  const days = dayParam(url, SITE_RULES.mars_days), since = now - days * DAY;
  // `week` is always the last 7 days, even when the list asks for fewer.
  const from = Math.min(since, now - 7 * DAY);
  const [me, acts, works, cache] = await Promise.all([
    fs.get(`users/${uid}`, ['contract']),
    fs.query(`users/${uid}`, 'activities', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', from)], orderBy: 'startAt', limit: 200, select: ACTIVITY_FIELDS.filter(f => f !== 'polyline') }),
    fs.query(`users/${uid}`, 'workouts', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', from)], orderBy: 'startAt', limit: 200, select: WORKOUT_FIELDS }),
    account(env, uid, '/internal/site/cache?names=cerc-slow,mars-routes'),
  ]);
  const teren = new Map((cache?.['cerc-slow']?.routes || []).map(r => [r.id, r.polyline]));
  for (const id of cache?.['cerc-slow']?.none || []) teren.set(id, null);
  const own = new Map(Array.isArray(cache?.['mars-routes']?.routes) ? cache['mars-routes'].routes.filter(r => Array.isArray(r) && typeof r[0] === 'string') : []);
  const allActs = (acts || []).filter(a => time(a.startAt)), inDays = allActs.filter(a => a.startAt >= since);
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
  const workouts = (contractGate(me?.contract) ? works || [] : []).filter(w => time(w.startAt)).map(w => ({ id: w.id, startAt: w.startAt, endAt: time(w.endAt), durationS: num(w.durationS) ?? 0, title: str(w.title, 80),
    kind: str(w.kind, 30), sets: int(w.sets) ?? 0, volumeKg: num(w.volumeKg), kcal: int(w.kcal) }));
  return { activities: activities.filter(a => a.startAt >= since), workouts: workouts.filter(w => w.startAt >= since), week: weekOf(activities, workouts, now) };
}
