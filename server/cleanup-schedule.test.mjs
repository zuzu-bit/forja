import test from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {InsightsAccount} from './insights-store.mjs';
import {scheduleSlots,defaultSchedule,nextSlot,validateSchedule} from './cleanup-schedule.mjs';
class Storage{
 values=new Map();async get(k){return structuredClone(this.values.get(k));}async put(k,v){this.values.set(k,structuredClone(v));}async delete(k){for(const key of Array.isArray(k)?k:[k])this.values.delete(key);}async list({prefix}){return new Map([...this.values].filter(([k])=>k.startsWith(prefix)).map(([k,v])=>[k,structuredClone(v)]));}async setAlarm(at){this.alarm=at;}async deleteAlarm(){this.alarm=null;}
}
function fixture(){
 const storage=new Storage(),bucket={files:new Map(),async put(k,b){this.files.set(k,b);},async delete(k){for(const key of Array.isArray(k)?k:[k])this.files.delete(key);},async list(){return {objects:[]};}};let queue=Promise.resolve();
 const object=new InsightsAccount({storage,blockConcurrencyWhile(fn){const next=queue.then(fn);queue=next.catch(()=>{});return next;}},{RECORDS:bucket});
 return {storage,bucket,object,call:(path,method='GET',body,owner='ownerA',headers={})=>object.fetch(new Request('https://fixture'+path,{method,headers:{'x-forja-owner':owner,'content-type':'application/json',...headers},...(body===undefined?{}:{body:body instanceof Uint8Array?body:JSON.stringify(body)})}))};
}
async function setup(t){
 let now=Date.parse('2026-09-15T07:00:00Z');t.mock.method(Date,'now',()=>now);
 const f=fixture(),device=randomUUID(),grant=randomUUID(),path='/v2/cleanup/devices/'+device;
 assert.equal((await f.call(path+'/grant','POST',{id:grant,enabled:true,photos:true,files:true,label:'Phone'})).status,200);
 const schedule={...defaultSchedule(),enabled:true,files:true,times:['11:00']};
 assert.equal((await f.call(path+'/schedule','POST',{revision:1,schedule})).status,200);
 return {...f,device,grant,path,schedule,setTime(v){now=typeof v==='number'?v:Date.parse(v);},claim:()=>f.call(path+'/claim','POST',{grant_id:grant})};
}
const report=(grant,phase='complete')=>({grant_id:grant,phase,analyzed:0,proposals:[],duplicates:0,uploaded:0,total:0,message:'Done'});
test('schedule validates counts, source grants, unique times, days and timezone',()=>{
 const grant={enabled:true,photos:true,files:false};assert.equal(validateSchedule({...defaultSchedule(),enabled:true},grant).count,25);
 for(const patch of [{count:100},{times:['24:00']},{times:['11:00','11:00']},{days:[]},{days:[1,1]},{timezone:'made/up'},{files:true,enabled:true}])assert.throws(()=>validateSchedule({...defaultSchedule(),...patch},grant));
});
test('DST skips missing wall time and executes repeated wall time once',()=>{
 const s=scheduleSlots({...defaultSchedule(),times:['03:30']},Date.parse('2026-03-29T00:00:00Z'));assert.equal(s.filter(s=>s.slot==='2026-03-29T03:30').length,0);
 const f=scheduleSlots({...defaultSchedule(),times:['03:30']},Date.parse('2026-10-25T00:00:00Z')).filter(s=>s.slot==='2026-10-25T03:30');assert.equal(f.length,1);assert.equal(f[0].at,Date.parse('2026-10-25T00:30:00Z'));
});
test('weekday and timezone govern next wake-up',()=>{assert.equal(nextSlot({...defaultSchedule(),days:[3],times:['11:00']},Date.parse('2026-09-15T07:00:00Z')),Date.parse('2026-09-16T08:00:00Z'));});
test('phone opt-in is required before web scheduling',async()=>{const f=fixture(),p='/v2/cleanup/devices/'+randomUUID();assert.equal((await f.call(p+'/schedule','POST',{revision:1,schedule:defaultSchedule()})).status,404);assert.equal((await f.call(p+'/claim','POST',{grant_id:randomUUID()})).status,404);});
test('nothing runs early; concurrent claims return the same durable run',async t=>{const f=await setup(t);assert.equal((await(await f.claim()).json()).run,null);f.setTime('2026-09-15T08:00:00Z');const[a,b]=await Promise.all([f.claim(),f.claim()]);assert.equal((await a.json()).run.id,(await b.json()).run.id);assert.equal((await f.storage.list({prefix:'cleanup-run:'})).size,1);});
test('completed slots are not replayed',async t=>{const f=await setup(t);f.setTime('2026-09-15T08:00:00Z');const r=(await(await f.claim()).json()).run;assert.equal((await f.call(f.path+'/runs/'+r.id,'POST',report(f.grant))).status,200);assert.equal((await(await f.claim()).json()).run,null);});
test('missed schedules do not execute as a catch-up burst',async t=>{const f=await setup(t);f.setTime('2026-09-15T10:01:00Z');assert.equal((await(await f.claim()).json()).run,null);});
test('web edits invalidate queued work and reject stale form revisions',async t=>{const f=await setup(t);f.setTime('2026-09-15T08:00:00Z');const r=(await(await f.claim()).json()).run;assert.equal((await f.call(f.path+'/schedule','POST',{revision:2,schedule:{...f.schedule,times:['14:00'],count:50}})).status,200);assert.equal((await f.call(f.path+'/runs/'+r.id)).status,409);assert.equal((await f.call(f.path+'/schedule','POST',{revision:2,schedule:f.schedule})).status,409);});
test('intake pause and phone revoke stop claimed work',async t=>{const f=await setup(t);f.setTime('2026-09-15T08:00:00Z');const r=(await(await f.claim()).json()).run;await f.storage.put('intake',{accepting:false});assert.equal((await f.call(f.path+'/runs/'+r.id)).status,423);assert.equal((await(await f.claim()).json()).run,null);await f.storage.put('intake',{accepting:true});await f.call(f.path+'/grant','POST',{id:randomUUID(),enabled:false,photos:true,files:true,label:'Phone'});assert.equal((await f.call(f.path+'/runs/'+r.id)).status,423);assert.equal((await f.claim()).status,403);});
test('grant retry preserves configured times',async t=>{const f=await setup(t);const d=await(await f.call(f.path+'/grant','POST',{id:f.grant,enabled:true,photos:true,files:true,label:'Phone'})).json();assert.equal(d.schedule.enabled,true);assert.equal(d.revision,2);});
test('account binding also protects cleanup schedules',async t=>{const f=await setup(t);assert.equal((await f.call('/v2/cleanup/devices','GET',undefined,'ownerB')).status,403);});
test('reports validate counts and expire after 24 hours',async t=>{const f=await setup(t);f.setTime('2026-09-15T08:00:00Z');const r=(await(await f.claim()).json()).run;assert.equal((await f.call(f.path+'/runs/'+r.id,'POST',{...report(f.grant),uploaded:2,total:1})).status,400);assert.equal((await f.call(f.path+'/runs/'+r.id,'POST',{...report(f.grant),analyzed:1,total:1,proposals:[{name:'<script>',destination:'Pictures/School/',reason:'OCR',kind:'photo'}]})).status,200);f.setTime(r.expires_at+1);await f.object.alarm();assert.equal((await f.storage.list({prefix:'cleanup-run:'})).size,0);});
test('automatic file uploads are rejected after the web schedule is stopped',async t=>{const f=await setup(t);f.setTime('2026-09-15T08:00:00Z');const r=(await(await f.claim()).json()).run;await f.call('/v2/files/settings/'+f.device,'POST',{enabled:true,photos:true,files:true});await f.call(f.path+'/schedule','POST',{revision:2,schedule:{...f.schedule,enabled:false}});const result=await f.call('/v2/files/'+randomUUID(),'PUT',new Uint8Array([1,2,3]),'ownerA',{'x-device-id':f.device,'x-cleanup-run':r.id});assert.equal(result.status,423);assert.equal(f.bucket.files.size,0);});
test('authorized scheduled uploads preserve bytes, run binding and original expiry',async t=>{
 const f=await setup(t);f.setTime('2026-09-15T08:00:00Z');const run=(await(await f.claim()).json()).run;
 await f.call('/v2/files/settings/'+f.device,'POST',{enabled:true,photos:true,files:true});
 const bytes=new Uint8Array([37,80,68,70,45,49,46,55]);const sha=[...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(v=>v.toString(16).padStart(2,'0')).join('');const id=randomUUID();
 const headers={'x-device-id':f.device,'x-cleanup-run':run.id,'x-file-kind':'file','x-file-name':'Document.pdf','x-media-type':'application/pdf','x-file-sha256':sha};
 const first=await f.call('/v2/files/'+id,'PUT',bytes,'ownerA',headers);assert.equal(first.status,201);const receipt=await first.json();assert.equal(receipt.cleanup_run,run.id);assert.equal(receipt.expires_at-receipt.received_at,86400000);
 assert.deepEqual([...f.bucket.files.values()][0],bytes);
 f.setTime('2026-09-15T08:05:00Z');const retry=await(await f.call('/v2/files/'+id,'PUT',bytes,'ownerA',headers)).json();assert.equal(retry.expires_at,receipt.expires_at);
 const {['x-cleanup-run']:ignored,...manual}=headers;assert.equal((await f.call('/v2/files/'+id,'PUT',bytes,'ownerA',manual)).status,409);
});
test('temporary intake pause preserves the same run for resumption',async t=>{
 const f=await setup(t);f.setTime('2026-09-15T08:00:00Z');const run=(await(await f.claim()).json()).run;
 await f.call(f.path+'/runs/'+run.id,'POST',report(f.grant,'analyzing'));await f.storage.put('intake',{accepting:false});
 const paused=await(await f.call('/v2/cleanup/devices')).json();assert.equal(paused.runs[0].paused,true);assert.equal(paused.runs[0].cancelled,false);
 await f.storage.put('intake',{accepting:true});assert.equal((await(await f.claim()).json()).run.id,run.id);assert.equal((await f.call(f.path+'/runs/'+run.id)).status,200);
});
