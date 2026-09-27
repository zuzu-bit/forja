// Synthetic responses for local UX review only. Nothing here is deployed by the worker.
const fs = require('node:fs');
const path = require('node:path');
const server = path.resolve(__dirname, '../server');
const clients = ['insights-client.js.txt', 'sleep-client.js.txt', 'files-preview.js.txt', 'files-client.js.txt', 'cleanup-client.js.txt', 'organizer-client.js.txt', 'social-client.js.txt', 'recovery-client.js.txt', 'journey-client.js.txt'];

function createFixture(now = Date.now()) {
  const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
  const position = (lat, lon, battery = 78) => ({lat, lon, battery, accuracy: 12, speed: 1.2, at: now});
  const deviceId = id(20);
  const sessions = [{session_id: id(1), created_at: now - 300000, expires_at: now + 86000000, bytes: 2560000, consent: {sleep: true, movement: true}, items: [], observations: []}];
  return {
    calls: [],
    state: {sessions, journals: {
      sleep: {records: Array.from({length: 7}, (_,i) => ({startAt: now - i * 86400000 - 28800000, endAt: now - i * 86400000, score: 84 + i}))},
      activities: {records: [{startAt: now - 7200000, type: 'Plimbare', durationS: 2400, distanceM: 3200}]},
      meals: {records: [{at: now - 14400000, name: 'Mic dejun', grams: 280, kcal: 410}]}
    }},
    social: {
      me: {id: 'demo-owner', name: 'Alex', code: id(3), session: {mode: 'walk', until: now + 3600000}, location: position(44.4132, 26.0938), places: [{id: id(4), name: 'Parcul Carol', lat: 44.4127, lon: 26.0966}], history: [], explored: ['a', 'b', 'c'], discoverable: true, partner: {id: 'demo-ana', name: 'Ana', state: 'accepted'}},
      friends: [
        {id: 'demo-ana', name: 'Ana Popescu', mode: 'walk', location: position(44.4172, 26.1001), checkin: {name: 'Parcul Carol'}},
        {id: 'demo-mihai', name: 'Mihai Ionescu', mode: 'cycle', location: position(44.4229, 26.0951, 62), checkin: null},
        {id: 'demo-ioana', name: 'Ioana Matei', mode: null, location: null, checkin: null}
      ], incoming: [],
      groups: [{id: id(5), name: 'O tură prin parc', mode: 'walk', at: now + 7200000, place: 'Fântâna din parc', lat: 44.4127, lon: 26.0966, going: ['demo-owner','demo-ana'], members: ['demo-owner','demo-ana','demo-mihai']}]
    },
    explore: {owner:'self',grid_m:150,cells:{type:'FeatureCollection',features:[]},places:[],next_cursor:null,updated_at:0},
    journey: {owner:'self',session:null,last_sample_at:null,routes:{type:'FeatureCollection',features:[]},zones:{type:'FeatureCollection',features:[]},visits:[],next_cursor:null,rules:{gap_ms:300000,radius_m:100,accuracy_m:50,grid_m:200,visit_ms:18000000}},
    visibility: {ghost:false,grants:[],updated_at:0,revision:0},
    recovery: [{id: deviceId, name: 'Telefonul meu', enabled: true, seen_at: now, online: true, status: 'ready', command: null, position: null}],
    files: {server_at: now, items: [
      {id: id(7), name: 'Lista pentru weekend.txt', kind: 'file', preview: 'text', media_type: 'text/plain', bytes: 1200, received_at: now - 300000, expires_at: now + 86400000, folder: 'Personal'},
      {id: id(8), name: 'Plan de antrenament.pdf', kind: 'file', preview: 'download', media_type: 'application/pdf', bytes: 250000, received_at: now - 600000, expires_at: now + 86400000, folder: 'Mișcare'}
    ], next_cursor: null, total: 2, bytes: 251200},
    fileSettings: {devices: [{id: deviceId, photos: true, files: true, enabled: true}]},
    cleanup: {devices: [{id: deviceId, label: 'Telefonul meu', last_seen: now, protocol: 3, revision: 1, grant: {id: id(10), enabled: true, photos: true, files: true, organize: true}, schedule: {enabled: true, photos: true, files: false, wifi_only: true, count: 25, timezone: 'Europe/Bucharest', times: ['20:00'], days: [1,3,5]}}], runs: []},
    intake: {accepting: true, revision: 1},
    phones: [{id: deviceId, name: 'Telefonul meu', online: true, seen_at: now, audio_allowed: true, audio_background_capable: true, audio_ready: true, foreground: false, collection_enabled: true, state: 'idle', revision: 1}],
    campaigns: [],
    sleep: {sessions: [], reports: {}},
    organizer: {jobs: [], items: {}, commands: {}, approvals: {}, moves: {}},
    messages: [{id: id(11), from: 'demo-ana', text: 'Ne vedem în parc? ☀️', at: now}]
  };
}

// This function is also serialized into a self-contained file:// review page.
function installMockFetch(win, fixture) {
  const response = (data, status = 200) => win.Response.json(data, {status});
  win.fetch = async (url, options = {}) => {
    const address = new win.URL(String(url), 'https://forja.demo');
    const route = address.pathname;
    const method = options.method || 'GET';
    const body = typeof options.body === 'string' ? JSON.parse(options.body) : {};
    fixture.calls.push({url: String(url), route, method, body, headers: options.headers});
    if (address.hostname === 'identitytoolkit.googleapis.com') return response({idToken: 'local-demo-token', refreshToken: 'local-demo-refresh', expiresIn: '3600', email: 'alex@example.test'});
    if (route === '/insights/api/state') return response(fixture.state);
    if (route === '/v2/sessions') return response({sessions: fixture.state.sessions});
    if (route === '/v2/social/state') return response(fixture.social);
    if(route==='/v2/social/journey/state')return response(fixture.journey);
    if(route==='/v2/social/explore/state')return response(fixture.explore);
    if(route.startsWith('/v2/social/explore/places/')&&method==='PATCH'){const place=fixture.explore.places.find(p=>route.endsWith('/'+encodeURIComponent(p.id)));if(!place)return response({error:'Loc indisponibil.'},404);Object.assign(place,body,{updated_at:Date.now()});return response(place);}
    if(route==='/v2/social/visibility'){if(method==='POST'){if(body.revision!==(fixture.visibility.revision||0))return response({error:'Vizibilitatea s-a schimbat.'},409);fixture.visibility={...body,updated_at:Date.now(),revision:(fixture.visibility.revision||0)+1};}return response(fixture.visibility);}
    if(route==='/v2/social/journey/session'){if(method==='POST')fixture.journey.session={id:body.id,started_at:Date.now()};else fixture.journey.session=null;return response(fixture.journey.session||{ok:true});}
    if (route === '/v2/social/chat') {
      if (method === 'POST') fixture.messages.push({...body, from: 'demo-owner', at: Date.now()});
      return response({messages: fixture.messages});
    }
    if (route === '/v2/social/session' && method === 'DELETE') {fixture.social.me.session = null;fixture.visibility={ghost:true,grants:[],updated_at:Date.now(),revision:(fixture.visibility.revision||0)+1};}
    if (route === '/v2/social/contacts/discovery' && method === 'DELETE') fixture.social.me.discoverable = false;
    if (route === '/v2/social/profile') fixture.social.me.name = body.name;
    if (route === '/v2/recovery/devices') return response({devices: fixture.recovery});
    if (route.startsWith('/v2/recovery/devices/') && route.endsWith('/command')) {
      const device = fixture.recovery.find(d => route.includes(d.id));
      if (method === 'POST') device.command = {id: body.id, phase: 'queued', until: Date.now() + body.minutes * 60000, start_before: Date.now() + 300000};
      if (method === 'DELETE') device.command = null;
    }
    if (route === '/v2/files') return response(fixture.files);
    if (route === '/v2/files/settings') return response(fixture.fileSettings);
    if (route.startsWith('/v2/files/') && method === 'PATCH') {
      const item=fixture.files.items.find(item=>route.endsWith('/'+item.id));if(!item)return response({error:'Copia nu este disponibilă.'},404);
      if(item.organizer_job){const job=fixture.organizer.jobs.find(job=>job.id===item.organizer_job),key=item.id+':'+body.request_id;
        if(!fixture.organizer.moves[key]&&job.revision!==body.revision)return response({error:'Actualizează organizarea.'},409);
        fixture.organizer.moves[key]=body;Object.assign(item,{pending_folder:body.folder,sync_state:'awaiting_phone'});
      }else item.folder=body.folder;
      return response(item);
    }
    if (route.startsWith('/v2/files/') && method === 'GET') return new win.Response('Plimbare în parc\nApă\nO carte bună', {headers: {'content-type':'text/plain'}});
    if (route === '/v2/cleanup/devices') return response(fixture.cleanup);
    const organizer = /^\/v2\/organizer\/devices\/([^/]+)\/jobs(?:\/([^/]+)(?:\/(command|items|approve))?)?$/.exec(route);
    if (organizer) {
      const [, device, id, action] = organizer, store = fixture.organizer;
      if (!id && method === 'GET') return response({jobs:store.jobs.filter(job=>job.device===device)});
      if (!id && method === 'POST') {
        let job=store.jobs.find(job=>job.id===body.id);
        if(!job){job={...body,device,revision:1,state:'awaiting_phone',command:null,inventory_total:null,inventory_complete:false,counters:{total:0,pending:0,uploaded:0,moved:0,needs_review:0,failed_retryable:0,copied_pending_removal:0},created_at:Date.now(),updated_at:Date.now()};store.jobs.push(job);}
        return response(job);
      }
      const job=store.jobs.find(job=>job.id===id&&job.device===device);
      if(!job)return response({error:'Organizare indisponibilă.'},404);
      if(!action&&method==='DELETE'){store.jobs=store.jobs.filter(row=>row.id!==id);delete store.items[id];return response({deleted:true},202);}
      if(action==='items'&&method==='GET'){
        const results=store.items[id]||{items:[],next_cursor:null,total:0};
        return response(address.searchParams.has('after')?results.pages[address.searchParams.get('after')]:results);
      }
      if(action==='command'&&method==='POST'){
        const key=id+':'+body.request_id;if(store.commands[key])return response(job);
        if(body.revision!==job.revision)return response({error:'Organizarea s-a schimbat. Actualizează.'},409);
        store.commands[key]=body;job.revision++;job.command={...body,revision:job.revision,selected:0,status:body.action==='continue'?'pending':body.action==='pause'?'paused':'cancelled'};job.state=body.action==='continue'?'awaiting_phone':body.action==='pause'?'paused':'cancelled';return response(job);
      }
      if(action==='approve'&&method==='POST'){const key=id+':'+body.request_id;if(store.approvals[key])return response(job);if(body.revision!==job.revision)return response({error:'Actualizează organizarea.'},409);store.approvals[key]=body;job.revision++;job.state='awaiting_phone';return response(job);}
      if(method==='GET')return response(job);
    }
    if(route==='/insights/api/organizer-analysis'&&method==='POST')return response({saved:true,applied:false});
    if (route === '/insights/api/phones') return response({phones: fixture.phones});
    if (route.startsWith('/insights/api/phones/') && route.endsWith('/command') && method === 'POST') {
      const phone = fixture.phones.find(p => route.includes(p.id));
      const start = body.start_at || Date.now();
      phone.command = {...body, start_at: start, stop_at: body.stop_at || start + body.minutes * 60000, start_before: start + 300000};
      phone.revision++;
      return response({ok: true});
    }
    if (route === '/v2/sleep/sessions') return response({sessions: fixture.sleep.sessions});
    if (route.startsWith('/v2/sleep/sessions/')) {
      const id = route.split('/')[4];
      if (method === 'DELETE') {fixture.sleep.sessions = fixture.sleep.sessions.filter(s => s.id !== id); delete fixture.sleep.reports[id]; return response({ok: true});}
      if (method === 'GET') {
        const report = fixture.sleep.reports[id];
        return response(address.searchParams.has('cursor') ? report.pages[address.searchParams.get('cursor')] : report);
      }
      return response({ok: true});
    }
    if (route === '/insights/api/intake') {
      if (method === 'POST') fixture.intake = {...fixture.intake, ...body, revision: fixture.intake.revision + 1};
      return response(fixture.intake);
    }
    if (route === '/insights/api/campaigns') {
      if (method === 'POST') {
        fixture.campaigns = fixture.campaigns.filter(c => c.id !== body.id);
        const campaign = {...body.content, id: body.id, revision: body.revision + 1};
        fixture.campaigns.push(campaign);
        return response(campaign);
      }
      return response({campaigns: fixture.campaigns});
    }
    if (route === '/insights/api/campaign-draft') return response({draft: {title: 'Un weekend cu energie', body: 'O plimbare, prieteni și puțin timp pentru tine.'}});
    if (route === '/insights/api/recommendations') return response({generated_at: Date.now(), model: 'DEMO local', evidence: [{id: 'sleep', text: 'Date sintetice: șapte sesiuni de somn.'}], recommendations: [{category: 'activities', confidence: 'medium', title: 'O plimbare după-amiază', why: 'Un moment de mișcare în aer liber.', next_step: 'Alege un parc și invită un prieten.', evidence_ids: ['sleep']}]});
    if (route.endsWith('/plans')) return response({plans: []});
    if (method !== 'GET' && (route.startsWith('/v2/') || route.startsWith('/insights/api/'))) return response({ok: true});
    throw Error('No local fixture for ' + method + ' ' + route);
  };
}

function clientSource() { return clients.map(name => fs.readFileSync(path.join(server, name), 'utf8')).join('\n'); }
module.exports = {server, clients, createFixture, installMockFetch, clientSource};
