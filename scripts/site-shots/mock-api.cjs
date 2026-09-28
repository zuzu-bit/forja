// Stateful mock of every endpoint THE SITE's client calls (Firebase auth, /insights/api/*, /v2/*), answered from a
// fixture profile (fixture.cjs). Used by shots.cjs through Playwright's page.route, one instance per page, so every
// captured view starts from the same data. Unknown routes answer 404 {"error":"UNMOCKED …"} and are listed in the run
// report, so a redesign that calls a new endpoint shows up immediately.

const json = (data, status = 200) => ({status, headers: {'content-type': 'application/json'}, body: JSON.stringify(data)});
const bytes = (body, contentType, status = 200) => ({status, headers: {'content-type': contentType}, body});
const fc = features => ({type: 'FeatureCollection', features});

function fakeJwt(now) {
  const b64 = v => Buffer.from(JSON.stringify(v)).toString('base64url');
  return [b64({alg: 'RS256', kid: 'local-harness', typ: 'JWT'}), b64({iss: 'https://securetoken.google.com/forja-65093', aud: 'forja-65093', sub: 'demo-owner', iat: Math.floor(now / 1000), exp: Math.floor(now / 1000) + 3600, email: 'lana@example.test'}), 'local-harness-signature'].join('.');
}

function createApi(fixture, assets) {
  const f = fixture, calls = [], unmocked = [];
  const blob = key => {
    const b = f.blobs[key];
    if (!b) return null;
    if (b.scene) return bytes(assets.scene(b.scene), 'image/png');
    if (b.text !== undefined) return bytes(Buffer.from(b.text, 'utf8'), 'text/plain; charset=utf-8');
    if (b.pdf) return bytes(assets.pdf(b.pdf), 'application/pdf');
    if (b.audio) return bytes(assets.audio(), 'audio/mp4');
    return null;
  };
  const job = (device, jid) => f.organizerJobs.find(j => j.id === jid && j.device === device);

  async function handle({method, url, body}) {
    const u = new URL(url), path = u.pathname, q = u.searchParams;
    let data = {};
    if (body && body.length) { try { data = JSON.parse(body); } catch { data = {}; } }
    calls.push({method, path: path + (u.search || '')});

    // ——— Firebase Auth (identitytoolkit / securetoken) ———
    if (u.hostname === 'identitytoolkit.googleapis.com') return json({kind: 'identitytoolkit#VerifyPasswordResponse', localId: 'demo-owner', email: data.email || 'lana@example.test', displayName: '', idToken: fakeJwt(f.now), registered: true, refreshToken: 'local-harness-refresh', expiresIn: '3600'});
    if (u.hostname === 'securetoken.googleapis.com') return json({id_token: fakeJwt(f.now), refresh_token: 'local-harness-refresh', expires_in: '3600', token_type: 'Bearer', user_id: 'demo-owner'});

    // ——— Account state, sessions, journals ———
    if (path === '/insights/api/state' && method === 'GET') return json({sessions: f.state.sessions, journals: f.state.journals, received_at: f.now, account: 'demo-owner', model: 'gemini-2.5-flash'});
    if (path === '/v2/sessions' && method === 'GET') return json({sessions: f.state.sessions});
    let m = /^\/v2\/sessions\/([0-9a-f-]+)(?:\/(data|items)(?:\/([0-9a-f-]+))?)?$/.exec(path);
    if (m) {
      const [, sid, part, item] = m, s = f.state.sessions.find(x => x.session_id === sid);
      if (!s) return json({error: 'Sesiunea nu mai este disponibilă.'}, 404);
      if (!part && method === 'GET') return json(s);
      if (!part && method === 'DELETE') { f.state.sessions = f.state.sessions.filter(x => x !== s); return json({deleted: true}); }
      if (part === 'data') return f.sessionData[sid] ? json(f.sessionData[sid]) : json({error: 'Fără date.'}, 404);
      if (part === 'items' && item) return blob('session:' + item) || json({error: 'Fișier indisponibil.'}, 404);
    }
    if (path === '/insights/api/observe' && method === 'POST') return json({text: 'Interpretare locală (harnașament): peisaj de munte, drumeție de weekend.'});
    if (path === '/insights/api/recommendations' && method === 'POST') return json(f.recommendations);
    if (path === '/insights/api/intake') { if (method === 'POST') f.intake = {accepting: !!data.accepting, revision: f.intake.revision + 1}; return json(f.intake); }

    // ——— Web control of the microphone + sleep reports ———
    if (path === '/insights/api/phones' && method === 'GET') return json({phones: f.phones});
    m = /^\/insights\/api\/phones\/([0-9a-f-]+)\/(command|collection)$/.exec(path);
    if (m && method === 'POST') {
      const p = f.phones.find(x => x.id === m[1]); if (!p) return json({error: 'Telefon indisponibil.'}, 404);
      if (m[2] === 'collection') p.collection_enabled = !!data.enabled;
      else { const start = data.start_at || f.now; p.command = {...data, start_at: start, stop_at: data.stop_at || start + (data.minutes || 1) * 60000, start_before: start + 300000}; }
      p.revision++; return json({ok: true});
    }
    if (path === '/v2/sleep/sessions' && method === 'GET') return json({sessions: f.sleepSessions});
    m = /^\/v2\/sleep\/sessions\/([0-9a-f-]+)(\/chunks)?$/.exec(path);
    if (m) {
      if (method === 'DELETE') { f.sleepSessions = f.sleepSessions.filter(s => s.id !== m[1]); return json({ok: true}); }
      if (m[2]) return json({ok: true});
      return f.sleepReports[m[1]] ? json(f.sleepReports[m[1]]) : json({error: 'Raport indisponibil.'}, 404);
    }

    // ——— Campaigns ———
    if (path === '/insights/api/campaigns') {
      if (method === 'POST') { const old = f.campaigns.find(c => c.id === data.id); const c = {...data.content, id: data.id, revision: (data.revision || 0) + 1}; f.campaigns = [...f.campaigns.filter(x => x !== old), c]; return json(c); }
      return json({campaigns: f.campaigns});
    }
    if (path.startsWith('/insights/api/campaigns/') && method === 'DELETE') { f.campaigns = f.campaigns.filter(c => !path.endsWith(c.id)); return json({deleted: true}); }
    if (path === '/insights/api/campaign-draft') return json({draft: {title: 'Un weekend cu mâinile în lut', body: 'Atelier pentru începători, sâmbătă dimineață. Materialele sunt incluse.'}, published: false});

    // ——— Files vault ———
    if (path === '/v2/files' && method === 'GET') {
      const kind = q.get('kind') || 'all', text = (q.get('q') || '').toLocaleLowerCase('ro-RO');
      const items = f.files.filter(x => (kind === 'all' || x.kind === kind) && (!text || (x.name + ' ' + (x.folder || '')).toLocaleLowerCase('ro-RO').includes(text))).sort((a, b) => b.received_at - a.received_at);
      return json({server_at: f.now, items, next_cursor: null, total: items.length, bytes: items.reduce((n, x) => n + x.bytes, 0)});
    }
    if (path === '/v2/files/settings' && method === 'GET') return json(f.fileSettings);
    m = /^\/v2\/files\/settings\/([0-9a-f-]+)$/.exec(path);
    if (m && method === 'POST') { const d = f.fileSettings.devices.find(x => x.id === m[1]); if (d) Object.assign(d, data); return json(d || {}); }
    m = /^\/v2\/files\/([0-9a-f-]+)(\/thumbnail)?$/.exec(path);
    if (m) {
      const item = f.files.find(x => x.id === m[1]);
      if (!item) return json({error: 'Fișierul nu mai este disponibil.'}, 404);
      if (method === 'DELETE') { f.files = f.files.filter(x => x !== item); return json({deleted: true}); }
      if (method === 'PATCH') { if (item.organizer_job) Object.assign(item, {pending_folder: data.folder, sync_state: 'awaiting_phone'}); else item.folder = data.folder; return json(item); }
      return blob('file:' + item.id) || json({error: 'Previzualizare indisponibilă.'}, 404);
    }

    // ——— Cleanup schedule + organizer (protocol 4 jobs; legacy runs/plans) ———
    if (path === '/v2/cleanup/devices' && method === 'GET') return json(f.cleanup);
    m = /^\/v2\/cleanup\/devices\/([0-9a-f-]+)\/schedule$/.exec(path);
    if (m && method === 'POST') { const d = f.cleanup.devices.find(x => x.id === m[1]); if (d) { d.schedule = {...d.schedule, ...data.schedule}; d.revision++; } return json(d || {}); }
    m = /^\/v2\/organizer\/devices\/([0-9a-f-]+)\/jobs(?:\/([0-9a-f-]+)(?:\/(command|items|approve))?)?$/.exec(path);
    if (m) {
      const [, device, jid, action] = m;
      if (!jid && method === 'GET') return json({jobs: f.organizerJobs.filter(j => j.device === device)});
      if (!jid && method === 'POST') {
        let j = f.organizerJobs.find(x => x.id === data.id);
        if (!j) { j = {...data, device, revision: 1, state: 'awaiting_phone', command: null, inventory_total: null, inventory_complete: false, counters: {total: 0, pending: 0, uploaded: 0, moved: 0, needs_review: 0, failed_retryable: 0, copied_pending_removal: 0}, created_at: f.now, updated_at: f.now}; f.organizerJobs.push(j); }
        return json(j);
      }
      const j = job(device, jid); if (!j) return json({error: 'Organizare indisponibilă.'}, 404);
      if (!action && method === 'DELETE') { f.organizerJobs = f.organizerJobs.filter(x => x !== j); return json({deleted: true}, 202); }
      if (!action) return json(j);
      if (action === 'items') { const items = f.organizerItems[jid] || []; return json({items, next_cursor: null, total: items.length}); }
      if (action === 'command') { j.revision++; j.command = {...data, revision: j.revision, status: data.action === 'continue' ? 'pending' : data.action === 'pause' ? 'paused' : 'cancelled'}; j.state = data.action === 'continue' ? 'awaiting_phone' : data.action === 'pause' ? 'paused' : 'cancelled'; return json(j); }
      if (action === 'approve') { j.revision++; j.state = 'awaiting_phone'; return json(j); }
    }
    m = /^\/v2\/organizer\/devices\/([0-9a-f-]+)\/(request|plans|runs)(?:\/([0-9a-f-]+))?(?:\/(items|plan))?$/.exec(path);
    if (m) {
      if (m[2] === 'plans') return method === 'GET' ? json({plans: f.legacyPlans}) : json({ok: true});
      if (m[2] === 'runs' && m[4] === 'items') return json({items: [], next_cursor: null, total: 0});
      return json({ok: true, message: 'Plan trimis telefonului.'});
    }
    if (path === '/insights/api/organizer-analysis' && method === 'POST') return json({saved: true, applied: false});
    if (path === '/insights/api/organize' && method === 'POST') return json({items: []});

    // ——— Social graph, journey (browser), explore (app) ———
    if (path.startsWith('/v2/social/')) {
      const r = path.slice('/v2/social/'.length);
      if (r === 'state') return json(f.social);
      if (r === 'journey/state') return json({...f.journey, owner: q.get('owner') || f.journey.owner});
      if (r === 'explore/state') return q.get('owner') ? json({owner: q.get('owner'), grid_m: 150, cells: fc([]), places: [], next_cursor: null, updated_at: 0}) : json(f.explore);
      if (r === 'visibility') { if (method === 'POST') { f.visibility = {ghost: data.ghost, grants: data.grants, configured: true, revision: (f.visibility.revision || 0) + 1, updated_at: f.now}; f.social.me.visibility = f.visibility; } return json(f.visibility); }
      if (r === 'chat') {
        if (method === 'POST') { (f.chat[data.friend] ||= []).push({id: data.id, from: f.social.me.id, text: data.text, at: f.now}); return json({ok: true}); }
        return json({messages: f.chat[q.get('friend')] || []});
      }
      m = /^explore\/places\/(.+)$/.exec(r);
      if (m && method === 'PATCH') { const p = f.explore.places.find(x => encodeURIComponent(x.id) === m[1] || x.id === decodeURIComponent(m[1])); if (!p) return json({error: 'Loc indisponibil.'}, 404); Object.assign(p, data, {updated_at: f.now}); return json(p); }
      if (r === 'session' && method === 'DELETE') { f.social.me.session = null; return json({ok: true}); }
      if (r === 'contacts/discovery' && method === 'DELETE') { f.social.me.discoverable = false; return json({ok: true}); }
      if (r === 'profile') { f.social.me.name = data.name; return json({ok: true}); }
      if (r === 'journey/session') { f.journey.session = method === 'POST' ? {id: data.id, started_at: f.now} : null; return json(f.journey.session || {ok: true}); }
      if (method !== 'GET') return json({ok: true});
    }

    // ——— Lost phone ———
    if (path === '/v2/recovery/devices' && method === 'GET') return json({devices: f.recovery});
    m = /^\/v2\/recovery\/devices\/([0-9a-f-]+)\/(command|grant)$/.exec(path);
    if (m) {
      const d = f.recovery.find(x => x.id === m[1]); if (!d) return json({error: 'Telefon indisponibil.'}, 404);
      if (m[2] === 'command') d.command = method === 'POST' ? {id: data.id, phase: 'queued', until: f.now + (data.minutes || 15) * 60000} : null;
      else f.recovery = f.recovery.filter(x => x !== d);
      return json({ok: true});
    }

    unmocked.push(method + ' ' + path);
    return json({error: 'UNMOCKED ' + method + ' ' + path + ' (scripts/site-shots/mock-api.cjs)'}, 404);
  }
  return {handle, calls, unmocked};
}

module.exports = {createApi};
