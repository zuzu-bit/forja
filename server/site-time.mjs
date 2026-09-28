// Calendarul site-ului: zilele se socotesc după ora României (Europe/Bucharest, cu ora de vară), ca pe telefonul Lanei.
// Funcții pure; merg la fel în Worker (ICU complet) și în Node (testele).

export const TZ = 'Europe/Bucharest';
export const DAY = 86400000;
const formatter = new Intl.DateTimeFormat('en-CA', { timeZone: TZ, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });

function parts(ms) {
  const out = {};
  for (const p of formatter.formatToParts(new Date(ms))) if (p.type !== 'literal') out[p.type] = Number(p.value);
  if (out.hour === 24) out.hour = 0;
  return out;
}
/** Decalajul orei locale față de UTC la momentul `ms` (ms; +3 h vara, +2 h iarna). */
export function offsetAt(ms) {
  const p = parts(ms);
  return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - Math.floor(ms / 1000) * 1000;
}
/** „YYYY-MM-DD” — ziua locală a momentului `ms`. */
export function localDate(ms) {
  const p = parts(ms);
  return `${p.year}-${String(p.month).padStart(2, '0')}-${String(p.day).padStart(2, '0')}`;
}
/** Momentul (ms epoch) în care începe ziua locală a lui `ms` — corect și în zilele cu schimbarea orei. */
export function localMidnight(ms) {
  const p = parts(ms);
  const wall = Date.UTC(p.year, p.month - 1, p.day);
  let guess = wall - offsetAt(ms);
  guess = wall - offsetAt(guess);
  return guess;
}
/** Începutul zilei locale următoare după `ms`. */
export function nextMidnight(ms) {
  return localMidnight(localMidnight(ms) + DAY + 3 * 3600000);
}
/** Ziua unui `epochDay` (numărul de zile de la 1970-01-01, cum îl scrie telefonul la mese). */
export function epochDayDate(epochDay) {
  return new Date(epochDay * DAY).toISOString().slice(0, 10);
}
/**
 * Împarte intervalul [from, to] pe zile locale: [{date, ms}] în ordine cronologică, cu durata din fiecare zi.
 * Un interval gol sau inversat dă [].
 */
export function splitByDay(from, to) {
  const out = [];
  if (!(Number.isFinite(from) && Number.isFinite(to)) || to <= from) return out;
  let at = from;
  for (let guard = 0; at < to && guard < 400; guard++) {
    const end = Math.min(to, nextMidnight(at));
    out.push({ date: localDate(at), ms: end - at });
    at = end;
  }
  return out;
}
