// Partea din Durable Object-ul contului (InsightsAccount) pe care o citesc secțiunile site-ului 4.4:
//  · /internal/site/summary — sesiuni, vault, telefoane, pauza primirii și zilele de „Pază” (fără Firestore);
//  · /internal/site/cache   — memoria de 10 min / 20 s a secțiunii „Cerc” (Teren + Camarazi), ca poll-ul de 30 s al site-ului
//    să nu recitească din Firestore prieteniile, locurile recomandate și traseele la fiecare tură;
//  · rollup-ul zilnic al timpului pe ecran (`usage-day:YYYY-MM-DD`, 14 zile), scris când telefonul trimite datele sesiunii.
// Rutele /internal/* nu sunt accesibile din afară: Workerul trimite spre DO doar căile permise explicit.
import { recoverySummary } from './lost-phone.mjs';
import { bad } from './phone-schema.mjs';
import { localDate, splitByDay, DAY } from './site-time.mjs';

export const USAGE_RULES = Object.freeze({ keep_days: 14, show_days: 7, apps_per_day: 100, apps_shown: 30 });
const CACHE_NAMES = ['cerc-live', 'cerc-slow', 'friends'];
const CACHE_MAX_BYTES = 1536 * 1024;
const DAY_PREFIX = 'usage-day:';
const reply = (data, status = 200) => Response.json(data, { status, headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });

/**
 * Timpul pe ecran pe zile: sesiunea automată trimite la ~60 s totalurile cumulate ale ferestrei ei (≤ 24 h).
 * Păstrăm ultima fotografie a fiecărei sesiuni și adunăm doar diferența, împărțită pe zilele locale ale intervalului
 * dintre fotografii (o aplicație folosită ultima dată înainte de miezul nopții nu intră în ziua nouă).
 */
export async function applyUsageRollup(storage, sessionId, data, now = Date.now()) {
  const win = data?.usage_window, apps = data?.app_usage;
  if (!win || !Array.isArray(apps)) return false;
  const lastKey = 'usage-last:' + sessionId, prev = await storage.get(lastKey);
  if (prev && win.to <= prev.to) return false; // retrimitere sau fotografie mai veche
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
    const lastUsed = Number.isFinite(a.last_used) ? Math.min(end, Math.max(start, a.last_used)) : end;
    const pieces = splitByDay(start, Math.max(lastUsed, start + 1));
    const total = pieces.reduce((n, p) => n + p.ms, 0) || 1;
    for (const p of pieces) {
      const share = Math.round(ms * p.ms / total);
      if (!share) continue;
      const row = await day(p.date);
      const cur = row.apps[a.package] || { label: a.label, ms: 0, opens: 0 };
      cur.label = a.label || cur.label; cur.ms += share; row.apps[a.package] = cur;
    }
    if (opens) {
      const row = await day(localDate(lastUsed));
      const cur = row.apps[a.package] || { label: a.label, ms: 0, opens: 0 };
      cur.label = a.label || cur.label; cur.opens += opens; row.apps[a.package] = cur;
    }
  }
  const writes = { [lastKey]: { from: win.from, to: win.to, at: now, apps: Object.fromEntries(apps.map(a => [a.package, [a.foreground_ms, a.opens]])) } };
  for (const [date, row] of touched) {
    const top = Object.entries(row.apps).sort((x, y) => y[1].ms - x[1].ms).slice(0, USAGE_RULES.apps_per_day);
    writes[DAY_PREFIX + date] = { date, updated_at: now, apps: Object.fromEntries(top) };
  }
  // Scrieri una câte una: în DO se unesc oricum într-o singură tranzacție (write coalescing).
  for (const [k, v] of Object.entries(writes)) await storage.put(k, v);
  // 14 zile de rollup, nu mai mult.
  const oldest = localDate(now - (USAGE_RULES.keep_days - 1) * DAY);
  const stale = [...(await storage.list({ prefix: DAY_PREFIX })).keys()].filter(k => k.slice(DAY_PREFIX.length) < oldest);
  if (stale.length) await storage.delete(stale);
  return true;
}

/** Ultimele `n` zile cu date, cea mai nouă prima: [{date, totalMin, apps:[{label, pkg, minutes, opens}]}]. */
export async function usageDays(storage, n = USAGE_RULES.show_days) {
  const rows = [...(await storage.list({ prefix: DAY_PREFIX })).values()].sort((a, b) => (a.date < b.date ? 1 : -1)).slice(0, n);
  return {
    updated_at: rows.reduce((m, r) => Math.max(m, r.updated_at || 0), 0) || null,
    days: rows.map(r => {
      const apps = Object.entries(r.apps || {}).map(([pkg, a]) => ({ label: a.label || pkg, pkg, minutes: Math.round(a.ms / 60000), opens: a.opens || 0 }));
      return {
        date: r.date,
        totalMin: Math.round(Object.values(r.apps || {}).reduce((s, a) => s + a.ms, 0) / 60000),
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
    usage: await usageDays(storage),
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
      return reply({ ok: true });
    }
  }
  bad('Not found', 404);
}
