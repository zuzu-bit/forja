import {handleJourney,journeyShared,ownJourneyLocation,legacyVisible,revokeJourneyGrant,stopSharing,exploreMeta} from './social-journey.mjs';
import {handleContacts} from './social-contacts.mjs';
import {readJSON,reply} from './insights-store.mjs';
import {bad,keys} from './phone-schema.mjs';
const uidPattern=/^[A-Za-z0-9_-]{1,128}$/;const uuid=/^[a-f0-9-]{36}$/;
const modes=['walk','cycle','out','partner'];const HOUR=3600000;
const text=(v,n=80)=>{if(typeof v!=='string'||!v.trim()||v.length>n||/[\x00-\x1f\x7f]/.test(v))bad('Text invalid.');return v.trim();};
const number=(v,lo,hi)=>{if(typeof v!=='number'||!Number.isFinite(v)||v<lo||v>hi)bad('Valoare invalidă.');return v;};
const point=v=>({lat:number(v.lat,-85,85),lon:number(v.lon,-180,180)});
const mode=v=>{if(!modes.includes(v))bad('Alege mers, cycling sau ieșire.');return v;};
export function liveLocation(p,now=Date.now()){return (p?.session?.continuous===true||p?.session?.until>now)&&p.location?.at>now-120000?p.location:null;}
export function distance(a,b){const rad=Math.PI/180,dlat=(b.lat-a.lat)*rad,dlon=(b.lon-a.lon)*rad;return 6371000*2*Math.asin(Math.min(1,Math.sqrt(Math.sin(dlat/2)**2+Math.cos(a.lat*rad)*Math.cos(b.lat*rad)*Math.sin(dlon/2)**2)));}
/** Serialized social graph. Only the JWT-verifying Worker can set the owner header.
 * No public directory, persisted contact books, historical partner trails or inferred homes.
 * Friendship, blocks and expiring session gates are checked on EVERY read/write. */
export class SocialGraph {
 constructor(ctx){this.ctx=ctx;this.s=ctx.storage;}
 async fetch(request){try{return await this.ctx.blockConcurrencyWhile(()=>this.handle(request));}catch(e){return reply({error:e.status?e.message:'Harta socială nu este disponibilă acum.'},e.status||500);}}
 async get(uid){return this.s.get('user:'+uid);}
 async save(p){await this.s.put('user:'+p.id,p);}
 async expiry(key,at){await this.s.put('expiry:'+key,at);const alarm=await this.s.getAlarm();if(!alarm||alarm>at)await this.s.setAlarm(at);}
 async groupVisible(p,g){if(!g?.members.includes(p.id)||g.at<Date.now()-7*24*HOUR)return false;if(g.owner!==p.id&&!p.friends.includes(g.owner))return false;for(const id of g.members){const f=await this.get(id);if(p.blocked.includes(id)||f?.blocked.includes(p.id))return false;}return true;}
 async friend(a,b){const other=await this.get(b);if(!other||!a.friends.includes(b)||!other.friends.includes(a.id)||a.blocked.includes(b)||other.blocked.includes(a.id))bad('Prietenia nu este activă.',403);return other;}
 async pair(p){const f=p.partner?.state==='accepted'&&await this.get(p.partner.id);return f&&f.partner?.state==='accepted'&&f.partner.id===p.id&&f.partner.token===p.partner.token&&p.friends.includes(f.id)&&f.friends.includes(p.id)&&!p.blocked.includes(f.id)&&!f.blocked.includes(p.id)?f:null;}
 async shared(p,viewer){const personal=journeyShared(p,viewer);if(personal)return personal;if(!legacyVisible(p,viewer))return null;if(p.session?.continuous){const f=await this.pair(p);if(!f||f.id!==viewer||p.session.audience!==f.id)return null;}return liveLocation(p);}
 async unpair(p,f){p.partner=null;f.partner=null;for(const user of [p,f])if(user.session?.continuous){user.session=null;user.location=null;user.checkin=null;await this.s.delete('session-expiry:'+user.id);}}
 async rate(p){const now=Date.now(),r=await this.s.get('rate:'+p.id)||{at:now,n:0};if(now-r.at>60000){r.at=now;r.n=0;}if(++r.n>90)bad('Prea multe acțiuni. Încearcă peste un minut.',429);await this.s.put('rate:'+p.id,r);}
 async handle(req){
  const uid=req.headers.get('x-forja-owner');if(!uidPattern.test(uid||''))bad('Conectează-te în FORJA.',401);
  const path=new URL(req.url).pathname.replace('/v2/social',''),method=req.method,now=Date.now();let p=await this.get(uid);
  if(!p){p={id:uid,name:'Prieten FORJA',code:crypto.randomUUID(),friends:[],incoming:[],outgoing:[],blocked:[],places:[],groups:[],session:null,location:null,history:[]};await this.save(p);await this.s.put('code:'+p.code,uid);}
  if(method!=='GET')await this.rate(p);
  const body=async allowed=>{const {value}=await readJSON(req,8192);keys(value,allowed);return value;};
  const journeyResult=await handleJourney(req,this,p,path);if(journeyResult)return journeyResult;
  const contactResult=await handleContacts(req,this,p,path);if(contactResult)return contactResult;
  // 4.4 site sections (Azi, Livret): when the app's explore mirror and the agenda listing last moved, without friends or cells.
  if(path==='/site-meta'&&method==='GET')return reply({explore:await exploreMeta(this.s,uid),contacts:{discoverable:(p.discovery?.until||0)>now,until:p.discovery?.until||null,verified:p.discovery?.verified===true}});
  if(path==='/state'&&method==='GET'){
   const friends=[];for(const id of p.friends){const f=await this.get(id);if(!f||f.blocked.includes(uid)||p.blocked.includes(id)||!f.friends.includes(uid))continue;const location=await this.shared(f,uid);friends.push({id:f.id,name:f.name,location,mode:location?f.session?.mode||'family':null,checkin:location?f.checkin||null:null});}
   const incoming=[];for(const id of p.incoming){const f=await this.get(id);if(f&&!f.blocked.includes(uid)&&!p.blocked.includes(id))incoming.push({id,name:f.name});}
   p.groups=p.groups.filter((id,i,a)=>a.indexOf(id)===i);const groups=[];for(const id of p.groups){const g=await this.s.get('group:'+id);if(await this.groupVisible(p,g))groups.push(g);}
   return reply({me:{id:uid,name:p.name,code:p.code,session:(p.session?.continuous||p.session?.until>now)?{id:p.session.id,mode:p.session.mode,until:p.session.until,metres:p.session.metres,continuous:p.session.continuous===true,audience:p.session.audience||null}:null,partner:p.partner||null,visibility:p.visibility||{ghost:false,grants:[],configured:false,revision:0,updated_at:0},discoverable:(p.discovery?.until||0)>now,discovery_until:p.discovery?.until||null,location:liveLocation(p)||ownJourneyLocation(p),places:p.places,history:p.history,explored:p.explored||[],blocked:p.blocked},friends,incoming,outgoing:p.outgoing.length,groups});
  }
  if(path==='/profile'&&method==='POST'){const v=await body(['name']);p.name=text(v.name,40);await this.save(p);return reply({ok:true});}
  if(path==='/invite'&&method==='POST'){const v=await body(['code']);if(!uuid.test(v.code||''))bad('Cod de invitație invalid.');const id=await this.s.get('code:'+v.code),f=id&&await this.get(id);if(!f||id===uid||f.blocked.includes(uid)||p.blocked.includes(id))bad('Invitație indisponibilă.',404);if(p.friends.includes(id))return reply({ok:true});if(p.outgoing.length>=50||f.incoming.length>=50||p.friends.length>=100||f.friends.length>=100)bad('Lista de prieteni sau cereri este plină.',409);if(!f.incoming.includes(uid)){f.incoming.push(uid);p.outgoing.push(id);await this.save(f);await this.save(p);}return reply({ok:true});}
  if(path==='/invite-code'&&method==='POST'){await body([]);await this.s.delete('code:'+p.code);p.code=crypto.randomUUID();await this.s.put('code:'+p.code,uid);await this.save(p);return reply({code:p.code});}
  if(path==='/friend'&&method==='POST'){
   const v=await body(['id','action']);if(!uidPattern.test(v.id||'')||v.id===uid||!['accept','reject','remove','block','unblock'].includes(v.action))bad('Acțiune invalidă.');const f=await this.get(v.id);if(!f)bad('Persoană indisponibilă.',404);
   if(v.action==='accept'){if(!p.incoming.includes(f.id)||!f.outgoing.includes(uid)||p.blocked.includes(f.id)||f.blocked.includes(uid))bad('Cerere indisponibilă.',409);if(p.friends.length>=100||f.friends.length>=100)bad('Maximum 100 de prieteni.',409);p.friends=[...new Set([...p.friends,f.id])];f.friends=[...new Set([...f.friends,uid])];}
   if(['remove','block'].includes(v.action)){revokeJourneyGrant(p,f.id);revokeJourneyGrant(f,uid);if(p.partner?.id===f.id||f.partner?.id===uid)await this.unpair(p,f);p.friends=p.friends.filter(id=>id!==f.id);f.friends=f.friends.filter(id=>id!==uid);await this.s.delete('chat:'+ [uid,f.id].sort().join(':'));}
   if(v.action==='block'){if(p.blocked.length>=200)bad('Lista de blocări este plină.',409);p.blocked=[...new Set([...p.blocked,f.id])];}
   if(v.action==='unblock')p.blocked=p.blocked.filter(id=>id!==f.id);
   p.incoming=p.incoming.filter(id=>id!==f.id);p.outgoing=p.outgoing.filter(id=>id!==f.id);f.incoming=f.incoming.filter(id=>id!==uid);f.outgoing=f.outgoing.filter(id=>id!==uid);await this.save(f);await this.save(p);return reply({ok:true});
  }

  if(path==='/partner'&&method==='POST'){
   const v=await body(['id','action']);if(!uidPattern.test(v.id||'')||v.id===uid||!['invite','accept','decline','disconnect'].includes(v.action))bad('Acțiune invalidă.');const f=await this.get(v.id);if(!f)bad('Partener indisponibil.',404);
   if(v.action==='invite'){await this.friend(p,f.id);if(p.partner||f.partner)bad('Există deja o asociere sau invitație de cuplu. Închide-o întâi.',409);const token=crypto.randomUUID();p.partner={id:f.id,name:f.name,state:'requested',token};f.partner={id:uid,name:p.name,state:'invited',token};}
   if(v.action==='accept'){await this.friend(p,f.id);if(p.partner?.state!=='invited'||p.partner.id!==f.id||f.partner?.state!=='requested'||f.partner.id!==uid||p.partner.token!==f.partner.token)bad('Invitația nu mai este activă.',409);p.partner.state='accepted';f.partner.state='accepted';}
   if(['decline','disconnect'].includes(v.action)){if(p.partner?.id!==f.id||f.partner?.id!==uid)bad('Nu există această asociere.',409);await this.unpair(p,f);}
   await this.save(f);await this.save(p);return reply({ok:true});
  }
  if(path==='/session'&&method==='POST'){
   const {value:v}=await readJSON(req,2048);keys(v,['mode','minutes','consent','continuous','audience'],['mode','minutes','consent']);if(v.consent!==true)bad('Confirmă partajarea locației.',403);mode(v.mode);
   if(v.continuous!==undefined&&typeof v.continuous!=='boolean')bad('Mod invalid.');const continuous=v.continuous===true;
   if(continuous){const f=await this.pair(p);if(!f||v.audience!==f.id||v.mode!=='partner'||v.minutes!==0)bad('Partajarea continuă necesită un partener acceptat și ales explicit.',403);}else{if(v.mode==='partner'||v.audience!==undefined)bad('Alege modul de partajare continuă pentru partener.');number(v.minutes,5,240);} 
   if(p.session?.continuous||p.session?.until>now)bad('Oprește întâi sesiunea activă.',409);
   if(p.visibility?.configured){const allowed=p.visibility.grants.filter(g=>g.current&&(!continuous||g.id===v.audience));let audience=false;for(const g of allowed){try{await this.friend(p,g.id);audience=true;}catch{}}if(!audience)bad('Alege cine te vede în Vizibilitate.',403);}
   if(p.visibility)p.visibility.ghost=false;
   p.session={id:crypto.randomUUID(),mode:v.mode,from:now,until:continuous?0:now+v.minutes*60000,metres:0,points:0,...(continuous?{continuous:true,audience:v.audience}:{})};p.location=null;p.checkin=null;await this.save(p);if(!continuous){await this.s.put('session-expiry:'+uid,p.session.until);const alarm=await this.s.getAlarm();if(!alarm||alarm>p.session.until)await this.s.setAlarm(p.session.until);}else await this.s.delete('session-expiry:'+uid);return reply(p.session);
  }
  if(path==='/session'&&method==='DELETE'){const expected=new URL(req.url).searchParams.get('session');if(expected&&p.session?.id!==expected)bad('Sesiunea s-a schimbat.',409);if(p.session&&!p.session.continuous){p.history=[{mode:p.session.mode,from:p.session.from,to:Math.min(now,p.session.until),metres:p.session.metres,points:p.session.points},...p.history].slice(0,30);}p.session=null;p.location=null;p.checkin=null;stopSharing(p);await this.save(p);await this.s.delete('session-expiry:'+uid);return reply({ok:true});}
  if(path==='/location'&&method==='POST'){
   const v=await body(['session','lat','lon','accuracy','speed','battery','at']);if(!p.session||p.session.id!==v.session||(!p.session.continuous&&p.session.until<=now)||p.session.continuous&&(!await this.pair(p)||p.session.audience!==p.partner.id))bad('Sesiunea de partajare s-a oprit.',409);point(v);number(v.accuracy,0,10000);number(v.speed,0,100);number(v.battery,0,100);number(v.at,now-90000,now+10000);if(p.location&&v.at<=p.location.at)bad('Poziție veche.',409);
   const old=p.location;const loc={...point(v),accuracy:v.accuracy,speed:v.speed,battery:Math.round(v.battery),at:v.at,since:old&&distance(old,v)<Math.max(40,v.accuracy,old.accuracy)?old.since:now};
   if(!p.session.continuous&&old&&v.accuracy<=50&&old.accuracy<=50&&v.at-old.at<90000){const d=distance(old,v),dt=(v.at-old.at)/1000;if(d>Math.max(10,v.accuracy)&&d/dt<(p.session.mode==='walk'?4.5:20))p.session.metres+=Math.round(d);}
   p.location=loc;if(!p.session.continuous)p.session.points++;if(!p.session.continuous&&v.accuracy<=50){const cell=[Math.floor(v.lat*1000)/1000,Math.floor(v.lon*1000)/1000];p.explored=[cell,...(p.explored||[]).filter(c=>c[0]!==cell[0]||c[1]!==cell[1])].slice(0,500);}await this.save(p);if(p.session.continuous)await this.expiry('last-position:'+uid,loc.at+120000);return reply({ok:true,metres:p.session.metres});
  }
  if(path==='/place'&&method==='POST'){const v=await body(['name','lat','lon']);if(p.places.length>=100)bad('Maximum 100 de locuri salvate.',409);const place={id:crypto.randomUUID(),name:text(v.name),...point(v)};p.places.push(place);await this.save(p);return reply(place);}
  if(path==='/place'&&method==='DELETE'){const v=await body(['id']);p.places=p.places.filter(x=>x.id!==v.id);await this.save(p);return reply({ok:true});}
  if(path==='/checkin'&&method==='POST'){const v=await body(['name']);if(!liveLocation(p))bad('Pornește partajarea și așteaptă o poziție recentă.',409);p.checkin={name:text(v.name),at:now};await this.save(p);return reply({ok:true});}
  if(path==='/chat'&&method==='GET'){const id=new URL(req.url).searchParams.get('friend');if(!uidPattern.test(id||''))bad('Prieten invalid.');await this.friend(p,id);const key='chat:'+ [uid,id].sort().join(':'),chat=(await this.s.get(key)||[]).filter(x=>x.at>now-7*24*HOUR);await this.s.put(key,chat);return reply({messages:chat});}
  if(path==='/chat'&&method==='POST'){const v=await body(['friend','id','text']);if(!uuid.test(v.id||''))bad('Mesaj invalid.');await this.friend(p,v.friend);const key='chat:'+ [uid,v.friend].sort().join(':'),messages=(await this.s.get(key)||[]).filter(x=>x.at>now-7*24*HOUR);const message={id:v.id,from:uid,text:text(v.text,1000),at:now};if(!messages.some(x=>x.id===v.id)){messages.push(message);await this.s.put(key,messages.slice(-100));await this.expiry(key,messages.slice(-100)[0].at+7*24*HOUR);}return reply({ok:true});}
  if(path==='/group'&&method==='POST'){const v=await body(['name','mode','at','place','lat','lon','friends']);if(!Array.isArray(v.friends)||v.friends.length>19||new Set(v.friends).size!==v.friends.length||p.groups.length>=30)bad('Maximum 20 participanți și 30 grupuri.');const members=[];for(const id of v.friends){const f=await this.friend(p,id);if(f.groups.length>=30)bad('Un prieten are deja 30 grupuri.',409);members.push(f);}const g={id:crypto.randomUUID(),owner:uid,name:text(v.name),mode:(v.mode==='partner'?bad('Alege mers, cycling sau ieșire.'):mode(v.mode)),at:number(v.at,now-60000,now+30*24*HOUR),place:text(v.place),...point(v),members:[uid,...v.friends],going:[uid]};await this.s.put('group:'+g.id,g);await this.expiry('group:'+g.id,g.at+7*24*HOUR);for(const f of [p,...members]){f.groups.push(g.id);await this.save(f);}return reply(g);}
  if(path==='/group-response'&&method==='POST'){const v=await body(['id','going']);const g=await this.s.get('group:'+v.id);if(!await this.groupVisible(p,g)||typeof v.going!=='boolean')bad('Grup indisponibil.',403);g.going=g.going.filter(id=>id!==uid);if(v.going)g.going.push(uid);await this.s.put('group:'+g.id,g);return reply({ok:true});}
  if(path==='/leave-group'&&method==='POST'){const v=await body(['id']);const g=await this.s.get('group:'+v.id);if(g){g.members=g.members.filter(id=>id!==uid);g.going=g.going.filter(id=>id!==uid);await this.s.put('group:'+g.id,g);}p.groups=p.groups.filter(id=>id!==v.id);await this.save(p);return reply({ok:true});}
  bad('Acțiune indisponibilă.',404);
 }
 async alarm(){await this.ctx.blockConcurrencyWhile(async()=>{let next=Infinity;for(const [key,until]of await this.s.list({prefix:'expiry:'})){if(until>Date.now()){next=Math.min(next,until);continue;}const target=key.slice(7);if(target.startsWith('last-position:')){const user=await this.get(target.slice(14));if(user?.location?.at<=Date.now()-120000){user.location=null;user.checkin=null;await this.save(user);}}if(target.startsWith('chat:')){const kept=(await this.s.get(target)||[]).filter(x=>x.at>Date.now()-7*24*HOUR);if(kept.length){await this.s.put(target,kept);const at=kept[0].at+7*24*HOUR;await this.s.put(key,at);next=Math.min(next,at);continue;}}if(target.startsWith('group:')){const g=await this.s.get(target);for(const id of g?.members||[]){const f=await this.get(id);if(f){f.groups=f.groups.filter(v=>v!==g.id);await this.save(f);}}}await this.s.delete(target);await this.s.delete(key);}for(const [key,until]of await this.s.list({prefix:'session-expiry:'})){if(until>Date.now()){next=Math.min(next,until);continue;}const p=await this.get(key.slice(15));if(p){if(p.session)p.history=[{mode:p.session.mode,from:p.session.from,to:p.session.until,metres:p.session.metres,points:p.session.points},...p.history].slice(0,30);p.session=null;p.location=null;p.checkin=null;await this.save(p);}await this.s.delete(key);}if(Number.isFinite(next))await this.s.setAlarm(next);});}
}
