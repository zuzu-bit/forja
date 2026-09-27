import {bad,keys,idPattern,TTL,n} from './phone-schema.mjs';
import {pathName} from './organizer-selection.mjs';

// Progress is account-owned and durable. Only extracted content / cloud copies expire.
export const JOB_ITEM_LIMIT=100000;
export const ITEM_STATES=['pending','analyzed','upload_pending','uploaded','ready','applying','moved','copied_pending_removal','needs_review','failed_retryable','skipped'];
const terminal=new Set(['moved','skipped']), reconcile=new Set(['moved','copied_pending_removal','needs_review','failed_retryable']);
const shaPattern=/^[0-9a-f]{64}$/;
const json=(v,status=200)=>Response.json(v,{status,headers:{'cache-control':'no-store','x-content-type-options':'nosniff'}});
const text=(v,max)=>typeof v==='string'&&v.length<=max&&!/[\u0000-\u001f\u007f]/.test(v);
const jobKey=id=>'org4-job:'+id, itemKey=(j,id)=>`org4-item:${j}:${id}`, queueKey=(j,id)=>`org4-queue:${j}:${id}`;
const originalKey=(d,o,v)=>`org4-original:${d}:${o}:${v}`;
const lockKey=(d,o)=>`org4-lock:${d}:${o}`;
const equal=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
const counters=()=>Object.fromEntries(['total',...ITEM_STATES].map(k=>[k,0]));
function under(path,root,recursive=true){return !root||path===root||recursive&&path.startsWith(root+'/');}
function destination(job,value){const p=pathName(value,200);if(!p||!under(p,job.destination))bad('Dosarul nu este în destinația autorizată.',403);return p;}
function active(job,d,intake){return d?.protocol===4&&d.grant.enabled&&d.grant.id===job.grant_id&&d.grant[job.source]&&intake?.accepting!==false&&!['cancelled','paused'].includes(job.state);}
export async function requireOrganizerJob(storage,device,id,{mutate=true}={}){
 if(!idPattern.test(device)||!idPattern.test(id))bad('Organizare invalidă.');
 const d=await storage.get('cleanup-device:'+device),job=await storage.get(jobKey(id));
 if(await storage.get('org4-gone:'+id))bad('Organizarea a fost ștearsă.',410);
 if(!d||!job||job.device!==device)bad('Organizare indisponibilă.',404);
 if(mutate&&!active(job,d,await storage.get('intake')))bad('Organizarea este oprită sau accesul s-a schimbat.',423);
 return {job,device:d};
}
function publicJob(job,d,intake){return {...job,state:!['cancelled','paused'].includes(job.state)&&!active(job,d,intake)?'access_needed':job.state,retention:{progress:'until_deleted',content_ms:TTL}};}
async function publicItem(storage,item,peers=[]){
 const f=item.file_id?await storage.get('cloud-file:'+item.file_id):null;
 const received=!!(f&&f.expires_at>Date.now()&&f.organizer_job===item.job_id&&f.organizer_item===item.id&&f.sha256===item.sha256);
 const content=await storage.get(`org4-content:${item.job_id}:${item.id}`);
 const analysis=content?.expires_at>Date.now()?content.analysis??null:null;
 let duplicate=null;
 if(received){
  const originals=new Map();for(const p of peers.filter(p=>p.organizer_job===item.job_id&&p.sha256===item.sha256&&p.bytes===f.bytes&&p.expires_at>Date.now()).sort((a,b)=>a.received_at-b.received_at||a.original_id.localeCompare(b.original_id))){if(!originals.has(p.original_id))originals.set(p.original_id,p);}
  if(originals.size>1){const keeper=[...originals.values()][0];duplicate={kind:'exact_bytes',sha256:item.sha256,original_count:originals.size,keeper_original_id:keeper.original_id,keeper_item_id:keeper.organizer_item,keeper_file_id:keeper.id,is_keeper:keeper.original_id===item.original_id,verified_received_bytes:true,requires_confirmation:true,review_only:true};}
 }
 const {lease,receipts,...out}=item;return {...out,analysis,duplicate,coverage:analysis?.coverage??null,partial:analysis?.status==='partial',received,file:received?Object.fromEntries(['id','name','media_type','preview','expires_at','thumbnail','sha256','bytes','folder'].map(k=>[k,f[k]])):null};
}
async function putItem(storage,job,item,previous){
 if(!previous){job.counters.total++;job.counters[item.state]++;}
 else if(previous.state!==item.state){job.counters[previous.state]--;job.counters[item.state]++;}
 await storage.put(itemKey(job.id,item.id),item);
 if(['pending','failed_retryable'].includes(item.state)&&!item.batch_id)await storage.put(queueKey(job.id,item.id),true);else await storage.delete(queueKey(job.id,item.id));
 job.updated_at=Date.now();
 job.sync_revision=(job.sync_revision||0)+1;
}
function progressState(job){
 if(['cancelled','paused'].includes(job.state))return;
 const c=job.counters,finished=c.moved+c.skipped;
 job.state=job.inventory_complete&&finished===c.total?'complete':c.needs_review||c.failed_retryable||c.copied_pending_removal?'partial':c.total?'running':'awaiting_phone';
 if(job.command?.action==='continue'&&job.command.status!=='complete'){
  const selected=job.command.selected||0,settled=job.command.finished||0;
  if(selected===settled&&(job.command.count>0&&selected>=job.command.count||job.inventory_complete&&['pending','analyzed','upload_pending','uploaded','ready','applying','copied_pending_removal','failed_retryable'].every(k=>c[k]===0)))job.command.status='complete';
  else job.command.status=selected?'running':'pending';
 }
}
export async function validateOrganizerUpload(storage,device,jobId,itemId,original,version,sha,kind){
 const {job}=await requireOrganizerJob(storage,device,jobId),item=await storage.get(itemKey(jobId,itemId));
 if(!item||item.original_id!==original||item.version!==version||item.sha256!==sha||job.source!==(kind==='photo'?'photos':'files'))bad('Copia nu corespunde originalului selectat.',409);
 if(!item.batch_id||terminal.has(item.state))bad('Elementul nu mai așteaptă un transfer.',409);
 return {job,item};
}
export async function bindOrganizerFile(storage,file){
 if(!file.organizer_job)return;
 const {job}=await requireOrganizerJob(storage,file.device_id,file.organizer_job),old=await storage.get(itemKey(job.id,file.organizer_item));
 if(!old||old.sha256!==file.sha256)bad('Original modificat.',409);
 const item={...old,file_id:file.id,updated_at:Date.now()};
 if(['pending','analyzed','upload_pending'].includes(item.state))item.state='uploaded';
 await storage.transaction(async tx=>{await putItem(tx,job,item,old);progressState(job);await tx.put(jobKey(job.id),job);});
}
export async function queueOrganizerFolder(storage,file,value){
 keys(value,['folder','request_id','revision']);if(!idPattern.test(value.request_id))bad('Identificator de mutare invalid.');
 const {job,device}=await requireOrganizerJob(storage,file.device_id,file.organizer_job);
 const path=destination(job,value.folder),key=`org4-file-move:${job.id}:${value.request_id}`,old=await storage.get(key),spec={file_id:file.id,...value};
 if(old){if(!equal(old,spec))bad('Comandă de mutare modificată.',409);return file;}
 if(value.revision!==job.revision||!device.grant.organize)bad('Actualizează organizarea și permite mutarea pe telefon.',409);
 const item=await storage.get(itemKey(job.id,file.organizer_item));
 if(!item||['applying','moved','copied_pending_removal'].includes(item.state))bad('Acest original nu mai poate fi mutat prin planul curent.',409);
 await copyFor(storage,job,item);
 const pending={...file,pending_folder:path,sync_state:'awaiting_phone'};
 await storage.transaction(async tx=>{await putItem(tx,job,{...item,destination:path,state:'ready',approval:{grant_id:job.grant_id,revision:job.revision},updated_at:Date.now()},item);await tx.put('cloud-file:'+file.id,pending);await tx.put(key,spec);await tx.put(jobKey(job.id),job);});
 return pending;
}
async function copyFor(storage,job,item){
 const file=item.file_id?await storage.get('cloud-file:'+item.file_id):null;
 if(!file||file.expires_at<=Date.now()||file.organizer_job!==job.id||file.organizer_item!==item.id||file.sha256!==item.sha256)bad('Încarcă din nou copia verificată înainte de mutare.',409);
 return file;
}
async function moveEvidence(storage,job,item){
 if(job.mode==='local'){
  const proof=item.receipts?.analyzed;
  if(!proof||proof.source_sha256!==item.sha256||!text(proof.reason,300)||!proof.reason.trim())bad('Analizează originalul pe telefon înainte de mutare.',409);
  return null;
 }
 return copyFor(storage,job,item);
}
function extraction(value,sha){
 if(value===undefined)return null;
 keys(value,['source_sha256','method','text','partial','pages_total','pages_processed','page_spans','limitations'],['source_sha256','method','text','partial']);
 if(value.source_sha256!==sha||!text(value.method,80)||typeof value.text!=='string'||value.text.length>32000||typeof value.partial!=='boolean')bad('Conținut extras invalid.');
 if(value.pages_total!==undefined)n(value.pages_total,1,100000);if(value.pages_processed!==undefined)n(value.pages_processed,0,value.pages_total??100000);
 if(value.page_spans!==undefined){if(!Array.isArray(value.page_spans)||value.page_spans.length>40)bad('Pagini invalide.');for(const p of value.page_spans){keys(p,['page','start','end','method']);n(p.page,1,value.pages_total??100000);n(p.start,0,value.text.length);n(p.end,p.start,value.text.length);if(!['pdf_text','ocr'].includes(p.method))bad('Extragere invalidă.');}}
 if(value.limitations!==undefined&&(!Array.isArray(value.limitations)||value.limitations.length>10||value.limitations.some(s=>!text(s,200))))bad('Acoperire invalidă.');
 return {...value,expires_at:Date.now()+TTL};
}
export async function sweepOrganizerJobs(storage,now=Date.now()){
 let next=Infinity;
 for(const [k,e] of await storage.list({prefix:'org4-content:'})){if(e.expires_at<=now)await storage.delete(k);else next=Math.min(next,e.expires_at);}
 // Deleting a large manifest is chunked. A tombstone blocks all delayed writes immediately.
 for(const [marker,removed] of await storage.list({prefix:'org4-delete:',limit:1})){
  const rows=await storage.list({prefix:`org4-item:${removed.id}:`,limit:100});
  for(const [key,item] of rows){
   const original=originalKey(removed.device,item.original_id,item.version),value=await storage.get(original);
   if(value?.job===removed.id)await storage.delete(original);
   const lock=lockKey(removed.device,item.original_id),lease=await storage.get(lock);if(lease?.job===removed.id)await storage.delete(lock);
   await storage.delete([key,queueKey(removed.id,item.id),`org4-content:${removed.id}:${item.id}`]);
  }
  if(rows.size){next=Math.min(next,now+1000);continue;}
  let remaining=false;
  for(const name of ['queue','batch','approval','file-move','command','content']){const keys=[...(await storage.list({prefix:`org4-${name}:${removed.id}:`,limit:128})).keys()];if(keys.length){await storage.delete(keys);remaining=true;}}
  if(remaining)next=Math.min(next,now+1000);else await storage.delete([marker,jobKey(removed.id)]);
 }
 if((await storage.list({prefix:'org4-delete:',limit:1})).size)next=Math.min(next,now+1000);
 return next;
}
/** All mutations run in the verified owner's serialized account Durable Object. */
export async function handleOrganizerJobs(request,account,readJSON){
 const url=new URL(request.url),m=/^\/v2\/organizer\/devices\/([0-9a-f-]+)\/jobs(?:\/([0-9a-f-]+)(?:\/(command|items|batch|receipts|approve|analysis))?)?$/.exec(url.pathname);
 if(!m)return null;
 const {storage}=account.ctx,[,device,id,action]=m;
 if(!idPattern.test(device)||id&&!idPattern.test(id))bad('Identificator invalid.');
 const d=await storage.get('cleanup-device:'+device),intake=await storage.get('intake');
 if(!d||d.protocol!==4)bad('Actualizează aplicația pentru organizarea continuă.',409);
 if(!id){
  if(request.method==='GET')return json({jobs:[...(await storage.list({prefix:'org4-job:'})).values()].filter(j=>j.device===device&&!j.deleting).sort((a,b)=>b.updated_at-a.updated_at).map(j=>publicJob(j,d,intake))});
  if(request.method!=='POST')bad('Method not allowed',405);
  const {value:v}=await readJSON(request,8192);keys(v,['id','grant_id','revision','source','source_id','scope','destination','mode','auto_apply','ai_consent','preferences'],['id','grant_id','revision','source','scope','destination','mode','auto_apply','ai_consent']);
  keys(v.scope,['folder','recursive']);
  if(!idPattern.test(v.id)||!['photos','files'].includes(v.source)||!['manual','local','online'].includes(v.mode)||typeof v.scope.recursive!=='boolean'||typeof v.auto_apply!=='boolean'||typeof v.ai_consent!=='boolean'||v.mode==='online'&&!v.ai_consent)bad('Alege sursa și modul de organizare.');
  const scope={folder:pathName(v.scope.folder,200),recursive:v.scope.recursive},dest=pathName(v.destination,200);
  if(!dest)bad('Alege dosarul de destinație.');
  const sources=d.grant.sources||[],source=v.source_id?sources.find(s=>s.id===v.source_id&&s.source===v.source):null;
  if(v.source_id&&!source)bad('Dosarul nu este autorizat pe telefon.',403);
  if(v.source==='files'&&sources.filter(s=>s.source==='files').length&& !source)bad('Alege dosarul autorizat pe telefon.',403);
  const preferences=v.preferences??{protected_folders:[]};keys(preferences,['protected_folders']);
  if(!Array.isArray(preferences.protected_folders)||preferences.protected_folders.length>30)bad('Preferințe invalide.');
  preferences.protected_folders=preferences.protected_folders.map(p=>pathName(p,200));
  const spec={device,grant_id:v.grant_id,source:v.source,source_id:v.source_id??null,scope,destination:dest,mode:v.mode,auto_apply:v.auto_apply,ai_consent:v.ai_consent,preferences};
  if(await storage.get('org4-gone:'+v.id))bad('Organizarea a fost ștearsă.',410);
  const old=await storage.get(jobKey(v.id));if(old){if(!equal(old.spec,spec))bad('Identificator folosit pentru altă organizare.',409);return json(publicJob(old,d,intake));}
  if(v.grant_id!==d.grant.id||!d.grant.enabled||!d.grant[v.source]||v.auto_apply&&!d.grant.organize||intake?.accepting===false)bad('Activează accesul necesar pe telefon.',403);
  if(v.revision!==d.revision)bad('Accesul s-a schimbat. Actualizează.',409);
  if([...(await storage.list({prefix:'org4-job:'})).values()].filter(j=>j.device===device&&!['cancelled','complete'].includes(j.state)).length>=10)bad('Maximum zece organizări active.',429);
  if((await storage.list({prefix:'org4-job:',limit:100})).size>=100)bad('Șterge un proiect vechi înainte de a începe altul (maximum 100).',429);
  const now=Date.now(),job={id:v.id,...spec,spec,revision:1,state:'awaiting_phone',command:null,inventory_complete:false,inventory_total:null,counters:counters(),created_at:now,updated_at:now};
  await storage.put(jobKey(job.id),job);return json(publicJob(job,d,intake),201);
 }
 const {job}=await requireOrganizerJob(storage,device,id,{mutate:false});
 if(!action&&request.method==='GET')return json(publicJob(job,d,intake));
 if(!action&&request.method==='DELETE'){
  if(job.counters.applying||job.counters.copied_pending_removal||job.unresolved)bad('Reconciliază mutările începute înainte de a șterge progresul.',409);
  await storage.transaction(async tx=>{await tx.put('org4-gone:'+id,Date.now());await tx.put('org4-delete:'+id,{id,device});await tx.put(jobKey(id),{...job,state:'cancelled',deleting:true});});
  await account.sweep();return json({deleted:true,phone_originals_unchanged:true,cloud_copies_expire_separately:true},202);
 }
 if(action==='items'&&request.method==='GET'){
  const after=url.searchParams.get('after');if(after&&!idPattern.test(after))bad('Cursor invalid.');
  const ids=url.searchParams.get('ids')?.split(',');if(ids&&(ids.length>5||ids.some(i=>!idPattern.test(i))))bad('Selecție invalidă.');
  const prefix=`org4-item:${id}:`,rows=ids?(await Promise.all(ids.map(i=>storage.get(prefix+i)))).filter(Boolean):[...(await storage.list({prefix,startAfter:after?prefix+after:'',limit:51})).values()];
  const peers=[...(await storage.list({prefix:'cloud-file:'})).values()].filter(f=>f.organizer_job===id);
  return json({items:await Promise.all(rows.slice(0,50).map(i=>publicItem(storage,i,peers))),next_cursor:!ids&&rows.length>50?rows[49].id:null,total:job.counters.total});
 }
 if(action==='command'&&request.method==='POST'){
  const {value:v}=await readJSON(request,2048);keys(v,['request_id','revision','action','count']);
  if(!idPattern.test(v.request_id)||!['continue','pause','cancel'].includes(v.action))bad('Comandă invalidă.');n(v.count,0,1000);
  const key=`org4-command:${id}:${v.request_id}`,old=await storage.get(key);if(old){if(!equal(old,v))bad('Comandă modificată.',409);return json(publicJob(job,d,intake));}
  if(v.revision!==job.revision)bad('Organizarea s-a schimbat. Actualizează.',409);
  if(job.state==='cancelled')bad('Organizarea este anulată.',409);
  if(v.action==='continue'){
   if(!d.grant.enabled||d.grant.id!==job.grant_id||!d.grant[job.source]||intake?.accepting===false)bad('Accesul trebuie reactivat pe telefon.',403);
   // Repeating Continue while a command is unfinished does not allocate the same originals twice.
   if(job.command?.action==='continue'&&job.command.status!=='complete'&&['awaiting_phone','running','partial'].includes(job.state))bad('Continuă mai întâi lotul aflat în lucru.',409);
   job.state='awaiting_phone';job.inventory_complete=false;
  }else job.state=v.action==='pause'?'paused':'cancelled';
  job.revision++;job.sync_revision=(job.sync_revision||0)+1;job.command={...v,revision:job.revision,selected:0,finished:0,status:v.action==='continue'?'pending':v.action==='pause'?'paused':'cancelled',at:Date.now()};job.updated_at=Date.now();
  await storage.transaction(async tx=>{await tx.put(key,v);await tx.put(jobKey(id),job);});return json(publicJob(job,d,intake));
 }
 // Final facts from an already authorized move remain recordable after pause/revoke.
 if(action!=='receipts')await requireOrganizerJob(storage,device,id);
 if(action==='items'&&request.method==='POST'){
  const {value:v}=await readJSON(request,512*1024);keys(v,['grant_id','items','inventory_complete','inventory_total'],['grant_id','items']);
  if(v.grant_id!==job.grant_id||!Array.isArray(v.items)||v.items.length>100||new Set(v.items.map(i=>i.id)).size!==v.items.length)bad('Inventar invalid.');
  if(!job.command||job.command.action!=='continue')bad('Pornește sau continuă organizarea.',409);
  if(v.inventory_complete!==undefined&&typeof v.inventory_complete!=='boolean')bad('Inventar invalid.');if(v.inventory_total!==undefined&&v.inventory_total!==null)n(v.inventory_total,0,10000000);
  const clean=v.items.map(i=>{
   keys(i,['id','original_id','version','sha256','name','folder','media_type','bytes','modified_at','extraction'],['id','original_id','version','sha256','name','folder','media_type','bytes','modified_at']);
   if(!idPattern.test(i.id)||!idPattern.test(i.original_id)||!shaPattern.test(i.sha256)||i.version!==i.sha256||!text(i.name,200)||!i.name.trim()||!text(i.media_type,120))bad('Identitate de fișier invalidă.');n(i.bytes,1,2**40);n(i.modified_at);
   const folder=pathName(i.folder,240);if(!under(folder,job.scope.folder,job.scope.recursive)||under(folder,job.destination))bad('Fișierul este în afara selecției sau deja în destinație.',403);
   if(job.preferences.protected_folders.some(p=>under(folder,p)))bad('Dosar protejat.',403);
   return {...i,folder,extraction:extraction(i.extraction,i.sha256)};
  });
  await storage.transaction(async tx=>{
   for(const i of clean){
    const old=await tx.get(itemKey(id,i.id));
    if(old){if(old.original_id!==i.original_id||old.version!==i.version||old.sha256!==i.sha256)bad('Identificator folosit pentru alt original.',409);if(i.extraction)await tx.put(`org4-content:${id}:${i.id}`,{extraction:i.extraction,expires_at:i.extraction.expires_at});continue;}
    if(job.counters.total>=JOB_ITEM_LIMIT)bad('Inventarul a atins limita de 100.000 de versiuni.',429);
    const index=await tx.get(originalKey(device,i.original_id,i.version));
    if(index?.state==='moved'||index?.job===id)continue;
    const {extraction:content,...metadata}=i,item={...metadata,job_id:id,state:'pending',file_id:null,batch_id:null,created_at:Date.now(),updated_at:Date.now()};
    await putItem(tx,job,item,null);await tx.put(originalKey(device,i.original_id,i.version),{job:id,item:i.id,state:'pending'});
    if(content)await tx.put(`org4-content:${id}:${i.id}`,{extraction:content,expires_at:content.expires_at});
   }
   if(v.inventory_complete!==undefined)job.inventory_complete=v.inventory_complete;
   if(v.inventory_total!==undefined)job.inventory_total=v.inventory_total;
   progressState(job);await tx.put(jobKey(id),job);
  });
  await account.sweep();return json(publicJob(job,d,intake));
 }
 if(action==='batch'&&request.method==='POST'){
  const {value:v}=await readJSON(request,8192);keys(v,['request_id','grant_id','limit','ids'],['request_id','grant_id','limit']);if(!idPattern.test(v.request_id)||v.grant_id!==job.grant_id)bad('Lot invalid.');n(v.limit,1,100);
  if(v.ids!==undefined&&(!Array.isArray(v.ids)||!v.ids.length||v.ids.length>v.limit||v.ids.some(i=>!idPattern.test(i))||new Set(v.ids).size!==v.ids.length))bad('Selecție de lot invalidă.');
  if(job.command?.action!=='continue')bad('Organizarea nu a fost pornită.',409);
  const key=`org4-batch:${id}:${v.request_id}`,old=await storage.get(key);
  if(old){if(old.limit!==v.limit||!equal(old.requested_ids??null,v.ids??null))bad('Lot modificat.',409);return json({...old,items:await Promise.all(old.ids.map(async i=>publicItem(storage,await storage.get(itemKey(id,i))))) });}
  const remaining=job.command.count===0?100:Math.max(0,job.command.count-job.command.selected),ids=[];let batch;
  await storage.transaction(async tx=>{
  const candidates=v.ids?v.ids.map(i=>[queueKey(id,i),true]):await tx.list({prefix:`org4-queue:${id}:`,limit:1000});
  for(const [key] of candidates){
   if(ids.length>=Math.min(v.limit,remaining))break;
   const item=await tx.get(itemKey(id,key.split(':').at(-1)));if(!item||item.batch_id||!['pending','failed_retryable'].includes(item.state))continue;
   const lock=await tx.get(lockKey(device,item.original_id));if(lock&&(lock.job!==id||lock.item!==item.id)){const other=await tx.get(jobKey(lock.job)),locked=await tx.get(itemKey(lock.job,lock.item));if(other?.state!=='cancelled'||locked?.reconciliation_required||['applying','copied_pending_removal'].includes(locked?.state))continue;}
   const prior=await tx.get(originalKey(device,item.original_id,item.version));if(prior?.state==='moved'){await tx.delete(key);continue;}
   item.batch_id=v.request_id;item.command_revision=job.revision;item.command_finished=false;
   await tx.put(lockKey(device,item.original_id),{job:id,item:item.id});await tx.put(itemKey(id,item.id),item);await tx.delete(key);ids.push(item.id);
  }
  job.command.selected+=ids.length;job.updated_at=Date.now();progressState(job);await tx.put(jobKey(id),job);
  batch={id:v.request_id,job_id:id,limit:v.limit,ids,requested_ids:v.ids??null,has_more:(await tx.list({prefix:`org4-queue:${id}:`,limit:1})).size>0,command_revision:job.revision};await tx.put(key,batch);
  });
  return json({...batch,items:await Promise.all(ids.map(async i=>publicItem(storage,await storage.get(itemKey(id,i)))))},201);
 }
 if(action==='approve'&&request.method==='POST'){
  const {value:v}=await readJSON(request,40000);keys(v,['request_id','revision','items','confirm']);
  if(!idPattern.test(v.request_id)||v.confirm!==true||!Array.isArray(v.items)||!v.items.length||v.items.length>100||new Set(v.items.map(i=>i.id)).size!==v.items.length)bad('Aprobare invalidă.');
  const key=`org4-approval:${id}:${v.request_id}`,old=await storage.get(key);if(old){if(!equal(old,v))bad('Aprobare modificată.',409);return json(publicJob(job,d,intake));}
  if(v.revision!==job.revision||!d.grant.organize)bad('Activează mutarea pe telefon și actualizează.',409);
  const selected=[];for(const i of v.items){keys(i,['id','destination']);const item=await storage.get(itemKey(id,i.id));if(!item||['applying','moved','copied_pending_removal'].includes(item.state))bad('Elementul nu poate fi aprobat.',409);await moveEvidence(storage,job,item);selected.push({old:item,next:{...item,state:'ready',destination:destination(job,i.destination),approval:{grant_id:job.grant_id,revision:job.revision},updated_at:Date.now()}});}
  await storage.transaction(async tx=>{for(const p of selected)await putItem(tx,job,p.next,p.old);await tx.put(key,v);progressState(job);await tx.put(jobKey(id),job);});return json(publicJob(job,d,intake));
 }
 if(action==='receipts'&&request.method==='POST'){
  const {value:v}=await readJSON(request,64000);keys(v,['grant_id','receipts']);if(v.grant_id!==job.grant_id||!Array.isArray(v.receipts)||!v.receipts.length||v.receipts.length>50)bad('Rezultat invalid.');
  const result=[];
  await storage.transaction(async tx=>{
   for(const r of v.receipts){
    keys(r,['id','operation_id','state','source_sha256','target_sha256','destination','file_id','message','reason'],['id','operation_id','state','source_sha256']);
    if(!idPattern.test(r.id)||!idPattern.test(r.operation_id)||!ITEM_STATES.includes(r.state)||r.state==='pending'||!shaPattern.test(r.source_sha256)||r.message!==undefined&&!text(r.message,400)||r.reason!==undefined&&!text(r.reason,300))bad('Confirmare de fișier invalidă.');
    const old=await tx.get(itemKey(id,r.id));if(!old||old.sha256!==r.source_sha256)bad('Originalul nu corespunde lotului.',409);
    if(old.operation_id&&old.operation_id!==r.operation_id&&!['failed_retryable','needs_review'].includes(old.state))bad('Altă operație este în curs.',409);
    if(old.receipts?.[r.state]&&old.operation_id===r.operation_id&&!(r.state==='ready'&&old.state==='ready'&&old.approval?.revision!==job.revision)){if(!equal(old.receipts[r.state],r))bad('Confirmare modificată.',409);result.push(old);continue;}
    if(!old.batch_id)bad('Originalul nu are un lot activ.',409);
    if(terminal.has(old.state))bad('Element deja finalizat.',409);
    const inFlight=['applying','copied_pending_removal'].includes(old.state)||old.reconciliation_required;
    const finishing=inFlight&&reconcile.has(r.state)&&old.operation_id===r.operation_id;
    if(!finishing&&!active(job,d,intake))bad('Accesul a fost oprit; nu pot începe operații noi.',423);
    const item={...old,operation_id:r.operation_id,message:r.message??old.message,reason:r.reason??old.reason,updated_at:Date.now(),receipts:old.operation_id===r.operation_id?{...old.receipts}: {}};
    if(r.destination!==undefined)item.destination=destination(job,r.destination);
    if(r.file_id!==undefined){if(!idPattern.test(r.file_id))bad('Copie invalidă.');item.file_id=r.file_id;}
    if(r.state==='uploaded')await copyFor(tx,job,item);
    if(['ready','applying'].includes(r.state))await moveEvidence(tx,job,item);
    if(r.state==='ready'){
     if(!d.grant.organize||!job.auto_apply&&!item.approval)bad('Aprobă mai întâi mutarea.',403);
     if(!item.destination)bad('Lipsește destinația.');
     if(job.auto_apply)item.approval={grant_id:job.grant_id,revision:job.revision};
    }
    if(r.state==='applying'){
     if(old.state!=='ready'||!d.grant.organize||item.approval?.grant_id!==job.grant_id||item.approval.revision!==job.revision||!item.destination)bad('Mutarea nu mai este aprobată.',409);
     if(r.destination&&r.destination!==old.destination)bad('Destinația aprobată s-a schimbat.',409);
     item.intent={operation_id:r.operation_id,source_sha256:old.sha256,destination:item.destination,grant_id:job.grant_id,revision:job.revision,at:Date.now()};
    }
    if(['moved','copied_pending_removal'].includes(r.state)){
     if(!finishing||r.target_sha256!==old.sha256||item.destination!==old.intent?.destination)bad('Mutarea cere intenție și copie verificată.',409);
     item.target_sha256=r.target_sha256;
    }
    if(inFlight&&(!reconcile.has(r.state)||r.state==='failed_retryable'))bad('Reconciliază operația începută înainte de altă încercare.',409);
    if(['analyzed','upload_pending','uploaded'].includes(r.state)&&['ready','applying','copied_pending_removal'].includes(old.state))bad('Stare veche.',409);
    item.state=r.state;item.receipts[r.state]=r;
    if(['moved','skipped','needs_review','failed_retryable'].includes(item.state)){
     const ambiguous=item.state==='needs_review'&&inFlight;
     const lock=await tx.get(lockKey(device,item.original_id));if(!ambiguous&&lock?.job===id&&lock?.item===item.id)await tx.delete(lockKey(device,item.original_id));
     if(ambiguous){item.reconciliation_required=true;if(!old.reconciliation_required)job.unresolved=(job.unresolved||0)+1;}
     if(item.state==='moved'&&old.reconciliation_required){item.reconciliation_required=false;job.unresolved=Math.max(0,(job.unresolved||0)-1);}
     if(item.state==='failed_retryable'){item.batch_id=null;if(item.command_revision===job.revision)job.command.selected=Math.max(0,job.command.selected-1);}
     else if(!item.command_finished&&item.command_revision===job.revision){job.command.finished=(job.command.finished||0)+1;item.command_finished=true;}
    }
    await tx.put(originalKey(device,item.original_id,item.version),{job:id,item:item.id,state:item.state});
    await putItem(tx,job,item,old);
    if(item.file_id){const file=await tx.get('cloud-file:'+item.file_id);if(file){if(item.state==='moved'){file.folder=item.destination;delete file.pending_folder;file.sync_state='applied';}else if(['needs_review','copied_pending_removal','failed_retryable'].includes(item.state))file.sync_state=item.state;await tx.put('cloud-file:'+file.id,file);}}
    result.push(item);
   }
   progressState(job);await tx.put(jobKey(id),job);
  });return json({job:publicJob(job,d,intake),items:await Promise.all(result.map(i=>publicItem(storage,i)))});
 }
 // Internal AI result endpoint is explicitly excluded from the public Worker allowlist.
 if(action==='analysis'&&request.method==='GET'){
  if(!job.ai_consent||job.mode!=='online')bad('Analiza online nu este activată.',403);
  const ids=url.searchParams.get('ids')?.split(',')||[];if(!ids.length||ids.length>5||ids.some(i=>!idPattern.test(i)))bad('Selecție invalidă.');
  const items=[];for(const i of ids){const item=await storage.get(itemKey(id,i));if(!item)bad('Element indisponibil.',404);const file=await copyFor(storage,job,item),content=await storage.get(`org4-content:${id}:${i}`);items.push({item,file,extraction:content?.expires_at>Date.now()?content.extraction:null,analysis:content?.expires_at>Date.now()?content.analysis:null});}
  return json({job,token:`${job.grant_id}:${job.revision}:${job.mode}:${job.ai_consent}`,items});
 }
 if(action==='analysis'&&request.method==='POST'){
  const {value:v}=await readJSON(request,64000);keys(v,['token','results']);if(v.token!==`${job.grant_id}:${job.revision}:${job.mode}:${job.ai_consent}`||!job.ai_consent||job.mode!=='online')bad('Aprobarea analizei s-a schimbat.',409);
  if(!Array.isArray(v.results)||!v.results.length||v.results.length>5)bad('Rezultate invalide.');
  for(const a of v.results){const old=await storage.get(itemKey(id,a.item_id));if(!old||a.source_sha256!==old.sha256)bad('Original schimbat.',409);await copyFor(storage,job,old);if(['ready','applying','moved','copied_pending_removal'].includes(old.state))continue;
   const dest=pathName(a.destination,200),item={...old,reason:a.reason,destination:under(dest,job.destination)?dest:destination(job,job.destination+'/'+dest),updated_at:Date.now()};
   const key=`org4-content:${id}:${old.id}`,content=await storage.get(key);await storage.put(key,{...content,analysis:a,expires_at:Math.min(content?.expires_at??Infinity,Date.now()+TTL)});
   await putItem(storage,job,item,old);
  }
  await storage.put(jobKey(id),job);return json({saved:true,applied:false});
 }
 bad('Method not allowed',405);
}
