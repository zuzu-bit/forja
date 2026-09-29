// FORJA 4.4 — API-ul de citire al site-ului: o secțiune pe fiecare abilitate a aplicației (DESIGN-4.4 §3.1–§3.2).
//
// Toate rutele sunt GET, sub /insights/api/*, după verificarea tokenului Firebase în insights-worker.mjs. Datele telefonului
// se citesc din Firestore prin REST CU TOKENUL CELUI CARE CERE (regulile Firestore se aplică exact ca în aplicație), iar ce
// ține de site (sesiuni, vault, telefoane, pauză, timp pe ecran) vine din Durable Object-ul contului. Nopțile (audio + analiză)
// vin din R2 `forja-sleep` (binding SLEEP), cu chei construite NUMAI din uid-ul verificat.
// Reguli copiate din aplicație: fantoma ascunde lat/lng/nowPlaying (FriendsRepository.kt:173-201); nowPlaying doar sub 10 min;
// poziția familiei din familyLoc (citibilă doar când ești în `allowed`). Nicio rută nu întoarce emailul altcuiva.
//
// Bugetul Firestore (Spark: 50 000 de citiri pe zi pentru tot proiectul). O citire = un document întors; o interogare fără
// rezultate costă tot 1. N = prieteni, F = documente familyLoc vizibile, R = locuri recomandate ție.
// Regulile noi (FIRESTORE-RULES.md) mai numără 1–2 exists() pe prietenie pentru fiecare profil de prieten citit: se
// facturează tot ca citiri, deci un profil de prieten costă ~2.
//   cerc      live (≤ 20 s, memorie + DO):  1 (eu) + N (batchGet) + N (exists în reguli) + max(1,F)   ≈ 22 cu 10 prieteni
//             lista de prieteni (≤ 10 min, DO): max(1,N)                                         ≈ 10 / 10 min
//             lent (≤ 10 min, DO): max(1,R) locuri + 1 (alergări mai noi decât ultima cunoscută)     ≈ 6 / 10 min
//             traseele se construiesc o singură dată (pagini de 5, ≤ 600 KB de polilinii pe cerere) și rămân în DO;
//             o dată pe zi, ~30–40 de citiri caută alergările ajunse târziu în Firestore (cu startAt mai vechi)
//             → la un poll de 30 s (doar cât Teren/Camarazi e vizibil): ≈ 120×22 + 6×10 + 6×6 ≈ 2 700 de citiri pe oră de
//               site deschis, adică ~18 ore de privit continuu înainte de plafonul zilnic.
//   azi       3 (eu, ținte, muzică) + mesele de azi + ≤ 60 activități și ≤ 60 antrenamente pe 7 zile + 2 nopți + 1 inventar
//             (+ N dacă lista de prieteni nu e în DO)                                            ≈ 15–25, memorie 20 s
//             Site-ul o reîmprospătează cel mult o dată la 5 min cât Azi e vizibil (site-azi.js.txt) → ≤ 300 pe oră.
//   somn      nopțile din `days` (≤ 100) + 1 listare R2 · somn/<id>: 1 + 1 citire și 1 listare R2 · chunk: 0 (doar R2)
//   ratie     1 (eu: contractul) + 1 (ținte) + mesele din `days` (≤ 800)                       ≈ 60–120 pe 30 de zile
//   mars      1 (contractul) + activitățile (fără polilinii) + antrenamentele din max(`days`, 7) (≤ 200 + ≤ 200) + ≤ 8
//             polilinii care nu sunt nici în traseele Teren, nici în memoria Marș din DO (păstrate acolo, citite o dată)
//   muzica    2 · paza 0 · inventar 1 + ≤ 20 · cont 7
// Secțiunile în afară de Teren/Camarazi (și Azi, la 5 min) se citesc la deschidere, nu în buclă.
// Ce a urcat doar cu contractul v3 (ținte, antrenamente, topul muzicii, rulările Inventarului) se arată doar cât contractul
// e semnat cel puțin la v3 și nerevocat (contractGate(raw), min = 3; ce e nou în v4 cere contractGate(raw, 4)); jurnalele
// (mese, activități, nopți) nu țin de contract.
//
// Împărțirea (mirror P0): aici rămân ruterul și ce e comun (reexportat din site/shared.mjs: FirestoreReader, memoria scurtă,
// contractGate, ajutoarele); fiecare secțiune stă în site/sec-*.mjs și primește același `ctx` { request, env, uid, url, now, fs }.
import { failure, reply, FirestoreReader } from './site/shared.mjs';
import { cerc } from './site/sec-cerc.mjs';
import { somn, somnNight, somnChunk } from './site/sec-somn.mjs';
import { ratie, mars, ratiePhoto } from './site/sec-body.mjs';
import { muzica, paza, concentrare } from './site/sec-mind.mjs';
import { inventar } from './site/sec-inventar.mjs';
import { azi, cont } from './site/sec-azi-cont.mjs';

export { SITE_RULES, FirestoreReader, where, resetSiteCache, initials, simplifyPolyline, contractGate } from './site/shared.mjs';

const SECTIONS = ['azi', 'cerc', 'somn', 'ratie', 'mars', 'muzica', 'paza', 'inventar', 'concentrare', 'cont'];
const SITE_PATH = new RegExp('^/insights/api/(' + SECTIONS.join('|') + ')(?:/(.*))?$');
/** True for the 4.4 section routes this module answers (the older /insights/api/* routes stay in insights-ai.mjs). */
export function isSiteApi(path) { return SITE_PATH.test(path); }
/** Secțiunea → funcția ei (ctx → date JSON). Sub-rutele somnului (noaptea, bucata de sunet) sunt tratate înainte. */
const HANDLERS = { azi, cerc, somn, ratie, mars, muzica, paza, inventar, concentrare, cont };

// ─────────────────────────────── intrarea ───────────────────────────────
/**
 * Routes one verified request. `deps.fetcher` / `deps.now` exist for the tests (mocked Firestore, fixed clock).
 * Missing data is null/[]; only a Firestore that answers nothing at all is an error (503).
 */
export async function handleSiteApi(request, env, uid, deps = {}) {
  const url = new URL(request.url), m = SITE_PATH.exec(url.pathname);
  if (!m) return failure('Secțiune necunoscută.', 404);
  const [, section, rest = ''] = m;
  const parts = rest ? rest.split('/') : [];
  // Poza mesei (pachetul C): singura rută care primește și PUT / DELETE (de la telefon), tot pe uid-ul verificat.
  const photo = section === 'ratie' && parts.length === 2 && parts[0] === 'photo';
  if (request.method !== 'GET' && !(photo && ['PUT', 'DELETE'].includes(request.method))) return failure('Metodă nepermisă.', 405);
  const token = (request.headers.get('Authorization') || '').slice(7);
  const ctx = { request, env, uid, url, now: deps.now ?? Date.now(), fs: new FirestoreReader(uid, token, deps.fetcher || ((u, o) => fetch(u, o))) };
  try {
    let data;
    if (photo) return await ratiePhoto(ctx, parts[1]);
    if (section === 'somn' && parts.length === 1) return await somnNight(ctx, parts[0]);
    if (section === 'somn' && parts.length === 3 && parts[1] === 'chunk') return await somnChunk(ctx, parts[0], parts[2]);
    if (parts.length) return failure('Secțiune necunoscută.', 404);
    data = await HANDLERS[section](ctx);
    if (ctx.fs.unreachable && !ctx.stale) { console.log('site-api firestore unreachable', section, ctx.fs.codes.join(',')); const r = failure('Datele din FORJA nu răspund acum. Reîncearcă peste un minut.', 503); r.headers.set('x-forja-fs', ctx.fs.codes.join(',')); return r; }
    return reply(data);
  } catch {
    return failure('Datele nu sunt disponibile acum.', 500);
  }
}
