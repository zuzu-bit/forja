import {runLimit} from './organizer-selection.mjs';
import {requireCleanupRun} from './cleanup-schedule.mjs';
import {validateOrganizerUpload,bindOrganizerFile,queueOrganizerFolder,invalidateOrganizerFile} from './organizer-jobs.mjs';
import { bad, keys, idPattern, TTL } from './phone-schema.mjs';

export const FILE_MAX_BYTES = 25 * 1024 * 1024;
export const THUMB_MAX_BYTES = 160 * 1024;
export const FILE_ACCOUNT_BYTES = 512 * 1024 * 1024;
export const FILE_ACCOUNT_ITEMS = 500;
const json = (data,status=200) => Response.json(data,{status,headers:{'cache-control':'no-store','x-content-type-options':'nosniff'}});
const hash = async bytes => [...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(v=>v.toString(16).padStart(2,'0')).join('');
const publicFile = ({key,thumb_self,...item}) => item;
const cleanText = (s,max=200) => typeof s==='string' && s.length<=max && !/[\u0000-\u001f\u007f]/.test(s);
function headerText(request,name,fallback='') {let v;try{v=decodeURIComponent(request.headers.get(name)||fallback);}catch{bad('Metadate invalide.');}if(!cleanText(v))bad('Metadate invalide.');return v;}
export function previewType(mime) {
  if(['image/jpeg','image/png','image/webp','image/gif','image/avif','image/bmp'].includes(mime))return 'image';
  if(mime==='application/pdf')return 'pdf';
  if(['text/plain','text/csv','text/markdown','application/json'].includes(mime))return 'text';
  if(mime==='application/vnd.openxmlformats-officedocument.wordprocessingml.document')return 'docx';
  return 'download';
}
export async function eraseFile(storage,bucket,item) {
  // Retain the ID alone so delayed retries cannot resurrect expired/deleted copies.
  await storage.put('file-gone:'+item.id,Date.now()+7*TTL);
  await bucket.delete([item.key,item.key+'.thumb']);
  await storage.delete('cloud-file:'+item.id);
  await invalidateOrganizerFile(storage,item);
}
export async function sweepFiles(storage,bucket,now=Date.now()) {
  let next=Infinity;
  for(const [key,item] of await storage.list({prefix:'file-staging:'})) {
    if(item.expires_at<=now){await storage.put('file-gone:'+key.slice('file-staging:'.length),now+7*TTL);await bucket.delete([item.key,item.key+'.thumb']);await storage.delete(key);next=Math.min(next,now+7*TTL);}else next=Math.min(next,item.expires_at);
  }
  for(const [key,until] of await storage.list({prefix:'file-gone:'})) {
    if(until<=now)await storage.delete(key);else next=Math.min(next,until);
  }
  for(const item of (await storage.list({prefix:'cloud-file:'})).values()) {
    if(item.expires_at<=now){await eraseFile(storage,bucket,item);next=Math.min(next,now+7*TTL);}
    else next=Math.min(next,item.expires_at);
  }
  return next;
}

/** The caller has already checked and bound the verified Firebase owner in the account DO. */
export async function handleFiles(request,account,uid,bytes,readJSON) {
  const {storage}=account.ctx,bucket=account.env.RECORDS,url=new URL(request.url),path=url.pathname;
  if(!path.startsWith('/v2/files'))return null;
  if(path==='/v2/files/settings') {
    if(request.method!=='GET')bad('Method not allowed',405);
    return json({devices:[...(await storage.list({prefix:'file-device:'})).values()]});
  }
  const settings=/^\/v2\/files\/settings\/([0-9a-f-]+)$/.exec(path);
  if(settings) {
    const id=settings[1];if(!idPattern.test(id))bad('Invalid device');
    const old=await storage.get('file-device:'+id);
    if(request.method==='GET')return json(old||{id,enabled:false,photos:false,files:false});
    if(request.method!=='POST')bad('Method not allowed',405);
    const {value}=await readJSON(request,1024);keys(value,['enabled','photos','files']);
    if(['enabled','photos','files'].some(k=>typeof value[k]!=='boolean')||(value.enabled&&!value.photos&&!value.files))bad('Alege ce sincronizezi.');
    if(!old&&(await storage.list({prefix:'file-device:'})).size>=5)bad('Maximum cinci dispozitive.',429);
    const next={id,...value,updated_at:Date.now()};await storage.put('file-device:'+id,next);return json(next);
  }
  if(path==='/v2/files') {
    if(request.method!=='GET')bad('Method not allowed',405);
    const rows=[...(await storage.list({prefix:'cloud-file:'})).values()].sort((a,b)=>b.received_at-a.received_at||a.id.localeCompare(b.id));
    const filter=url.searchParams.get('kind')||'all',q=(url.searchParams.get('q')||'').toLocaleLowerCase('ro');
    if(!['all','photo','file'].includes(filter)||q.length>200)bad('Filtru invalid.');
    const selected=rows.filter(i=>(filter==='all'||i.kind===filter)&&`${i.name} ${i.folder}`.toLocaleLowerCase('ro').includes(q));
    const cursor=url.searchParams.get('after');const start=cursor?selected.findIndex(i=>i.id===cursor)+1:0;
    const limit=24,items=selected.slice(start,start+limit);
    return json({items:items.map(publicFile),next_cursor:start+limit<selected.length?items.at(-1)?.id:null,total:selected.length,bytes:rows.reduce((n,i)=>n+i.bytes+(i.thumbnail_bytes||0),0),server_at:Date.now(),retention_ms:TTL,limits:{file_bytes:FILE_MAX_BYTES,account_bytes:FILE_ACCOUNT_BYTES,items:FILE_ACCOUNT_ITEMS}});
  }
  const match=/^\/v2\/files\/([0-9a-f-]+)(?:\/(thumbnail))?$/.exec(path);
  if(!match||!idPattern.test(match[1]))bad('Not found',404);
  const id=match[1],thumb=!!match[2],old=await storage.get('cloud-file:'+id);
  if(request.method==='GET') {
    if(!old||old.expires_at<=Date.now()||(thumb&&!old.thumbnail))bad('Fișierul nu mai este disponibil.',404);
    // A small JPEG photo is its own thumbnail (GalleryUploader sends 512 px copies and no separate /thumbnail).
    const object=await bucket.get(old.key+(thumb&&!old.thumb_self?'.thumb':''));if(!object)bad('Fișier indisponibil.',404);
    // Downloads are never served as active HTML/SVG or publicly accessible R2 URLs.
    return new Response(object.body,{headers:{'content-type':thumb?'image/jpeg':'application/octet-stream','content-disposition':thumb?'inline':`attachment; filename="file"; filename*=UTF-8''${encodeURIComponent(old.name)}`,'cache-control':'private, no-store, max-age=0','x-content-type-options':'nosniff','content-security-policy':"default-src 'none'; sandbox",'x-expires-at':String(old.expires_at)}});
  }
  if(request.method==='DELETE'&&!thumb) {
    if(old)await eraseFile(storage,bucket,old);else await storage.put('file-gone:'+id,Date.now()+7*TTL);
    await account.sweep();return json({deleted:true,phone_original_unchanged:true});
  }
  if(request.method==='PATCH'&&!thumb) {
    if(!old)bad('Fișier indisponibil.',404);
    const {value}=await readJSON(request,1024);
    if(old.organizer_job)return json(publicFile(await queueOrganizerFolder(storage,old,value)));
    keys(value,['folder']);
    if(!cleanText(value.folder,120))bad('Nume de dosar invalid.');
    old.folder=value.folder.trim();await storage.put('cloud-file:'+id,old);return json(publicFile(old));
  }
  if(request.method!=='PUT')bad('Method not allowed',405);
  const intake=await storage.get('intake');if(intake?.accepting===false)bad('Primirea datelor este oprită din site.',423);
  const device=request.headers.get('x-device-id');if(!device||!idPattern.test(device))bad('Dispozitiv invalid.');
  const autoRun=request.headers.get('x-cleanup-run');
  const organizerJob=request.headers.get('x-organizer-job'),organizerItem=request.headers.get('x-organizer-item'),original=request.headers.get('x-original-id'),version=request.headers.get('x-original-version');
  if(organizerJob&&autoRun)bad('Alege o singură organizare.',409);
  const automatic=autoRun?(await requireCleanupRun(storage,device,autoRun)).run:null;
  const consent=await storage.get('file-device:'+device);
  if(!consent?.enabled)bad('Sincronizarea nu este activată pentru acest dispozitiv.',403);
  if(await storage.get('file-gone:'+id))bad('Copia a expirat sau a fost ștearsă.',410);
  if(!bytes?.length)bad('Fișier gol.');
  if(thumb) {
    if(!old||old.device_id!==device)bad('Fișier indisponibil.',404);
    if(!consent[old.kind==='photo'?'photos':'files'])bad('Sursa nu mai este autorizată.',403);
    if(!old.media_type.startsWith('image/')||bytes.length>THUMB_MAX_BYTES||bytes[0]!==255||bytes[1]!==216||bytes[2]!==255)bad('Miniatură JPEG invalidă.');
    const rows=[...(await storage.list({prefix:'cloud-file:'})).values()];
    if(rows.reduce((n,i)=>n+i.bytes+(i.thumbnail_bytes||0),0)+bytes.length-(old.thumbnail_bytes||0)>FILE_ACCOUNT_BYTES)bad('Spațiul temporar este plin.',429);
    await bucket.put(old.key+'.thumb',bytes,{httpMetadata:{contentType:'image/jpeg'}});
    old.thumbnail=true;old.thumbnail_bytes=bytes.length;delete old.thumb_self;await storage.put('cloud-file:'+id,old);return json(publicFile(old));
  }
  const kind=request.headers.get('x-file-kind');if(!['photo','file'].includes(kind)||!consent[kind==='photo'?'photos':'files'])bad('Sursă neautorizată.',403);
  const name=headerText(request,'x-file-name','Fișier'),folder=headerText(request,'x-file-folder');
  const mime=(request.headers.get('x-media-type')||'application/octet-stream').toLowerCase();
  if(!name.trim()||!cleanText(folder,120)||mime.length>120||! /^[a-z0-9.+-]+\/[a-z0-9.+-]+$/.test(mime))bad('Metadate invalide.');
  if(kind==='photo'&&!mime.startsWith('image/'))bad('Fotografie invalidă.');
  if(bytes.length>FILE_MAX_BYTES)bad('Fișier mai mare de 25 MB.',413);
  const sha256=await hash(bytes),claimed=request.headers.get('x-file-sha256');
  if(claimed!==sha256)bad('Transfer incomplet: amprenta fișierului nu corespunde.',422);
  if(organizerJob)await validateOrganizerUpload(storage,device,organizerJob,organizerItem,original,version,sha256,kind,id);
  else if(organizerItem||original||version)bad('Lipsește organizarea copiei.');
  if(old) {
    if((old.organizer_job||null)!==(organizerJob||null)||(old.organizer_item||null)!==(organizerItem||null)||(old.original_id||null)!==(original||null)||(old.original_version||null)!==(version||null))bad('Copia aparține altei organizări.',409);
    if((old.cleanup_run||null)!==(autoRun||null))bad('Fișierul aparține altei analize.',409);
    if(old.device_id!==device||old.sha256!==sha256||old.kind!==kind||old.media_type!==mime||old.name!==name)bad('Identificator folosit pentru alt fișier.',409);
    if(organizerJob)await bindOrganizerFile(storage,old);
    return json(publicFile(old)); // Idempotent: preserves the original expiry and folder edits.
  }
  const rows=[...(await storage.list({prefix:'cloud-file:'})).values()];
  if(automatic){
    const max=runLimit(automatic);
    if(rows.filter(i=>i.cleanup_run===autoRun).length>=max)bad('Selecția programată a fost deja încărcată.',409);
    if(!automatic.schedule.files&&kind!=='photo')bad('Programul permite doar fotografii.',403);
  }
  if(rows.length>=FILE_ACCOUNT_ITEMS||rows.reduce((n,i)=>n+i.bytes+(i.thumbnail_bytes||0),0)+bytes.length>FILE_ACCOUNT_BYTES)bad('Spațiul temporar este plin. Transferul va fi reluat după expirarea copiilor vechi.',429);
  const day=Math.floor(Date.now()/TTL),budget=await storage.get('file-budget');
  const used=budget?.day===day?budget.used:0;if(used>=1500)bad('Limita zilnică de încărcări a fost atinsă.',429);
  const key=`_insights/${uid}/files/${id}`;
  // Staging metadata precedes R2 so an interrupted write remains covered by expiry cleanup.
  const staging=await storage.get('file-staging:'+id);
  if(staging&&(staging.sha256!==sha256||staging.device_id!==device))bad('Identificator folosit pentru alt transfer.',409);
  const now=staging?.received_at||Date.now(),item={id,device_id:device,kind,name,folder,media_type:mime,preview:previewType(mime),sha256,bytes:bytes.length,received_at:now,expires_at:now+TTL,thumbnail:false,key};
  if(kind==='photo'&&mime==='image/jpeg'&&bytes.length<=THUMB_MAX_BYTES&&bytes[0]===255&&bytes[1]===216&&bytes[2]===255)Object.assign(item,{thumbnail:true,thumb_self:true});
  if(autoRun)item.cleanup_run=autoRun;
  if(organizerJob)Object.assign(item,{organizer_job:organizerJob,organizer_item:organizerItem,original_id:original,original_version:version});
  await storage.put('file-staging:'+id,{key,sha256,device_id:device,received_at:now,expires_at:now+TTL});
  await account.sweep();
  await bucket.put(key,bytes,{httpMetadata:{contentType:'application/octet-stream'}});
  await storage.put('cloud-file:'+id,item);
  if(organizerJob)await bindOrganizerFile(storage,item);
  await storage.delete(['file-staging:'+id,'file-gone:'+id]);
  await storage.put('file-budget',{day,used:used+1});await account.sweep();
  return json(publicFile(item),201);
}
