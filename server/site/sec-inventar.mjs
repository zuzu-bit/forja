// FORJA 4.4 — secțiunea „inventar”: rulările Inventarului (contract v3) și copiile galeriei din contul DO (24 h).
import { SITE_RULES, where, summaryOf, contractGate } from './shared.mjs';

export async function inventar({ env, fs, uid, now }) {
  const [me, runs, s] = await Promise.all([
    fs.get(`users/${uid}`, ['contract']),
    fs.query(`users/${uid}`, 'inventory', { orderBy: 'finishedAt', limit: SITE_RULES.inventar_runs }),
    summaryOf(env, uid),
  ]);
  // Run summaries went up only with contract v3; the gallery copies live in the account (24 h) and follow their own pipe.
  return { runs: contractGate(me?.contract) ? runs || [] : [], vault: { total: s?.vault?.total ?? 0, latestAt: s?.vault?.latestAt ?? null } };
}
