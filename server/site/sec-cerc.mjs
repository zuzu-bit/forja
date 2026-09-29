// FORJA 4.4 — secțiunea „cerc”: Teren + Camarazi (prietenii, familia, fantoma, locurile recomandate, traseele).
import { SITE_RULES, num, int, str, time, where, recall, remember, account, isGhost, nowPlaying, initials, position, byName, simplifyPolyline } from './shared.mjs';

const FRIEND_FIELDS = ['name', 'lat', 'lng', 'locUpdatedAt', 'state', 'ghostUntil', 'nowPlaying', 'exploreCells'];
async function friendUids(fs, uid) {
  const rows = await fs.query('', 'friendships', { filters: [where('members', 'ARRAY_CONTAINS', uid)], select: ['members'], limit: 200 });
  if (!rows) return null;
  const set = new Set();
  for (const r of rows) for (const m of Array.isArray(r.members) ? r.members : []) if (typeof m === 'string' && m !== uid && /^[A-Za-z0-9_-]{1,128}$/.test(m)) set.add(m);
  return [...set].slice(0, SITE_RULES.friends_max);
}
export async function cachedFriends(env, fs, uid, now, cache) {
  const hit = cache?.friends;
  if (hit && now - hit.at <= SITE_RULES.friends_ms && Array.isArray(hit.uids)) return { uids: hit.uids, write: null, failed: false };
  const uids = await friendUids(fs, uid);
  return { uids: uids || (Array.isArray(hit?.uids) ? hit.uids : []), write: uids ? { at: now, uids } : null, failed: !uids };
}
async function cercLive(fs, uid, friends, now) {
  const [meDoc, docs, familyRows] = await Promise.all([
    fs.get(`users/${uid}`, [...FRIEND_FIELDS, 'inviteCode']),
    fs.batchGet(friends.map(f => `users/${f}`), FRIEND_FIELDS),
    fs.query('', 'familyLoc', { filters: [where('allowed', 'ARRAY_CONTAINS', uid)], select: ['lat', 'lng', 'locUpdatedAt', 'state'], limit: 60 }),
  ]);
  const familyLoc = new Map((familyRows || []).filter(r => position(r)).map(r => [r.id, r]));
  let me = null;
  if (meDoc) {
    const ghost = isGhost(meDoc, now), p = ghost ? null : position(meDoc);
    me = { lat: p?.lat ?? null, lng: p?.lng ?? null, at: p ? time(meDoc.locUpdatedAt) : null, ghost,
      ghostUntil: meDoc.ghostUntil === -1 || (num(meDoc.ghostUntil) !== null && meDoc.ghostUntil > now) ? meDoc.ghostUntil : null,
      state: ghost ? 'ghost' : str(meDoc.state, 20), nowPlaying: ghost ? null : nowPlaying(meDoc.nowPlaying, now), exploreCells: int(meDoc.exploreCells) };
  }
  const out = [], family = [];
  for (const f of friends) {
    const u = docs.get(`users/${f}`);
    if (!u) continue;
    const name = str(u.name, 60) || 'Prieten', ghost = isGhost(u, now), fam = familyLoc.get(f);
    let p = ghost ? null : position(u), at = p ? time(u.locUpdatedAt) : null;
    // He has you in his family: familyLoc is written in the background every 2 min, often fresher than his public pin.
    if (!ghost && fam && (time(fam.locUpdatedAt) || 0) > (at || 0)) { p = position(fam); at = time(fam.locUpdatedAt); }
    out.push({ uid: f, name, initials: initials(name), lat: p?.lat ?? null, lng: p?.lng ?? null, at, state: ghost ? 'ghost' : str(u.state, 20),
      ghost, viaFamily: false, nowPlaying: ghost ? null : nowPlaying(u.nowPlaying, now), exploreCells: int(u.exploreCells) });
    // Ghost for everyone else, visible to you as family (MapScreen.kt:138-147): the position comes only through familyLoc.
    if (ghost && fam) family.push({ uid: f, name, initials: initials(name), lat: fam.lat, lng: fam.lng, at: time(fam.locUpdatedAt) });
  }
  return { me, friends: out.sort(byName), family: family.sort(byName), inviteCode: str(meDoc?.inviteCode, 40) };
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
export async function cerc(ctx) {
  const { env, fs, uid, now } = ctx;
  const key = uid + ':cerc', hit = recall(key, SITE_RULES.cerc_live_ms, now);
  if (hit) return hit;
  const cache = await account(env, uid, '/internal/site/cache?names=cerc-live,cerc-slow,friends');
  let live = cache?.['cerc-live'], slow = cache?.['cerc-slow'];
  const writes = {};
  if (!live || now - live.at > SITE_RULES.cerc_live_ms) {
    const mark = fs.mark();
    const friends = await cachedFriends(env, fs, uid, now, cache);
    if (friends.write) writes.friends = friends.write;
    const fresh = await cercLive(fs, uid, friends.uids, now);
    // Any failed read (friend list, own doc, friends' batchGet, familyLoc) makes the refresh incomplete: get/batchGet answer
    // null for a document they could not read, so friends or family would silently vanish from the map.
    const failed = fs.failedSince(mark);
    // A failed refresh never overwrites what we had: an answer up to 10 min old (with its own updated_at) beats an empty map.
    // Older than that it is not served, so a friend who has since turned ghost cannot reappear from the cache.
    if (failed && live && now - live.at <= SITE_RULES.stale_max_ms) ctx.stale = true;
    else {
      live = { at: now, me: fresh.me, friends: fresh.friends, family: fresh.family, inviteCode: fresh.inviteCode };
      // Nothing to fall back on: the partial answer is served once, but neither the DO nor the memory keeps it.
      if (failed) ctx.partial = true; else writes['cerc-live'] = live;
    }
  }
  const due = !slow || now - slow.at > SITE_RULES.cerc_slow_ms, building = slow && !slow.done;
  if (due || building) {
    const recommended = due ? await recommendedPlaces(fs, uid) : slow.recommended;
    const routes = await updateRoutes(fs, uid, slow, now);
    if (recommended === null && routes.failed && slow && now - slow.at <= SITE_RULES.stale_max_ms) ctx.stale = true;
    else {
      const { failed, ...kept } = routes;
      slow = { at: due ? now : slow.at, recommended: recommended ?? slow?.recommended ?? [], ...kept };
      if (!failed && recommended !== null) writes['cerc-slow'] = slow;
    }
  }
  if (Object.keys(writes).length) await account(env, uid, '/internal/site/cache', 'POST', writes);
  const out = { me: live.me, friends: live.friends, family: live.family, recommended: slow.recommended, routes: slow.routes, inviteCode: live.inviteCode, updated_at: live.at };
  // Remembered from the data's own time: a copy taken from the DO at 19 s old leaves the memory 1 s later, not 20 s later.
  if (!fs.unreachable && !ctx.stale && !ctx.partial) remember(key, out, live.at);
  return out;
}
