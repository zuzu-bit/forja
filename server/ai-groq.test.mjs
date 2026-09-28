// Modele Groq descoperite dinamic (Llama 4 Scout/Maverick retrase → 404 în 28.09): euristici pe id, cache 24 h, 404 → retras, fără viziune → sărit la poze.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { rankGroqModels, groqCatalog, groq, GROQ_CACHE_KEY, GROQ_VISION_MODELS, GROQ_TEXT_MODEL, GROQ_WHISPER_MODEL } from './ai-groq.mjs';
import { visionJson, textJson, transcribe, diagProviders, resetBudgetCache } from './ai-router.mjs';
import { MEAL_SCHEMA } from './ai-schemas.mjs';

const LIST = JSON.parse(readFileSync(new URL('./fixtures/groq-models.json', import.meta.url), 'utf8'));
const NO_VISION = { object: 'list', data: ['llama-3.3-70b-versatile', 'llama-3.1-8b-instant', 'openai/gpt-oss-120b', 'qwen/qwen3-32b', 'whisper-large-v3-turbo', 'playai-tts', 'meta-llama/llama-guard-4-12b'].map((id) => ({ id })) };
const GEMINI_LIST = { models: [{ name: 'models/gemini-3.8-flash', supportedGenerationMethods: ['generateContent'] }] };
const openaiReply = (text) => ({ choices: [{ message: { content: text }, finish_reason: 'stop' }] });
const ok = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const MEAL = { fel: 'Pui cu orez', incredere: 'ridicată', componente: [{ nume: 'pui', grame: 150, kcal: 248, proteine: 46, carbo: 0, grasimi: 6 }] };
const IMG = [{ b64: 'AAAA', mime: 'image/jpeg' }];
const aiMock = (text = JSON.stringify(MEAL)) => ({ run: async (model, input) => (input.image ? { description: 'chicken - 150 g, rice - 100 g' } : { response: text }) });

function mockFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
    const body = init.body && typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    calls.push({ url: String(url), method: init.method || 'GET', body, headers: init.headers || {} });
    return handler(String(url), body, init);
  };
  return calls;
}
const groqModels = (calls) => calls.filter((c) => c.url.endsWith('/chat/completions')).map((c) => c.body.model);
const listCalls = (calls) => calls.filter((c) => c.url === 'https://api.groq.com/openai/v1/models').length;
class Bucket {
  files = new Map();
  async put(key, value, opts = {}) { this.files.set(key, { text: typeof value === 'string' ? value : new TextDecoder().decode(value), customMetadata: opts.customMetadata || {} }); }
  async get(key) { const f = this.files.get(key); return f ? { key, customMetadata: f.customMetadata, text: async () => f.text } : null; }
  async head(key) { return this.files.has(key) ? { key } : null; }
  async delete(key) { this.files.delete(key); }
}
test.beforeEach(() => resetBudgetCache());

test('euristici pe id: viziune în ordinea llama-4 → vision → gemma-3 → qwen vl → pixtral → mistral small 3; text llama-3.3-70b primul; whisper large-v3 înaintea turbo; tts/guard nu intră', () => {
  const r = rankGroqModels(LIST.data);
  assert.deepEqual(r.vision, ['meta-llama/llama-4-maverick-17b-128e-instruct', 'meta-llama/llama-4-scout-17b-16e-instruct', 'google/gemma-3-27b-it', 'qwen/qwen2.5-vl-72b-instruct', 'mistralai/mistral-small-3.2-24b-instruct']);
  assert.deepEqual(r.text, ['llama-3.3-70b-versatile', 'meta-llama/llama-4-maverick-17b-128e-instruct', 'meta-llama/llama-4-scout-17b-16e-instruct', 'qwen/qwen3-32b', 'openai/gpt-oss-120b', 'openai/gpt-oss-20b', 'moonshotai/kimi-k2-instruct', 'deepseek-r1-distill-llama-70b', 'groq/compound', 'groq/compound-mini']);
  assert.deepEqual(r.whisper, ['whisper-large-v3', 'whisper-large-v3-turbo', 'distil-whisper-large-v3-en']);
  assert.ok(!JSON.stringify(r).includes('guard') && !JSON.stringify(r).includes('tts'));
  assert.ok(!r.text.includes('allam-2-7b') && !r.vision.includes('allam-2-7b'), 'id necunoscut = nici text cunoscut, nici viziune');
  const none = rankGroqModels(NO_VISION.data);
  assert.deepEqual(none.vision, []);
  assert.equal(none.text[0], 'llama-3.3-70b-versatile');
  assert.deepEqual(none.whisper, ['whisper-large-v3-turbo']);
  assert.deepEqual(rankGroqModels(null), { vision: [], text: [], whisper: [] });
});

test('catalog: GET /models cu Bearer, pus în R2 `ai-models/groq.json` cu ttl 24 h; alt izolat citește cache-ul; la eșec listele statice (cele de azi)', async () => {
  const bucket = new Bucket();
  const env = { GROQ_API_KEY: ' gsk_secret ', RECORDS: bucket };
  const calls = mockFetch(() => ok(LIST));
  const c = await groqCatalog(env);
  assert.equal(c.source, 'discovery');
  assert.equal(calls[0].headers.authorization, 'Bearer gsk_secret');
  const saved = bucket.files.get(GROQ_CACHE_KEY);
  assert.equal(saved.customMetadata.ttl, String(24 * 3600_000));
  assert.ok(!saved.text.includes('secret'));
  resetBudgetCache();
  await groqCatalog(env);
  assert.equal(listCalls(calls), 1, 'cache-ul din R2 ține loc de /models');
  assert.deepEqual(await groq.models(env, { images: IMG }), c.vision);
  assert.deepEqual(await groq.models({ ...env, GROQ_TEXT_MODEL: 'openai/gpt-oss-120b' }), ['openai/gpt-oss-120b', ...c.text.filter((m) => m !== 'openai/gpt-oss-120b')]);
  resetBudgetCache();
  mockFetch(() => ok({ error: 'boom' }, 500));
  const s = await groqCatalog({ GROQ_API_KEY: 'q' });
  assert.equal(s.source, 'static');
  assert.deepEqual(s.vision, GROQ_VISION_MODELS);
  assert.deepEqual(s.text, [GROQ_TEXT_MODEL]);
  assert.deepEqual(s.whisper, [GROQ_WHISPER_MODEL]);
});

test('404 pe Scout și Maverick (faptul din 28.09) → retrase 24 h, trecere la următorul model de viziune; cererea următoare nu le mai încearcă; fără alt model de viziune Groq e sărit la poze', async () => {
  const bucket = new Bucket();
  const env = { GROQ_API_KEY: 'q', RECORDS: bucket, AI: aiMock() };
  const calls = mockFetch((url, body) => url.endsWith('/models') ? ok(LIST) : /llama-4/.test(body.model) ? ok({ error: { message: 'model not found', code: 'model_not_found' } }, 404) : ok(openaiReply(JSON.stringify(MEAL))));
  const r = await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r.provider, 'groq');
  assert.equal(r.model, 'google/gemma-3-27b-it');
  assert.deepEqual(groqModels(calls), ['meta-llama/llama-4-maverick-17b-128e-instruct', 'meta-llama/llama-4-scout-17b-16e-instruct', 'google/gemma-3-27b-it']);
  assert.match(r.attempts[0], /404 \(model retras 24 h\)/);
  const saved = JSON.parse(bucket.files.get(GROQ_CACHE_KEY).text);
  assert.ok(saved.retired['meta-llama/llama-4-scout-17b-16e-instruct'] > Date.now() + 23 * 3600_000);
  calls.length = 0;
  await visionJson(env, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.deepEqual(groqModels(calls), ['google/gemma-3-27b-it'], 'modelele retrase nu se mai încearcă');
  // Lista fără niciun model de viziune: Groq e sărit la poze (fără eroare), Workers preia; la text Groq rămâne.
  resetBudgetCache();
  const env2 = { GROQ_API_KEY: 'q', AI: aiMock() };
  const calls2 = mockFetch((url) => url.endsWith('/models') ? ok(NO_VISION) : ok(openaiReply(JSON.stringify(MEAL))));
  const r2 = await visionJson(env2, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r2.provider, 'workers');
  assert.equal(groqModels(calls2).length, 0, 'niciun apel Groq cu poze');
  assert.match(r2.attempts[0], /groq: fără model pentru poze \(sărit\)/);
  const t = await textJson(env2, { task: 's', prompt: 'p JSON', schema: MEAL_SCHEMA });
  assert.equal(t.provider, 'groq');
  assert.equal(t.model, 'llama-3.3-70b-versatile');
  // Lista statică (descoperirea picată) + 404 pe amândouă → tot fără viziune → Workers.
  resetBudgetCache();
  const calls3 = mockFetch((url) => url.endsWith('/models') ? ok({}, 500) : ok({ error: 'gone' }, 404));
  const r3 = await visionJson(env2, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(r3.provider, 'workers');
  assert.deepEqual(groqModels(calls3), GROQ_VISION_MODELS);
  calls3.length = 0;
  await visionJson(env2, { task: 'meal', prompt: 'x JSON', images: IMG, schema: MEAL_SCHEMA });
  assert.equal(groqModels(calls3).length, 0, 'retrase și pe lista statică: Groq sărit direct');
});

test('whisper: id-ul din listă (large-v3, altfel turbo); diag arată listele Groq și spune când nu există model de viziune', async () => {
  const env = { GROQ_API_KEY: 'q' };
  let calls = mockFetch((url) => url.endsWith('/models') ? ok(NO_VISION) : ok({ text: 'Bună.', language: 'ro', segments: [] }));
  const r = await transcribe(env, { bytes: new Uint8Array(4000), mime: 'audio/wav' });
  assert.equal(r.model, 'whisper-large-v3-turbo');
  const form = calls.find((c) => c.url.includes('/audio/transcriptions')).body;
  assert.equal(form.get('model'), 'whisper-large-v3-turbo');
  const d = await diagProviders(env);
  assert.equal(d.providers.groq, 'configured');
  assert.deepEqual(d.models.groq.viziune, []);
  assert.match(d.models.groq.nota, /fără model de viziune/);
  assert.equal(d.models.groq.whisper, 'whisper-large-v3-turbo');
  assert.equal(d.models.groq.text[0], 'llama-3.3-70b-versatile');
  resetBudgetCache();
  calls = mockFetch((url) => url.endsWith('/models') ? ok(LIST) : url.includes('googleapis') ? ok(GEMINI_LIST) : ok({ text: 'x', segments: [] }));
  const d2 = await diagProviders({ ...env, GEMINI_API_KEY: 'g' });
  assert.equal(d2.models.groq.nota, undefined);
  assert.equal(d2.models.groq.viziune[0], 'meta-llama/llama-4-maverick-17b-128e-instruct');
  assert.equal(d2.models.groq.whisper, 'whisper-large-v3');
  assert.equal(d2.models.gemini.ordine[0], 'gemini-3.8-flash');
  assert.ok(!JSON.stringify(d2).includes('gsk_'));
});
