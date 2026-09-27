import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {randomUUID} from 'node:crypto';
import {InsightsAccount} from './insights-store.mjs';
const wav=readFileSync(new URL('./fixtures/two-minutes-silence.m4a',import.meta.url));
class Storage{values=new Map();alarm=null;async get(k){return structuredClone(this.values.get(k));}async put(k,v){if(typeof k==='object')for(const [a,b]of Object.entries(k))this.values.set(a,structuredClone(b));else this.values.set(k,structuredClone(v));}async list({prefix}){return new Map([...this.values].filter(([k])=>k.startsWith(prefix)).map(([k,v])=>[k,structuredClone(v)]));}async delete(k){if(Array.isArray(k)&&k.length>128)throw Error('Storage delete batch exceeds128');for(const x of Array.isArray(k)?k:[k])this.values.delete(x);}async setAlarm(v){this.alarm=v;}async deleteAlarm(){this.alarm=null;}}
class Bucket{files=new Map();async put(k,v){this.files.set(k,new Uint8Array(v));}async get(k){return this.files.has(k)?{body:this.files.get(k)}:null;}async list({prefix}){return{objects:[...this.files.keys()].filter(k=>k.startsWith(prefix)).map(key=>({key})),truncated:false};}async delete(keys){for(const k of Array.isArray(keys)?keys:[keys])this.files.delete(k);}}
function fixture(){const storage=new Storage(),bucket=new Bucket();let queue=Promise.resolve();const account=new InsightsAccount({storage,blockConcurrencyWhile(fn){const next=queue.then(fn);queue=next.catch(()=>{});return next;}},{RECORDS:bucket});const call=(path,method='GET',body,extra={},uid='owner')=>account.fetch(new Request('https://test'+path,{method,headers:{'x-forja-owner':uid,'content-type':'application/json',...extra},...(body===undefined?{}:{body:body instanceof Uint8Array?body:JSON.stringify(body)})}));return{call,storage,bucket,account};}
const consent={audio:true,location:false,app_usage:false,photos:false,files:false};
const phone=()=>({id:randomUUID(),grant:randomUUID(),name:'Phone',foreground:false,audio_allowed:true,audio_ready:true,audio_session:randomUUID(),state:'idle',handled_command:null,sleep_capable:true,sleep_analysis_allowed:true});
async function setup(f,{analysis=true,started=Date.now()-180000}={}){const p=phone();p.sleep_analysis_allowed=analysis;assert.equal((await f.call('/internal/phone-sync','POST',p)).status,200);const s={id:randomUUID(),device_id:p.id,started_at:started,planned_stop_at:started+12*3600000,analysis_consent:analysis};assert.equal((await f.call('/v2/sleep/sessions','POST',s)).status,201);return{p,s};}
async function chunk(f,s,{id=randomUUID(),from=s.started_at+1000}={}){const root='/v2/sleep/sessions/'+s.id;assert.equal((await f.call(root+'/reserve','POST',{recording_session_id:id})).status,201);assert.equal((await f.call('/v2/sessions','POST',{session_id:id,consent,mode:'recording'})).status,201);const r=await f.call('/v2/sessions/'+id+'/recording','POST',wav,{'content-type':'audio/mp4','x-recorded-from':String(from),'x-recorded-to':String(from+120000)});assert.equal(r.status,201);return{id,receipt:await r.json(),from};}
const result={status:'complete',transcript:'Ne vedem mâine.',transcript_status:'unverified',segments:[{id:'s1',text:'Ne vedem mâine.'}],topics:[],topics_status:'complete',limitations:['asr_may_be_inaccurate'],snoring:{status:'unavailable',reason:'audio_event_classifier_unavailable'},models:{transcription:'test'}};
test('sleep chunks reserve, upload, attach, analyze and finish idempotently with measured coverage',async()=>{const f=fixture(),{s}=await setup(f),c=await chunk(f,s),path='/v2/sleep/sessions/'+s.id;
 assert.equal((await f.call('/v2/sleep/sessions','POST',s)).status,200);
 for(let i=0;i<2;i++)assert.ok((await f.call(path+'/chunks','POST',{recording_session_id:c.id})).ok);
 const lease=await(await f.call(`/internal/sleep/sessions/${s.id}/claim/${c.id}`,'POST',{})).json();assert.equal(lease.claimed,true);
 assert.equal((await(await f.call(`/internal/sleep/sessions/${s.id}/claim/${c.id}`,'POST',{})).json()).claimed,false);
 assert.equal((await f.call(`/internal/sleep/sessions/${s.id}/result/${c.id}`,'POST',{lease:lease.lease,result})).status,200);
 assert.equal((await(await f.call(`/internal/sleep/sessions/${s.id}/claim/${c.id}`,'POST',{})).json()).claimed,false);
 const end=c.from+120000;assert.equal((await f.call(path+'/finish','POST',{ended_at:end})).status,200);assert.equal((await f.call(path+'/finish','POST',{ended_at:end})).status,200);assert.equal((await f.call(path+'/finish','POST',{ended_at:end+1})).status,409);
 const report=await(await f.call(path)).json();assert.equal(report.state,'complete');assert.equal(report.chunk_count,1);assert.equal(report.recorded_ms,120000);assert.equal(report.analyzed_ms,120000);assert.equal(report.chunks[0].result.transcript,result.transcript);assert.equal(report.snoring.status,'unavailable');assert.equal(report.chunks[0].lease,undefined);
});
test('sleep source, consent, ownership, time bounds, overlaps and deletion are enforced',async()=>{const f=fixture(),{s}=await setup(f),c=await chunk(f,s),path='/v2/sleep/sessions/'+s.id;
 assert.equal((await f.call(path,'GET',undefined,{},'intruder')).status,403);
 assert.equal((await f.call(path+'/chunks','POST',{recording_session_id:randomUUID()})).status,409);
 assert.equal((await f.call(path+'/chunks','POST',{recording_session_id:c.id})).status,201);
 const overlap=await chunk(f,s,{from:c.from+1000});assert.equal((await f.call(path+'/chunks','POST',{recording_session_id:overlap.id})).status,409);
 const lease=await(await f.call(`/internal/sleep/sessions/${s.id}/claim/${c.id}`,'POST',{})).json();
 assert.equal((await f.call(path,'DELETE')).status,200);assert.equal((await f.call(`/internal/sleep/sessions/${s.id}/result/${c.id}`,'POST',{lease:lease.lease,result})).status,404);assert.equal((await f.call('/v2/sleep/sessions','POST',s)).status,410);assert.equal((await f.call('/v2/sessions/'+c.id)).status,200);
});
test('AI revocation during inference discards result and disabled analysis never claims audio',async()=>{const f=fixture(),{s,p}=await setup(f),c=await chunk(f,s);await f.call('/v2/sleep/sessions/'+s.id+'/chunks','POST',{recording_session_id:c.id});const path=`/internal/sleep/sessions/${s.id}`;
 const lease=await(await f.call(path+'/claim/'+c.id,'POST',{})).json();await f.call('/internal/phone-sync','POST',{...p,sleep_analysis_allowed:false});
 const out=await(await f.call(path+'/result/'+c.id,'POST',{lease:lease.lease,result})).json();assert.equal(out.state,'skipped');assert.equal(out.result,null);
 const f2=fixture(),setup2=await setup(f2,{analysis:false}),c2=await chunk(f2,setup2.s);await f2.call('/v2/sleep/sessions/'+setup2.s.id+'/chunks','POST',{recording_session_id:c2.id});const no=await(await f2.call(`/internal/sleep/sessions/${setup2.s.id}/claim/${c2.id}`,'POST',{})).json();assert.equal(no.claimed,false);assert.equal(no.chunk.state,'skipped');
});
test('sleep pool permits more than twenty chunks while preserving generic session cap and total reservation bound',async()=>{const f=fixture(),{s}=await setup(f);for(let i=0;i<23;i++){const id=randomUUID();assert.equal((await f.call(`/v2/sleep/sessions/${s.id}/reserve`,'POST',{recording_session_id:id})).status,201);assert.equal((await f.call('/v2/sessions','POST',{session_id:id,consent,mode:'recording'})).status,201);}
 for(let i=0;i<20;i++)assert.equal((await f.call('/v2/sessions','POST',{session_id:randomUUID(),consent,mode:'recording'})).status,201);
 assert.equal((await f.call('/v2/sessions','POST',{session_id:randomUUID(),consent,mode:'recording'})).status,429);
 for(let i=23;i<365;i++)await f.storage.put('sleep-reservation:'+randomUUID(),{sleep_id:s.id,expires_at:Date.now()+86400000});assert.equal((await f.call(`/v2/sleep/sessions/${s.id}/reserve`,'POST',{recording_session_id:randomUUID()})).status,429);
});
test('future nap windows are editable, 12h sleep command is bounded and capture awaits phone confirmation',async()=>{const f=fixture(),p=phone();await f.call('/internal/phone-sync','POST',p);const path='/internal/phones/'+p.id+'/command',now=Date.now(),first={id:randomUUID(),action:'start',purpose:'sleep',sleep_id:randomUUID(),minutes:90,revision:0,start_at:now+3600000,stop_at:now+9000000};
 let r=await f.call(path,'POST',first);assert.equal(r.status,200);let out=await r.json();assert.equal(out.state,'idle');assert.equal(out.command.chunk_minutes,2);assert.equal(out.command.stop_at,first.stop_at);
 assert.equal((await f.call(path,'POST',first)).status,200);assert.equal((await f.call(path,'POST',{...first,minutes:91})).status,409);
 const edit={...first,id:randomUUID(),sleep_id:randomUUID(),revision:1,replace_command:first.id,start_at:now+1800000,stop_at:now+7200000};r=await f.call(path,'POST',edit);assert.equal(r.status,200);out=await r.json();assert.equal(out.command.id,edit.id);
 await f.call('/internal/phone-sync','POST',{...p,audio_ready:false,audio_session:null});const off=await(await f.call('/internal/phones')).json();assert.equal(off.phones[0].command,null);
 assert.equal((await f.call(path,'POST',{id:randomUUID(),action:'start',purpose:'sleep',sleep_id:randomUUID(),minutes:720,revision:3})).status,409);
 await f.call('/internal/phone-sync','POST',{...p,audio_session:randomUUID()});const current=(await(await f.call('/internal/phones')).json()).phones[0];assert.equal((await f.call(path,'POST',{id:randomUUID(),action:'start',purpose:'sleep',sleep_id:randomUUID(),minutes:721,revision:current.revision})).status,400);
 assert.equal((await f.call(path,'POST',{id:randomUUID(),action:'start',purpose:'sleep',sleep_id:randomUUID(),minutes:720,revision:current.revision})).status,200);
});
test('sleep expiry removes report/leases/reservations without resurrecting deleted analysis',async()=>{const f=fixture(),{s}=await setup(f),c=await chunk(f,s);await f.call('/v2/sleep/sessions/'+s.id+'/chunks','POST',{recording_session_id:c.id});const row=await f.storage.get('sleep:'+s.id);row.expires_at=Date.now()-1;await f.storage.put('sleep:'+s.id,row);await f.account.alarm();assert.equal((await f.storage.list({prefix:'sleep:'})).size,0);assert.equal((await f.storage.list({prefix:'sleep-chunk:'})).size,0);assert.equal((await f.storage.list({prefix:'sleep-reservation:'})).size,0);});

test('edge inference releases the account lock, duplicates do not rerun AI, and report saves real ASR',async()=>{
 const {handleSleepAPI}=await import('./sleep-api.mjs');const f=fixture(),{s}=await setup(f),c=await chunk(f,s);let release,started;const entered=new Promise(r=>started=r),paused=new Promise(r=>release=r);let calls=0;
 const env={INSIGHTS:{idFromName:n=>n,get:()=>f.account},AI:{run:async(model)=>{calls++;if(model.includes('whisper')){started();await paused;return{text:'Da. Ne vedem mâine.'};}return{response:{topics:[]}};}}};
 const request=()=>new Request('https://test/v2/sleep/sessions/'+s.id+'/chunks',{method:'POST',headers:{'content-type':'application/json','x-forja-owner':'forged'},body:JSON.stringify({recording_session_id:c.id})});
 const pending=handleSleepAPI(request(),env,'owner');await entered;
 const duplicate=await handleSleepAPI(request(),env,'owner');assert.equal(duplicate.status,202);assert.equal(calls,1);
 assert.equal((await f.call('/internal/phones')).status,200,'AI must not block device controls');release();
 const complete=await pending;assert.equal(complete.status,200);const out=await complete.json();assert.equal(out.result.transcript,'Da. Ne vedem mâine.');assert.equal(out.result.transcript_status,'unverified');
 assert.equal((await handleSleepAPI(request(),env,'owner')).status,200);assert.equal(calls,2,'one ASR and one topics call only');
});
test('edge provider failure persists failure, then retry uses the same uploaded chunk',async()=>{
 const {handleSleepAPI}=await import('./sleep-api.mjs');const f=fixture(),{s}=await setup(f),c=await chunk(f,s);let failing=true;
 const env={INSIGHTS:{idFromName:n=>n,get:()=>f.account},AI:{run:async(model)=>{if(failing)throw Error('provider down');return model.includes('whisper')?{text:''}:{response:{topics:[]}};}}};
 const request=()=>new Request('https://test/v2/sleep/sessions/'+s.id+'/chunks',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({recording_session_id:c.id})});
 const failed=await handleSleepAPI(request(),env,'owner');assert.equal(failed.status,503);assert.equal((await failed.json()).result.transcript,null);
 failing=false;const retried=await handleSleepAPI(request(),env,'owner');assert.equal(retried.status,200);const out=await retried.json();assert.equal(out.result.transcript,'');assert.equal(out.attempts,2);assert.equal(f.bucket.files.size,1);
});

test('on-device acoustic observations preserve actual coverage, unavailable differs from no detections',async()=>{
 const f=fixture(),{s}=await setup(f),c=await chunk(f,s),path='/v2/sleep/sessions/'+s.id;
 const acoustic={status:'complete',model:'yamnet',model_version:'yamnet-audioset-1',model_sha256:'a'.repeat(64),analyzed_ms:120000,analyzed_ranges:[{start_ms:0,end_ms:120000}],events:[{start_ms:1000,end_ms:3000,kind:'possible_snoring',score:.71}]};
 assert.equal((await f.call(path+'/chunks','POST',{recording_session_id:c.id,acoustic})).status,201);
 let report=await(await f.call(path)).json();assert.equal(report.snoring.status,'complete');assert.equal(report.snoring.analyzed_ms,120000);assert.equal(report.snoring.possible_intervals,1);assert.equal(report.snoring.possible_ms,2000);assert.equal(report.snoring.source,'on_device');
 assert.equal((await f.call(path+'/chunks','POST',{recording_session_id:c.id,acoustic})).status,200);
 assert.equal((await f.call(path+'/chunks','POST',{recording_session_id:c.id,acoustic:{...acoustic,events:[]}})).status,409);
 const f2=fixture(),s2=(await setup(f2)).s,c2=await chunk(f2,s2),path2='/v2/sleep/sessions/'+s2.id;
 const unavailable={status:'unavailable',model:'yamnet',model_version:'yamnet-audioset-1',analyzed_ms:0,analyzed_ranges:[],events:[],reason:'model_unavailable'};
 await f2.call(path2+'/chunks','POST',{recording_session_id:c2.id,acoustic:unavailable});report=await(await f2.call(path2)).json();assert.equal(report.snoring.possible_intervals,null);assert.equal(report.snoring.status,'unavailable');
 await f2.call(path2+'/chunks','POST',{recording_session_id:c2.id,acoustic:{...acoustic,events:[]}});report=await(await f2.call(path2)).json();assert.equal(report.snoring.possible_intervals,0);assert.equal(report.snoring.status,'complete');
});
test('acoustic metadata cannot claim observations outside decoded coverage or invalid model scores',async()=>{
 const {validateAcoustic}=await import('./sleep-acoustic.mjs');const base={status:'complete',model:'yamnet',model_version:'yamnet-audioset-1',model_sha256:'b'.repeat(64),analyzed_ms:1000,analyzed_ranges:[{start_ms:0,end_ms:1000}],events:[]};
 for(const change of [{analyzed_ms:120000},{analyzed_ranges:[{start_ms:0,end_ms:1000},{start_ms:500,end_ms:1500}],analyzed_ms:2000},{events:[{start_ms:500,end_ms:1500,kind:'possible_snoring',score:.8}]},{events:[{start_ms:0,end_ms:500,kind:'possible_snoring',score:1.2}]},{events:[{start_ms:0,end_ms:500,kind:'apnea',score:.8}]},{model:'unverified'},{model_sha256:''}])assert.throws(()=>validateAcoustic({...base,...change},120000));
 assert.throws(()=>validateAcoustic({...base,status:'unavailable',reason:'decode_failed'},120000));
});

test('a full night of365 report chunks deletes and expires using supported storage batches',async()=>{
 for(const expired of [false,true]){const f=fixture(),{s}=await setup(f);for(let i=0;i<365;i++)await f.storage.put('sleep-chunk:'+s.id+':'+randomUUID(),{id:randomUUID()});
 if(expired){const row=await f.storage.get('sleep:'+s.id);row.expires_at=Date.now()-1;await f.storage.put('sleep:'+s.id,row);await f.account.alarm();}else assert.equal((await f.call('/v2/sleep/sessions/'+s.id,'DELETE')).status,200);
 assert.equal((await f.storage.list({prefix:'sleep-chunk:'})).size,0);assert.equal(await f.storage.get('sleep:'+s.id),undefined);}
});
test('an unreserved generic recording cannot bypass the bounded sleep pool by later attachment',async()=>{
 const f=fixture(),{s}=await setup(f),id=randomUUID();await f.call('/v2/sessions','POST',{session_id:id,consent,mode:'recording'});assert.equal((await f.call('/v2/sessions/'+id+'/recording','POST',wav,{'content-type':'audio/mp4','x-recorded-from':String(s.started_at+1000),'x-recorded-to':String(s.started_at+121000)})).status,201);
 assert.equal((await f.call('/v2/sleep/sessions/'+s.id+'/chunks','POST',{recording_session_id:id})).status,409);assert.equal((await f.call('/v2/sleep/sessions/'+s.id+'/reserve','POST',{recording_session_id:id})).status,409);
});

test('still-retained finished sessions accept exact registration retry after24h and reject inconsistent end',async()=>{
 const f=fixture(),{s}=await setup(f),c=await chunk(f,s),path='/v2/sleep/sessions/'+s.id;await f.call(path+'/chunks','POST',{recording_session_id:c.id});assert.equal((await f.call(path+'/finish','POST',{ended_at:s.started_at+1000})).status,400);
 const old=await f.storage.get('sleep:'+s.id),aged={...old,started_at:Date.now()-25*3600000,planned_stop_at:Date.now()-13*3600000,ended_at:Date.now()-13*3600000,expires_at:Date.now()+3600000};await f.storage.put('sleep:'+s.id,aged);
 const replay={id:s.id,device_id:s.device_id,started_at:aged.started_at,planned_stop_at:aged.planned_stop_at,analysis_consent:s.analysis_consent};assert.equal((await f.call('/v2/sleep/sessions','POST',replay)).status,200);
});
test('expired inference lease becomes visibly retryable and a new lease invalidates old results',async()=>{
 const f=fixture(),{s}=await setup(f),c=await chunk(f,s),path='/v2/sleep/sessions/'+s.id;await f.call(path+'/chunks','POST',{recording_session_id:c.id});const first=await(await f.call(`/internal/sleep/sessions/${s.id}/claim/${c.id}`,'POST',{})).json();
 const key='sleep-chunk:'+s.id+':'+c.id,row=await f.storage.get(key);row.lease.until=Date.now()-1;await f.storage.put(key,row);const report=await(await f.call(path)).json();assert.equal(report.chunks[0].state,'pending');assert.equal(report.chunks[0].retryable,true);assert.equal(report.analysis.retryable,1);
 const next=await(await f.call(`/internal/sleep/sessions/${s.id}/claim/${c.id}`,'POST',{})).json();assert.equal(next.claimed,true);assert.notEqual(next.lease,first.lease);assert.equal((await f.call(`/internal/sleep/sessions/${s.id}/result/${c.id}`,'POST',{lease:first.lease,result})).status,409);
});

test('AI off then on still invalidates the old consent lease and audio deletion prevents new derived text',async()=>{
 for(const revoke of [true,false]){const f=fixture(),{s,p}=await setup(f),c=await chunk(f,s);await f.call('/v2/sleep/sessions/'+s.id+'/chunks','POST',{recording_session_id:c.id});const path=`/internal/sleep/sessions/${s.id}`;const claim=await(await f.call(path+'/claim/'+c.id,'POST',{})).json();
 if(revoke){await f.call('/internal/phone-sync','POST',{...p,sleep_analysis_allowed:false});await f.call('/internal/phone-sync','POST',p);}else await f.call('/v2/sessions/'+c.id,'DELETE');
 const out=await(await f.call(path+'/result/'+c.id,'POST',{lease:claim.lease,result})).json();assert.equal(out.state,revoke?'skipped':'rejected');assert.equal(out.result?.transcript??null,null);}
});
test('codec padding alone cannot be reported as successful acoustic coverage',async()=>{
 const {validateAcoustic,summarizeAcoustics}=await import('./sleep-acoustic.mjs');const padded={status:'complete',model:'yamnet',model_version:'yamnet-audioset-1',model_sha256:'a'.repeat(64),analyzed_ms:1000,analyzed_ranges:[{start_ms:120000,end_ms:121000}],events:[]};assert.throws(()=>validateAcoustic(padded,120000));assert.equal(summarizeAcoustics([{recorded_from:0,duration_ms:120000,acoustic:{...padded,analyzed_ms:0}}]).possible_intervals,null);
});

test('expired audio reservations release capacity for the next night without allowing replay resurrection',async()=>{
 const f=fixture(),{s}=await setup(f);let stale;for(let i=0;i<365;i++){const id=randomUUID();stale=id;await f.storage.put('sleep-reservation:'+id,{sleep_id:s.id,created_at:Date.now()-86400001,expires_at:Date.now()-1});}
 const path='/v2/sleep/sessions/'+s.id+'/reserve';assert.equal((await f.call(path,'POST',{recording_session_id:stale})).status,410);assert.equal((await f.call(path,'POST',{recording_session_id:randomUUID()})).status,201);
 assert.equal((await f.call('/v2/sessions','POST',{session_id:stale,consent,mode:'recording'})).status,409);
});
