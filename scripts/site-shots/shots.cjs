#!/usr/bin/env node
// LOCAL SITE PREVIEW HARNESS for THE SITE (forja-insights, FORJA 4.4). Design-review tool, not part of CI.
//
// What it does
//  1. Imports server/insights-worker.mjs itself (text-loader.mjs = wrangler's Text + Data module rules) and serves every
//     static route from a local HTTP server with the worker's own responses: the HTML + CSP headers, /insights/app.js
//     concatenated from the same site-*.js.txt files in the same order, MapLibre + the map renderer, the fonts, pdf.js.
//  2. Drives Chromium (Playwright) and mocks the network with page.route: Firebase identitytoolkit/securetoken
//     (fake idToken), every §3.2 / §3.4 endpoint (mock-api.cjs + fixture.cjs), OpenFreeMap style/tiles/glyphs (assets.cjs,
//     a local style with liberty's layer ids). Everything else is blocked and reported.
//  3. Opens the deep link of each view (/insights#<section>), logs in through the real form (or restores a saved session)
//     and captures every view in views.cjs at desktop 1440×900, phone 390×844 and Galaxy S23 360×780, writing PNGs,
//     report.json (errors, unmocked calls, density metrics, horizontal overflow) and index.html.
//
// Usage
//   node scripts/site-shots/shots.cjs --out=/tmp/shots [--only=teren,gasire] [--profile=rich,lana,empty]
//        [--viewports=desktop,phone,s23] [--jobs=3] [--scale=1] [--clock=fixed|real] [--list]
// Requirements: Playwright at /opt/node22/lib/node_modules/playwright (or FORJA_PLAYWRIGHT), browsers in /opt/pw-browsers.
'use strict';
const fs = require('node:fs');
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
const VIEWPORTS = {desktop: {width: 1440, height: 900, isMobile: false}, phone: {width: 390, height: 844, isMobile: true}, s23: {width: 360, height: 780, isMobile: true}};

function args(argv) {
  const o = {out: path.join(__dirname, 'out'), only: [], profile: ['rich'], viewports: ['desktop', 'phone', 's23'], jobs: 3, scale: 1, clock: 'fixed', list: false};
  for (const a of argv) {
    const [k, v = ''] = a.replace(/^--/, '').split(/=(.*)/s);
    if (k === 'out') o.out = path.resolve(v);
    else if (k === 'only') o.only = v.split(',').filter(Boolean);
    else if (k === 'profile' || k === 'profiles') o.profile = v.split(',').filter(Boolean);
    else if (k === 'viewports' || k === 'viewport') o.viewports = v.split(',').filter(Boolean);
    else if (k === 'jobs') o.jobs = Math.max(1, Number(v) || 1);
    else if (k === 'scale') o.scale = Number(v) || 1;
    else if (k === 'clock') o.clock = v;
    else if (k === 'list') o.list = true;
    else if (k === 'views') o.views = path.resolve(v);
    else throw Error('Unknown flag ' + a);
  }
  for (const v of o.viewports) if (!VIEWPORTS[v]) throw Error('Unknown viewport ' + v);
  return o;
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

async function runView({browser, base, assets, opts, profile, vpName, view}) {
  const vp = VIEWPORTS[vpName];
  const fixture = buildFixture(profile, opts.clock === 'real' ? Date.now() : NOW);
  const api = createApi(fixture, assets, {fail: view.fail || []});
  const record = {view: view.id, title: view.title, profile, viewport: vpName, file: null, ok: false, errors: [], console: [], blocked: [], unmocked: api.unmocked, metrics: null};
  const context = await browser.newContext({viewport: {width: vp.width, height: vp.height}, deviceScaleFactor: opts.scale, isMobile: vp.isMobile, hasTouch: vp.isMobile,
    locale: 'ro-RO', timezoneId: 'Europe/Bucharest', colorScheme: 'dark', reducedMotion: 'reduce'});
  if (opts.clock !== 'real') { await context.clock.install({time: fixture.now}); await context.clock.resume(); }
  const page = await context.newPage();
  page.on('pageerror', e => record.errors.push(e.message));
  page.on('console', m => { if (['error', 'warning'].includes(m.type()) && !/GPU stall|ReadPixels|WebGL/i.test(m.text())) record.console.push(m.type() + ': ' + m.text().slice(0, 240)); });
  page.on('dialog', d => { record.errors.push('native dialog: ' + d.type()); d.dismiss().catch(() => {}); });
  let inflight = 0, lastActivity = Date.now();
  const cors = {'access-control-allow-origin': '*', 'access-control-allow-headers': '*', 'access-control-allow-methods': 'GET,POST,PATCH,DELETE,OPTIONS'};
  await page.route('**/*', async route => {
    const req = route.request(), u = new URL(req.url());
    const local = u.origin === base;
    if (local && !u.pathname.startsWith('/v2/') && !u.pathname.startsWith('/insights/api/')) return route.continue();
    if (u.protocol === 'data:' || u.protocol === 'blob:') return route.continue();
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
  if (process.env.FORJA_SHOTS_DEBUG) await page.addInitScript(() => { let v; Object.defineProperty(window, 'ForjaMapRenderer', {configurable: true, get: () => v, set: x => { const c = x.create; x.create = (...a) => (window.__r = c(...a)); v = x; }}); });
  const storage = {...(view.storage || {})};
  if (view.auth === 'stored') storage['forja.auth.v1'] = JSON.stringify({v: 1, refresh: 'local-harness-refresh', uid: fixture.azi.me.uid, email: fixture.azi.me.email});
  if (Object.keys(storage).length) await page.addInitScript(entries => { for (const [k, v] of Object.entries(entries)) localStorage.setItem(k, v); }, storage);
  const settle = async (max = 10000) => {
    const start = Date.now();
    while (Date.now() - start < max) { if (inflight === 0 && Date.now() - lastActivity > 500) break; await new Promise(r => setTimeout(r, 100)); }
    await page.evaluate(() => document.fonts ? document.fonts.ready.then(() => true) : true).catch(() => {});
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))).catch(() => {});
  };
  const section = (view.hash || 'azi').split('/')[0];
  try {
    await page.goto(base + '/insights' + (view.hash ? '#' + view.hash : ''), {waitUntil: 'load'});
    if (view.auth === false) await page.waitForSelector('#login:not([hidden])', {timeout: 15000});
    else {
      if (view.auth !== 'stored') {
        await page.waitForSelector('#login:not([hidden])', {timeout: 15000});
        await page.fill('#login-email', 'lana@example.test'); await page.fill('#login-password', 'harness-only');
        await page.click('#login-submit');
      }
      await page.waitForSelector('#app:not([hidden])', {timeout: 15000});
      await page.waitForFunction(id => { const s = document.getElementById('s-' + id); return !!s && !s.hidden && !s.querySelector('.skeleton'); }, section, {timeout: 20000, polling: 200}).catch(() => record.errors.push('harness: section still loading'));
    }
    await settle();
    if (view.requires) {
      const present = typeof view.requires === 'string' ? await page.locator(view.requires).count() > 0 : await page.evaluate(view.requires);
      if (!present) { record.ok = true; record.skipped = 'nothing to open for this profile'; await context.close(); return record; }
    }
    for (const step of view.steps || []) {
      try {
        if (step.optional && step.click && !(await page.locator(step.click).count())) continue;
        if (step.click) await page.click(step.click, {timeout: 8000});
        else if (step.clickText) { const [sel, re] = step.clickText; await page.locator(sel).filter({hasText: new RegExp(re)}).first().click({timeout: 8000}); }
        else if (step.fill) await page.fill(step.fill[0], step.fill[1]);
        else if (step.waitFor) await page.waitForSelector(step.waitFor, {timeout: step.timeout || 10000, state: 'attached'});
        else if (step.waitFn) await page.waitForFunction(step.waitFn, null, {timeout: step.timeout || 10000, polling: 200});
        else if (step.eval) await page.evaluate(step.eval, step.arg);
        else if (step.scroll) await page.evaluate(sel => document.querySelector(sel)?.scrollIntoView({block: 'start'}), step.scroll);
        else if (step.wait) await page.waitForTimeout(step.wait);
      } catch (e) { if (!step.optional) throw e; }
      await settle(4000);
    }
    await settle();
    const shot = view.shot || 'full';
    if (await page.locator('#map-canvas').count()) await page.waitForFunction(() => { const c = document.getElementById('map-canvas'); return !c || !c.isConnected || !c.classList.contains('maplibregl-map') || c.dataset.idle === '1' || document.getElementById('map-host')?.dataset.failed === '1'; }, null, {timeout: 30000, polling: 250}).catch(() => record.errors.push('harness: map never idle'));
    if (shot === 'full') {
      // Capturile întregi: bara de jos a telefonului stă la capătul paginii (live e fixată jos pe ecran).
      await page.addStyleTag({content: 'body{position:relative}.tabbar{position:absolute!important}'});
      const height = await page.evaluate(() => document.scrollingElement.scrollHeight);
      for (let y = 0; y < height; y += Math.round(vp.height * 0.6)) { await page.evaluate(top => window.scrollTo(0, top), y); await page.waitForTimeout(220); }
      await settle(6000);
      await page.evaluate(() => window.scrollTo(0, 0));
      await settle(2000);
    }
    record.metrics = await page.evaluate(id => {
      const app = document.getElementById('app');
      const root = app && !app.hidden ? document.getElementById('s-' + id) : document.getElementById('login');
      const visible = e => !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
      const count = sel => root ? [...root.querySelectorAll(sel)].filter(visible).length : 0;
      const se = document.scrollingElement;
      const wide = root ? [...root.querySelectorAll('*')].filter(e => { const r = e.getBoundingClientRect(); return r.width > 0 && (r.right > se.clientWidth + 1 || r.left < -1) && getComputedStyle(e).position !== 'fixed' && !e.closest('.maplibregl-map'); }).slice(0, 5).map(e => e.tagName.toLowerCase() + '.' + String(e.className).split(' ')[0]) : [];
      const text = root ? root.innerText || '' : '';
      return {words: text.split(/\s+/).filter(Boolean).length, buttons: count('button'), inputs: count('input,select,textarea'),
        height: se.scrollHeight, overflowX: se.scrollWidth > se.clientWidth + 1 ? se.scrollWidth - se.clientWidth : 0, wide,
        cedilla: /[şţŞŢ]/.test(document.body.innerText), bang: /!/.test(text.replace(/<!--[\s\S]*?-->/g, '')), title: document.title,
        chip: document.getElementById('chip-' + id)?.textContent || null, map: document.getElementById('map-host')?.dataset.ready || document.getElementById('map-host')?.dataset.failed && 'failed' || null};
    }, section);
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
  record.calls = api.calls.map(c => c.method + ' ' + c.path);
  await context.close();
  return record;
}

function contactSheet(out, records, opts) {
  const esc = s => String(s).replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
  const rows = records.filter(r => !r.skipped).map(r => `<figure class="${r.viewport}${r.ok ? '' : ' failed'}"><a href="${esc(r.file)}"><img loading="lazy" src="${esc(r.file)}" alt=""></a><figcaption><b>${esc(r.view)}</b> · ${esc(r.profile)} · ${esc(r.viewport)}<br>${esc(r.title || '')}${r.errors.length ? '<br><span class="err">' + esc(r.errors.join(' | ').slice(0, 300)) + '</span>' : ''}${r.unmocked.length ? '<br><span class="err">unmocked: ' + esc(r.unmocked.join(', ')) + '</span>' : ''}${r.metrics?.overflowX ? '<br><span class="err">overflow-x ' + r.metrics.overflowX + 'px</span>' : ''}</figcaption></figure>`).join('\n');
  fs.writeFileSync(path.join(out, 'index.html'), `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FORJA site shots</title>
<style>:root{color-scheme:dark}body{margin:0;padding:16px;background:#0A0A0B;color:#F4F2EE;font:13px system-ui,sans-serif}h1{font-size:18px}main{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px}figure{margin:0;background:#121214;border:1px solid #2a2a30;border-radius:6px;overflow:hidden}figure.failed{border-color:#c55}img{display:block;width:100%;height:300px;object-fit:cover;object-position:top}figcaption{padding:8px 10px;line-height:1.4}.err{color:#ffb4a6}</style>
<h1>FORJA · site 4.4 · ${esc(opts.profile.join(', '))} · ${esc(new Date().toISOString())}</h1><main>${rows}</main>`);
}

(async () => {
  const opts = args(process.argv.slice(2));
  if (opts.views) VIEWS = require(opts.views);
  const views = VIEWS.filter(v => !opts.only.length || opts.only.some(t => v.id.includes(t)));
  if (opts.list) { for (const v of VIEWS) console.log(v.id.padEnd(24), v.title); return; }
  fs.mkdirSync(opts.out, {recursive: true});
  const {server, base} = await startServer();
  const browser = await chromium.launch({args: ['--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--use-angle=swiftshader']});
  const assets = await createAssets(browser, SERVER);
  const tasks = [];
  for (const profile of opts.profile) for (const vpName of opts.viewports) for (const view of views) if (!view.only || view.only.includes(vpName)) tasks.push({profile, vpName, view});
  const records = new Array(tasks.length);
  let next = 0;
  await Promise.all(Array.from({length: Math.min(opts.jobs, tasks.length)}, async () => {
    while (next < tasks.length) {
      const i = next++, t = tasks[i], started = Date.now();
      records[i] = await runView({browser, base, assets, opts, ...t});
      const r = records[i];
      console.error(`${r.skipped ? 'skip' : r.ok ? 'ok  ' : 'FAIL'} ${r.file || r.profile + '-' + r.viewport + '-' + r.view} (${((Date.now() - started) / 1000).toFixed(1)} s)${r.errors.length ? ' — ' + r.errors.join(' | ').slice(0, 200) : ''}${r.metrics?.overflowX ? ' — OVERFLOW ' + r.metrics.overflowX + 'px ' + r.metrics.wide.join(',') : ''}`);
    }
  }));
  await browser.close(); server.close();
  const report = {generated_at: new Date().toISOString(), base, opts: {...opts, out: opts.out}, views: records};
  fs.writeFileSync(path.join(opts.out, 'report.json'), JSON.stringify(report, null, 1));
  contactSheet(opts.out, records, opts);
  const failed = records.filter(r => !r.ok).length;
  const summary = {out: opts.out, shots: records.filter(r => r.file && r.ok).length, skipped: records.filter(r => r.skipped).length, failed,
    pageErrors: records.reduce((a, r) => a + r.errors.length, 0), unmocked: [...new Set(records.flatMap(r => r.unmocked))], blocked: [...new Set(records.flatMap(r => r.blocked))],
    overflow: records.filter(r => r.metrics?.overflowX).map(r => r.file), cedilla: records.filter(r => r.metrics?.cedilla).map(r => r.file), bang: records.filter(r => r.metrics?.bang).map(r => r.file)};
  console.log(JSON.stringify(summary, null, 1));
  if (failed || summary.pageErrors || summary.unmocked.length) process.exitCode = 1;
})().catch(e => { console.error(e); process.exitCode = 1; });
