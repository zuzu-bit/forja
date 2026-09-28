import test from 'node:test';
import assert from 'node:assert/strict';
import { providers, visionJson, textJson, audioJson, transcribe, diagProviders, resetBudgetCache, lastUsed } from './ai-router.mjs';
import { MEAL_SCHEMA, SUMMARY_SCHEMA, SLEEP_AUDIO_SCHEMA } from './ai-schemas.mjs';

const geminiReply = (text) => ({ candidates: [{ content: { parts: [{ text }] }, finishReason: 'STOP' }] });
const anthropicReply = (text) => ({ content: [{ type: 'text', text }], stop_reason: 'end_turn' });
const openaiReply = (text) => ({ choices: [{ message: { content: text }, finish_reason: 'stop' }] });
const ok = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const MEAL = { fel: 'Pui cu orez', incredere: 'ridicată', componente: [{ nume: 'pui', grame: 150, kcal: 248, proteine: 46, carbo: 0, grasimi: 6 }] };
const KEYS = { GEMINI_API_KEY: 'g-secret', GROQ_API_KEY: 'q-secret', ANTHROPIC_API_KEY: 'a-secret', OPENAI_API_KEY: 'o-secret' };
const aiMock = (text = JSON.stringify(MEAL)) => ({ run: async (model, input) => (input.image ? { description: 'chicken - 150 g, rice - 100 g' } : { response: text }) });

// Descoperirea modelelor Gemini (GET /v1beta/models) primește o listă mică și realistă: patru modele de generare + trei de exclus.
// Apelul nu intră în `calls` (testele numără apelurile de generare); `calls.discovery` spune de câte ori s-a cerut lista.
const GEMINI_LIST = { models: [
  { name: 'models/gemini-3.5-flash', supportedGenerationMethods: ['generateContent', 'countTokens'] },
  { name: 'models/gemini-3.8-flash', supportedGenerationMethods: ['generateContent'] },
  { name: 'models/gemini-3.5-flash-lite', supportedGenerationMethods: ['generateContent'] },
  { name: 'models/gemini-3.1-pro-preview', supportedGenerationMethods: ['generateContent'] },
  { name: 'models/gemini-2.5-flash-preview-tts', supportedGenerationMethods: ['generateContent'] },
  { name: 'models/gemini-embedding-001', supportedGenerationMethods: ['embedContent'] },
  { name: 'models/gemini-3.5-transcribe', supportedGenerationMethods: ['generateContent'] },
] };
/** Înlocuiește fetch-ul global pentru un test; întoarce jurnalul apelurilor (url + corp parsat). */
// Lista Groq (GET /openai/v1/models) e servită la fel: un model de viziune, textul Llama 3.3 și Whisper.
const GROQ_LIST = { data: ['llama-3.3-70b-versatile', 'meta-llama/llama-4-scout-17b-16e-instruct', 'openai/gpt-oss-120b', 'whisper-large-v3'].map((id) => ({ id })) };
function mockFetch(handler) {
  const calls = [];
  calls.discovery = 0;
  globalThis.fetch = async (url, init = {}) => {
    if (String(url).includes('/v1beta/models?')) { calls.discovery++; return ok(GEMINI_LIST); }
    if (String(url) === 'https://api.groq.com/openai/v1/models') return ok(GROQ_LIST);
    const body = init.body && typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    calls.push({ url: String(url), body, headers: init.headers || {} });
    return handler(String(url), body, calls.length);
  };
  return calls;
}
test.beforeEach(() => resetBudgetCache());

test('ordinea furnizorilor după env: gemini → groq → anthropic → openai → workers, doar cei configurați', () => {
  assert.deepEqual(providers({ ...KEYS, AI: aiMock() }).map((p) => p.name), ['gemini', 'groq', 'anthropic', 'openai', 'workers']);
  assert.deepEqual(providers({ ANTHROPIC_API_KEY: 'x', AI: aiMock() }).map((p) => p.name), ['anthropic', 'workers']);
  assert.deepEqual(providers({ GROQ_API_KEY: 'x' }).map((p) => p.name), ['groq']);
  assert.deepEqual(providers({}).map((p) => p.name), []);
  assert.deepEqual(providers({ GEMINI_API_KEY: '' }).map((p) => p.name), []);
  assert.deepEqual(providers({ GEMINI_API_KEY: ' \n ', GROQ_API_KEY: '""' }).map((p) => p.name), [], 'doar spații sau ghilimele = cheie lipsă');
});

test('fallback: gemini 500 pe toate modelele → groq 429 (fatal, sare modelele rămase) → anthropic răspunde', async () => {
  const calls = mockFetch((url) => {
    if (url.includes('googleapis')) return ok({ error: 'boom' }, 500);
    if (url.includes('groq')) return ok({ error: 'rate' }, 429);
    if (url.includes('anthropic')) return ok(anthropicReply(JSON.stringify(MEAL)));
    throw new Error('neașteptat ' + url);
  });
  const r = await visionJson({ ...KEYS }, { task: 'meal', prompt: 'x JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA });
  assert.equal(r.provider, 'anthropic');
  assert.equal(r.model, 'claude-fable-5-1');
  assert.equal(r.json.fel, 'Pui cu orez');
  const gemini = calls.filter((c) => c.url.includes('googleapis'));
  assert.equal(gemini.length, 4, 'toate cele patru modele Gemini descoperite au fost încercate');
  assert.deepEqual(gemini.map((c) => c.url.split('/models/')[1].split(':')[0]), ['gemini-3.8-flash', 'gemini-3.5-flash-lite', 'gemini-3.5-flash', 'gemini-3.1-pro-preview'], 'ordinea la mese: cel mai bun flash, flash-lite-ul rapid, restul flash-urilor, pro');
  assert.equal(calls.discovery, 1, 'lista de modele se cere o singură dată');
  assert.equal(calls.filter((c) => c.url.includes('groq')).length, 1, '429 la groq = un singur apel');
  assert.ok(calls.every((c) => !JSON.stringify(c.url).includes('secret')), 'cheile nu apar în URL');
  assert.equal(calls.find((c) => c.url.includes('googleapis')).headers['x-goog-api-key'], 'g-secret');
  assert.equal(lastUsed().meal.provider, 'anthropic');
});

test('JSON invalid → o singură reparare per furnizor, apoi următorul furnizor', async () => {
  let n = 0;
  const calls = mockFetch((url, body) => {
    if (url.includes('googleapis')) { n++; return ok(geminiReply(n === 2 ? 'tot nu e json' : 'Sigur! Iată: {"fel": ')); }
    if (url.includes('groq')) return ok(openaiReply('```json\n' + JSON.stringify(MEAL) + '\n```'));
    throw new Error('neașteptat');
  });
  const r = await visionJson({ GEMINI_API_KEY: 'g', GROQ_API_KEY: 'q' }, { task: 'meal', prompt: 'x JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA });
  assert.equal(r.provider, 'groq');
  const gemini = calls.filter((c) => c.url.includes('googleapis'));
  // 4 modele + o singură reparare (la primul model) = 5 apeluri, nu 8
  assert.equal(gemini.length, 5);
  assert.match(gemini[1].body.contents[0].parts.at(-1).text, /NU a fost JSON valid/);
  assert.ok(!gemini[2].body.contents[0].parts.at(-1).text.includes('NU a fost JSON valid'));
});

test('JSON valid dar în afara schemei se repară cu erorile schemei în prompt', async () => {
  let n = 0;
  const calls = mockFetch(() => { n++; return ok(geminiReply(n === 1 ? JSON.stringify({ fel: 'x', incredere: 'sigur', componente: [] }) : JSON.stringify(MEAL))); });
  const r = await textJson({ GEMINI_API_KEY: 'g' }, { task: 't', prompt: 'p JSON', schema: MEAL_SCHEMA });
  assert.equal(r.json.incredere, 'ridicată');
  assert.match(calls[1].body.contents[0].parts.at(-1).text, /incredere/);
});

test('twoPass: a doua trecere primește imaginea + JSON-ul pasului 1 și poate corecta; la eșec rămâne pasul 1', async () => {
  const fixed = { ...MEAL, componente: [{ ...MEAL.componente[0], kcal: 250 }] };
  let n = 0;
  const calls = mockFetch(() => { n++; return ok(anthropicReply(n === 1 ? JSON.stringify(MEAL) : JSON.stringify(fixed))); });
  const r = await visionJson({ ANTHROPIC_API_KEY: 'a' }, { task: 'meal', prompt: 'p JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA, twoPass: true });
  assert.equal(r.verified, true);
  assert.equal(r.json.componente[0].kcal, 250);
  assert.equal(calls.length, 2);
  const second = calls[1].body.messages[0].content;
  assert.ok(second.some((b) => b.type === 'image'), 'imaginea e retrimisă la verificare');
  assert.match(second.at(-1).text, /4×proteine/);
  assert.match(second.at(-1).text, /"fel":"Pui cu orez"/);

  n = 0;
  mockFetch(() => { n++; return n === 1 ? ok(anthropicReply(JSON.stringify(MEAL))) : ok({}, 500); });
  const r2 = await visionJson({ ANTHROPIC_API_KEY: 'a' }, { task: 'meal', prompt: 'p JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA, twoPass: true });
  assert.equal(r2.verified, false);
  assert.equal(r2.json.componente[0].kcal, 248);
});

test('PDF-urile ajung nativ la anthropic/gemini și sunt lăsate deoparte la groq/openai', async () => {
  const calls = mockFetch((url) => url.includes('anthropic') ? ok(anthropicReply('{"summary":"ok"}')) : ok(openaiReply('{"summary":"ok"}')));
  const docs = [{ b64: 'JVBERi0x', mime: 'application/pdf', name: 'factura.pdf', label: 'id d1' }];
  await visionJson({ ANTHROPIC_API_KEY: 'a' }, { task: 'org', prompt: 'p JSON', documents: docs, schema: SUMMARY_SCHEMA });
  const content = calls[0].body.messages[0].content;
  assert.equal(content[1].type, 'document');
  assert.equal(content[1].source.media_type, 'application/pdf');
  assert.equal(content[0].text, '[id d1]');
  await visionJson({ GROQ_API_KEY: 'q' }, { task: 'org', prompt: 'p JSON', documents: docs, schema: SUMMARY_SCHEMA });
  assert.equal(typeof calls[1].body.messages.at(-1).content, 'string');
  assert.ok(!JSON.stringify(calls[1].body).includes('JVBERi0x'));
});

test('workers: fără chei, poza devine descriere și Llama produce JSON-ul; refuzul complet aruncă', async () => {
  const r = await visionJson({ AI: aiMock() }, { task: 'meal', prompt: 'p JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA });
  assert.equal(r.provider, 'workers');
  assert.equal(r.json.fel, 'Pui cu orez');
  await assert.rejects(() => visionJson({ AI: aiMock('nu e json') }, { task: 'meal', prompt: 'p JSON', schema: MEAL_SCHEMA }), /Niciun model/);
  await assert.rejects(() => visionJson({}, { task: 'meal', prompt: 'p' }), /Niciun furnizor/);
});

test('audioJson: doar gemini; fără el întoarce null; 180 s buget', async () => {
  assert.equal(await audioJson({ GROQ_API_KEY: 'q', AI: aiMock() }, { task: 'a', prompt: 'p', audio: { b64: 'AAAA', mime: 'audio/wav' } }), null);
  const calls = mockFetch(() => ok(geminiReply('{"type":"snore","speech":false,"confidence":0.8,"intensity":0.4}')));
  const r = await audioJson({ GEMINI_API_KEY: 'g' }, { task: 'a', prompt: 'p', audio: { b64: 'AAAA', mime: 'audio/wav' }, schema: SLEEP_AUDIO_SCHEMA });
  assert.equal(r.json.type, 'snore');
  assert.equal(calls[0].body.contents[0].parts[0].inline_data.mime_type, 'audio/wav');
  assert.equal(calls[0].body.generationConfig.responseMimeType, 'application/json');
  assert.equal(calls[0].body.generationConfig.responseSchema.properties.type.enum[0], 'talk');
});

test('transcribe: groq whisper cu segmente (verbose_json), apoi workers whisper la eroare', async () => {
  mockFetch(() => ok({ text: 'Ne vedem mâine.', language: 'ro', segments: [{ start: 1.2, end: 2.5, text: ' Ne vedem mâine.', no_speech_prob: 0.1 }] }));
  const r = await transcribe({ GROQ_API_KEY: 'q' }, { bytes: new Uint8Array(4000), mime: 'audio/mp4' });
  assert.equal(r.provider, 'groq');
  assert.deepEqual(r.segments[0], { startMs: 1200, endMs: 2500, text: 'Ne vedem mâine.', noSpeechProb: 0.1, avgLogprob: null });
  mockFetch(() => ok({}, 500));
  const r2 = await transcribe({ GROQ_API_KEY: 'q', AI: { run: async (m) => (m.includes('turbo') ? { text: 'salut', segments: [{ start: 0, end: 1, text: 'salut' }] } : { text: 'x' }) } }, { bytes: new Uint8Array(10) });
  assert.equal(r2.provider, 'workers');
  assert.equal(r2.segments.length, 1);
  await assert.rejects(() => transcribe({ GROQ_API_KEY: 'q' }, { bytes: new Uint8Array(10) }), /Transcrierea/);
});

test('bugetul zilnic: la Gemini contorul e per model (limită necunoscută → null), 429 pe un model trece la următorul, toate 429 → furnizorul următor; consumul se vede în diag, cheile nu', async () => {
  const kv = new Map();
  const env = { GEMINI_API_KEY: 'g-secret', GROQ_API_KEY: 'q-secret', AI_BUDGET: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => kv.set(k, v) } };
  const day = new Date().toISOString().slice(0, 10);
  let calls = mockFetch((url) => url.includes('gemini-3.8-flash:') ? ok({ error: 'rate' }, 429) : url.includes('googleapis') ? ok(geminiReply('{"summary":"ok"}')) : ok(openaiReply('{"summary":"ok"}')));
  let r = await textJson(env, { task: 'sum', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
  assert.equal(r.provider, 'gemini');
  assert.equal(r.model, 'gemini-3.5-flash', '429 la 3.8-flash → 3.5-flash (cota lui), nu alt furnizor');
  assert.equal(calls.length, 2);
  assert.equal(kv.get(`ai-budget:gemini/gemini-3.8-flash:${day}`), '1');
  assert.equal(kv.get(`ai-budget:gemini/gemini-3.5-flash:${day}`), '1');
  assert.equal(kv.get(`ai-budget:gemini:${day}`), '2', 'contorul furnizorului numără toate apelurile lui');
  calls = mockFetch((url) => url.includes('googleapis') ? ok({ error: 'rate' }, 429) : ok(openaiReply('{"summary":"ok"}')));
  r = await textJson(env, { task: 'sum', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
  assert.equal(r.provider, 'groq', 'toate modelele Gemini la 429 → furnizorul următor');
  assert.equal(calls.filter((c) => c.url.includes('googleapis')).length, 4);
  assert.equal(kv.get(`ai-budget:groq:${day}`), '1');
  const diag = await diagProviders(env);
  assert.deepEqual(diag.providers, { gemini: 'configured', groq: 'configured', anthropic: 'absent', openai: 'absent', workers: 'absent' });
  assert.equal(diag.budget.gemini.azi, 6);
  assert.deepEqual(diag.budget.gemini.modele['gemini-3.8-flash'], { azi: 2, limita: null }, 'limita per model nu se mai presupune');
  assert.deepEqual(Object.keys(diag.budget.gemini.modele), ['gemini-3.8-flash', 'gemini-3.5-flash', 'gemini-3.5-flash-lite', 'gemini-3.1-pro-preview']);
  assert.deepEqual(diag.models.gemini.ordine, ['gemini-3.8-flash', 'gemini-3.5-flash', 'gemini-3.5-flash-lite', 'gemini-3.1-pro-preview']);
  assert.equal(diag.models.gemini.sursa, 'discovery');
  assert.equal(diag.models.gemini.transcriere, 'gemini-3.5-transcribe');
  assert.equal(diag.budget.groq.azi, 1);
  assert.equal(diag.lastUsed.sum.provider, 'groq');
  assert.ok(!JSON.stringify(diag).includes('secret'));
  const none = await diagProviders({});
  assert.match(none.mode, /fără chei/);
});

test('gemini: 429 e per model — 3.8-flash la 429 → următorul model (la mese: 3.5-flash-lite) răspunde, fără să sară tot furnizorul', async () => {
  const calls = mockFetch((url) => url.includes('gemini-3.8-flash:') ? ok({ error: 'rate' }, 429) : ok(geminiReply(JSON.stringify(MEAL))));
  const r = await visionJson({ GEMINI_API_KEY: 'g', GROQ_API_KEY: 'q' }, { task: 'meal', prompt: 'x JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA });
  assert.equal(r.provider, 'gemini');
  assert.equal(r.model, 'gemini-3.5-flash-lite');
  assert.equal(calls.length, 2);
  assert.ok(calls.every((c) => c.url.includes('googleapis')));
});

test('timeoutMs de la apelant = buget pe TOT furnizorul: modelele rămase nu mai pornesc după ce s-a epuizat; fără el, bugetul e per model', async () => {
  const realNow = Date.now;
  let clock = realNow();
  Date.now = () => clock;
  try {
    const slow = (url) => { if (url.includes('googleapis')) { clock += 30_000; return ok({}, 500); } return ok(openaiReply(JSON.stringify(MEAL))); };
    let calls = mockFetch(slow);
    const r = await visionJson({ GEMINI_API_KEY: 'g', GROQ_API_KEY: 'q' }, { task: 'meal', prompt: 'x JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA, timeoutMs: 45_000 });
    assert.equal(r.provider, 'groq');
    assert.equal(calls.filter((c) => c.url.includes('googleapis')).length, 2, '30 s + 30 s > 45 s: al treilea model Gemini nu mai pornește');
    resetBudgetCache();
    calls = mockFetch(slow);
    await visionJson({ GEMINI_API_KEY: 'g', GROQ_API_KEY: 'q' }, { task: 'meal', prompt: 'x JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA });
    assert.equal(calls.filter((c) => c.url.includes('googleapis')).length, 4, 'fără timeoutMs fiecare model are bugetul lui');
  } finally { Date.now = realNow; }
});

test('transcribe cu skipAudioProviders: Gemini deja încercat de apelant → direct la Groq Whisper, fără alt apel Gemini', async () => {
  const calls = mockFetch((url) => url.includes('googleapis') ? ok(geminiReply('{"transcript":"x"}')) : ok({ text: 'Bună.', language: 'ro', segments: [] }));
  const r = await transcribe({ GEMINI_API_KEY: 'g', GROQ_API_KEY: 'q' }, { bytes: new Uint8Array(4000), mime: 'audio/wav', skipAudioProviders: true });
  assert.equal(r.provider, 'groq');
  assert.equal(calls.length, 1);
  assert.ok(calls[0].url.includes('groq'));
  const r2 = await transcribe({ GEMINI_API_KEY: 'g', GROQ_API_KEY: 'q' }, { bytes: new Uint8Array(4000), mime: 'audio/wav' });
  assert.equal(r2.provider, 'gemini', 'fără opțiune, Gemini rămâne primul');
});

test('contorul zilnic: incrementarea citește valoarea stocată (mai multe izolate), cache-ul expiră după 60 s', async () => {
  const kv = new Map();
  const env = { GROQ_API_KEY: 'q', AI_BUDGET: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => kv.set(k, v) } };
  const day = new Date().toISOString().slice(0, 10);
  const key = `ai-budget:groq:${day}`;
  mockFetch(() => ok(openaiReply('{"summary":"ok"}')));
  await textJson(env, { task: 'sum', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
  assert.equal(kv.get(key), '1');
  kv.set(key, '10'); // alt izolat a numărat între timp
  await textJson(env, { task: 'sum', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
  assert.equal(kv.get(key), '11', 'citire-modificare-scriere, nu cache + 1');
  const realNow = Date.now;
  let clock = realNow();
  Date.now = () => clock;
  try {
    kv.set(key, '14400'); // limita atinsă de alte izolate; cache-ul local încă spune 11
    const calls = mockFetch(() => ok(openaiReply('{"summary":"ok"}')));
    await textJson(env, { task: 'sum', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
    assert.equal(calls.length, 1, 'sub 60 s poarta se uită în cache');
    clock += 61_000;
    await assert.rejects(() => textJson(env, { task: 'sum', prompt: 'p JSON', schema: SUMMARY_SCHEMA }), /limita zilnică/);
  } finally { Date.now = realNow; }
});

test('workers: contorul numără apelurile env.AI.run (vedere + text), limita e null și unitatea e spusă în diag', async () => {
  const env = { AI: aiMock() };
  const r = await visionJson(env, { task: 'meal', prompt: 'p JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA });
  assert.equal(r.provider, 'workers');
  const diag = await diagProviders(env);
  assert.equal(diag.budget.workers.azi, 2, 'un apel de vedere + un apel de text');
  assert.equal(diag.budget.workers.limita, null);
  assert.match(diag.budget.workers.unitate, /apeluri/);
  assert.match(diag.budget.workers.cunoscut, /neuroni/);
});

test('twoPass fără poză la verificare: workers primește descrierea modelului de vedere, groq verifică doar coerența cifrelor', async () => {
  const prompts = [];
  const env = { AI: { run: async (model, input) => { if (input.image) return { description: 'chicken - 150 g, rice - 100 g' }; prompts.push(input.messages.at(-1).content); return { response: JSON.stringify(MEAL) }; } } };
  const r = await visionJson(env, { task: 'meal', prompt: 'p JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA, twoPass: true });
  assert.equal(r.verified, true);
  assert.equal(prompts.length, 2);
  assert.match(prompts[1], /Nu vezi imaginea/);
  assert.match(prompts[1], /chicken - 150 g/);
  assert.ok(!prompts[1].includes('față de imagine'));
  const calls = mockFetch(() => ok(openaiReply(JSON.stringify(MEAL))));
  await visionJson({ GROQ_API_KEY: 'q' }, { task: 'meal', prompt: 'p JSON', images: [{ b64: 'AAAA', mime: 'image/jpeg' }], schema: MEAL_SCHEMA, twoPass: true });
  const second = calls[1].body.messages.at(-1).content;
  assert.equal(typeof second, 'string', 'fără imagine la verificare');
  assert.match(second, /coerența cifrelor/);
  assert.match(second, /4×proteine/);
});
