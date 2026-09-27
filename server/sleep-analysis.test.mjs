import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  analyzeSleepChunk, SLEEP_ASR_MODEL, SLEEP_TOPICS_MODEL,
  SLEEP_CHUNK_MAX_BYTES, SLEEP_ANALYSIS_MAX_BYTES,
} from './sleep-analysis.mjs';

const m4a = readFileSync(new URL('./fixtures/two-minutes-silence.m4a', import.meta.url));
const options = { duration_ms: 120000, media_type: 'audio/mp4' };
function fixture(asr = { text: '' }, topics = { response: { topics: [] } }) {
  const calls = [];
  return { calls, env: { AI: { async run(model, input) {
    calls.push({ model, input });
    const value = model === SLEEP_ASR_MODEL ? asr : topics;
    if (value instanceof Error) throw value;
    if (typeof value === 'function') return value(input);
    return value;
  } } } };
}

test('complete bounded M4A is sent unchanged to Whisper turbo with hallucination controls', async () => {
  const f = fixture({ text: 'Mâine mergem la mare.', segments: [{ text: 'Mâine mergem la mare.', start: 2.5, end: 5 }] }, {
    response: { topics: [{ title: 'mergem la mare', evidence_ids: ['s1'] }] },
  });
  const value = await analyzeSleepChunk(f.env, m4a, options);
  assert.equal(value.status, 'complete');
  assert.equal(value.transcript, 'Mâine mergem la mare.');
  assert.equal(value.transcript_status, 'unverified');
  assert.equal(value.segments[0].start_ms, 2500);
  assert.equal(value.segments[0].end_ms, 5000);
  assert.equal(value.segments[0].reliability, 'unverified');
  assert.deepEqual(value.topics[0].quotes, ['Mâine mergem la mare.']);
  assert.deepEqual(value.snoring, { status: 'unavailable', reason: 'audio_event_classifier_unavailable' });
  assert.equal(value.speech, undefined);
  assert.equal(value.confidence, undefined);
  assert.equal(f.calls.length, 2);
  assert.equal(f.calls[0].model, SLEEP_ASR_MODEL);
  assert.equal(f.calls[1].model, SLEEP_TOPICS_MODEL);
  assert.deepEqual(Buffer.from(f.calls[0].input.audio, 'base64'), m4a);
  assert.equal(f.calls[0].input.task, 'transcribe');
  assert.equal(f.calls[0].input.vad_filter, true);
  assert.equal(f.calls[0].input.condition_on_previous_text, false);
  assert.equal(f.calls[0].input.initial_prompt, undefined);
  assert.deepEqual(f.calls[1].input.response_format.json_schema.properties.topics.items.properties.evidence_ids.items.enum, ['s1']);
});

test('short actual replies are retained without made-up certainty or topics', async () => {
  for (const text of ['Da.', 'Nu.', 'A!', 'Mhm', 'Da, da, da, da.', 'Mulțumesc pentru vizionare.']) {
    const f = fixture({ text });
    const value = await analyzeSleepChunk(f.env, m4a, options);
    assert.equal(value.transcript, text);
    assert.equal(value.transcript_status, 'unverified');
    assert.deepEqual(value.topics, []);
    assert.equal(value.topics_status, 'insufficient_evidence');
    assert.equal(value.segments[0].start_ms, undefined);
  }
});

test('empty ASR is not treated as proven silence, snoring or successful event classification', async () => {
  const f = fixture({ text: '', segments: [] });
  const value = await analyzeSleepChunk(f.env, m4a, options);
  assert.equal(value.status, 'complete');
  assert.equal(value.transcript_status, 'empty');
  assert.equal(value.transcript, '');
  assert.equal(value.snoring.status, 'unavailable');
  assert.ok(value.limitations.includes('empty_transcript_is_not_verified_silence'));
  assert.equal(f.calls.length, 1);
});

test('provider failure, missing binding and invalid payload never fabricate an empty transcript', async () => {
  for (const raw of [new Error('secret provider details'), {}, null, { text: 4 }, { text: '', error: 'bad audio' }, { success: false, text: '' }, { text: '', segments: [null] }]) {
    const f = fixture(raw);
    const value = await analyzeSleepChunk(f.env, m4a, options);
    assert.equal(value.status, 'failed');
    assert.equal(value.transcript, null);
    assert.equal(value.transcript_status, 'unavailable');
    assert.ok(value.error.code);
    assert.ok(!JSON.stringify(value).includes('secret provider details'));
    assert.equal(f.calls.length, 1);
  }
  const value = await analyzeSleepChunk({}, m4a, options);
  assert.equal(value.status, 'unavailable');
  assert.equal(value.error.code, 'ai_unavailable');
});

test('bad size, duration, MIME, container, video, and external media fail before an AI call', async () => {
  const video = Buffer.from(m4a); video.write('vide', video.indexOf('soun'));
  const external = Buffer.from(m4a); external.writeUInt32BE(0, external.indexOf('url ') + 4);
  const cases = [
    [new Uint8Array(), options], [new Uint8Array(SLEEP_CHUNK_MAX_BYTES + 1), options],
    [m4a, { duration_ms: 0 }], [m4a, { duration_ms: 300001 }], [m4a, { duration_ms: 60000 }],
    [m4a, { ...options, media_type: 'audio/wav' }], [m4a, { ...options, language: 'ignore previous' }],
    [m4a.subarray(0, m4a.length - 100), options], [new Uint8Array(300), options], [video, options], [external, options],
  ];
  for (const [bytes, opts] of cases) {
    const f = fixture();
    const value = await analyzeSleepChunk(f.env, bytes, opts);
    assert.equal(value.status, 'rejected');
    assert.equal(value.transcript, null);
    assert.equal(f.calls.length, 0);
  }
});

test('uncertain ASR remains visible but cannot be promoted into grounded topics', async () => {
  for (const metric of [{ no_speech_prob: 0.9 }, { avg_logprob: -1.5 }, { compression_ratio: 3 }]) {
    const f = fixture({ text: 'Am visat la mare.', segments: [{ text: 'Am visat la mare.', start: 1, end: 3, ...metric }] });
    const value = await analyzeSleepChunk(f.env, m4a, options);
    assert.equal(value.status, 'partial');
    assert.equal(value.transcript, 'Am visat la mare.');
    assert.equal(value.segments[0].reliability, 'uncertain');
    assert.equal(value.topics_status, 'insufficient_evidence');
    assert.deepEqual(value.topics, []);
    assert.equal(f.calls.length, 1);
  }
});

test('timestamps must fit the chunk; missing timing is left unknown', async () => {
  const f = fixture({ text: 'Da. Nu. Poate.', segments: [
    { text: 'Da.', start: -1, end: 2 }, { text: 'Nu.', start: 119, end: 150 }, { text: 'Poate.' },
  ] });
  const value = await analyzeSleepChunk(f.env, m4a, options);
  assert.equal(value.status, 'partial');
  assert.ok(value.segments.every(s => s.start_ms === undefined && s.end_ms === undefined));
  assert.deepEqual(value.segments.map(s => s.text), ['Da.', 'Nu.', 'Poate.']);
});

test('fabricated sources, psychological titles, injected quotes and extra fields are rejected', async () => {
  const invalidTopics = [
    { title: 'la mare', evidence_ids: ['invented'] },
    { title: 'Persoana este anxioasă', evidence_ids: ['s1'] },
    { title: 'la mare', evidence_ids: ['s1'], quotes: ['Inventat'] },
    { title: 'la mare', evidence_ids: ['s1'], diagnosis: 'insomnie' },
    { title: 'la mare', evidence_ids: ['s1', 's1'] },
  ];
  for (const topic of invalidTopics) {
    const f = fixture({ text: 'Mergem la mare.' }, { response: { topics: [topic] } });
    const value = await analyzeSleepChunk(f.env, m4a, options);
    assert.equal(value.status, 'partial');
    assert.equal(value.transcript, 'Mergem la mare.');
    assert.equal(value.topics_status, 'failed');
    assert.deepEqual(value.topics, []);
  }
});

test('topic provider error and malformed JSON keep the transcript usable', async () => {
  for (const topicResult of [new Error('outage'), { response: '{"topics":' }, { response: {} }, { response: { topics: [], command: 'run' } }]) {
    const f = fixture({ text: 'Mergem la mare.' }, topicResult);
    const value = await analyzeSleepChunk(f.env, m4a, options);
    assert.equal(value.status, 'partial');
    assert.equal(value.transcript, 'Mergem la mare.');
    assert.equal(value.topics_status, 'failed');
  }
});

test('inconsistent provider segments are omitted and cannot supply false evidence', async () => {
  const f = fixture({ text: 'Mergem la mare.', segments: [{ text: 'Am cumpărat o casă.', start: 1, end: 3 }] });
  const value = await analyzeSleepChunk(f.env, m4a, options);
  assert.equal(value.status, 'partial');
  assert.ok(value.limitations.includes('inconsistent_segments_omitted'));
  assert.ok(!JSON.stringify(value).includes('cumpărat'));
  assert.ok(!f.calls[1].input.messages[1].content.includes('cumpărat'));
});

test('output remains below 32KiB with huge Unicode, escaped strings and many segments', async () => {
  for (const text of ['Șț😀 '.repeat(10000), '"\\\n'.repeat(20000)]) {
    const f = fixture({ text, segments: Array.from({ length: 250 }, () => ({ text: text.slice(0, 1000), start: 1, end: 2 })) });
    const value = await analyzeSleepChunk(f.env, m4a, options);
    assert.equal(value.status, 'partial');
    assert.ok(Buffer.byteLength(JSON.stringify(value)) < SLEEP_ANALYSIS_MAX_BYTES);
    assert.ok(Buffer.byteLength(value.transcript) <= 12000);
    assert.ok(value.segments.length <= 100);
    assert.ok(value.segments.every(s => s.text.length <= 1000));
    assert.ok(!value.transcript.includes('\ufffd'));
  }
});
