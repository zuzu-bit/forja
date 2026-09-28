// Pachetul „ai-fix”: modele Gemini descoperite dinamic (2.5 retras → 3.x), chei curățate și verificate în diag,
// rezerva Cloudflare cu JSON forțat, buget de timp pe toată cererea.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { rankGeminiModels, geminiCatalog, retireGeminiModel, gemini, GEMINI_MODELS, GEMINI_CACHE_KEY } from './ai-gemini.mjs';
import { visionJson, textJson, diagProviders, keyStatus, resetBudgetCache, providers } from './ai-router.mjs';
import { groq } from './ai-groq.mjs';
import { workersJsonSchema } from './ai-workers.mjs';
import { readKey } from './ai-common.mjs';
import { MEAL_SCHEMA, SUMMARY_SCHEMA, normalizeMeal } from './ai-schemas.mjs';

const LIST = JSON.parse(readFileSync(new URL('./fixtures/gemini-models.json', import.meta.url), 'utf8'));
const NONJSON = readFileSync(new URL('./fixtures/workers-meal-nonjson.txt', import.meta.url), 'utf8');
const geminiReply = (text) => ({ candidates: [{ content: { parts: [{ text }] }, finishReason: 'STOP' }] });
const openaiReply = (text) => ({ choices: [{ message: { content: text }, finish_reason: 'stop' }] });
const ok = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const MEAL = { fel: 'Pui cu orez', incredere: 'ridicată', componente: [{ nume: 'pui', grame: 150, kcal: 248, proteine: 46, carbo: 0, grasimi: 6 }] };
const IMG = [{ b64: 'AAAA', mime: 'image/jpeg' }];

/** fetch de test: jurnal complet (inclusiv GET /models), handler-ul primește url + corp parsat. */
function mockFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    const body = init.body && typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    calls.push({ url: String(url), method: init.method || 'GET', body, headers: init.headers || {} });
    return handler(String(url), body, init);
  };
  return calls;
}
const listCalls = (calls) => calls.filter((c) => c.url.includes('/v1beta/models?'));
const genModels = (calls) => calls.filter((c) => c.url.includes(':generateContent')).map((c) => c.url.split('/models/')[1].split(':')[0]);

/** R2 minimal: put/get/head/delete cu metadate. */
class Bucket {
  files = new Map();
  async put(key, value, opts = {}) { this.files.set(key, { text: typeof value === 'string' ? value : new TextDecoder().decode(value), customMetadata: opts.customMetadata || {} }); }
  async get(key) { const f = this.files.get(key); return f ? { key, customMetadata: f.customMetadata, text: async () => f.text } : null; }
  async head(key) { return this.files.has(key) ? { key } : null; }
  async delete(key) { this.files.delete(key); }
}
test.beforeEach(() => resetBudgetCache());

test('descoperire: filtru + ordine exact pe lista cheii Lanei (28.09): flash după versiune, flash-lite, pro, preview-uri, aliasuri; fără tts/image/embedding/robotics/computer-use/live/transcribe/native-audio', () => {
  const r = rankGeminiModels(LIST.models);
  assert.deepEqual(r.text, [
    'gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash', 'gemini-2.5-flash', 'gemini-omni-1.1-flash',
    'gemini-3.5-flash-lite', 'gemini-3.1-flash-lite', 'gemini-2.5-flash-lite',
    'gemini-3.1-pro-preview', 'gemini-2.5-pro',
    'gemini-3-flash-preview', 'gemini-omni-flash-preview',
    'gemini-flash-latest', 'gemini-flash-lite-latest', 'gemini-pro-latest',
  ]);
  assert.equal(r.audio[0], 'gemini-omni-1.1-flash', 'audio: omni (audio+video) primul');
  assert.equal(r.audio[1], 'gemini-2.5-flash-native-audio-latest');
  assert.equal(r.audio[2], 'gemini-3.8-flash', 'apoi flash-urile obișnuite');
  assert.equal(r.transcribe, 'gemini-3.5-transcribe');
  for (const bad of ['tts', 'image', 'embedding', 'robotics', 'computer-use', 'live', 'transcribe', 'native-audio', 'imagen', 'gemma']) assert.ok(!r.text.some((n) => n.includes(bad)), bad + ' nu e în lista de text');
  assert.deepEqual(rankGeminiModels(null).text, []);
  assert.deepEqual(rankGeminiModels([{ name: 'models/gemini-9-flash' }]).text, [], 'fără generateContent nu intră');
});

test('catalog: GET /models cu x-goog-api-key (pageSize=100), pus în R2 `ai-models/gemini.json` cu ttl 24 h; a doua cerere citește cache-ul; pagini următoare urmate', async () => {
  const bucket = new Bucket();
  const env = { GEMINI_API_KEY: 'g-secret', RECORDS: bucket };
  const calls = mockFetch((url) => url.includes('pageToken=p2') ? ok({ models: [{ name: 'models/gemini-4.0-flash', supportedGenerationMethods: ['generateContent'] }] }) : ok({ ...LIST, nextPageToken: 'p2' }));
  const c = await geminiCatalog(env);
  assert.equal(c.source, 'discovery');
  assert.equal(c.text[0], 'gemini-4.0-flash', 'modelul din pagina a doua e cel mai nou');
  assert.equal(listCalls(calls).length, 2);
  assert.ok(calls[0].url.startsWith('https://generativelanguage.googleapis.com/v1beta/models?pageSize=100'));
  assert.equal(calls[0].headers['x-goog-api-key'], 'g-secret');
  const saved = bucket.files.get(GEMINI_CACHE_KEY);
  assert.ok(saved, 'catalogul e salvat în R2');
  assert.equal(saved.customMetadata.ttl, String(24 * 3600_000));
  assert.ok(Number(saved.customMetadata.at) > 0);
  assert.ok(!saved.text.includes('secret'));
  resetBudgetCache(); // alt izolat: memoria e goală, dar R2 are catalogul
  const again = await geminiCatalog(env);
  assert.equal(again.text[0], 'gemini-4.0-flash');
  assert.equal(listCalls(calls).length, 2, 'nicio nouă cerere /models cât cache-ul e valabil');
  assert.deepEqual(await gemini.models({ ...env, GEMINI_MODEL: 'gemini-3.5-flash' }), ['gemini-3.5-flash', ...again.text.filter((m) => m !== 'gemini-3.5-flash')], 'GEMINI_MODEL din env trece primul');
});

test('catalog: când descoperirea pică (rețea / 500 / răspuns fără listă) → lista statică, fără să arunce', async () => {
  const env = { GEMINI_API_KEY: 'g' };
  mockFetch(() => ok({ error: 'boom' }, 500));
  let c = await geminiCatalog(env);
  assert.equal(c.source, 'static');
  assert.deepEqual(c.text, ['gemini-3.8-flash', 'gemini-3.5-flash', 'gemini-flash-latest', 'gemini-3.1-pro-preview']);
  assert.deepEqual(GEMINI_MODELS, c.text);
  resetBudgetCache();
  globalThis.fetch = async () => { throw new Error('ECONNRESET'); };
  c = await geminiCatalog(env);
  assert.equal(c.source, 'static');
  assert.match(c.eroare || c.error, /rețea/);
  resetBudgetCache();
  mockFetch(() => ok(geminiReply('nu e lista')));
  assert.equal((await geminiCatalog(env)).source, 'static');
});

test('404 pe un model = retras 24 h: routerul trece la următorul, cache-ul reține retragerea, cererea următoare nu-l mai încearcă', async () => {
  const bucket = new Bucket();
  const env = { GEMINI_API_KEY: 'g', RECORDS: bucket };
  const calls = mockFetch((url) => url.includes('/v1beta/models?') ? ok(LIST) : /gemini-3\.8-flash:|gemini-3\.7-flash:/.test(url) ? ok({ error: { code: 404, message: 'not found' } }, 404) : ok(geminiReply(JSON.stringify(MEAL))));
  const r = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r.model, 'gemini-3.6-flash');
  assert.deepEqual(genModels(calls), ['gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-3.6-flash']);
  assert.match(r.attempts[0], /gemini\/gemini-3\.8-flash: gemini a răspuns cu 404 \(model retras 24 h\)/);
  const saved = JSON.parse(bucket.files.get(GEMINI_CACHE_KEY).text);
  assert.ok(saved.retired['gemini-3.8-flash'] > Date.now() + 23 * 3600_000, 'retragerea e persistată, ~24 h');
  assert.ok(saved.retired['gemini-3.7-flash'] > Date.now());
  calls.length = 0;
  const r2 = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r2.model, 'gemini-3.6-flash');
  assert.deepEqual(genModels(calls), ['gemini-3.6-flash'], 'modelele retrase nu se mai încearcă');
  // Alt izolat: citește din R2 și retragerea rămâne.
  resetBudgetCache();
  assert.ok(!(await gemini.models(env)).includes('gemini-3.8-flash'));
  const info = await gemini.catalogInfo(env);
  assert.deepEqual(info.retrase.sort(), ['gemini-3.7-flash', 'gemini-3.8-flash']);
  // Retragerea expirată nu mai contează.
  const realNow = Date.now;
  Date.now = () => realNow() + 25 * 3600_000;
  try {
    resetBudgetCache();
    await retireGeminiModel(env, 'gemini-3.6-flash');
    const c = await geminiCatalog(env);
    assert.equal(c.source, 'discovery');
    assert.ok(!(await gemini.models(env)).includes('gemini-3.6-flash'));
  } finally { Date.now = realNow; }
});

test('audio: omni → native-audio → flash-uri; transcriere: gemini-3.5-transcribe primul', async () => {
  const env = { GEMINI_API_KEY: 'g' };
  mockFetch(() => ok(LIST));
  const audio = await gemini.models(env, { audio: true });
  assert.deepEqual(audio.slice(0, 3), ['gemini-omni-1.1-flash', 'gemini-2.5-flash-native-audio-latest', 'gemini-3.8-flash']);
  const tr = await gemini.models(env, { audio: true, task: 'transcribe' });
  assert.equal(tr[0], 'gemini-3.5-transcribe');
  assert.equal(tr[1], 'gemini-omni-1.1-flash');
  assert.ok(!(await gemini.models(env)).includes('gemini-3.5-transcribe'), 'transcribe nu e model de text/poze');
});

test('gemini: 400 la generateContent → reîncercare fără thinkingConfig, apoi fără responseSchema; abia apoi modelul următor', async () => {
  const env = { GEMINI_API_KEY: 'g' };
  const calls = mockFetch((url, body) => {
    if (url.includes('/v1beta/models?')) return ok(LIST);
    if (body.generationConfig.thinkingConfig || body.generationConfig.responseSchema) return ok({ error: 'bad config' }, 400);
    return ok(geminiReply(JSON.stringify(MEAL)));
  });
  const r = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r.model, 'gemini-3.8-flash');
  const gen = calls.filter((c) => c.url.includes(':generateContent'));
  assert.equal(gen.length, 3);
  assert.ok(gen[0].body.generationConfig.thinkingConfig && gen[0].body.generationConfig.responseSchema);
  assert.ok(!gen[1].body.generationConfig.thinkingConfig && gen[1].body.generationConfig.responseSchema);
  assert.ok(!gen[2].body.generationConfig.thinkingConfig && !gen[2].body.generationConfig.responseSchema);
  assert.equal(gen[2].body.generationConfig.responseMimeType, 'application/json');
});

test('chei: trim() la citire (spații, linii noi, ghilimele) — antetul primește cheia curată; goală = furnizor absent', async () => {
  assert.equal(readKey({ GROQ_API_KEY: ' \n"gsk_abc" \n' }, 'GROQ_API_KEY'), 'gsk_abc');
  assert.equal(readKey({ GROQ_API_KEY: "'x'" }, 'GROQ_API_KEY'), 'x');
  assert.equal(readKey({}, 'GROQ_API_KEY'), '');
  assert.equal(readKey({ GROQ_API_KEY: 42 }, 'GROQ_API_KEY'), '');
  assert.deepEqual(providers({ GEMINI_API_KEY: '  ', GROQ_API_KEY: '\n' }), []);
  const calls = mockFetch((url) => url.includes('/v1beta/models?') ? ok(LIST) : url.includes('googleapis') ? ok(geminiReply('{"summary":"ok"}')) : ok(openaiReply('{"summary":"ok"}')));
  await textJson({ GEMINI_API_KEY: ' "g-secret"\n' }, { task: 's', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
  assert.equal(calls.find((c) => c.url.includes(':generateContent')).headers['x-goog-api-key'], 'g-secret');
  await textJson({ GROQ_API_KEY: '\tgsk_q-secret \n' }, { task: 's', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
  assert.equal(calls.find((c) => c.url.includes('groq')).headers.authorization, 'Bearer gsk_q-secret');
});

test('diag: verificarea cheilor prin GET /models, o dată pe oră (cache R2) — groq 401 → „configured but rejected (401)”, fără chei în ieșire; ordinea Gemini descoperită', async () => {
  const bucket = new Bucket();
  const env = { GEMINI_API_KEY: 'g-secret', GROQ_API_KEY: 'q-secret-wrong', RECORDS: bucket };
  const calls = mockFetch((url) => url.includes('groq.com/openai/v1/models') ? ok({ error: { message: 'Invalid API Key' } }, 401) : url.includes('/v1beta/models?') ? ok(LIST) : ok({}));
  const d = await diagProviders(env);
  assert.equal(d.providers.groq, 'configured but rejected (401)');
  assert.equal(d.providers.gemini, 'configured');
  assert.equal(d.keys.groq.http, 401);
  assert.match(d.keys.groq.sfat, /gsk_/);
  assert.equal(d.keys.gemini.http, 200);
  assert.deepEqual(d.models.gemini.ordine.slice(0, 4), ['gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash']);
  assert.equal(d.models.gemini.sursa, 'discovery');
  assert.equal(calls.find((c) => c.url.includes('groq.com/openai/v1/models')).headers.authorization, 'Bearer q-secret-wrong');
  assert.ok(!JSON.stringify(d).includes('secret'), 'nicio cheie în diag');
  const groqChecks = () => calls.filter((c) => c.url.includes('groq.com/openai/v1/models')).length;
  assert.equal(groqChecks(), 1);
  await diagProviders(env);
  assert.equal(groqChecks(), 1, 'a doua verificare vine din memorie');
  resetBudgetCache();
  const saved = bucket.files.get('ai-keycheck/groq.json');
  assert.ok(saved && saved.customMetadata.ttl === String(3600_000), 'verificarea e salvată în R2 cu ttl 1 h');
  assert.ok(!saved.text.includes('secret'));
  const d2 = await diagProviders(env);
  assert.equal(groqChecks(), 1, 'alt izolat citește verificarea din R2');
  assert.equal(d2.providers.groq, 'configured but rejected (401)');
  // Rețea picată la verificare: rămâne „configured” (nu știm), fără cache fals.
  resetBudgetCache();
  globalThis.fetch = async () => { throw new Error('ECONNRESET'); };
  const s = await keyStatus({ GROQ_API_KEY: 'q' }, groq);
  assert.equal(s.status, 0);
  assert.equal((await diagProviders({ GROQ_API_KEY: 'q' })).providers.groq, 'configured');
});

test('workers: JSON forțat cu response_format json_schema (schema convertită); când runtime-ul îl respinge → prompt „doar JSON” + extractor tolerant pe un răspuns nestructurat (fixture) → masă validă', async () => {
  const inputs = [];
  const env = { AI: { run: async (model, input) => {
    if (input.image) return { description: 'tripe soup - 400 g' };
    inputs.push(input);
    if (input.response_format) throw new Error('AiError: 5006: response_format is not supported by this model');
    return { response: NONJSON };
  } } };
  const r = await visionJson(env, { task: 'meal', prompt: 'p JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r.provider, 'workers');
  assert.equal(r.json.fel, 'Ciorbă de burtă');
  assert.equal(inputs.length, 2, 'o cerere cu json_schema, una cu prompt „doar JSON”');
  assert.equal(inputs[0].response_format.type, 'json_schema');
  assert.equal(inputs[0].response_format.json_schema.name, 'raspuns');
  assert.equal(inputs[0].response_format.json_schema.schema.type, 'object');
  assert.deepEqual(inputs[0].response_format.json_schema.schema.required, MEAL_SCHEMA.required);
  assert.equal(inputs[1].response_format, undefined);
  assert.match(inputs[1].messages.at(-1).content, /Răspunde DOAR cu obiectul JSON cerut/);
  const meal = normalizeMeal(r.json, r.model);
  assert.equal(meal.total.kcal, 260);
  assert.equal(meal.versiune, 2);
  // Cu json_schema acceptat, `response` poate veni gata parsat (obiect): merge la fel.
  const env2 = { AI: { run: async (m, input) => (input.image ? { description: 'a bowl of soup, about 400 g' } : { response: MEAL }) } };
  const r2 = await visionJson(env2, { task: 'meal', prompt: 'p JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r2.json.fel, 'Pui cu orez');
  // Text fără niciun JSON → repararea (o dată) aduce JSON-ul.
  let n = 0;
  const env3 = { AI: { run: async (m, input) => (input.image ? { description: 'a bowl of soup, about 400 g' } : { response: ++n === 1 ? 'Nu pot să dau cifre exacte, dar pare o ciorbă.' : JSON.stringify(MEAL) }) } };
  const r3 = await visionJson(env3, { task: 'meal', prompt: 'p JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r3.json.fel, 'Pui cu orez');
  assert.equal(n, 2);
  // Conversia schemei: nullable → type cu null, const → enum, cuvinte necunoscute dispar.
  const js = workersJsonSchema({ type: 'object', properties: { a: { type: 'string', nullable: true, maxLength: 3 }, b: { const: 'x' }, c: { type: 'number', minimum: 0, weird: 1 } }, required: ['a'] });
  assert.deepEqual(js.properties.a.type, ['string', 'null']);
  assert.deepEqual(js.properties.b, { enum: ['x'] });
  assert.deepEqual(js.properties.c, { type: 'number', minimum: 0 });
});

test('buget de timp: totalTimeoutMs plafonează TOATĂ cererea (furnizorii rămași nu mai pornesc); a doua trecere se sare după un prim pas lent sau la Workers', async () => {
  const realNow = Date.now;
  let clock = realNow();
  Date.now = () => clock;
  try {
    const env = { GEMINI_API_KEY: 'g', GROQ_API_KEY: 'q', ANTHROPIC_API_KEY: 'a' };
    let calls = mockFetch((url) => {
      if (url.includes('/v1beta/models?')) return ok(LIST);
      clock += 30_000;
      return ok({ error: 'slow' }, 500);
    });
    const t0 = clock;
    await assert.rejects(() => visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA, twoPass: true, timeoutMs: 45_000, totalTimeoutMs: 90_000 }), (e) => {
      assert.ok(e.attempts.some((a) => /bugetul total de timp/.test(a)), e.attempts.join(' | '));
      return true;
    });
    assert.ok(clock - t0 <= 90_000, 'clientul nu așteaptă un lanț mort: ' + (clock - t0) + ' ms');
    assert.equal(calls.filter((c) => c.url.includes('anthropic')).length, 0, 'al treilea furnizor nu mai pornește');
    assert.equal(genModels(calls).length, 2, 'Gemini: 45 s = două modele lente');
    assert.equal(calls.filter((c) => c.url.includes('groq')).length, 1, 'Groq primește doar ce a mai rămas (30 s)');

    // Primul pas a durat 26 s → fără a doua trecere (ar depăși 90 s cu un verificator lent).
    resetBudgetCache();
    calls = mockFetch((url) => { if (url.includes('/v1beta/models?')) return ok(LIST); clock += 26_000; return ok(geminiReply(JSON.stringify(MEAL))); });
    let r = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA, twoPass: true, timeoutMs: 45_000, totalTimeoutMs: 90_000, twoPassMaxFirstPassMs: 25_000 });
    assert.equal(r.verified, false);
    assert.equal(genModels(calls).length, 1);
    resetBudgetCache();
    calls = mockFetch((url) => { if (url.includes('/v1beta/models?')) return ok(LIST); clock += 10_000; return ok(geminiReply(JSON.stringify(MEAL))); });
    r = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA, twoPass: true, timeoutMs: 45_000, totalTimeoutMs: 90_000, twoPassMaxFirstPassMs: 25_000 });
    assert.equal(r.verified, true, 'un prim pas rapid păstrează verificarea');
    assert.equal(genModels(calls).length, 2);
  } finally { Date.now = realNow; }
  // Workers în twoPassSkip: un singur apel de text, fără verificare.
  let textCalls = 0;
  const env = { AI: { run: async (m, input) => { if (input.image) return { description: 'a bowl of soup, about 400 g' }; textCalls++; return { response: JSON.stringify(MEAL) }; } } };
  const r = await visionJson(env, { task: 'meal', prompt: 'p JSON', images: IMG, schema: MEAL_SCHEMA, twoPass: true, twoPassSkip: ['workers'] });
  assert.equal(r.verified, false);
  assert.equal(textCalls, 1);
});
