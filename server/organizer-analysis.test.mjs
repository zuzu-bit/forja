import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash,randomUUID} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {analyzeOrganizerContent,prepareOrganizerEvidence,validateOrganizerProposal,organizerModelInput,organizerModel,geminiGenerate,ORGANIZER_MODELS,ORGANIZER_VISION_MODEL} from './organizer-analysis.mjs';
import {GEMINI_ENDPOINT,geminiSchema} from './gemini.mjs';
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
  const {result}=await analyze(f,{env:{ORGANIZER_ANALYSIS_MODEL:ORGANIZER_MODELS.legacy,AI:{run:async(model,input)=>{calls.push({model,input});if(model===ORGANIZER_VISION_MODEL)return{response:'Fotografie de factură pentru reparație de bicicletă.'};return{response:proposal({destination:'Documente/Facturi',evidence:[{source_id:'image',quote:'',observation:'Textul FACTURĂ este vizibil.'}]})};}}}});
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

// ── Gemini 2.5 Flash adapter (fetch is mocked; no network) ──
const geminiReply=(value,status=200)=>new Response(JSON.stringify(status===200?{candidates:[{content:{parts:[{text:JSON.stringify(value)}]},finishReason:'STOP'}]}:{error:{code:status}}),{status,headers:{'content-type':'application/json'}});
const hasKeyword=(value,keys)=>JSON.stringify(value).split('"').some(s=>keys.includes(s));
test('a Gemini key selects gemini-2.5-flash; without it the configured Workers AI model or Scout is used',()=>{
  assert.equal(organizerModel({GEMINI_API_KEY:'k',ORGANIZER_ANALYSIS_MODEL:ORGANIZER_MODELS.kimi}),ORGANIZER_MODELS.gemini);
  assert.equal(organizerModel({ORGANIZER_ANALYSIS_MODEL:ORGANIZER_MODELS.kimi}),ORGANIZER_MODELS.kimi);
  assert.equal(organizerModel({}),ORGANIZER_MODELS.scout);assert.equal(organizerModel({GEMINI_API_KEY:''}),ORGANIZER_MODELS.scout);
});
test('Gemini receives the system rule, the evidence context, the real image bytes inline and a relaxed JSON schema',async(t)=>{
  const bytes=new Uint8Array(await readFile(new URL('./fixtures/organizer/invoice.png',import.meta.url))),f=source(bytes,'image/png'),requests=[];let aiCalls=0;
  t.mock.method(globalThis,'fetch',async(url,init)=>{requests.push({url,init});return geminiReply(proposal({destination:'Documente/Facturi',evidence:[{source_id:'image',quote:'',observation:'Se vede o factură tipărită cu un total în lei.'}]}));});
  const {result}=await analyze(f,{env:{GEMINI_API_KEY:'secret-key',ORGANIZER_ANALYSIS_MODEL:ORGANIZER_MODELS.scout,AI:{run:async()=>{aiCalls++;return {};}}}});
  assert.equal(aiCalls,0);assert.equal(requests.length,1);assert.equal(requests[0].url,GEMINI_ENDPOINT);assert.equal(requests[0].url,'https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent');
  assert.equal(requests[0].init.method,'POST');assert.equal(requests[0].init.headers['x-goog-api-key'],'secret-key');assert.equal(requests[0].init.headers['content-type'],'application/json');
  const body=JSON.parse(requests[0].init.body);
  assert.match(body.system_instruction.parts[0].text,/Clasifică numai conținutul furnizat/);
  assert.equal(body.contents[0].role,'user');assert.equal(body.contents[0].parts[0].text.includes('"source_sha256":"'+f.file.sha256+'"'),true);assert.doesNotMatch(body.contents[0].parts[0].text,/secretă/);
  assert.deepEqual(body.contents[0].parts[1],{inline_data:{mime_type:'image/png',data:Buffer.from(bytes).toString('base64')}});
  assert.equal(body.generationConfig.responseMimeType,'application/json');assert.equal(body.generationConfig.temperature,0.1);assert.equal(body.generationConfig.maxOutputTokens,2000);
  const schema=body.generationConfig.responseSchema;
  assert.equal(hasKeyword(schema,['const','oneOf','additionalProperties']),false);
  assert.deepEqual(schema.properties.evidence.items.properties.source_id.enum,['image']);
  // Gemini's enum holds strings only: booleans and empty strings never appear inside an enum, and deletion_review is a single object.
  assert.equal(schema.properties.deletion_review.anyOf,undefined);assert.equal(schema.properties.deletion_review.properties.suggested.type,'boolean');assert.equal(schema.properties.deletion_review.properties.suggested.enum,undefined);
  assert.deepEqual(schema.properties.deletion_review.properties.basis.enum,['none','low_information']);
  assert.equal(JSON.stringify(schema).includes('"enum":[false]')||JSON.stringify(schema).includes('"enum":[""]')||JSON.stringify(schema).includes('"enum":[true]'),false);
  assert.deepEqual(body.generationConfig.thinkingConfig,{thinkingBudget:0});
  assert.equal(result.model,ORGANIZER_MODELS.gemini);assert.equal(result.vision_model,null);assert.equal(result.status,'complete');assert.equal(result.destination,'Documente/Facturi');
  assert.equal(result.evidence[0].representation,'original');assert.equal(result.applied,false);
});
test('Gemini stays behind the proposal gate: invented quotes and deletions are rejected even from the preferred model',async(t)=>{
  t.mock.method(globalThis,'fetch',async()=>geminiReply(proposal({evidence:[{source_id:'text',quote:'Fișier inutil',observation:''}]})));
  await assert.rejects(()=>analyze(source(),{env:{GEMINI_API_KEY:'k',AI:{run:async()=>({response:proposal()})}}}),/Citatul AI nu există/);
});
test('any Gemini failure falls back to Workers AI Scout with the same context; the 503 remains only when both fail',async(t)=>{
  for(const failure of [()=>geminiReply(null,500),()=>{throw new Error('network');},()=>new Response(JSON.stringify({promptFeedback:{blockReason:'SAFETY'}}),{status:200}),()=>new Response(JSON.stringify({candidates:[{content:{parts:[]},finishReason:'MAX_TOKENS'}]}),{status:200})]) {
    const fetchMock=t.mock.method(globalThis,'fetch',async()=>failure());
    const f=source(),calls=[],{result}=await analyze(f,{env:{GEMINI_API_KEY:'k',AI:{run:async(model,input)=>{calls.push({model,input});return {response:proposal()};}}}});
    assert.equal(fetchMock.mock.callCount(),1);fetchMock.mock.restore();
    assert.equal(calls.length,1);assert.equal(calls[0].model,ORGANIZER_MODELS.scout);assert.equal(calls[0].input.guided_json.type,'object');assert.match(calls[0].input.messages[1].content,/fotosinteză/);
    assert.equal(result.model,ORGANIZER_MODELS.scout);assert.equal(result.destination,'Educație/Biologie');
  }
  t.mock.method(globalThis,'fetch',async()=>geminiReply(null,429));
  await assert.rejects(()=>analyze(source(),{env:{GEMINI_API_KEY:'k',AI:{run:async()=>{throw new Error('unavailable');}}}}),/Analiza AI nu a reușit/);
  await assert.rejects(()=>analyze(source(),{env:{GEMINI_API_KEY:'k'}}),/Analiza AI nu a reușit/);
  await assert.rejects(()=>analyze(source(),{env:{}}),/Serviciul de analiză nu este disponibil/);
});
test('a grant change during the Gemini call discards the result before any fallback',async(t)=>{
  let token='grant:1';
  t.mock.method(globalThis,'fetch',async()=>{token='grant:2';return geminiReply(proposal());});
  let aiCalls=0;
  await assert.rejects(()=>analyze(source(),{authorize:async()=>token,env:{GEMINI_API_KEY:'k',AI:{run:async()=>{aiCalls++;return {response:proposal()};}}}}),/s-a schimbat/);
  assert.equal(aiCalls,0);
});
test('geminiGenerate never runs without a key and converts every schema keyword Gemini rejects',async()=>{
  await assert.rejects(()=>geminiGenerate({},{system:'s',parts:[{text:'t'}]}),/gemini_key_missing/);
  const relaxed=geminiSchema({type:'object',additionalProperties:false,required:['a'],properties:{a:{type:'string',const:'x'},b:{oneOf:[{type:'string',minLength:2,maxLength:5},{type:'string',const:''}]},c:{type:'boolean',const:true},d:{type:'array',minItems:0,maxItems:2,items:{type:'string',enum:['e1']}}}});
  assert.deepEqual(relaxed,{type:'object',required:['a'],properties:{a:{type:'string',enum:['x']},b:{anyOf:[{type:'string',minLength:2,maxLength:5},{type:'string',description:'Valoare fixă: "".'}]},c:{type:'boolean',description:'Valoare fixă: true.'},d:{type:'array',minItems:0,maxItems:2,items:{type:'string',enum:['e1']}}}});
  assert.deepEqual(geminiSchema({type:['string','null'],format:'uri',description:'d'}),{type:'string',nullable:true,description:'d'});
  assert.deepEqual(geminiSchema({type:'integer',enum:[1,2],format:'int32'}),{type:'integer',format:'int32',description:'Valori permise: 1, 2.'});
  const text=source(),input=organizerModelInput(ORGANIZER_MODELS.gemini,[{role:'system',content:'S'},{role:'user',content:'context'}],[{id:'text',kind:'text',text:'abc'}]);
  assert.deepEqual(input.parts,[{text:'context'}]);assert.equal(input.system,'S');assert.deepEqual(input.schema.properties.evidence.items.properties.source_id.enum,['text']);
  assert.equal(hasKeyword(input.schema,['const','oneOf','additionalProperties']),false);
  const fetched=[];const r=await geminiGenerate({GEMINI_API_KEY:'k'},input,async(url,init)=>{fetched.push(init);return geminiReply({ok:true});});
  assert.deepEqual(JSON.parse(r.response),{ok:true});assert.equal(r.model,'gemini-2.5-flash');assert.equal(JSON.parse(fetched[0].body).generationConfig.responseSchema.type,'object');
  assert.equal(text.file.bytes>0,true);
});
test('the evaluation allowlist accepts gemini-2.5-flash and routes it through the Gemini contract, not Workers AI',async()=>{
  const seen=[];
  const report=await evaluateOrganizerModels(async(model,input)=>{seen.push({model,input});return {response:JSON.stringify(proposal({destination:'Documente/Facturi',evidence:[{source_id:'text',quote:'FACTURĂ.',observation:''}]}))};},{models:[ORGANIZER_MODELS.gemini],cases:['text_not_filename'],maxCalls:1});
  assert.equal(report.calls,1);assert.equal(seen[0].model,'gemini-2.5-flash');assert.equal(Array.isArray(seen[0].input.parts),true);assert.equal(seen[0].input.messages,undefined);
  assert.equal(report.summary[0].passed,1);
});
