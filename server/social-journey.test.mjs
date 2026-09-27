import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {SocialGraph} from './social.mjs';
class Store{
 m=new Map(); async get(k){return structuredClone(this.m.get(k));}async put(k,v){this.m.set(k,structuredClone(v));}async delete(k){this.m.delete(k);}
 async list({prefix='',startAfter='',limit=1000}){return new Map([...this.m].filter(([k])=>k.startsWith(prefix)&&k>startAfter).sort(([a],[b])=>a<b?-1:a>b?1:0).slice(0,limit));}
 async transaction(fn){const before=structuredClone(this.m);try{return await fn(this);}catch(e){this.m=before;throw e;}}
 async getAlarm(){return this.alarm;}async setAlarm(v){this.alarm=v;}
}
function fixture(){const storage=new Store(),graph=new SocialGraph({storage,blockConcurrencyWhile:fn=>fn()});const call=async(owner,path,method='GET',value)=>{const r=await graph.fetch(new Request('https://forja/v2/social/'+path,{method,headers:{'x-forja-owner':owner,'content-type':'application/json'},...(value?{body:JSON.stringify(value)}:{})}));return{status:r.status,...await r.json()};};return{storage,call,graph};}
async function friends(f){const b=await f.call('bob','state');await f.call('alice','invite','POST',{code:b.me.code});await f.call('bob','friend','POST',{id:'alice',action:'accept'});}
async function start(f){const id=randomUUID();assert.equal((await f.call('alice','journey/session','POST',{id,consent:true})).status,200);return id;}
const visibility=async(f,v)=>f.call('alice','visibility','POST',{...v,revision:(await f.call('alice','visibility')).revision});
const q=(at,patch={})=>({at,lat:44.43,lon:26.1,accuracy:5,speed:0,...patch});
const send=(f,session,points,id=randomUUID())=>f.call('alice','journey/samples','POST',{session,id,points});
test('journal requires separate consent, is owner scoped and closed IDs cannot restart',async()=>{const f=fixture(),id=randomUUID();assert.equal((await f.call('alice','journey/session','POST',{id,consent:false})).status,403);await start(f);assert.equal((await f.call('bob','journey/state?owner=alice')).status,403);const session=(await f.call('alice','journey/state')).session.id;assert.equal((await f.call('alice','journey/session?session='+session,'DELETE')).status,200);assert.equal((await f.call('alice','journey/session','POST',{id:session,consent:true})).status,409);assert.equal((await send(f,session,[q(Date.now())])).status,409);});
test('five hours exactly is not a visit; observed time above threshold is durable and editable',async t=>{const f=fixture(),now=Date.now();let clock=now;t.mock.method(Date,'now',()=>clock);const id=await start(f);for(let i=0;i<=60;i++){clock=now+i*300000;assert.equal((await send(f,id,[q(clock)])).status,200);}let state=await f.call('alice','journey/state');assert.equal(state.visits.length,0);clock+=1000;await send(f,id,[q(clock)]);state=await f.call('alice','journey/state');assert.equal(state.visits.length,1);assert.equal(state.visits[0].observed_ms,18001000);assert.equal(state.zones.features.length,1);assert.equal(state.routes.features.length,0);const v=state.visits[0];assert.equal((await f.call('alice','journey/visits/'+v.id,'PATCH',{name:'Parcul meu',rating:5})).status,200);assert.equal((await f.call('alice','journey/state')).visits[0].name,'Parcul meu');});
test('gaps, GPS noise and transit do not become long visits; routes do not bridge missing data',async t=>{const f=fixture(),now=Date.now();let clock=now;t.mock.method(Date,'now',()=>clock);const id=await start(f);await send(f,id,[q(clock)]);clock+=6*3600000;await send(f,id,[q(clock)]);assert.equal((await f.call('alice','journey/state')).visits.length,0);clock+=60000;await send(f,id,[q(clock,{accuracy:200})]);clock+=60000;await send(f,id,[q(clock,{lon:26.11})]);let st=await f.call('alice','journey/state');assert.equal(st.routes.features.length,0);clock+=60000;await send(f,id,[q(clock,{lon:26.1102,speed:1})]);st=await f.call('alice','journey/state');assert.equal(st.routes.features.length,1);assert.equal(st.visits.length,0);});
test('offline ordered batches replay once and failed atomic writes cannot leave partial progress',async t=>{const f=fixture(),now=Date.now();let clock=now;t.mock.method(Date,'now',()=>clock);const id=await start(f);clock+=60000;const batch=randomUUID(),points=[q(now),q(clock,{lon:26.101})];const a=await send(f,id,points,batch);assert.equal(a.accepted,2);assert.equal((await send(f,id,points,batch)).replayed,true);assert.equal((await send(f,id,[q(now)],batch)).status,409);const state=await f.call('alice','journey/state');assert.equal(state.routes.features.length,1);const original=f.storage.put.bind(f.storage);let fail=true;f.storage.put=async(k,v)=>{await original(k,v);if(fail&&k.startsWith('journey:alice:route:'))throw Error('injected');};clock+=60000;assert.equal((await send(f,id,[q(clock,{lon:26.102})])).status,500);fail=false;assert.equal((await f.call('alice','journey/state')).routes.features.length,1);assert.equal((await send(f,id,[q(clock,{lon:26.102})])).accepted,1);assert.equal((await f.call('alice','journey/state')).routes.features.length,2);});
test('family requires explicit current and history grants; ghost exception and Stop all work independently',async t=>{const f=fixture();await friends(f);const now=Date.now();t.mock.method(Date,'now',()=>now);const id=await start(f);await send(f,id,[q(now)]);assert.equal((await f.call('bob','state')).friends[0].location,null);const grant={id:'bob',current:true,ghost:false,history:false};assert.equal((await visibility(f,{ghost:true,grants:[grant],consent:true})).status,200);assert.equal((await f.call('bob','state')).friends[0].location,null);grant.ghost=true;await visibility(f,{ghost:true,grants:[grant],consent:true});assert.equal((await f.call('bob','state')).friends[0].location.lat,44.43);assert.equal((await f.call('bob','journey/state?owner=alice')).status,403);grant.history=true;await visibility(f,{ghost:true,grants:[grant],consent:true});assert.equal((await f.call('bob','journey/state?owner=alice')).zones.features.length,1);await f.call('alice','session','DELETE');assert.equal((await f.call('bob','state')).friends[0].location,null);assert.equal((await f.call('bob','journey/state?owner=alice')).status,403);assert.equal((await f.call('alice','journey/state')).session.id,id);});
test('stale locations disappear, revoked friendship cannot revive old grants after reacceptance',async t=>{const f=fixture();await friends(f);const now=Date.now();let clock=now;t.mock.method(Date,'now',()=>clock);const id=await start(f);await send(f,id,[q(clock)]);await visibility(f,{ghost:true,grants:[{id:'bob',current:true,ghost:true,history:true}],consent:true});clock+=120001;assert.equal((await f.call('bob','state')).friends[0].location,null);await f.call('alice','friend','POST',{id:'bob',action:'remove'});await friends(f);assert.equal((await f.call('bob','journey/state?owner=alice')).status,403);assert.deepEqual((await f.call('alice','visibility')).grants,[]);});
test('history deletion invalidates queued old samples and removes every paged record',async()=>{const f=fixture(),id=await start(f);for(let i=0;i<205;i++)await f.storage.put('journey:alice:zone:'+String(i).padStart(3,'0'),{type:'Feature',properties:{id:i},geometry:null});let r=await f.call('alice','journey/state'),total=r.zones.features.length;assert.equal(total,100);assert(r.next_cursor);while(r.next_cursor){r=await f.call('alice','journey/state?cursor='+encodeURIComponent(r.next_cursor));total+=r.zones.features.length;}assert.equal(total,205);await f.call('alice','journey/history','DELETE');assert.equal((await f.call('alice','journey/state')).zones.features.length,0);assert.equal((await send(f,id,[q(Date.now())])).status,409);});
test('recommendation shares selected coordinates once without granting history access',async()=>{const f=fixture();await friends(f);const id=randomUUID();await f.storage.put('journey:alice:visit:'+id,{id,name:'Muzeu',lat:44,lon:26,rating:4,observed_ms:18000001});const v={visit:id,friend:'bob',id:randomUUID()};assert.equal((await f.call('alice','journey/recommend','POST',v)).status,200);await f.call('alice','journey/recommend','POST',v);const chat=await f.call('bob','chat?friend=alice');assert.equal(chat.messages.length,1);assert.equal(chat.messages[0].place.name,'Muzeu');assert.equal((await f.call('bob','journey/state?owner=alice')).status,403);});

test('only the owner state exposes visibility so controls can stop family-only sharing',async()=>{const f=fixture();await friends(f);await visibility(f,{ghost:true,grants:[{id:'bob',current:true,ghost:true,history:false}],consent:true});const mine=await f.call('alice','state'),theirs=await f.call('bob','state');assert.equal(mine.me.visibility.grants.length,1);assert.equal(mine.me.visibility.ghost,true);assert.equal(theirs.friends[0].visibility,undefined);});

test('explicit recipient opt-out also suppresses a legacy session and stale grants cannot undo Stop',async()=>{const f=fixture();await friends(f);const session=await f.call('alice','session','POST',{mode:'walk',minutes:60,consent:true});await f.call('alice','location','POST',{session:session.id,lat:44,lon:26,accuracy:5,speed:0,battery:80,at:Date.now()});assert.equal((await f.call('bob','state')).friends[0].location.lat,44);await visibility(f,{ghost:false,grants:[],consent:true});assert.equal((await f.call('bob','state')).friends[0].location,null);const previous=await f.call('alice','visibility');await f.call('alice','session','DELETE');assert.equal((await f.call('alice','visibility','POST',{ghost:false,grants:[{id:'bob',current:true,ghost:true,history:true}],consent:true,revision:previous.revision})).status,409);});
test('impossible GPS jumps do not unlock zones or replace own current location',async t=>{const f=fixture(),now=Date.now();let clock=now;t.mock.method(Date,'now',()=>clock);const id=await start(f);await send(f,id,[q(clock)]);clock+=1000;await send(f,id,[q(clock,{lat:45.43})]);const state=await f.call('alice','journey/state');assert.equal(state.zones.features.length,1);assert.equal(state.routes.features.length,0);assert.equal((await f.call('alice','state')).me.location.lat,44.43);});

test('session stop writes are atomic; closed markers always reject stale generation writes',async()=>{const f=fixture(),id=await start(f),original=f.storage.put.bind(f.storage);let fail=true;f.storage.put=async(k,v)=>{await original(k,v);if(fail&&k==='user:alice')throw Error('injected stop');};assert.equal((await f.call('alice','journey/session?session='+id,'DELETE')).status,500);assert.equal(await f.storage.get('journey:alice:closed:'+id),undefined);fail=false;await f.storage.put('journey:alice:closed:'+id,{at:Date.now()});assert.equal((await send(f,id,[q(Date.now())])).status,409);assert.equal((await f.call('alice','journey/session','POST',{id,consent:true})).status,409);});

// ── v4.0 explore mirror (150 m cells + places with stars/notes pushed by the phone) ──
const device=randomUUID();
const cell=(x,y,patch={})=>({id:x+'_'+y,min_lat:44.43+y*0.001,min_lng:26.09+x*0.001,max_lat:44.431+y*0.001,max_lng:26.091+x*0.001,first_at:1759000000000,last_at:1759003600000,visits:1,...patch});
const place=(id,patch={})=>({id,lat:44.43,lng:26.09,first_at:1759000000000,last_at:1759003600000,stay_ms:19800000,name:'Parcul Herăstrău',stars:4,note:'Bancă la lac',recommended:false,visible_to:[],updated_at:1759003600000,deleted:false,...patch});
const sync=(f,owner,body)=>f.call(owner,'explore/sync','POST',{device,grid_m:150,revision:1,reset:false,cells:[],places:[],...body});
test('explore sync is an idempotent upsert: cells merge min/max and places follow last-writer-wins',async()=>{
 const f=fixture();
 let r=await sync(f,'alice',{cells:[cell(1,1),cell(2,1)],places:[place('p1')]});
 assert.equal(r.status,200);assert.equal(r.ok,true);assert.equal(r.cells,2);assert.equal(r.places,1);assert.equal(r.revision,1);assert(Number.isSafeInteger(r.server_at));
 r=await sync(f,'alice',{cells:[cell(1,1),cell(2,1)],places:[place('p1')]});assert.equal(r.cells,2);assert.equal(r.places,1);
 r=await sync(f,'alice',{revision:2,cells:[cell(1,1,{first_at:1758000000000,last_at:1759090000000,visits:3})],places:[place('p1',{name:'Vechi',updated_at:1759000000000}),place('p2',{stars:5,note:'',updated_at:1759004000000})]});
 assert.equal(r.cells,2);assert.equal(r.places,2);assert.equal(r.revision,2);
 const state=await f.call('alice','explore/state');assert.equal(state.status,200);assert.equal(state.owner,'alice');assert.equal(state.grid_m,150);
 const merged=state.cells.features.find(c=>c.properties.id==='1_1');assert.equal(merged.geometry.type,'Polygon');assert.equal(merged.geometry.coordinates[0].length,5);assert.equal(merged.properties.kind,'explored');
 assert.equal(merged.properties.first_at,1758000000000);assert.equal(merged.properties.last_at,1759090000000);assert.equal(merged.properties.visits,3);
 assert.equal(state.places.length,2);const p1=state.places.find(p=>p.id==='p1');assert.equal(p1.name,'Parcul Herăstrău','older phone edit must not overwrite');assert.equal(p1.lon,26.09);assert.equal(p1.lng,26.09);assert.equal(p1.stars,4);assert.equal(p1.visible_to,undefined);
 assert.equal(state.next_cursor,null);
});
test('explore sync validates bounds, sizes, ids and text and rejects oversized batches',async()=>{
 const f=fixture();
 assert.equal((await sync(f,'alice',{cells:[cell(1,1,{id:'a_b'})]})).status,400);
 assert.equal((await sync(f,'alice',{cells:[cell(1,1,{max_lat:44.45})]})).status,400);
 assert.equal((await sync(f,'alice',{cells:[cell(1,1,{max_lng:26.09})]})).status,400);
 assert.equal((await sync(f,'alice',{cells:[cell(1,1,{visits:0})]})).status,400);
 assert.equal((await sync(f,'alice',{cells:Array.from({length:501},(_,i)=>cell(i,0))})).status,413,'a full 501-cell batch is over 64 KiB');
 assert.equal((await sync(f,'alice',{cells:Array.from({length:501},(_,i)=>({...cell(0,0),id:String(i)+'_0',first_at:0,last_at:0}))})).status,400,'a compact batch still fails the 500-cell count');
 assert.equal((await sync(f,'alice',{places:Array.from({length:101},(_,i)=>place('p'+i))})).status,400);
 assert.equal((await sync(f,'alice',{places:[place('p1',{stars:6})]})).status,400);
 assert.equal((await sync(f,'alice',{places:[place('p1',{name:'x'.repeat(81)})]})).status,400);
 assert.equal((await sync(f,'alice',{places:[place('p1',{note:'bad\x00'})]})).status,400);
 assert.equal((await sync(f,'alice',{places:[place('p1',{name:'două\nrânduri'})]})).status,400,'names stay on one line');
 assert.equal((await sync(f,'alice',{places:[place('p1',{note:'rând 1\nrând 2'})]})).status,200,'a note may span lines');
 assert.equal((await f.call('alice','explore/places/p1','PATCH',{note:'de pe\nlaptop'})).status,200);
 assert.equal((await sync(f,'alice',{places:[place('bad id')]})).status,400);
 assert.equal((await sync(f,'alice',{grid_m:50})).status,400);
 assert.equal((await sync(f,'alice',{device:'phone'})).status,400);
 assert.equal((await f.call('alice','explore/sync','POST',{device,extra:1})).status,400);
 assert.equal((await f.call('alice','explore/state?cursor=zzz')).status,400);
 assert.equal((await f.call('alice','explore/state')).cells.features.length,0,'nothing invalid was stored');
 assert.equal((await f.call('alice','explore/state')).places[0].note,'de pe\nlaptop');
});
test('explore cells page 200 at a time by cursor and places arrive on every page',async()=>{
 const f=fixture();
 for(let i=0;i<205;i+=100)assert.equal((await sync(f,'alice',{cells:Array.from({length:Math.min(100,205-i)},(_,k)=>cell(i+k,0))})).status,200);
 await sync(f,'alice',{places:[place('p1')]});
 let r=await f.call('alice','explore/state'),total=r.cells.features.length;assert.equal(total,200);assert(r.next_cursor);assert.equal(r.places.length,1);
 r=await f.call('alice','explore/state?cursor='+encodeURIComponent(r.next_cursor));total+=r.cells.features.length;assert.equal(total,205);assert.equal(r.next_cursor,null);assert.equal(r.places.length,1);
});
test('explore state of a friend needs the history grant and shows only places shared with the viewer',async()=>{
 const f=fixture();await friends(f);
 const r=await sync(f,'alice',{cells:[cell(1,1)],places:[place('p1',{visible_to:['bob']}),place('p2',{visible_to:['carol']}),place('p3')]});
 assert.equal(r.status,200);assert.equal(r.places,3);
 assert.equal((await f.call('bob','explore/state?owner=alice')).status,403);
 assert.equal((await f.call('carol','explore/state?owner=alice')).status,403,'not a friend');
 await visibility(f,{ghost:false,grants:[{id:'bob',current:false,ghost:false,history:true}],consent:true});
 const shared=await f.call('bob','explore/state?owner=alice');assert.equal(shared.status,200);assert.equal(shared.owner,'alice');assert.equal(shared.cells.features.length,1);
 assert.deepEqual(shared.places.map(p=>p.id),['p1'],'non-friend uids are dropped, private places stay private');
 assert.equal((await f.call('alice','explore/state')).places.length,3);
 assert.equal((await f.call('bob','explore/places/p1','PATCH',{name:'Al meu'})).status,404,'only the owner edits');
});
test('laptop edits bump updated_at so the phone pull wins over an older phone copy',async t=>{
 const f=fixture(),now=Date.now();let clock=now;t.mock.method(Date,'now',()=>clock);
 await sync(f,'alice',{places:[place('p1',{updated_at:now-1000})]});
 assert.equal((await f.call('alice','explore/places/p9','PATCH',{name:'Nu există'})).status,404);
 assert.equal((await f.call('alice','explore/places/p1','PATCH',{})).status,400);
 assert.equal((await f.call('alice','explore/places/p1','PATCH',{stars:9})).status,400);
 clock=now+5000;const edited=await f.call('alice','explore/places/p1','PATCH',{name:'Lacul',stars:5,note:'Dimineața devreme'});
 assert.equal(edited.status,200);assert.equal(edited.name,'Lacul');assert.equal(edited.stars,5);assert.equal(edited.note,'Dimineața devreme');assert.equal(edited.updated_at,now+5000);
 let state=await f.call('alice','explore/state');assert.equal(state.places[0].name,'Lacul');assert.equal(state.updated_at,now+5000);
 await sync(f,'alice',{places:[place('p1',{name:'Telefon vechi',updated_at:now+4000})]});
 state=await f.call('alice','explore/state');assert.equal(state.places[0].name,'Lacul','phone copy older than the laptop edit is ignored');
 await sync(f,'alice',{places:[place('p1',{name:'Telefon nou',updated_at:now+6000})]});
 assert.equal((await f.call('alice','explore/state')).places[0].name,'Telefon nou');
});
test('tombstones remove places, reset wipes before upsert and history deletion clears everything',async()=>{
 const f=fixture();
 await sync(f,'alice',{cells:[cell(1,1),cell(2,2)],places:[place('p1'),place('p2')]});
 let r=await sync(f,'alice',{places:[{id:'p1',deleted:true,updated_at:1759009999999}]});assert.equal(r.status,200);assert.equal(r.places,1);
 assert.deepEqual((await f.call('alice','explore/state')).places.map(p=>p.id),['p2']);
 assert.equal((await f.call('alice','explore/places/p1','PATCH',{name:'Înviat'})).status,404);
 r=await sync(f,'alice',{places:[place('p1',{updated_at:1759000000000})]});assert.equal(r.places,1,'an older upsert cannot revive a deleted place');
 r=await sync(f,'alice',{reset:true,cells:[cell(5,5)],places:[place('p7')]});assert.equal(r.cells,1);assert.equal(r.places,1);
 let state=await f.call('alice','explore/state');assert.deepEqual(state.cells.features.map(c=>c.properties.id),['5_5']);assert.deepEqual(state.places.map(p=>p.id),['p7']);
 assert.equal((await f.call('alice','explore/history','DELETE')).status,200);
 state=await f.call('alice','explore/state');assert.equal(state.cells.features.length,0);assert.equal(state.places.length,0);assert.equal(state.updated_at,0);
 assert.equal((await f.call('bob','explore/state')).cells.features.length,0,'owner scoped');
 assert.equal((await f.call('alice','explore/unknown')).status,404);
});
