// Synthetic responses for the jsdom UX tests of THE SITE (FORJA 4.4). Nothing here is deployed by the worker.
// The data and the endpoint mock are the same as the screenshot harness (scripts/site-shots/fixture.cjs + mock-api.cjs),
// so the tests and the screenshots exercise exactly the DESIGN-4.4 §3.2 / §3.4 contract.
const fs = require('node:fs');
const path = require('node:path');
const {buildFixture, NOW} = require('./site-shots/fixture.cjs');
const {createApi} = require('./site-shots/mock-api.cjs');
const server = path.resolve(__dirname, '../server');
/** Same list and order as server/site-static.mjs CLIENT_FILES and server/verify-live.mjs (ux-ui-test checks it). */
const clients = ['site-core.js.txt', 'site-mascot.js.txt', 'files-preview.js.txt', 'site-azi.js.txt', 'site-teren.js.txt', 'site-camarazi.js.txt', 'site-gasire.js.txt', 'site-inventar.js.txt', 'site-somn.js.txt', 'site-ratie.js.txt', 'site-mars.js.txt', 'site-muzica.js.txt', 'site-paza.js.txt', 'site-concentrare.js.txt', 'site-ecran.js.txt', 'site-cont.js.txt', 'site-boot.js.txt'];
const assets = {scene: () => Buffer.from('\x89PNG\r\n\x1a\nfixture'), pdf: () => Buffer.from('%PDF-1.4 fixture'), audio: () => Buffer.from('fixture-audio')};

function createFixture(profile = 'rich', now = NOW, options = {}) {
  const data = buildFixture(profile, now);
  const api = createApi(data, assets, options);
  return {data, api, calls: [], fail: options.fail || []};
}

/** window.fetch answered by the harness mock (Firebase + every site endpoint); unknown routes throw. */
function installMockFetch(win, fixture) {
  win.fetch = async (url, options = {}) => {
    const address = new win.URL(String(url), win.location.href);
    const method = (options.method || 'GET').toUpperCase();
    const body = typeof options.body === 'string' ? Buffer.from(options.body) : options.body instanceof win.URLSearchParams ? Buffer.from(options.body.toString()) : null;
    fixture.calls.push({url: address.href, route: address.pathname, search: address.search, method, body: body ? body.toString() : '', headers: options.headers || {}});
    const r = await fixture.api.handle({method, url: address.href, body});
    if (fixture.api.unmocked.length) throw Error('No local fixture for ' + fixture.api.unmocked.join(', '));
    return new win.Response(r.body, {status: r.status, headers: r.headers});
  };
}

function clientSource() { return clients.map(name => fs.readFileSync(path.join(server, name), 'utf8')).join('\n'); }
module.exports = {server, clients, createFixture, installMockFetch, clientSource, NOW};
