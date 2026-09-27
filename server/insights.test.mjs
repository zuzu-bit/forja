import test from 'node:test';
import assert from 'node:assert/strict';
import { InsightsAccount } from './insights-store.mjs';
import { buildEvidence, validateRecommendations, loadJournals, internalRequest, parsedJSON, recommendationFormat } from './insights-ai.mjs';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { handleInsights } from './insights-ai.mjs';
import { checkRecording } from './recording-schema.mjs';
import { campaignFields } from './app-content.mjs';
class MemoryStorage {
  values=new Map(); alarm=null;
  async get(k){return structuredClone(this.values.get(k));}
  async put(k,v){if(typeof k==='object'){for(const [a,b]of Object.entries(k))this.values.set(a,structuredClone(b));}else this.values.set(k,structuredClone(v));}
  async list({prefix}){return new Map([...this.values].filter(([k])=>k.startsWith(prefix)).map(([k,v])=>[k,structuredClone(v)]));}
  async delete(k){for(const key of Array.isArray(k)?k:[k])this.values.delete(key);}
  async setAlarm(v){this.alarm=v;} async deleteAlarm(){this.alarm=null;}
}
class Bucket {
  files=new Map();
  async put(k,v){this.files.set(k,new Uint8Array(v));}
  async get(k){return this.files.has(k)?{body:this.files.get(k)}:null;}
  async list({prefix}){return {objects:[...this.files.keys()].filter(k=>k.startsWith(prefix)).map(key=>({key})),truncated:false};}
  async delete(keys){for(const key of Array.isArray(keys)?keys:[keys])this.files.delete(key);}
}
function fixture(bucket=new Bucket()){
  const storage=new MemoryStorage(); let queue=Promise.resolve();
  const ctx={storage,blockConcurrencyWhile(fn){const next=queue.then(fn);queue=next.catch(()=>{});return next;}};
  const object=new InsightsAccount(ctx,{RECORDS:bucket});
  const call=(path,method='GET',data,uid='userA',headers={})=>object.fetch(new Request('https://test'+path,{method,headers:{'x-forja-owner':uid,'content-type':'application/json',...headers},...(data===undefined?{}:{body:data instanceof Uint8Array?data:JSON.stringify(data)})}));
  return {call,storage,bucket,object};
}
const consent=(...ks)=>Object.fromEntries(['location','app_usage','files','photos','audio'].map(k=>[k,ks.includes(k)]));
async function session(f,...ks){const id=randomUUID();assert.equal((await f.call('/v2/sessions','POST',{session_id:id,consent:consent(...ks)})).status,201);return id;}
function phone(){return {locations:[{at:1000,latitude:44.4,longitude:26.1,accuracy_m:8,segment:1}],visits:[{first_seen:1000,last_seen:1000,latitude:44.4,longitude:26.1,observed_ms:0,samples:1}]};}
function wav(){const b=Buffer.alloc(32044);b.write('RIFF');b.writeUInt32LE(b.length-8,4);b.write('WAVEfmt ',8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(16000,24);b.writeUInt32LE(32000,28);b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(b.length-44,40);return b;}
test('online metrics round trip, receipts, and unknown fields rejected',async()=>{const f=fixture(),id=await session(f,'location');assert.equal((await f.call('/v2/sessions/'+id+'/data','POST',{...phone(),email:'private'})).status,400);const r=await f.call('/v2/sessions/'+id+'/data','POST',phone());assert.equal(r.status,201);const receipt=await r.json();assert.equal(receipt.bytes,Buffer.byteLength(JSON.stringify(phone())));assert.match(receipt.sha256,/^[a-f0-9]{64}$/);assert.deepEqual(await(await f.call('/v2/sessions/'+id+'/data')).json(),phone());});
test('concurrent duplicate uploads produce exactly one receipt and one stored item',async()=>{const f=fixture(),id=await session(f,'files');const upload=()=>f.call('/v2/sessions/'+id+'/items?kind=file&sequence=0','POST',new Uint8Array([1,2,3]));assert.deepEqual((await Promise.all([upload(),upload()])).map(r=>r.status).sort(),[201,409]);assert.equal(f.bucket.files.size,1);});
test('account namespaces isolate an identical client session ID',async()=>{const bucket=new Bucket(),a=fixture(bucket),b=fixture(bucket),id=randomUUID();for(const[f,uid]of[[a,'userA'],[b,'userB']])assert.equal((await f.call('/v2/sessions','POST',{session_id:id,consent:consent('files')},uid)).status,201);const r=await a.call('/v2/sessions/'+id+'/items?kind=file&sequence=0','POST',new Uint8Array([7]));const item=await r.json();assert.equal((await b.call('/v2/sessions/'+id+'/items/'+item.item_id,'GET',undefined,'userB')).status,404);assert.equal((await a.call('/v2/sessions','GET',undefined,'userB')).status,403);});
test('selected binary bytes and session deletion including orphan cleanup',async()=>{const f=fixture(),id=await session(f,'photos');const bytes=new Uint8Array([1,2,3,0,255]);const r=await f.call('/v2/sessions/'+id+'/items?kind=photo&sequence=0','POST',bytes);const item=await r.json();const download=await f.call('/v2/sessions/'+id+'/items/'+item.item_id);assert.deepEqual(new Uint8Array(await download.arrayBuffer()),bytes);assert.equal(download.headers.get('content-type'),'application/octet-stream');const record=await f.storage.get('session:'+id);await f.bucket.put(record.prefix+'orphan',bytes);assert.equal((await f.call('/v2/sessions/'+id,'DELETE')).status,200);assert.equal(f.bucket.files.size,0);});
test('consent, coordinates, selected-item and size bounds',async()=>{const f=fixture(),id=await session(f,'files');assert.equal((await f.call('/v2/sessions/'+id+'/data','POST',phone())).status,400);assert.equal((await f.call('/v2/sessions/'+id+'/items?kind=photo&sequence=0','POST',new Uint8Array([1]))).status,400);assert.equal((await f.call('/v2/sessions/'+id+'/items?kind=file&sequence=5','POST',new Uint8Array([1]))).status,400);assert.equal((await f.call('/v2/sessions/'+id+'/items?kind=file&sequence=0','POST',new Uint8Array(5*1024*1024+1))).status,413);const p=phone();p.locations[0].latitude=120;const loc=await session(f,'location');assert.equal((await f.call('/v2/sessions/'+loc+'/data','POST',p)).status,400);});
test('valid audio only and upload window enforced',async()=>{const f=fixture(),id=await session(f,'audio');assert.equal((await f.call('/v2/sessions/'+id+'/items?kind=audio&sequence=0','POST',new Uint8Array([1,2]))).status,400);assert.equal((await f.call('/v2/sessions/'+id+'/items?kind=audio&sequence=0','POST',wav())).status,201);const record=await f.storage.get('session:'+id);record.created_at-=181000;await f.storage.put('session:'+id,record);assert.equal((await f.call('/v2/sessions/'+id+'/items?kind=audio&sequence=1','POST',wav())).status,410);});
test('expired sessions are inaccessible and removed with their binaries',async()=>{const f=fixture(),id=await session(f,'files');await f.call('/v2/sessions/'+id+'/items?kind=file&sequence=0','POST',new Uint8Array([1]));const record=await f.storage.get('session:'+id);record.expires_at=Date.now()-1;await f.storage.put('session:'+id,record);assert.equal((await f.call('/v2/sessions/'+id)).status,404);assert.equal(f.bucket.files.size,0);assert.equal(f.storage.alarm,null);});
test('observations need a real owned item; writes and AI budget are bounded',async()=>{const f=fixture(),id=await session(f,'files');assert.equal((await f.call('/v2/sessions/'+id+'/observation','POST',{item_id:randomUUID(),text:'invented',model:'test'})).status,400);assert.equal((await f.call('/internal/ai-budget','POST',{})).status,200);assert.equal((await f.call('/internal/ai-budget','POST',{})).status,429);});
const journals=()=>({sleep:{records:[],error:null},activities:{records:[],error:null},meals:{records:[],error:null}});
test('no recommendations are supported by a completely empty account',()=>assert.deepEqual(buildEvidence(journals(),[]),[]));
test('evidence omits exact coordinates and labels location meaning as unknown',()=>{const j=journals();j.sleep.records=[{startAt:1000,endAt:3601000}];const data=phone();const e=buildEvidence(j,[{session_id:randomUUID(),data:{bytes:1},metrics:data,observations:[]}]);const text=JSON.stringify(e);assert.ok(!text.includes('44.4'));assert.ok(!text.includes('26.1'));assert.match(text,/Nu știm/);assert.match(text,/NU demonstrează/);});
test('AI cannot cite invented evidence or inject actionable HTML/URLs fields',()=>{const evidence=[{id:'sleep-summary',text:'O singură sesiune de somn trimisă; nu cunoaștem rutina.',count:1}];const r={category:'sleep',title:'O seară liniștită',why:'Un indiciu',next_step:'Verifică rutina.',confidence:'low',evidence_ids:['sleep-summary'],url:'javascript:alert(1)'};assert.equal(validateRecommendations({recommendations:[r]},evidence)[0].url,undefined);assert.throws(()=>validateRecommendations({recommendations:[{...r,evidence_ids:['invented']}]},evidence));assert.throws(()=>validateRecommendations({recommendations:[{...r,confidence:'certain'}]},evidence));});
test('Firestore queries are owner-scoped and one failed journal does not hide others',async()=>{const calls=[];const out=await loadJournals('testUid','synthetic-token',async(url,opts)=>{calls.push([url,opts]);const k=JSON.parse(opts.body).structuredQuery.from[0].collectionId;if(k==='sleep')throw Error('offline');if(k==='meals')return Response.json({error:'blocked'},{status:403});return Response.json([{document:{name:'projects/x/users/testUid/activities/a1',fields:{durationS:{integerValue:'120'},type:{stringValue:'walk'}}}}]);},1000000000);assert.equal(out.activities.records[0].durationS,120);assert.ok(out.sleep.error);assert.equal(out.meals.error,'Firestore HTTP 403');assert.ok(calls.every(([u])=>u.includes('/users/testUid:runQuery')));});
test('internal requests set owner independently of arbitrary paths',()=>{const r=internalRequest('verifiedUid','/v2/sessions');assert.equal(r.headers.get('x-forja-owner'),'verifiedUid');assert.equal(new URL(r.url).host,'internal');});
test('structured AI responses accept object and JSON forms but reject truncation and fabricated citations',()=>{
  const evidence=[{id:'sleep-summary',text:'O singură sesiune de somn trimisă; nu cunoaștem rutina.',count:1}];
  const value={recommendations:[{category:'sleep',title:'Rutina de seară',why:'Un indiciu din jurnal.',next_step:'Confirmă dacă estimarea reflectă noaptea ta.',confidence:'low',evidence_ids:['sleep-summary']}]};
  for(const response of [value,JSON.stringify(value),'```json\n'+JSON.stringify(value)+'\n```'])
    assert.deepEqual(validateRecommendations(parsedJSON(response),evidence),[{...value.recommendations[0],why:evidence[0].text}]);
  assert.throws(()=>parsedJSON(JSON.stringify(value).slice(0,-1)));
  const inventedReason=structuredClone(value);inventedReason.recommendations[0].why='Persoana are o rutină constantă';inventedReason.recommendations[0].confidence='medium';
  const grounded=validateRecommendations(inventedReason,evidence)[0];assert.equal(grounded.why,evidence[0].text);assert.equal(grounded.confidence,'low');
  const fabricated=structuredClone(value);fabricated.recommendations[0].evidence_ids=['made-up'];
  assert.throws(()=>validateRecommendations(parsedJSON(fabricated),evidence));
  assert.deepEqual(recommendationFormat(evidence).json_schema.properties.recommendations.items.properties.evidence_ids.items.enum,['sleep-summary']);
});

test('automatic session retries are idempotent but cannot change category consent',async()=>{
  const f=fixture(),id=randomUUID(),body={session_id:id,consent:consent('location'),mode:'automatic'};
  assert.equal((await f.call('/v2/sessions','POST',body)).status,201);
  assert.equal((await f.call('/v2/sessions','POST',body)).status,200);
  assert.equal((await f.call('/v2/sessions','POST',{...body,consent:consent('audio')})).status,409);
  assert.equal((await f.call('/v2/sessions','POST',{...body,session_id:randomUUID(),mode:'hidden'})).status,400);
});
test('automatic metrics replace the bounded snapshot without growing storage accounting',async()=>{
  const f=fixture(),id=randomUUID();await f.call('/v2/sessions','POST',{session_id:id,consent:consent('location'),mode:'automatic'});
  for(let i=0;i<5;i++)assert.equal((await f.call('/v2/sessions/'+id+'/data','POST',phone())).status,201);
  const r=await(await f.call('/v2/sessions/'+id)).json();assert.equal(r.bytes,r.data.bytes);
  const manual=await session(f,'location');await f.call('/v2/sessions/'+manual+'/data','POST',phone());
  assert.equal((await f.call('/v2/sessions/'+manual+'/data','POST',phone())).status,409);
});
test('automatic rolling audio replaces old bytes and invalidates old item IDs',async()=>{
  const f=fixture(),id=randomUUID();await f.call('/v2/sessions','POST',{session_id:id,consent:consent('audio'),mode:'automatic'});
  const path='/v2/sessions/'+id;const r=await f.storage.get('session:'+id);r.created_at-=181000;await f.storage.put('session:'+id,r);
  let first;
  for(let i=0;i<30;i++) {const response=await f.call(path+'/items?kind=audio&sequence='+i%24,'POST',wav());assert.equal(response.status,201);if(i===0)first=await response.json();}
  const record=await(await f.call(path)).json();assert.equal(record.items.length,24);assert.equal(f.bucket.files.size,24);assert.equal(record.bytes,24*wav().length);
  assert.equal((await f.call(path+'/items/'+first.item_id)).status,404);
});
test('replaced selected files cannot retain AI observations from older content',async()=>{
  const f=fixture(),id=randomUUID();await f.call('/v2/sessions','POST',{session_id:id,consent:consent('files'),mode:'automatic'});
  const path='/v2/sessions/'+id;const item=await(await f.call(path+'/items?kind=file&sequence=0','POST',new Uint8Array([1]))).json();
  await f.call(path+'/observation','POST',{item_id:item.item_id,text:'old content',model:'test'});
  assert.equal((await f.call(path+'/items?kind=file&sequence=0','POST',new Uint8Array([2,3]))).status,201);
  const record=await(await f.call(path)).json();assert.equal(record.observations.length,0);assert.equal(record.bytes,2);assert.equal(f.bucket.files.size,1);
});

const ad = { title: 'O idee pentru weekend', body: 'Descoperă oferta atelierului.', sponsor: 'Atelier', cta: '', url: '', published: false };
test('campaign drafts, publication and withdrawal are isolated to the owner', async () => {
  const f=fixture(), other=fixture(), id=randomUUID();
  let r=await f.call('/internal/campaigns','POST',{id,revision:0,content:ad});
  assert.equal(r.status,201); assert.equal((await r.json()).label,'Publicitate');
  assert.equal((await(await f.call('/internal/app-feed')).json()).campaigns.length,0);
  assert.equal((await f.call('/internal/campaigns','POST',{id,revision:0,content:{...ad,published:true}})).status,409);
  assert.equal((await f.call('/internal/campaigns','POST',{id,revision:1,content:{...ad,published:true}})).status,200);
  assert.equal((await(await f.call('/internal/app-feed')).json()).campaigns[0].id,id);
  assert.equal((await f.call('/internal/app-feed','GET',undefined,'userB')).status,403);
  assert.equal((await(await other.call('/internal/app-feed','GET',undefined,'userB')).json()).campaigns.length,0);
  assert.equal((await f.call('/internal/campaigns/'+id+'?revision=1','DELETE')).status,409);
  await f.call('/internal/campaigns','POST',{id,revision:2,content:ad});
  assert.equal((await(await f.call('/internal/app-feed')).json()).campaigns.length,0);
  assert.equal((await f.call('/internal/campaigns/'+id+'?revision=3','DELETE')).status,200);
});
test('campaigns reject unsafe links, unknown targeting and missing sponsorship',()=>{
  for(const url of ['javascript:alert(1)','http://example.com','https://user:pass@example.com','https://localhost']) assert.throws(()=>campaignFields({...ad,url,cta:'Vezi'}));
  assert.throws(()=>campaignFields({...ad,sponsor:''}));
  assert.throws(()=>campaignFields({...ad,health_target:'sleep'}));
  assert.throws(()=>campaignFields({...ad,cta:'Vezi'}));
  assert.equal(campaignFields({...ad,url:'https://example.com/offer',cta:'Vezi'}).url,'https://example.com/offer');
});
test('AI ad drafts use only the creator brief and never auto-publish or read journals',async()=>{
  const requests=[], runs=[];
  const env={ INSIGHTS:{idFromName:name=>name,get:name=>({fetch:async request=>{requests.push([name,new URL(request.url).pathname,request.headers.get('x-forja-owner')]);return Response.json({ok:true});}})},
    AI:{run:async(model,input)=>{runs.push(input);return {response:{title:'Atelier de desen',body:'Descoperă atelierul de desen.'}};}} };
  const body={brief:'Atelier de desen în weekend.',sponsor:'Atelier'};
  const r=await handleInsights(new Request('https://test/insights/api/campaign-draft',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)}),env,'userA');
  assert.deepEqual(await r.json(),{draft:{title:'Atelier de desen',body:'Descoperă atelierul de desen.'},published:false});
  assert.deepEqual(requests,[['account:userA','/internal/ai-budget','userA']]);
  assert.deepEqual(JSON.parse(runs[0].messages[1].content),body);
});
test('server intake pause blocks all new data while reads, deletion and app content remain usable',async()=>{
  const f=fixture(),id=await session(f,'files');
  assert.equal((await f.call('/internal/intake','POST',{accepting:false,revision:0})).status,200);
  assert.equal((await f.call('/internal/intake','POST',{accepting:true,revision:0})).status,409);
  assert.equal((await f.call('/v2/sessions','POST',{session_id:randomUUID(),consent:consent('location')})).status,423);
  for(const suffix of ['data','items?kind=file&sequence=0','recording']) assert.equal((await f.call('/v2/sessions/'+id+'/'+suffix,'POST',new Uint8Array([1]))).status,423);
  assert.equal((await f.call('/v2/sessions')).status,200);
  assert.equal((await f.call('/internal/app-feed')).status,200);
  assert.equal((await f.call('/v2/sessions/'+id,'DELETE')).status,200);
  assert.equal((await f.call('/internal/intake','POST',{accepting:true,revision:1})).status,200);
  await session(f,'location');
});
const phoneStatus=(id=randomUUID())=>({id,grant:randomUUID(),name:'Telefon propriu',foreground:true,audio_allowed:true,state:'idle',handled_command:null});
const command=(revision=0)=>({id:randomUUID(),action:'start',minutes:30,revision});
test('web recording commands are owner-scoped, bounded, replay-safe and await phone acknowledgement',async()=>{
  const f=fixture(),p=phoneStatus(),c=command();
  assert.equal((await f.call('/internal/phones/'+p.id+'/command','POST',c)).status,404);
  assert.equal((await f.call('/internal/phone-sync','POST',p)).status,200);
  const path='/internal/phones/'+p.id+'/command';
  let r=await f.call(path,'POST',c);assert.equal(r.status,200);let out=await r.json();
  assert.equal(out.state,'idle');assert.equal(out.handled_command,null);assert.equal(out.command.stop_at-out.command.requested_at,30*60000);
  assert.equal(out.command.start_before-out.command.requested_at,30000);
  assert.equal((await f.call(path,'POST',c)).status,200);
  assert.equal((await f.call(path,'POST',{...c,minutes:60})).status,409);
  assert.equal((await f.call(path,'POST',command(1))).status,409);
  assert.equal((await f.call(path,'POST',c,'userB')).status,403);
  await f.call('/internal/phone-sync','POST',{...p,handled_command:c.id,state:'recording'});
  out=(await(await f.call('/internal/phones')).json()).phones[0];assert.equal(out.state,'recording');assert.equal(out.handled_command,c.id);
  const stale=await f.storage.get('phone:'+p.id);stale.seen_at=Date.now()-60000;await f.storage.put('phone:'+p.id,stale);
  const stop=await f.call(path,'POST',{...command(1),action:'stop'});assert.equal(stop.status,200);assert.equal((await stop.json()).state,'recording');
});
test('a microphone start is rejected without recent visible-phone consent and valid duration',async()=>{
  for(const changes of [{audio_allowed:false},{foreground:false},{state:'recording'}]) {
    const f=fixture(),p={...phoneStatus(),...changes};await f.call('/internal/phone-sync','POST',p);
    assert.ok([403,409].includes((await f.call('/internal/phones/'+p.id+'/command','POST',command())).status));
  }
  const f=fixture(),p=phoneStatus();await f.call('/internal/phone-sync','POST',p);
  for(const minutes of [0,61,2.5,'30']) assert.equal((await f.call('/internal/phones/'+p.id+'/command','POST',{...command(),minutes})).status,400);
  const old=await f.storage.get('phone:'+p.id);old.seen_at-=26000;await f.storage.put('phone:'+p.id,old);
  assert.equal((await f.call('/internal/phones/'+p.id+'/command','POST',command())).status,409);
  const other=fixture();assert.equal((await other.call('/internal/phones/'+p.id+'/command','POST',command(),'userB')).status,404);
});
test('phone collection grants remain separate from Android consent and resist stale web updates',async()=>{
  const f=fixture(),p=phoneStatus();await f.call('/internal/phone-sync','POST',p);
  const path='/internal/phones/'+p.id+'/collection';
  assert.equal((await f.call(path,'POST',{enabled:true,revision:0})).status,200);
  const synced=await(await f.call('/internal/phone-sync','POST',p)).json();assert.equal(synced.collection_enabled,true);assert.equal(synced.revision,1);
  assert.equal((await f.call(path,'POST',{enabled:false,revision:0})).status,409);
  assert.equal((await f.call(path,'POST',{enabled:false,revision:1})).status,200);
  assert.equal((await f.call('/internal/phone-sync','POST',{...p,collection_enabled:true})).status,400);
});
test('Worker forwarding replaces an untrusted account header on phone/content routes',async()=>{
  const f=fixture();const env={INSIGHTS:{idFromName:name=>{assert.equal(name,'account:userA');return name;},get:()=>f.object}};
  const r=await handleInsights(new Request('https://test/insights/api/phone-sync',{method:'POST',headers:{'content-type':'application/json','x-forja-owner':'userB'},body:JSON.stringify(phoneStatus())}),env,'userA');
  assert.equal(r.status,200);assert.equal(await f.storage.get('owner'),'userA');
});
const m4a=readFileSync(new URL('./fixtures/two-minutes-silence.m4a',import.meta.url));
async function recordingSession(f){const id=randomUUID();assert.equal((await f.call('/v2/sessions','POST',{session_id:id,consent:consent('audio'),mode:'recording'})).status,201);return id;}
const interval=()=>{const to=Date.now()-1000;return {'content-type':'audio/mp4','x-recorded-from':String(to-120000),'x-recorded-to':String(to)};};
test('one complete two-minute AAC recording survives byte-for-byte retrieval and idempotent retry',async()=>{
  const f=fixture(),id=await recordingSession(f),headers=interval(),path='/v2/sessions/'+id;
  let r=await f.call(path+'/recording','POST',m4a,'userA',headers);assert.equal(r.status,201);const receipt=await r.json();
  assert.ok(Math.abs(receipt.duration_ms-120000)<1000);assert.equal(receipt.media_type,'audio/mp4');assert.equal(receipt.bytes,m4a.length);
  r=await f.call(path+'/recording','POST',m4a,'userA',headers);assert.equal(r.status,200);assert.equal((await r.json()).item_id,receipt.item_id);
  const download=await f.call(path+'/items/'+receipt.item_id);assert.deepEqual(Buffer.from(await download.arrayBuffer()),m4a);
  assert.equal((await(await f.call(path)).json()).items.length,1);
  assert.equal((await f.call(path+'/recording','POST',m4a,'userA',{...headers,'x-recorded-to':String(Number(headers['x-recorded-to'])+1)})).status,409);
  assert.equal((await f.call(path+'/items?kind=audio&sequence=0','POST',wav())).status,400);
  assert.equal((await f.call(path+'/data','POST',{})).status,400);
});
test('M4A validation rejects false time ranges, video and external data references',()=>{
  const to=Date.now(),from=to-120000;
  assert.throws(()=>checkRecording(m4a,from,to-60000));assert.throws(()=>checkRecording(m4a,to-3603000,to));
  assert.throws(()=>checkRecording(m4a.subarray(0,m4a.length-20),from,to));
  const video=Buffer.from(m4a);video.write('vide',video.indexOf('soun'));assert.throws(()=>checkRecording(video,from,to));
  const external=Buffer.from(m4a);external.writeUInt32BE(0,external.indexOf('url ')+4);assert.throws(()=>checkRecording(external,from,to));
});
test('a slow recording upload does not lock phone controls or override intake pause',async()=>{
  const f=fixture(),id=await recordingSession(f);let release;
  const stream=new ReadableStream({start(controller){release=()=>{controller.enqueue(m4a);controller.close();};}});
  const pending=f.object.fetch(new Request('https://test/v2/sessions/'+id+'/recording',{method:'POST',headers:{'x-forja-owner':'userA',...interval()},body:stream,duplex:'half'}));
  assert.equal((await f.call('/internal/intake','POST',{accepting:false,revision:0})).status,200);
  assert.equal((await f.call('/v2/sessions/'+id+'/recording','POST',m4a,'userA',interval())).status,429);
  release();assert.equal((await pending).status,423);assert.equal(f.bucket.files.size,0);
});
test('deleting a session while recording bytes arrive cannot recreate its record',async()=>{
  const f=fixture(),id=await recordingSession(f);let release;
  const stream=new ReadableStream({start(controller){release=()=>{controller.enqueue(m4a);controller.close();};}});
  const pending=f.object.fetch(new Request('https://test/v2/sessions/'+id+'/recording',{method:'POST',headers:{'x-forja-owner':'userA',...interval()},body:stream,duplex:'half'}));
  assert.equal((await f.call('/v2/sessions/'+id,'DELETE')).status,200);release();assert.equal((await pending).status,404);assert.equal(f.bucket.files.size,0);
});

test('an automatic retry cannot recreate a recording explicitly deleted from the web',async()=>{
  const f=fixture(),id=await recordingSession(f);await f.call('/v2/sessions/'+id,'DELETE');
  assert.equal((await f.call('/v2/sessions','POST',{session_id:id,consent:consent('audio'),mode:'recording'})).status,410);
});

test('scheduled windows keep exact X/Y times and a new local grant invalidates old commands',async()=>{
  const f=fixture(),p=phoneStatus();await f.call('/internal/phone-sync','POST',p);
  const start_at=Date.now()+60000,stop_at=start_at+15*60000,body={...command(),minutes:15,start_at,stop_at};
  const path='/internal/phones/'+p.id+'/command';const response=await f.call(path,'POST',body);assert.equal(response.status,200);
  const saved=await response.json();assert.equal(saved.command.start_at,start_at);assert.equal(saved.command.stop_at,stop_at);
  assert.equal((await f.call(path,'POST',body)).status,200);
  assert.equal((await f.call(path,'POST',{...body,stop_at:stop_at+60000})).status,409);
  const renewed=await(await f.call('/internal/phone-sync','POST',{...p,grant:randomUUID()})).json();
  assert.equal(renewed.command,null);assert.equal(renewed.collection_enabled,false);assert.equal(renewed.revision,2);
});

test('phone upload status is bounded, account-scoped and remains compatible with v41 phones',async()=>{
  const f=fixture(),p=phoneStatus();
  const recording_transfer={id:randomUUID(),state:'pending',from:Date.now()-600000,duration_ms:600000,pending_count:1,message:'Salvată pe telefon. Trimiterea se reia automat.'};
  const body={...p,recording_transfer,audio_status:'Înregistrare încheiată.'};
  assert.equal((await f.call('/internal/phone-sync','POST',body)).status,200);
  const out=(await(await f.call('/internal/phones')).json()).phones[0];assert.deepEqual(out.recording_transfer,recording_transfer);
  assert.equal((await f.call('/internal/phone-sync','POST',body,'userB')).status,403);
  for(const changes of [{pending_count:6},{duration_ms:86400000},{message:'x'.repeat(257)},{state:'hidden'},{extra:'private'}])
    assert.equal((await f.call('/internal/phone-sync','POST',{...body,recording_transfer:{...recording_transfer,...changes}})).status,400);
  assert.equal((await f.call('/internal/phone-sync','POST',{...body,audio_status:'x'.repeat(257)})).status,400);
  const old=await(await f.call('/internal/phone-sync','POST',p)).json();assert.equal(old.recording_transfer,null);assert.equal(old.audio_status,'');
});

test('a recording held during intake pause can be uploaded and played after intake resumes',async()=>{
  const f=fixture(),id=await recordingSession(f),headers=interval(),path='/v2/sessions/'+id;
  await f.call('/internal/intake','POST',{accepting:false,revision:0});
  assert.equal((await f.call(path+'/recording','POST',m4a,'userA',headers)).status,423);assert.equal(f.bucket.files.size,0);
  await f.call('/internal/intake','POST',{accepting:true,revision:1});
  const response=await f.call(path+'/recording','POST',m4a,'userA',headers);assert.equal(response.status,201);
  const item=await response.json();const received=(await(await f.call('/v2/sessions')).json()).sessions[0];
  assert.equal(received.items.length,1);assert.equal(received.items[0].item_id,item.item_id);
  assert.deepEqual(Buffer.from(await(await f.call(path+'/items/'+item.item_id)).arrayBuffer()),m4a);
});

// Temporary photo/document copies: separate from microphone sessions.
const fileBytes=new TextEncoder().encode('Document pentru organizare, numai în contul propriu.');
const fileHash=async b=>[...new Uint8Array(await crypto.subtle.digest('SHA-256',b))].map(v=>v.toString(16).padStart(2,'0')).join('');
async function fileDevice(f,selection={enabled:true,photos:true,files:true}){const id=randomUUID();assert.equal((await f.call('/v2/files/settings/'+id,'POST',selection)).status,200);return id;}
async function putFile(f,device,id=randomUUID(),bytes=fileBytes,extra={}){
 return f.call('/v2/files/'+id,'PUT',bytes,'userA',{'content-type':'application/octet-stream','x-device-id':device,'x-file-kind':'file','x-file-name':'Material%20didactic.txt','x-media-type':'text/plain','x-file-sha256':await fileHash(bytes),...extra});
}
test('file sync is off until explicitly activated and sources are enforced',async()=>{
 const f=fixture(),device=randomUUID();assert.equal((await putFile(f,device)).status,403);
 assert.equal((await f.call('/v2/files/settings/'+device,'GET')).status,200);
 await f.call('/v2/files/settings/'+device,'POST',{enabled:true,photos:true,files:false});
 assert.equal((await putFile(f,device)).status,403);
 assert.equal((await putFile(f,device,randomUUID(),new Uint8Array([255,216,255,217]),{'x-file-kind':'photo','x-media-type':'image/jpeg'})).status,201);
});
test('files preserve exact bytes and retries or online folder edits never extend expiry',async()=>{
 const f=fixture(),device=await fileDevice(f),id=randomUUID();
 const first=await(await putFile(f,device,id)).json();assert.equal(first.expires_at-first.received_at,86400000);
 assert.equal(first.preview,'text');assert.equal(first.name,'Material didactic.txt');assert.equal(first.key,undefined);
 const body=await f.call('/v2/files/'+id);assert.equal(body.headers.get('cache-control'),'private, no-store, max-age=0');assert.equal(body.headers.get('content-type'),'application/octet-stream');assert.deepEqual(new Uint8Array(await body.arrayBuffer()),fileBytes);
 const edit=await(await f.call('/v2/files/'+id,'PATCH',{folder:'Educație/Grupa mare'})).json();assert.equal(edit.expires_at,first.expires_at);
 const retry=await putFile(f,device,id);assert.equal(retry.status,200);const again=await retry.json();assert.equal(again.expires_at,first.expires_at);assert.equal(again.folder,'Educație/Grupa mare');
});
test('expired file copies and thumbnails are denied and removed without a client request',async()=>{
 const f=fixture(),device=await fileDevice(f),id=randomUUID(),bytes=new Uint8Array([255,216,255,217]);
 await putFile(f,device,id,bytes,{'x-file-kind':'photo','x-media-type':'image/jpeg'});
 assert.equal((await f.call('/v2/files/'+id+'/thumbnail','PUT',bytes,'userA',{'x-device-id':device})).status,200);
 const r=await f.storage.get('cloud-file:'+id);r.expires_at=Date.now()-1;await f.storage.put('cloud-file:'+id,r);await f.object.alarm();
 assert.equal(f.bucket.files.size,0);assert.equal((await f.call('/v2/files/'+id)).status,404);assert.equal((await f.call('/v2/files/'+id+'/thumbnail')).status,404);
 assert.equal((await putFile(f,device,id,bytes,{'x-file-kind':'photo','x-media-type':'image/jpeg'})).status,410);
});
test('web deletion blocks delayed retry and affects only the online copy',async()=>{
 const f=fixture(),device=await fileDevice(f),id=randomUUID();await putFile(f,device,id);
 assert.equal((await(await f.call('/v2/files/'+id,'DELETE')).json()).phone_original_unchanged,true);
 assert.equal((await putFile(f,device,id)).status,410);assert.equal(f.bucket.files.size,0);
});
test('account isolation also covers thumbnails, consent settings and file metadata',async()=>{
 const bucket=new Bucket(),a=fixture(bucket),b=fixture(bucket),device=await fileDevice(a),id=randomUUID();await putFile(a,device,id);
 for(const path of ['/v2/files/'+id,'/v2/files/'+id+'/thumbnail'])assert.equal((await b.call(path,'GET',undefined,'userB')).status,404);
 assert.equal((await a.call('/v2/files','GET',undefined,'userB')).status,403);
 assert.equal((await(await b.call('/v2/files/settings/'+device,'GET',undefined,'userB')).json()).enabled,false);
});
test('checksum, size, metadata and intake gates reject unsafe or incomplete transfers',async()=>{
 const f=fixture(),device=await fileDevice(f);
 assert.equal((await putFile(f,device,randomUUID(),fileBytes,{'x-file-sha256':'0'.repeat(64)})).status,422);
 assert.equal((await putFile(f,device,randomUUID(),fileBytes,{'x-file-name':'a%0Ab'})).status,400);
 assert.equal((await putFile(f,device,randomUUID(),fileBytes,{'content-length':String(25*1024*1024+1)})).status,413);
 await f.call('/internal/intake','POST',{accepting:false,revision:0});assert.equal((await putFile(f,device)).status,423);
 assert.equal(f.bucket.files.size,0);
});
test('listing supports search and pagination without exposing storage paths',async()=>{
 const f=fixture(),device=await fileDevice(f);
 for(let i=0;i<26;i++)assert.equal((await putFile(f,device,randomUUID(),fileBytes,{'x-file-name':`Plan-${i}.txt`})).status,201);
 const first=await(await f.call('/v2/files')).json();assert.equal(first.items.length,24);assert.equal(first.total,26);assert.ok(first.next_cursor);assert.equal(first.items[0].key,undefined);
 const second=await(await f.call('/v2/files?after='+first.next_cursor)).json();assert.equal(second.items.length,2);assert.equal(second.next_cursor,null);
 const search=await(await f.call('/v2/files?q=Plan-25')).json();assert.equal(search.total,1);
});
test('an interrupted R2 write can retry the same ID and orphan staging is expired',async()=>{
 const f=fixture(),device=await fileDevice(f),id=randomUUID();const put=f.bucket.put.bind(f.bucket);let once=true;
 f.bucket.put=async(...args)=>{if(once){once=false;throw Error('temporary R2 outage');}return put(...args);};
 assert.equal((await putFile(f,device,id)).status,500);assert.ok(await f.storage.get('file-staging:'+id));
 assert.equal((await putFile(f,device,id)).status,201);assert.equal(await f.storage.get('file-staging:'+id),undefined);
 const orphan=randomUUID(),key='_insights/userA/files/'+orphan;await put(key,fileBytes);await f.storage.put('file-staging:'+orphan,{key,expires_at:Date.now()-1});
 await f.object.alarm();assert.equal(f.bucket.files.has(key),false);assert.ok(await f.storage.get('file-gone:'+orphan));
});
test('retention alarm schedules another attempt after storage deletion failure',async()=>{
 const f=fixture(),device=await fileDevice(f),id=randomUUID();await putFile(f,device,id);const r=await f.storage.get('cloud-file:'+id);r.expires_at=Date.now()-1;await f.storage.put('cloud-file:'+id,r);
 const remove=f.bucket.delete.bind(f.bucket);f.bucket.delete=async()=>{throw Error('unavailable');};await assert.rejects(f.object.alarm());assert.ok(f.storage.alarm>Date.now());
 f.bucket.delete=remove;await f.object.alarm();assert.equal(f.bucket.files.size,0);
});

test('an explicitly armed background phone accepts Start without pretending to be foreground',async()=>{
  const f=fixture(),p={...phoneStatus(),foreground:false,audio_ready:true,audio_session:randomUUID()};
  const synced=await(await f.call('/internal/phone-sync','POST',p)).json();
  assert.equal(synced.background_audio,1);assert.equal(synced.foreground,false);assert.equal(synced.audio_ready,true);
  const c=command(),r=await f.call('/internal/phones/'+p.id+'/command','POST',c);
  assert.equal(r.status,200);const waiting=await r.json();assert.equal(waiting.state,'idle');assert.equal(waiting.handled_command,null);
  const acknowledged=await(await f.call('/internal/phone-sync','POST',{...p,state:'recording',handled_command:c.id})).json();
  assert.equal(acknowledged.state,'recording');assert.equal(acknowledged.handled_command,c.id);
});
test('v19 disabled readiness rejects Start even when the app is visible',async()=>{
  for(const foreground of [true,false]){
    const f=fixture(),p={...phoneStatus(),foreground,audio_ready:false};await f.call('/internal/phone-sync','POST',p);
    assert.equal((await f.call('/internal/phones/'+p.id+'/command','POST',command())).status,409);
  }
});
test('readiness is strictly boolean and requires audio permission',async()=>{
  for(const audio_ready of ['true',1,null,{}]){const f=fixture();assert.equal((await f.call('/internal/phone-sync','POST',{...phoneStatus(),audio_ready})).status,400);}
  const f=fixture();assert.equal((await f.call('/internal/phone-sync','POST',{...phoneStatus(),audio_allowed:false,audio_ready:true})).status,400);
});
test('readiness expires with the heartbeat and cannot address another owner',async()=>{
  const f=fixture(),p={...phoneStatus(),foreground:false,audio_ready:true,audio_session:randomUUID()};await f.call('/internal/phone-sync','POST',p);
  const path='/internal/phones/'+p.id+'/command',old=await f.storage.get('phone:'+p.id);old.seen_at=Date.now()-25000;await f.storage.put('phone:'+p.id,old);
  assert.equal((await f.call(path,'POST',command())).status,409);
  assert.equal((await f.call(path,'POST',command(),'userB')).status,403);
  assert.equal((await f.call(path,'POST',{...command(),action:'stop'})).status,200);
});
test('disarming drops readiness and a new pairing discards previous scheduled commands',async()=>{
  const f=fixture(),p={...phoneStatus(),foreground:false,audio_ready:true,audio_session:randomUUID()};await f.call('/internal/phone-sync','POST',p);
  const start_at=Date.now()+60000,path='/internal/phones/'+p.id+'/command';
  assert.equal((await f.call(path,'POST',{...command(),minutes:5,start_at,stop_at:start_at+300000})).status,200);
  const off=await(await f.call('/internal/phone-sync','POST',{...p,audio_ready:false,audio_session:null})).json();assert.equal(off.audio_ready,false);
  const renew=await(await f.call('/internal/phone-sync','POST',{...p,grant:randomUUID(),audio_ready:false,audio_session:null})).json();assert.equal(renew.command,null);assert.equal(renew.audio_ready,false);
  assert.equal((await f.call(path,'POST',command(renew.revision))).status,409);
});
test('changing the armed session cancels queued commands and rejects stale browser revisions',async()=>{
  const f=fixture(),p={...phoneStatus(),foreground:false,audio_ready:true,audio_session:randomUUID()};
  await f.call('/internal/phone-sync','POST',p);const path='/internal/phones/'+p.id+'/command';
  const sent=await(await f.call(path,'POST',command())).json();assert.equal(sent.command.audio_session,p.audio_session);
  const rearmed=await(await f.call('/internal/phone-sync','POST',{...p,audio_session:randomUUID()})).json();
  assert.equal(rearmed.command,null);assert.equal(rearmed.revision,sent.revision+1);
  assert.equal((await f.call(path,'POST',command(sent.revision))).status,409);
  assert.equal((await f.call(path,'POST',command(rearmed.revision))).status,200);
});
test('readiness requires a valid session and inactive phones cannot retain a session',async()=>{
  for(const changes of [{audio_ready:true},{audio_ready:true,audio_session:'bad'},{audio_ready:true,audio_session:12},{audio_ready:false,audio_session:randomUUID()}]){
    const f=fixture();assert.equal((await f.call('/internal/phone-sync','POST',{...phoneStatus(),...changes})).status,400);
  }
});

test('unified recording journals preserve null metrics and never imply measured sleep quality or phases',async()=>{
 const data=await loadJournals('testUid','token',async(url,opts)=>{const kind=JSON.parse(opts.body).structuredQuery.from[0].collectionId;return Response.json(kind==='sleep'?[{document:{name:'users/testUid/sleep/stable',fields:{startAt:{integerValue:'1000'},endAt:{integerValue:'3601000'},score:{nullValue:null},deepMin:{nullValue:null},lightMin:{nullValue:null},remMin:{nullValue:null},measurement:{stringValue:'recording_interval'},recordingId:{stringValue:'stable-recording'}}}}]:[]);},1000000000);
 const row=data.sleep.records[0];assert.equal(row.score,null);assert.equal(row.deepMin,null);assert.equal(row.remMin,null);assert.equal(row.measurement,'recording_interval');assert.equal(row.recordingId,'stable-recording');
 const evidence=buildEvidence(data,[]),recording=evidence.find(e=>e.id==='recording-interval-summary');assert(recording);assert.match(recording.text,/NU durata somnului/);assert.match(recording.text,/necunoscute/);assert.equal(evidence.find(e=>e.id==='sleep-summary'),undefined);
 data.sleep.records.push({startAt:2000,endAt:null,measurement:'recording_interval',score:-1});assert.equal(buildEvidence(data,[]).find(e=>e.id==='recording-interval-summary').count,1);
});
