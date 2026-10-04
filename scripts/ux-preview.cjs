// Creates a standalone local review page. Production code/auth is left untouched.
// Usage: node scripts/ux-preview.cjs [output.html]
const fs = require('node:fs');
const path = require('node:path');
const {server, createFixture, installMockFetch, clientSource} = require('./ux-fixture.cjs');
const script = value => '<script>' + value.replace(/<\/script/gi, '<\\/script') + '</script>';
function buildPreview({fixture = createFixture(), defaultPage = 'overview'} = {}) {
let html = fs.readFileSync(path.join(server, 'insights.html'), 'utf8')
  .replace(/<script\b[^>]*src=["'][^"']+["'][^>]*><\/script>/g, '')
  .replace(/<link\b[^>]*href=["']\/insights\/(?:leaflet|maplibre).css["'][^>]*>/g, '')
  .replace('<title>', '<title>DEMO local · ');
const leafletCSS = fs.readFileSync(path.join(server, 'vendor/leaflet-1.9.4.css.txt'), 'utf8');
const leafletJS = fs.readFileSync(path.join(server, 'vendor/leaflet-1.9.4.js.txt'), 'utf8');
// Illustrative backdrop, not a geographic source. The production map still uses OSM.
const backdrop = `<svg xmlns="http://www.w3.org/2000/svg" width="860" height="720" viewBox="0 0 860 720"><rect width="860" height="720" fill="#e3e6d8"/><g fill="#d0d5c6" stroke="#f5f5ee" stroke-width="7"><path d="M-40 20h220v125H-40zM205 10h200v135H205zM430 12h170v133H430zM625 10h250v135H625zM-40 170h130v150H-40zM120 170h280v100H120zM590 170h280v165H590zM-20 350h125v150H-20zM630 360h250v110H630zM-20 535h260v160H-20zM275 540h180v170H275zM500 525h170v180H500zM705 510h160v220H705z"/></g><path d="M165 314C238 241 367 273 440 210S590 173 603 247L598 485C459 500 372 572 248 491S126 409 165 314" fill="#bdceab" stroke="#a9bf94" stroke-width="3"/><g fill="none" stroke="#eceddc" stroke-width="7"><path d="M170 352Q325 361 485 233M202 461Q380 400 571 285M330 286Q420 382 503 501M196 390Q340 504 563 427"/></g><path d="M430 330Q493 302 533 345T514 419Q474 454 438 410T430 330" fill="#aac8c8" stroke="#91b5b5" stroke-width="3"/><g fill="none" stroke="#f9f9f4" stroke-width="20"><path d="M-20 150H900M108 -20V740M612 -20V740M-20 518H900"/><path d="M-40 670L880 80" stroke-width="15"/></g><g fill="none" stroke="#ced2c7" stroke-width="2"><path d="M-20 150H900M108 -20V740M612 -20V740M-20 518H900"/></g><g fill="#a9be98"><circle cx="255" cy="327" r="11"/><circle cx="280" cy="312" r="13"/><circle cx="334" cy="355" r="11"/><circle cx="395" cy="446" r="11"/><circle cx="551" cy="465" r="13"/><circle cx="228" cy="427" r="11"/></g><g font-family="system-ui,sans-serif" text-anchor="middle"><text x="365" y="393" font-size="17" font-weight="600" fill="#58704d">Parc</text><text x="489" y="382" font-size="12" fill="#56767a">Lac</text><text x="732" y="158" font-size="11" fill="#7f877a">Bulevard</text><text x="721" y="653" font-size="10" fill="#6d7665">HARTĂ ILUSTRATIVĂ · DEMO</text></g></svg>`;
html = html.replace('</head>', `<style>${leafletCSS}\n#social-map{background:#e3e6d8 url("data:image/svg+xml,${encodeURIComponent(backdrop)}") center/cover no-repeat!important}.ux-demo-badge{position:fixed;right:12px;bottom:10px;z-index:10000;border:1px solid #a5b499;background:#eef3e5;color:#2a432d;border-radius:12px;padding:7px 11px;font:11px/1.3 system-ui;box-shadow:0 4px 18px #0002;pointer-events:none}.ux-demo-badge b{display:block}</style></head>`);
const setup = `
window.__fixture=${JSON.stringify(fixture)};
(${installMockFetch.toString()})(window, window.__fixture);
// Local review never asks for live device location.
Object.defineProperty(navigator,'geolocation',{configurable:true,value:{watchPosition(callback){setTimeout(()=>callback({timestamp:Date.now(),coords:{latitude:44.4132,longitude:26.0938,accuracy:12,speed:0}}),0);return 1;},clearWatch(){}}});
// Use Leaflet's real interaction engine, with an offline, illustrative backdrop.
L.tileLayer=()=>({addTo(){return this;}});
`;
const boot = `
const reviewParams=new URLSearchParams(location.search);
if(reviewParams.get('view')!=='login'){
  document.getElementById('email').value='alex@example.test';
  document.getElementById('password').value='local-demo-only';
  document.getElementById('login-form').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));
  const reviewTimer=setInterval(()=>{
    if(document.getElementById('app').hidden)return;
    clearInterval(reviewTimer);
    const name=reviewParams.get('page')||${JSON.stringify(defaultPage)};
    document.querySelector('[data-page="'+name.replace(/[^a-z]/g,'')+'"]')?.click();
  },30);
  setTimeout(()=>clearInterval(reviewTimer),3000);
}
`;
html = html.replace('</body>', '<div class="ux-demo-badge"><b>DEMO · date sintetice</b>Previzualizare locală</div>' + script(leafletJS) + script(setup) + script(clientSource()) + script(boot) + '</body>');
return html;
}
if (require.main === module) {
  const output = path.resolve(process.argv[2] || path.join(__dirname, '../../forja-ux-preview.html'));
  fs.mkdirSync(path.dirname(output), {recursive: true});
  fs.writeFileSync(output, buildPreview());
  console.log(output);
}
module.exports = {buildPreview};
