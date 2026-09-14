import { createRemoteJWKSet, jwtVerify } from 'jose';
import { handleInsights, accountStub } from './insights-ai.mjs';
import { reply } from './insights-store.mjs';
import html from './insights.html';
import client from './insights-client.js.txt';
export { InsightsAccount } from './insights-store.mjs';

const project = 'forja-65093';
const keys = createRemoteJWKSet(new URL('https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com'));
export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method === 'GET' && ['/', '/admin', '/insights', '/insights/'].includes(path)) {
      return new Response(html, { headers: {
        'content-type':'text/html; charset=utf-8', 'cache-control':'no-store',
        'content-security-policy':"default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self' https://identitytoolkit.googleapis.com https://securetoken.googleapis.com; img-src 'self' blob: data:; media-src blob:; base-uri 'none'; frame-ancestors 'none'; form-action 'none'",
        'referrer-policy':'no-referrer', 'x-content-type-options':'nosniff'
      } });
    }
    if (request.method === 'GET' && path === '/insights/app.js') return new Response(client, { headers: { 'content-type':'text/javascript; charset=utf-8', 'cache-control':'no-cache', 'x-content-type-options':'nosniff' } });
    if (request.method === 'GET' && path === '/health') return reply({ ok:true, service:'forja-insights', version:4 });
    if (!path.startsWith('/v2/') && !path.startsWith('/insights/api/')) return reply({error:'Not found'},404);
    const auth = request.headers.get('Authorization') || ''; let uid;
    try {
      if (!auth.startsWith('Bearer ')) throw Error();
      const {payload} = await jwtVerify(auth.slice(7), keys, {issuer:'https://securetoken.google.com/'+project,audience:project,algorithms:['RS256']});
      uid = payload.sub;
      if (typeof uid !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(uid)) throw Error();
    } catch { return reply({error:'Conectează-te cu contul FORJA.'},401); }
    try {
      if (path.startsWith('/insights/api/')) return await handleInsights(request, env, uid);
      if (!/^\/v2\/sessions(?:\/[0-9a-f-]+(?:\/(?:data|items|recording)(?:\/[0-9a-f-]+)?)?)?$/.test(path)) return reply({error:'Not found'},404);
      const headers = new Headers(request.headers); headers.set('x-forja-owner',uid);
      return await accountStub(env,uid).fetch(new Request(request,{headers}));
    } catch(e) { return reply({error:e.status?e.message:'Datele nu sunt disponibile acum.'},e.status||500); }
  }
};
