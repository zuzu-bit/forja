// Views captured by shots.cjs (FORJA 4.4 site: one section per ability, DESIGN-4.4 §3.1). Each view starts from a fresh
// page + fresh fixture state, so views are independent.
// Fields:
//   id        file-name stem (profile-viewport-id.png); --only matches substrings of it
//   title     human label for the contact sheet
//   hash      the deep link opened BEFORE logging in (e.g. 'gasire', 'somn/s31'); proves deep links survive the login
//   auth      default true: log in through the real form (mocked Firebase). 'stored' = a saved session in localStorage
//             (forja.auth.v1) instead of the form; false = stay on the login screen
//   storage   {key: value} written to localStorage before the page loads
//   fail      path prefixes answered with HTTP 500 (error states)
//   steps     [{click}|{clickText:[selector, regex]}|{fill:[selector, text]}|{waitFor: selector}|{waitFn: () => boolean, timeout}|
//              {eval: fn}|{scroll: selector}|{wait: ms}] — run in order, each followed by a network/animation settle
//   shot      'full' (default), 'viewport', or a CSS selector (element only)
//   probe     optional () => json evaluated in the page before the shot; stored as `probe` in report.json
//   only      restrict to viewports, e.g. ['phone', 's23']
//   requires  selector or () => boolean checked after navigation; when false the view is SKIPPED for that profile
//   step.optional  skip that step silently when its target is missing
// waitFn predicates are functions: the site's CSP (script-src 'self', no unsafe-eval) blocks string predicates in-page.

const MAP = () => { const m = document.getElementById('map-host'); return !!m && (m.dataset.ready === '1' || m.dataset.failed === '1'); };
const MAP_SETTLED = () => { const m = document.getElementById('map-host'); return !!m && m.dataset.failed !== '1' && m.dataset.ready === '1' && !!document.querySelector('.maplibregl-canvas'); };
// Pe hartă, randarea WebGL software face cadrele lente: click-urile de acolo se dau direct în pagină (fără verificarea de stabilitate).
const tap = sel => ({eval: q => { const n = document.querySelector(q); if (!n) throw Error('No element ' + q); n.click(); }, arg: sel});
const MAP_PROBE = () => { const c = document.querySelector('.maplibregl-canvas'); const r = c?.getBoundingClientRect(); return {canvas: r ? [Math.round(r.width), Math.round(r.height)] : null, ready: document.getElementById('map-host')?.dataset.ready || null, failed: document.getElementById('map-host')?.dataset.failed || null, status: document.getElementById('map-status')?.textContent || ''}; };

module.exports = [
  {id: '00-login', title: 'Logare', auth: false},
  {id: '01-login-deeplink', title: 'Logare dintr-o legătură spre Găsire', auth: false, hash: 'gasire'},
  {id: '02-login-reset', title: 'Ai uitat parola?', auth: false, steps: [{fill: ['#login-email', 'lana@example.test']}, {click: '#login-forgot'}, {waitFor: '#login-message:not([hidden])'}]},
  {id: '03-login-error', title: 'Parolă greșită', auth: false, steps: [{fill: ['#login-email', 'lana@example.test']}, {fill: ['#login-password', 'gresit']}, {click: '#login-submit'}, {waitFor: '#login-message:not([hidden])'}]},
  {id: '04-restored', title: 'Sesiune salvată: deschide direct Camarazi', auth: 'stored', hash: 'camarazi'},

  {id: '10-azi', title: 'Azi', hash: 'azi'},
  {id: '11-more', title: 'Mai mult (telefon)', hash: 'azi', only: ['phone', 's23', 'chrome'], steps: [{click: '#tab-more'}, {waitFor: '#more-sheet:not([hidden])'}], shot: 'viewport'},
  {id: '12-info', title: 'Punctul „i” pe Azi', hash: 'azi', steps: [{click: '#s-azi .infodot'}, {waitFor: '#info-dialog[open]'}], shot: 'viewport'},
  {id: '13-error', title: 'Eroare de server pe Azi', hash: 'azi', fail: ['/insights/api/azi']},

  {id: '20-teren', title: 'Teren · harta', hash: 'teren', steps: [{waitFn: MAP, timeout: 30000}, {wait: 1600}], shot: 'viewport', probe: MAP_PROBE},
  {id: '21-teren-place', title: 'Teren · loc selectat (editare)', hash: 'teren', steps: [{waitFn: MAP, timeout: 30000}, tap('#teren-tab-locuri'), {waitFor: '#teren-panel .place-row, #teren-panel .panel-empty'}, {...tap('#teren-panel .place-row'), optional: true}, {wait: 1400}], shot: 'viewport'},
  {id: '22-teren-3d', title: 'Teren · 3D', hash: 'teren', steps: [{waitFn: MAP_SETTLED, timeout: 30000}, {wait: 800}, tap('#teren-3d'), {wait: 2200}], shot: 'viewport'},
  {id: '23-teren-layers', title: 'Teren · straturi', hash: 'teren', steps: [{waitFn: MAP, timeout: 30000}, tap('#teren-layers-btn'), {wait: 600}], shot: 'viewport'},
  {id: '24-teren-deeplink', title: 'Teren · legătură spre un punct', hash: 'teren/44.43550,26.10160', steps: [{waitFn: MAP, timeout: 30000}, {wait: 1600}], shot: 'viewport'},
  {id: '25-teren-camarazi', title: 'Teren · fila Camarazi', hash: 'teren', steps: [{waitFn: MAP, timeout: 30000}, tap('#teren-tab-camarazi'), {wait: 800}], shot: 'viewport'},

  {id: '30-camarazi', title: 'Camarazi', hash: 'camarazi', steps: [{wait: 400}]},

  {id: '40-gasire', title: 'Găsire', hash: 'gasire', steps: [{waitFn: () => !!document.querySelector('#gasire-panel .device, #gasire-panel .state'), timeout: 15000}, {waitFn: MAP, timeout: 30000, optional: true}, {wait: 1400}], shot: 'viewport'},
  {id: '41-gasire-tracking', title: 'Găsire · Urmărește 10 min', hash: 'gasire', requires: '#gasire-panel .device', steps: [{waitFn: MAP, timeout: 30000}, {eval: () => [...document.querySelectorAll('#gasire-panel .device-actions button')].find(b => /Urmărește/.test(b.textContent)).click()}, {waitFor: '#gasire-panel .device.tone-live'}, {wait: 1400}], shot: 'viewport'},
  {id: '42-gasire-menu', title: 'Găsire · opțiuni telefon', hash: 'gasire', requires: '#gasire-panel .device', steps: [tap('#gasire-panel .device-head .icon-btn'), {waitFor: '#sheet-dialog[open]'}], shot: 'viewport'},

  {id: '50-inventar', title: 'Inventar', hash: 'inventar', steps: [{wait: 600}]},
  {id: '51-inventar-run', title: 'Inventar · o rulare', hash: 'inventar', requires: '#inventar-runs .panel-link, #inventar-runs .run-row', steps: [{eval: () => (document.querySelector('#inventar-runs .panel-link') || document.querySelector('#inventar-runs .run-row')).click()}, {wait: 700}]},
  {id: '52-inventar-preview', title: 'Inventar · copie deschisă', hash: 'inventar', requires: () => !!document.querySelector('#inventar-vault .thumb'), steps: [{click: '#inventar-vault .thumb'}, {waitFor: '#preview-body img, #preview-body .preview-error'}, {wait: 500}], shot: 'viewport'},

  {id: '60-somn', title: 'Somn', hash: 'somn'},
  {id: '61-somn-night', title: 'Somn · noaptea ascultată', hash: 'somn', requires: '#s-somn .night-row', steps: [{click: '#s-somn .night-row'}, {waitFor: '#s-somn .timeline'}, {wait: 500}]},

  // Mirror (pachetul C, profilul „mirror”): oglinda, un album, vizualizatorul, un document, masa deschisă, antrenamentul deschis.
  {id: '53-oglinda-album', title: 'Oglinda · un album', hash: 'inventar/album%3Agallery%3ACamera', requires: () => !!document.querySelector('#inventar-mirror .mgrid, #inventar-mirror .sk'), steps: [{waitFor: '#inventar-mirror .mtile'}, {wait: 900}]},
  {id: '54-oglinda-viewer', title: 'Oglinda · pe tot ecranul', hash: 'inventar/album%3Agallery%3ACamera', requires: () => !!document.querySelector('#inventar-mirror .mgrid, #inventar-mirror .sk'), steps: [{waitFor: '#inventar-mirror .mtile'}, {click: '#inventar-mirror .mtile'}, {waitFor: '#mirror-viewer[open] img:not([style])'}, {wait: 500}], shot: 'viewport'},
  {id: '55-oglinda-docs', title: 'Oglinda · documente', hash: 'inventar', requires: '#inventar-mirror .seg', steps: [{clickText: ['#inventar-mirror .seg-btn', /Documente/]}, {waitFor: '#inventar-mirror .mtile.doc'}, {wait: 500}]},
  {id: '70-ratie', title: 'Rație', hash: 'ratie'},
  {id: '71-ratie-masa', title: 'Rație · masa deschisă', hash: 'ratie', requires: '#body-ratie .meal.open-able', steps: [{click: '#body-ratie .meal.open-able'}, {waitFor: '#body-ratie .meal-photo img'}, {wait: 400}]},
  {id: '81-mars-antrenament', title: 'Marș · antrenamentul deschis', hash: 'mars', requires: '#body-mars .work.open-able', steps: [{click: '#body-mars .work.open-able'}, {wait: 400}]},
  {id: '80-mars', title: 'Marș', hash: 'mars'},
  {id: '90-muzica', title: 'Muzică', hash: 'muzica'},
  {id: '91-paza', title: 'Pază', hash: 'paza'},
  {id: '91b-paza-14', title: 'Pază · 14 zile', hash: 'paza', requires: '#s-paza .seg-btn', steps: [{eval: () => [...document.querySelectorAll('#s-paza .seg-btn')].find(b => /14/.test(b.textContent)).click()}, {wait: 700}]},
  {id: '92-concentrare', title: 'Concentrare', hash: 'concentrare'},
  {id: '95-cont', title: 'Cont', hash: 'cont', steps: [{eval: () => { const d = document.querySelector('#s-cont details.privacy'); if (d) d.open = true; }}]}
];
