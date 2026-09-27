// Workers AI (env.AI) — gratuit, fără cheie, plasa de siguranță: fără nicio cheie totul trebuie să meargă pe el, cât mai bine.
// Viziune: modelul de vedere descrie (la asta e bun), apoi Llama 3.3 70B pune cifrele/JSON-ul în română.
import { AiError, b64ToBytes, bytesToB64, errorText } from "./ai-common.mjs";

export const WORKERS_VISION_MODELS = ["@cf/meta/llama-3.2-11b-vision-instruct", "@cf/llava-hf/llava-1.5-7b-hf"];
export const WORKERS_TEXT_MODELS = ["@cf/meta/llama-3.3-70b-instruct-fp8-fast", "@cf/meta/llama-3.1-70b-instruct"];
export const WORKERS_WHISPER = "@cf/openai/whisper-large-v3-turbo";
export const WORKERS_WHISPER_CLASSIC = "@cf/openai/whisper";

// Modelele Meta cer o acceptare de licență unică per cont: promptul „agree”.
export async function runWithAgree(env, model, input) {
  try {
    return await env.AI.run(model, input);
  } catch (e) {
    const msg = errorText(e);
    if (msg.includes("5016") || msg.toLowerCase().includes("agree")) {
      try { await env.AI.run(model, { prompt: "agree" }); } catch (_) { }
      return await env.AI.run(model, input);
    }
    throw e;
  }
}

const outText = (r) => ((r && (r.response || r.description || r.text)) || "").toString();

/** Text liber cu modelele mari Llama; "" dacă niciunul nu răspunde. */
export async function runText(env, prompt, maxTokens = 900, system = "") {
  if (!env?.AI) return "";
  for (const model of WORKERS_TEXT_MODELS) {
    try {
      const r = await runWithAgree(env, model, {
        messages: [...(system ? [{ role: "system", content: system }] : []), { role: "user", content: prompt }],
        max_tokens: maxTokens, temperature: 0.2,
      });
      const out = outText(r);
      if (out.trim()) return out;
    } catch (_) { }
  }
  return "";
}

/** O descriere scurtă (engleză) a unei imagini, cu modelul de vedere; "" dacă nu iese nimic. `onCall` numără apelurile de model. */
export async function describeImage(env, b64, prompt, maxTokens = 300, onCall = null) {
  let bytes;
  try { bytes = b64ToBytes(b64); } catch (_) { return ""; }
  for (const model of [env?.ORGANIZE_VISION_MODEL, ...WORKERS_VISION_MODELS].filter(Boolean)) {
    try {
      if (onCall) onCall();
      const r = await runWithAgree(env, model, { image: [...bytes], prompt, max_tokens: maxTokens });
      const out = outText(r).trim();
      if (out.length > 3) return out.slice(0, 1200);
    } catch (_) { }
  }
  return "";
}

export const workers = {
  name: "workers",
  timeoutMs: 60000,
  supports: { images: true, documents: false, audio: false, verifyWithImages: false, transcribe: true },
  available: (env) => !!(env && env.AI && typeof env.AI.run === "function"),
  models: () => [WORKERS_TEXT_MODELS[0]],
  // Limita reală e 10 000 neuroni/zi (plan gratuit) și se vede doar în dash.cloudflare.com; aici numărăm apelurile env.AI.run,
  // deci nu pretindem o poartă zilnică pe care n-o putem măsura.
  dailyLimit: null,
  budgetUnit: "apeluri model (neuronii nu se măsoară aici)",

  /**
   * Imaginile devin descrieri (max 8), apoi modelul de text produce JSON-ul cerut. `trace` (de la router) primește
   * `visionNotes` (descrierile, refolosite la pasul de verificare, care nu vede poza) și `modelCalls` (câte apeluri env.AI.run au fost).
   */
  async generate(env, { model, system, prompt, images = [], maxTokens, timeoutMs, trace = null }) {
    const deadline = Date.now() + (timeoutMs || this.timeoutMs);
    const count = () => { if (trace) trace.modelCalls = (trace.modelCalls || 0) + 1; };
    const notes = [];
    for (const img of images.slice(0, 8)) {
      if (Date.now() > deadline - 5000) break;
      const desc = await describeImage(env, img.b64, img.describePrompt || "Describe everything visible in this photo in detail: objects, food items with estimated portion size in grams, text, quality (sharp/blurry), whether it is a screenshot, document or meme. Be factual, do not guess what is not visible.", 300, count);
      if (desc) notes.push(`${img.label ? "[" + img.label + "] " : ""}Descriere vizuală (model de vedere, engleză): ${desc}`);
    }
    if (images.length && !notes.length) throw new AiError("workers: modelele de vedere n-au putut citi poza", { provider: "workers", model, kind: "empty" });
    if (trace && notes.length) trace.visionNotes = notes.slice();
    const full = notes.length ? prompt + "\n\n" + notes.join("\n") : prompt;
    let lastErr = null;
    for (const m of [model, ...WORKERS_TEXT_MODELS].filter((x, i, a) => x && a.indexOf(x) === i)) {
      if (Date.now() > deadline) break;
      try {
        count();
        const r = await runWithAgree(env, m, {
          messages: [...(system ? [{ role: "system", content: system }] : []), { role: "user", content: full }],
          max_tokens: Math.min(4000, Math.max(256, maxTokens || 1400)), temperature: 0.2,
        });
        const out = outText(r);
        if (out.trim()) return out;
        lastErr = new AiError("workers: răspuns gol", { provider: "workers", model: m, kind: "empty" });
      } catch (e) { lastErr = new AiError("workers: " + errorText(e), { provider: "workers", model: m, kind: "http" }); }
    }
    throw lastErr || new AiError("workers indisponibil", { provider: "workers", model, kind: "network" });
  },

  /** Whisper large v3 turbo (base64) cu segmente; apoi Whisper clasic (octeți). `calls` = câte apeluri de model au fost. */
  async transcribe(env, { bytes, language = "" }) {
    const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    let calls = 0;
    try {
      calls++;
      const r = await env.AI.run(WORKERS_WHISPER, { audio: bytesToB64(u8), ...(language ? { language } : {}) });
      const text = String(r?.text || "").trim();
      const segments = (Array.isArray(r?.segments) ? r.segments : []).filter((s) => s && typeof s.text === "string" && Number.isFinite(s.start) && Number.isFinite(s.end) && s.end > s.start)
        .map((s) => ({ startMs: Math.round(s.start * 1000), endMs: Math.round(s.end * 1000), text: s.text.trim().slice(0, 400), noSpeechProb: Number.isFinite(s.no_speech_prob) ? s.no_speech_prob : null, avgLogprob: Number.isFinite(s.avg_logprob) ? s.avg_logprob : null }));
      if (text || segments.length) return { text, segments, language: "", model: WORKERS_WHISPER, calls };
    } catch (_) { }
    try {
      calls++;
      const r = await env.AI.run(WORKERS_WHISPER_CLASSIC, { audio: [...u8] });
      const text = String(r?.text || "").trim();
      return { text, segments: [], language: "", model: WORKERS_WHISPER_CLASSIC, calls };
    } catch (e) {
      throw new AiError("workers whisper: " + errorText(e), { provider: "workers", model: WORKERS_WHISPER_CLASSIC, kind: "http" });
    }
  },
};
