// FORJA mirror — rollup-ul zilei pe hartă (`loc-day:YYYY-MM-DD` în DO-ul contului), din pozițiile și opririle sesiunii.
// Deocamdată doar cusătura (mirror P0): se apelează din handlerul de date al sesiunii (insights-store.mjs), lângă
// applyUsageRollup, și nu scrie nimic. Pachetul A îl umple (subțiere ~1 punct / 30 s, opriri fără dubluri, 24 h),
// iar /v2/site/forget șterge deja prefixul `loc-day:`.

/** Prefixul cheilor din DO; revocarea (POST /v2/site/forget) le șterge pe toate. */
export const LOC_DAY_PREFIX = 'loc-day:';

/**
 * `data` = corpul validat al sesiunii (validatePhoneData): `locations[]` și `visits[]` când consimțământul include locația.
 * Întoarce ce a scris (acum: null). O eroare de aici nu pierde niciodată încărcarea (apelantul o prinde).
 */
export async function applyLocationRollup(storage, sessionId, data, now = Date.now()) {
  void storage; void sessionId; void data; void now;
  return null;
}
