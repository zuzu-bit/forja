import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash,randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {analyzeOrganizerContent,prepareOrganizerEvidence,validateOrganizerProposal,organizerModelInput,ORGANIZER_MODELS,ORGANIZER_VISION_MODEL} from './organizer-analysis.mjs';
import {evaluateOrganizerModels} from './organizer-model-eval.mjs';
import evaluationBridge from './organizer-eval-worker.mjs';

const digest=b=>createHash('sha256').update(b).digest('hex');
const source=(text='Lecție despre fotosinteză. Plantele transformă lumina în energie.',mime='text/plain')=>{
  const bytes=typeof text==='string'?new TextEncoder().encode(text):text;
  return {bytes,file:{id:randomUUID(),item_id:randomUUID(),sha256:digest(bytes),bytes:bytes.length,media_type:mime,name:'FACTURĂ secretă.pdf'}};
};
const proposal=(patch={})=>({destination:'Educație/Biologie',reason:'Lecție despre fotosinteză.',confidence:'high',evidence:[{source_id:'text',quote:'Lecție despre fotosinteză.',observation:''}],...patch});
async function analyze(f=source(),changes={}) {
  const calls=[];const result=await analyzeOrganizerContent({env:{AI:{async run(model,input){calls.push({model,input});return {response:proposal()};}}},...f,authorize:async()=>'owner:grant:job:1',...changes});
  return {result,calls};
}
test('actual UTF-8 bytes drive inference; metadata names never stand in for content',async()=>{
  const {result,calls}=await analyze();assert.equal(calls.length,1);
  assert.match(calls[0].input.messages[1].content,/fotosinteză/);assert.doesNotMatch(calls[0].input.messages[1].content,/secretă/);
  assert.equal(result.status,'complete');assert.equal(result.coverage.methods[0],'server_utf8_from_received_bytes');
  assert.equal(result.evidence[0].quote,'Lecție despre fotosinteză.');assert.equal(result.applied,false);assert.equal(result.deletion.suggested,false);
});
test('copy hash, size, signature and source limits fail before any AI use',async()=>{
  for(const change of [f=>f.file.sha256='a'.repeat(64),f=>f.file.bytes++,f=>f.file.media_type='image/jpeg',f=>f.file.bytes=25*1024*1024+1]) {
    const f=source();change(f);let calls=0;
    await assert.rejects(()=>analyze(f,{env:{AI:{run(){calls++;}}}}));assert.equal(calls,0);
  }
});
test('unsupported binary is honestly unavailable with no metadata-only inference',async()=>{
  const f=source(new Uint8Array([0,1,2,3]),'application/octet-stream'),{result,calls}=await analyze(f);
  assert.equal(calls.length,0);assert.equal(result.status,'unsupported');assert.equal(result.model,null);assert.deepEqual(result.evidence,[]);
});
test('oversized text is bounded and cannot be presented as integral',async()=>{
  const f=source('Lecție despre fotosinteză. '+'a'.repeat(200000)),{result,calls}=await analyze(f);
  assert.equal(result.coverage.analyzed_text_chars,32000);assert.equal(result.status,'partial');assert.equal(result.confidence,'medium');
  assert(calls[0].input.messages[1].content.length<34000);
});
test('PDF extraction ties exact source hash and real page spans to each quote',async()=>{
  const f=source('%PDF-1.7\n synthetic placeholder','application/pdf'),text='Lecție despre fotosinteză.';
  const extraction={source_sha256:f.file.sha256,method:'pdf_text_ocr',partial:true,text,pages_total:50,pages_processed:1,page_spans:[{page:23,start:0,end:text.length,method:'ocr'}],limitations:['page_limit']};
  const p=await prepareOrganizerEvidence(f.file,f.bytes,extraction);
  assert.equal(p.coverage.status,'partial');assert.equal(p.coverage.analyzed_text_chars,text.length);
  const validated=validateOrganizerProposal(proposal({evidence:[{source_id:'page-23',quote:text,observation:''}]}),f.file,p);
  assert.equal(validated.evidence[0].page,23);assert.equal(validated.evidence[0].method,'phone_ocr');assert.equal(validated.confidence,'medium');
  await assert.rejects(()=>prepareOrganizerEvidence(f.file,f.bytes,{...extraction,source_sha256:'b'.repeat(64)}));
  await assert.rejects(()=>prepareOrganizerEvidence(f.file,f.bytes,{...extraction,page_spans:[{page:51,start:0,end:text.length,method:'ocr'}]}));
});
test('phone extraction provenance cannot claim independently server-verified OCR',async()=>{
  const f=source('%PDF-1.7\n sample','application/pdf');const p=await prepareOrganizerEvidence(f.file,f.bytes,{source_sha256:f.file.sha256,partial:false,method:'pdf_text',text:'Unele cuvinte'});
  assert.equal(p.coverage.status,'partial');assert(p.coverage.limitations.includes('phone_extraction_linked_by_source_hash'));
  assert(p.coverage.limitations.includes('page_coverage_unknown'));
});
test('binary formats cannot gain fabricated readable content through mismatched extraction methods',async()=>{
  const f=source(new Uint8Array([0,1,2,3]),'application/octet-stream');
  for(const method of ['pdf_text','unsupported'])await assert.rejects(()=>prepareOrganizerEvidence(f.file,f.bytes,{source_sha256:f.file.sha256,partial:false,method,text:'Lecție inventată'}));
});
test('unquoted text outside page spans is not counted as read evidence',async()=>{
  const f=source('%PDF-1.7\n sample','application/pdf');const p=await prepareOrganizerEvidence(f.file,f.bytes,{source_sha256:f.file.sha256,partial:true,method:'pdf_text',text:'abc omitted',pages_total:2,pages_processed:1,page_spans:[{page:1,start:0,end:3,method:'pdf_text'}]});
  assert.equal(p.coverage.analyzed_text_chars,3);
});
test('invented quotes, sources, traversal, silent deletion and unknown fields fail closed',async()=>{
  const f=source(),p=await prepareOrganizerEvidence(f.file,f.bytes);
  for(const value of [proposal({destination:'../private'}),proposal({destination:'a/\u202eprivate'}),proposal({deletion:true}),proposal({confidence:'certain'}),proposal({evidence:[{source_id:'page-8',quote:'Text',observation:''}]}),proposal({evidence:[{source_id:'text',quote:'Fișier inutil',observation:''}]}),proposal({evidence:[{source_id:'text',quote:'Lecție',observation:'pretins'}]})])assert.throws(()=>validateOrganizerProposal(value,f.file,p));
});
test('revocation and off-on revision changes during inference discard the result',async()=>{
  for(const scenario of ['revoked','off-on']) {
    let token='grant:1';const f=source();let calls=0;
    await assert.rejects(()=>analyze(f,{authorize:async()=>{if(token==='revoked')throw new Error('revoked');return token;},env:{AI:{run:async()=>{calls++;token=scenario==='revoked'?'revoked':'grant:3';return {response:proposal()};}}}}));
    assert.equal(calls,1);
  }
});
test('authorization required before loading and must remain stable even for unsupported files',async()=>{
  const f=source();await assert.rejects(()=>analyze(f,{authorize:undefined}));await assert.rejects(()=>analyze(f,{authorize:async()=>null}));
  let checks=0;await assert.rejects(()=>analyze(source(new Uint8Array([0,1,2])) ,{authorize:async()=>String(++checks)}));
});
test('actual original image bytes enter vision and legacy caption stage is explicitly partial',async()=>{
  const bytes=new Uint8Array(await readFile(new URL('./fixtures/organizer/invoice.png',import.meta.url))),f=source(bytes,'image/png'),calls=[];
  const {result}=await analyze(f,{env:{AI:{run:async(model,input)=>{calls.push({model,input});if(model===ORGANIZER_VISION_MODEL)return{response:'Fotografie de factură pentru reparație de bicicletă.'};return{response:proposal({destination:'Documente/Facturi',evidence:[{source_id:'image',quote:'',observation:'Textul FACTURĂ este vizibil.'}]})};}}}});
  assert.equal(calls.length,2);assert.deepEqual(calls[0].input.image,[...bytes]);assert.equal(result.status,'partial');assert(result.coverage.limitations.includes('intermediate_visual_description'));
  assert.equal(result.evidence[0].representation,'original');assert.equal(result.evidence[0].interpretation,'unconfirmed_ai_observation');
});
test('large original photo uses only a source-bound real thumbnail and reports reduced resolution',async()=>{
  const small=new Uint8Array([255,216,255,224,1,2,3]),big=new Uint8Array(4*1024*1024+1);big.set(small);const f=source(big,'image/jpeg');
  const p=await prepareOrganizerEvidence(f.file,f.bytes,{visual:{bytes:small,source_sha256:f.file.sha256,media_type:'image/jpeg',representation:'thumbnail'}});
  assert.equal(p.coverage.status,'partial');assert.equal(p.image.representation,'thumbnail');assert(p.coverage.limitations.includes('reduced_resolution_image'));
  const none=await prepareOrganizerEvidence(f.file,f.bytes);assert.equal(none.coverage.status,'unavailable');
});
test('HEIF and AVIF require real source-bound JPEG renditions, never opaque-original vision',async()=>{
  for(const brand of ['heic','mif1','avif']) {
    const original=new Uint8Array(24);new DataView(original.buffer).setUint32(0,24);original.set(new TextEncoder().encode('ftyp'+brand),4);original.set(new TextEncoder().encode(brand),16);
    const f=source(original,brand==='avif'?'image/avif':'image/heic');
    const unavailable=await prepareOrganizerEvidence(f.file,f.bytes);assert.equal(unavailable.coverage.status,'unavailable');assert.equal(unavailable.image,null);
    const thumb=new Uint8Array([255,216,255,224,1,2,3]);const extraction={visual:{bytes:thumb,source_sha256:f.file.sha256,media_type:'image/jpeg',representation:'thumbnail'}};
    const ready=await prepareOrganizerEvidence(f.file,f.bytes,extraction);
    assert.equal(ready.image.media_type,'image/jpeg');assert.deepEqual(ready.image.bytes,thumb);assert.equal(ready.coverage.status,'partial');assert(ready.coverage.limitations.includes('image_format_rendition'));
    await assert.rejects(()=>prepareOrganizerEvidence(f.file,f.bytes,{visual:{...extraction.visual,source_sha256:'c'.repeat(64)}}));
  }
});
test('candidate contracts use actual data URL and supported structured-output options',async()=>{
  const bytes=new Uint8Array(await readFile(new URL('./fixtures/organizer/invoice.png',import.meta.url))),f=source(bytes,'image/png');
  for(const model of [ORGANIZER_MODELS.kimi,ORGANIZER_MODELS.scout]) {
    let input;await analyze(f,{env:{ORGANIZER_ANALYSIS_MODEL:model,AI:{run:async(_,i)=>{input=i;return{choices:[{message:{content:JSON.stringify(proposal({evidence:[{source_id:'image',quote:'',observation:'O factură tipărită.'}]}))}}]};}}}});
    const data=input.messages[1].content[1].image_url.url;assert.equal(data,'data:image/png;base64,'+Buffer.from(bytes).toString('base64'));
    if(model===ORGANIZER_MODELS.kimi){assert.equal(input.reasoning_effort,'none');assert.equal(input.max_completion_tokens,2000);assert.equal(input.response_format.json_schema.schema.type,'object');}
    else assert.equal(input.guided_json.type,'object');
  }
});
test('provider errors and unknown models are failures, never fabricated successful proposals',async()=>{
  await assert.rejects(()=>analyze(source(),{env:{AI:{run:async()=>{throw new Error('unavailable');}}}}));
  await assert.rejects(()=>analyze(source(),{env:{AI:{run:async()=>({response:'not JSON'})}}}));
  await assert.rejects(()=>analyze(source(),{env:{ORGANIZER_ANALYSIS_MODEL:'invented',AI:{run:async()=>({})}}}));
});
test('protected inputs do not acquire an AI deletion proposal',async()=>{
  const {result}=await analyze(source(),{preferences:{protected:true}});assert.equal(result.deletion.suggested,false);assert.equal(result.deletion.reason,'Fișier protejat.');assert.equal(result.deletion.requires_confirmation,true);
});
test('evaluation enforces a hard invocation budget and emits only sanitized provider diagnostics',async()=>{
  let calls=0;const report=await evaluateOrganizerModels(async()=>{calls++;const error=new Error('secret raw provider response');error.httpStatus=403;error.codes=[9109,'unsafe',NaN];throw error;},{models:[ORGANIZER_MODELS.legacy],diagnostic:true,maxCalls:1});
  assert.equal(calls,1);assert.equal(report.calls,1);assert.deepEqual(report.results[0].provider_errors,[{model:ORGANIZER_MODELS.legacy,http_status:403,codes:[9109]}]);assert.doesNotMatch(JSON.stringify(report),/secret|unsafe/);
  calls=0;const bounded=await evaluateOrganizerModels(async()=>{calls++;throw new Error('unavailable');},{maxCalls:1});assert.equal(calls,1);assert.equal(bounded.calls,1);
});
test('local evaluation bridge rejects public hosts and nonallowlisted models and caps calls at12',async()=>{
  let calls=0;const env={AI:{run:async()=>{calls++;return{response:'synthetic'};}}};
  const request=(host='127.0.0.1:8789',model=ORGANIZER_MODELS.legacy)=>new Request(`http://${host}/infer`,{method:'POST',headers:{'content-type':'application/json','x-organizer-eval':'synthetic-only-v1'},body:JSON.stringify({model,input:{messages:[]}})});
  assert.equal((await evaluationBridge.fetch(request('public.example'),env)).status,403);
  assert.equal((await evaluationBridge.fetch(request(undefined,'invented'),env)).status,400);assert.equal(calls,0);
  for(let i=0;i<12;i++)assert.equal((await evaluationBridge.fetch(request(),env)).status,200);
  assert.equal((await evaluationBridge.fetch(request(),env)).status,429);assert.equal(calls,12);
});
