import {validateAcoustic,summarizeAcoustics} from './sleep-acoustic.mjs';
import {bad,keys,n,idPattern,TTL} from './phone-schema.mjs';
import {defaultIntake} from './app-content.mjs';

const MAX_SESSION_MS=12*3600000, MAX_CHUNKS=365, LEASE_MS=180000;
const reply=(data,status=200)=>Response.json(data,{status,headers:{'cache-control':'no-store','x-content-type-options':'nosniff'}});
const sessionKey=id=>'sleep:'+id;
const chunkPrefix=id=>'sleep-chunk:'+id+':';
const chunkKey=(id,recording)=>chunkPrefix(id)+recording;
const uuid=id=>{if(typeof id!=='string'||!idPattern.test(id))bad('Invalid sleep identifier');};
const publicChunk=c=>{const {lease,...out}=c;const expired=c.state==='analyzing'&&(!lease||lease.until<=Date.now());return {...out,...(expired?{state:'pending'}:{}),retryable:expired||['pending','failed','unavailable'].includes(c.state),retry_after_ms:c.state==='analyzing'&&lease?Math.max(0,lease.until-Date.now()):0};};
async function deleteMany(storage,keys){for(let i=0;i<keys.length;i+=128)await storage.delete(keys.slice(i,i+128));}
async function allChunks(storage,id){return [...(await storage.list({prefix:chunkPrefix(id)})).values()].sort((a,b)=>a.recorded_from-b.recorded_from||a.id.localeCompare(b.id));}
function coverage(chunks){let total=0,end=-Infinity;for(const c of chunks){total+=Math.max(0,c.recorded_to-Math.max(c.recorded_from,end));end=Math.max(end,c.recorded_to);}return total;}
export async function sleepSummary(storage,s){
  const chunks=await allChunks(storage,s.id),counts={ready:0,pending:0,failed:0,skipped:0,retryable:0};
  for(const c of chunks){if(publicChunk(c).retryable)counts.retryable++;if(['complete','partial'].includes(c.state))counts.ready++;else if(['failed','unavailable','rejected'].includes(c.state))counts.failed++;else if(c.state==='skipped')counts.skipped++;else counts.pending++;}
  const state=!s.ended_at?(s.planned_stop_at<Date.now()?'awaiting_finish':'recording'):counts.pending?'analyzing':counts.failed?'needs_retry':chunks.length?'complete':'awaiting_upload';
  return {...s,state,recorded_ms:coverage(chunks),analyzed_ms:coverage(chunks.filter(c=>['complete','partial'].includes(c.state))),chunk_count:chunks.length,analysis:counts,
    snoring:summarizeAcoustics(chunks),
    coverage:'received_chunks_only'};
}
async function getSession(storage,id){uuid(id);const s=await storage.get(sessionKey(id));if(!s||s.expires_at<=Date.now())bad('Sleep session not found or expired',404);return s;}
async function requireIntake(storage){if(!(await storage.get('intake')||defaultIntake()).accepting)bad('Primirea datelor este oprită din panoul web.',423);}
function validateResult(v){
  if(!v||typeof v!=='object'||Array.isArray(v)||new TextEncoder().encode(JSON.stringify(v)).length>=32768)bad('Invalid sleep analysis result');
  if(!['complete','partial','unavailable','failed','rejected'].includes(v.status)||!(v.transcript===null||typeof v.transcript==='string'))bad('Invalid sleep analysis result');
  return v;
}
export async function sweepSleep(storage,now=Date.now()){
  let next=Infinity;
  for(const [key,s]of await storage.list({prefix:'sleep:'})){
    if(s.expires_at<=now||await storage.get('sleep-deleted:'+s.id)){const rows=await storage.list({prefix:chunkPrefix(s.id)});await deleteMany(storage,[...rows.keys(),key]);}
    else next=Math.min(next,s.expires_at);
  }
  for(const [key,r]of await storage.list({prefix:'sleep-reservation:'})){const s=await storage.get(sessionKey(r.sleep_id));if(!s||s.expires_at<=now)await storage.delete(key);}
  for(const [key,until]of await storage.list({prefix:'sleep-deleted:'})){if(until<=now)await storage.delete(key);else next=Math.min(next,until);}
  return next;
}
/** Runs only inside the account Durable Object's transaction/owner boundary. AI runs outside this lock. */
export async function handleSleepStore(request,account,readJSON){
  const {storage}=account.ctx,url=new URL(request.url),path=url.pathname;
  if(!path.startsWith('/v2/sleep/')&&!path.startsWith('/internal/sleep/'))return null;
  if(path==='/v2/sleep/sessions'){
    if(request.method==='GET'){
      const sessions=[...(await storage.list({prefix:'sleep:'})).values()].filter(s=>s.expires_at>Date.now()).sort((a,b)=>b.started_at-a.started_at);
      return reply({sessions:await Promise.all(sessions.map(s=>sleepSummary(storage,s)))});
    }
    if(request.method!=='POST')bad('Method not allowed',405);
    await requireIntake(storage);
    const {value:v}=await readJSON(request,2048);keys(v,['id','device_id','started_at','planned_stop_at','analysis_consent']);uuid(v.id);uuid(v.device_id);
    n(v.started_at);n(v.planned_stop_at,v.started_at+60000,v.started_at+MAX_SESSION_MS);
    if(typeof v.analysis_consent!=='boolean')bad('Analysis choice required');
    if(await storage.get('sleep-deleted:'+v.id))bad('Sleep session was deleted',410);
    const old=await storage.get(sessionKey(v.id));
    if(old){if(['device_id','started_at','planned_stop_at','analysis_consent'].some(k=>old[k]!==v[k]))bad('Sleep ID reused with different content',409);return reply(await sleepSummary(storage,old));}
    n(v.started_at,Date.now()-TTL,Date.now()+30000);
    const phone=await storage.get('phone:'+v.device_id);
    if(!phone?.sleep_capable||!phone.audio_allowed)bad('Activează înregistrarea de somn pe telefon.',403);
    if(v.analysis_consent&&!phone.sleep_analysis_allowed)bad('Analiza audio nu este autorizată pe telefon.',403);
    const sessions=[...(await storage.list({prefix:'sleep:'})).values()];
    if(sessions.length>=12)bad('Maximum twelve retained sleep sessions',429);
    if(sessions.some(s=>s.device_id===v.device_id&&!s.ended_at&&s.planned_stop_at>Date.now()))bad('Telefonul are deja o sesiune de somn activă.',409);
    const s={...v,created_at:Date.now(),ended_at:null,expires_at:Date.now()+TTL};await storage.put(sessionKey(s.id),s);await account.sweep();return reply(await sleepSummary(storage,s),201);
  }
  const m=/^\/(v2|internal)\/sleep\/sessions\/([0-9a-f-]+)(?:\/(chunks|finish|claim|result|reserve)(?:\/([0-9a-f-]+))?)?$/.exec(path);
  if(!m)return reply({error:'Not found'},404);
  const s=await getSession(storage,m[2]);
  if(m[1]==='v2'&&!m[3]){
    if(request.method==='DELETE'){
      await storage.put('sleep-deleted:'+s.id,s.expires_at);await storage.setAlarm(Date.now()+60000);await deleteMany(storage,[...(await storage.list({prefix:chunkPrefix(s.id)})).keys(),sessionKey(s.id)]);await account.sweep();
      return reply({deleted:true,recordings_retained:true});
    }
    if(request.method!=='GET')bad('Method not allowed',405);
    const chunks=await allChunks(storage,s.id),cursor=url.searchParams.get('cursor');const start=cursor?chunks.findIndex(c=>c.id===cursor)+1:0;
    if(cursor&&start===0)bad('Invalid report cursor');const page=chunks.slice(start,start+25);
    return reply({...await sleepSummary(storage,s),chunks:page.map(publicChunk),next_cursor:start+25<chunks.length?page.at(-1).id:null});
  }
  if(m[1]==='v2'&&m[3]==='finish'&&!m[4]&&request.method==='POST'){
    const {value:v}=await readJSON(request,1024);keys(v,['ended_at']);n(v.ended_at,s.started_at,Math.min(Date.now()+30000,s.planned_stop_at+300000));
    if(s.ended_at){if(s.ended_at!==v.ended_at)bad('Sleep end already recorded',409);return reply(await sleepSummary(storage,s));}
    const chunks=await allChunks(storage,s.id);if(chunks.some(c=>c.recorded_to>v.ended_at+1000))bad('Sleep end precedes uploaded audio');
    s.ended_at=v.ended_at;s.expires_at=Math.min(s.created_at+MAX_SESSION_MS+TTL,Math.max(s.expires_at,v.ended_at+TTL));await storage.put(sessionKey(s.id),s);await account.sweep();return reply(await sleepSummary(storage,s));
  }
  if(m[1]==='v2'&&m[3]==='reserve'&&!m[4]&&request.method==='POST'){
    await requireIntake(storage);const {value:v}=await readJSON(request,1024);keys(v,['recording_session_id']);uuid(v.recording_session_id);
    const key='sleep-reservation:'+v.recording_session_id,old=await storage.get(key);
    if(old){if(old.sleep_id!==s.id)bad('Recording reserved for another sleep session',409);if(old.expires_at<=Date.now())bad('Sleep recording reservation expired',410);return reply({reserved:true,recording_session_id:v.recording_session_id});}
    const existing=await storage.get('session:'+v.recording_session_id);if(existing&&existing.sleep_session_id!==s.id)bad('Recording already exists outside this sleep session',409);
    if([...(await storage.list({prefix:'sleep-reservation:'})).values()].filter(r=>r.expires_at>Date.now()).length>=MAX_CHUNKS)bad('Maximum 365 retained sleep chunks per account',429);
    await storage.put(key,{sleep_id:s.id,created_at:Date.now(),expires_at:Date.now()+TTL});return reply({reserved:true,recording_session_id:v.recording_session_id},201);
  }
  if(m[1]==='v2'&&m[3]==='chunks'&&!m[4]&&request.method==='POST'){
    await requireIntake(storage);const {value:v}=await readJSON(request,32000);keys(v,['recording_session_id','acoustic'],['recording_session_id']);uuid(v.recording_session_id);
    const old=await storage.get(chunkKey(s.id,v.recording_session_id));
    if(old){if(v.acoustic!==undefined){const acoustic=validateAcoustic(v.acoustic,old.duration_ms);if(old.acoustic?.status==='complete'&&JSON.stringify(old.acoustic)!==JSON.stringify(acoustic))bad('Acoustic observations already recorded',409);old.acoustic=acoustic;await storage.put(chunkKey(s.id,v.recording_session_id),old);}return reply(publicChunk(old));}
    const recording=await storage.get('session:'+v.recording_session_id),item=recording?.items?.[0];
    if(!recording||recording.expires_at<=Date.now()||recording.mode!=='recording'||!recording.consent.audio||recording.items.length!==1||item.kind!=='audio')bad('Uploaded recording required',409);
    const reservation=await storage.get('sleep-reservation:'+v.recording_session_id);
    if(recording.sleep_session_id!==s.id||reservation?.sleep_id!==s.id)bad('A matching sleep reservation is required before uploading this recording',409);
    if(item.duration_ms>300000||item.bytes>2*1024*1024)bad('Sleep chunks must be at most five minutes and 2 MiB',413);
    if(item.recorded_from<s.started_at-5000||item.recorded_to>(s.ended_at||s.planned_stop_at)+5000)bad('Recording outside sleep interval');
    const chunks=await allChunks(storage,s.id);if(chunks.length>=MAX_CHUNKS)bad('Too many sleep chunks',413);
    if(chunks.some(c=>Math.min(c.recorded_to,item.recorded_to)-Math.max(c.recorded_from,item.recorded_from)>1000))bad('Overlapping sleep chunks',409);
    const acoustic=v.acoustic===undefined?null:validateAcoustic(v.acoustic,item.duration_ms);
    const c={id:v.recording_session_id,item_id:item.item_id,acoustic,recorded_from:item.recorded_from,recorded_to:item.recorded_to,duration_ms:item.duration_ms,bytes:item.bytes,state:s.analysis_consent?'pending':'skipped',attempts:0,result:null,received_at:Date.now()};
    recording.sleep_session_id=s.id;await storage.put({[chunkKey(s.id,c.id)]:c,['session:'+recording.session_id]:recording});return reply(publicChunk(c),201);
  }
  if(m[1]==='internal'&&['claim','result'].includes(m[3])&&m[4]&&request.method==='POST'){
    uuid(m[4]);const key=chunkKey(s.id,m[4]),c=await storage.get(key);if(!c)bad('Sleep chunk not found',404);
    const {value:v}=await readJSON(request,36000);
    const phone=await storage.get('phone:'+s.device_id);
    if(m[3]==='claim'){
      keys(v,[]);await requireIntake(storage);
      if(!s.analysis_consent||!phone?.sleep_analysis_allowed){if(['pending','analyzing','failed','unavailable'].includes(c.state)){c.state='skipped';c.lease=null;await storage.put(key,c);}return reply({claimed:false,chunk:publicChunk(c)});}
      if(['complete','partial','skipped','rejected'].includes(c.state)||c.lease?.until>Date.now())return reply({claimed:false,chunk:publicChunk(c)});
      if(c.attempts>=5)bad('Analiza a eșuat de cinci ori. Înregistrarea rămâne disponibilă.',429);
      const source=await storage.get('session:'+c.id);if(!source||source.expires_at<=Date.now())bad('Recording expired',410);
      c.lease={id:crypto.randomUUID(),until:Date.now()+LEASE_MS,analysis_epoch:phone.sleep_analysis_epoch||0};c.state='analyzing';c.attempts++;await storage.put(key,c);
      return reply({claimed:true,lease:c.lease.id,chunk:publicChunk(c)});
    }
    keys(v,['lease','result']);uuid(v.lease);if(c.lease?.id!==v.lease)bad('Analysis lease changed',409);
    const source=await storage.get('session:'+c.id);
    if(!s.analysis_consent||!phone?.sleep_analysis_allowed||(phone.sleep_analysis_epoch||0)!==c.lease.analysis_epoch){c.state='skipped';c.result=null;}
    else if(!source||source.expires_at<=Date.now()||!source.items.some(i=>i.item_id===c.item_id)){c.state='rejected';c.result={status:'rejected',transcript:null,transcript_status:'unavailable',error:{code:'source_unavailable',message:'Înregistrarea a fost ștearsă sau a expirat înainte de încheierea analizei.'}};}
    else{c.result=validateResult(v.result);c.state=v.result.status;}
    c.lease=null;c.analyzed_at=Date.now();await storage.put(key,c);return reply(publicChunk(c));
  }
  return reply({error:'Not found'},404);
}
