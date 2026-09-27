import {bad,keys,n} from './phone-schema.mjs';
const supportedReasons=['decode_failed','model_unavailable','inference_failed','cancelled'];
export function validateAcoustic(v,duration){
 keys(v,['status','model','model_version','model_sha256','analyzed_ms','analyzed_ranges','events','reason'],['status','model','model_version','analyzed_ms','analyzed_ranges','events']);
 if(!['complete','unavailable'].includes(v.status)||v.model!=='yamnet'||v.model_version!=='yamnet-audioset-1')bad('Invalid acoustic model');
 if(v.model_sha256!==undefined&&!/^[a-f0-9]{64}$/.test(v.model_sha256))bad('Invalid acoustic model hash');
 if(!Array.isArray(v.analyzed_ranges)||v.analyzed_ranges.length>200||!Array.isArray(v.events)||v.events.length>200)bad('Too many acoustic intervals');
 n(v.analyzed_ms,0,duration+1000);const ranges=[];let covered=0,last=-1;
 for(const r of v.analyzed_ranges){keys(r,['start_ms','end_ms']);n(r.start_ms,0,duration+1000);n(r.end_ms,r.start_ms+1,duration+1000);if(r.start_ms<last)bad('Overlapping acoustic coverage');last=r.end_ms;covered+=r.end_ms-r.start_ms;ranges.push({start_ms:Math.min(duration,r.start_ms),end_ms:Math.min(duration,r.end_ms)});}
 if(Math.abs(covered-v.analyzed_ms)>1)bad('Acoustic coverage mismatch');
 const events=[];last=-1;
 for(const e of v.events){keys(e,['start_ms','end_ms','kind','score']);n(e.start_ms,0,duration+1000);n(e.end_ms,e.start_ms+1,duration+1000);n(e.score,0,1,false);if(e.kind!=='possible_snoring'||e.start_ms<last||!v.analyzed_ranges.some(r=>e.start_ms>=r.start_ms&&e.end_ms<=r.end_ms))bad('Invalid acoustic event');last=e.end_ms;events.push({start_ms:Math.min(duration,e.start_ms),end_ms:Math.min(duration,e.end_ms),kind:e.kind,score:e.score});}
 if(v.status==='complete'&&(!v.model_sha256||!covered||v.reason!==undefined))bad('Complete acoustic coverage requires model and samples');
 if(v.status==='unavailable'&&(!supportedReasons.includes(v.reason)||covered||events.length))bad('Unavailable acoustic analysis cannot assert observations');
 const clippedRanges=ranges.filter(r=>r.end_ms>r.start_ms);if(v.status==='complete'&&!clippedRanges.length)bad('Complete acoustic coverage must include recorded samples');
 return{status:v.status,model:v.model,model_version:v.model_version,...(v.model_sha256?{model_sha256:v.model_sha256}:{}),source:'on_device',analyzed_ms:clippedRanges.reduce((sum,r)=>sum+r.end_ms-r.start_ms,0),analyzed_ranges:clippedRanges,events:events.filter(e=>e.end_ms>e.start_ms),...(v.reason?{reason:v.reason}:{})};
}
export function summarizeAcoustics(chunks){
 const available=chunks.filter(c=>c.acoustic?.status==='complete'&&c.acoustic.analyzed_ms>0),ranges=available.flatMap(c=>c.acoustic.events.map(e=>({start:c.recorded_from+e.start_ms,end:c.recorded_from+e.end_ms}))).sort((a,b)=>a.start-b.start);
 const merged=[];for(const r of ranges){const last=merged.at(-1);if(last&&r.start<=last.end){last.end=Math.max(last.end,r.end);}else merged.push({...r});}
 return {status:available.length?(available.length===chunks.length&&available.every(c=>c.acoustic.analyzed_ms>=c.duration_ms-1000)?'complete':'partial'):'unavailable',model:'yamnet',source:'on_device',analyzed_ms:available.reduce((sum,c)=>sum+c.acoustic.analyzed_ms,0),possible_intervals:available.length?merged.length:null,possible_ms:available.length?merged.reduce((sum,r)=>sum+r.end-r.start,0):null,limitations:['acoustic_detection_unverified','score_is_not_probability','not_a_diagnosis'],...(!available.length?{reason:'audio_event_analysis_unavailable'}:{})};
}
