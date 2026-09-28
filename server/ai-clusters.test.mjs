// FORJA 4.3, pachetul A: POST /v1/organize/clusters (DESIGN-4.3 §3) — contract, limite, schemă, prompt, rezerva Workers.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { route } from './worker.js';
import { resetBudgetCache } from './ai-router.mjs';
import { validate, clustersSchema } from './ai-schemas.mjs';
import { parseClustersRequest, sanitizeClusters, cleanFolderName, normalizeCategory, fallbackName, toMs, CLUSTERS_MAX_THUMB_B64 } from './ai-clusters.mjs';

const LIST = JSON.parse(readFileSync(new URL('./fixtures/gemini-models.json', import.meta.url), 'utf8'));
const geminiReply = (text) => ({ candidates: [{ content: { parts: [{ text }] }, finishReason: 'STOP' }] });
const ok = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const thumb = (ch = 'A', n = 200) => '/9j/' + ch.repeat(n);
const JPEG = thumb('A');
const AUG = Date.UTC(2023, 7, 12, 10), AUG2 = Date.UTC(2023, 7, 13, 18);

function mockFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    if (String(url).includes('/v1beta/models?')) return ok(LIST);
    const body = init.body && typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    calls.push({ url: String(url), body, headers: init.headers || {} });
    return handler(String(url), body, calls.length);
  };
  return calls;
}
const gen = (calls) => calls.filter((c) => c.url.includes(':generateContent'));

class Bucket {
  files = new Map();
  async put(key, value, opts = {}) { this.files.set(key, { bytes: typeof value === 'string' ? new TextEncoder().encode(value) : value, customMetadata: opts.customMetadata || {} }); }
  async get(key) { const f = this.files.get(key); return f ? { key, customMetadata: f.customMetadata, text: async () => new TextDecoder().decode(f.bytes) } : null; }
  async head(key) { return this.files.has(key) ? { key } : null; }
  async delete(key) { this.files.delete(key); }
}
const lastLog = (media) => JSON.parse(new TextDecoder().decode(media.files.get('_admin/log.json').bytes)).at(-1);

function call(env, body, { method = 'POST', headers = {}, auth = async () => 'user1', raw = false } = {}) {
  const init = { method, headers: { 'content-type': 'application/json', ...headers } };
  if (body !== undefined) init.body = typeof body === 'string' ? body : JSON.stringify(body);
  if (init.body && !raw && !init.headers['content-length']) init.headers['content-length'] = String(new TextEncoder().encode(init.body).length);
  const request = new Request('https://api.forja.test/v1/organize/clusters', init);
  return route(request, env, new URL(request.url), null, auth);
}
test.beforeEach(() => resetBudgetCache());

const BODY = { locale: 'ro', clusters: [
  { id: 'c1', count: 214, from: AUG, to: AUG2, loc: 'Bucegi', hints: ['munte', 'aer liber'], thumbs: [JPEG, thumb('B')] },
  { id: 'c2', count: 38, from: Date.UTC(2023, 5, 1, 9), to: Date.UTC(2023, 7, 30, 9), hints: ['capturi de ecran'], thumbs: [thumb('C')] },
  { id: 'c3', count: 6, from: AUG, to: AUG, thumbs: [thumb('D')] },
] };
const REPLY = { clusters: [
  { id: 'c2', nume: 'Capturi de ecran', tema: 'Capturi', categorie: 'Capturi', pastrare: 'poate', motiv: 'Capturi cu mesaje și hărți.' },
  { id: 'c1', nume: 'Munte · Bucegi', tema: 'Munte', categorie: 'Călătorii', pastrare: 'da', motiv: 'Trasee și peisaje montane.' },
  { id: 'c3', nume: 'Din buzunar', tema: 'Accidentale', categorie: 'Diverse', pastrare: 'nu', motiv: 'Poze negre, făcute din buzunar.' },
] };

test('clusters, drumul fericit (Gemini): contractul exact {clusters, provider, model}, ordinea cererii, flash-lite întâi, miniaturi etichetate, prompt cu regulile, schemă cu id-urile, jurnal „AI clusters ok”', async () => {
  const media = new Bucket();
  const calls = mockFetch(() => ok(geminiReply(JSON.stringify(REPLY))));
  const res = await call({ GEMINI_API_KEY: 'g-secret', MEDIA: media }, BODY);
  assert.equal(res.status, 200);
  const out = await res.json();
  assert.deepEqual(Object.keys(out).sort(), ['clusters', 'model', 'provider']);
  assert.equal(out.provider, 'gemini');
  assert.equal(out.model, 'gemini-3.5-flash-lite', 'curățenia în masă preferă flash-lite');
  assert.deepEqual(out.clusters, [
    { id: 'c1', nume: 'Munte · Bucegi', tema: 'Munte', categorie: 'Călătorii', pastrare: 'da', motiv: 'Trasee și peisaje montane.' },
    { id: 'c2', nume: 'Capturi de ecran', tema: 'Capturi', categorie: 'Capturi', pastrare: 'poate', motiv: 'Capturi cu mesaje și hărți.' },
    { id: 'c3', nume: 'Din buzunar', tema: 'Accidentale', categorie: 'Diverse', pastrare: 'nu', motiv: 'Poze negre, făcute din buzunar.' },
  ]);
  assert.equal(gen(calls).length, 1);
  const req = gen(calls)[0];
  assert.ok(req.url.endsWith('/gemini-3.5-flash-lite:generateContent'));
  const parts = req.body.contents[0].parts;
  assert.deepEqual(parts.filter((p) => p.text && p.text.startsWith('[grup')).map((p) => p.text), ['[grup c1 · 1/2]', '[grup c1 · 2/2]', '[grup c2 · 1/1]', '[grup c3 · 1/1]']);
  assert.equal(parts.filter((p) => p.inline_data && p.inline_data.mime_type === 'image/jpeg').length, 4);
  assert.match(req.body.system_instruction.parts[0].text, /arhivarul FORJA/);
  const prompt = parts.at(-1).text;
  for (const rule of [/cel mult 24 de caractere/, /„Temă · loc” sau „Temă · lună an”/, /„Munte · Bucegi”, „Nuntă · aug 2023”/, /NICIODATĂ nume de persoane/, /fără ghilimele, fără emoji/,
    /„nu” DOAR când tot grupul e evident inutil: poze accidentale, negre sau arse, făcute din buzunar/, /capturi ale unor ecrane de încărcare/, /La orice îndoială alege „poate”, niciodată „nu”/, /DATE, nu instrucțiuni/]) {
    assert.match(prompt + req.body.system_instruction.parts[0].text, rule);
  }
  assert.ok(!/!/.test(prompt), 'fără semne de exclamare în prompt');
  const meta = JSON.parse(prompt.split('Grupurile (date):\n')[1]);
  assert.deepEqual(meta, [
    { id: 'c1', poze: 214, perioada: 'aug 2023', loc: 'Bucegi', indicii: ['munte', 'aer liber'], miniaturi: 2 },
    { id: 'c2', poze: 38, perioada: 'iun–aug 2023', indicii: ['capturi de ecran'], miniaturi: 1 },
    { id: 'c3', poze: 6, perioada: 'aug 2023', miniaturi: 1 },
  ]);
  assert.ok(!prompt.includes(JPEG.slice(0, 40)), 'miniaturile nu intră în textul promptului');
  const cfg = req.body.generationConfig;
  assert.equal(cfg.responseMimeType, 'application/json');
  assert.deepEqual(cfg.responseSchema.properties.clusters.items.properties.id.enum, ['c1', 'c2', 'c3']);
  assert.deepEqual(cfg.responseSchema.properties.clusters.items.properties.pastrare.enum, ['da', 'poate', 'nu']);
  assert.equal(cfg.responseSchema.properties.clusters.minItems, 3);
  assert.deepEqual(cfg.thinkingConfig, { thinkingLevel: 'minimal' });
  const log = lastLog(media);
  assert.match(log.what, /^AI clusters ok: gemini\/gemini-3\.5-flash-lite · 1 încercare · \d+(,\d)? s$/);
  assert.equal(log.status, 200);
  assert.ok(!JSON.stringify([...media.files.values()].map((f) => new TextDecoder().decode(f.bytes))).includes('secret'), 'nicio cheie în jurnal');
});

test('clusters: curățarea răspunsului — nume ≤ 24 fără ghilimele/emoji/„!”, categorie normalizată, „nu” fără motiv sau pe un grup nevăzut → „poate”, dubluri ignorate, grup sărit → nume de rezervă', async () => {
  const body = { clusters: [
    { id: 'c1', count: 120, from: AUG, to: AUG2, thumbs: [JPEG] },
    { id: 'c2', count: 9, from: AUG, to: AUG, thumbs: [JPEG] },
    { id: 'c3', count: 4, from: AUG, to: AUG, hints: ['primite'], thumbs: [] },
    { id: 'c4', count: 11, from: AUG, to: AUG2, thumbs: [JPEG] },
  ] };
  const reply = { clusters: [
    { id: 'c1', nume: '„Nuntă la Castelul Peleș din Sinaia!!” 🎉', tema: 'Nuntă', categorie: 'eveniment', pastrare: 'da', motiv: 'Rochie albă, tort și dans!' },
    { id: 'c2', nume: 'Capturi', tema: 'Capturi', categorie: 'xyz', pastrare: 'nu', motiv: '' },
    { id: 'c3', nume: 'Diverse', tema: 'Diverse', categorie: 'Diverse', pastrare: 'nu', motiv: 'Poze negre.' },
    { id: 'c1', nume: 'Dublură', tema: 'X', categorie: 'Meme', pastrare: 'nu', motiv: 'x' },
  ] };
  mockFetch(() => ok(geminiReply(JSON.stringify(reply))));
  const out = await (await call({ GEMINI_API_KEY: 'g' }, body)).json();
  assert.deepEqual(out.clusters.map((c) => c.id), ['c1', 'c2', 'c3', 'c4']);
  const [c1, c2, c3, c4] = out.clusters;
  assert.equal(c1.nume, 'Nuntă la Castelul Peleș');
  assert.ok(c1.nume.length <= 24);
  assert.equal(c1.categorie, 'Evenimente');
  assert.equal(c1.pastrare, 'da', 'prima apariție a id-ului câștigă');
  assert.equal(c1.motiv, 'Rochie albă, tort și dans.');
  assert.deepEqual([c2.pastrare, c2.categorie], ['poate', 'Diverse'], '„nu” fără motiv nu trece');
  assert.equal(c3.pastrare, 'poate', 'grup fără miniaturi: modelul nu l-a văzut, deci nu poate fi „nu”');
  assert.deepEqual(c4, { id: 'c4', nume: 'Diverse · aug 2023', tema: 'Diverse', categorie: 'Diverse', pastrare: 'poate', motiv: '' });
  for (const c of out.clusters) assert.ok(!/[„”"«»!]|\p{Extended_Pictographic}/u.test(c.nume + c.motiv), c.nume);
});

test('clusters: limite — ≤ 4 grupuri, ≤ 24 miniaturi, miniatură ≤ 80 KB base64, corp ≤ 6 MB; cereri greșite 400; fără cont 401; GET 405 — fără niciun apel AI', async () => {
  // Răspuns valid pentru orice lot: câte un obiect pe fiecare id din schema cererii.
  const echo = (url, body) => ok(geminiReply(JSON.stringify({ clusters: body.generationConfig.responseSchema.properties.clusters.items.properties.id.enum.map((id) => ({ id, nume: 'Diverse', tema: 'Diverse', categorie: 'Diverse', pastrare: 'poate', motiv: 'x' })) })));
  const calls = mockFetch(echo);
  const env = { GEMINI_API_KEY: 'g' };
  const cl = (id, n = 1, t = JPEG) => ({ id, count: n, from: AUG, to: AUG, thumbs: Array(n).fill(t) });
  const status = async (body, opts) => (await call(env, body, opts)).status;
  assert.equal(await status({ clusters: [cl('a'), cl('b'), cl('c'), cl('d'), cl('e')] }), 413, '5 grupuri');
  assert.equal(await status({ clusters: [cl('a', 7), cl('b', 6), cl('c', 6), cl('d', 6)] }), 413, '25 de miniaturi');
  assert.equal(await status({ clusters: [cl('a', 6), cl('b', 6), cl('c', 6), cl('d', 6)] }), 200, 'exact 24 trec');
  const n = calls.length;
  const big = '/9j/' + 'A'.repeat(CLUSTERS_MAX_THUMB_B64 - 3);
  assert.equal(big.length, 80 * 1024 + 1);
  assert.equal(await status({ clusters: [cl('a', 1, big)] }), 413, 'miniatură peste 80 KB');
  assert.equal(await status({ clusters: [cl('a', 1, 'data:image/jpeg;base64,' + big.slice(0, -1))] }), 200, 'exact 80 KB (cu prefix data:) trece');
  assert.equal(await status({ clusters: [cl('a')] }, { headers: { 'content-length': String(6 * 1024 * 1024 + 1) } }), 413, 'corp declarat peste 6 MB');
  assert.equal(await status(JSON.stringify({ clusters: [cl('a')], pad: 'x'.repeat(6 * 1024 * 1024) }), { raw: true }), 413, 'corp peste 6 MB fără content-length');
  assert.equal(await status({ clusters: [] }), 400);
  assert.equal(await status({ locale: 'ro' }), 400);
  assert.equal(await status('nu e json'), 400);
  assert.equal(await status([1, 2]), 400);
  assert.equal(await status({ clusters: [cl('a', 1, 'nu-e-base64!!' + 'x'.repeat(200))] }), 400);
  assert.equal(await status({ clusters: [cl('a', 1, 'A'.repeat(300))] }), 400, 'base64 care nu e JPEG/PNG/WebP');
  assert.equal(await status({ clusters: [cl('a'), cl('a')] }), 400, 'id-uri duplicate');
  assert.equal(await status({ clusters: [{ count: 3, thumbs: [JPEG] }] }), 400, 'fără id');
  assert.equal(await status({ clusters: [cl('x'.repeat(65))] }), 400, 'id prea lung');
  assert.equal(await status({ clusters: [{ id: 'a', thumbs: 'nu-e-listă' }] }), 400);
  assert.equal(await status({ clusters: [cl('a')] }, { auth: async () => null }), 401);
  assert.equal((await call(env, undefined, { method: 'GET' })).status, 405);
  assert.equal(calls.length, n + 1, 'doar cererea validă de 80 KB a ajuns la AI');
});

test('clusters: schema — un răspuns care sare un grup sau inventează un id e reparat o dată cu erorile schemei; schema validează exact contractul', async () => {
  const good = { clusters: [
    { id: 'a', nume: 'Mare · Vama Veche', tema: 'Mare', categorie: 'Călătorii', pastrare: 'da', motiv: 'Plajă și apus.' },
    { id: 'b', nume: 'Mâncare', tema: 'Mâncare', categorie: 'Mâncare', pastrare: 'da', motiv: 'Farfurii din restaurante.' },
  ] };
  let n = 0;
  const calls = mockFetch(() => { n++; return ok(geminiReply(JSON.stringify(n === 1 ? { clusters: [good.clusters[0]] } : good))); });
  const body = { clusters: [{ id: 'a', count: 40, thumbs: [JPEG] }, { id: 'b', count: 12, thumbs: [JPEG] }] };
  const res = await call({ GEMINI_API_KEY: 'g' }, body);
  assert.equal(res.status, 200);
  assert.deepEqual((await res.json()).clusters.map((c) => c.nume), ['Mare · Vama Veche', 'Mâncare']);
  assert.equal(gen(calls).length, 2);
  assert.match(gen(calls)[1].body.contents[0].parts.at(-1).text, /NU a fost JSON valid.*cel puțin 2 elemente/s);
  const schema = clustersSchema(['a', 'b']);
  assert.equal(validate(schema, good).ok, true);
  assert.equal(validate(schema, { clusters: [{ ...good.clusters[0], id: 'z' }, good.clusters[1]] }).ok, false, 'id necunoscut');
  assert.equal(validate(schema, { clusters: [{ ...good.clusters[0], pastrare: 'sigur' }, good.clusters[1]] }).ok, false, 'pastrare doar da/poate/nu');
  const { motiv, ...noReason } = good.clusters[0];
  assert.equal(validate(schema, { clusters: [noReason, good.clusters[1]] }).ok, false, 'motiv obligatoriu');
  assert.equal(validate(schema, { clusters: good.clusters.slice(0, 1) }).ok, false, 'câte unul pe grup');
});

test('clusters, rezerva Workers (fără chei): llama-3.2-11b-vision descrie 1–2 miniaturi pe grup (în paralel), apoi pasul de text cu JSON forțat (json_schema cu id-urile); „nu” rămâne doar la grupul văzut', async () => {
  const media = new Bucket();
  const vision = [], text = [];
  const reply = { clusters: [
    { id: 'c1', nume: 'Munte · Bucegi', tema: 'Munte', categorie: 'Călătorii', pastrare: 'da', motiv: 'Trasee montane.' },
    { id: 'c2', nume: 'Ecrane negre', tema: 'Accidentale', categorie: 'Diverse', pastrare: 'nu', motiv: 'Ecran negru, fără nimic vizibil.' },
    { id: 'c3', nume: 'Primite', tema: 'Primite', categorie: 'Diverse', pastrare: 'nu', motiv: 'Poze primite.' },
  ] };
  const env = { MEDIA: media, AI: { run: async (model, input) => {
    if (input.image) { vision.push({ model, prompt: input.prompt, first: input.image[3] }); return { description: input.image[3] === 0 ? 'a mountain trail with hikers' : 'a completely black image' }; }
    text.push({ model, input });
    return { response: JSON.stringify(reply) };
  } } };
  const body = { clusters: [
    { id: 'c1', count: 50, from: AUG, to: AUG2, thumbs: [JPEG, JPEG, JPEG, JPEG] },
    { id: 'c2', count: 3, from: AUG, to: AUG, thumbs: [thumb('B')] },
    { id: 'c3', count: 7, from: AUG, to: AUG, thumbs: [] },
  ] };
  const res = await call(env, body);
  assert.equal(res.status, 200);
  const out = await res.json();
  assert.equal(out.provider, 'workers');
  assert.equal(out.model, '@cf/meta/llama-3.3-70b-instruct-fp8-fast');
  assert.equal(vision.length, 3, 'c1: 2 miniaturi (prima și cea din mijloc), c2: 1, c3: 0');
  assert.ok(vision.every((v) => v.model === '@cf/meta/llama-3.2-11b-vision-instruct'));
  assert.match(vision[0].prompt, /Do not name or identify people/);
  assert.equal(text.length, 1);
  const input = text[0].input;
  assert.equal(input.response_format.type, 'json_schema');
  assert.deepEqual(input.response_format.json_schema.schema.properties.clusters.items.properties.id.enum, ['c1', 'c2', 'c3']);
  const content = input.messages.at(-1).content;
  assert.equal((content.match(/\[grup c1\] Descriere vizuală/g) || []).length, 2);
  assert.equal((content.match(/\[grup c2\] Descriere vizuală/g) || []).length, 1);
  assert.match(content, /NICIODATĂ nume de persoane/);
  assert.deepEqual(out.clusters.map((c) => [c.id, c.pastrare]), [['c1', 'da'], ['c2', 'nu'], ['c3', 'poate']]);
  assert.match(lastLog(media).what, /^AI clusters ok: workers\/@cf\/meta\/llama-3\.3-70b-instruct-fp8-fast · 1 încercare/);

  // Vederea pică pentru grupul c2 (ambele modele de vedere) → c2 nu e văzut: „nu” devine „poate”.
  resetBudgetCache();
  const env2 = { AI: { run: async (model, input) => {
    if (input.image) { if (input.image[3] !== 0) throw new Error('vision down'); return { description: 'a mountain trail' }; }
    return { response: JSON.stringify(reply) };
  } } };
  const out2 = await (await call(env2, body)).json();
  assert.deepEqual(out2.clusters.map((c) => [c.id, c.pastrare]), [['c1', 'da'], ['c2', 'poate'], ['c3', 'poate']]);
});

test('clusters: Gemini pică pe toate modelele → Workers preia (jurnalul arată rezervele); totul pică → 422 cu `detalii` și rândul „AI clusters:”; fără furnizor → 503', async () => {
  const media = new Bucket();
  const reply = { clusters: [{ id: 'a', nume: 'Munte', tema: 'Munte', categorie: 'Natură', pastrare: 'da', motiv: 'Munți.' }] };
  mockFetch(() => ok({ error: { code: 500, message: 'Internal error', status: 'INTERNAL' } }, 500));
  const env = { GEMINI_API_KEY: 'g-secret', MEDIA: media, AI: { run: async (m, input) => (input.image ? { description: 'mountains' } : { response: JSON.stringify(reply) }) } };
  const body = { clusters: [{ id: 'a', count: 3, thumbs: [JPEG] }] };
  const out = await (await call(env, body)).json();
  assert.equal(out.provider, 'workers');
  const line = lastLog(media).what;
  assert.match(line, /^AI clusters ok: workers\/.+ · \d+ încercări · .+ · înainte: gemini\/gemini-[\w.-]+ 500/);
  assert.ok(line.length <= 180);

  resetBudgetCache();
  const media2 = new Bucket();
  const res = await call({ GEMINI_API_KEY: 'g-secret', MEDIA: media2 }, body);
  assert.equal(res.status, 422);
  const err = await res.json();
  assert.equal(err.error, 'AI-ul n-a putut numi grupurile. Mai încearcă.');
  assert.match(err.detalii, /gemini/);
  assert.ok(err.detalii.length <= 600);
  assert.ok(!JSON.stringify(err).includes('secret'));
  assert.match(lastLog(media2).what, /^AI clusters: /);
  assert.equal(lastLog(media2).status, 422);

  const res2 = await call({ AI: { run: async (m, input) => { if (input.image) throw new Error('vision down'); return { response: '{}' }; } } }, body);
  assert.equal(res2.status, 422, 'Workers fără nicio descriere: nu numim orbește');
  assert.match((await res2.json()).detalii, /modelele de vedere/);
  assert.equal((await call({}, body)).status, 503);
});

test('diag: „organize-clusters”: „ok”', async () => {
  const request = new Request('https://api.forja.test/v1/diag?models=0');
  const d = await (await route(request, {}, new URL(request.url), null, async () => null)).json();
  assert.equal(d['organize-clusters'], 'ok');
});

test('clusters, unități: date (ms, secunde, ISO, ora României sau tzOffsetMin), perioade, nume de dosar sigure, categorii, nume de rezervă', () => {
  assert.equal(toMs(AUG), AUG);
  assert.equal(toMs(AUG / 1000), AUG, 'secunde');
  assert.equal(toMs(String(AUG)), AUG);
  assert.equal(toMs('2023-08-12T10:00:00Z'), AUG);
  assert.equal(toMs('ieri'), null);
  assert.equal(toMs(Date.now() + 10 * 86400_000), null, 'în viitor');
  const lateAug = Date.UTC(2023, 7, 31, 22, 30); // 1 sep 01:30 în România
  assert.equal(parseClustersRequest({ clusters: [{ id: 'a', from: lateAug, to: lateAug }] }).clusters[0].period, 'sep 2023');
  assert.equal(parseClustersRequest({ tzOffsetMin: 0, clusters: [{ id: 'a', from: lateAug, to: lateAug }] }).clusters[0].period, 'aug 2023');
  const multi = parseClustersRequest({ clusters: [{ id: 'a', from: '2022-12-20', to: '2023-01-03', loc: '  Brașov\n', hints: ['x', 7, '', 'y'.repeat(99)] }] }).clusters[0];
  assert.equal(multi.period, 'dec 2022 – ian 2023');
  assert.equal(fallbackName(multi), 'Diverse · 2022–2023');
  assert.equal(multi.loc, 'Brașov');
  assert.deepEqual(multi.hints, ['x', 'y'.repeat(40)]);
  assert.equal(multi.count, 0);
  assert.equal(parseClustersRequest({ clusters: [{ id: 'a' }] }).clusters[0].period, null);
  assert.equal(fallbackName(parseClustersRequest({ clusters: [{ id: 'a' }] }).clusters[0]), 'Diverse');
  assert.equal(cleanFolderName('Munte - Bucegi'), 'Munte · Bucegi');
  assert.equal(cleanFolderName('Munte: Bucegi'), 'Munte · Bucegi');
  assert.equal(cleanFolderName('nuntă · aug. 2023'), 'Nuntă · aug 2023');
  assert.equal(cleanFolderName('.ascuns'), 'Ascuns');
  assert.equal(cleanFolderName('Acte/Facturi*?'), 'Acte Facturi');
  assert.equal(cleanFolderName('Excursie · Munții Făgăraș și Bucegi'), 'Excursie · Munții');
  assert.equal(cleanFolderName('Aniversare surpriză la bunici acasă'), 'Aniversare surpriză');
  assert.equal(cleanFolderName('🎉🎉'), '');
  assert.equal(cleanFolderName('iPhone vechi'), 'iPhone vechi', 'nume de marcă neatins');
  assert.equal(cleanFolderName('ștrand · iul 2024'), 'Ștrand · iul 2024');
  assert.equal(cleanFolderName(null), '');
  assert.equal(normalizeCategory('calatorie'), 'Călătorii');
  assert.equal(normalizeCategory('NATURA'), 'Natură');
  assert.equal(normalizeCategory('Documente'), 'Acte');
  assert.equal(normalizeCategory('Animal'), 'Animale');
  assert.equal(normalizeCategory('altceva'), 'Diverse');
  const clusters = parseClustersRequest({ clusters: [{ id: 'a', from: AUG, thumbs: [JPEG] }, { id: 'b', from: AUG, thumbs: [JPEG] }] }).clusters;
  const r = sanitizeClusters({ clusters: [{ id: 'zz', nume: 'Inventat', pastrare: 'da' }, { id: 'b', nume: '', tema: 'Sport', pastrare: 'nu', motiv: 'Mișcate.' }] }, clusters, { seen: new Set(['a']) });
  assert.deepEqual(r[0], { id: 'a', nume: 'Diverse · aug 2023', tema: 'Diverse', categorie: 'Diverse', pastrare: 'poate', motiv: '' }, 'id inventat ignorat, grupul lipsă primește rezerva');
  assert.deepEqual([r[1].nume, r[1].tema, r[1].pastrare], ['Sport · aug 2023', 'Sport', 'poate'], 'fără nume: tema + luna; nevăzut (Workers) → „poate”');
});
