import {handleSleepAPI} from './sleep-api.mjs';
import {organizeJobAI} from './organizer-job-ai.mjs';
import { createRemoteJWKSet, jwtVerify } from 'jose';
import { handleInsights, accountStub } from './insights-ai.mjs';
import { handleSiteApi, isSiteApi } from './site-api.mjs';
import { somnSigned, isSignedAudio } from './site/sec-somn.mjs';
import { reply } from './insights-store.mjs';
import { siteStatic } from './site-static.mjs';
export { SocialGraph } from './social.mjs';
export { InsightsAccount } from './insights-store.mjs';

const project = 'forja-65093';
/** The number typed in the app: forwarded ONLY under this name (never as x-forja-phone, which is the SMS-verified claim). */
const declaredPhone = request => { const v = request.headers.get('x-forja-phone-declared') || ''; return /^\+[1-9][0-9]{7,14}$/.test(v) ? v : ''; };
const keys = createRemoteJWKSet(new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'));
export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    const asset = siteStatic(request, path); if (asset) return asset;
    if (request.method === 'GET' && path === '/health') return reply({ ok:true, service:'forja-insights', version:18, organizer_jobs:4, journey:1, explore_sync:2, map3d:1, content_ai:2, visual_ui:1, sleep_audio:1, lost_phone:2, partners:1, contacts:2, social:1, organizer_modes:1, files_sync:1, cleanup_schedule:1, background_audio:1, organizer:1, site_sections:1, inventory_runs:1, music_summary:1 });
    // Sunetul nopții pe URL semnat (fără token: <audio> nu trimite antete); semnătura HMAC e credențialul.
    if (isSignedAudio(path)) { try { return await somnSigned(request, env); } catch { return reply({error:'Sunetul nu poate fi redat acum.'},500); } }
    if (!path.startsWith('/v2/') && !path.startsWith('/insights/api/')) return reply({error:'Not found'},404);
    const auth = request.headers.get('Authorization') || ''; let uid,verifiedPhone='',tokenIssued=0;
    try {
      if (!auth.startsWith('Bearer ')) throw Error();
      const {payload} = await jwtVerify(auth.slice(7), keys, {issuer:'https://securetoken.google.com/'+project,audience:project,algorithms:['RS256']});
      uid = payload.sub;verifiedPhone=typeof payload.phone_number==='string'&&/^\+[1-9][0-9]{7,14}$/.test(payload.phone_number)?payload.phone_number:'';tokenIssued=payload.iat||0;
      if (typeof uid !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) throw Error();
    } catch { return reply({error:'Conectează-te cu contul FORJA.'},401); }
    try {
      if (path.startsWith('/v2/social/')) {const headers=new Headers(request.headers);headers.set('x-forja-owner',uid);headers.set('x-forja-phone',verifiedPhone);headers.set('x-forja-phone-declared',declaredPhone(request));headers.set('x-forja-token-issued',String(tokenIssued));return await env.SOCIAL.get(env.SOCIAL.idFromName('friends-v1')).fetch(new Request(request,{headers}));}
      if(path.startsWith('/v2/sleep/'))return await handleSleepAPI(request,env,uid);
      if(path==='/insights/api/organizer-analysis'&&request.method==='POST')return await organizeJobAI(request,env,uid,accountStub(env,uid));
      if (isSiteApi(path)) return await handleSiteApi(request, env, uid);
      if (path.startsWith('/insights/api/')) return await handleInsights(request, env, uid);
      if (path.startsWith('/v2/recovery/')) {const headers=new Headers(request.headers);headers.set('x-forja-owner',uid);return await accountStub(env,uid).fetch(new Request(request,{headers}));}
      if (!/^\/v2\/organizer\/devices\/[0-9a-f-]+\/jobs(?:\/[0-9a-f-]+(?:\/(?:command|items|batch|receipts|approve))?)?$/.test(path) && !/^\/v2\/organizer\/devices\/[0-9a-f-]+\/(?:request|plans|runs)(?:\/[0-9a-f-]+)?(?:\/(?:items|plan))?$/.test(path) && !/^\/v2\/cleanup\/devices(?:\/[0-9a-f-]+(?:\/(?:grant|schedule|claim|runs)(?:\/[0-9a-f-]+)?)?)?$/.test(path) && !/^\/v2\/files(?:\/settings(?:\/[0-9a-f-]+)?|\/[0-9a-f-]+(?:\/thumbnail)?)?$/.test(path) && path !== '/v2/site/forget' && !/^\/v2\/mirror(?:\/(?:summary|consent|ids|cover\/[A-Za-z0-9_-]{1,40}(?:\/[A-Za-z0-9_-]{1,40})?|[0-9a-f-]{36}(?:\/(?:thumb|poster))?))?$/.test(path) && !/^\/v2\/sessions(?:\/[0-9a-f-]+(?:\/(?:data|items|recording)(?:\/[0-9a-f-]+)?)?)?$/.test(path)) return reply({error:'Not found'},404);
      const headers = new Headers(request.headers); headers.set('x-forja-owner',uid);
      return await accountStub(env,uid).fetch(new Request(request,{headers}));
    } catch(e) { return reply({error:e.status?e.message:'Datele nu sunt disponibile acum.'},e.status||500); }
  }
};
