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

/** Înlocuiește fetch-ul global pentru un test; întoarce jurnalul apelurilor (url + corp parsat). */
function mockFetch(handler) {
  const calls = [];
  globalThis.fetch = async (url, init = {}) => {
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
  assert.equal(gemini.length, 3, 'toate cele trei modele Gemini încercate');
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
  // 3 modele + o singură reparare (la primul model) = 4 apeluri, nu 6
  assert.equal(gemini.length, 4);
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

test('bugetul zilnic: la limită furnizorul e sărit; consumul se vede în diag, cheile nu', async () => {
  const kv = new Map();
  const env = { GEMINI_API_KEY: 'g-secret', GROQ_API_KEY: 'q-secret', AI_BUDGET: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => kv.set(k, v) } };
  const day = new Date().toISOString().slice(0, 10);
  kv.set(`ai-budget:gemini:${day}`, '250');
  const calls = mockFetch(() => ok(openaiReply('{"summary":"ok"}')));
  const r = await textJson(env, { task: 'sum', prompt: 'p JSON', schema: SUMMARY_SCHEMA });
  assert.equal(r.provider, 'groq');
  assert.ok(calls.every((c) => c.url.includes('groq')));
  assert.equal(kv.get(`ai-budget:groq:${day}`), '1');
  const diag = await diagProviders(env);
  assert.deepEqual(diag.providers, { gemini: 'configured', groq: 'configured', anthropic: 'absent', openai: 'absent', workers: 'absent' });
  assert.equal(diag.budget.gemini.azi, 250);
  assert.equal(diag.budget.groq.azi, 1);
  assert.equal(diag.lastUsed.sum.provider, 'groq');
  assert.ok(!JSON.stringify(diag).includes('secret'));
  const none = await diagProviders({});
  assert.match(none.mode, /fără chei/);
});
