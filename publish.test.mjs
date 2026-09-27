import test from 'node:test';import assert from 'node:assert/strict';import {publish} from './publish.mjs';
const old={service:'forja-insights',version:5,files_sync:1},current={service:'forja-insights',version:15,organizer_jobs:4,journey:1,map3d:1,content_ai:1,sleep_audio:1,visual_ui:1,lost_phone:1,partners:1,contacts:1,social:1,organizer_modes:1,files_sync:1,cleanup_schedule:1,background_audio:1,organizer:1};
test('already published needs no installation, new grant or redeployment',async()=>{const result=await publish({health:async()=>current,exec:async()=>assert.fail('must not publish'),log:()=>{}});assert.equal(result,'already-current');});
test('a newer incompatible site is never overwritten',async()=>{await assert.rejects(publish({health:async()=>({version:16}),exec:async()=>assert.fail('must not run'),log:()=>{}}),/mai noua/);});
for(const failStep of ['test','login','deployments'])test('failure at '+failStep+' prevents publishing',async()=>{
 const calls=[];await assert.rejects(publish({health:async()=>old,exec:async(kind,args)=>{calls.push(args);if(args[0]===failStep)throw Error('fixture failure');},log:()=>{}}),/fixture failure/);
 assert.equal(calls.filter(a=>a[0]==='deploy'&&!a.includes('--dry-run')).length,0);
});
test('success validates, authenticates, checks existing Worker and confirms live feature',async()=>{
 const calls=[];let healthCount=0;const result=await publish({health:async()=>++healthCount===1?old:current,exec:async(kind,args)=>calls.push([kind,args]),log:()=>{},delay:async()=>{}});
 assert.equal(result,'published');assert.deepEqual(calls.map(c=>c[1][0]),['ci','test','deploy','login','deployments','deploy']);assert(calls.at(-1)[1].includes('--experimental-provision=false'));
});
test('a missing live feature is reported without a second deployment',async()=>{
 const calls=[];await assert.rejects(publish({health:async()=>old,exec:async(kind,args)=>calls.push(args),log:()=>{},delay:async()=>{}}),/nu pot confirma/);
 assert.equal(calls.filter(a=>a[0]==='deploy'&&!a.includes('--dry-run')).length,1);
});
