// FORJA 4.4 — secțiunile minții: „muzica” (ce asculți acum, topul săptămânii, jurnalul ascultărilor pe zile), „paza”
// (timpul pe ecran pe ore, din DO) și „concentrare” (sesiunile de concentrare, detoxul, respirația, Casca).
//
// Ce a pornit cu v3 (topul muzicii, timpul pe ecran) cere contractGate(me?.contract); ce e nou în v4 (jurnalul de ascultare,
// concentrarea, detoxul, respirația, mesajele Căștii) cere contractGate(me?.contract, 4). Cuvintele detoxului și scrisoarea
// se întorc doar când telefonul le-a trimis cu acordul separat (detox/words, onSite: true); altfel nu există pe server.
//
// Citiri Firestore: paza 1 · muzica 2 (+ ≤ `days` zile de ascultări, ținute 5 min în memorie)
//                   concentrare 1 + 1 (cuvintele) + focus/detox/breath/nudges din `days` (≤ 4 × days)
import { int, str, num, time, account, nowPlaying, contractGate, contractOf, dayParam, where, recall, remember, MIN } from './shared.mjs';
import { localDate, localMidnight, DAY } from '../site-time.mjs';

export const MIND_RULES = Object.freeze({
  paza_days: [1, 14, 7], concentrare_days: [1, 30, 7], muzica_days: [1, 30, 7], listens_ms: 5 * MIN,
  sessions_per_day: 50, casca_max: 60, words_max: 200, letter_max: 4000, listens_per_day: 300, packs: ['01', '02', '03', '04', '18', 'own'],
});

/** Ultimele `n` zile locale, azi prima. */
function lastDates(now, n) {
  const mid = localMidnight(now);
  return Array.from({ length: n }, (_, k) => localDate(mid - k * DAY + 12 * 3600000));
}
const byDate = rows => new Map((rows || []).filter(r => typeof r?.date === 'string').map(r => [r.date, r]));
const pkgOk = p => typeof p === 'string' && /^[A-Za-z0-9_.]{1,200}$/.test(p);
function counts(obj, keyOk = pkgOk) {
  const out = {};
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return out;
  for (const [k, v] of Object.entries(obj)) { const n = int(v); if (keyOk(k) && n > 0) out[k] = n; }
  return out;
}
const total = obj => Object.values(obj).reduce((a, b) => a + b, 0);
const latestOf = rows => rows.reduce((m, r) => Math.max(m, time(r?.updatedAt) || 0), 0) || null;

// ─────────────────────────────── muzica ───────────────────────────────
function musicSummary(m) {
  if (!m || !Array.isArray(m.top)) return null;
  const top = m.top.filter(t => t && str(t.title, 120)).slice(0, 10).map(t => ({ title: str(t.title, 120), artist: str(t.artist, 120), plays: int(t.plays) ?? 0, minutes: int(t.minutes) ?? 0, app: str(t.app, 40) }));
  return { updatedAt: time(m.updatedAt), windowDays: int(m.windowDays) ?? 7, totalMinutes: int(m.totalMinutes) ?? 0, top };
}
/** O zi de ascultări (users/{uid}/listens/{zi}): piesele în ordine, cu minutele, săriturile și ce a pornit FORJA. */
function listenDay(doc) {
  const items = (Array.isArray(doc.items) ? doc.items : []).filter(x => x && time(x.at) && str(x.title, 120)).slice(0, MIND_RULES.listens_per_day)
    .map(x => ({ at: x.at, title: str(x.title, 120), artist: str(x.artist, 120), app: str(x.app, 40), durS: Math.max(0, int(x.durS) ?? 0),
      src: x.src === 'forja' ? 'forja' : 'user', event: x.event === 'skip' ? 'skip' : 'play', kind: ['music', 'talk', 'video'].includes(x.kind) ? x.kind : null }))
    .sort((a, b) => a.at - b.at);
  const plays = items.filter(x => x.event === 'play');
  return {
    date: doc.date, updatedAt: time(doc.updatedAt),
    minutes: items.length ? Math.round(plays.reduce((s, x) => s + x.durS, 0) / 60) : Math.max(0, int(doc.minutes) ?? 0),
    plays: items.length ? plays.length : Math.max(0, int(doc.count) ?? 0),
    skips: items.length ? items.length - plays.length : Math.max(0, int(doc.skips) ?? 0),
    forja: items.length ? plays.filter(x => x.src === 'forja').length : Math.max(0, int(doc.forja) ?? 0),
    firstAt: items[0]?.at ?? null, lastAt: items.at(-1)?.at ?? null, items,
  };
}
async function listens(fs, uid, now, n) {
  const key = `${uid}:listens:${n}`, hit = recall(key, MIND_RULES.listens_ms, now);
  if (hit) return hit;
  const dates = lastDates(now, n), mark = fs.mark();
  const rows = await fs.query(`users/${uid}`, 'listens', { filters: [where('date', 'GREATER_THAN_OR_EQUAL', dates.at(-1))], limit: n });
  const days = (rows || []).filter(r => typeof r.date === 'string' && dates.includes(r.date)).map(listenDay).sort((a, b) => (a.date < b.date ? 1 : -1));
  const out = { window: n, updatedAt: latestOf(days), days };
  if (rows && !fs.failedSince(mark)) remember(key, out, now);
  return out;
}
export async function muzica({ fs, uid, now, url }) {
  const docs = await fs.batchGet([`users/${uid}`, `users/${uid}/settings/music`], ['nowPlaying', 'contract', 'updatedAt', 'windowDays', 'totalMinutes', 'top']);
  const me = docs.get(`users/${uid}`);
  // The live song is the one friends see too; the weekly top went up only with contract v3, the listening log with v4.
  return {
    now: nowPlaying(me?.nowPlaying, now),
    summary: contractGate(me?.contract) ? musicSummary(docs.get(`users/${uid}/settings/music`)) : null,
    listens: contractGate(me?.contract, 4) ? await listens(fs, uid, now, dayParam(url, MIND_RULES.muzica_days)) : null,
  };
}

// ─────────────────────────────── paza ───────────────────────────────
/**
 * Timpul pe ecran din DO (`usage-day:*`, până la 14 zile, pe ore). Doar cu contractul semnat (v3+) și nerevocat: după o
 * revocare, rândurile rămase nu se mai arată. Dacă Firestore nu răspunde, se arată ce e în DO (ștergerea la revocare
 * /v2/site/forget e garanția), marcat `contract: null`.
 */
export async function paza(ctx) {
  const { env, uid, fs, url } = ctx;
  const n = dayParam(url, MIND_RULES.paza_days), mark = fs.mark();
  const me = await fs.get(`users/${uid}`, ['contract']);
  const failed = fs.failedSince(mark);
  if (!failed && !contractGate(me?.contract)) return { updated_at: null, days: [], window: n, contract: false };
  if (failed) ctx.stale = true;
  const s = await account(env, uid, `/internal/site/usage?days=${n}`);
  return { updated_at: s?.updated_at ?? null, days: s?.days || [], window: n, contract: failed ? null : true };
}

// ─────────────────────────────── concentrare ───────────────────────────────
function session(s, labels) {
  const startAt = time(s?.startAt);
  if (!startAt) return null;
  const endAt = time(s.endAt) && s.endAt >= startAt ? s.endAt : null;
  const hits = counts(s.hits);
  const apps = (Array.isArray(s.rules) ? s.rules : []).filter(pkgOk).slice(0, 30);
  return {
    startAt, endAt, kind: s.kind === 'detox' ? 'detox' : 'focus', plannedMin: Math.max(0, int(s.plannedMin) ?? 0),
    minutes: endAt ? Math.round((endAt - startAt) / 60000) : null, grown: s.grown === true, withered: s.withered === true,
    apps: apps.map(pkg => ({ pkg, label: str(labels[pkg], 80) || pkg })),
    hits: Object.entries(hits).sort((a, b) => b[1] - a[1]).map(([pkg, n]) => ({ pkg, label: str(labels[pkg], 80) || pkg, n })),
    endedBy: ['timer', 'user', 'system'].includes(s.endedBy) ? s.endedBy : null,
  };
}
const CASCA_OUTCOMES = ['posted', 'opened', 'tapped', 'dismissed'];
function cascaItem(x) {
  const at = time(x?.at);
  if (!at) return null;
  const hidden = x.private === true;
  return {
    at, ctx: str(x.ctx, 40), channel: str(x.channel, 40), private: hidden,
    title: hidden ? null : str(x.title, 160), body: hidden ? null : str(x.body, 400),
    outcome: CASCA_OUTCOMES.includes(x.outcome) ? x.outcome : 'posted',
  };
}

/**
 * „concentrare” (contract v4): pe zile — minutele de concentrare și de detox digital, copacii crescuți / uscați, încercările
 * de a deschide o aplicație consemnată, respirația, interceptările paznicului pe pachete (niciodată textul) — încrucișate cu
 * timpul pe ecran din DO (cât a stat în aplicațiile consemnate). Plus seria detoxului, mesajele Căștii și, doar cu acordul
 * separat din FORJA, cuvintele de care se lasă și scrisoarea.
 */
export async function concentrare(ctx) {
  const { fs, uid, url, now, env } = ctx;
  const n = dayParam(url, MIND_RULES.concentrare_days), dates = lastDates(now, n), oldest = dates.at(-1);
  const me = await fs.get(`users/${uid}`, ['contract']);
  const c = contractOf(me?.contract), on = contractGate(me?.contract, 4);
  const out = { window: n, contract: { on, version: c.version, needs: 4 }, updatedAt: null, days: [], blocked: [], detox: null, breath: { minutes: 0, sessions: 0 }, casca: [] };
  if (!on) return out;
  const q = coll => fs.query(`users/${uid}`, coll, { filters: [where('date', 'GREATER_THAN_OR_EQUAL', oldest)], limit: n });
  const [focusRows, detoxRows, breathRows, nudgeRows, words, usage] = await Promise.all([
    q('focus'), q('detox'), q('breath'), q('nudges'),
    fs.get(`users/${uid}/detox/words`, ['onSite', 'words', 'packs', 'letter', 'updatedAt']),
    account(env, uid, `/internal/site/usage?days=${Math.min(14, n)}`),
  ]);
  const F = byDate(focusRows), D = byDate(detoxRows), B = byDate(breathRows), N = byDate(nudgeRows), U = byDate(usage?.days);
  const blocked = new Map();
  const blockedOf = (pkg, label) => { if (!blocked.has(pkg)) blocked.set(pkg, { pkg, label: label || pkg, hits: 0, sessions: 0, screenMin: 0 }); const b = blocked.get(pkg); if (label && b.label === pkg) b.label = label; return b; };
  const detoxDays = [];
  let breathMin = 0, breathSessions = 0;
  for (const date of dates) {
    const f = F.get(date), d = D.get(date), b = B.get(date), u = U.get(date);
    const labels = f?.labels && typeof f.labels === 'object' ? f.labels : {};
    const sessions = (Array.isArray(f?.sessions) ? f.sessions : []).slice(0, MIND_RULES.sessions_per_day).map(s => session(s, labels)).filter(Boolean).sort((a, b2) => a.startAt - b2.startAt);
    const hits = counts(f?.hits);
    for (const s of sessions) for (const a of s.apps) if (s.kind === 'focus') blockedOf(a.pkg, a.label).sessions++;
    for (const [pkg, k] of Object.entries(hits)) blockedOf(pkg, str(labels[pkg], 80)).hits += k;
    const breath = (Array.isArray(b?.sessions) ? b.sessions : []).filter(x => time(x?.startAt)).slice(0, MIND_RULES.sessions_per_day)
      .map(x => ({ startAt: x.startAt, durationS: Math.max(0, int(x.durationS) ?? 0), cycles: Math.max(0, int(x.cycles) ?? 0), pattern: str(x.pattern, 20), completed: x.completed === true }));
    const bMin = breath.length ? Math.round(breath.reduce((s, x) => s + x.durationS, 0) / 60) : Math.max(0, int(b?.minutes) ?? 0);
    breathMin += bMin; breathSessions += breath.length;
    const byPack = counts(d?.byPack, k => MIND_RULES.packs.includes(k));
    const interceptions = d ? Math.max(int(d.interceptions) ?? 0, total(byPack)) : 0;
    if (d) detoxDays.push({ date, interceptions, byPack });
    if (!f && !d && !b) continue;
    const minutesOf = kind => (num(f?.[kind + 'Min']) !== null ? Math.max(0, int(f[kind + 'Min'])) : sessions.filter(s => s.kind === kind).reduce((a, s) => a + (s.minutes || 0), 0));
    out.days.push({
      date, focusMin: minutesOf('focus'), detoxMin: minutesOf('detox'),
      grown: Math.max(0, int(f?.grown) ?? 0), withered: Math.max(0, int(f?.withered) ?? 0), hits: total(hits),
      breathMin: bMin, interceptions, screenMin: u ? u.totalMin : null, sessions, breath,
    });
  }
  // Timpul pe ecran în aplicațiile consemnate, în aceeași fereastră (cât există în DO: cel mult 14 zile).
  for (const u of usage?.days || []) if (dates.includes(u.date)) for (const a of u.apps || []) if (blocked.has(a.pkg)) blocked.get(a.pkg).screenMin += a.minutes || 0;
  out.blocked = [...blocked.values()].sort((a, b) => b.hits - a.hits || b.screenMin - a.screenMin).slice(0, 20);
  out.breath = { minutes: breathMin, sessions: breathSessions };
  const lastDetox = dates.map(x => D.get(x)).find(Boolean) || null;
  if (lastDetox || words?.onSite === true) {
    const byPack = {};
    for (const x of detoxDays) for (const [k, v] of Object.entries(x.byPack)) byPack[k] = (byPack[k] || 0) + v;
    out.detox = {
      guardOn: lastDetox?.guardOn === true, addictionOn: lastDetox?.addictionOn === true, streakStart: time(lastDetox?.streakStart),
      slips: Math.max(0, int(lastDetox?.slips) ?? 0), interceptions: detoxDays.reduce((s, x) => s + x.interceptions, 0), byPack, days: detoxDays,
      words: words?.onSite === true ? {
        words: (Array.isArray(words.words) ? words.words : []).map(w => str(w, 60)).filter(Boolean).slice(0, MIND_RULES.words_max),
        packs: (Array.isArray(words.packs) ? words.packs : []).filter(p => MIND_RULES.packs.includes(p)),
        letter: str(words.letter, MIND_RULES.letter_max), updatedAt: time(words.updatedAt),
      } : null,
    };
  }
  out.casca = dates.flatMap(x => (Array.isArray(N.get(x)?.items) ? N.get(x).items : [])).map(cascaItem).filter(Boolean)
    .sort((a, b) => b.at - a.at).slice(0, MIND_RULES.casca_max);
  out.updatedAt = latestOf([...(focusRows || []), ...(detoxRows || []), ...(breathRows || []), ...(nudgeRows || [])]);
  return out;
}
