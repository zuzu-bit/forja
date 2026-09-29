// Partea din Durable Object-ul contului (InsightsAccount) pe care o citesc secțiunile site-ului 4.4:
//  · /internal/site/summary — sesiuni, vault, telefoane, pauza primirii și zilele de „Pază” (fără Firestore);
//  · /internal/site/cache   — memoria de 10 min / 20 s a secțiunii „Cerc” (Teren + Camarazi), ca poll-ul de 30 s al site-ului
//    să nu recitească din Firestore prieteniile, locurile recomandate și traseele la fiecare tură;
//  · /internal/site/usage   — zilele „Pazei” (?days= până la 14), cu orele, prima și ultima folosire;
//  · rollup-ul zilnic al timpului pe ecran (`usage-day:YYYY-MM-DD`, 14 zile, pe ore), scris când telefonul trimite datele sesiunii.
// Alarma DO-ului (`sweepSite`) șterge zilele mai vechi de 14 și copia live a Cercului (pozițiile prietenilor) după 10 minute,
// chiar dacă telefonul sau site-ul nu mai trimit nimic.
// Rutele /internal/* nu sunt accesibile din afară: Workerul trimite spre DO doar căile permise explicit.
import { recoverySummary } from './lost-phone.mjs';
import { bad } from './phone-schema.mjs';
import { localDate, localMidnight, offsetAt, DAY } from './site-time.mjs';

export const USAGE_RULES = Object.freeze({ keep_days: 14, show_days: 7, apps_per_day: 100, apps_shown: 30 });
/** How long the live tier of Cerc (friends' positions) may be served from the DO when Firestore fails, and kept at all. */
export const CERC_LIVE_MAX_MS = 10 * 60000;
const CACHE_NAMES = ['cerc-live', 'cerc-slow', 'friends', 'mars-routes'];
const LIVE_KEY = 'site-cache:cerc-live';
const CACHE_MAX_BYTES = 1536 * 1024;
const DAY_PREFIX = 'usage-day:';
const reply = (data, status = 200) => Response.json(data, { status, headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
/** The local date `k` calendar days before the day of `now` (0 = today), right across daylight-saving changes. */
const daysAgo = (now, k) => localDate(localMidnight(now) - k * DAY + 12 * 3600000);
/** The moment a `usage-day:` row of `date` leaves the 14-day window: local midnight, 14 days after that day began. */
function rowExpiry(date) {
  const [y, m, d] = date.split('-').map(Number);
  return localMidnight(Date.UTC(y, m - 1, d + USAGE_RULES.keep_days, 12));
}

/** FORJA însăși nu intră în „timpul pe ecran” (ca pe ecranul Focus): apare separat, `self`. */
export const isSelfApp = pkg => /^com\.forja\.app(\.|$)/.test(String(pkg || ''));
const HOUR_MS = 3600000;
/**
 * Împarte [from, to] pe orele locale: [{date, hour, start, ms}]. Ora României are decalaj întreg (+2 / +3 h), deci
 * granițele de oră locală sunt granițele de oră UTC; în noaptea cu ora dată înapoi, ora 03 primește ambele ore.
 */
export function splitByHour(from, to) {
  const out = [];
  if (!(Number.isFinite(from) && Number.isFinite(to)) || to <= from) return out;
  let at = from;
  for (let guard = 0; at < to && guard < 24 * 400; guard++) {
    const end = Math.min(to, (Math.floor(at / HOUR_MS) + 1) * HOUR_MS);
    out.push({ date: localDate(at), hour: new Date(at + offsetAt(at)).getUTCHours(), start: at, ms: end - at });
    at = end;
  }
  return out;
}
const emptyHours = () => Array(24).fill(0);

/**
 * Timpul pe ecran pe zile: sesiunea automată trimite la ~60 s totalurile cumulate ale ferestrei ei (≤ 24 h).
 * Păstrăm ultima fotografie a fiecărei sesiuni și adunăm doar diferența, împărțită pe orele locale ale intervalului
 * dintre fotografii (o aplicație folosită ultima dată înainte de miezul nopții nu intră în ziua nouă). Fiecare zi ține
 * și cele 24 de ore (ms, fără FORJA), prima și ultima folosire. `usage_backfill` (zilele încheiate, numărate pe telefon
 * din istoricul Android) înlocuiește o zi doar când nu are rând, e tot o zi trimisă așa, sau are cel puțin cât rândul.
 */
export async function applyUsageRollup(storage, sessionId, data, now = Date.now()) {
  const win = data?.usage_window, apps = data?.app_usage;
  if (!win || !Array.isArray(apps)) return false;
  const lastKey = 'usage-last:' + sessionId, prev = await storage.get(lastKey);
  const writes = {};
  if (!(prev && win.to <= prev.to)) { // altfel: retrimitere sau fotografie mai veche (zilele trimise întregi merg totuși)
    const start = prev ? Math.max(prev.to, win.from) : win.from, end = win.to;
    const touched = new Map();
    const day = async date => {
      if (!touched.has(date)) touched.set(date, (await storage.get(DAY_PREFIX + date)) || { date, apps: {} });
      return touched.get(date);
    };
    for (const a of apps) {
      const before = prev?.apps?.[a.package] || [0, 0];
      const ms = Math.max(0, a.foreground_ms - before[0]), opens = Math.max(0, a.opens - before[1]);
      if (!ms && !opens) continue;
      const self = isSelfApp(a.package);
      const lastUsed = Number.isFinite(a.last_used) ? Math.min(end, Math.max(start, a.last_used)) : end;
      // Timpul nou al aplicației stă cel mult în [ultima folosire − ms, ultima folosire]: un gol lung între fotografii
      // (telefon offline, Doze, repornire) nu se întinde pe toată noaptea și nu mută „prima dată” la 00:00.
      const from = Math.max(start, lastUsed - ms);
      const pieces = splitByHour(from, Math.max(lastUsed, from + 1));
      const total = pieces.reduce((n, p) => n + p.ms, 0) || 1;
      for (const p of pieces) {
        const share = Math.round(ms * p.ms / total);
        if (!share) continue;
        const row = await day(p.date);
        const cur = row.apps[a.package] || { label: a.label, ms: 0, opens: 0 };
        cur.label = a.label || cur.label; cur.ms += share; cur.lastAt = Math.max(cur.lastAt || 0, p.start + p.ms);
        row.apps[a.package] = cur;
        if (self) continue;
        if (!Array.isArray(row.hours) || row.hours.length !== 24) row.hours = emptyHours();
        row.hours[p.hour] += share;
        row.firstAt = row.firstAt ? Math.min(row.firstAt, p.start) : p.start;
        row.lastAt = Math.max(row.lastAt || 0, p.start + p.ms);
      }
      if (opens) {
        const row = await day(localDate(lastUsed));
        const cur = row.apps[a.package] || { label: a.label, ms: 0, opens: 0 };
        cur.label = a.label || cur.label; cur.opens += opens; row.apps[a.package] = cur;
      }
    }
    writes[lastKey] = { from: win.from, to: win.to, at: now, apps: Object.fromEntries(apps.map(a => [a.package, [a.foreground_ms, a.opens]])) };
    for (const [date, row] of touched) {
      const top = Object.entries(row.apps).sort((x, y) => y[1].ms - x[1].ms).slice(0, USAGE_RULES.apps_per_day);
      writes[DAY_PREFIX + date] = { ...row, date, updated_at: now, apps: Object.fromEntries(top) };
    }
  }
  if (Array.isArray(data.usage_backfill)) {
    const oldest = daysAgo(now, USAGE_RULES.keep_days - 1), today = localDate(now);
    for (const d of data.usage_backfill) {
      if (typeof d?.date !== 'string' || d.date < oldest || d.date >= today || !Array.isArray(d.apps)) continue;
      const cur = writes[DAY_PREFIX + d.date] || (await storage.get(DAY_PREFIX + d.date));
      const curMs = cur ? Object.values(cur.apps || {}).reduce((n, a) => n + (a.ms || 0), 0) : 0;
      const total = d.apps.reduce((n, a) => n + (a.foreground_ms || 0), 0);
      if (cur && cur.source !== 'day' && curMs > total) continue; // alt telefon a adus mai mult: nu scădem ziua
      writes[DAY_PREFIX + d.date] = {
        date: d.date, updated_at: now, source: 'day', firstAt: d.first_at || null, lastAt: d.last_at || null,
        hours: Array.isArray(d.hours) && d.hours.length === 24 ? d.hours.map(x => Math.max(0, Math.round(x) || 0)) : null,
        // „ultima” pe aplicație: cea trimisă de telefon (last_used) sau, altfel, cea din rândul live înlocuit.
        apps: Object.fromEntries(d.apps.filter(a => a.foreground_ms > 0 || a.opens > 0).map(a => [a.package, {
          label: a.label, ms: a.foreground_ms, opens: a.opens,
          ...((Number.isFinite(a.last_used) ? a.last_used : cur?.apps?.[a.package]?.lastAt) ? { lastAt: Number.isFinite(a.last_used) ? a.last_used : cur.apps[a.package].lastAt } : {}),
        }])),
      };
    }
  }
  if (!Object.keys(writes).length) return false;
  // Scrieri una câte una: în DO se unesc oricum într-o singură tranzacție (write coalescing).
  for (const [k, v] of Object.entries(writes)) await storage.put(k, v);
  // 14 zile de rollup, nu mai mult (și alarma le șterge când telefonul nu mai trimite).
  await pruneUsage(storage, now);
  return true;
}
async function pruneUsage(storage, now) {
  const oldest = daysAgo(now, USAGE_RULES.keep_days - 1);
  const dates = [...(await storage.list({ prefix: DAY_PREFIX })).keys()].map(k => k.slice(DAY_PREFIX.length));
  const stale = dates.filter(d => d < oldest);
  if (stale.length) await storage.delete(stale.map(d => DAY_PREFIX + d));
  return dates.filter(d => d >= oldest).sort();
}

/**
 * Called by the account DO's sweep (its alarm): drops the screen-time days older than 14 and the live copy of Cerc once it is
 * older than the 10 minutes it may be served. Returns the next time something has to go (Infinity when nothing is left).
 */
export async function sweepSite(storage, now = Date.now()) {
  let next = Infinity;
  const kept = await pruneUsage(storage, now);
  if (kept.length) next = rowExpiry(kept[0]);
  const live = await storage.get(LIVE_KEY);
  if (live) {
    const end = (Number(live.at) || 0) + CERC_LIVE_MAX_MS;
    if (end <= now) await storage.delete(LIVE_KEY); else next = Math.min(next, end);
  }
  return next;
}

/**
 * Zilele cu date din ultimele `n` zile calendaristice (azi inclusiv, n ≤ 14), cea mai nouă prima:
 * [{date, totalMin, forjaMin, firstAt, lastAt, hours[24] (minute) | null, source, apps:[{label, pkg, minutes, opens, lastAt, self?}]}].
 * totalMin și orele nu includ FORJA (ca ecranul Focus); FORJA rămâne în listă, cu `self: true`.
 */
export async function usageDays(storage, n = USAGE_RULES.show_days, now = Date.now()) {
  n = Math.max(1, Math.min(USAGE_RULES.keep_days, n));
  const oldest = daysAgo(now, n - 1);
  const rows = [...(await storage.list({ prefix: DAY_PREFIX })).values()].filter(r => typeof r?.date === 'string' && r.date >= oldest)
    .sort((a, b) => (a.date < b.date ? 1 : -1)).slice(0, n);
  const min = ms => Math.round((ms || 0) / 60000);
  return {
    updated_at: rows.reduce((m, r) => Math.max(m, r.updated_at || 0), 0) || null,
    days: rows.map(r => {
      const entries = Object.entries(r.apps || {});
      const apps = entries.map(([pkg, a]) => ({ label: a.label || pkg, pkg, minutes: min(a.ms), opens: a.opens || 0, lastAt: a.lastAt || null, ...(isSelfApp(pkg) ? { self: true } : {}) }));
      const other = entries.filter(([pkg]) => !isSelfApp(pkg)), self = entries.filter(([pkg]) => isSelfApp(pkg));
      return {
        date: r.date,
        totalMin: min(other.reduce((s, [, a]) => s + (a.ms || 0), 0)),
        forjaMin: min(self.reduce((s, [, a]) => s + (a.ms || 0), 0)),
        firstAt: r.firstAt || null, lastAt: r.lastAt || null,
        hours: Array.isArray(r.hours) && r.hours.length === 24 ? r.hours.map(min) : null,
        source: r.source === 'day' ? 'day' : 'live',
        apps: apps.filter(a => a.minutes > 0).sort((a, b) => b.minutes - a.minutes || b.opens - a.opens).slice(0, USAGE_RULES.apps_shown),
      };
    }),
  };
}

async function summary(storage, now) {
  const sessions = [...(await storage.list({ prefix: 'session:' })).values()].filter(r => r.expires_at > now && !r.sleep_session_id);
  const withData = sessions.filter(r => r.data);
  const files = [...(await storage.list({ prefix: 'cloud-file:' })).values()].filter(f => f.expires_at > now);
  const intake = await storage.get('intake');
  return {
    sessions: { count: sessions.length, updated_at: withData.reduce((m, r) => Math.max(m, r.updated_at || 0), 0) || null },
    usage: await usageDays(storage, USAGE_RULES.show_days, now),
    vault: { total: files.length, latestAt: files.reduce((m, f) => Math.max(m, f.received_at || 0), 0) || null },
    recovery: await recoverySummary(storage, now),
    intake: { paused: intake?.accepting === false },
  };
}

/** Called inside the account DO after the owner binding. Returns null for any other path. */
export async function handleSiteStore(request, account, readJSON) {
  const url = new URL(request.url), path = url.pathname;
  if (!path.startsWith('/internal/site/')) return null;
  const storage = account.ctx.storage, now = Date.now();
  if (path === '/internal/site/summary' && request.method === 'GET') return reply(await summary(storage, now));
  // „Pază” cu ?days= (1–14): aceleași rânduri ca rezumatul, cu orele și prima / ultima folosire.
  if (path === '/internal/site/usage' && request.method === 'GET') {
    const raw = Number(url.searchParams.get('days'));
    return reply(await usageDays(storage, Number.isInteger(raw) && raw > 0 ? raw : USAGE_RULES.show_days, now));
  }
  if (path === '/internal/site/cache') {
    if (request.method === 'GET') {
      const names = (url.searchParams.get('names') || '').split(',').filter(n => CACHE_NAMES.includes(n));
      const values = await Promise.all(names.map(n => storage.get('site-cache:' + n)));
      return reply(Object.fromEntries(names.map((n, i) => [n, values[i] ?? null])));
    }
    if (request.method === 'POST') {
      const { value } = await readJSON(request, CACHE_MAX_BYTES);
      if (!value || typeof value !== 'object' || Array.isArray(value) || Object.keys(value).some(k => !CACHE_NAMES.includes(k))) bad('Memorie invalidă.');
      for (const [k, v] of Object.entries(value)) { if (v === null) await storage.delete('site-cache:' + k); else await storage.put('site-cache:' + k, v); }
      // Friends' positions do not outlive their 10 minutes: the alarm (sweep) removes them even if the site stops asking.
      const at = Number(value['cerc-live']?.at);
      if (Number.isFinite(at)) {
        const due = at + CERC_LIVE_MAX_MS, current = await storage.getAlarm();
        if (current === null || current === undefined || current > due) await storage.setAlarm(due);
      }
      return reply({ ok: true });
    }
  }
  bad('Not found', 404);
}
