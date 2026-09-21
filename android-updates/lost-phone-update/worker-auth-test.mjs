/** Execute the Wrangler bundle with synthetic RS256 keys and a mocked JWK endpoint.
 * Only static Text imports are inlined for Node; authentication code is unchanged.
 */
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdtemp,rm} from 'node:fs/promises';
import {resolve,dirname,join} from 'node:path';
import {tmpdir} from 'node:os';
import {pathToFileURL} from 'node:url';
import {generateKeyPair,exportJWK,SignJWT} from '../audio-diagnosis/forja-v17/server/node_modules/jose/dist/node/esm/index.js';
const bundle=resolve(process.argv[2]);const temp=await mkdtemp(join(tmpdir(),'forja-jwt22-'));
const {privateKey,publicKey}=await generateKeyPair('RS256');const jwk={...await exportJWK(publicKey),kid:'fixture',alg:'RS256',use:'sig'};
const originalFetch=globalThis.fetch;
globalThis.fetch=async url=>{assert.equal(String(url),'https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com');return Response.json({keys:[jwk]});};
try{
 let source=await readFile(bundle,'utf8');for(const m of [...source.matchAll(/^import (\w+) from "(\.\/[^\"]+)";$/gm)]) {const value=JSON.stringify(await readFile(resolve(dirname(bundle),m[2]),'utf8'));source=source.replace(m[0],()=>`const ${m[1]} = ${value};`);}
 assert(!/^import /m.test(source));const local=join(temp,'worker.mjs');await writeFile(local,source);const worker=(await import(pathToFileURL(local).href)).default;
 const recoveryCalls=[],accounts=[];const calls=[],env={INSIGHTS:{idFromName:uid=>{accounts.push(uid);return uid},get:id=>({fetch:r=>{recoveryCalls.push(r);return Response.json({ok:true})}})},SOCIAL:{idFromName:name=>name,get:id=>({fetch:r=>{calls.push(r);return Response.json({ok:true});}})}};
 const token=(extra={})=>new SignJWT({sub:'alice',iss:'https://securetoken.google.com/forja-65093',aud:'forja-65093',iat:Math.floor(Date.now()/1000),exp:Math.floor(Date.now()/1000)+300,...extra}).setProtectedHeader({alg:'RS256',kid:'fixture'}).sign(privateKey);
 const call=t=>worker.fetch(new Request('https://forja.test/v2/social/contacts/discovery',{method:'POST',headers:{authorization:'Bearer '+t,'x-forja-owner':'victim','x-forja-phone':'+40721999999','x-forja-token-issued':'1'},body:'{"consent":true}'}),env);
 assert.equal((await call(await token())).status,200);assert.equal(calls[0].headers.get('x-forja-owner'),'alice');assert.equal(calls[0].headers.get('x-forja-phone'),'');assert.notEqual(calls[0].headers.get('x-forja-token-issued'),'1');
 assert.equal((await call(await token({phone_number:'+40721000001'}))).status,200);assert.equal(calls[1].headers.get('x-forja-phone'),'+40721000001');
 for(const claims of [{aud:'another-project'},{iss:'https://attacker.test'},{exp:1},{sub:'../victim'}])assert.equal((await call(await token(claims))).status,401);
 assert.equal((await call('unsigned.forged.jwt')).status,401);assert.equal(calls.length,2);
 const recovery=t=>worker.fetch(new Request('https://forja.test/v2/recovery/devices',{headers:{authorization:'Bearer '+t,'x-forja-owner':'victim'}}),env);
 assert.equal((await recovery(await token())).status,200);assert.equal(recoveryCalls[0].headers.get('x-forja-owner'),'alice');assert.deepEqual(accounts,['account:alice']);assert.equal((await recovery('forged.jwt.token')).status,401);assert.equal(recoveryCalls.length,1);
 console.log(JSON.stringify({worker_auth_checks:9,real_RS256_validation:true,synthetic_JWK:true,live_firebase:false}));
}finally{globalThis.fetch=originalFetch;await rm(temp,{recursive:true,force:true});}
