// FORJA 4.4 — secțiunile minții: „muzica” (ce asculți acum, topul săptămânii), „paza” (timpul pe ecran, din DO) și
// „concentrare” (sesiunile de concentrare, detoxul, respirația; goală până o umple pachetul D).
import { int, str, time, summaryOf, nowPlaying, contractGate } from './shared.mjs';

function musicSummary(m) {
  if (!m || !Array.isArray(m.top)) return null;
  const top = m.top.filter(t => t && str(t.title, 120)).slice(0, 10).map(t => ({ title: str(t.title, 120), artist: str(t.artist, 120), plays: int(t.plays) ?? 0, minutes: int(t.minutes) ?? 0, app: str(t.app, 40) }));
  return { updatedAt: time(m.updatedAt), windowDays: int(m.windowDays) ?? 7, totalMinutes: int(m.totalMinutes) ?? 0, top };
}
export async function muzica({ fs, uid, now }) {
  const docs = await fs.batchGet([`users/${uid}`, `users/${uid}/settings/music`], ['nowPlaying', 'contract', 'updatedAt', 'windowDays', 'totalMinutes', 'top']);
  const me = docs.get(`users/${uid}`);
  // The live song is the one friends see too; the weekly top went up only with contract v3.
  return { now: nowPlaying(me?.nowPlaying, now), summary: contractGate(me?.contract) ? musicSummary(docs.get(`users/${uid}/settings/music`)) : null };
}
export async function paza({ env, uid }) {
  const s = await summaryOf(env, uid);
  return { updated_at: s?.usage?.updated_at ?? null, days: s?.usage?.days || [] };
}

/**
 * „concentrare”: sesiunile de concentrare, detoxul, respirația (contract v4). Deocamdată gol: e cusătura pe care pachetul D
 * o umple (focus/{zi}, detox/{zi}, breath, încrucișat cu usage-day din DO), cu contractGate(me?.contract, 4).
 */
export async function concentrare() {
  return {};
}
