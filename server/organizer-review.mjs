import {bad} from './phone-schema.mjs';

/** Review labels only. This module has no file/storage/network/mutation capability. */
const branch=suggested=>({type:'object',additionalProperties:false,
  required:['suggested','basis','reason','evidence_ids'],properties:{
    suggested:{type:'boolean',const:suggested},basis:{type:'string',const:suggested?'low_information':'none'},
    reason:suggested?{type:'string',minLength:2,maxLength:300}:{type:'string',const:''},
    evidence_ids:{type:'array',minItems:suggested?1:0,maxItems:suggested?6:0,items:{type:'string',enum:['e1','e2','e3','e4','e5','e6']}}
  }});
export const ORGANIZER_DELETION_REVIEW_SCHEMA={oneOf:[branch(false),branch(true)]};

export function noDeletionReview(protectedFile=false) {
  return {suggested:false,basis:null,reason:protectedFile?'Fișier protejat.':'',evidence_ids:[],requires_confirmation:true,review_only:true};
}

export function validateDeletionReview(value,evidence,{protectedFile=false}={}) {
  if(!value||typeof value!=='object'||Array.isArray(value)||Object.keys(value).length!==4||
     !['suggested','basis','reason','evidence_ids'].every(k=>Object.hasOwn(value,k))||typeof value.suggested!=='boolean'||
     !['none','low_information'].includes(value.basis)||typeof value.reason!=='string'||value.reason.length>300||
     /[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/.test(value.reason)||!Array.isArray(value.evidence_ids)||value.evidence_ids.length>6||
     new Set(value.evidence_ids).size!==value.evidence_ids.length)bad('Propunerea de verificare este invalidă.',502);
  if(!value.suggested) {
    if(value.basis!=='none'||value.evidence_ids.length||value.reason!=='')bad('Propunerea de verificare este contradictorie.',502);
    return noDeletionReview(protectedFile);
  }
  if(value.basis!=='low_information'||!value.reason.trim()||!value.evidence_ids.length||
     value.evidence_ids.some(id=>!evidence.some(e=>e.id===id&&['text','visual'].includes(e.kind))))bad('Verificarea necesită dovezi din conținut.',502);
  if(protectedFile)return noDeletionReview(true);
  return {suggested:true,basis:'low_information',reason:value.reason,evidence_ids:value.evidence_ids,
    requires_confirmation:true,review_only:true};
}
