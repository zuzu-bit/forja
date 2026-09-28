// Verificarea regulilor Firestore pe emulator: fiecare drum de citire/scriere al aplicației, cu regulile noi
// (firestore.rules) și, pentru compatibilitate, cu cele vechi (OLD_RULES). Pașii de rulare: FIRESTORE-RULES.md.
//
//   RULES=firestore.rules OLD_RULES=firestore.rules.old \
//     npx firebase emulators:exec --only firestore --project demo-forja "node firestore.rules.test.mjs"
//
// Nu face parte din `npm test` (are nevoie de emulatorul Firestore, adică de Java și firebase-tools).
import { readFileSync, existsSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import {
  doc, getDoc, setDoc, updateDoc, deleteDoc, deleteField, collection, query, where, getDocs, arrayUnion,
} from 'firebase/firestore';

const NEW = process.env.RULES || new URL('./firestore.rules', import.meta.url).pathname;
const OLD = process.env.OLD_RULES || '';
const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8080').split(':');

// uid-uri cu majuscule și cifre, ca cele din Firebase Auth: „Zeta…” < „alpha…” în ambele ordonări (Kotlin și reguli).
const ANA = 'alpha1Ana';        // proprietarul
const BOGDAN = 'Zeta9Bogdan';   // prieten (prietenie creată de aplicație, id sortat)
const CRISTI = 'mCristi33';     // străin, apoi prieten prin cod
const DANA = 'dDana44';         // în familia Anei (users/{ana}.familyUids), fără prietenie
const DAY = '2026-09-28';

const sortedId = (a, b) => [a, b].sort().join('_');

let failures = 0;
async function check(label, fn) {
  try { await fn(); console.log('  ok   ' + label); }
  catch (e) { failures++; console.log('  FAIL ' + label + '\n       ' + (e?.message || e)); }
}

// Site-ul (worker-ul forja-insights) citește prin REST cu tokenul utilizatorului: documents:batchGet.
// Emulatorul acceptă un JWT nesemnat (alg „none”), ca în @firebase/rules-unit-testing.
function mockToken(uid) {
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  return b64({ alg: 'none', typ: 'JWT' }) + '.' + b64({
    iss: 'https://securetoken.google.com/demo-forja', aud: 'demo-forja', iat: now, exp: now + 3600, auth_time: now,
    sub: uid, user_id: uid, firebase: { sign_in_provider: 'password', identities: {} },
  }) + '.';
}
async function batchGet(uid, paths) {
  const root = 'projects/demo-forja/databases/(default)/documents';
  const r = await fetch(`http://${host}:${port}/v1/${root}:batchGet`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: 'Bearer ' + mockToken(uid) },
    body: JSON.stringify({ documents: paths.map((x) => `${root}/${x}`) }),
  });
  return r.status;
}
async function expectStatus(promise, want) {
  const got = await promise;
  if (got !== want) throw new Error(`HTTP ${got}, așteptat ${want}`);
}

async function seed(env) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'users', ANA), {
      name: 'Ana', inviteCode: 'ANA123', state: 'walk', lat: 44.43, lng: 26.1, locUpdatedAt: 1,
      ghostUntil: 0, familyUids: [DANA], email: 'ana@example.com',
      nowPlaying: { title: 'Cântec', artist: 'Artist', app: 'Spotify', at: 1 },
    });
    await setDoc(doc(db, 'users', BOGDAN), { name: 'Bogdan', state: 'idle', familyUids: [] });
    await setDoc(doc(db, 'users', CRISTI), { name: 'Cristi', state: 'idle' });
    await setDoc(doc(db, 'users', DANA), { name: 'Dana', state: 'idle' });
    await setDoc(doc(db, 'inviteCodes', 'ANA123'), { uid: ANA });
    await setDoc(doc(db, 'friendships', sortedId(ANA, BOGDAN)), { members: [ANA, BOGDAN].sort(), since: 1 });
    await setDoc(doc(db, 'familyLoc', ANA), { lat: 44.43, lng: 26.1, speedMps: 0, state: 'idle', locUpdatedAt: 1, allowed: [DANA] });
    await setDoc(doc(db, 'users', ANA, 'meals', 'm1'), { name: 'Supă', kcal: 300 });
  });
}

async function suite(name, rules, { tightened }) {
  console.log(`\n${name}`);
  const env = await initializeTestEnvironment({
    projectId: 'demo-forja',
    firestore: { rules, host, port: Number(port) },
  });
  await env.clearFirestore();
  await seed(env);
  const as = (uid) => env.authenticatedContext(uid).firestore();
  const anon = env.unauthenticatedContext().firestore();
  const ana = as(ANA), bogdan = as(BOGDAN), cristi = as(CRISTI), dana = as(DANA);
  const denyWhenTight = (p) => (tightened ? assertFails(p) : assertSucceeds(p));
  const allowWhenTight = (p) => (tightened ? assertSucceeds(p) : assertFails(p));

  // ── profilul propriu (AuthRepository, MapScreen, ContractScreen, PresenceRepository) ──
  await check('eu îmi citesc profilul', () => assertSucceeds(getDoc(doc(ana, 'users', ANA))));
  await check('eu îmi citesc profilul inexistent (cont nou)', () => assertSucceeds(getDoc(doc(as('newUser1'), 'users', 'newUser1'))));
  await check('eu îmi scriu poziția (merge)', () => assertSucceeds(setDoc(doc(ana, 'users', ANA), { lat: 44.44, locUpdatedAt: 2 }, { merge: true })));
  await check('eu îmi mut emailul (settings/account + ștergerea câmpului)', async () => {
    await assertSucceeds(setDoc(doc(ana, 'users', ANA, 'settings', 'account'), { email: 'ana@example.com' }, { merge: true }));
    await assertSucceeds(updateDoc(doc(ana, 'users', ANA), { email: deleteField() }));
  });
  await check('eu îmi pun familia (arrayUnion)', () => assertSucceeds(setDoc(doc(ana, 'users', ANA), { familyUids: arrayUnion(DANA) }, { merge: true })));
  await check('nu scriu profilul altcuiva', () => assertFails(setDoc(doc(cristi, 'users', ANA), { name: 'x' }, { merge: true })));
  await check('nu șterg profilul', () => assertFails(deleteDoc(doc(ana, 'users', ANA))));

  // ── date pentru site, doar ale mele (SiteMirror, CloudSync) ──
  await check('eu scriu users/{me}/workouts/w1', () => assertSucceeds(setDoc(doc(ana, 'users', ANA, 'workouts', 'w1'),
    { startAt: 1, endAt: 2, durationS: 1, title: 'Piept & Spate', kind: 'forta', sets: 9, volumeKg: 625, kcal: null, source: 'instructie' }, { merge: true })));
  await check('eu scriu users/{me}/settings/targets', () => assertSucceeds(setDoc(doc(ana, 'users', ANA, 'settings', 'targets'),
    { kcal: 2000, protein: 125, carbs: 225, fat: 66, updatedAt: 3 }, { merge: true })));
  await check('eu îmi citesc jurnalele (site-ul, cu tokenul meu)', () => assertSucceeds(getDocs(collection(ana, 'users', ANA, 'meals'))));
  await check('prietenul NU îmi citește contul (email)', () => assertFails(getDoc(doc(bogdan, 'users', ANA, 'settings', 'account'))));
  await check('prietenul NU îmi citește antrenamentele', () => assertFails(getDoc(doc(bogdan, 'users', ANA, 'workouts', 'w1'))));

  // ── prieteni (FriendsRepository.friendsFlow) ──
  await check('prietenul îmi citește profilul', () => assertSucceeds(getDoc(doc(bogdan, 'users', ANA))));
  await check('eu citesc profilul prietenului', () => assertSucceeds(getDoc(doc(ana, 'users', BOGDAN))));
  await check('prieteniile mele (members array-contains me)', () =>
    assertSucceeds(getDocs(query(collection(ana, 'friendships'), where('members', 'array-contains', ANA)))));
  await check('străinul NU îmi citește profilul', () => denyWhenTight(getDoc(doc(cristi, 'users', ANA))));
  await check('fără cont NU se citește nimic', () => assertFails(getDoc(doc(anon, 'users', ANA))));
  await check('nimeni nu listează toți utilizatorii', () => denyWhenTight(getDocs(collection(cristi, 'users'))));

  // ── familia (users/{me}.familyUids + familyLoc) ──
  await check('familia îmi citește profilul și fără prietenie', () => assertSucceeds(getDoc(doc(dana, 'users', ANA))));
  await check('familia îmi citește familyLoc (allowed array-contains)', () =>
    assertSucceeds(getDocs(query(collection(dana, 'familyLoc'), where('allowed', 'array-contains', DANA)))));
  await check('străinul NU citește familyLoc', () => assertFails(getDoc(doc(cristi, 'familyLoc', ANA))));
  await check('eu îmi scriu familyLoc', () => assertSucceeds(setDoc(doc(ana, 'familyLoc', ANA),
    { lat: 1, lng: 2, speedMps: 0, state: 'idle', locUpdatedAt: 4, allowed: [DANA] })));

  // ── prieten nou prin cod (FriendsRepository.addFriendByCode): cod → prietenie → numele ──
  await check('codul se citește de oricine conectat', () => assertSucceeds(getDoc(doc(cristi, 'inviteCodes', 'ANA123'))));
  await check('codul NU se citește fără cont', () => assertFails(getDoc(doc(anon, 'inviteCodes', 'ANA123'))));
  await check('eu îmi scriu codul', () => assertSucceeds(setDoc(doc(cristi, 'inviteCodes', 'CRI333'), { uid: CRISTI })));
  await check('nu scriu codul altcuiva', () => assertFails(setDoc(doc(cristi, 'inviteCodes', 'FALS00'), { uid: ANA })));
  await check('prietenia prin cod se creează', () => assertSucceeds(setDoc(doc(cristi, 'friendships', sortedId(CRISTI, ANA)),
    { members: [CRISTI, ANA].sort(), since: 5 })));
  await check('apoi numele prietenului nou se citește', () => assertSucceeds(getDoc(doc(cristi, 'users', ANA))));
  await check('a doua oară: „sunteți deja prieteni” (update refuzat)', () => assertFails(setDoc(doc(cristi, 'friendships', sortedId(CRISTI, ANA)),
    { members: [CRISTI, ANA].sort(), since: 6 })));
  await check('prietenie cu altcineva decât mine: refuzată', () => assertFails(setDoc(doc(cristi, 'friendships', sortedId(ANA, DANA)),
    { members: [ANA, DANA].sort(), since: 7 })));

  // ── prietenie din agendă (ContactsSync.addFriendDirect) cu id în ordinea inversă (date vechi/altă sortare) ──
  await check('prietenie cu id invers dă și ea acces', async () => {
    const [x, y] = [DANA, BOGDAN].sort();
    await assertSucceeds(setDoc(doc(dana, 'friendships', y + '_' + x), { members: [x, y], since: 8 }));
    await assertSucceeds(getDoc(doc(dana, 'users', BOGDAN)));
  });

  // ── după ștergerea prieteniei, profilul se închide ──
  await check('fost prieten NU mai citește profilul', async () => {
    await assertSucceeds(deleteDoc(doc(cristi, 'friendships', sortedId(CRISTI, ANA))));
    await denyWhenTight(getDoc(doc(cristi, 'users', ANA)));
  });

  // ── locuri recomandate (FriendsRepository.recommendPlace / recommendedPlacesFlow) ──
  const place = { ownerUid: ANA, ownerName: 'Ana', lat: 1, lng: 2, name: 'Parcul IOR', stars: 4, note: '', at: 9, visits: 3, visibleTo: [BOGDAN] };
  await check('recomand un loc', () => assertSucceeds(setDoc(doc(ana, 'places', 'pl1'), place)));
  await check('îl recomand din nou (update complet)', () => assertSucceeds(setDoc(doc(ana, 'places', 'pl1'), { ...place, stars: 5, at: 10 })));
  await check('prietenul îl vede (visibleTo array-contains)', () =>
    assertSucceeds(getDocs(query(collection(bogdan, 'places'), where('visibleTo', 'array-contains', BOGDAN)))));
  await check('străinul NU îl vede', () => assertFails(getDoc(doc(cristi, 'places', 'pl1'))));
  await check('îl șterg', () => assertSucceeds(deleteDoc(doc(ana, 'places', 'pl1'))));
  await check('nu-l dau altcuiva (ownerUid schimbat)', async () => {
    await assertSucceeds(setDoc(doc(ana, 'places', 'pl2'), place));
    await denyWhenTight(setDoc(doc(ana, 'places', 'pl2'), { ...place, ownerUid: BOGDAN }));
  });
  await check('nu pun 9 stele la actualizare', () => denyWhenTight(setDoc(doc(ana, 'places', 'pl3'), place).then(() =>
    setDoc(doc(ana, 'places', 'pl3'), { ...place, stars: 9 }))));

  // ── energie (FriendsRepository.sendEnergy / energyFlow, MapScreen) ──
  const eid = `${BOGDAN}_${DAY}_${ANA}`;
  // Regulile vechi refuzau citirea documentului lipsă (resource == null): trimiterea eșua mereu („I-ai trimis deja”).
  await check('expeditorul verifică dacă a trimis azi (document lipsă)', () => allowWhenTight(getDoc(doc(ana, 'energy', eid))));
  await check('expeditorul trimite energia', () => assertSucceeds(setDoc(doc(ana, 'energy', eid), { to: BOGDAN, from: ANA, fromName: 'Ana', day: DAY, at: 11 })));
  await check('a doua oară azi: refuzat (update)', () => assertFails(setDoc(doc(ana, 'energy', eid), { to: BOGDAN, from: ANA, fromName: 'Ana', day: DAY, at: 12 })));
  await check('destinatarul o primește (to == me, day)', () =>
    assertSucceeds(getDocs(query(collection(bogdan, 'energy'), where('to', '==', BOGDAN), where('day', '==', DAY)))));
  await check('destinatarul vede de la cine (to == me, from == el)', () =>
    assertSucceeds(getDocs(query(collection(bogdan, 'energy'), where('to', '==', BOGDAN), where('from', '==', ANA)))));
  await check('străinul NU o citește', () => assertFails(getDoc(doc(cristi, 'energy', eid))));
  await check('străinul NU ghicește energia altora (document lipsă)', () => assertFails(getDoc(doc(cristi, 'energy', `${ANA}_${DAY}_${BOGDAN}`))));
  await check('nu trimit în numele altcuiva', () => assertFails(setDoc(doc(cristi, 'energy', `${ANA}_${DAY}_${BOGDAN}`), { to: ANA, from: BOGDAN, day: DAY, at: 13 })));

  // ── site-ul: batchGet cu tokenul meu (P1, secțiunile Teren/Camarazi) ──
  if (tightened) {
    const many = Array.from({ length: 21 }, (_, i) => `f${String(i).padStart(2, '0')}Prieten`);
    await env.withSecurityRulesDisabled(async (ctx) => {
      const db = ctx.firestore();
      for (const f of many) {
        await setDoc(doc(db, 'users', f), { name: f, lat: 1, lng: 2 });
        await setDoc(doc(db, 'friendships', sortedId(ANA, f)), { members: [ANA, f].sort(), since: 1 });
      }
    });
    await check('site: eu + prietenii într-un batchGet (≤ 20 de prieteni)', () =>
      expectStatus(batchGet(ANA, [`users/${ANA}`, ...many.slice(0, 20).map((f) => `users/${f}`)]), 200));
    await check('site: 21 de prieteni într-un batchGet → refuzat (limita de 20 de verificări); se citesc pe loturi', () =>
      expectStatus(batchGet(ANA, many.map((f) => `users/${f}`)), 403));
    await check('site: un străin în lot strică tot lotul → doar prieteni în batchGet', () =>
      expectStatus(batchGet(ANA, [`users/${BOGDAN}`, `users/${CRISTI}`]), 403));
  }

  await env.cleanup();
}

await suite('Reguli noi (firestore.rules)', readFileSync(NEW, 'utf8'), { tightened: true });
if (OLD && existsSync(OLD)) await suite('Reguli vechi (compatibilitate)', readFileSync(OLD, 'utf8'), { tightened: false });

console.log(failures ? `\n${failures} verificări au picat` : '\nToate verificările au trecut');
process.exit(failures ? 1 : 0);
