// Stateful mock of every endpoint the 4.4 site calls, answered from a fixture profile (fixture.cjs), exactly as
// DESIGN-4.4.md §3.2 / §3.4 specify: Firebase auth (signInWithPassword, sendOobCode, securetoken), /insights/api/{azi,cerc,
// somn,somn/<id>,somn/<id>/chunk/<i>,ratie,mars,muzica,paza,inventar,cont,intake}, /v2/social/{state,explore/state,
// explore/places/<id>,contacts/discovery}, /v2/recovery/devices[/<id>[/command|/extend|/grant]], /v2/files[/<id>[/thumbnail]].
// One instance per page, so every captured view starts from the same data. Unknown routes answer 404 {"error":"UNMOCKED …"}
// and are listed in the run report. `fail` (array of path prefixes) makes those routes answer 500, for error states.

const NO_STORE = {'content-type': 'application/json', 'cache-control': 'no-store'};
const json = (data, status = 200) => ({status, headers: NO_STORE, body: JSON.stringify(data)});
const bytes = (body, contentType, status = 200) => ({status, headers: {'content-type': contentType, 'cache-control': 'no-store'}, body});
const fc = features => ({type: 'FeatureCollection', features});

function fakeJwt(now, uid) {
  const b64 = v => Buffer.from(JSON.stringify(v)).toString('base64url');
  return [b64({alg: 'RS256', kid: 'local-harness', typ: 'JWT'}), b64({iss: 'https://securetoken.google.com/forja-65093', aud: 'forja-65093', sub: uid, iat: Math.floor(now / 1000), exp: Math.floor(now / 1000) + 3600, email: 'lana@example.test'}), 'local-harness-signature'].join('.');
}

function createApi(fixture, assets, {fail = []} = {}) {
  const f = fixture, calls = [], unmocked = [];
  const uid = f.azi.me.uid;
  // Ceasul paginii pornește de la fixture.now și curge; serverul simulat folosește același ceas.
  const t0 = Date.now(), pageNow = () => f.now + (Date.now() - t0);
  const blob = key => {
    const b = f.blobs[key];
    if (!b) return null;
    if (b.scene) return bytes(assets.scene(b.scene), 'image/png');
    if (b.text !== undefined) return bytes(Buffer.from(b.text, 'utf8'), 'text/plain; charset=utf-8');
    if (b.pdf) return bytes(assets.pdf(b.pdf), 'application/pdf');
    if (b.audio) return bytes(assets.audio(), 'audio/mp4');
    return null;
  };
  const device = did => f.devices.find(d => d.id === did);
  const PAGE = 200;

  async function handle({method, url, body}) {
    const u = new URL(url), path = u.pathname, q = u.searchParams;
    let data = {};
    if (body && body.length) { try { data = JSON.parse(body.toString('utf8')); } catch { data = Object.fromEntries(new URLSearchParams(body.toString('utf8'))); } }
    calls.push({method, path: path + (u.search || '')});
    if (fail.some(p => path.startsWith(p))) return json({error: 'Serverul nu răspunde acum.'}, 500);

    // ——— Firebase Auth ———
    if (u.hostname === 'identitytoolkit.googleapis.com') {
      if (path.endsWith(':sendOobCode')) return json({kind: 'identitytoolkit#GetOobConfirmationCodeResponse', email: data.email});
      if (path.endsWith(':signInWithPassword')) {
        if (data.password === 'gresit') return json({error: {code: 400, message: 'INVALID_LOGIN_CREDENTIALS'}}, 400);
        return json({kind: 'identitytoolkit#VerifyPasswordResponse', localId: uid, email: data.email || 'lana@example.test', displayName: '', idToken: fakeJwt(f.now, uid), registered: true, refreshToken: 'local-harness-refresh', expiresIn: '3600'});
      }
    }
    if (u.hostname === 'securetoken.googleapis.com') {
      if (data.refresh_token === 'revoked-refresh') return json({error: {code: 400, message: 'TOKEN_EXPIRED'}}, 400);
      return json({id_token: fakeJwt(f.now, uid), refresh_token: 'local-harness-refresh', expires_in: '3600', token_type: 'Bearer', user_id: uid});
    }

    // ——— §3.2 site read API ———
    if (method === 'GET') {
      if (path === '/insights/api/azi') return json(f.azi);
      if (path === '/insights/api/cerc') return json(f.cerc);
      if (path === '/insights/api/somn') { const days = Number(q.get('days') || 14), from = f.now - days * 86400000; return json({nights: f.somn.nights.filter(n => n.startAt >= from - 86400000)}); }
      let m = /^\/insights\/api\/somn\/([A-Za-z0-9_-]+)$/.exec(path);
      if (m) return f.somnDetail[m[1]] ? json(f.somnDetail[m[1]]) : json({error: 'Înregistrarea nu mai există.'}, 404);
      m = /^\/insights\/api\/somn\/([A-Za-z0-9_-]+)\/chunk\/(\d+)$/.exec(path);
      if (m) return blob('chunk:' + m[1] + ':' + m[2]) || json({error: 'Înregistrarea a expirat.'}, 404);
      if (path === '/insights/api/ratie') return json(f.ratie);
      if (path === '/insights/api/mars') return json(f.mars);
      if (path === '/insights/api/muzica') return json(f.muzica);
      if (path === '/insights/api/paza') return json(f.paza);
      if (path === '/insights/api/inventar') return json(f.inventar);
      if (path === '/insights/api/cont') return json(f.cont);
    }
    if (path === '/insights/api/intake') {
      if (method === 'POST') { if (data.revision !== undefined && data.revision !== f.intake.revision) return json({error: 'Setarea s-a schimbat. Reîncearcă.'}, 409); f.intake = {accepting: !!data.accepting, revision: f.intake.revision + 1}; f.cont.intake = {paused: !f.intake.accepting}; }
      return json(f.intake);
    }

    // ——— Explorarea (SocialGraph, existentă) ———
    if (path === '/v2/social/explore/state' && method === 'GET') {
      const since = Number(q.get('since') || 0);
      if (since && since === f.explore.updated_at) return json({unchanged: true, updated_at: f.explore.updated_at});
      const all = f.explore.features, cursor = q.get('cursor');
      const start = cursor ? all.findIndex(c => c.properties.id === cursor) + 1 : 0;
      const page = all.slice(start, start + PAGE);
      return json({owner: f.explore.owner, grid_m: f.explore.grid_m, cells: fc(page), places: f.explore.places, next_cursor: start + PAGE < all.length ? page.at(-1).properties.id : null, updated_at: f.explore.updated_at});
    }
    let m = /^\/v2\/social\/explore\/places\/([A-Za-z0-9_-]+)$/.exec(path);
    if (m && method === 'PATCH') {
      const p = f.explore.places.find(x => x.id === m[1]); if (!p) return json({error: 'Loc indisponibil.'}, 404);
      for (const k of ['name', 'stars', 'note']) if (data[k] !== undefined) p[k] = data[k];
      p.updated_at = pageNow(); f.explore.updated_at = pageNow();
      return json(p);
    }
    if (path === '/v2/social/state' && method === 'GET') return json({me: {id: uid, name: 'Prieten FORJA', discoverable: f.discovery.discoverable, discovery_until: f.discovery.discovery_until}, friends: [], incoming: [], outgoing: 0, groups: []});
    if (path === '/v2/social/contacts/discovery') {
      if (method === 'DELETE') { f.discovery = {discoverable: false, discovery_until: null}; return json({ok: true}); }
      if (method === 'POST') { if (data.consent !== true) return json({error: 'Confirmă că poți fi găsit după numărul tău.'}, 403); f.discovery = {discoverable: true, discovery_until: f.now + 30 * 86400000}; return json({ok: true, until: f.discovery.discovery_until, verified: true}); }
    }

    // ——— §3.4 Găsire (site, proprietar) ———
    if (path === '/v2/recovery/devices' && method === 'GET') return json({devices: f.devices, retention_hours: 24, last_retention_days: 7});
    m = /^\/v2\/recovery\/devices\/([0-9a-f-]+)(?:\/(command|extend|grant))?$/.exec(path);
    if (m) {
      const d = device(m[1]); if (!d) return json({error: 'Telefon sau acțiune invalidă.'}, 404);
      const now = pageNow();
      if (m[2] === 'command' && method === 'POST') {
        if (d.command) return json({error: 'Oprește întâi căutarea activă.'}, 409);
        const ring = data.kind === 'ring';
        d.command = {id: data.id, kind: ring ? 'ring' : 'locate', created_at: now, start_before: now + 30 * 60000, until: now + (ring ? (data.seconds || 60) * 1000 : (data.minutes || 10) * 60000), phase: 'active', minutes: ring ? null : data.minutes || 10, seconds: ring ? data.seconds || 60 : null};
        d.status = ring ? 'ringing' : 'locating';
        if (!ring) d.position = {...d.last, at: now - 8000, fresh: true, battery: d.battery};
        return json({command: d.command, phone_online: d.online}, 202);
      }
      if (m[2] === 'command' && method === 'DELETE') { if (!d.command || d.command.id !== q.get('id')) return json({error: 'Cererea nu mai este activă.'}, 409); d.command = null; d.status = 'stopped'; return json({ok: true}); }
      if (m[2] === 'extend' && method === 'POST') { if (!d.command || d.command.id !== data.command) return json({error: 'Cererea nu mai este activă.'}, 409); d.command.until = Math.min(d.command.created_at + 60 * 60000, d.command.until + (data.minutes || 10) * 60000); return json({command: d.command}); }
      if (m[2] === 'grant' && method === 'DELETE') { f.devices = f.devices.filter(x => x !== d); return json({ok: true}); }
      if (!m[2] && method === 'PATCH') { if (typeof data.name !== 'string' || !data.name.trim() || data.name.length > 40) return json({error: 'Nume invalid.'}, 400); d.name = data.name.trim(); return json({ok: true, name: d.name}); }
    }

    // ——— Copiile din galerie (/v2/files, existent) ———
    if (path === '/v2/files' && method === 'GET') {
      const kind = q.get('kind') || 'all', list = f.files.filter(i => kind === 'all' || i.kind === kind);
      return json({server_at: pageNow(), items: list, next_cursor: null, total: list.length, bytes: list.reduce((a, i) => a + i.bytes, 0)});
    }
    m = /^\/v2\/files\/([0-9a-f-]+)(\/thumbnail)?$/.exec(path);
    if (m) {
      const item = f.files.find(i => i.id === m[1]); if (!item) return json({error: 'Copia nu mai este disponibilă.'}, 404);
      if (method === 'DELETE') { f.files = f.files.filter(i => i !== item); return json({deleted: true}); }
      if (method === 'GET') return blob('file:' + item.id) || json({error: 'Fișier indisponibil.'}, 404);
    }

    unmocked.push(method + ' ' + path);
    return json({error: 'UNMOCKED ' + method + ' ' + path}, 404);
  }
  return {handle, calls, unmocked};
}

module.exports = {createApi, fakeJwt};
