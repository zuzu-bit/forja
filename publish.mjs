import {spawn} from 'node:child_process';
import {existsSync,readFileSync} from 'node:fs';
import {dirname,join,resolve} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';

const root=dirname(fileURLToPath(import.meta.url));
const project=join(root,'server');
const config='wrangler.insights.toml';
const site='https://forja-insights.forja-22e7ea2d.workers.dev';
const wrangler=join(project,'node_modules/wrangler/bin/wrangler.js');
function execute(kind,args){
 const entry=kind==='npm'?join(dirname(process.execPath),'node_modules/npm/bin/npm-cli.js'):wrangler;
 if(!existsSync(entry))throw Error('Instrumentele nu sunt complete. Porneste din nou PUBLICA_FORJA.cmd.');
 return new Promise((resolve,reject)=>{
  const child=spawn(process.execPath,[entry,...args],{cwd:project,stdio:'inherit',env:{...process.env,WRANGLER_SEND_METRICS:'false'}});
  child.on('error',reject);child.on('exit',code=>code===0?resolve():reject(Error(`Pasul ${kind} ${args[0]} nu a reusit (cod ${code}). Publicarea s-a oprit.`)));
 });
}
async function readHealth(){const response=await fetch(site+'/health',{cache:'no-store',signal:AbortSignal.timeout(20000)});if(!response.ok)throw Error('Site-ul nu raspunde: HTTP '+response.status);return response.json();}
export async function publish({exec=execute,health=readHealth,log=console.log,delay=ms=>new Promise(r=>setTimeout(r,ms))}={}){
 let before;try{before=await health();}catch{log('Nu pot verifica acum versiunea online. Voi verifica si dupa publicare.');}
 if(before?.service==='forja-insights'&&before.files_sync===1&&before.cleanup_schedule===1&&before.version===13&&before.sleep_audio===1&&before.visual_ui===1&&before.background_audio===1&&before.organizer===1&&before.social===1&&before.partners===1&&before.contacts===1&&before.lost_phone===1&&before.organizer_modes===1){log('GATA! Site-ul are deja aceasta versiune. Foloseste APK-ul v25 pentru Somn si sincronizare.');return 'already-current';}
 if(before?.version>13)throw Error('Site-ul are o versiune mai noua. Nu public acest pachet peste ea.');
 log('\n1/5 Pregatesc dependentele si verific actualizarea.');
 await exec('npm',['ci','--no-audit','--no-fund']);await exec('npm',['test']);
 await exec('wrangler',['deploy','--dry-run','--config',config]);
 log('\n2/5 Conecteaza-te in browser la contul Cloudflare unde exista site-ul FORJA. Aproba aplicatia Wrangler.');
 await exec('wrangler',['login','--scopes','account:read','user:read','workers_scripts:write']);
 log('\n3/5 Verific accesul la Worker-ul existent. Daca sunt mai multe conturi, alege contul FORJA.');
 await exec('wrangler',['deployments','list','--config',config]);
 log('\n4/5 Public interfata simplificata cu toate functiile existente.');
 await exec('wrangler',['deploy','--config',config,'--experimental-provision=false']);
 log('\n5/5 Verific daca actualizarea raspunde pe adresa site-ului.');
 for(let i=0;i<6;i++){
  try{const result=await health();if(result.service==='forja-insights'&&result.version===13&&result.sleep_audio===1&&result.visual_ui===1&&result.organizer===1&&result.social===1&&result.partners===1&&result.contacts===1&&result.lost_phone===1&&result.organizer_modes===1&&result.background_audio===1&&result.files_sync===1&&result.cleanup_schedule===1){log('\nGATA! Site-ul FORJA a fost actualizat. Instaleaza APK-ul v25 si intra cu acelasi cont FORJA.');return 'published';}}catch{}
  if(i<5)await delay(2000);
 }
 throw Error('Cloudflare a terminat publicarea, dar nu pot confirma functia la adresa FORJA. Nu republica automat; verifica mesajele din fereastra.');
}
function validatePackage(){
 for(const name of ['package.json','package-lock.json',config,'insights-worker.mjs','files-vault.mjs','files-client.js.txt','cleanup-schedule.mjs','cleanup-client.js.txt','organizer.mjs','organizer-selection.mjs','organizer-ai.mjs','organizer-client.js.txt','social.mjs','social-contacts.mjs','lost-phone.mjs','recovery-client.js.txt','recovery.test.mjs','social-client.js.txt','social.test.mjs','sleep-api.mjs','sleep-store.mjs','sleep-analysis.mjs','sleep-acoustic.mjs','sleep-client.js.txt','sleep.test.mjs','sleep-analysis.test.mjs','vendor/leaflet-1.9.4.js.txt','vendor/leaflet-1.9.4.css.txt'])if(!existsSync(join(project,name)))throw Error('Fisier lipsa: '+name+'. Extrage TOT continutul arhivei, apoi deschide PUBLICA_FORJA.cmd.');
 const text=readFileSync(join(project,config),'utf8');
 if(!/^name\s*=\s*"forja-insights"\s*$/m.test(text)||!/^bucket_name\s*=\s*"forja-insights-data"\s*$/m.test(text))throw Error('Configuratia nu este cea a site-ului FORJA. Publicarea s-a oprit.');
 if(Number(process.versions.node.split('.')[0])<22)throw Error('Este necesar Node.js 22 sau mai nou. Porneste PUBLICA_FORJA.cmd.');
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
 try{
  validatePackage();
  if(process.argv.includes('--check-only'))console.log('Pachetul si destinatia FORJA sunt valide. Nu am publicat nimic.');
  else{console.log('FORJA v25 - Somn, sincronizare si prezentare vizuala\nDestinatie: '+site+'\nAcest program publica actualizarea in contul tau, dupa conectarea in browser.');await publish();
   if(process.platform==='win32')spawn('rundll32.exe',['url.dll,FileProtocolHandler',site],{stdio:'ignore',detached:true}).on('error',()=>console.log('Deschide manual '+site)).unref();
  }
 }catch(error){console.error('\nOPRIT: '+error.message+'\nTrimite o captura a mesajului de eroare, fara parole sau coduri de acces.');process.exitCode=1;}
}
