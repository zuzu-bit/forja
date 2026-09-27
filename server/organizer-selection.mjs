import {bad,keys} from './phone-schema.mjs';
export const INVENTORY_LIMIT=15000;
export function pathName(value,max=200){
 if(typeof value!=='string'||value.length>max||/[\u0000-\u001f\u007f\\:*?"<>|]/.test(value)||value.startsWith('/')||value.split('/').some(p=>p==='.'||p==='..'))bad('Dosar invalid. Folosește un subdosar din sursa autorizată.');
 const parts=value.replace(/\/+$/,'').split('/');if(parts.length>8||parts.some(p=>p&&p!==p.trim())||parts.length>1&&parts.some(p=>!p))bad('Folosește cel mult opt subdosare, fără segmente goale.');
 return parts.join('/');
}
export function selection(v,grant){
 keys(v,['photos','files','photo_count','file_count','photo_folder','file_folder','from','to','recursive','wifi_only','mode'],['photos','files','photo_count','file_count','photo_folder','file_folder','from','to','recursive','wifi_only']);
 if(v.mode!==undefined&&!['manual','local'].includes(v.mode))bad('Alege organizare manuală sau analiză locală.');
 if(['photos','files','recursive','wifi_only'].some(k=>typeof v[k]!=='boolean')||!v.photos&&!v.files)bad('Alege fotografii sau documente.');
 if(!grant?.enabled||v.photos&&!grant.photos||v.files&&!grant.files)bad('Sursa nu este autorizată pe telefon.',403);
 if(['photo_count','file_count'].some(k=>!Number.isInteger(v[k])||v[k]<0||v[k]>INVENTORY_LIMIT))bad('Alege un număr între 1 și 15.000, sau Toate.');
 for(const key of ['from','to'])if(v[key]!==null&&(!Number.isSafeInteger(v[key])||v[key]<0||v[key]>8640000000000000))bad('Interval invalid.');
 if(v.from!==null&&v.to!==null&&v.from>=v.to)bad('Sfârșitul intervalului trebuie să fie după început.');
 return {...v,photo_folder:pathName(v.photo_folder),file_folder:pathName(v.file_folder)};
}
export function runLimit(run){const s=run.selection;return s?(s.photos?(s.photo_count||INVENTORY_LIMIT):0)+(s.files?(s.file_count||INVENTORY_LIMIT):0):run.schedule.count*(Number(run.schedule.photos)+Number(run.schedule.files));}
