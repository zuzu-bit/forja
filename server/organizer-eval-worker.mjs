/** Local Wrangler-only bridge to its remote AI binding. No production bindings or routes. */
import {ORGANIZER_MODELS,ORGANIZER_VISION_MODEL} from './organizer-analysis.mjs';
const models=new Set([...Object.values(ORGANIZER_MODELS),ORGANIZER_VISION_MODEL]);
let calls=0;
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'content-type':'application/json','cache-control':'no-store'}});
export default {
  async fetch(request,env) {
    const url=new URL(request.url);
    if(!['localhost','127.0.0.1','[::1]'].includes(url.hostname))return json({error:'loopback_only'},403);
    if(request.method==='GET'&&url.pathname==='/health')return json({ready:true,remaining:12-calls});
    if(request.method!=='POST'||url.pathname!=='/infer'||request.headers.get('x-organizer-eval')!=='synthetic-only-v1')return json({error:'eval_only'},403);
    if(calls>=12)return json({error:'evaluation_budget_exhausted'},429);
    if(Number(request.headers.get('content-length'))>256*1024)return json({error:'fixture_too_large'},413);
    let body;try{const text=await request.text();if(text.length>256*1024)return json({error:'fixture_too_large'},413);body=JSON.parse(text);}catch{return json({error:'invalid_fixture'},400);}
    if(!body||Object.keys(body).some(k=>!['model','input'].includes(k))||!models.has(body.model)||!body.input||typeof body.input!=='object'||Array.isArray(body.input))return json({error:'invalid_fixture'},400);
    calls++;
    try{return json({success:true,result:await env.AI.run(body.model,body.input)});}
    catch(error){
      // Never return a provider message/stack: these may contain prompt or credential material.
      const status=Number(error?.status??error?.httpStatus),code=Number(error?.code);
      return json({success:false,provider_http_status:Number.isInteger(status)&&status>=400&&status<600?status:null,
        errors:Number.isSafeInteger(code)?[{code}]:[]},502);
    }
  },
};
