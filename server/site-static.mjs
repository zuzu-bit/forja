// Partea statică a site-ului FORJA (forja-insights): pagina, scriptul concatenat, harta, fonturile, pdf.js.
// Rutele de date (/insights/api/*, /v2/*) rămân în insights-worker.mjs. Lista și ordinea fișierelor client de mai jos
// sunt aceleași în server/verify-live.mjs și scripts/ux-fixture.cjs (testul UI verifică potrivirea).
import html from './insights.html';
import core from './site-core.js.txt';
import mascot from './site-mascot.js.txt';
import filePreview from './files-preview.js.txt';
import azi from './site-azi.js.txt';
import teren from './site-teren.js.txt';
import camarazi from './site-camarazi.js.txt';
import gasire from './site-gasire.js.txt';
import inventar from './site-inventar.js.txt';
import somn from './site-somn.js.txt';
import ratie from './site-ratie.js.txt';
import mars from './site-mars.js.txt';
import muzica from './site-muzica.js.txt';
import paza from './site-paza.js.txt';
import concentrare from './site-concentrare.js.txt';
import cont from './site-cont.js.txt';
import boot from './site-boot.js.txt';
import mapRenderer from './map-renderer.js.txt';
import maplibre from './vendor/maplibre-5.10.0.js.txt';
import maplibreCSS from './vendor/maplibre-5.10.0.css.txt';
import pdfClient from './vendor/pdfjs-5.6.205.mjs.txt';
import pdfWorker from './vendor/pdfjs-worker-5.6.205.mjs.txt';
import barlow500 from './fonts/barlowc-500.woff2';
import barlow600 from './fonts/barlowc-600.woff2';
import barlow700 from './fonts/barlowc-700.woff2';
import hanken from './fonts/hanken-var.woff2';
import jbmono from './fonts/jbmono-var.woff2';

/** Ordinea contează: un singur modul ES, un singur domeniu de nume (site-core întâi, site-boot la final). */
export const CLIENT_FILES = ['site-core.js.txt', 'site-mascot.js.txt', 'files-preview.js.txt', 'site-azi.js.txt', 'site-teren.js.txt', 'site-camarazi.js.txt', 'site-gasire.js.txt', 'site-inventar.js.txt', 'site-somn.js.txt', 'site-ratie.js.txt', 'site-mars.js.txt', 'site-muzica.js.txt', 'site-paza.js.txt', 'site-concentrare.js.txt', 'site-cont.js.txt', 'site-boot.js.txt'];
const appJs = [core, mascot, filePreview, azi, teren, camarazi, gasire, inventar, somn, ratie, mars, muzica, paza, concentrare, cont, boot].join('\n');

/** Doar gazdele de care are nevoie pagina: Firebase (logare), OpenFreeMap (harta). Fonturile sunt servite de aici. */
export const PAGE_CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://tiles.openfreemap.org; img-src 'self' blob: data: https://tiles.openfreemap.org; media-src blob:; worker-src 'self' blob:; font-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'";

const FONTS = { 'barlowc-500': barlow500, 'barlowc-600': barlow600, 'barlowc-700': barlow700, 'hanken-var': hanken, 'jbmono-var': jbmono };
const text = (body, type, cache) => new Response(body, { headers: { 'content-type': type + '; charset=utf-8', 'cache-control': cache, 'x-content-type-options': 'nosniff' } });

/** Răspunsul static pentru `path`, sau null când ruta nu e statică. */
export function siteStatic(request, path) {
  if (request.method !== 'GET') return null;
  if (['/', '/admin', '/insights', '/insights/'].includes(path)) {
    return new Response(html, { headers: {
      'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'content-security-policy': PAGE_CSP,
      'referrer-policy': 'strict-origin-when-cross-origin', 'x-content-type-options': 'nosniff'
    } });
  }
  if (path === '/insights/app.js') return text(appJs, 'text/javascript', 'no-cache');
  if (path === '/insights/map-renderer.js') return text(mapRenderer, 'text/javascript', 'no-cache');
  if (path === '/insights/maplibre.js') return text(maplibre, 'text/javascript', 'public, max-age=86400');
  if (path === '/insights/maplibre.css') return text(maplibreCSS, 'text/css', 'public, max-age=86400');
  if (path === '/insights/pdf.mjs') return text(pdfClient, 'text/javascript', 'public, max-age=3600');
  if (path === '/insights/pdf.worker.mjs') return text(pdfWorker, 'text/javascript', 'public, max-age=3600');
  const font = /^\/insights\/fonts\/([a-z0-9-]+)\.woff2$/.exec(path);
  if (font && FONTS[font[1]]) return new Response(FONTS[font[1]], { headers: { 'content-type': 'font/woff2', 'cache-control': 'public, max-age=604800', 'x-content-type-options': 'nosniff' } });
  return null;
}
