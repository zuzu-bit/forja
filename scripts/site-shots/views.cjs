// Views captured by shots.cjs. Each view starts from a fresh page + fresh fixture state, so views are independent.
// Fields:
//   id        file-name stem (profile-viewport-id.png); --only matches substrings of it
//   title     human label for the contact sheet
//   auth      default true: log in through the real login form (mocked Firebase) before the steps
//   page      nav target (clicks .nav[data-page=…], opening "Mai multe" first when the item lives there)
//   url       path to load instead of /insights (e.g. /insights/map-frame); hash is appended as given
//   host      emulate the Android WebView bridge (window.ForjaMapHost) — map-frame only
//   steps     [{click}|{clickText:[selector, regex]}|{open: selector}|{check: selector}|{fill:[selector, text]}|
//              {waitFor: selector}|{waitFn: () => boolean, timeout}|{eval: 'js expression'}|{mapFrameData:true}|
//              {scroll: selector}|{wait: ms}]  — run in order, followed by a network/animation settle
//   shot      'full' (default, whole page), 'viewport' (dialogs, menus, maps) or a CSS selector (element only)
//   probe     optional () => json evaluated in the page before the shot; stored as `probe` in report.json
//   only      restrict to viewports, e.g. ['phone']
//   requires  selector or () => boolean checked after navigation; when false the view is SKIPPED for that profile
//             (e.g. no chat without friends) and listed as skipped in report.json — the page shot shows the empty state
//   step.optional  skip that step silently when its target is missing
// Selectors come from server/insights.html and the *-client.js.txt renderers; a redesign updates them here.

// waitFn predicates are functions: the site's CSP (script-src 'self', no unsafe-eval) blocks string predicates in-page.
const MAP_READY = () => { const s = document.getElementById('social-map'); return !!s && s.querySelectorAll('.leaflet-tile-loaded').length > 3; };
const VECTOR_DONE = () => { const v = document.getElementById('social-vector'), s = document.getElementById('map-render-status'); return (!v.hidden && v.style.opacity === '1') || /nu este disponibil/.test(s.textContent); };

module.exports = [
  {id: '00-login', title: 'Login', auth: false},
  {id: '01-privacy', title: 'Confidențialitate și ajutor (public)', auth: false, url: '/insights#privacy'},

  {id: '10-home', title: 'Acasă', page: 'overview'},
  {id: '11-menu-more', title: 'Meniul „Mai multe” (telefon)', page: 'overview', only: ['phone'], steps: [{eval: "document.getElementById('nav-more').open = true"}], shot: 'viewport'},

  {id: '20-map', title: 'Hartă · prima vedere', page: 'social', steps: [{waitFn: MAP_READY, timeout: 15000}, {wait: 600}]},
  {id: '21-map-viewport', title: 'Hartă · ecranul vizibil', page: 'social', steps: [{waitFn: MAP_READY, timeout: 15000}, {wait: 600}], shot: 'viewport'},
  {id: '22-map-3d', title: 'Hartă · după apăsarea 3D', page: 'social', steps: [{waitFn: MAP_READY, timeout: 15000}, {click: '#map-dimension'}, {waitFn: VECTOR_DONE, timeout: 25000}, {wait: 2500}], shot: 'viewport'},
  {id: '23-map-plans', title: 'Hartă · tab Planuri', page: 'social', steps: [{click: '#social-plans-tab'}]},
  {id: '24-map-places', title: 'Hartă · tab Locuri', page: 'social', steps: [{click: '#social-places-tab'}]},
  {id: '25-map-chat', title: 'Hartă · conversație', page: 'social', requires: () => [...document.querySelectorAll('#social-friends button')].some(b => b.textContent === 'Mesaj'), steps: [{clickText: ['#social-friends button', '^Mesaj$']}, {wait: 500}]},
  {id: '26-map-visibility', title: 'Hartă · dialog „Cine mă vede”', page: 'social', steps: [{click: '#social-visibility'}, {waitFor: '#visibility-dialog[open]'}], shot: 'viewport'},
  {id: '27-map-lost-phone', title: 'Hartă · Găsește telefonul', page: 'social', steps: [{open: 'details:has(#recovery-devices)'}, {scroll: '#recovery-devices'}, {wait: 300}], shot: 'viewport'},
  {id: '28-map-place-selected', title: 'Hartă · loc din aplicație selectat', page: 'social', requires: '#journey-places button', steps: [{clickText: ['#journey-places button', 'Vezi']}, {wait: 800}], shot: 'viewport'},

  {id: '30-files', title: 'Fișiere', page: 'files'},
  {id: '31-files-job-result', title: 'Fișiere · rezultatul unei lucrări', page: 'files', requires: '.organizer-job-detail', steps: [{open: '.organizer-job-detail'}, {wait: 600}]},
  {id: '32-files-settings', title: 'Fișiere · curățenie automată + telefoane', page: 'files', steps: [{open: 'details:has(#cleanup-devices)'}, {open: 'details:has(#vault-devices)'}, {scroll: '#cleanup-devices'}]},
  {id: '33-files-preview', title: 'Fișiere · previzualizare foto', page: 'files', requires: () => [...document.querySelectorAll('#vault-grid .vault-card')].some(c => c.querySelector('img') && /Previzualizează/.test(c.textContent)), steps: [{clickText: ['#vault-grid button', '^Previzualizează$']}, {waitFor: '#vault-preview img'}, {wait: 400}], shot: 'viewport'},
  {id: '34-files-preview-pdf', title: 'Fișiere · previzualizare PDF', page: 'files', requires: () => [...document.querySelectorAll('#vault-grid .vault-card')].some(c => /\.pdf/.test(c.textContent)), steps: [{eval: "[...document.querySelectorAll('#vault-grid .vault-card')].find(c => /\\.pdf/.test(c.textContent))?.querySelector('button')?.click()"}, {waitFor: '#vault-preview canvas'}, {wait: 1500}], shot: 'viewport'},

  {id: '40-ideas', title: 'Pentru tine', page: 'ai'},
  {id: '41-ideas-generated', title: 'Pentru tine · idei generate', page: 'ai', steps: [{check: '#ai-consent'}, {click: '#recommend'}, {waitFor: '#recommendations .recommendation, #recommendations .empty'}]},

  {id: '50-journals', title: 'Jurnale', page: 'data'},
  {id: '51-journals-audio', title: 'Jurnale · Audio', page: 'data', steps: [{click: '[data-tab="audio"]'}]},
  {id: '52-journals-sessions', title: 'Jurnale · Sesiuni (prima deschisă)', page: 'data', requires: '#sessions details[data-session]', steps: [{click: '[data-tab="sessions"]'}, {open: '#sessions details[data-session]'}, {wait: 700}]},

  {id: '60-sleep-audio', title: 'Somn și audio', page: 'control', steps: [{open: '#sleep-reports details', optional: true}, {wait: 700}]},
  {id: '70-campaigns', title: 'Campanii', page: 'content'},

  {id: '80-map-frame', title: '/insights/map-frame (WebView vechi) · 2D', auth: false, url: '/insights/map-frame', host: true, steps: [{mapFrameData: true}, {wait: 2500}], shot: 'viewport'},
  {id: '81-map-frame-3d', title: '/insights/map-frame · 3D', auth: false, url: '/insights/map-frame', host: true, steps: [{mapFrameData: true}, {wait: 1200}, {eval: 'window.ForjaMap.set3D(true)'}, {waitFn: () => window.ForjaMap.mode3d || !document.getElementById('map-status').hidden, timeout: 25000}, {wait: 2500}], shot: 'viewport'}
];
