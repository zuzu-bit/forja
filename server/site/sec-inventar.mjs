// FORJA 4.4 — secțiunea „inventar”: rulările Inventarului (contract v3) și copiile galeriei din contul DO (24 h).
// Mirror (pachetul C, contract v4): oglinda galeriei și a documentelor cât ai contul (albumele, contorul de spațiu; pozele
// însele se citesc direct din /v2/mirror), numărătoarea din telefon (`settings/storage`) și jocurile ZID / ASALT
// (`games/{zid|asalt}`). Rulările poartă acum și starea (în analiză, gata, aplicată), motivele și coperțile.
import { SITE_RULES, num, int, str, time, summaryOf, account, contractGate } from './shared.mjs';

const GAMES = [['zid', 'ZID', 15], ['asalt', 'ASALT', 12]];
const STORAGE_FIELDS = ['photos', 'videos', 'docs', 'gallery', 'updatedAt'];
const GAME_FIELDS = ['unlocked', 'levels', 'cleared', 'starsTotal', 'endlessBest', 'best', 'stars', 'plays', 'playedS', 'playedToday', 'lastAt', 'updatedAt'];

const count = v => (int(v) !== null && v >= 0 ? int(v) : null);
function censusOf(d) {
  if (!d) return null;
  const pair = x => (x && typeof x === 'object' ? { count: count(x.count), bytes: count(x.bytes) } : null);
  const docs = d.docs && typeof d.docs === 'object' ? { loose: count(d.docs.loose), organized: count(d.docs.organized), bytes: count(d.docs.bytes), folders: count(d.docs.folders) } : null;
  const g = d.gallery && typeof d.gallery === 'object' ? { total: count(d.gallery.total), mirrored: count(d.gallery.mirrored), waiting: count(d.gallery.waiting),
    state: str(d.gallery.state, 20), cellular: d.gallery.cellular === true, lastAt: time(d.gallery.lastAt) } : null;
  return { photos: pair(d.photos), videos: pair(d.videos), docs, gallery: g, updatedAt: time(d.updatedAt) };
}
function gameOf(id, label, levels, d) {
  if (!d) return null;
  const plays = (Array.isArray(d.plays) ? d.plays : []).slice(0, 50).filter(p => p && time(p.at)).map(p => ({ at: p.at, level: int(p.level), outcome: str(p.outcome, 10),
    stars: int(p.stars) ?? 0, score: int(p.score) ?? 0, durationS: int(p.durationS) ?? 0 }));
  const stars = d.stars && typeof d.stars === 'object' ? Object.fromEntries(Object.entries(d.stars).filter(([k, v]) => /^\d{1,2}$/.test(k) && int(v) !== null).map(([k, v]) => [k, Math.max(0, Math.min(3, int(v)))])) : {};
  return { id, label, levels: int(d.levels) ?? levels, unlocked: int(d.unlocked) ?? 1, cleared: int(d.cleared) ?? 0, starsTotal: int(d.starsTotal) ?? 0,
    endlessBest: int(d.endlessBest), stars, playedS: int(d.playedS) ?? 0, playedToday: int(d.playedToday) ?? 0, lastAt: time(d.lastAt), plays };
}

export async function inventar({ env, fs, uid, now }) {
  const [me, runs, s] = await Promise.all([
    fs.get(`users/${uid}`, ['contract']),
    fs.query(`users/${uid}`, 'inventory', { orderBy: 'finishedAt', limit: SITE_RULES.inventar_runs }),
    summaryOf(env, uid),
  ]);
  // Run summaries went up only with contract v3; the gallery copies live in the account (24 h) and follow their own pipe.
  const out = { runs: contractGate(me?.contract) ? runs || [] : [], vault: { total: s?.vault?.total ?? 0, latestAt: s?.vault?.latestAt ?? null } };
  if (!contractGate(me?.contract, 4)) return out;
  // v4: oglinda (DO, 0 citiri Firestore) + numărătoarea și jocurile (1 batchGet de 3 documente).
  const [mirror, docs] = await Promise.all([
    account(env, uid, '/v2/mirror/summary'),
    fs.batchGet([`users/${uid}/settings/storage`, ...GAMES.map(([id]) => `users/${uid}/games/${id}`)], [...new Set([...STORAGE_FIELDS, ...GAME_FIELDS])]),
  ]);
  out.mirror = mirror ? { stats: mirror.stats, albums: (mirror.albums || []).slice(0, 400), meter: mirror.meter, consent: mirror.consent } : null;
  out.storage = censusOf(docs.get(`users/${uid}/settings/storage`));
  out.games = GAMES.map(([id, label, levels]) => gameOf(id, label, levels, docs.get(`users/${uid}/games/${id}`))).filter(Boolean);
  return out;
}
