// FORJA 4.4 — „azi” (ziua de azi, noaptea, o legătură pe secțiune) și „cont” (contractul, ultima dată pe fiecare conductă).
import { SITE_RULES, MIN, HOUR, int, str, round1, time, sum, where, recall, remember, account, summaryOf, socialMeta, mealDate, latest, contractOf, contractLink, contractGate } from './shared.mjs';
import { localDate, localMidnight, DAY } from '../site-time.mjs';
import { cachedFriends } from './sec-cerc.mjs';

function tokenEmail(request) {
  try {
    const part = (request.headers.get('Authorization') || '').slice(7).split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const claims = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(part + '='.repeat((4 - part.length % 4) % 4)), c => c.charCodeAt(0))));
    return typeof claims.email === 'string' ? claims.email : null;
  } catch { return null; }
}

async function lastOf(fs, uid, collection, field, select) {
  const rows = await fs.query(`users/${uid}`, collection, { orderBy: field, limit: 1, select });
  return rows?.[0] || null;
}

/** Freshness window per section: "on" when the pipe delivered within it, "stale" when older, "off" when never. */
// Găsire: the same 12 min as `online` in lost-phone.mjs (a phone in Doze beats about every 9 min).
const WINDOWS = { teren: DAY, camarazi: DAY, gasire: 12 * MIN, inventar: 30 * DAY, somn: 2 * DAY, ratie: DAY, mars: 7 * DAY, muzica: DAY, paza: 2 * HOUR };
function link(key, lastAt, count, now) {
  return { key, state: !lastAt ? 'off' : now - lastAt <= WINDOWS[key] ? 'on' : 'stale', lastAt: lastAt || null, count: count ?? null };
}

export async function azi({ env, fs, uid, now, request }) {
  const key = uid + ':azi', hit = recall(key, SITE_RULES.azi_ms, now);
  if (hit) return hit;
  const today = localDate(now), midnight = localMidnight(now);
  const cache = await account(env, uid, '/internal/site/cache?names=friends');
  const [docs, meals, acts, works, nights, inv, friends, summary, social] = await Promise.all([
    fs.batchGet([`users/${uid}`, `users/${uid}/settings/targets`, `users/${uid}/settings/music`], ['name', 'contract', 'locUpdatedAt', 'nowPlaying', 'kcal', 'updatedAt']),
    fs.query(`users/${uid}`, 'meals', { filters: [where('at', 'GREATER_THAN_OR_EQUAL', midnight - 12 * HOUR)], orderBy: 'at', limit: 80, select: ['kcal', 'protein', 'carbs', 'fat', 'epochDay', 'at'] }),
    fs.query(`users/${uid}`, 'activities', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', now - 7 * DAY)], orderBy: 'startAt', limit: 60, select: ['startAt', 'endAt', 'distanceM', 'durationS'] }),
    fs.query(`users/${uid}`, 'workouts', { filters: [where('startAt', 'GREATER_THAN_OR_EQUAL', now - 7 * DAY)], orderBy: 'startAt', limit: 60, select: ['startAt', 'endAt', 'durationS'] }),
    fs.query(`users/${uid}`, 'sleep', { orderBy: 'startAt', limit: 2, select: ['startAt', 'endAt', 'score', 'summary'] }),
    lastOf(fs, uid, 'inventory', 'finishedAt', ['finishedAt']),
    cachedFriends(env, fs, uid, now, cache),
    summaryOf(env, uid),
    socialMeta(env, uid),
  ]);
  if (friends.write) await account(env, uid, '/internal/site/cache', 'POST', { friends: friends.write });
  const me = docs.get(`users/${uid}`), targets = docs.get(`users/${uid}/settings/targets`), music = docs.get(`users/${uid}/settings/music`);
  const todays = (meals || []).filter(m => time(m.at) && mealDate(m) === today);
  const lastMeal = (meals || []).reduce((m, r) => latest(m, r.at), null) || (await lastOf(fs, uid, 'meals', 'at', ['at']))?.at || null;
  const contract = contractOf(me?.contract), gated = contractGate(me?.contract);
  // Contract-v3 uploads (target, workouts, music top, Inventar runs) only while it is signed; journals always.
  const activities = (acts || []).filter(a => time(a.startAt)), workouts = gated ? (works || []).filter(w => time(w.startAt)) : [];
  const aToday = activities.filter(a => localDate(a.startAt) === today), wToday = workouts.filter(w => localDate(w.startAt) === today);
  const lastNight = (nights || []).find(n => time(n.endAt) && n.endAt > n.startAt) || null;
  const night = lastNight && now - lastNight.endAt <= 36 * HOUR ? { id: lastNight.id, startAt: lastNight.startAt, endAt: lastNight.endAt,
    minutes: Math.round((lastNight.endAt - lastNight.startAt) / MIN), score: int(lastNight.score), summary: str(lastNight.summary, 2000) } : null;
  const sleepAt = latest(...(nights || []).map(n => n.endAt), ...(nights || []).map(n => n.startAt));
  const moveAt = latest(...activities.map(a => a.endAt || a.startAt), ...workouts.map(w => w.endAt || w.startAt));
  const devices = summary?.recovery || [];
  const out = {
    me: { uid, name: str(me?.name, 80), email: tokenEmail(request) },
    today: { date: today, kcal: Math.round(sum(todays, m => m.kcal)), kcalTarget: gated ? int(targets?.kcal) : null, protein: round1(sum(todays, m => m.protein)), carbs: round1(sum(todays, m => m.carbs)),
      fat: round1(sum(todays, m => m.fat)), meals: todays.length, moveMin: Math.round((sum(aToday, a => a.durationS) + sum(wToday, w => w.durationS)) / 60),
      km: round1(sum(aToday, a => a.distanceM) / 1000), workouts: wToday.length },
    night,
    links: [
      link('teren', time(social?.explore?.updated_at), social ? social.explore.cells : null, now),
      link('camarazi', time(me?.locUpdatedAt), friends.uids.length, now),
      link('gasire', latest(...devices.map(d => d.seen_at)), devices.length, now),
      link('inventar', gated ? time(inv?.finishedAt) : null, null, now),
      link('somn', sleepAt, null, now),
      link('ratie', lastMeal, todays.length, now),
      link('mars', moveAt, activities.length + workouts.length, now),
      link('muzica', latest(gated ? music?.updatedAt : null, me?.nowPlaying?.at), null, now),
      link('paza', latest(summary?.usage?.updated_at, summary?.sessions?.updated_at), summary?.usage?.days?.[0]?.date === today ? summary.usage.days[0].apps.length : null, now),
      contractLink(contract, now),
    ],
    updated_at: now,
  };
  if (!fs.unreachable) remember(key, out, now);
  return out;
}
export async function cont({ env, fs, uid, request }) {
  const [docs, night, meal, act, work, inv, summary, social] = await Promise.all([
    fs.batchGet([`users/${uid}`, `users/${uid}/settings/music`], ['name', 'contract', 'nowPlaying', 'updatedAt']),
    lastOf(fs, uid, 'sleep', 'startAt', ['startAt', 'endAt']),
    lastOf(fs, uid, 'meals', 'at', ['at']),
    lastOf(fs, uid, 'activities', 'startAt', ['startAt', 'endAt']),
    lastOf(fs, uid, 'workouts', 'startAt', ['startAt', 'endAt']),
    lastOf(fs, uid, 'inventory', 'finishedAt', ['finishedAt']),
    summaryOf(env, uid),
    socialMeta(env, uid),
  ]);
  const me = docs.get(`users/${uid}`), music = docs.get(`users/${uid}/settings/music`);
  const until = time(social?.contacts?.until);
  return {
    me: { uid, name: str(me?.name, 80), email: tokenEmail(request) },
    contract: contractOf(me?.contract),
    pipes: [
      { key: 'sesiune', lastAt: time(summary?.sessions?.updated_at) },
      { key: 'galerie', lastAt: time(summary?.vault?.latestAt) },
      { key: 'explorare', lastAt: time(social?.explore?.updated_at) },
      // The agenda listing lives 30 days and is renewed at most once a day by a sync: renewal time = until − 30 days.
      { key: 'agenda', lastAt: social?.contacts?.discoverable && until ? until - 30 * DAY : null },
      { key: 'somn', lastAt: latest(night?.endAt, night?.startAt) },
      { key: 'gasire', lastAt: latest(...(summary?.recovery || []).map(d => d.seen_at)) },
      { key: 'mese', lastAt: time(meal?.at) },
      { key: 'miscare', lastAt: latest(act?.endAt, act?.startAt, work?.endAt, work?.startAt) },
      { key: 'muzica', lastAt: latest(music?.updatedAt, me?.nowPlaying?.at) },
      { key: 'inventar', lastAt: time(inv?.finishedAt) },
    ],
    intake: { paused: summary?.intake?.paused === true },
  };
}
