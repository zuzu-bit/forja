import {accountStub,internalRequest} from './insights-ai.mjs';
import {analyzeSleepChunk} from './sleep-analysis.mjs';
const reply=(value,status=200)=>Response.json(value,{status,headers:{'cache-control':'no-store','x-content-type-options':'nosniff'}});
async function checked(response){if(!response.ok){let body;try{body=await response.json();}catch{}throw Object.assign(Error(body?.error||'Sleep storage unavailable'),{status:response.status});}return response.json();}
/** Authenticated edge orchestration. Expensive inference never holds the account Durable Object lock. */
export async function handleSleepAPI(request,env,uid){
  const url=new URL(request.url),stub=accountStub(env,uid),headers=new Headers(request.headers);headers.set('x-forja-owner',uid);
  const response=await stub.fetch(new Request(request,{headers}));
  const path=/^\/v2\/sleep\/sessions\/([0-9a-f-]+)\/chunks$/.exec(url.pathname);
  if(!path||request.method!=='POST'||!response.ok)return response;
  const attached=await response.json(),session=path[1];
  const claim=await checked(await stub.fetch(internalRequest(uid,`/internal/sleep/sessions/${session}/claim/${attached.id}`,'POST',{})));
  if(!claim.claimed)return reply(claim.chunk,claim.chunk.state==='analyzing'?202:200);
  let result;
  try{
    const source=await stub.fetch(internalRequest(uid,`/v2/sessions/${attached.id}/items/${attached.item_id}`));
    if(!source.ok)throw Error('Audio source is no longer available');
    const bytes=new Uint8Array(await source.arrayBuffer());
    result=await analyzeSleepChunk(env,bytes,{duration_ms:attached.duration_ms,media_type:'audio/mp4'});
  }catch{
    result={status:'failed',transcript:null,transcript_status:'unavailable',segments:[],topics:[],topics_status:'unavailable',limitations:['analysis_failed'],snoring:{status:'unavailable',reason:'audio_event_classifier_unavailable'},models:{transcription:null,topics:null},error:{code:'analysis_failed',message:'Analiza audio nu a reușit. Înregistrarea rămâne disponibilă.'}};
  }
  const saved=await checked(await stub.fetch(internalRequest(uid,`/internal/sleep/sessions/${session}/result/${attached.id}`,'POST',{lease:claim.lease,result})));
  return reply(saved,['failed','unavailable'].includes(saved.state)?503:200);
}
