import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const site = 'https://forja-insights.forja-22e7ea2d.workers.dev';
const flags = ['journey','map3d','content_ai','sleep_audio', 'visual_ui', 'files_sync', 'cleanup_schedule', 'background_audio', 'organizer', 'organizer_modes', 'social', 'partners', 'contacts', 'lost_phone'];
const sha = value => createHash('sha256').update(value).digest('hex');
async function get(path) {
  return fetch(site + path, { cache: 'no-store', signal: AbortSignal.timeout(20000) });
}
async function health() {
  const r = await get('/health');
  if (!r.ok) throw Error('Site indisponibil: HTTP ' + r.status);
  const h = await r.json();
  if (h.service !== 'forja-insights' || !Number.isInteger(h.version)) throw Error('Adresa nu răspunde ca site FORJA.');
  if (h.version > 16) throw Error('Site-ul este mai nou decât acest pachet. Publicarea a fost oprită.');
  return h;
}
if (process.argv.includes('--before')) {
  const h = await health();
  console.log(JSON.stringify({ stage: 'before', site, version: h.version }));
} else {
  const html = await readFile(new URL('./insights.html', import.meta.url), 'utf8');
  const scripts = ['insights-client.js.txt', 'sleep-client.js.txt', 'files-preview.js.txt', 'files-client.js.txt', 'cleanup-client.js.txt', 'organizer-client.js.txt', 'social-client.js.txt', 'recovery-client.js.txt','journey-client.js.txt'];
  const js = (await Promise.all(scripts.map(p => readFile(new URL(p, import.meta.url), 'utf8')))).join('\n');
  let passed = false;
  for (let attempt = 0; attempt < 6; attempt++) {
    try {
      const h = await health();
      if (h.version !== 16 || h.organizer_jobs!==4 || flags.some(k => h[k] !== 1)) throw Error('Versiunea sau funcțiile online nu corespund actualizării.');
      const page = await get('/');
      const client = await get('/insights/app.js');
      if (!page.ok || !client.ok || sha(await page.text()) !== sha(html) || sha(await client.text()) !== sha(js)) throw Error('Pagina sau interfața online diferă de fișierele verificate.');
      for(const [path,file] of [['/insights/map-renderer.js','map-renderer.js.txt'],['/insights/map-frame','map-frame.html'],['/insights/map-frame.js','map-frame-client.js.txt'],['/insights/maplibre.js','vendor/maplibre-5.10.0.js.txt'],['/insights/maplibre.css','vendor/maplibre-5.10.0.css.txt']]){
        const asset=await get(path),expected=await readFile(new URL(file,import.meta.url),'utf8');if(!asset.ok||sha(await asset.text())!==sha(expected))throw Error('Resursa de hartă online diferă: '+path);
      }
      const privateRoute = await get('/v2/recovery/devices');
      if (privateRoute.status !== 401) throw Error('Ruta privată nu refuză accesul neautentificat.');
      console.log(JSON.stringify({ stage: 'after', site, version: h.version, features: flags, html_sha256: sha(html), client_sha256: sha(js), unauthenticated_status: privateRoute.status, owner_login_tested: false, phone_tested: false }));
      passed = true;
      break;
    } catch (error) {
      if (attempt === 5) throw error;
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }
  if (!passed) process.exitCode = 1;
}
