// FORJA 4.3, pachetul A: Gemini acordat după sonda directă din 28.09 (GEMINI-PROBE-0928, cheia gratuită a Lanei):
// gândirea pe familie, 400 „thinking/invalid argument” → fără thinkingConfig, 503 „high demand” → o reîncercare după 1,5 s,
// 429 „limit: N, model: X” → răcire 65 s (memorie + R2), preferința pe sarcină peste lista descoperită, încercările în jurnal.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { gemini, geminiThinking, parseGeminiError, preferForTask, rankGeminiModels, geminiCooldown, geminiTiming, GEMINI_MODELS, GEMINI_CACHE_KEY, GEMINI_COOLDOWN_MS } from './ai-gemini.mjs';
import { visionJson, textJson, resetBudgetCache, diagProviders, triesSummary } from './ai-router.mjs';
import { MEAL_SCHEMA, SUMMARY_SCHEMA } from './ai-schemas.mjs';
import { route } from './worker.js';

const LIST = JSON.parse(readFileSync(new URL('./fixtures/gemini-models.json', import.meta.url), 'utf8'));
const geminiReply = (text) => ({ candidates: [{ content: { parts: [{ text }] }, finishReason: 'STOP' }] });
const ok = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const MEAL = { fel: 'Pește cu fasole', incredere: 'medie', componente: [{ nume: 'pește', grame: 180, kcal: 216, proteine: 40, carbo: 0, grasimi: 6 }] };
const IMG = [{ b64: 'AAAA', mime: 'image/jpeg' }];

// Mesajele reale din sondă (28.09, 07:19 UTC) — corpul de eroare Google are forma {error:{code, message, status, details?}}.
const PROBE_429 = 'Quota exceeded for metric generate_content_free_tier_requests, limit: 5, model: gemini-3.8-flash';
const googleError = (code, message, status, details) => ok({ error: { code, message, status, ...(details ? { details } : {}) } }, code);
const quota429 = () => googleError(429, PROBE_429, 'RESOURCE_EXHAUSTED');
const highDemand = () => googleError(503, 'This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.', 'UNAVAILABLE');

function mockFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    const body = init.body && typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    calls.push({ url: String(url), method: init.method || 'GET', body, headers: init.headers || {} });
    if (String(url).includes('/v1beta/models?')) return ok(LIST);
    return handler(String(url), body, init);
  };
  return calls;
}
const gen = (calls) => calls.filter((c) => c.url.includes(':generateContent'));
const genModels = (calls) => gen(calls).map((c) => c.url.split('/models/')[1].split(':')[0]);

class Bucket {
  files = new Map();
  async put(key, value, opts = {}) { this.files.set(key, { text: typeof value === 'string' ? value : new TextDecoder().decode(value), customMetadata: opts.customMetadata || {} }); }
  async get(key) { const f = this.files.get(key); return f ? { key, customMetadata: f.customMetadata, text: async () => f.text } : null; }
  async head(key) { return this.files.has(key) ? { key } : null; }
  async delete(key) { this.files.delete(key); }
}
const realSleep = geminiTiming.sleep;
test.beforeEach(() => { resetBudgetCache(); geminiTiming.sleep = realSleep; });

test('gândirea pe familie (concluzia 1 din sondă): 3.x flash → low, 3.x flash-lite → minimal, 2.5 flash → buget 0, pro/preview → low, -latest → fără', () => {
  for (const m of ['gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash', 'gemini-4.0-flash', 'gemini-3-flash-preview', 'gemini-omni-1.1-flash', 'gemini-omni-flash-preview']) assert.deepEqual(geminiThinking(m), { thinkingLevel: 'low' }, m);
  for (const m of ['gemini-3.5-flash-lite', 'gemini-3.1-flash-lite']) assert.deepEqual(geminiThinking(m), { thinkingLevel: 'minimal' }, m);
  for (const m of ['gemini-2.5-flash', 'gemini-2.5-flash-lite', 'gemini-2.5-flash-preview-05-20']) assert.deepEqual(geminiThinking(m), { thinkingBudget: 0 }, m);
  for (const m of ['gemini-3.1-pro-preview', 'gemini-2.5-pro', 'gemini-exp-1206']) assert.deepEqual(geminiThinking(m), { thinkingLevel: 'low' }, m);
  for (const m of ['gemini-flash-latest', 'gemini-flash-lite-latest', 'gemini-pro-latest', 'gemini-2.5-flash-native-audio-latest', 'gemini-2.0-flash', 'gemini-1.5-flash', 'gemini-3.5-transcribe', '']) assert.equal(geminiThinking(m), null, m);
});

test('cererea trimisă: meal → 3.8-flash cu thinkingLevel „low”; organize-clusters → 3.5-flash-lite cu „minimal” (fără loc în plus); alias -latest → fără thinkingConfig', async () => {
  const calls = mockFetch(() => ok(geminiReply(JSON.stringify({ ...MEAL, summary: 'ok' }))));
  await visionJson({ GEMINI_API_KEY: 'g' }, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA, maxTokens: 3000 });
  let body = gen(calls)[0].body;
  assert.ok(gen(calls)[0].url.includes('/gemini-3.8-flash:generateContent'));
  assert.deepEqual(body.generationConfig.thinkingConfig, { thinkingLevel: 'low' });
  assert.ok(body.generationConfig.maxOutputTokens > 3000, 'la „low” tokenii de gândire au loc în plus');
  await visionJson({ GEMINI_API_KEY: 'g' }, { task: 'organize-clusters', prompt: 'x JSON', images: IMG, schema: SUMMARY_SCHEMA, maxTokens: 1500 });
  body = gen(calls)[1].body;
  assert.ok(gen(calls)[1].url.includes('/gemini-3.5-flash-lite:generateContent'));
  assert.deepEqual(body.generationConfig.thinkingConfig, { thinkingLevel: 'minimal' });
  assert.equal(body.generationConfig.maxOutputTokens, 1500);
  await textJson({ GEMINI_API_KEY: 'g', GEMINI_MODEL: 'gemini-flash-latest' }, { task: 's', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
  body = gen(calls)[2].body;
  assert.ok(gen(calls)[2].url.includes('/gemini-flash-latest:generateContent'));
  assert.equal(body.generationConfig.thinkingConfig, undefined, 'aliasurile acceptă implicitul');
});

test('400 care pomenește gândirea (mesajul din sondă) → o reîncercare pe ACELAȘI model fără thinkingConfig; modelul e ținut minte (fără încă un 400 la cererea următoare)', async () => {
  const env = { GEMINI_API_KEY: 'g' };
  let calls = mockFetch((url, body) => body.generationConfig.thinkingConfig ? googleError(400, 'Thinking level LOW is not supported for this model.', 'INVALID_ARGUMENT') : ok(geminiReply(JSON.stringify(MEAL))));
  const r = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r.model, 'gemini-3.8-flash');
  assert.deepEqual(genModels(calls), ['gemini-3.8-flash', 'gemini-3.8-flash']);
  assert.ok(gen(calls)[0].body.generationConfig.thinkingConfig);
  assert.equal(gen(calls)[1].body.generationConfig.thinkingConfig, undefined);
  assert.ok(gen(calls)[1].body.generationConfig.responseSchema, 'schema rămâne: 400-ul era despre gândire');
  calls = mockFetch((url, body) => body.generationConfig.thinkingConfig ? googleError(400, 'Thinking level LOW is not supported for this model.', 'INVALID_ARGUMENT') : ok(geminiReply(JSON.stringify(MEAL))));
  await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(gen(calls).length, 1, 'a doua cerere pleacă direct fără thinkingConfig');
  assert.deepEqual((await gemini.catalogInfo(env)).faraGandire, ['gemini-3.8-flash']);
});

test('400 „invalid argument” (3.5-flash-lite cu thinkingBudget 0 în sondă) → fără thinkingConfig; 400 fără legătură (poza) → direct modelul următor; cheie invalidă → Gemini oprit', async () => {
  const env = { GEMINI_API_KEY: 'g' };
  let calls = mockFetch((url, body) => body.generationConfig.thinkingConfig ? googleError(400, 'Request contains an invalid argument.', 'INVALID_ARGUMENT') : ok(geminiReply('{"summary":"ok"}')));
  let r = await visionJson(env, { task: 'organize', prompt: 'x JSON', images: IMG, schema: SUMMARY_SCHEMA });
  assert.equal(r.model, 'gemini-3.5-flash-lite');
  assert.deepEqual(genModels(calls), ['gemini-3.5-flash-lite', 'gemini-3.5-flash-lite']);
  resetBudgetCache();
  calls = mockFetch((url) => url.includes('gemini-3.8-flash:') ? googleError(400, 'Unable to process input image. Please retry or report in https://developers.generativeai.google/guide/troubleshooting', 'INVALID_ARGUMENT') : ok(geminiReply(JSON.stringify(MEAL))));
  r = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.deepEqual(genModels(calls), ['gemini-3.8-flash', 'gemini-3.5-flash-lite'], 'nicio reîncercare inutilă (cota e 5/min); următorul = flash-lite rapid');
  assert.match(r.attempts[0], /gemini a răspuns cu 400 \(cerere respinsă\)$/);
  assert.ok(!r.attempts.join(' ').includes('Unable to process'), 'textul Google nu ajunge în încercări');
  resetBudgetCache();
  calls = mockFetch((url) => url.includes('googleapis') ? googleError(400, 'API key not valid. Please pass a valid API key.', 'INVALID_ARGUMENT', [{ '@type': 'type.googleapis.com/google.rpc.ErrorInfo', reason: 'API_KEY_INVALID' }]) : ok({ choices: [{ message: { content: JSON.stringify(MEAL) }, finish_reason: 'stop' }] }));
  r = await visionJson({ GEMINI_API_KEY: 'g', OPENAI_API_KEY: 'o' }, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r.provider, 'openai');
  assert.equal(genModels(calls).length, 1, 'cheie respinsă: restul modelelor Gemini nu mai sunt încercate');
  assert.match(r.attempts[0], /\(cheie respinsă\)/);
});

test('503 „high demand” → o reîncercare pe același model după 1,5 s; a doua oară → modelul următor (fără răcire, fără retragere); fără timp → fără reîncercare', async () => {
  const slept = [];
  geminiTiming.sleep = async (ms) => { slept.push(ms); };
  const bucket = new Bucket();
  const env = { GEMINI_API_KEY: 'g', RECORDS: bucket };
  let n = 0;
  let calls = mockFetch((url) => (url.includes('gemini-3.8-flash:') && ++n === 1 ? highDemand() : ok(geminiReply(JSON.stringify(MEAL)))));
  let r = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r.model, 'gemini-3.8-flash');
  assert.deepEqual(genModels(calls), ['gemini-3.8-flash', 'gemini-3.8-flash']);
  assert.deepEqual(slept, [1500]);
  assert.deepEqual(r.tries, [{ provider: 'gemini', model: 'gemini-3.8-flash', code: 'ok' }], 'reîncercarea e în interiorul aceluiași model');

  slept.length = 0;
  calls = mockFetch((url) => (url.includes('gemini-3.8-flash:') ? highDemand() : ok(geminiReply(JSON.stringify(MEAL)))));
  r = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r.model, 'gemini-3.5-flash-lite', 'la mese, după 3.8-flash ocupat vine flash-lite (secunde), nu alt flash lent');
  assert.deepEqual(genModels(calls), ['gemini-3.8-flash', 'gemini-3.8-flash', 'gemini-3.5-flash-lite']);
  assert.deepEqual(slept, [1500]);
  assert.match(r.attempts[0], /gemini\/gemini-3\.8-flash: gemini a răspuns cu 503 \(ocupat; și reîncercarea după 1,5 s\)/);
  assert.equal(await geminiCooldown(env, 'gemini-3.8-flash'), null, '503 nu pune în răcire');
  assert.ok(!JSON.parse(bucket.files.get(GEMINI_CACHE_KEY).text).retired['gemini-3.8-flash'], '503 nu retrage');
  assert.equal(triesSummary({ ...r, ms: 21_400 }), 'gemini/gemini-3.5-flash-lite · 2 încercări · 21 s · înainte: gemini-3.8-flash 503');

  // Buget de 3 s pe furnizor: nu mai încape pauza de 1,5 s + un apel → fiecare model o singură dată.
  slept.length = 0;
  calls = mockFetch(() => highDemand());
  await assert.rejects(() => visionJson({ GEMINI_API_KEY: 'g' }, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA, timeoutMs: 3000 }));
  assert.deepEqual(slept, []);
  assert.equal(new Set(genModels(calls)).size, genModels(calls).length, 'fără reîncercări');
});

test('parseGeminiError: mesajul exact din sondă, forma completă Google (QuotaFailure + RetryInfo), cota pe zi, 503, 400-urile', () => {
  let e = parseGeminiError(429, { error: { code: 429, message: PROBE_429, status: 'RESOURCE_EXHAUSTED' } });
  assert.deepEqual(e.quota, [{ limit: 5, model: 'gemini-3.8-flash', perDay: false }]);
  assert.equal(e.reason, 'quota');
  e = parseGeminiError(429, { error: { code: 429, status: 'RESOURCE_EXHAUSTED',
    message: 'You exceeded your current quota, please check your plan and billing details. For more information on this error, head to: https://ai.google.dev/gemini-api/docs/rate-limits.\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 5, model: gemini-3.8-flash\nPlease retry in 38.919426178s.',
    details: [{ '@type': 'type.googleapis.com/google.rpc.QuotaFailure', violations: [{ quotaMetric: 'generativelanguage.googleapis.com/generate_content_free_tier_requests', quotaId: 'GenerateRequestsPerMinutePerProjectPerModel-FreeTier', quotaDimensions: { location: 'global', model: 'gemini-3.8-flash' }, quotaValue: '5' }] }, { '@type': 'type.googleapis.com/google.rpc.RetryInfo', retryDelay: '38s' }] } });
  assert.deepEqual(e.quota, [{ limit: 5, model: 'gemini-3.8-flash', perDay: false }]);
  assert.equal(e.retryMs, 38_000);
  e = parseGeminiError(429, { error: { code: 429, message: 'Quota exceeded', details: [{ violations: [{ quotaId: 'GenerateRequestsPerDayPerProjectPerModel-FreeTier', quotaDimensions: { model: 'gemini-3.5-flash' }, quotaValue: '20' }] }] } });
  assert.deepEqual(e.quota, [{ limit: 20, model: 'gemini-3.5-flash', perDay: true }]);
  assert.equal(parseGeminiError(429, { error: 'rate' }).quota.length, 0, 'fără mesajul Google: nicio cotă ghicită');
  e = parseGeminiError(429, { error: { code: 429, message: 'You exceeded your current quota.\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_input_token_count, limit: 250000, model: gemini-3.8-flash\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 5, model: gemini-3.8-flash' } });
  assert.deepEqual(e.quota, [{ limit: 5, model: 'gemini-3.8-flash', perDay: false }], 'metrica de cereri are prioritate față de tokeni');
  assert.equal(parseGeminiError(503, { error: { code: 503, message: 'This model is currently experiencing high demand.', status: 'UNAVAILABLE' } }).reason, 'high-demand');
  assert.equal(parseGeminiError(400, { error: { code: 400, message: 'Thinking level MINIMAL is not supported for this model.', status: 'INVALID_ARGUMENT' } }).reason, 'thinking');
  assert.equal(parseGeminiError(400, { error: { code: 400, message: 'Request contains an invalid argument.', status: 'INVALID_ARGUMENT' } }).reason, 'invalid-argument');
  assert.equal(parseGeminiError(400, { error: { code: 400, message: '* GenerateContentRequest.generation_config.response_schema.properties: should be non-empty for OBJECT type', status: 'INVALID_ARGUMENT' } }).reason, 'schema');
  assert.equal(parseGeminiError(400, { error: 'bad config' }).reason, 'unknown');
  assert.equal(parseGeminiError(400, null).reason, 'unknown');
});

test('429 cu mesajul din sondă → gemini-3.8-flash în răcire 65 s (memorie + R2 ai-cooldown/…, cu ttl), NU retras; cererile următoare îl sar fără apel, alt izolat citește R2, după 65 s revine', async () => {
  const bucket = new Bucket();
  const env = { GEMINI_API_KEY: 'g', RECORDS: bucket };
  const realNow = Date.now;
  let clock = realNow();
  Date.now = () => clock;
  try {
    const handler = (url) => (url.includes('gemini-3.8-flash:') ? quota429() : ok(geminiReply(JSON.stringify(MEAL))));
    let calls = mockFetch(handler);
    const t0 = clock;
    const r = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
    assert.equal(r.provider, 'gemini');
    assert.equal(r.model, 'gemini-3.5-flash-lite', '429 → modelul următor (la mese: flash-lite), nu alt furnizor');
    assert.deepEqual(genModels(calls), ['gemini-3.8-flash', 'gemini-3.5-flash-lite']);
    assert.match(r.attempts[0], /gemini a răspuns cu 429 \(cotă 5 pe gemini-3\.8-flash; în răcire 65 s\)/);
    assert.equal(GEMINI_COOLDOWN_MS, 65_000);
    const saved = bucket.files.get('ai-cooldown/gemini-3.8-flash.json');
    assert.ok(saved, 'răcirea e scrisă în R2');
    assert.equal(saved.customMetadata.ttl, '65000');
    assert.deepEqual([JSON.parse(saved.text).until, JSON.parse(saved.text).limit], [t0 + 65_000, 5]);
    const catalog = JSON.parse(bucket.files.get(GEMINI_CACHE_KEY).text);
    assert.ok(!catalog.retired['gemini-3.8-flash'], '429 nu retrage modelul');
    assert.ok((await gemini.models(env, { task: 'meal' })).includes('gemini-3.8-flash'), 'rămâne în listă, doar sărit cât e în răcire');

    clock += 10_000;
    calls = mockFetch(handler);
    const r2 = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
    assert.deepEqual(genModels(calls), ['gemini-3.5-flash-lite'], 'în răcire: niciun apel la 3.8-flash');
    assert.ok(r2.attempts.some((a) => /gemini\/gemini-3\.8-flash: în răcire după 429 \(limită 5\), încă 55 s \(sărit\)/.test(a)), r2.attempts.join(' | '));
    assert.equal((await gemini.catalogInfo(env)).racire['gemini-3.8-flash'].secunde, 55);

    resetBudgetCache(); // alt izolat: memoria e goală, R2 are răcirea
    clock += 20_000;
    calls = mockFetch(handler);
    await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
    assert.deepEqual(genModels(calls), ['gemini-3.5-flash-lite'], 'alt izolat: răcirea vine din R2');

    clock = t0 + 65_000 + 1000;
    calls = mockFetch(() => ok(geminiReply(JSON.stringify(MEAL))));
    const r4 = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
    assert.equal(r4.model, 'gemini-3.8-flash', 'după 65 s modelul revine');
  } finally { Date.now = realNow; }
});

test('429 pe un alias care numește alt model (flash-latest → gemini-3.8-flash): amândouă în răcire; 429 fără „limit/model” → doar modelul următor, fără răcire; cota pe zi → 1 h', async () => {
  const env = { GEMINI_API_KEY: 'g' };
  mockFetch(() => quota429());
  await assert.rejects(() => gemini.generate(env, { model: 'gemini-flash-latest', prompt: 'p' }), /429 \(cotă 5 pe gemini-3\.8-flash; în răcire 65 s\)/);
  assert.ok(await geminiCooldown(env, 'gemini-3.8-flash'));
  assert.ok(await geminiCooldown(env, 'gemini-flash-latest'));
  resetBudgetCache();
  const calls = mockFetch((url) => (url.includes('gemini-3.8-flash:') ? ok({ error: 'rate' }, 429) : ok(geminiReply('{"summary":"ok"}'))));
  const r = await textJson(env, { task: 'sleep-summary', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
  assert.equal(r.model, 'gemini-3.5-flash-lite');
  assert.equal(await geminiCooldown(env, 'gemini-3.8-flash'), null, 'fără mesajul Google nu inventăm o răcire');
  assert.equal(genModels(calls).length, 2);
  resetBudgetCache();
  mockFetch(() => googleError(429, 'Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.5-flash', 'RESOURCE_EXHAUSTED', [{ '@type': 'type.googleapis.com/google.rpc.QuotaFailure', violations: [{ quotaId: 'GenerateRequestsPerDayPerProjectPerModel-FreeTier', quotaDimensions: { model: 'gemini-3.5-flash' }, quotaValue: '20' }] }]));
  await assert.rejects(() => gemini.generate(env, { model: 'gemini-3.5-flash', prompt: 'p' }), /cotă 20 pe gemini-3\.5-flash pe zi; în răcire 3600 s/);
  const cd = await geminiCooldown(env, 'gemini-3.5-flash');
  assert.ok(cd.until - Date.now() > 59 * 60_000);
});

test('/v1/meal: toate modelele Gemini la 429 (cota pe minut) → 422 cu „ocupat acum”, nu „limită zilnică”; cererea următoare nu mai bate la modelele în răcire', async () => {
  const env = { GEMINI_API_KEY: 'g' };
  const calls = mockFetch((url) => { const m = url.split('/models/')[1].split(':')[0]; return googleError(429, `Quota exceeded for metric generate_content_free_tier_requests, limit: 5, model: ${m}`, 'RESOURCE_EXHAUSTED'); });
  const call = () => { const request = new Request('https://api.forja.test/v1/meal', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ image: '/9j/' + 'A'.repeat(200) }) }); return route(request, env, new URL(request.url), null, async () => 'user1'); };
  const res = await call();
  assert.equal(res.status, 422);
  const out = await res.json();
  assert.equal(out.error, 'Serverul AI e ocupat acum. Mai încearcă peste un minut.');
  assert.ok(!/!/.test(out.error));
  const first = gen(calls).length;
  assert.equal(first, rankGeminiModels(LIST.models).text.length, 'fiecare model o dată');
  await call();
  assert.equal(gen(calls).length, first, 'a doua cerere: toate sunt în răcire, niciun apel');
});

test('preferința pe sarcină, ca sortare peste lista DESCOPERITĂ a cheii Lanei (fixture 28.09)', async () => {
  const env = { GEMINI_API_KEY: 'g' };
  mockFetch(() => ok({}));
  const discovered = rankGeminiModels(LIST.models).text;
  const meal = ['gemini-3.8-flash', 'gemini-3.5-flash-lite', 'gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash', 'gemini-flash-latest', 'gemini-3.1-flash-lite', 'gemini-flash-lite-latest',
    'gemini-2.5-flash', 'gemini-omni-1.1-flash', 'gemini-2.5-flash-lite', 'gemini-3.1-pro-preview', 'gemini-2.5-pro', 'gemini-3-flash-preview', 'gemini-omni-flash-preview', 'gemini-pro-latest'];
  const bulk = ['gemini-3.5-flash-lite', 'gemini-3.1-flash-lite', 'gemini-flash-lite-latest', 'gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash', 'gemini-flash-latest',
    'gemini-2.5-flash', 'gemini-omni-1.1-flash', 'gemini-2.5-flash-lite', 'gemini-3.1-pro-preview', 'gemini-2.5-pro', 'gemini-3-flash-preview', 'gemini-omni-flash-preview', 'gemini-pro-latest'];
  assert.deepEqual(await gemini.models(env, { task: 'meal' }), meal, 'mese: 3.8, apoi flash-lite-ul rapid, apoi 3.7 → 3.6 → 3.5 → flash-latest, apoi restul');
  assert.deepEqual(await gemini.models(env, { task: 'sleep-summary' }), meal);
  assert.deepEqual(await gemini.models(env, { task: 'organize' }), bulk, 'curățenie: 3.5-lite → 3.1-lite → flash-lite-latest, apoi flash-urile');
  assert.deepEqual(await gemini.models(env, { task: 'organize-clusters' }), bulk);
  const audio = await gemini.models(env, { audio: true, task: 'sleep-analyze' });
  assert.deepEqual(audio.slice(0, 7), ['gemini-omni-1.1-flash', 'gemini-2.5-flash-native-audio-latest', 'gemini-3.8-flash', 'gemini-3.7-flash', 'gemini-3.6-flash', 'gemini-3.5-flash', 'gemini-flash-latest']);
  assert.deepEqual((await gemini.models(env, { audio: true, task: 'transcribe' })).slice(0, 2), ['gemini-3.5-transcribe', 'gemini-omni-1.1-flash']);
  assert.deepEqual(await gemini.models(env, { task: 'altceva' }), discovered, 'altă sarcină: ordinea descoperită, neschimbată');
  for (const list of [meal, bulk]) assert.deepEqual([...list].sort(), [...discovered].sort(), 'doar o sortare: nimic adăugat, nimic scos');
  assert.deepEqual((await gemini.models({ ...env, GEMINI_MODEL: 'gemini-3.6-flash' }, { task: 'organize' }))[0], 'gemini-3.6-flash', 'GEMINI_MODEL trece primul');
  assert.deepEqual(preferForTask(GEMINI_MODELS, { task: 'organize-clusters' }), ['gemini-3.8-flash', 'gemini-3.5-flash', 'gemini-flash-latest', 'gemini-3.1-pro-preview'], 'lista statică, fără flash-lite: ordinea rămâne');
  const info = (await diagProviders(env)).models.gemini;
  assert.deepEqual(info.peSarcina.meal.slice(0, 5), meal.slice(0, 5));
  assert.deepEqual(info.peSarcina['organize-clusters'].slice(0, 3), bulk.slice(0, 3));
  assert.equal(info.peSarcina.audio[0], 'gemini-omni-1.1-flash');
  assert.deepEqual(info.ordine, discovered, 'ordine = lista descoperită');
});

test('încercările ajung în rezultat (pentru jurnal) și în eroare; rezumatul e compact, fără conținut', async () => {
  let calls = mockFetch((url) => (url.includes('gemini-3.5-flash-lite:') ? ok({}, 500) : url.includes('gemini-3.1-flash-lite:') ? ok(geminiReply('nu e json')) : ok(geminiReply('{"summary":"ok"}'))));
  const r = await textJson({ GEMINI_API_KEY: 'g' }, { task: 'organize', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
  assert.equal(r.model, 'gemini-flash-lite-latest');
  assert.deepEqual(r.tries.map((t) => [t.model, t.code]), [['gemini-3.5-flash-lite', 500], ['gemini-3.1-flash-lite', 'json'], ['gemini-flash-lite-latest', 'ok']]);
  const line = triesSummary({ ...r, ms: 2900 });
  assert.equal(line, 'gemini/gemini-flash-lite-latest · 3 încercări · 2,9 s · înainte: gemini-3.5-flash-lite 500, gemini-3.1-flash-lite json');
  assert.equal(triesSummary({ provider: 'workers', model: 'm', ms: 400, tries: [{ provider: 'gemini', model: 'gemini-3.8-flash', code: 429 }, { provider: 'workers', model: 'm', code: 'ok' }] }), 'workers/m · 2 încercări · 0,4 s · înainte: gemini/gemini-3.8-flash 429');
  calls = mockFetch(() => ok({}, 500));
  await assert.rejects(() => textJson({ GEMINI_API_KEY: 'g' }, { task: 'organize', prompt: 'p JSON', schema: SUMMARY_SCHEMA }), (e) => {
    assert.equal(e.tries.length, gen(calls).length);
    return true;
  });
});
