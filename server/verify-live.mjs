import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

// Verificarea publicării site-ului FORJA 4.4 (forja-insights, /health version 18, DESIGN-4.4 §3.5).
const site = 'https://forja-insights.forja-22e7ea2d.workers.dev';
const flags = { organizer_jobs: 4, journey: 1, explore_sync: 2, map3d: 1, content_ai: 2, sleep_audio: 1, visual_ui: 1, files_sync: 1, cleanup_schedule: 1, background_audio: 1, organizer: 1, organizer_modes: 1, social: 1, partners: 1, contacts: 2, lost_phone: 2, site_sections: 1, inventory_runs: 1, music_summary: 1 };
const sha = value => createHash('sha256').update(value).digest('hex');
async function get(path) {
  return fetch(site + path, { cache: 'no-store', signal: AbortSignal.timeout(20000) });
}
async function health() {
  const r = await get('/health');
  if (!r.ok) throw Error('Site indisponibil: HTTP ' + r.status);
  const h = await r.json();
  if (h.service !== 'forja-insights' || !Number.isInteger(h.version)) throw Error('Adresa nu răspunde ca site FORJA.');
  if (h.version > 18) throw Error('Site-ul este mai nou decât acest pachet. Publicarea a fost oprită.');
  return h;
}
if (process.argv.includes('--before')) {
  const h = await health();
  console.log(JSON.stringify({ stage: 'before', site, version: h.version }));
} else {
  const html = await readFile(new URL('./insights.html', import.meta.url), 'utf8');
  // Aceeași listă și ordine ca CLIENT_FILES din site-static.mjs și clients din scripts/ux-fixture.cjs.
  const scripts = ['site-core.js.txt', 'site-mascot.js.txt', 'files-preview.js.txt', 'site-azi.js.txt', 'site-teren.js.txt', 'site-camarazi.js.txt', 'site-gasire.js.txt', 'site-inventar.js.txt', 'site-somn.js.txt', 'site-ratie.js.txt', 'site-mars.js.txt', 'site-muzica.js.txt', 'site-paza.js.txt', 'site-concentrare.js.txt', 'site-cont.js.txt', 'site-boot.js.txt'];
  const js = (await Promise.all(scripts.map(p => readFile(new URL(p, import.meta.url), 'utf8')))).join('\n');
  const assets = [['/insights/map-renderer.js', 'map-renderer.js.txt'], ['/insights/maplibre.js', 'vendor/maplibre-5.10.0.js.txt'], ['/insights/maplibre.css', 'vendor/maplibre-5.10.0.css.txt']];
  const fonts = ['barlowc-500', 'barlowc-600', 'barlowc-700', 'hanken-var', 'jbmono-var'];
  let passed = false;
  for (let attempt = 0; attempt < 6; attempt++) {
    try {
      const h = await health();
      if (h.version !== 18 || Object.entries(flags).some(([k, v]) => h[k] !== v)) throw Error('Versiunea sau funcțiile online nu corespund actualizării.');
      const page = await get('/insights');
      const client = await get('/insights/app.js');
      if (!page.ok || !client.ok || sha(await page.text()) !== sha(html) || sha(await client.text()) !== sha(js)) throw Error('Pagina sau interfața online diferă de fișierele verificate.');
      const csp = page.headers.get('content-security-policy') || '';
      if (!/font-src 'self'/.test(csp) || /openstreetmap|unsafe-eval/.test(csp)) throw Error('Politica de securitate a paginii nu este cea așteptată.');
      for (const [path, file] of assets) {
        const asset = await get(path), expected = await readFile(new URL(file, import.meta.url), 'utf8');
        if (!asset.ok || sha(await asset.text()) !== sha(expected)) throw Error('Resursa de hartă online diferă: ' + path);
      }
      for (const name of fonts) {
        const font = await get('/insights/fonts/' + name + '.woff2'), expected = await readFile(new URL('fonts/' + name + '.woff2', import.meta.url));
        if (!font.ok || font.headers.get('content-type') !== 'font/woff2' || sha(Buffer.from(await font.arrayBuffer())) !== sha(expected)) throw Error('Fontul online diferă: ' + name);
      }
      for (const gone of ['/insights/map-frame', '/insights/leaflet.js']) { const r = await get(gone); if (r.ok) throw Error('Ruta retrasă răspunde încă: ' + gone); }
      const privateRoute = await get('/v2/recovery/devices');
      if (privateRoute.status !== 401) throw Error('Ruta privată nu refuză accesul neautentificat.');
      const siteRoute = await get('/insights/api/azi');
      if (siteRoute.status !== 401) throw Error('Secțiunile site-ului nu cer autentificare.');
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
