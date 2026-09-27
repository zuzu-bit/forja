import {runLimit} from './organizer-selection.mjs';
import {bad,keys,idPattern,TTL} from './phone-schema.mjs';
const json=(v,status=200)=>Response.json(v,{status,headers:{'cache-control':'no-store','x-content-type-options':'nosniff'}});
const validText=(v,n)=>typeof v==='string'&&v.length<=n&&!/[\u0000-\u001f\u007f]/.test(v);
export const defaultSchedule=()=>({enabled:false,count:25,photos:true,files:false,times:['20:00'],days:[1,2,3,4,5,6,7],timezone:'Europe/Bucharest',wifi_only:true});
export function validateSchedule(value,grant){
 keys(value,['enabled','count','photos','files','times','days','timezone','wifi_only']);
 if(['enabled','photos','files','wifi_only'].some(k=>typeof value[k]!=='boolean')||![25,50].includes(value.count))bad('Alege 25 sau 50 de elemente.');
 if(!Array.isArray(value.times)||value.times.length<1||value.times.length>4||value.times.some(t=>typeof t!=='string'||!/^([01]\d|2[0-3]):[0-5]\d$/.test(t))||new Set(value.times).size!==value.times.length)bad('Alege între una și patru ore distincte, HH:MM.');
 if(!Array.isArray(value.days)||value.days.length<1||value.days.length>7||value.days.some(d=>!Number.isInteger(d)||d<1||d>7)||new Set(value.days).size!==value.days.length)bad('Zile nevalide.');
 if(!validText(value.timezone,64))bad('Fus orar invalid.');
 try{new Intl.DateTimeFormat('en',{timeZone:value.timezone}).format();}catch{bad('Fus orar invalid.');}
 if(!value.photos&&!value.files)bad('Alege fotografiile sau fișierele.');
 if(value.enabled&&(!grant?.enabled||value.photos&&!grant.photos||value.files&&!grant.files))bad('Activează mai întâi aceste surse în aplicația de pe telefon.',403);
 return {...value,times:[...value.times].sort(),days:[...value.days].sort((a,b)=>a-b)};
}
function formatter(tz){return new Intl.DateTimeFormat('en-CA',{timeZone:tz,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'});}
function parts(fmt,at){return Object.fromEntries(fmt.formatToParts(new Date(at)).filter(p=>p.type!=='literal').map(p=>[p.type,p.value]));}
function stamp(p){return `${p.year}-${p.month}-${p.day}T${p.hour}:${p.minute}`;}
/** Enumerate real wall-clock slots. Missing DST times are skipped; repeated times run once. */
export function scheduleSlots(schedule,now){
 const fmt=formatter(schedule.timezone),p=parts(fmt,now),today=Date.UTC(+p.year,+p.month-1,+p.day),out=[];
 for(let day=-1;day<=8;day++){
  const date=new Date(today+day*TTL),ymd=date.toISOString().slice(0,10),weekday=date.getUTCDay()||7;if(!schedule.days.includes(weekday))continue;
  for(const time of schedule.times){
   const wall=Date.parse(ymd+'T'+time+':00Z'),offsets=new Set([-36,0,36].map(h=>{const at=wall+h*3600000,q=parts(fmt,at);return Date.parse(stamp(q)+':00Z')-at;}));
   const candidates=[...offsets].map(offset=>wall-offset).filter(at=>stamp(parts(fmt,at))===ymd+'T'+time).sort((a,b)=>a-b);
   if(candidates.length)out.push({at:candidates[0],slot:ymd+'T'+time});
  }
 }
 return out.sort((a,b)=>a.at-b.at);
}
export function nextSlot(schedule,now,since=0){return scheduleSlots(schedule,now).find(s=>s.at>now&&s.at>=since)?.at??null;}
export async function sweepCleanup(storage,now=Date.now()){
 let next=Infinity;
 for(const [key,run] of await storage.list({prefix:'cleanup-run:'})){if(run.expires_at<=now)await storage.delete(key);else next=Math.min(next,run.expires_at);}
 return next;
}
export async function requireCleanupRun(storage,device,id,grantId){
 if(!idPattern.test(id))bad('Analiză invalidă.');
 const d=await storage.get('cleanup-device:'+device),r=await storage.get('cleanup-run:'+id);
 if(!d||!r||r.device!==device)bad('Analiză indisponibilă.',404);
 if(!d.grant.enabled||!r.on_demand&&!d.schedule.enabled||(await storage.get('intake'))?.accepting===false)bad('Analiza automată este oprită.',423);
 if(r.grant_id!==d.grant.id||grantId&&grantId!==d.grant.id||!r.on_demand&&r.revision!==d.revision)bad('Programul sau permisiunile s-au schimbat.',409);
 if(r.expires_at<=Date.now()||!r.on_demand&&r.phase==='queued'&&Date.now()>r.at+2*3600000)bad('Ora programată a fost ratată. Urmează următoarea programare.',410);
 return {device:d,run:r};
}
export async function handleCleanup(request,account,readJSON){
 const path=new URL(request.url).pathname;if(!path.startsWith('/v2/cleanup'))return null;
 const {storage}=account.ctx;
 if(path==='/v2/cleanup/devices'&&request.method==='GET'){
  const devices=[...(await storage.list({prefix:'cleanup-device:'})).values()],byId=new Map(devices.map(d=>[d.id,d]));
  const paused=(await storage.get('intake'))?.accepting===false,files=[...(await storage.list({prefix:'cloud-file:'})).values()];
  const runs=[...(await storage.list({prefix:'cleanup-run:'})).values()].sort((a,b)=>b.at-a.at).slice(0,20).map(r=>{
   const d=byId.get(r.device),finished=['complete','error'].includes(r.phase);
   return {...r,uploaded:Math.max(r.uploaded,files.filter(f=>f.cleanup_run===r.id).length),paused:!finished&&paused,cancelled:!finished&&(!d?.grant.enabled||!r.on_demand&&!d.schedule.enabled||d.grant.id!==r.grant_id||!r.on_demand&&d.revision!==r.revision)};
  });
  return json({devices,runs,server_at:Date.now()});
 }
 const m=/^\/v2\/cleanup\/devices\/([0-9a-f-]+)(?:\/(grant|schedule|claim|runs)(?:\/([0-9a-f-]+))?)?$/.exec(path);
 if(!m||!idPattern.test(m[1]))bad('Not found',404);
 const id=m[1],action=m[2],runId=m[3],key='cleanup-device:'+id;let d=await storage.get(key);
 if(action==='grant'&&request.method==='POST'){
  const {value:v}=await readJSON(request,8192);keys(v,['id','enabled','photos','files','label','protocol','organize','sources'],['id','enabled','photos','files','label']);
  if(v.protocol!==undefined&&![2,3,4].includes(v.protocol)||v.organize!==undefined&&typeof v.organize!=='boolean')bad('Capabilități invalide.');
  if(v.sources!==undefined){
   if(v.protocol!==4||!Array.isArray(v.sources)||v.sources.length>12||new Set(v.sources.map(s=>s.id)).size!==v.sources.length)bad('Surse invalide.');
   for(const s of v.sources){keys(s,['id','source','label','folder']);if(!idPattern.test(s.id)||!['photos','files'].includes(s.source)||!validText(s.label,100)||!validText(s.folder,200)||!v[s.source])bad('Sursă invalidă.');}
  }
  if(!idPattern.test(v.id)||['enabled','photos','files'].some(k=>typeof v[k]!=='boolean')||v.enabled&&!v.photos&&!v.files||!validText(v.label,80))bad('Permisiuni de analiză invalide.');
  if(!d&&(await storage.list({prefix:'cleanup-device:'})).size>=5)bad('Maximum cinci telefoane.',429);
  const same=d?.grant.id===v.id;if(same&&(['enabled','photos','files'].some(k=>d.grant[k]!==v[k])||!!d.grant.organize!==(v.organize===true)||(d.protocol||1)!==(v.protocol||1)||JSON.stringify(d.grant.sources||[])!==JSON.stringify(v.sources||[])))bad('Folosește o activare nouă pentru schimbarea surselor.',409);
  if(same)return json(d);
  d={id,label:v.label,protocol:v.protocol||1,grant:{id:v.id,enabled:v.enabled,photos:v.photos,files:v.files,organize:v.organize===true,...(v.sources?{sources:v.sources}:{})},schedule:{...defaultSchedule(),photos:v.photos,files:v.files},revision:(d?.revision||0)+1,updated_at:Date.now(),last_seen:Date.now()};
  await storage.put(key,d);return json(d);
 }
 if(!d)bad('Activează analiza automată în aplicație.',404);
 if(!action&&request.method==='GET')return json({...d,next_at:d.schedule.enabled?nextSlot(d.schedule,Date.now(),d.updated_at):null,server_at:Date.now()});
 if(action==='schedule'&&request.method==='POST'){
  const {value}=await readJSON(request,4096);keys(value,['revision','schedule']);if(value.revision!==d.revision)bad('Programul s-a schimbat. Reîncarcă pagina.',409);
  d={...d,schedule:validateSchedule(value.schedule,d.grant),revision:d.revision+1,updated_at:Date.now()};await storage.put(key,d);return json(d);
 }
 if(action==='claim'&&request.method==='POST'){
  const {value}=await readJSON(request,1024);keys(value,['grant_id']);if(value.grant_id!==d.grant.id||!d.grant.enabled)bad('Activează din nou analiza pe telefon.',403);
  d.last_seen=Date.now();await storage.put(key,d);
  if((await storage.get('intake'))?.accepting===false)return json({run:null,next_at:null,server_at:Date.now()});
  const now=Date.now(),runs=[...(await storage.list({prefix:'cleanup-run:'})).values()].filter(r=>r.device===id&&r.grant_id===d.grant.id&&(r.on_demand||r.revision===d.revision)&&r.expires_at>now);
  const active=runs.sort((a,b)=>a.at-b.at).find(r=>['queued','analyzing','analyzed'].includes(r.phase)&&(r.on_demand||r.phase!=='queued'||r.at+2*3600000>=now));
  if(active)return json({run:active,next_at:d.schedule.enabled?nextSlot(d.schedule,now,d.updated_at):null,server_at:now});
  if(!d.schedule.enabled)return json({run:null,next_at:null,server_at:now});
  const slot=scheduleSlots(d.schedule,now).filter(s=>s.at<=now&&s.at>=d.updated_at&&now-s.at<=2*3600000).at(-1);
  if(!slot||runs.some(r=>r.slot===slot.slot))return json({run:null,next_at:nextSlot(d.schedule,now,d.updated_at),server_at:now});
  const run={id:crypto.randomUUID(),device:id,grant_id:d.grant.id,revision:d.revision,...slot,schedule:d.schedule,phase:'queued',analyzed:0,proposals:[],duplicates:0,uploaded:0,total:0,message:'Telefonul pregătește analiza.',expires_at:now+TTL};
  await storage.put('cleanup-run:'+run.id,run);await account.sweep();return json({run,next_at:nextSlot(d.schedule,now,d.updated_at),server_at:now},201);
 }
 if(action==='runs'&&runId){
  const {run}=await requireCleanupRun(storage,id,runId);
  if(request.method==='GET')return json(run);
  if(request.method==='POST'){
   const {value:v}=await readJSON(request,128*1024);keys(v,['grant_id','phase','analyzed','proposals','duplicates','uploaded','total','message']);
   if(v.grant_id!==run.grant_id)bad('Activare veche.',409);
   const phases=['queued','analyzing','analyzed','complete','error'];
   if(!phases.includes(v.phase)||v.phase==='queued'||['analyzed','complete','error'].includes(run.phase)&&phases.indexOf(v.phase)<phases.indexOf(run.phase))bad('Stare de analiză invalidă.',409);
   const max=runLimit(run);
   if(['analyzed','duplicates','uploaded','total'].some(k=>!Number.isInteger(v[k])||v[k]<0||v[k]>max)||v.uploaded>v.total||v.analyzed>v.total||v.duplicates>v.analyzed||!validText(v.message,400)||!Array.isArray(v.proposals)||v.proposals.length>Math.min(v.analyzed,100))bad('Rezultat invalid.');
   for(const p of v.proposals){keys(p,['name','destination','reason','kind']);if(!validText(p.name,200)||!validText(p.destination,240)||!validText(p.reason,300)||!['photo','file'].includes(p.kind))bad('Propunere invalidă.');}
   const next={...run,...v};delete next.grant_id;next.grant_id=run.grant_id;await storage.put('cleanup-run:'+runId,next);return json(next);
  }
 }
 bad('Method not allowed',405);
}
