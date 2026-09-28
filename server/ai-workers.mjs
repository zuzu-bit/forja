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

// Cu `response_format: json_schema` Workers AI întoarce uneori `response` gata parsat (obiect): îl serializăm ca text pentru extractor.
const outText = (r) => {
  const v = r && (r.response ?? r.description ?? r.text);
  if (v && typeof v === "object") { try { return JSON.stringify(v); } catch (_) { return ""; } }
  return (v ?? "").toString();
};

/**
 * Schema noastră → JSON Schema pe care îl acceptă `response_format: { type: "json_schema" }` la Workers AI (llama-3.3-70b):
 * `nullable` devine `type: [t, "null"]`, `const` devine `enum`, restul cuvintelor cunoscute rămân, ce nu e standard dispare.
 */
export function workersJsonSchema(schema) {
  if (Array.isArray(schema)) return schema.map(workersJsonSchema);
  if (!schema || typeof schema !== "object") return schema;
  const out = {};
  for (const [k, v] of Object.entries(schema)) {
    if (k === "nullable") continue;
    if (k === "const") { out.enum = [v]; continue; }
    if (k === "properties") { out.properties = Object.fromEntries(Object.entries(v).map(([n, sub]) => [n, workersJsonSchema(sub)])); continue; }
    if (k === "items" || k === "anyOf") { out[k] = workersJsonSchema(v); continue; }
    if (["type", "enum", "required", "minimum", "maximum", "minItems", "maxItems", "minLength", "maxLength", "description"].includes(k)) out[k] = v;
  }
  if (schema.nullable && out.type) out.type = [...new Set([...(Array.isArray(out.type) ? out.type : [out.type]), "null"])];
  return out;
}
const JSON_ONLY = "\n\nRăspunde DOAR cu obiectul JSON cerut: fără explicații, fără text înainte sau după, fără ```.";
// Când runtime-ul respinge response_format (model/versiune fără suport), mesajul de eroare pomenește de obicei formatul sau schema.
const rejectsFormat = (e) => /response_format|json_schema|schema|unsupported|not supported|invalid.*format|5006|3010/i.test(errorText(e));

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
   * `visionNotes` (descrierile, refolosite la pasul de verificare, care nu vede poza), `visionLabels` (etichetele pozelor descrise
   * cu succes — ex. grupurile văzute la /v1/organize/clusters) și `modelCalls` (câte apeluri env.AI.run au fost). Descrierile rămân
   * în `trace` pe durata cererii: repararea JSON-ului nu mai descrie pozele a doua oară. `describeConcurrency` (1–4, implicit 1)
   * descrie mai multe poze în paralel.
   */
  async generate(env, { model, system, prompt, images = [], schema = null, maxTokens, timeoutMs, trace = null, describeConcurrency = 1 }) {
    const deadline = Date.now() + (timeoutMs || this.timeoutMs);
    const count = () => { if (trace) trace.modelCalls = (trace.modelCalls || 0) + 1; };
    const described = trace ? (trace.described instanceof Map ? trace.described : (trace.described = new Map())) : new Map();
    const notes = [];
    const labels = [];
    const list = images.slice(0, 8);
    const step = Math.max(1, Math.min(4, Math.floor(Number(describeConcurrency)) || 1));
    for (let i = 0; i < list.length; i += step) {
      if (Date.now() > deadline - 5000) break;
      const batch = list.slice(i, i + step);
      const descs = await Promise.all(batch.map(async (img) => {
        if (described.has(img)) return described.get(img);
        const d = await describeImage(env, img.b64, img.describePrompt || "Describe everything visible in this photo in detail: objects, food items with estimated portion size in grams, text, quality (sharp/blurry), whether it is a screenshot, document or meme. Be factual, do not guess what is not visible.", 300, count);
        if (d) described.set(img, d);
        return d;
      }));
      batch.forEach((img, j) => {
        if (!descs[j]) return;
        notes.push(`${img.label ? "[" + img.label + "] " : ""}Descriere vizuală (model de vedere, engleză): ${descs[j]}`);
        if (img.label) labels.push(String(img.label));
      });
    }
    if (images.length && !notes.length) throw new AiError("workers: modelele de vedere n-au putut citi poza", { provider: "workers", model, kind: "empty" });
    if (trace && notes.length) { trace.visionNotes = notes.slice(); trace.visionLabels = labels.slice(); }
    const full = notes.length ? prompt + "\n\n" + notes.join("\n") : prompt;
    let lastErr = null;
    // JSON forțat: `response_format: json_schema` (suportat de llama-3.3-70b). Dacă runtime-ul îl respinge, același model primește
    // promptul „doar JSON”; extractorul tolerant și repararea (o dată) sunt în router.
    const format = schema ? { type: "json_schema", json_schema: { name: "raspuns", schema: workersJsonSchema(schema) } } : null;
    const input = (withFormat) => ({
      messages: [...(system ? [{ role: "system", content: system }] : []), { role: "user", content: withFormat ? full : full + JSON_ONLY }],
      max_tokens: Math.min(4000, Math.max(256, maxTokens || 1400)), temperature: 0.2,
      ...(withFormat && format ? { response_format: format } : {}),
    });
    for (const m of [model, ...WORKERS_TEXT_MODELS].filter((x, i, a) => x && a.indexOf(x) === i)) {
      if (Date.now() > deadline) break;
      let r;
      try {
        count();
        r = await runWithAgree(env, m, input(!!format));
      } catch (e) {
        if (format && rejectsFormat(e) && Date.now() < deadline) {
          try { count(); r = await runWithAgree(env, m, input(false)); }
          catch (e2) { lastErr = new AiError("workers: " + errorText(e2), { provider: "workers", model: m, kind: "http" }); continue; }
        } else { lastErr = new AiError("workers: " + errorText(e), { provider: "workers", model: m, kind: "http" }); continue; }
      }
      const out = outText(r);
      if (out.trim()) return out;
      lastErr = new AiError("workers: răspuns gol", { provider: "workers", model: m, kind: "empty" });
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
