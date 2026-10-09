import test from 'node:test';
import assert from 'node:assert/strict';
import { mergeTimeline, timelineStats, coverage, normalizeChunk, formatClock, formatDuration, eventsFromSegments, SNORE_MERGE_GAP_MS } from './sleep-timeline.mjs';
import { classifyClip, parseWav, envelopePeriodicity } from './sleep-clip.mjs';
import { validate, SLEEP_EVENTS_SCHEMA } from './ai-schemas.mjs';

const T0 = Date.UTC(2026, 8, 27, 23, 0, 0); // 23:00 UTC
const MIN = 60_000;
const chunks = [
  { index: 0, from: T0, dur: 30 * MIN },
  { index: 1, from: T0 + 30 * MIN, dur: 30 * MIN },
  { index: 2, from: T0 + 60 * MIN, dur: 30 * MIN },
];

test('mergeTimeline: decalajul `from`, tăierea la marginile chunk-ului, sortarea, vorbitul fără cuvinte nu e dovadă', () => {
  const t = mergeTimeline(chunks, {
    1: [{ type: 'talk', startMs: 5 * MIN, endMs: 5 * MIN + 3000, transcript: 'Ne vedem mâine.', language: 'ro', confidence: 0.9 },
        { type: 'talk', startMs: 6 * MIN, endMs: 6 * MIN + 1000, transcript: '' },
        { type: 'cough', startMs: 29 * MIN, endMs: 41 * MIN }],
    0: [{ type: 'noise', startMs: 2 * MIN, endMs: 2 * MIN + 500, intensity: 0.3 }],
  });
  assert.equal(t.events.length, 3);
  assert.equal(t.events[0].type, 'noise');
  assert.equal(t.events[0].from, T0 + 2 * MIN);
  assert.equal(t.events[1].type, 'talk');
  assert.equal(t.events[1].from, T0 + 35 * MIN);
  assert.equal(t.events[1].to, T0 + 35 * MIN + 3000);
  assert.equal(t.events[1].transcript, 'Ne vedem mâine.');
  assert.equal(t.events[1].chunk, 1);
  assert.equal(t.events[2].type, 'cough');
  assert.equal(t.events[2].to, T0 + 60 * MIN, 'tăiat la sfârșitul chunk-ului');
});

test('sforăitul la < 20 s distanță devine un episod; la ≥ 20 s rămân două', () => {
  const t = mergeTimeline(chunks, [
    { index: 0, events: [
      { type: 'snore', startMs: 0, endMs: 60_000, intensity: 0.4 },
      { type: 'snore', startMs: 60_000 + SNORE_MERGE_GAP_MS - 1, endMs: 150_000, intensity: 0.7 },
      { type: 'snore', startMs: 150_000 + SNORE_MERGE_GAP_MS, endMs: 200_000, intensity: 0.5 },
    ] },
    { index: 1, events: [{ type: 'snore', startMs: 0, endMs: 10 * MIN }] },
  ]);
  const snores = t.events.filter((e) => e.type === 'snore');
  assert.equal(snores.length, 3);
  assert.equal(snores[0].from, T0);
  assert.equal(snores[0].to, T0 + 150_000);
  assert.equal(snores[0].intensity, 0.7);
  assert.equal(snores[0].parts, 2);
  assert.equal(snores[1].parts, undefined);
  assert.equal(t.stats.snoreEpisodes, 3);
  assert.equal(t.stats.snoreMs, 150_000 + 30_000 + 10 * MIN);
  assert.equal(t.stats.snoreMinutes, 13);
  assert.deepEqual(t.stats.longestSnore, { from: T0 + 30 * MIN, to: T0 + 40 * MIN, minutes: 10 });
});

test('statistici: fraze (max 6), tuse, zgomote; acoperire „N min din M analizate”', () => {
  const talks = Array.from({ length: 8 }, (_, i) => ({ type: 'talk', startMs: i * MIN, endMs: i * MIN + 1000, transcript: 'fraza ' + i }));
  const t = mergeTimeline(chunks, { 0: talks, 2: [{ type: 'cough', startMs: 1, endMs: 2 }, { type: 'noise', startMs: 3, endMs: 4 }] });
  assert.equal(t.stats.talkEvents, 8);
  assert.equal(t.stats.phrases.length, 6);
  assert.deepEqual(t.stats.phrases[0], { at: T0, text: 'fraza 0' });
  assert.equal(t.stats.coughs, 1);
  assert.equal(t.stats.noises, 1);
  assert.equal(t.stats.snoreEpisodes, 0);
  assert.equal(t.stats.longestSnore, null);
  // chunk-ul 1 nu a fost analizat
  assert.deepEqual(t.coverage, { analyzedMs: 60 * MIN, totalMs: 90 * MIN, chunksAnalyzed: 2, chunksTotal: 3, text: '60 min din 90 analizate' });
  assert.equal(coverage(chunks, [0, 1, 2], 8 * 60 * MIN).text, '90 min din 480 analizate');
  assert.equal(timelineStats([], { maxPhrases: 2 }).phrases.length, 0);
});

test('normalizeChunk: index întreg 0–99999, from ≥ 0, dur în (0, 35 min]', () => {
  assert.deepEqual(normalizeChunk({ index: '2', from: '1000', dur: '60000' }), { index: 2, from: 1000, dur: 60000 });
  assert.deepEqual(normalizeChunk({ index: 2880, from: 28_800_000, dur: 10_000 }), { index: 2880, from: 28_800_000, dur: 10_000 });
  assert.equal(normalizeChunk({ index: -1, from: 0, dur: 1000 }), null);
  assert.equal(normalizeChunk({ index: 1.5, from: 0, dur: 1000 }), null);
  assert.equal(normalizeChunk({ index: 100_000, from: 0, dur: 1000 }), null);
  assert.equal(normalizeChunk({ index: 1, from: 0, dur: 0 }), null);
  assert.equal(normalizeChunk({ index: 1, from: 0, dur: 36 * MIN }), null);
  assert.equal(normalizeChunk({ index: 1, from: -5, dur: 1000 }), null);
  assert.equal(normalizeChunk(null), null);
  assert.equal(mergeTimeline([{ index: 1, from: 0, dur: 36 * MIN }], { 1: [] }).events.length, 0);
});

test('ceasul și durata: fusul telefonului, „7 h 42 min”', () => {
  assert.equal(formatClock(T0, 0), '23:00');
  assert.equal(formatClock(T0, 180), '02:00');
  assert.equal(formatClock(T0 + 14 * MIN, -300), '18:14');
  assert.equal(formatDuration(462 * MIN), '7 h 42 min');
  assert.equal(formatDuration(42 * MIN), '42 min');
});

test('eventsFromSegments: segmentele Whisper devin „talk” după filtrul de halucinații și pragurile de siguranță', () => {
  const clean = (t) => ({ speech: t.trim().split(/\s+/).length >= 2, transcript: t.trim(), words: t.trim().split(/\s+/).length });
  const ev = eventsFromSegments([
    { startMs: 100, endMs: 2000, text: 'Ne vedem mâine dimineață', noSpeechProb: 0.1 },
    { startMs: 3000, endMs: 4000, text: 'Thank you.', noSpeechProb: 0.9 },
    { startMs: 5000, endMs: 6000, text: 'mhm', noSpeechProb: 0.1 },
    { startMs: 7000, endMs: 8000, text: 'ceva ceva', avgLogprob: -1.5 },
  ], clean);
  assert.equal(ev.length, 1);
  assert.deepEqual(ev[0], { type: 'talk', startMs: 100, endMs: 2000, transcript: 'Ne vedem mâine dimineață', confidence: 0.7 });
});

// ── WAV sintetic pentru clasificarea acustică ──
function wav(samples, sampleRate = 16000) {
  const buf = new ArrayBuffer(44 + samples.length * 2);
  const dv = new DataView(buf);
  const str = (o, s) => { for (let i = 0; i < s.length; i++) dv.setUint8(o + i, s.charCodeAt(i)); };
  str(0, 'RIFF'); dv.setUint32(4, 36 + samples.length * 2, true); str(8, 'WAVE'); str(12, 'fmt '); dv.setUint32(16, 16, true);
  dv.setUint16(20, 1, true); dv.setUint16(22, 1, true); dv.setUint32(24, sampleRate, true); dv.setUint32(28, sampleRate * 2, true);
  dv.setUint16(32, 2, true); dv.setUint16(34, 16, true); str(36, 'data'); dv.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) dv.setInt16(44 + i * 2, Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767))), true);
  return new Uint8Array(buf);
}
let seed = 7;
const rnd = () => { seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), 1 | seed); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296 - 0.5; };
const seconds = 5, rate = 16000;
const silence = wav(new Float32Array(seconds * rate).fill(0));
const snore = wav(Float32Array.from({ length: seconds * rate }, (_, i) => { const t = i / rate; const env = 0.5 * (1 + Math.sin(2 * Math.PI * 0.9 * t)); return (env * env) * 0.6 * rnd(); }));
const noise = wav(Float32Array.from({ length: seconds * rate }, () => 0.4 * rnd()));

test('classifyClip: liniște / sforăit periodic (0,5–2 Hz) / zgomot fără ritm; vorbitul are întâietate; WAV nevalid → zgomot slab', () => {
  assert.equal(parseWav(silence).sampleRate, 16000);
  assert.equal(classifyClip(silence).type, 'silence');
  const s = classifyClip(snore);
  assert.equal(s.type, 'snore');
  assert.ok(s.periodicity >= 0.45, 'periodicitate ' + s.periodicity);
  assert.ok(Math.abs(s.periodMs - 1111) < 120, 'perioada ≈ 1,1 s: ' + s.periodMs);
  assert.ok(s.intensity > 0.2 && s.intensity <= 1);
  const n = classifyClip(noise);
  assert.equal(n.type, 'noise');
  assert.ok(n.periodicity < 0.45);
  assert.equal(classifyClip(snore, { speech: true }).type, 'talk');
  const bad = classifyClip(new Uint8Array(100));
  assert.equal(bad.type, 'noise');
  assert.equal(bad.decoded, false);
  assert.deepEqual(envelopePeriodicity([1, 2, 3]), { peak: 0, periodMs: 0 });
});

/** WAV cu un chunk JUNK de 4020 octeți și un antet `fmt ` trunchiat la coadă: 4048 octeți (trece de pragul de 4000 al rutei). */
function truncatedFmtWav() {
  const buf = new ArrayBuffer(4048);
  const dv = new DataView(buf);
  const str = (o, s) => { for (let i = 0; i < s.length; i++) dv.setUint8(o + i, s.charCodeAt(i)); };
  str(0, 'RIFF'); dv.setUint32(4, 4040, true); str(8, 'WAVE'); str(12, 'JUNK'); dv.setUint32(16, 4020, true); str(4040, 'fmt '); dv.setUint32(4044, 16, true);
  return new Uint8Array(buf);
}

test('parseWav: antet fmt trunchiat sau prea scurt → null și verdict „zgomot slab”, nu RangeError', () => {
  const bytes = truncatedFmtWav();
  assert.equal(parseWav(bytes), null);
  const v = classifyClip(bytes);
  assert.deepEqual([v.type, v.decoded, v.confidence], ['noise', false, 0.2]);
  const short = new Uint8Array(silence);
  new DataView(short.buffer).setUint32(16, 8, true); // fmt declarat pe 8 octeți
  assert.equal(parseWav(short), null);
  assert.equal(parseWav(silence).bits, 16, 'WAV-ul întreg se citește în continuare');
});

test('450 de „respirații” de sforăit într-un chunk trec de schemă și se unesc într-un singur episod întreg', () => {
  const events = Array.from({ length: 450 }, (_, i) => ({ type: 'snore', startMs: i * 4000, endMs: i * 4000 + 3000, intensity: 0.5 }));
  assert.equal(validate(SLEEP_EVENTS_SCHEMA, { events }).ok, true);
  const t = mergeTimeline(chunks, { 0: events });
  assert.equal(t.events.length, 1);
  assert.equal(t.events[0].parts, 450);
  assert.equal(t.events[0].from, T0);
  assert.equal(t.events[0].to, T0 + 449 * 4000 + 3000, 'episodul ține până la ultima respirație, nu doar până la a 400-a');
  assert.equal(t.stats.snoreEpisodes, 1);
  assert.equal(t.stats.snoreMinutes, 30);
});
