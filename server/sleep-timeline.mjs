// Cronologia nopții: evenimentele per chunk (timpi față de începutul clipului) → o singură listă pe axa reală a nopții,
// cu sforăitul unit în episoade, statistici oneste și acoperirea „N min din M analizate”. Funcții pure, fără I/O.

export const SNORE_MERGE_GAP_MS = 20_000;
const TYPES = new Set(["talk", "snore", "cough", "noise"]);
const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n));
const finite = (v, fallback = 0) => (Number.isFinite(Number(v)) ? Number(v) : fallback);

/** Normalizează un chunk primit de la client: index întreg ≥ 0, from ≥ 0, dur în (0, 35 min]. null dacă e inutilizabil. */
export function normalizeChunk(raw, maxDurMs = 35 * 60_000) {
  if (!raw || typeof raw !== "object") return null;
  const index = Number(raw.index), from = Number(raw.from), dur = Number(raw.dur);
  if (!Number.isInteger(index) || index < 0 || index > 999) return null;
  if (!Number.isFinite(from) || from < 0 || !Number.isFinite(dur) || dur <= 0 || dur > maxDurMs) return null;
  return { index, from: Math.round(from), dur: Math.round(dur) };
}

/**
 * chunks: [{index, from, dur}]; events: {[index]: [{type,startMs,endMs,transcript,intensity,confidence}]} sau [{index, events}].
 * Întoarce {events, stats, coverage}: evenimente absolute (ms epoch), sortate; sforăitul la < 20 s distanță devine un episod.
 */
export function mergeTimeline(chunks, events, options = {}) {
  const byIndex = new Map();
  if (Array.isArray(events)) {
    for (const e of events) if (e && Number.isInteger(e.index)) byIndex.set(e.index, Array.isArray(e.events) ? e.events : []);
  } else if (events && typeof events === "object") {
    for (const [k, v] of Object.entries(events)) if (Array.isArray(v)) byIndex.set(Number(k), v);
  }
  const valid = (Array.isArray(chunks) ? chunks : []).map((c) => normalizeChunk(c, options.maxDurMs)).filter(Boolean).sort((a, b) => a.from - b.from);
  const absolute = [];
  for (const chunk of valid) {
    const list = byIndex.get(chunk.index);
    if (!Array.isArray(list)) continue;
    for (const ev of list.slice(0, 400)) {
      if (!ev || !TYPES.has(ev.type)) continue;
      const start = clamp(finite(ev.startMs), 0, chunk.dur), end = clamp(finite(ev.endMs, start), 0, chunk.dur);
      if (end <= start && ev.type !== "talk") continue;
      const transcript = typeof ev.transcript === "string" ? ev.transcript.trim().slice(0, 400) : "";
      if (ev.type === "talk" && !transcript) continue; // vorbit fără cuvinte auzite nu e dovadă
      absolute.push({
        type: ev.type, from: chunk.from + start, to: chunk.from + Math.max(end, start + 1), chunk: chunk.index,
        ...(transcript ? { transcript } : {}),
        ...(typeof ev.language === "string" && ev.language ? { language: ev.language.slice(0, 16) } : {}),
        intensity: clamp(finite(ev.intensity, ev.type === "snore" ? 0.5 : 0), 0, 1),
        confidence: clamp(finite(ev.confidence, 0.5), 0, 1),
      });
    }
  }
  absolute.sort((a, b) => a.from - b.from || a.to - b.to);
  const merged = [];
  for (const ev of absolute) {
    const last = merged[merged.length - 1];
    if (ev.type === "snore" && last && last.type === "snore" && ev.from - last.to < SNORE_MERGE_GAP_MS) {
      last.to = Math.max(last.to, ev.to);
      last.intensity = Math.max(last.intensity, ev.intensity);
      last.confidence = Math.max(last.confidence, ev.confidence);
      last.parts = (last.parts || 1) + 1;
      continue;
    }
    merged.push({ ...ev });
  }
  const analyzed = valid.filter((c) => byIndex.has(c.index));
  return { events: merged, stats: timelineStats(merged, options), coverage: coverage(valid, analyzed.map((c) => c.index), options.sessionMs) };
}

/** Minute de sforăit, episoade, cel mai lung episod, fraze auzite (până la `maxPhrases`, implicit 6), tuse, zgomote. */
export function timelineStats(events, { maxPhrases = 6 } = {}) {
  const snores = events.filter((e) => e.type === "snore");
  const snoreMs = snores.reduce((s, e) => s + (e.to - e.from), 0);
  let longest = null;
  for (const e of snores) if (!longest || e.to - e.from > longest.to - longest.from) longest = e;
  const talks = events.filter((e) => e.type === "talk" && e.transcript);
  return {
    snoreMinutes: Math.round(snoreMs / 60_000),
    snoreMs,
    snoreEpisodes: snores.length,
    longestSnore: longest ? { from: longest.from, to: longest.to, minutes: Math.round((longest.to - longest.from) / 60_000) } : null,
    talkEvents: talks.length,
    phrases: talks.slice(0, Math.max(0, maxPhrases)).map((e) => ({ at: e.from, text: e.transcript })),
    coughs: events.filter((e) => e.type === "cough").length,
    noises: events.filter((e) => e.type === "noise").length,
  };
}

/** Acoperirea: minute analizate din minute înregistrate (sau din durata sesiunii, dacă e dată). */
export function coverage(chunks, analyzedIndexes, sessionMs) {
  const set = new Set(analyzedIndexes || []);
  const totalRecorded = chunks.reduce((s, c) => s + c.dur, 0);
  const analyzedMs = chunks.filter((c) => set.has(c.index)).reduce((s, c) => s + c.dur, 0);
  const totalMs = Number.isFinite(sessionMs) && sessionMs > 0 ? Math.max(sessionMs, totalRecorded) : totalRecorded;
  const n = Math.round(analyzedMs / 60_000), m = Math.round(totalMs / 60_000);
  return { analyzedMs, totalMs, chunksAnalyzed: set.size, chunksTotal: chunks.length, text: `${n} min din ${m} analizate` };
}

/** „HH:MM” pentru un timp epoch, cu decalajul de fus al telefonului (minute, ca în JS `getTimezoneOffset`, semn inversat: +180 = UTC+3). */
export function formatClock(ms, tzOffsetMin = 0) {
  const d = new Date(finite(ms) + finite(tzOffsetMin) * 60_000);
  return String(d.getUTCHours()).padStart(2, "0") + ":" + String(d.getUTCMinutes()).padStart(2, "0");
}

/** „7 h 42 min” / „42 min”. */
export function formatDuration(ms) {
  const min = Math.round(finite(ms) / 60_000);
  const h = Math.floor(min / 60), m = min % 60;
  return h ? `${h} h ${String(m).padStart(2, "0")} min` : `${m} min`;
}

/**
 * Segmentele Whisper (fără sforăit) devin evenimente „talk”, după filtrul de halucinații primit ca funcție (cleanTranscript din worker.js).
 * Segmentele cu no_speech_prob ≥ 0,6 sau avg_logprob < −1 sunt lăsate deoparte: nu e dovadă.
 */
export function eventsFromSegments(segments, clean) {
  const out = [];
  for (const s of Array.isArray(segments) ? segments : []) {
    if (!s || typeof s.text !== "string") continue;
    if (Number.isFinite(s.noSpeechProb) && s.noSpeechProb >= 0.6) continue;
    if (Number.isFinite(s.avgLogprob) && s.avgLogprob < -1) continue;
    const c = clean ? clean(s.text) : { speech: !!s.text.trim(), transcript: s.text.trim(), words: s.text.trim().split(/\s+/).length };
    if (!c.speech) continue;
    out.push({ type: "talk", startMs: finite(s.startMs), endMs: finite(s.endMs), transcript: c.transcript, confidence: c.words >= 4 ? 0.7 : 0.5 });
  }
  return out;
}
