import {bad,keys,idPattern,TTL} from './phone-schema.mjs';
import {selection,pathName,runLimit} from './organizer-selection.mjs';
import {requireCleanupRun} from './cleanup-schedule.mjs';
const reply=(v,s=200)=>Response.json(v,{status:s,headers:{'cache-control':'no-store','x-content-type-options':'nosniff'}});
const text=(s,n)=>typeof s==='string'&&s.length<=n&&!/[\u0000-\u001f\u007f]/.test(s);
export async function sweepOrganizer(storage,now){
 let next=Infinity;
 for(const [key,v]of await storage.list({prefix:'organizer-expiry:'})){
  if(v.expires_at>now){next=Math.min(next,v.expires_at);continue;}
  let page;do{page=await storage.list({prefix:'organizer-item:'+v.run+':',limit:128});if(page.size)await storage.delete([...page.keys()]);}while(page.size);
  await storage.delete([key,'organizer-count:'+v.run]);
 }
 for(const [key,v]of await storage.list({prefix:'organizer-plan:'})){if(v.expires_at<=now)await storage.delete(key);else next=Math.min(next,v.expires_at);}return next;
}
export async function handleOrganizer(request,account,readJSON){
 const url=new URL(request.url);if(!url.pathname.startsWith('/v2/organizer/'))return null;
 const m=/^\/v2\/organizer\/devices\/([0-9a-f-]+)\/(request|plans|runs)(?:\/([0-9a-f-]+))?(?:\/(items|plan))?$/.exec(url.pathname);
 if(!m||!idPattern.test(m[1]))bad('Not found',404);
 const [_,id,action,ref,sub]=m,storage=account.ctx.storage,d=await storage.get('cleanup-device:'+id);
 if(!d||![2,3].includes(d.protocol))bad('Actualizează APK-ul și activează Organizare din laptop pe telefon.',409);
 if(!d.grant.enabled)bad('Organizarea este dezactivată pe telefon.',403);
 if((await storage.get('intake'))?.accepting===false)bad('Primirea datelor este oprită din site.',423);
 if(action==='request'&&!ref&&!sub&&request.method==='POST'){
  const {value:v}=await readJSON(request,4096);keys(v,['id','revision','selection']);if(!idPattern.test(v.id))bad('Cerere invalidă.');
  const selected=selection(v.selection,d.grant),key='cleanup-run:'+v.id,old=await storage.get(key);
  if(selected.mode==='manual'&&d.protocol<3)bad('Actualizează FORJA la v21 și reactivează Organizare din laptop pentru modul Manual.',409);
  if(old){if(old.device!==id||old.grant_id!==d.grant.id||JSON.stringify(old.selection)!==JSON.stringify(selected))bad('Identificator reutilizat.',409);return reply(old);}
  if(v.revision!==d.revision)bad('Configurarea s-a schimbat. Reîncarcă pagina.',409);
  const rows=[...(await storage.list({prefix:'cleanup-run:'})).values()];
  if(rows.filter(r=>r.device===id&&r.grant_id===d.grant.id&&r.expires_at>Date.now()&&!['complete','error'].includes(r.phase)).length>=3)bad('Există deja trei analize în așteptare.',429);
  const now=Date.now(),run={id:v.id,device:id,grant_id:d.grant.id,revision:d.revision,on_demand:true,selection:selected,at:now,slot:'request:'+v.id,schedule:{...d.schedule,enabled:true,photos:selected.photos,files:selected.files,wifi_only:selected.wifi_only,count:Math.max(selected.photo_count||15000,selected.file_count||15000)},phase:'queued',analyzed:0,proposals:[],duplicates:0,uploaded:0,total:0,message:'Cererea așteaptă telefonul. Android poate întârzia pornirea în fundal.',expires_at:now+TTL};
  await storage.put(key,run);await account.sweep();return reply(run,201);
 }
 if(action==='plans'&&!sub){
  const plans=[...(await storage.list({prefix:'organizer-plan:'})).values()].filter(p=>p.device===id&&p.grant_id===d.grant.id&&p.expires_at>Date.now());
  if(!ref&&request.method==='GET')return reply({plans:plans.sort((a,b)=>b.created_at-a.created_at)});
  const plan=plans.find(p=>p.id===ref);if(!plan)bad('Plan indisponibil.',404);
  if(request.method==='DELETE'){if(plan.state==='applying')bad('Mutarea a început; așteaptă rezultatul telefonului.',409);const cancelled={...plan,state:'error',message:'Plan anulat de tine din site.'};await storage.put('organizer-plan:'+ref,cancelled);return reply(cancelled);}
  await requireCleanupRun(storage,id,plan.run,d.grant.id);
  if(!d.grant.organize)bad('Mutările din web nu sunt autorizate.',403);
  if(request.method==='GET')return reply(plan);
  if(request.method==='POST'){
   const {value:v}=await readJSON(request,4096);keys(v,['grant_id','state','moved','copied','skipped','message']);
   if(v.grant_id!==d.grant.id)bad('Activare schimbată.',409);
   if(!['awaiting_phone','applying','complete','error'].includes(v.state)||['moved','copied','skipped'].some(k=>!Number.isInteger(v[k])||v[k]<0||v[k]>plan.items.length)||v.moved+v.copied+v.skipped>plan.items.length||!text(v.message,800))bad('Rezultat invalid.');
   if(['complete','error'].includes(plan.state))return reply(plan);
   if(v.state==='complete'&&plan.state!=='applying'||v.state==='awaiting_phone'&&plan.state==='applying')bad('Tranziție de plan invalidă.',409);
   const next={...plan,...v};await storage.put('organizer-plan:'+ref,next);return reply(next);
  }
 }
 if(action==='runs'&&idPattern.test(ref||'')){
  const {run}=await requireCleanupRun(storage,id,ref);
  const prefix='organizer-item:'+ref+':';
  if(sub==='items'){
   if(request.method==='GET'){
    const after=url.searchParams.get('after'),requested=url.searchParams.get('ids')?.split(',');
    if(requested&&(requested.length>25||requested.some(id=>!idPattern.test(id))))bad('Selecție invalidă.');
    if(after&&!idPattern.test(after))bad('Cursor invalid.');
    const rows=requested?(await Promise.all(requested.map(id=>storage.get(prefix+id)))).filter(Boolean):[...(await storage.list({prefix,...(after?{startAfter:prefix+after}:{}),limit:26})).values()];
    const items=rows.slice(0,25),total=await storage.get('organizer-count:'+ref)||0;
    const files=new Map([...(await storage.list({prefix:'cloud-file:'})).values()].map(f=>[f.id,f]));
    return reply({items:items.map(i=>({...i,received:files.has(i.id),preview:files.get(i.id)?.preview,thumbnail:files.get(i.id)?.thumbnail,media_type:files.get(i.id)?.media_type,expires_at:Math.min(i.expires_at,files.get(i.id)?.expires_at||i.expires_at)})),next_cursor:!requested&&rows.length>25?items.at(-1).id:null,total});
   }
   if(request.method==='POST'){
    const {value:v}=await readJSON(request,220000);keys(v,['grant_id','items']);if(v.grant_id!==run.grant_id)bad('Activare schimbată.',409);
    if(!Array.isArray(v.items)||v.items.length>50||new Set(v.items.map(i=>i?.id)).size!==v.items.length)bad('Maximum 50 de rezultate per cerere.');
    const validated=v.items.map(i=>{keys(i,['id','name','kind','source','folder','destination','reason','excerpt','partial']);if(!idPattern.test(i.id)||!['photo','file'].includes(i.kind)||!['photos','files'].includes(i.source)||!run.schedule[i.source]||!text(i.name,200)||!text(i.folder,240)||!text(i.reason,300)||typeof i.excerpt!=='string'||i.excerpt.length>2000||typeof i.partial!=='boolean')bad('Rezultat invalid.');return {...i,destination:pathName(i.destination,200),expires_at:run.expires_at};});
    await storage.transaction(async tx=>{
     const known=await Promise.all(validated.map(i=>tx.get(prefix+i.id))),count=await tx.get('organizer-count:'+ref)||0,added=known.filter(i=>!i).length;
     if(count+added>runLimit(run))bad('Rezultatele depășesc selecția.',409);
     for(const i of validated)await tx.put(prefix+i.id,i);
     await tx.put('organizer-count:'+ref,count+added);await tx.put('organizer-expiry:'+ref,{run:ref,expires_at:run.expires_at});
    });return reply({accepted:validated.length});
   }
  }
  if(sub==='plan'&&request.method==='POST'){
   const {value:v}=await readJSON(request,64000);keys(v,['id','grant_id','items','confirm']);
   if(!d.grant.organize||v.confirm!==true||v.grant_id!==d.grant.id)bad('Confirmă mutarea și autorizează organizarea din web pe telefon.',403);
   if(!idPattern.test(v.id)||!Array.isArray(v.items)||!v.items.length||v.items.length>200||new Set(v.items.map(i=>i.id)).size!==v.items.length)bad('Alege 1–200 de fișiere distincte.');
   const approved=[];
   for(const i of v.items){keys(i,['id','destination']);const metadata=await storage.get(prefix+i.id),file=await storage.get('cloud-file:'+i.id);
    if(!metadata||!file||file.cleanup_run!==ref||file.device_id!==id||file.kind!==metadata.kind||file.expires_at<=Date.now())bad('Așteaptă primirea tuturor copiilor selectate înainte de mutare.',409);
    const destination=pathName(i.destination);if(!destination)bad('Alege un dosar destinație.');
    approved.push({id:i.id,destination,sha256:file.sha256,source:metadata.source});
   }
   if(new Set(approved.map(i=>i.source)).size!==1)bad('Aplică separat fotografiile și documentele.');
   const old=await storage.get('organizer-plan:'+v.id);
   if(old){if(old.run!==ref||JSON.stringify(old.items)!==JSON.stringify(approved))bad('Plan diferit cu același identificator.',409);return reply(old);}
   const plans=[...(await storage.list({prefix:'organizer-plan:'})).values()];
   if(plans.some(p=>p.device===id&&p.expires_at>Date.now()&&['approved','awaiting_phone','applying'].includes(p.state)))bad('Există un plan în lucru. Finalizează-l înaintea următorului.',409);
   const plan={id:v.id,run:ref,device:id,grant_id:run.grant_id,items:approved,state:'approved',created_at:Date.now(),expires_at:run.expires_at,moved:0,copied:0,skipped:0,message:'Plan aprobat din laptop; așteaptă telefonul.'};
   await storage.put('organizer-plan:'+v.id,plan);await account.sweep();return reply(plan,201);
  }
 }
 bad('Method not allowed',405);
}
