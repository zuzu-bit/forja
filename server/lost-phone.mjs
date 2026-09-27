import {bad,keys} from './phone-schema.mjs';
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const SECRET=/^[a-f0-9]{64}$/;const DAY=86400000;
const states=['ready','locating','location_off','permission_missing','notification_missing','offline','stopped'];
const reply=(data,status=200)=>Response.json(data,{status,headers:{'cache-control':'no-store','x-content-type-options':'nosniff'}});
const hash=async value=>[...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value)))].map(x=>x.toString(16).padStart(2,'0')).join('');
const validNumber=(n,min,max)=>typeof n==='number'&&Number.isFinite(n)&&n>=min&&n<=max;
function expire(d,now){if(d.position?.at<=now-DAY)d.position=null;if(d.command&&(d.command.until<=now||d.command.phase==='queued'&&d.command.start_before<=now)){d.command=null;d.status='expired';}}
function view(d,now){expire(d,now);return {id:d.id,name:d.name,enabled:true,seen_at:d.seen_at,status:d.status,online:d.seen_at>now-90000,command:d.command,position:d.position?{...d.position,fresh:d.position.at>now-120000}:null};}
export async function sweepRecovery(storage,now=Date.now()){
 let next=Infinity;for(const [key,d]of await storage.list({prefix:'recovery:device:'})){
  if(d.expires_at<=now){await storage.delete(key);continue;}const oldPosition=d.position,oldCommand=d.command;expire(d,now);if(d.position!==oldPosition||d.command!==oldCommand)await storage.put(key,d);next=Math.min(next,d.expires_at,d.position?d.position.at+DAY:Infinity,d.command?d.command.phase==='queued'?Math.min(d.command.start_before,d.command.until):d.command.until:Infinity);
 }return next;
}
/** Account-scoped, explicitly enrolled phone recovery. Never reads/writes the friend graph. */
export async function handleRecovery(req,account,readJSON){
 const url=new URL(req.url),path=url.pathname;if(!path.startsWith('/v2/recovery/'))return null;
 const s=account.ctx.storage,now=Date.now(),method=req.method;
 const body=async fields=>{const {value}=await readJSON(req,4096);keys(value,fields);return value;};
 const alarm=async d=>{const next=Math.min(d.expires_at,d.position?d.position.at+DAY:Infinity,d.command?d.command.phase==='queued'?Math.min(d.command.start_before,d.command.until):d.command.until:Infinity);const current=await s.getAlarm();if(!current||current>next)await s.setAlarm(next);};
 if(path==='/v2/recovery/devices'&&method==='GET'){const devices=[];for(const d of (await s.list({prefix:'recovery:device:'})).values())if(d.expires_at>now)devices.push(view(d,now));return reply({devices,retention_hours:24});}
 const match=path.match(/^\/v2\/recovery\/devices\/([^/]+)\/(grant|poll|status|position|command|stop|revoke)$/);if(!match||!UUID.test(match[1]))bad('Telefon sau acțiune invalidă.',404);
 const [_,id,action]=match,key='recovery:device:'+id;let d=await s.get(key);if(d?.expires_at<=now){await s.delete(key);d=null;}
 if(action==='grant'&&method==='POST'){
  const v=await body(['name','secret','consent']);if(v.consent!==true)bad('Activează găsirea pe propriul telefon.',403);if(typeof v.secret!=='string'||!SECRET.test(v.secret)||typeof v.name!=='string'||!v.name.trim()||v.name.length>60)bad('Date de activare invalide.');const secret_hash=await hash(v.secret);
  if(d&&d.secret_hash!==secret_hash)bad('Telefonul are deja altă activare.',409);
  if(!d&&[...(await s.list({prefix:'recovery:device:'})).values()].filter(x=>x.expires_at>now).length>=5)bad('Maximum cinci telefoane. Elimină o activare veche.',409);
  d=d||{id,secret_hash,created_at:now,seen_at:0,status:'waiting_for_phone',command:null,position:null,last_start:0,seen_requests:[]};d.name=v.name.trim();d.expires_at=now+30*DAY;await s.put(key,d);await alarm(d);return reply({ok:true,id});
 }
 if(!d)bad('Găsirea nu este activată pentru acest telefon.',404);expire(d,now);
 const device=async v=>{if(typeof v.secret!=='string'||!SECRET.test(v.secret)||await hash(v.secret)!==d.secret_hash)bad('Activarea telefonului s-a schimbat.',403);};
 const current=cmd=>{if(!d.command||d.command.id!==cmd)bad('Cererea de localizare nu mai este activă.',409);};
 if(action==='grant'&&method==='DELETE'){await s.delete(key);return reply({ok:true});}
 if(action==='revoke'&&method==='POST'){const v=await body(['secret']);await device(v);await s.delete(key);return reply({ok:true});}
 if(action==='command'&&method==='POST'){
  const v=await body(['id','minutes']);if(typeof v.id!=='string'||!UUID.test(v.id)||![5,15,30].includes(v.minutes))bad('Alege 5, 15 sau 30 minute.');
  if(d.command?.id===v.id)return reply({command:d.command,phone_online:d.seen_at>now-90000});
  d.seen_requests=(d.seen_requests||[]).filter(r=>r.until>now);if(d.seen_requests.some(r=>r.id===v.id))bad('Această cerere a fost deja închisă. Pornește una nouă.',409);if(d.seen_requests.length>=100)bad('Maximum 100 cereri pe 24 de ore.',429);
  if(d.command)bad('Oprește întâi căutarea activă.',409);if(now-d.last_start<30000)bad('Așteaptă 30 de secunde înainte de altă cerere.',429);
  d.command={id:v.id,created_at:now,start_before:now+5*60000,until:now+v.minutes*60000,phase:'queued'};d.status='requested';d.last_start=now;d.seen_requests.push({id:v.id,until:now+DAY});await s.put(key,d);await alarm(d);return reply({command:d.command,phone_online:d.seen_at>now-90000},202);
 }
 if(action==='command'&&method==='DELETE'){const expected=url.searchParams.get('id');if(!UUID.test(expected||''))bad('Identifică cererea pe care o oprești.');current(expected);d.command=null;d.position=null;d.status='stopped';await s.put(key,d);return reply({ok:true});}
 if(action==='stop'&&method==='POST'){const v=await body(['secret','command']);await device(v);current(v.command);d.command=null;d.position=null;d.status='stopped';await s.put(key,d);return reply({ok:true});}
 if(action==='poll'&&method==='POST'){
  const v=await body(['secret','status']);await device(v);if(!states.includes(v.status))bad('Stare invalidă.');d.seen_at=now;d.expires_at=now+30*DAY;d.status=v.status;await s.put(key,d);await alarm(d);return reply({command:d.command});
 }
 if(action==='status'&&method==='POST'){
  const v=await body(['secret','command','status']);await device(v);current(v.command);if(!states.includes(v.status))bad('Stare invalidă.');d.seen_at=now;d.status=v.status;if(v.status==='locating')d.command.phase='active';await s.put(key,d);await alarm(d);return reply({ok:true});
 }
 if(action==='position'&&method==='POST'){
  const v=await body(['secret','command','lat','lon','accuracy','at','battery']);await device(v);current(v.command);if(d.command.phase!=='active')bad('Telefonul nu a confirmat pornirea.',409);
  if(!validNumber(v.lat,-90,90)||!validNumber(v.lon,-180,180)||!validNumber(v.accuracy,0,10000)||!validNumber(v.at,Math.max(now-90000,d.command.created_at),now+10000)||!validNumber(v.battery,0,100))bad('Poziție sau precizie invalidă.');
  if(d.position&&v.at<=d.position.at)bad('Poziție veche.',409);
  d.position={lat:v.lat,lon:v.lon,accuracy:v.accuracy,at:v.at,battery:Math.round(v.battery),command:v.command};d.seen_at=now;d.status='locating';await s.put(key,d);await alarm(d);return reply({ok:true});
 }
 bad('Acțiune indisponibilă.',404);
}
