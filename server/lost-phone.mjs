import {bad,keys} from './phone-schema.mjs';
/*
 * Găsirea telefonului, protocolul 2 (FORJA 4.4). Telefonul e ținta, nu căutătorul: se înrolează singur când e semnat
 * contractul v3 (basis "contract"), bate la ~60 s cu ultima poziție și bateria (`beat`), iar site-ul îl caută sau îl sună.
 * Clienții 4.3 (grant cu consent:true, poll la 30 s, doar localizare) merg mai departe neschimbați.
 *
 * Retenție: `last` = un singur punct suprascris, ținut 7 zile de la măsurare; `position` = poziția dintr-o căutare, 24 h.
 * Oprirea (din site sau de pe telefon) închide doar comanda: `last` și `position` rămân.
 */
const UUID=/^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;
const SECRET=/^[a-f0-9]{64}$/;const DAY=86400000,MIN=60000;
export const RECOVERY_RULES=Object.freeze({queue_ms:30*MIN,position_ms:DAY,last_ms:7*DAY,enrollment_ms:30*DAY,online_ms:150000,
 locate_minutes:[5,10,15,30],ring_seconds:[30,60,120],extend_cap_minutes:60,beat_s:60,active_beat_s:15,name_max:40,grant_name_max:60,devices:5});
const states=['ready','locating','ringing','found','location_off','permission_missing','notification_missing','offline','stopped'];
const reply=(data,status=200)=>Response.json(data,{status,headers:{'cache-control':'no-store','x-content-type-options':'nosniff'}});
const hash=async value=>[...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value)))].map(x=>x.toString(16).padStart(2,'0')).join('');
const validNumber=(n,min,max)=>typeof n==='number'&&Number.isFinite(n)&&n>=min&&n<=max;
const cleanName=(v,max)=>{if(typeof v!=='string'||/[\u0000-\u001f\u007f]/.test(v)||!v.trim()||v.trim().length>max)bad('Numele are între 1 și '+max+' de caractere.');return v.trim();};
const duration=c=>c.kind==='ring'?c.seconds*1000:c.minutes*MIN;
/** Starts or keeps the phone's last known point: only a strictly newer measurement replaces it. */
function keepLast(d,fix){if(!d.last||fix.at>d.last.at)d.last={lat:fix.lat,lon:fix.lon,accuracy:fix.accuracy,at:fix.at};}
function expire(d,now){
 if(d.position?.at<=now-RECOVERY_RULES.position_ms)d.position=null;
 if(d.last?.at<=now-RECOVERY_RULES.last_ms)d.last=null;
 if(d.command&&(d.command.until<=now||d.command.phase==='queued'&&d.command.start_before<=now)){d.command=null;d.status='expired';}
}
function nextWake(d){return Math.min(d.expires_at,d.position?d.position.at+RECOVERY_RULES.position_ms:Infinity,d.last?d.last.at+RECOVERY_RULES.last_ms:Infinity,d.command?d.command.phase==='queued'?Math.min(d.command.start_before,d.command.until):d.command.until:Infinity);}
function view(d,now){
 expire(d,now);
 return {id:d.id,name:d.name,basis:d.basis||'consent',enabled:true,seen_at:d.seen_at,online:d.seen_at>now-RECOVERY_RULES.online_ms,status:d.status,
  battery:Number.isFinite(d.battery)?d.battery:Number.isFinite(d.position?.battery)?d.position.battery:null,charging:typeof d.charging==='boolean'?d.charging:null,
  last:d.last?{lat:d.last.lat,lon:d.last.lon,accuracy:d.last.accuracy,at:d.last.at}:null,
  position:d.position?{...d.position,fresh:d.position.at>now-120000}:null,command:d.command};
}
export async function sweepRecovery(storage,now=Date.now()){
 let next=Infinity;for(const [key,d]of await storage.list({prefix:'recovery:device:'})){
  if(d.expires_at<=now){await storage.delete(key);continue;}
  const before=JSON.stringify([d.position,d.last,d.command]);expire(d,now);if(JSON.stringify([d.position,d.last,d.command])!==before)await storage.put(key,d);next=Math.min(next,nextWake(d));
 }return next;
}
/** A short, read-only view for the site's section summaries (Azi, Livret): no secret, no hash, no command. */
export async function recoverySummary(storage,now=Date.now()){
 const devices=[];for(const d of (await storage.list({prefix:'recovery:device:'})).values())if(d.expires_at>now)devices.push({id:d.id,name:d.name,seen_at:d.seen_at||0,status:d.status});
 return devices;
}
/** Account-scoped phone finder. Never reads or writes the friend graph. */
export async function handleRecovery(req,account,readJSON){
 const url=new URL(req.url),path=url.pathname;if(!path.startsWith('/v2/recovery/'))return null;
 const s=account.ctx.storage,now=Date.now(),method=req.method;
 const body=async(fields,required=fields)=>{const {value}=await readJSON(req,4096);keys(value,fields,required);return value;};
 const alarm=async d=>{const next=nextWake(d);const current=await s.getAlarm();if(!current||current>next)await s.setAlarm(next);};
 if(path==='/v2/recovery/devices'&&method==='GET'){const devices=[];for(const d of (await s.list({prefix:'recovery:device:'})).values())if(d.expires_at>now)devices.push(view(d,now));return reply({devices,retention_hours:24,last_retention_days:7});}
 const match=path.match(/^\/v2\/recovery\/devices\/([^/]+)(?:\/(grant|poll|beat|status|position|command|extend|stop|revoke))?$/);if(!match||!UUID.test(match[1]))bad('Telefon sau acțiune invalidă.',404);
 const [_,id,action]=match,key='recovery:device:'+id;let d=await s.get(key);if(d?.expires_at<=now){await s.delete(key);d=null;}
 if(action==='grant'&&method==='POST'){
  const v=await body(['name','secret','consent','basis','contract_version'],['name','secret']);
  if(v.basis!==undefined&&v.basis!=='contract')bad('Temei de activare invalid.');
  const basis=v.basis==='contract'?'contract':'consent';
  if(basis==='contract'){if(!Number.isSafeInteger(v.contract_version)||v.contract_version<3||(v.consent!==undefined&&v.consent!==true))bad('Semnează contractul în FORJA.',403);}
  else if(v.consent!==true||v.contract_version!==undefined)bad('Activează găsirea pe propriul telefon.',403);
  if(typeof v.secret!=='string'||!SECRET.test(v.secret)||typeof v.name!=='string'||!v.name.trim()||v.name.length>RECOVERY_RULES.grant_name_max||/[\u0000-\u001f\u007f]/.test(v.name))bad('Date de activare invalide.');const secret_hash=await hash(v.secret);
  if(d&&d.secret_hash!==secret_hash)bad('Telefonul are deja altă activare.',409);
  if(!d&&[...(await s.list({prefix:'recovery:device:'})).values()].filter(x=>x.expires_at>now).length>=RECOVERY_RULES.devices)bad('Maximum cinci telefoane. Elimină o activare veche.',409);
  d=d||{id,secret_hash,created_at:now,seen_at:0,status:'waiting_for_phone',command:null,position:null,last:null,last_start:0,seen_requests:[]};
  // A name set on the site wins over the phone's default name on a later re-grant.
  if(!d.renamed)d.name=v.name.trim();
  d.basis=basis;d.contract_version=basis==='contract'?v.contract_version:null;d.expires_at=now+RECOVERY_RULES.enrollment_ms;await s.put(key,d);await alarm(d);return reply({ok:true,id});
 }
 if(!d)bad('Găsirea nu este activată pentru acest telefon.',404);expire(d,now);
 const device=async v=>{if(typeof v.secret!=='string'||!SECRET.test(v.secret)||await hash(v.secret)!==d.secret_hash)bad('Activarea telefonului s-a schimbat.',403);};
 const current=cmd=>{if(!d.command||d.command.id!==cmd)bad('Cererea nu mai este activă.',409);};
 if(!action&&method==='PATCH'){const v=await body(['name']);d.name=cleanName(v.name,RECOVERY_RULES.name_max);d.renamed=true;await s.put(key,d);return reply({ok:true,device:view(d,now)});}
 if(action==='grant'&&method==='DELETE'){await s.delete(key);return reply({ok:true});}
 if(action==='revoke'&&method==='POST'){const v=await body(['secret']);await device(v);await s.delete(key);return reply({ok:true});}
 if(action==='command'&&method==='POST'){
  const v=await body(['id','kind','minutes','seconds'],['id']);if(typeof v.id!=='string'||!UUID.test(v.id))bad('Cerere invalidă.');
  const kind=v.kind===undefined?'locate':v.kind;if(!['locate','ring'].includes(kind))bad('Alege Sună sau Urmărește.');
  if(kind==='locate'&&(v.seconds!==undefined||!RECOVERY_RULES.locate_minutes.includes(v.minutes)))bad('Alege 5, 10, 15 sau 30 de minute.');
  const seconds=kind==='ring'?(v.seconds===undefined?60:v.seconds):null;
  if(kind==='ring'&&(v.minutes!==undefined||!RECOVERY_RULES.ring_seconds.includes(seconds)))bad('Alege 30, 60 sau 120 de secunde.');
  if(d.command?.id===v.id)return reply({command:d.command,phone_online:d.seen_at>now-RECOVERY_RULES.online_ms});
  d.seen_requests=(d.seen_requests||[]).filter(r=>r.until>now);if(d.seen_requests.some(r=>r.id===v.id))bad('Această cerere a fost deja închisă. Pornește una nouă.',409);if(d.seen_requests.length>=100)bad('Maximum 100 cereri pe 24 de ore.',429);
  // Only a phone that already speaks protocol 2 (it sent a beat) knows how to ring; a 4.3 phone would just locate.
  if(kind==='ring'&&!(d.proto>=2))bad('Actualizează FORJA pe telefon ca să poată suna.',409);
  const supersede=kind==='ring'&&d.command?.kind!=='ring'&&!!d.command;
  if(d.command&&!supersede)bad('Oprește întâi căutarea activă.',409);if(!supersede&&now-d.last_start<30000)bad('Așteaptă 30 de secunde înainte de altă cerere.',429);
  const start_before=now+RECOVERY_RULES.queue_ms;
  d.command={id:v.id,kind,created_at:now,start_before,until:0,phase:'queued',minutes:kind==='locate'?v.minutes:null,seconds};
  // While queued, `until` is the latest possible end; the phone's acknowledgment restarts the clock (a sleeping phone loses no minutes).
  d.command.until=start_before+duration(d.command);
  d.status='requested';d.last_start=now;d.seen_requests.push({id:v.id,until:now+DAY});await s.put(key,d);await alarm(d);return reply({command:d.command,phone_online:d.seen_at>now-RECOVERY_RULES.online_ms},202);
 }
 if(action==='extend'&&method==='POST'){
  const v=await body(['command','minutes']);current(v.command);if(d.command.kind!=='locate')bad('Doar urmărirea se prelungește.',409);
  if(!Number.isSafeInteger(v.minutes)||v.minutes<1||v.minutes>30)bad('Prelungește cu 1–30 de minute.');
  if(d.command.minutes+v.minutes>RECOVERY_RULES.extend_cap_minutes)bad('O căutare ține cel mult 60 de minute.',409);
  d.command.minutes+=v.minutes;d.command.until+=v.minutes*MIN;await s.put(key,d);await alarm(d);return reply({command:d.command});
 }
 if(action==='command'&&method==='DELETE'){const expected=url.searchParams.get('id');if(!UUID.test(expected||''))bad('Identifică cererea pe care o oprești.');current(expected);d.command=null;d.status='stopped';await s.put(key,d);return reply({ok:true});}
 if(action==='stop'&&method==='POST'){const v=await body(['secret','command']);await device(v);current(v.command);d.command=null;d.status='stopped';d.seen_at=now;await s.put(key,d);return reply({ok:true});}
 if(action==='poll'&&method==='POST'){
  const v=await body(['secret','status']);await device(v);if(!states.includes(v.status))bad('Stare invalidă.');d.seen_at=now;d.expires_at=now+RECOVERY_RULES.enrollment_ms;d.status=v.status;await s.put(key,d);await alarm(d);return reply({command:d.command});
 }
 if(action==='beat'&&method==='POST'){
  // The heartbeat is what keeps the phone findable, so it is lenient: unknown fields are ignored, and a fix or battery
  // value that cannot be used (malformed, older than 7 days, far in the future) is dropped instead of refusing the beat.
  const {value:v}=await readJSON(req,4096);if(!v||typeof v!=='object'||Array.isArray(v))bad('Bătaie invalidă.');await device(v);if(!states.includes(v.status))bad('Stare invalidă.');
  const f=v.fix;
  if(f&&typeof f==='object'&&validNumber(f.lat,-90,90)&&validNumber(f.lon,-180,180)&&validNumber(f.accuracy,0,10000)&&Number.isSafeInteger(f.at)&&f.at>=now-RECOVERY_RULES.last_ms&&f.at<=now+DAY)
   keepLast(d,{lat:f.lat,lon:f.lon,accuracy:f.accuracy,at:Math.min(f.at,now)});
  if(validNumber(v.battery,0,100)){d.battery=Math.round(v.battery);d.battery_at=now;}
  if(typeof v.charging==='boolean')d.charging=v.charging;
  d.seen_at=now;d.expires_at=now+RECOVERY_RULES.enrollment_ms;d.status=v.status;d.proto=2;await s.put(key,d);await alarm(d);
  return reply({command:d.command,next_s:d.command?RECOVERY_RULES.active_beat_s:RECOVERY_RULES.beat_s});
 }
 if(action==='status'&&method==='POST'){
  const v=await body(['secret','command','status']);await device(v);current(v.command);if(!states.includes(v.status))bad('Stare invalidă.');d.seen_at=now;d.status=v.status;
  if(['locating','ringing'].includes(v.status)&&d.command.phase==='queued'){d.command.phase='active';d.command.activated_at=now;d.command.until=now+duration(d.command);}
  if(['found','stopped'].includes(v.status))d.command=null;
  await s.put(key,d);await alarm(d);return reply({ok:true,command:d.command});
 }
 if(action==='position'&&method==='POST'){
  const v=await body(['secret','command','lat','lon','accuracy','at','battery','charging'],['secret','command','lat','lon','accuracy','at','battery']);await device(v);current(v.command);if(d.command.phase!=='active')bad('Telefonul nu a confirmat pornirea.',409);
  if(!validNumber(v.lat,-90,90)||!validNumber(v.lon,-180,180)||!validNumber(v.accuracy,0,10000)||!validNumber(v.at,Math.max(now-90000,d.command.created_at),now+10000)||!validNumber(v.battery,0,100))bad('Poziție sau precizie invalidă.');
  if(v.charging!==undefined&&typeof v.charging!=='boolean')bad('Încărcare invalidă.');
  if(d.position&&v.at<=d.position.at)bad('Poziție veche.',409);
  d.position={lat:v.lat,lon:v.lon,accuracy:v.accuracy,at:v.at,battery:Math.round(v.battery),command:v.command};keepLast(d,v);
  d.battery=Math.round(v.battery);d.battery_at=now;if(typeof v.charging==='boolean')d.charging=v.charging;
  d.seen_at=now;if(d.command.kind!=='ring')d.status='locating';await s.put(key,d);await alarm(d);return reply({ok:true});
 }
 bad('Acțiune indisponibilă.',404);
}
