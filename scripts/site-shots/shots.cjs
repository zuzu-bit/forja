#!/usr/bin/env node
// LOCAL SITE PREVIEW HARNESS for THE SITE (forja-insights). Investigation / design-review tool, not part of CI.
//
// What it does
//  1. Imports server/insights-worker.mjs itself (text-loader.mjs = wrangler's Text-module rule) and serves every
//     static route from a local HTTP server with the worker's own responses: the HTML + CSP headers, /insights/app.js
//     concatenated from the same *-client.js.txt files in the same order, map-frame, MapLibre/Leaflet/pdf.js.
//  2. Drives Chromium (Playwright) and mocks the network with page.route: Firebase identitytoolkit/securetoken
//     (fake idToken), every /insights/api/* and /v2/* endpoint (mock-api.cjs + fixture.cjs), OSM raster tiles and
//     OpenFreeMap style/tiles/glyphs (assets.cjs placeholders). Everything else is blocked and reported.
//  3. Logs in through the real login form and captures every view in views.cjs at desktop 1440×900 and phone 390×844,
//     writing PNGs, report.json (errors, unmocked calls, density metrics, horizontal overflow) and index.html.
//
// Usage
//   node scripts/site-shots/shots.cjs --out=/tmp/shots [--only=map,files] [--profile=rich,lana,empty]
//        [--viewports=desktop,phone] [--jobs=2] [--scale=1] [--clock=fixed|real] [--raw-fonts] [--list]
//   --only        comma-separated substrings of view ids (see --list)
//   --views       alternative views module (same format as views.cjs), e.g. for a redesign branch or ad-hoc probes
//   --profile     fixture profiles (fixture.cjs); default rich
//   --clock       fixed (default): the page clock starts at fixture NOW (2026-09-28 19:40 Bucharest) and flows;
//                 real: fixture is built around the real current time
//   --raw-fonts   keep the container's fontconfig (DejaVu Sans, much wider than Segoe UI / Roboto). By default the
//                 run aliases Inter / system-ui / sans-serif to Liberation Sans (Arial metrics) for realistic widths.
// Requirements: Playwright at /opt/node22/lib/node_modules/playwright (or FORJA_PLAYWRIGHT), browsers in /opt/pw-browsers.
'use strict';
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const {register} = require('node:module');
const {pathToFileURL} = require('node:url');
const {chromium} = require(process.env.FORJA_PLAYWRIGHT || '/opt/node22/lib/node_modules/playwright');
const {buildFixture, NOW} = require('./fixture.cjs');
const {createApi} = require('./mock-api.cjs');
const {createAssets} = require('./assets.cjs');
let VIEWS = require('./views.cjs');

const SERVER = path.resolve(__dirname, '../../server');
const VIEWPORTS = {desktop: {width: 1440, height: 900, isMobile: false}, phone: {width: 390, height: 844, isMobile: true}};
const MORE_PAGES = ['data', 'control', 'content'];

function args(argv) {
  const o = {out: path.join(__dirname, 'out'), only: [], profile: ['rich'], viewports: ['desktop', 'phone'], jobs: 2, scale: 1, clock: 'fixed', rawFonts: false, list: false};
  for (const a of argv) {
    const [k, v = ''] = a.replace(/^--/, '').split(/=(.*)/s);
    if (k === 'out') o.out = path.resolve(v);
    else if (k === 'only') o.only = v.split(',').filter(Boolean);
    else if (k === 'profile' || k === 'profiles') o.profile = v.split(',').filter(Boolean);
    else if (k === 'viewports' || k === 'viewport') o.viewports = v.split(',').filter(Boolean);
    else if (k === 'jobs') o.jobs = Math.max(1, Number(v) || 1);
    else if (k === 'scale') o.scale = Number(v) || 1;
    else if (k === 'clock') o.clock = v;
    else if (k === 'raw-fonts') o.rawFonts = true;
    else if (k === 'list') o.list = true;
    else if (k === 'views') o.views = path.resolve(v);
    else throw Error('Unknown flag ' + a);
  }
  for (const v of o.viewports) if (!VIEWPORTS[v]) throw Error('Unknown viewport ' + v);
  return o;
}

// fontconfig override: the site's stack is Inter, ui-sans-serif, system-ui, -apple-system, 'Segoe UI', sans-serif.
function fontEnv(dir) {
  const file = path.join(dir, 'fonts.conf');
  const alias = fam => `<alias binding="strong"><family>${fam}</family><prefer><family>Liberation Sans</family></prefer></alias>`;
  fs.writeFileSync(file, `<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd"><fontconfig><include ignore_missing="yes">/etc/fonts/fonts.conf</include>${['Inter', 'system-ui', 'ui-sans-serif', 'Segoe UI', 'sans-serif'].map(alias).join('')}</fontconfig>`);
  return {FONTCONFIG_FILE: file};
}

async function startServer() {
  register(pathToFileURL(path.join(__dirname, 'text-loader.mjs')));
  const worker = (await import(pathToFileURL(path.join(SERVER, 'insights-worker.mjs')).href)).default;
  const server = http.createServer(async (req, res) => {
    try {
      const url = 'http://' + req.headers.host + req.url, p = new URL(url).pathname;
      if (p.startsWith('/v2/') || p.startsWith('/insights/api/')) { res.writeHead(501, {'content-type': 'application/json'}); res.end('{"error":"API is answered by page.route in shots.cjs"}'); return; }
      const r = await worker.fetch(new Request(url, {method: req.method, headers: req.headers}), {});
      const headers = {}; r.headers.forEach((v, k) => { headers[k] = v; });
      res.writeHead(r.status, headers); res.end(Buffer.from(await r.arrayBuffer()));
    } catch (e) { res.writeHead(500); res.end(String(e && e.stack || e)); }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return {server, base: 'http://127.0.0.1:' + server.address().port};
}

// Mirrors journeyPayload() in journey-client.js.txt for the standalone map-frame.
function mapFramePayload(f) {
  const initials = n => (n || '').trim().split(/\s+/).slice(0, 2).map(v => v[0] || '').join('').toUpperCase();
  const feature = (kind, fid, p, name) => ({type: 'Feature', id: kind + ':' + fid, geometry: {type: 'Point', coordinates: [p.lon, p.lat]}, properties: {id: String(fid), kind, name: name || '', label: initials(name)}});
  const fc = features => ({type: 'FeatureCollection', features});
  const fresh = l => l && l.at > f.now - 120000;
  const people = [{...f.social.me, name: 'Tu'}, ...f.social.friends].filter(p => fresh(p.location)).map(p => feature('person', p.id, p.location, p.name));
  return {routes: f.journey.routes, zones: fc([...f.journey.zones.features, ...f.explore.cells.features.map(c => ({...c, properties: {...c.properties, explore: true}}))]),
    visits: fc(f.journey.visits.map(v => feature('visit', v.id, v, v.name))), people: fc(people),
    places: fc([...f.social.me.places.map(p => feature('place', p.id, p, p.name)), ...f.explore.places.map(p => { const x = feature('place', p.id, p, p.name || 'Loc fără nume'); x.properties.stars = p.stars || 0; return x; })]),
    devices: fc(f.recovery.filter(d => d.position).map(d => feature('device', d.id, d.position, d.name)))};
}

async function runView({browser, base, assets, opts, profile, vpName, view}) {
  const vp = VIEWPORTS[vpName];
  const fixture = buildFixture(profile, opts.clock === 'real' ? Date.now() : NOW);
  const api = createApi(fixture, assets);
  const record = {view: view.id, title: view.title, profile, viewport: vpName, file: null, ok: false, errors: [], console: [], blocked: [], unmocked: api.unmocked, metrics: null};
  const context = await browser.newContext({viewport: {width: vp.width, height: vp.height}, deviceScaleFactor: opts.scale, isMobile: vp.isMobile, hasTouch: vp.isMobile,
    locale: 'ro-RO', timezoneId: 'Europe/Bucharest', colorScheme: 'dark', reducedMotion: 'reduce'});
  if (opts.clock !== 'real') { await context.clock.install({time: fixture.now}); await context.clock.resume(); }
  const page = await context.newPage();
  page.on('pageerror', e => record.errors.push(e.message));
  page.on('console', m => { if (['error', 'warning'].includes(m.type())) record.console.push(m.type() + ': ' + m.text().slice(0, 240)); });
  page.on('dialog', d => d.accept().catch(() => {}));
  let inflight = 0, lastActivity = Date.now();
  const cors = {'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': 'GET,POST,PATCH,DELETE,OPTIONS'};
  await page.route('**/*', async route => {
    const req = route.request(), u = new URL(req.url());
    const local = u.origin === base;
    if (local && !u.pathname.startsWith('/v2/') && !u.pathname.startsWith('/insights/api/')) return route.continue();
    inflight++; lastActivity = Date.now();
    try {
      if (local || u.hostname === 'identitytoolkit.googleapis.com' || u.hostname === 'securetoken.googleapis.com') {
        if (req.method() === 'OPTIONS') return await route.fulfill({status: 204, headers: cors, body: ''});
        const r = await api.handle({method: req.method(), url: req.url(), body: req.postDataBuffer()});
        return await route.fulfill({status: r.status, headers: {...r.headers, ...(local ? {} : cors)}, body: r.body});
      }
      const third = assets.thirdParty(u);
      if (third) return await route.fulfill({...third, headers: cors});
      record.blocked.push(req.method() + ' ' + u.origin + u.pathname);
      return await route.abort('blockedbyclient');
    } catch (e) { if (!/closed|disposed/i.test(String(e))) record.errors.push('route: ' + e.message); }
    finally { inflight--; lastActivity = Date.now(); }
  });
  if (view.host) await page.addInitScript(() => { window.ForjaMapHost = {event: s => (window.__hostEvents ||= []).push(s)}; });
  const settle = async (max = 10000) => {
    const start = Date.now();
    while (Date.now() - start < max) { if (inflight === 0 && Date.now() - lastActivity > 500) break; await new Promise(r => setTimeout(r, 100)); }
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))).catch(() => {});
  };
  try {
    await page.goto(base + (view.url || '/insights'), {waitUntil: 'load'});
    if (view.auth !== false) {
      await page.fill('#email', 'lana@example.test'); await page.fill('#password', 'harness-only');
      await page.click('#login-button');
      await page.waitForSelector('#app:not([hidden])', {timeout: 15000});
      await page.waitForFunction(() => /La zi/.test(document.getElementById('status-text').textContent), null, {timeout: 15000});
    }
    if (view.page && view.page !== 'overview') {
      if (MORE_PAGES.includes(view.page)) await page.evaluate(() => { document.getElementById('nav-more').open = true; });
      await page.click(`.nav[data-page="${view.page}"]`);
    }
    await settle();
    if (view.requires) {
      const present = typeof view.requires === 'string' ? await page.locator(view.requires).count() > 0 : await page.evaluate(view.requires);
      if (!present) { record.ok = true; record.skipped = 'nothing to open for this profile (' + String(view.requires).slice(0, 80) + ')'; await context.close(); return record; }
    }
    for (const step of view.steps || []) {
      if (step.optional && (step.open || step.click) && !(await page.locator(step.open || step.click).count())) continue;
      if (step.click) await page.click(step.click, {timeout: 8000});
      else if (step.clickText) { const [sel, re] = step.clickText; await page.locator(sel).filter({hasText: new RegExp(re)}).first().click({timeout: 8000}); }
      else if (step.open) await page.evaluate(sel => { const d = document.querySelector(sel); if (!d) throw Error('No element for ' + sel); d.open = true; d.dispatchEvent(new Event('toggle')); }, step.open);
      else if (step.check) await page.check(step.check);
      else if (step.fill) await page.fill(step.fill[0], step.fill[1]);
      else if (step.waitFor) await page.waitForSelector(step.waitFor, {timeout: step.timeout || 10000, state: 'attached'});
      else if (step.waitFn) await page.waitForFunction(typeof step.waitFn === 'string' ? new Function('return (' + step.waitFn + ')') : step.waitFn, null, {timeout: step.timeout || 10000});
      else if (step.eval) await page.evaluate(step.eval);
      else if (step.scroll) await page.evaluate(sel => document.querySelector(sel)?.scrollIntoView({block: 'start'}), step.scroll);
      else if (step.wait) await page.waitForTimeout(step.wait);
      else if (step.mapFrameData) {
        await page.waitForFunction(() => !!window.ForjaMap, null, {timeout: 10000});
        await page.evaluate(data => { window.ForjaMap.setData(data); const pts = [].concat(...['people', 'places', 'visits', 'devices'].map(k => data[k].features.map(f => ({lat: f.geometry.coordinates[1], lon: f.geometry.coordinates[0]})))); window.ForjaMap.fit(pts); }, mapFramePayload(fixture));
      }
      await settle(4000);
    }
    await settle();
    const shot = view.shot || 'full';
    if (shot === 'full') {
      // Walk the page like a reader so IntersectionObserver-driven content (vault thumbnails) loads, then go back up.
      const height = await page.evaluate(() => document.scrollingElement.scrollHeight);
      for (let y = 0; y < height; y += Math.round(vp.height * 0.8)) { await page.evaluate(top => window.scrollTo(0, top), y); await page.waitForTimeout(60); }
      await settle(6000);
      await page.evaluate(() => window.scrollTo(0, 0));
      await settle(2000);
    }
    record.metrics = await page.evaluate(pageName => {
      const privacy = document.getElementById('privacy');
      const root = (pageName && document.getElementById('page-' + pageName)) || (privacy && !privacy.hidden ? privacy : document.body);
      const visible = e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
      const count = sel => [...root.querySelectorAll(sel)].filter(visible).length;
      const se = document.scrollingElement;
      return {words: (root.innerText || '').split(/\s+/).filter(Boolean).length, buttons: count('button'), inputs: count('input,select,textarea'), details: count('details'),
        height: se.scrollHeight, overflowX: se.scrollWidth > se.clientWidth + 1 ? se.scrollWidth - se.clientWidth : 0,
        status: document.getElementById('status-text')?.textContent || null, notice: document.getElementById('notice') && !document.getElementById('notice').hidden ? document.getElementById('notice').textContent : null,
        leafletTiles: document.querySelectorAll('.leaflet-tile-loaded').length, mapStatus: document.getElementById('map-render-status')?.textContent || document.getElementById('map-status')?.textContent || null,
        mapError: document.getElementById('map-render-status')?.dataset.error || document.getElementById('map-status')?.dataset.error || null};
    }, view.page || (view.auth === false ? null : 'overview'));
    if (view.probe) record.probe = await page.evaluate(view.probe);
    const file = `${profile}-${vpName}-${view.id}.png`;
    const target = path.join(opts.out, file);
    if (shot === 'full' || shot === 'viewport') await page.screenshot({path: target, fullPage: shot === 'full'});
    else await page.locator(shot).first().screenshot({path: target});
    record.file = file; record.ok = true;
  } catch (e) {
    record.errors.push('harness: ' + e.message.split('\n')[0]);
    const file = `${profile}-${vpName}-${view.id}-FAILED.png`;
    await page.screenshot({path: path.join(opts.out, file), fullPage: false}).catch(() => {});
    record.file = file;
  }
  record.apiCalls = api.calls.length;
  await context.close();
  return record;
}

function contactSheet(out, records, opts) {
  const esc = s => String(s).replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
  const rows = records.filter(r => !r.skipped).map(r => `<figure class="${r.viewport}${r.ok ? '' : ' failed'}"><a href="${esc(r.file)}"><img loading="lazy" src="${esc(r.file)}" alt=""></a><figcaption><b>${esc(r.view)}</b> · ${esc(r.profile)} · ${esc(r.viewport)}<br>${esc(r.title || '')}${r.errors.length ? '<br><span class="err">' + esc(r.errors.join(' | ').slice(0, 300)) + '</span>' : ''}${r.unmocked.length ? '<br><span class="err">unmocked: ' + esc(r.unmocked.join(', ')) + '</span>' : ''}</figcaption></figure>`).join('\n');
  fs.writeFileSync(path.join(out, 'index.html'), `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FORJA site shots</title>
<style>:root{color-scheme:dark}body{margin:0;padding:16px;background:#0e1411;color:#e8efe4;font:13px system-ui,sans-serif}h1{font-size:18px}main{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px}figure{margin:0;background:#18211c;border:1px solid #2c3a31;border-radius:10px;overflow:hidden}figure.failed{border-color:#c55}img{display:block;width:100%;height:260px;object-fit:cover;object-position:top}figcaption{padding:8px 10px;line-height:1.4}.err{color:#ffb4a6}</style>
<h1>FORJA · site shots · ${esc(opts.profile.join(', '))} · ${esc(new Date().toISOString())}</h1><main>${rows}</main>`);
}

(async () => {
  const opts = args(process.argv.slice(2));
  if (opts.views) VIEWS = require(opts.views);
  const views = VIEWS.filter(v => !opts.only.length || opts.only.some(t => v.id.includes(t)));
  if (opts.list) { for (const v of VIEWS) console.log(v.id.padEnd(24), v.title); return; }
  fs.mkdirSync(opts.out, {recursive: true});
  const env = opts.rawFonts ? {} : fontEnv(fs.mkdtempSync(path.join(os.tmpdir(), 'forja-fonts-')));
  const {server, base} = await startServer();
  const browser = await chromium.launch({env: {...process.env, ...env}, args: ['--enable-unsafe-swiftshader', '--ignore-gpu-blocklist']});
  const assets = await createAssets(browser, SERVER);
  const tasks = [];
  for (const profile of opts.profile) for (const vpName of opts.viewports) for (const view of views) if (!view.only || view.only.includes(vpName)) tasks.push({profile, vpName, view});
  const records = new Array(tasks.length);
  let next = 0;
  await Promise.all(Array.from({length: Math.min(opts.jobs, tasks.length)}, async () => {
    while (next < tasks.length) {
      const i = next++, t = tasks[i], started = Date.now();
      records[i] = await runView({browser, base, assets, opts, ...t});
      console.error(`${records[i].skipped ? 'skip' : records[i].ok ? 'ok  ' : 'FAIL'} ${records[i].file || records[i].profile + '-' + records[i].viewport + '-' + records[i].view} (${((Date.now() - started) / 1000).toFixed(1)} s)${records[i].errors.length ? ' — ' + records[i].errors.join(' | ').slice(0, 200) : ''}`);
    }
  }));
  await browser.close(); server.close();
  const report = {generated_at: new Date().toISOString(), base, opts: {...opts, out: opts.out}, views: records};
  fs.writeFileSync(path.join(opts.out, 'report.json'), JSON.stringify(report, null, 1));
  contactSheet(opts.out, records, opts);
  const failed = records.filter(r => !r.ok).length;
  console.log(JSON.stringify({out: opts.out, shots: records.filter(r => r.file).length, skipped: records.filter(r => r.skipped).length, failed, unmocked: [...new Set(records.flatMap(r => r.unmocked))], files: records.map(r => r.file).filter(Boolean)}, null, 1));
  if (failed) process.exitCode = 1;
})().catch(e => { console.error(e); process.exitCode = 1; });
