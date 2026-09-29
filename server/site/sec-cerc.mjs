// FORJA 4.4 — secțiunea „cerc”: Teren + Camarazi (prietenii, familia, fantoma, locurile recomandate, traseele).
// Mirror (pachetul A): de când e camaradul, km-ii săptămânii și ultima tură, locurile cucerite, familia în ambele sensuri,
// cine te vede acum (fantoma, locația în fundal, familia), punctul tău privat în fantomă, tura GO în desfășurare, energia
// primită și trimisă, agenda (fără nume din agendă, fără numere), locurile pe care le-ai recomandat și ziua pe hartă (24 h).
import { SITE_RULES, num, int, str, time, where, recall, remember, account, isGhost, nowPlaying, initials, position, byName, simplifyPolyline, contractGate } from './shared.mjs';
import { localDate } from '../site-time.mjs';

// Tot ce scrie aplicația în users/{uid} și văd prietenii în FriendsSheet: poziția, starea, muzica, km-ii săptămânii,
// ultima tură (GoTrackService la final) și teritoriul (ExploreTracker.publishCounts). Masca nu costă citiri în plus.
const FRIEND_FIELDS = ['name', 'lat', 'lng', 'locUpdatedAt', 'state', 'ghostUntil', 'nowPlaying', 'exploreCells', 'placesCount',
  'weekKm', 'lastActivityType', 'lastActivityKm', 'lastActivityDurS', 'lastActivityAt'];
const ME_FIELDS = [...FRIEND_FIELDS, 'inviteCode', 'familyUids', 'contract', 'speedMps'];
const UID = /^[A-Za-z0-9_-]{1,128}$/;
export const CERC_RULES = Object.freeze({ energy_query: 100, energy_kept: 30, agenda_max: 50, go_points: 500, go_stale_ms: 10 * 60000, mine_max: 100 });
async function friendUids(fs, uid) {
  const rows = await fs.query('', 'friendships', { filters: [where('members', 'ARRAY_CONTAINS', uid)], select: ['members', 'since'], limit: 200 });
  if (!rows) return null;
  const set = new Set(), since = {};
  for (const r of rows) for (const m of Array.isArray(r.members) ? r.members : []) if (typeof m === 'string' && m !== uid && UID.test(m)) {
    set.add(m);
    if (time(r.since) && (!since[m] || r.since < since[m])) since[m] = r.since;
  }
  const uids = [...set].slice(0, SITE_RULES.friends_max);
  return { uids, since: Object.fromEntries(uids.filter(u => since[u]).map(u => [u, since[u]])) };
}
/** Lista prietenilor (10 min în DO). `since` = de când e prietenia (friendships.since), pentru „camarad din martie”. */
export async function cachedFriends(env, fs, uid, now, cache) {
  const hit = cache?.friends;
  const since = hit?.since && typeof hit.since === 'object' ? hit.since : {};
  if (hit && now - hit.at <= SITE_RULES.friends_ms && Array.isArray(hit.uids)) return { uids: hit.uids, since, write: null, failed: false };
  const got = await friendUids(fs, uid);
  return { uids: got?.uids || (Array.isArray(hit?.uids) ? hit.uids : []), since: got?.since || since, write: got ? { at: now, uids: got.uids, since: got.since } : null, failed: !got };
}
function lastActivity(u) {
  const at = time(u?.lastActivityAt), type = str(u?.lastActivityType, 20);
  if (!at || !type) return null;
  return { type, km: num(u.lastActivityKm), durS: int(u.lastActivityDurS), at };
}
/** Tura GO în desfășurare (users/{uid}/live/go, scris la 30 s de GoTrackService, șters la final). Veche de 10 min: oprită. */
function liveGo(doc, now) {
  const at = time(doc?.updatedAt), startedAt = time(doc?.startedAt);
  if (!doc || !at || !startedAt || now - at > CERC_RULES.go_stale_ms) return null;
  return { sport: str(doc.sport, 20) || 'walk', startedAt, distanceM: num(doc.distanceM), at, polyline: simplifyPolyline(doc.polyline, CERC_RULES.go_points) };
}
async function cercLive(fs, uid, friends, now) {
  const [meDoc, docs, familyRows] = await Promise.all([
    fs.get(`users/${uid}`, ME_FIELDS),
    fs.batchGet(friends.map(f => `users/${f}`), FRIEND_FIELDS),
    fs.query('', 'familyLoc', { filters: [where('allowed', 'ARRAY_CONTAINS', uid)], select: ['lat', 'lng', 'locUpdatedAt', 'state'], limit: 60 }),
  ]);
  const hasMe = new Set((familyRows || []).map(r => r.id));
  const familyLoc = new Map((familyRows || []).filter(r => position(r)).map(r => [r.id, r]));
  const myFamily = new Set((Array.isArray(meDoc?.familyUids) ? meDoc.familyUids : []).filter(x => typeof x === 'string' && UID.test(x)));
  const signed = contractGate(meDoc?.contract, 3), ghostMe = !!meDoc && isGhost(meDoc, now);
  // Al doilea rând de citiri, doar când au sens: punctul trimis familiei (în fantomă e singura ta poziție) și tura GO.
  const [ownFamily, goDoc] = await Promise.all([
    meDoc && ghostMe ? fs.get(`familyLoc/${uid}`, ['lat', 'lng', 'locUpdatedAt']) : null,
    meDoc && signed && (ghostMe || ['run', 'walk', 'ride'].includes(meDoc.state)) ? fs.get(`users/${uid}/live/go`, ['sport', 'startedAt', 'distanceM', 'polyline', 'updatedAt']) : null,
  ]);
  const out = [], family = [];
  for (const f of friends) {
    const u = docs.get(`users/${f}`);
    if (!u) continue;
    const name = str(u.name, 60) || 'Prieten', ghost = isGhost(u, now), fam = familyLoc.get(f);
    let p = ghost ? null : position(u), at = p ? time(u.locUpdatedAt) : null, viaFamily = false;
    // He has you in his family: familyLoc is written in the background every 2 min, often fresher than his public pin.
    if (!ghost && fam && (time(fam.locUpdatedAt) || 0) > (at || 0)) { p = position(fam); at = time(fam.locUpdatedAt); viaFamily = true; }
    out.push({ uid: f, name, initials: initials(name), lat: p?.lat ?? null, lng: p?.lng ?? null, at, state: ghost ? 'ghost' : str(u.state, 20),
      ghost, viaFamily, nowPlaying: ghost ? null : nowPlaying(u.nowPlaying, now), exploreCells: int(u.exploreCells), placesCount: int(u.placesCount),
      weekKm: num(u.weekKm), last: lastActivity(u), inMyFamily: myFamily.has(f), hasMeInFamily: hasMe.has(f) });
    // Ghost for everyone else, visible to you as family (MapScreen.kt:138-147): the position comes only through familyLoc.
    if (ghost && fam) family.push({ uid: f, name, initials: initials(name), lat: fam.lat, lng: fam.lng, at: time(fam.locUpdatedAt) });
  }
  let me = null;
  if (meDoc) {
    const ghost = ghostMe, p = ghost ? null : position(meDoc), own = position(ownFamily);
    const names = new Map(out.map(f => [f.uid, f.name]));
    me = { lat: p?.lat ?? null, lng: p?.lng ?? null, at: p ? time(meDoc.locUpdatedAt) : null, ghost,
      ghostUntil: meDoc.ghostUntil === -1 || (num(meDoc.ghostUntil) !== null && meDoc.ghostUntil > now) ? meDoc.ghostUntil : null,
      state: ghost ? 'ghost' : str(meDoc.state, 20), nowPlaying: ghost ? null : nowPlaying(meDoc.nowPlaying, now), exploreCells: int(meDoc.exploreCells),
      placesCount: int(meDoc.placesCount), weekKm: num(meDoc.weekKm), last: lastActivity(meDoc), speedMps: ghost ? null : num(meDoc.speedMps),
      // Cine te vede și în fantomă: familia ta (users/{me}.familyUids), cu numele din lista prietenilor.
      family: [...myFamily].map(f => ({ uid: f, name: names.get(f) || 'Camarad' })).sort(byName),
      familyAt: time(ownFamily?.locUpdatedAt),
      // În fantomă, prietenii nu te văd, dar tu da: ultimul punct trimis familiei (familyLoc/{tu}, doar al tău și al familiei).
      private: ghost && own ? { lat: own.lat, lng: own.lng, at: time(ownFamily.locUpdatedAt), source: 'family' } : null,
      go: liveGo(goDoc, now) };
  }
  return { me, friends: out.sort(byName), family: family.sort(byName), inviteCode: str(meDoc?.inviteCode, 40), signed };
}
const ROUTE_FIELDS = ['type', 'startAt', 'distanceM', 'durationS', 'polyline'];
/**
 * "Străzile tale": the newest 30 activities that have a route, simplified once and kept in the DO. A run is written once and
 * never edited, so after the first build each refresh only asks for activities newer than the newest known one (1 read).
 * The first build goes back 5 activities at a time and stops at ~600 KB of polylines per request (the free Workers CPU budget);
 * the next polls continue it until 30 routes or the oldest activity.
 * Once a day after that, one id-only query over the range of the kept routes finds runs that reached Firestore late with an
 * older startAt (the phone's offline queue, a reinstall that re-uploads history) and reads their polylines, 5 per refresh.
 */
async function updateRoutes(fs, uid, prev, now) {
  const st = { routes: [...(prev?.routes || [])], none: [...(prev?.none || [])], newest: prev?.newest ?? null, oldest: prev?.oldest ?? null, done: prev?.done === true,
    checkedAt: num(prev?.checkedAt) };
  const built = st.done;
  let bytes = 0, failed = false;
  const take = rows => {
    for (const a of rows) {
      bytes += typeof a.polyline === 'string' ? a.polyline.length : 0;
      if (!time(a.startAt)) continue;
      st.newest = st.newest === null ? a.startAt : Math.max(st.newest, a.startAt);
      st.oldest = st.oldest === null ? a.startAt : Math.min(st.oldest, a.startAt);
      const polyline = simplifyPolyline(a.polyline, SITE_RULES.route_points);
      if (polyline && !st.routes.some(r => r.id === a.id)) st.routes.push({ id: a.id, type: str(a.type, 20), startAt: a.startAt, distanceM: num(a.distanceM), durationS: num(a.durationS), polyline });
      // Activities without a route are remembered too, so Marș never downloads them again.
      if (!polyline && !st.none.includes(a.id)) st.none = [...st.none, a.id].slice(-100);
    }
  };
  // Newer than what we have, oldest first, so a burst of new runs never leaves a gap.
  for (let page = 0; st.newest !== null && page < 4 && bytes < SITE_RULES.route_bytes; page++) {
    const rows = await fs.query(`users/${uid}`, 'activities', { filters: [where('startAt', 'GREATER_THAN', st.newest)], orderBy: 'startAt', direction: 'ASCENDING', limit: SITE_RULES.route_page, select: ROUTE_FIELDS });
    if (!rows) { failed = true; break; }
    take(rows);
    if (rows.length < SITE_RULES.route_page) break;
  }
  // Older ones, newest first, until 30 routes, the oldest activity, or the byte budget of this request.
  while (!failed && !st.done && st.routes.length < SITE_RULES.routes && bytes < SITE_RULES.route_bytes) {
    const rows = await fs.query(`users/${uid}`, 'activities', { filters: st.oldest === null ? [] : [where('startAt', 'LESS_THAN', st.oldest)], orderBy: 'startAt', limit: SITE_RULES.route_page, select: ROUTE_FIELDS });
    if (!rows) { failed = true; break; }
    take(rows);
    if (rows.length < SITE_RULES.route_page) st.done = true;
  }
  // Late arrivals: at most once a day, only on a finished build (a build in progress will page over them anyway).
  if (!failed && built && bytes < SITE_RULES.route_bytes && (st.checkedAt === null || now - st.checkedAt > SITE_RULES.routes_check_ms)) {
    const kept = [...st.routes].sort((a, b) => b.startAt - a.startAt);
    const cutoff = kept.length >= SITE_RULES.routes ? kept[SITE_RULES.routes - 1].startAt : null;
    const rows = await fs.query(`users/${uid}`, 'activities', { filters: cutoff === null ? [] : [where('startAt', 'GREATER_THAN_OR_EQUAL', cutoff)], orderBy: 'startAt', limit: 100, select: ['startAt'] });
    if (!rows) failed = true;
    else {
      const known = new Set([...st.routes.map(r => r.id), ...st.none]);
      const late = rows.filter(a => time(a.startAt) && !known.has(a.id)), batch = late.slice(0, SITE_RULES.route_page);
      const mark = fs.mark();
      const docs = await fs.batchGet(batch.map(a => `users/${uid}/activities/${a.id}`), ROUTE_FIELDS);
      if (fs.failedSince(mark)) failed = true;
      else {
        take(batch.map(a => docs.get(`users/${uid}/activities/${a.id}`)).filter(Boolean));
        if (late.length <= batch.length) st.checkedAt = now;
      }
    }
  }
  st.routes.sort((a, b) => b.startAt - a.startAt);
  st.routes = st.routes.slice(0, SITE_RULES.routes);
  if (st.routes.length >= SITE_RULES.routes) st.done = true;
  // A build that has just finished has seen every activity up to now.
  if (!built && st.done) st.checkedAt = now;
  return { ...st, failed };
}
async function recommendedPlaces(fs, uid) {
  const places = await fs.query('', 'places', { filters: [where('visibleTo', 'ARRAY_CONTAINS', uid)], select: ['ownerUid', 'ownerName', 'name', 'stars', 'note', 'lat', 'lng', 'visits', 'at'], limit: 100 });
  if (!places) return null;
  return places.filter(p => p.ownerUid !== uid && typeof p.ownerUid === 'string' && position(p)).sort((a, b) => (num(b.at) || 0) - (num(a.at) || 0))
    .map(p => ({ id: p.id, ownerUid: p.ownerUid, ownerName: str(p.ownerName, 60) || 'Un prieten', name: str(p.name, 80) || '', stars: int(p.stars) || 0, note: str(p.note, 300) || '', lat: p.lat, lng: p.lng, visits: int(p.visits) > 0 ? int(p.visits) : null }));
}
/** Locurile pe care LE-AI recomandat (places/{id}, ownerUid = tu): câți camarazi le văd. */
async function myRecommendations(fs, uid) {
  const rows = await fs.query('', 'places', { filters: [where('ownerUid', 'EQUAL', uid)], select: ['name', 'stars', 'lat', 'lng', 'visibleTo', 'at'], limit: CERC_RULES.mine_max });
  if (!rows) return null;
  return rows.filter(p => position(p)).sort((a, b) => (num(b.at) || 0) - (num(a.at) || 0))
    .map(p => ({ id: p.id, name: str(p.name, 80) || '', stars: int(p.stars) || 0, lat: p.lat, lng: p.lng, seenBy: Array.isArray(p.visibleTo) ? p.visibleTo.filter(x => x !== uid).length : 0, at: time(p.at) }));
}
/**
 * Energia (⚡): primită (to = tu) și trimisă (from = tu; regula din P0). Un index pe un singur câmp, deci fără orderBy:
 * sortarea se face aici. Păstrăm cele mai noi 30 din fiecare parte.
 */
async function energyOf(fs, uid) {
  const fields = ['to', 'from', 'fromName', 'day', 'at'];
  const [got, gave] = await Promise.all([
    fs.query('', 'energy', { filters: [where('to', 'EQUAL', uid)], select: fields, limit: CERC_RULES.energy_query }),
    fs.query('', 'energy', { filters: [where('from', 'EQUAL', uid)], select: fields, limit: CERC_RULES.energy_query }),
  ]);
  if (!got || !gave) return null;
  const clean = (rows, other) => rows.filter(r => time(r.at) && typeof r[other] === 'string' && UID.test(r[other]) && r[other] !== uid)
    .sort((a, b) => b.at - a.at).slice(0, CERC_RULES.energy_kept);
  return {
    received: clean(got, 'from').map(r => ({ uid: r.from, name: str(r.fromName, 60) || 'Un camarad', at: r.at, day: str(r.day, 10) })),
    sent: clean(gave, 'to').map(r => ({ uid: r.to, at: r.at, day: str(r.day, 10) })),
  };
}
/** settings/presence (doar tu): „Locație în fundal” pornită sau oprită, scrisă de telefon când se schimbă. */
async function presenceOf(fs, uid) {
  const d = await fs.get(`users/${uid}/settings/presence`, ['bgShare', 'updatedAt']);
  return d ? { bgShare: d.bgShare === true, at: time(d.updatedAt) } : null;
}
/** settings/contacts (doar tu, cu contractul): rezultatul ultimei comparări a agendei. Fără nume din agendă, fără numere. */
async function agendaOf(fs, uid) {
  const d = await fs.get(`users/${uid}/settings/contacts`, ['syncedAt', 'status', 'compared', 'found', 'mutual', 'matches']);
  if (!d) return null;
  const list = (Array.isArray(d.matches) ? d.matches : []).filter(m => m && typeof m.uid === 'string' && UID.test(m.uid) && m.uid !== uid).slice(0, CERC_RULES.agenda_max)
    .map(m => ({ uid: m.uid, name: str(m.forjaName, 60) || 'Camarad FORJA', mutual: m.mutual === true, verified: m.verified === true }));
  return { syncedAt: time(d.syncedAt), status: str(d.status, 40), compared: int(d.compared), found: int(d.found) ?? list.length, mutual: int(d.mutual) ?? list.filter(m => m.mutual).length, list };
}
/** Numele locurilor tale din harta FORJA (SocialGraph, „Și pe site”), ca opririle zilei să se numească „Acasă”, „Sala”. */
async function placeNames(env, uid) {
  if (!env.SOCIAL) return null;
  try {
    const res = await env.SOCIAL.get(env.SOCIAL.idFromName('friends-v1')).fetch(new Request('https://internal/v2/social/explore/place-names', { headers: { 'x-forja-owner': uid } }));
    const v = res.ok ? await res.json() : null;
    return Array.isArray(v?.places) ? v.places.slice(0, 1000) : null;
  } catch { return null; }
}
/** Energia cu numele camarazilor (trimisă) și numărătorile zilei și ale săptămânii. */
function energyView(e, friends, now) {
  if (!e) return null;
  const names = new Map(friends.map(f => [f.uid, f.name])), today = localDate(now), week = now - 7 * 86400000;
  const received = e.received || [], sent = (e.sent || []).map(r => ({ ...r, name: names.get(r.uid) || 'Un camarad' }));
  return { received, sent, today: received.filter(r => r.day === today).length, week: received.filter(r => r.at > week).length,
    sentToday: sent.filter(r => r.day === today).map(r => r.uid) };
}
export async function cerc(ctx) {
  const { env, fs, uid, now } = ctx;
  const key = uid + ':cerc', hit = recall(key, SITE_RULES.cerc_live_ms, now);
  if (hit) return hit;
  const cache = await account(env, uid, '/internal/site/cache?names=cerc-live,cerc-slow,friends');
  let live = cache?.['cerc-live'], slow = cache?.['cerc-slow'], since = cache?.friends?.since || {};
  const writes = {};
  let refreshed = false, liveFailed = false;
  if (!live || now - live.at > SITE_RULES.cerc_live_ms) {
    const mark = fs.mark();
    const friends = await cachedFriends(env, fs, uid, now, cache);
    if (friends.write) writes.friends = friends.write;
    since = friends.since || since;
    const fresh = await cercLive(fs, uid, friends.uids, now);
    // Any failed read (friend list, own doc, friends' batchGet, familyLoc) makes the refresh incomplete: get/batchGet answer
    // null for a document they could not read, so friends or family would silently vanish from the map.
    liveFailed = fs.failedSince(mark);
    // A failed refresh never overwrites what we had: an answer up to 10 min old (with its own updated_at) beats an empty map.
    // Older than that it is not served, so a friend who has since turned ghost cannot reappear from the cache.
    if (liveFailed && live && now - live.at <= SITE_RULES.stale_max_ms) ctx.stale = true;
    else {
      live = { at: now, me: fresh.me, friends: fresh.friends, family: fresh.family, inviteCode: fresh.inviteCode, signed: fresh.signed, day: live?.day ?? null };
      refreshed = true;
      // Nothing to fall back on: the partial answer is served once, but neither the DO nor the memory keeps it.
      if (liveFailed) ctx.partial = true;
    }
  }
  const due = !slow || now - slow.at > SITE_RULES.cerc_slow_ms, building = slow && !slow.done;
  if (due || building) {
    const recommended = due ? await recommendedPlaces(fs, uid) : slow.recommended;
    const routes = await updateRoutes(fs, uid, slow, now);
    // Restul stratului lent (10 min): energia, locurile tale recomandate, „Locație în fundal”, agenda, numele locurilor.
    let extra = null, extraFailed = false;
    if (due) {
      const mark = fs.mark();
      const [mine, energy, presence, agenda, names] = await Promise.all([myRecommendations(fs, uid), energyOf(fs, uid), presenceOf(fs, uid),
        live?.signed ? agendaOf(fs, uid) : null, placeNames(env, uid)]);
      extraFailed = fs.failedSince(mark);
      extra = { mine: mine ?? slow?.mine ?? [], energy: energy ?? slow?.energy ?? null, presence: extraFailed ? slow?.presence ?? presence : presence,
        agenda: extraFailed ? slow?.agenda ?? agenda : agenda, placeNames: names ?? slow?.placeNames ?? [] };
    }
    if (recommended === null && routes.failed && slow && now - slow.at <= SITE_RULES.stale_max_ms) ctx.stale = true;
    else {
      const { failed, ...kept } = routes;
      slow = { ...(slow || {}), at: due ? now : slow.at, recommended: recommended ?? slow?.recommended ?? [], ...kept, ...(extra || {}) };
      if (!failed && recommended !== null && !extraFailed) writes['cerc-slow'] = slow;
    }
  }
  // Ziua pe hartă (ultimele 24 h, din DO-ul contului, fără Firestore) se reface odată cu stratul live; cu contractul semnat.
  if (refreshed) {
    if (!live.signed) live.day = null;
    else {
      const got = await account(env, uid, '/internal/site/day', 'POST', { places: slow?.placeNames || [] });
      if (got) live.day = got.day ?? null;
    }
    if (!liveFailed) writes['cerc-live'] = live;
  }
  if (Object.keys(writes).length) await account(env, uid, '/internal/site/cache', 'POST', writes);
  const agenda = live.signed ? slow?.agenda ?? null : null;
  const fromAgenda = new Set((agenda?.list || []).filter(m => m.mutual).map(m => m.uid));
  const friendSet = new Set(live.friends.map(f => f.uid));
  const friends = live.friends.map(f => ({ ...f, since: time(since[f.uid]), fromAgenda: fromAgenda.has(f.uid) }));
  const me = live.me ? { ...live.me, bgShare: slow?.presence ? slow.presence.bgShare : null, bgShareAt: slow?.presence?.at ?? null } : null;
  const out = { me, friends, family: live.family, recommended: slow.recommended, routes: slow.routes, inviteCode: live.inviteCode,
    energy: energyView(slow?.energy, live.friends, now), agenda: agenda ? { ...agenda, list: agenda.list.map(m => ({ ...m, friend: friendSet.has(m.uid) })) } : null,
    mine: slow?.mine || [], day: live.day ?? null, updated_at: live.at };
  // Remembered from the data's own time: a copy taken from the DO at 19 s old leaves the memory 1 s later, not 20 s later.
  if (!fs.unreachable && !ctx.stale && !ctx.partial) remember(key, out, live.at);
  return out;
}
