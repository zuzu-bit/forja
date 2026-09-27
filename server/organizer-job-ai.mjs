import {bad,keys,idPattern} from './phone-schema.mjs';
import {readJSON,readBytes,reply} from './insights-store.mjs';
import {FILE_MAX_BYTES,THUMB_MAX_BYTES} from './files-vault.mjs';
import {analyzeOrganizerContent} from './organizer-analysis.mjs';

/** Inference runs outside the account DO lock; immutable grant/revision is rechecked. */
export async function organizeJobAI(request,env,uid,stub){
 const {value:v}=await readJSON(request,4096);keys(v,['device','job','ids','consent']);
 if(!idPattern.test(v.device)||!idPattern.test(v.job)||v.consent!==true||!Array.isArray(v.ids)||!v.ids.length||v.ids.length>5||new Set(v.ids).size!==v.ids.length||v.ids.some(i=>!idPattern.test(i)))bad('Alege până la cinci fișiere și activează analiza online.');
 const call=async(path,method='GET',body)=>{const r=await stub.fetch(new Request('https://internal'+path,{method,headers:{'x-forja-owner':uid,'content-type':'application/json'},...(body===undefined?{}:{body:JSON.stringify(body)})}));if(!r.ok){let error;try{error=(await r.json()).error}catch{}bad(error||'Fișier indisponibil.',r.status);}return r;};
 const path=`/v2/organizer/devices/${v.device}/jobs/${v.job}/analysis`,selection=path+'?ids='+v.ids.join(','),initial=await(await call(selection)).json();
 const authorize=async()=>{const current=await(await call(selection)).json();if(current.token!==initial.token)bad('Aprobarea analizei s-a schimbat.',409);return current.token;};
 const pending=initial.items.filter(row=>!row.analysis);
 if(pending.length)await call('/internal/organizer-ai-budget','POST',{units:pending.reduce((n,row)=>n+(row.file.media_type.startsWith('image/')?2:1),0)});
 const results=[];
 for(const row of initial.items){
  if(row.analysis){results.push(row.analysis);continue;}
  await authorize();const bytes=await readBytes(await call('/v2/files/'+row.file.id),FILE_MAX_BYTES);
  const extraction={...(row.extraction||{})};
  if(row.file.media_type.startsWith('image/')&&row.file.thumbnail){const thumb=await readBytes(await call('/v2/files/'+row.file.id+'/thumbnail'),THUMB_MAX_BYTES);extraction.visual={bytes:thumb,media_type:'image/jpeg',source_sha256:row.file.sha256,representation:'thumbnail'};}
  const file={...row.file,job_id:v.job,item_id:row.item.id};
  const result=await analyzeOrganizerContent({env,file,bytes,extraction,preferences:initial.job.preferences,authorize});
  if(result.item_id!==row.item.id||result.source_sha256!==row.item.sha256)bad('Analiza nu corespunde originalului.',502);
  results.push(result);
 }
 await authorize();await call(path,'POST',{token:initial.token,results});
 return reply({items:results,applied:false});
}
