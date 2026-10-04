import {readJSON,reply} from './insights-store.mjs';
import {bad,keys,idPattern} from './phone-schema.mjs';

export const JOURNEY_RULES=Object.freeze({gap_ms:300000,radius_m:100,accuracy_m:50,grid_m:200,visit_ms:18000000});
const DAY=86400000, PAGE=100, uid=/^[A-Za-z0-9_-]{1,128}$/;
const finite=(v,a,b)=>{if(typeof v!=='number'||!Number.isFinite(v)||v<a||v>b)bad('Poziție invalidă.');return v;};
const clean=(v,n)=>{if(typeof v!=='string'||!v.trim()||v.length>n||/[\x00-\x1f\x7f]/.test(v))bad('Text invalid.');return v.trim();};
const dist=(a,b)=>{const r=Math.PI/180;return 12742000*Math.asin(Math.min(1,Math.sqrt(Math.sin((b.lat-a.lat)*r/2)**2+Math.cos(a.lat*r)*Math.cos(b.lat*r)*Math.sin((b.lon-a.lon)*r/2)**2)));};
const prefix=(id,kind)=>'journey:'+id+':'+kind+':';
const fc=features=>({type:'FeatureCollection',features});
const feature=(geometry,properties)=>({type:'Feature',geometry,properties});
const stamp=t=>String(Math.floor(t)).padStart(13,'0');
function grid(p){const R=6378137,size=JOURNEY_RULES.grid_m,r=Math.PI/180,x=Math.floor(R*p.lon*r/size),y=Math.floor(R*Math.log(Math.tan(Math.PI/4+p.lat*r/2))/size),ll=(a,b)=>[a*size/R/r,(2*Math.atan(Math.exp(b*size/R))-Math.PI/2)/r];return{id:x+','+y,geometry:{type:'Polygon',coordinates:[[ll(x,y),ll(x+1,y),ll(x+1,y+1),ll(x,y+1),ll(x,y)]]}};}
function permitted(p,viewer,kind){const v=p.visibility,g=v?.grants?.find(g=>g.id===viewer);return !!g?.[kind]&&(kind==='history'||!v.ghost||g.ghost);}
export function journeyShared(p,viewer,now=Date.now()){
 if(!permitted(p,viewer,'current'))return null;
 const loc=p.journeyLatest;
 return p.journeySession&&loc?.at>now-120000?{...loc,battery:null}:null;
}
export function legacyVisible(p,viewer){return p.visibility?.configured?permitted(p,viewer,'current'):!p.visibility?.ghost||permitted(p,viewer,'current');}
export function ownJourneyLocation(p,now=Date.now()){return p.journeySession&&p.journeyLatest?.at>now-120000?{...p.journeyLatest,battery:null}:null;}
export function revokeJourneyGrant(p,id){if(p.visibility?.grants.some(g=>g.id===id)){p.visibility.grants=p.visibility.grants.filter(g=>g.id!==id);p.visibility.revision=(p.visibility.revision||0)+1;p.visibility.updated_at=Date.now();}}
export function stopSharing(p){p.visibility={ghost:true,grants:[],configured:p.visibility?.configured===true,revision:(p.visibility?.revision||0)+1,updated_at:Date.now()};}

async function read(req,names,required=names){const {value}=await readJSON(req,32768);keys(value,names,required);return value;}
async function page(s,id,kind,cursor){if(cursor==='-')return{values:[],next:'-'};const p=prefix(id,kind);if(cursor&&(!cursor.startsWith(p)||cursor.length>300))bad('Pagina nu este validă.');const rows=[...await s.list({prefix:p,limit:PAGE+1,...(cursor?{startAfter:cursor}:{})})];return{values:rows.slice(0,PAGE).map(([,v])=>v),next:rows.length>PAGE?rows[PAGE-1][0]:'-'};}
function decodeCursor(value){if(!value)return{};try{const c=JSON.parse(atob(value));keys(c,['route','zone','visit']);for(const v of Object.values(c))if(typeof v!=='string'||v.length>300)throw Error();return c;}catch{bad('Pagina nu este validă.');}}

export async function handleJourney(req,graph,p,path){
 const method=req.method,s=graph.s,url=new URL(req.url),now=Date.now();
 if(path==='/visibility'&&method==='GET')return reply(p.visibility||{ghost:false,grants:[],configured:false,revision:0,updated_at:0});
 if(path==='/visibility'&&method==='POST'){
  const v=await read(req,['ghost','grants','consent','revision']);if(v.consent!==true)bad('Confirmă persoanele alese.',403);
  if(!Number.isSafeInteger(v.revision)||v.revision!==(p.visibility?.revision||0))bad('Vizibilitatea s-a schimbat. Reîncarcă persoanele alese.',409);
  if(typeof v.ghost!=='boolean'||!Array.isArray(v.grants)||v.grants.length>100)bad('Vizibilitate invalidă.');
  const seen=new Set();for(const g of v.grants){keys(g,['id','current','ghost','history']);if(!uid.test(g.id||'')||seen.has(g.id)||[g.current,g.ghost,g.history].some(x=>typeof x!=='boolean')||g.ghost&&!g.current)bad('Alege separat locația și istoricul.');seen.add(g.id);await graph.friend(p,g.id);}
  p.visibility={ghost:v.ghost,grants:v.grants.filter(g=>g.current||g.history),configured:true,revision:v.revision+1,updated_at:now};await graph.save(p);return reply(p.visibility);
 }
 if(!path.startsWith('/journey/'))return null;
 if(path==='/journey/state'&&method==='GET'){
  const owner=url.searchParams.get('owner')||p.id;let target=p;
  if(owner!==p.id){if(!uid.test(owner))bad('Persoană invalidă.');target=await graph.friend(p,owner);if(!permitted(target,p.id,'history'))bad('Istoricul nu este partajat cu tine.',403);}
  const c=decodeCursor(url.searchParams.get('cursor')),pages={};for(const kind of ['route','zone','visit'])pages[kind]=await page(s,owner,kind,c[kind]);
  const next=Object.fromEntries(Object.entries(pages).map(([k,v])=>[k,v.next]));
  return reply({owner,session:owner===p.id?p.journeySession||null:null,last_sample_at:owner===p.id?p.journeyLatest?.at||null:null,routes:fc(pages.route.values),zones:fc(pages.zone.values),visits:pages.visit.values,next_cursor:Object.values(next).every(v=>v==='-')?null:btoa(JSON.stringify(next)),rules:JOURNEY_RULES});
 }
 if(path==='/journey/session'&&method==='POST'){
  const v=await read(req,['id','consent']);if(v.consent!==true)bad('Activează jurnalul personal.',403);if(!idPattern.test(v.id||''))bad('Sesiune invalidă.');
  // Closed IDs may never reopen: delayed retries cannot restart recording after Stop.
  if(await s.get(prefix(p.id,'closed')+v.id))bad('Această sesiune a fost închisă.',409);
  if(p.journeySession){if(p.journeySession.id!==v.id)bad('Jurnalul este deja pornit pe un dispozitiv.',409);return reply(p.journeySession);}
  p.journeySession={id:v.id,started_at:now};p.journeyCandidate=null;p.journeyLatest=null;p.journeyLastAt=0;await graph.save(p);return reply(p.journeySession);
 }
 if(path==='/journey/session'&&method==='DELETE'){
  const expected=url.searchParams.get('session');if(!expected||!idPattern.test(expected))bad('Sesiune invalidă.');
  if(p.journeySession&&p.journeySession.id!==expected)bad('Sesiunea s-a schimbat.',409);
  if(!p.journeySession&&!await s.get(prefix(p.id,'closed')+expected))bad('Sesiune indisponibilă.',409);
  p.journeySession=null;p.journeyLatest=null;p.journeyCandidate=null;await s.transaction(async tx=>{await tx.put(prefix(p.id,'closed')+expected,{at:now});await tx.put('user:'+p.id,p);});return reply({ok:true});
 }
 if(path==='/journey/samples'&&method==='POST'){
  const v=await read(req,['session','id','points']);if(!idPattern.test(v.id||'')||!Array.isArray(v.points)||!v.points.length||v.points.length>50)bad('Lot invalid.');
  if(!p.journeySession||p.journeySession.id!==v.session||await s.get(prefix(p.id,'closed')+v.session))bad('Jurnalul s-a oprit.',409);
  let prior=0;for(const q of v.points){keys(q,['at','lat','lon','accuracy','speed']);finite(q.at,Math.max(now-7*DAY,p.journeySession.started_at),now+10000);finite(q.lat,-85,85);finite(q.lon,-180,180);finite(q.accuracy,0,10000);finite(q.speed,0,100);if(!Number.isSafeInteger(q.at)||q.at<=prior)bad('Pozițiile trebuie să fie în ordine.');prior=q.at;}
  const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(v))))).map(x=>x.toString(16).padStart(2,'0')).join('');
  const oldBatch=(p.journeyBatches||[]).find(b=>b.id===v.id);if(oldBatch){if(oldBatch.digest!==digest)bad('Lotul s-a schimbat.',409);return reply({ok:true,accepted:oldBatch.accepted,replayed:true});}
  const writes=new Map(),get=async key=>writes.has(key)?writes.get(key):await s.get(key),put=async(key,value)=>writes.set(key,value);
  let accepted=0;
  for(const q of v.points){if(q.at<=(p.journeyLastAt||0))continue;const old=p.journeyLatest;const gap=old?q.at-old.at:Infinity;p.journeyLastAt=q.at;accepted++;
   if(q.accuracy>JOURNEY_RULES.accuracy_m){p.journeyLatest=null;p.journeyCandidate=null;continue;}
   if(old&&gap<=JOURNEY_RULES.gap_ms&&dist(old,q)/(gap/1000)>50){p.journeyCandidate=null;continue;}
   const connected=old&&gap<=JOURNEY_RULES.gap_ms&&dist(old,q)/(gap/1000)<=50;
   const cell=grid(q),zoneKey=prefix(p.id,'zone')+cell.id;const zone=await get(zoneKey);if(!zone)await put(zoneKey,feature(cell.geometry,{id:cell.id,first_seen:q.at,kind:'explored'}));
   if(connected&&dist(old,q)>5)await put(prefix(p.id,'route')+stamp(old.at)+'-'+stamp(q.at),feature({type:'LineString',coordinates:[[old.lon,old.lat],[q.lon,q.lat]]},{id:old.at+'-'+q.at,from:old.at,to:q.at,kind:'route'}));
   let c=p.journeyCandidate;
   if(!connected||!c||dist(c,q)>JOURNEY_RULES.radius_m||q.speed>2.5||old.speed>2.5)c={id:crypto.randomUUID(),lat:q.lat,lon:q.lon,from:q.at,to:q.at,observed_ms:0};
   else{c.observed_ms+=gap;c.to=q.at;}
   if(c.observed_ms>JOURNEY_RULES.visit_ms){const key=prefix(p.id,'visit')+c.id,existing=await get(key);await put(key,{...c,name:existing?.name||p.places?.find(place=>dist(place,c)<=JOURNEY_RULES.radius_m)?.name||'Loc vizitat',rating:existing?.rating||null});}
   p.journeyCandidate=c;p.journeyLatest={...q};
  }
  p.journeyBatches=[...(p.journeyBatches||[]),{id:v.id,digest,accepted}].slice(-200);writes.set('user:'+p.id,p);await s.transaction(async tx=>{for(const [key,value] of writes)await tx.put(key,value);});return reply({ok:true,accepted,last_sample_at:p.journeyLatest?.at||null});
 }
 const match=path.match(/^\/journey\/visits\/([a-f0-9-]{36})$/i);
 if(match&&['PATCH','DELETE'].includes(method)){
  const key=prefix(p.id,'visit')+match[1],visit=await s.get(key);if(!visit)bad('Loc indisponibil.',404);
  if(method==='PATCH'){const v=await read(req,['name','rating'],[]);if(!Object.keys(v).length)bad('Alege un nume sau un rating.');if(v.name!==undefined)visit.name=clean(v.name,80);if(v.rating!==undefined){finite(v.rating,1,5);if(!Number.isInteger(v.rating))bad('Rating invalid.');visit.rating=v.rating;}await s.put(key,visit);return reply(visit);}
  await s.delete(key);if(p.journeyCandidate?.id===visit.id){p.journeyCandidate=null;await graph.save(p);}return reply({ok:true});
 }
 if(path==='/journey/history'&&method==='DELETE'){
  // Stop the recording generation before deletion; old queued samples cannot restore it.
  const closing=p.journeySession?.id;p.journeySession=null;p.journeyCandidate=null;p.journeyLatest=null;p.journeyBatches=[];await s.transaction(async tx=>{if(closing)await tx.put(prefix(p.id,'closed')+closing,{at:now});await tx.put('user:'+p.id,p);});
  for(const kind of ['route','zone','visit']){let rows;do{rows=await s.list({prefix:prefix(p.id,kind),limit:500});for(const key of rows.keys())await s.delete(key);}while(rows.size===500);}return reply({ok:true});
 }
 if(path==='/journey/recommend'&&method==='POST'){
  const v=await read(req,['visit','friend','id']);if(!idPattern.test(v.id||'')||!idPattern.test(v.visit||''))bad('Recomandare invalidă.');await graph.friend(p,v.friend);const visit=await s.get(prefix(p.id,'visit')+v.visit);if(!visit)bad('Loc indisponibil.',404);
  const key='chat:'+ [p.id,v.friend].sort().join(':'),messages=(await s.get(key)||[]).filter(x=>x.at>now-7*DAY);if(!messages.some(x=>x.id===v.id)){messages.push({id:v.id,from:p.id,text:'Îți recomand '+visit.name+(visit.rating?' · '+visit.rating+'/5':''),at:now,place:{name:visit.name,lat:visit.lat,lon:visit.lon,rating:visit.rating}});await s.put(key,messages.slice(-100));await graph.expiry(key,messages.slice(-100)[0].at+7*DAY);}return reply({ok:true});
 }
 bad('Acțiune indisponibilă.',404);
}
