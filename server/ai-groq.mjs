// Groq (cheie GRATUITĂ de la console.groq.com, fără card) — API compatibil OpenAI: viziune Llama 4, text Llama 3.3 70B,
// audio Whisper large v3 cu timpi pe segmente (verbose_json). A doua opinie gratuită după Gemini.
import { AiError, fetchWithTimeout, readKey } from "./ai-common.mjs";
import { chatCompletionsJson } from "./ai-openai.mjs";

export const GROQ_BASE = "https://api.groq.com/openai/v1";
export const GROQ_VISION_MODELS = ["meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct"];
export const GROQ_TEXT_MODEL = "llama-3.3-70b-versatile";
export const GROQ_WHISPER_MODEL = "whisper-large-v3";

export const groq = {
  name: "groq",
  timeoutMs: 60000,
  supports: { images: true, documents: false, audio: false, verifyWithImages: false, transcribe: true },
  // Cheia e citită cu trim(): un secret lipit cu spații sau ghilimele dă 401 fără explicații. O cheie Groq validă începe cu `gsk_`.
  available: (env) => readKey(env, "GROQ_API_KEY").length > 0,
  // Cu imagini: modelele de viziune; doar text: Llama 3.3 70B (mai bun la română și la JSON).
  models: (env, { images = [] } = {}) => (images.length ? [...new Set([env?.GROQ_VISION_MODEL || GROQ_VISION_MODELS[0], ...GROQ_VISION_MODELS])] : [env?.GROQ_TEXT_MODEL || GROQ_TEXT_MODEL]),
  dailyLimit: 14400,
  generate(env, opts) {
    return chatCompletionsJson({ ...opts, url: GROQ_BASE + "/chat/completions", apiKey: readKey(env, "GROQ_API_KEY"), provider: "groq", timeoutMs: opts.timeoutMs || this.timeoutMs });
  },
  /** Verificarea cheii (pentru diag): codul HTTP al lui GET /models (200 = bună, 401 = respinsă — cheia trebuie regenerată, format `gsk_…`; 0 = rețea). */
  async keyCheck(env) {
    try {
      const resp = await fetchWithTimeout(GROQ_BASE + "/models", { method: "GET", headers: { authorization: "Bearer " + readKey(env, "GROQ_API_KEY") } }, 8000);
      return resp.status;
    } catch (_) { return 0; }
  },

  /** Transcriere cu timpi: {text, segments:[{startMs,endMs,text,noSpeechProb}], language}. */
  async transcribe(env, { bytes, mime = "audio/mp4", language = "", timeoutMs }) {
    const form = new FormData();
    const ext = /wav/.test(mime) ? "wav" : /mpeg|mp3/.test(mime) ? "mp3" : "m4a";
    form.append("file", new Blob([bytes], { type: mime }), "clip." + ext);
    form.append("model", GROQ_WHISPER_MODEL);
    form.append("response_format", "verbose_json");
    form.append("temperature", "0");
    if (language) form.append("language", language);
    const resp = await fetchWithTimeout(GROQ_BASE + "/audio/transcriptions", { method: "POST", headers: { authorization: "Bearer " + readKey(env, "GROQ_API_KEY") }, body: form }, timeoutMs || 120000)
      .catch((e) => { e.provider = "groq"; e.model = GROQ_WHISPER_MODEL; throw e; });
    if (!resp.ok) throw new AiError("groq a răspuns cu " + resp.status, { provider: "groq", model: GROQ_WHISPER_MODEL, status: resp.status, fatal: resp.status === 429 || resp.status === 401 || resp.status === 403 });
    const data = await resp.json().catch(() => { throw new AiError("groq: răspuns care nu e JSON", { provider: "groq", model: GROQ_WHISPER_MODEL, kind: "json" }); });
    const segments = (Array.isArray(data?.segments) ? data.segments : []).filter((s) => s && typeof s.text === "string" && Number.isFinite(s.start) && Number.isFinite(s.end) && s.end > s.start)
      .map((s) => ({ startMs: Math.round(s.start * 1000), endMs: Math.round(s.end * 1000), text: s.text.trim().slice(0, 400), noSpeechProb: Number.isFinite(s.no_speech_prob) ? s.no_speech_prob : null, avgLogprob: Number.isFinite(s.avg_logprob) ? s.avg_logprob : null }));
    return { text: String(data?.text || "").trim(), segments, language: String(data?.language || "").slice(0, 16), model: GROQ_WHISPER_MODEL };
  },
};
