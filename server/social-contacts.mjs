import {bad,keys} from './phone-schema.mjs';
import {reply,readJSON} from './insights-store.mjs';
export const isPhone=v=>typeof v==='string'&&/^\+[1-9][0-9]{7,14}$/.test(v);
export async function contactHash(graph,value){
 if(!graph.contactKey){let raw=await graph.s.get('contacts-key');if(!raw){raw=[...crypto.getRandomValues(new Uint8Array(32))];await graph.s.put('contacts-key',raw);}graph.contactKey=await crypto.subtle.importKey('raw',new Uint8Array(raw),{name:'HMAC',hash:'SHA-256'},false,['sign']);}
 return [...new Uint8Array(await crypto.subtle.sign('HMAC',graph.contactKey,new TextEncoder().encode(value)))].map(x=>x.toString(16).padStart(2,'0')).join('');
}
async function verified(req,g,p){const phone=req.headers.get('x-forja-phone');if(!isPhone(phone))bad('Verifică numărul tău prin SMS în aplicație.',403);return contactHash(g,'phone|'+phone);}
export async function handleContacts(req,g,p,path){
 if(!path.startsWith('/contacts/'))return null;const now=Date.now(),uid=p.id;
 const body=async fields=>{const {value}=await readJSON(req,12000);keys(value,fields);return value;};
 if(path==='/contacts/discovery'&&req.method==='DELETE'){if(p.discovery){const key='phone:'+p.discovery.hash;const entry=await g.s.get(key);if(entry?.uid===uid)await g.s.delete(key);}p.discovery=null;await g.save(p);return reply({ok:true});}
 if(path==='/contacts/discovery'&&req.method==='POST'){
  const v=await body(['consent']);if(v.consent!==true)bad('Confirmă că poți fi găsit după numărul tău.',403);const hash=await verified(req,g,p);const issued=Number(req.headers.get('x-forja-token-issued'));if(!Number.isSafeInteger(issued)||Math.abs(now-issued*1000)>300000)bad('Actualizează autentificarea înainte să confirmi numărul.',401);
  if(p.discovery&&p.discovery.hash!==hash){const key='phone:'+p.discovery.hash;const old=await g.s.get(key);if(old?.uid===uid)await g.s.delete(key);}
  p.discovery={hash,until:now+30*86400000};await g.s.put('phone:'+hash,{uid,until:p.discovery.until});await g.expiry('phone:'+hash,p.discovery.until);await g.save(p);return reply({ok:true,until:p.discovery.until});
 }
 if(path==='/contacts/match'&&req.method==='POST'){
  const v=await body(['numbers','consent']);if(v.consent!==true)bad('Confirmă folosirea numerelor din agendă pentru găsirea prietenilor.',403);if(!Array.isArray(v.numbers)||v.numbers.length<1||v.numbers.length>200||v.numbers.some(n=>!isPhone(n)))bad('Trimite cel mult 200 numere în format internațional.');const ownHash=await verified(req,g,p);if(!p.discovery||p.discovery.hash!==ownHash||p.discovery.until<=now||(await g.s.get('phone:'+ownHash))?.uid!==uid)bad('Activează întâi găsirea după propriul număr.',403);
  const day=Math.floor(now/86400000),old=await g.s.get('contact-budget:'+uid),budget=old?.day===day?old:{day,n:0};const numbers=[...new Set(v.numbers)];if(budget.n+numbers.length>10000)bad('Limita este 10.000 numere verificate pe zi. Reîncearcă mâine.',429);budget.n+=numbers.length;await g.s.put('contact-budget:'+uid,budget);
  if(p.discovery.until-now<29*86400000){p.discovery.until=now+30*86400000;await g.s.put('phone:'+ownHash,{uid,until:p.discovery.until});await g.expiry('phone:'+ownHash,p.discovery.until);await g.save(p);}
  const matches=[];for(let i=0;i<v.numbers.length;i++){const hash=await contactHash(g,'phone|'+v.numbers[i]);const entry=await g.s.get('phone:'+hash);if(!entry||entry.until<=now||entry.uid===uid)continue;const f=await g.get(entry.uid);if(!f||f.discovery?.hash!==hash||f.discovery.until<=now||f.blocked.includes(uid)||p.blocked.includes(f.id))continue;const until=now+600000;const proof=until+'.'+await contactHash(g,`invite|${uid}|${f.id}|${hash}|${until}`);matches.push({index:i,id:f.id,name:f.name,friend:p.friends.includes(f.id)&&f.friends.includes(uid),pending:p.outgoing.includes(f.id),proof});}
  // Contact names and numbers are not written to storage, logs or responses.
  return reply({matches,remaining:10000-budget.n});
 }
 if(path==='/contacts/invite'&&req.method==='POST'){
  const ownHash=await verified(req,g,p);if(p.discovery?.hash!==ownHash||p.discovery.until<=now||(await g.s.get('phone:'+ownHash))?.uid!==uid)bad('Activează găsirea după număr.',403);
  const v=await body(['id','proof']);if(typeof v.id!=='string'||typeof v.proof!=='string')bad('Contact invalid.');const f=await g.get(v.id);const parts=v.proof.split('.'),until=Number(parts[0]);if(!f?.discovery||f.discovery.until<=now||parts.length!==2||!Number.isSafeInteger(until)||until<=now||until>now+600000||parts[1]!==await contactHash(g,`invite|${uid}|${f.id}|${f.discovery.hash}|${until}`)||f.blocked.includes(uid)||p.blocked.includes(f.id))bad('Reîmprospătează contactele înainte de cerere.',403);
  if(p.friends.includes(f.id)&&f.friends.includes(uid))return reply({ok:true});if(p.outgoing.length>=50||f.incoming.length>=50||p.friends.length>=100||f.friends.length>=100)bad('Lista de prieteni sau cereri este plină.',409);
  if(!f.incoming.includes(uid)){f.incoming.push(uid);p.outgoing.push(f.id);await g.save(f);await g.save(p);}return reply({ok:true});
 }
 bad('Acțiune pentru contacte indisponibilă.',404);
}
