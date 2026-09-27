import {bad,keys} from './phone-schema.mjs';
import {reply,readJSON} from './insights-store.mjs';
export const isPhone=v=>typeof v==='string'&&/^\+[1-9][0-9]{7,14}$/.test(v);
const DAY=86400000,LISTING_TTL=30*DAY,AGENDA_MAX=5000,AGENDA_PREFIX=16;
export async function contactHash(graph,value){
 if(!graph.contactKey){let raw=await graph.s.get('contacts-key');if(!raw){raw=[...crypto.getRandomValues(new Uint8Array(32))];await graph.s.put('contacts-key',raw);}graph.contactKey=await crypto.subtle.importKey('raw',new Uint8Array(raw),{name:'HMAC',hash:'SHA-256'},false,['sign']);}
 return [...new Uint8Array(await crypto.subtle.sign('HMAC',graph.contactKey,new TextEncoder().encode(value)))].map(x=>x.toString(16).padStart(2,'0')).join('');
}
/** Two trust levels for MY number: verified (phone_number claim in the Firebase token, set by the gateway as x-forja-phone)
 * or declared (typed in the app, forwarded by the gateway ONLY as x-forja-phone-declared). Verified always wins. */
async function identity(req,g){const verifiedPhone=req.headers.get('x-forja-phone'),declared=req.headers.get('x-forja-phone-declared');const phone=isPhone(verifiedPhone)?verifiedPhone:isPhone(declared)?declared:null;if(!phone)bad('Adaugă numărul tău în aplicație sau verifică-l prin SMS.',403);return{hash:await contactHash(g,'phone|'+phone),verified:isPhone(verifiedPhone)};}
/** Only the agenda fingerprints of THIS account (truncated HMACs, never numbers or names), used for reciprocity. */
const agendaKey=uid=>'contacts-of:'+uid;
const short=hash=>hash.slice(0,AGENDA_PREFIX);
async function rememberAgenda(g,uid,hashes,now){const set=new Set(await g.s.get(agendaKey(uid))||[]);for(const h of hashes)set.add(short(h));const list=[...set].slice(-AGENDA_MAX);await g.s.put(agendaKey(uid),list);await g.expiry(agendaKey(uid),now+LISTING_TTL);}
async function hasInAgenda(g,uid,hash){const list=await g.s.get(agendaKey(uid));return Array.isArray(list)&&list.includes(short(hash));}
async function forgetAgenda(g,uid){await g.s.delete(agendaKey(uid));await g.s.delete('expiry:'+agendaKey(uid));}
const listingOk=(p,own,now)=>p.discovery&&p.discovery.hash===own&&p.discovery.until>now;
export async function handleContacts(req,g,p,path){
 if(!path.startsWith('/contacts/'))return null;const now=Date.now(),uid=p.id;
 const body=async fields=>{const {value}=await readJSON(req,12000);keys(value,fields);return value;};
 if(path==='/contacts/discovery'&&req.method==='DELETE'){if(p.discovery){const key='phone:'+p.discovery.hash;const entry=await g.s.get(key);if(entry?.uid===uid){await g.s.delete(key);await g.s.delete('expiry:'+key);}}p.discovery=null;await forgetAgenda(g,uid);await g.save(p);return reply({ok:true});}
 if(path==='/contacts/discovery'&&req.method==='POST'){
  const v=await body(['consent']);if(v.consent!==true)bad('Confirmă că poți fi găsit după numărul tău.',403);const {hash,verified}=await identity(req,g);const issued=Number(req.headers.get('x-forja-token-issued'));if(!Number.isSafeInteger(issued)||Math.abs(now-issued*1000)>300000)bad('Actualizează autentificarea înainte să confirmi numărul.',401);
  const current=await g.s.get('phone:'+hash);
  // A declared number never takes over a listing of ANOTHER account that is still active (verified or declared). A verified one does.
  if(current&&current.uid!==uid&&current.until>now&&!verified)bad('Numărul e folosit deja de alt cont. Verifică-l prin SMS ca să-l revendici.',409);
  if(p.discovery&&p.discovery.hash!==hash){const key='phone:'+p.discovery.hash;const old=await g.s.get(key);if(old?.uid===uid){await g.s.delete(key);await g.s.delete('expiry:'+key);}}
  p.discovery={hash,until:now+LISTING_TTL,verified};await g.s.put('phone:'+hash,{uid,until:p.discovery.until,verified});await g.expiry('phone:'+hash,p.discovery.until);await g.save(p);return reply({ok:true,until:p.discovery.until,verified});
 }
 if(path==='/contacts/match'&&req.method==='POST'){
  const v=await body(['numbers','consent']);if(v.consent!==true)bad('Confirmă folosirea numerelor din agendă pentru găsirea prietenilor.',403);if(!Array.isArray(v.numbers)||v.numbers.length<1||v.numbers.length>200||v.numbers.some(n=>!isPhone(n)))bad('Trimite cel mult 200 numere în format internațional.');const {hash:ownHash}=await identity(req,g);if(!listingOk(p,ownHash,now)||(await g.s.get('phone:'+ownHash))?.uid!==uid)bad('Activează întâi găsirea după propriul număr.',403);
  const day=Math.floor(now/DAY),old=await g.s.get('contact-budget:'+uid),budget=old?.day===day?old:{day,n:0};const numbers=[...new Set(v.numbers)];if(budget.n+numbers.length>10000)bad('Limita este 10.000 numere verificate pe zi. Reîncearcă mâine.',429);budget.n+=numbers.length;await g.s.put('contact-budget:'+uid,budget);
  if(p.discovery.until-now<29*DAY){p.discovery.until=now+LISTING_TTL;await g.s.put('phone:'+ownHash,{uid,until:p.discovery.until,verified:p.discovery.verified===true});await g.expiry('phone:'+ownHash,p.discovery.until);}
  const hashes=new Map();for(const n of numbers)hashes.set(n,await contactHash(g,'phone|'+n));
  await rememberAgenda(g,uid,[...hashes.values()].filter(h=>h!==ownHash),now);
  const matches=[];let changed=false;
  for(let i=0;i<v.numbers.length;i++){const hash=hashes.get(v.numbers[i]);const entry=await g.s.get('phone:'+hash);if(!entry||entry.until<=now||entry.uid===uid)continue;const f=await g.get(entry.uid);if(!f||f.discovery?.hash!==hash||f.discovery.until<=now||f.blocked.includes(uid)||p.blocked.includes(f.id))continue;
   // Reciprocity: my fingerprint is in HIS agenda too → friends on the site graph, no request needed.
   const mutual=await hasInAgenda(g,f.id,ownHash);let friend=p.friends.includes(f.id)&&f.friends.includes(uid);
   if(mutual&&!friend&&p.friends.length<100&&f.friends.length<100){if(!p.friends.includes(f.id))p.friends.push(f.id);if(!f.friends.includes(uid))f.friends.push(uid);p.incoming=p.incoming.filter(x=>x!==f.id);p.outgoing=p.outgoing.filter(x=>x!==f.id);f.incoming=f.incoming.filter(x=>x!==uid);f.outgoing=f.outgoing.filter(x=>x!==uid);await g.save(f);friend=true;changed=true;}
   const until=now+600000;const proof=until+'.'+await contactHash(g,`invite|${uid}|${f.id}|${hash}|${until}`);matches.push({index:i,id:f.id,name:f.name,friend,pending:!friend&&p.outgoing.includes(f.id),mutual,verified:entry.verified===true,proof});}
  if(changed||p.discovery.until>now+29*DAY)await g.save(p);
  // Contact names and numbers are not written to storage, logs or responses; only truncated HMACs of MY agenda are kept for reciprocity.
  return reply({matches,remaining:10000-budget.n});
 }
 if(path==='/contacts/invite'&&req.method==='POST'){
  const {hash:ownHash}=await identity(req,g);if(!listingOk(p,ownHash,now)||(await g.s.get('phone:'+ownHash))?.uid!==uid)bad('Activează găsirea după număr.',403);
  const v=await body(['id','proof']);if(typeof v.id!=='string'||typeof v.proof!=='string')bad('Contact invalid.');const f=await g.get(v.id);const parts=v.proof.split('.'),until=Number(parts[0]);if(!f?.discovery||f.discovery.until<=now||parts.length!==2||!Number.isSafeInteger(until)||until<=now||until>now+600000||parts[1]!==await contactHash(g,`invite|${uid}|${f.id}|${f.discovery.hash}|${until}`)||f.blocked.includes(uid)||p.blocked.includes(f.id))bad('Reîmprospătează contactele înainte de cerere.',403);
  if(p.friends.includes(f.id)&&f.friends.includes(uid))return reply({ok:true});if(p.outgoing.length>=50||f.incoming.length>=50||p.friends.length>=100||f.friends.length>=100)bad('Lista de prieteni sau cereri este plină.',409);
  if(!f.incoming.includes(uid)){f.incoming.push(uid);p.outgoing.push(f.id);await g.save(f);await g.save(p);}return reply({ok:true});
 }
 bad('Acțiune pentru contacte indisponibilă.',404);
}
