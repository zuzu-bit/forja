// Google Gemini (cheie GRATUITĂ din AI Studio, fără card) — poze, PDF și SINGURUL drum pentru AUDIO integral.
// gemini.mjs rămâne adaptorul site-ului (API neschimbat); de aici luăm doar conversia schemei.
//
// Lista de modele e DINAMICĂ (diagnosticul din 28.09: modelele 2.5 erau listate de GET /models, dar generateContent răspundea 404 —
// retrase pentru generare; cheia avea deja gemini-3.x și omni). La prima folosire din zi cerem GET /v1beta/models, păstrăm doar ce
// știe generateContent și ordonăm: flash (non-lite, non-preview) după versiune descrescător, apoi flash-lite, apoi pro, apoi
// preview-urile, iar aliasurile `-latest` ca rezervă. Un 404 pe un model îl marchează „retras” 24 h. Când descoperirea pică, lista statică.
import { AiError, postJson, fetchWithTimeout, labelText, readKey, readCached, writeCached, errorText } from "./ai-common.mjs";
import { geminiSchema } from "./gemini.mjs";

export const GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
export const GEMINI_LIST_URL = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100";
/** Lista statică de rezervă (când GET /models nu răspunde): ce știm că există pe cheile gratuite din septembrie 2026. */
export const GEMINI_MODELS = ["gemini-3.8-flash", "gemini-3.5-flash", "gemini-flash-latest", "gemini-3.1-pro-preview"];
/** Audio integral: modelele făcute pentru sunet (audio+video), înaintea flash-urilor obișnuite (care acceptă și ele audio inline). */
export const GEMINI_AUDIO_PREFERRED = ["gemini-omni-1.1-flash", "gemini-2.5-flash-native-audio-latest"];
export const GEMINI_TRANSCRIBE_MODEL = "gemini-3.5-transcribe";
export const GEMINI_CACHE_KEY = "ai-models/gemini.json";
export const GEMINI_CACHE_TTL_MS = 24 * 3600_000;
export const GEMINI_RETIRE_MS = 24 * 3600_000;
const STATIC_RETRY_MS = 10 * 60_000; // după o descoperire picată nu mai batem la /models la fiecare cerere
const DISCOVERY_TIMEOUT_MS = 8000;

// Nume care nu produc text/JSON din poze sau sunet: le excludem din listele de generare.
const EXCLUDED = /tts|image|embedding|robotics|computer-use|live|transcribe|native-audio|imagen|veo|aqa/;
const version = (name) => { const m = /(\d+(?:\.\d+)?)/.exec(name); return m ? parseFloat(m[1]) : 0; };
/** Grupul de ordonare: 0 flash, 1 flash-lite, 2 pro, 3 preview-uri/altele, 4 aliasuri `-latest` (fără versiune). */
function group(name) {
  const preview = /preview|exp/.test(name);
  if (/-latest$/.test(name) && !version(name)) return 4;
  if (/flash-lite/.test(name)) return preview ? 3 : 1;
  if (/flash/.test(name)) return preview ? 3 : 0;
  if (/pro/.test(name)) return 2;
  return 3;
}
const rank = (a, b) => group(a) - group(b) || version(b) - version(a) || a.localeCompare(b);
const shortName = (m) => String(m?.name || "").replace(/^models\//, "");
const generates = (m) => Array.isArray(m?.supportedGenerationMethods) && m.supportedGenerationMethods.includes("generateContent");

/**
 * Din răspunsul GET /v1beta/models (lista `models`) → { text, audio, transcribe }: doar `gemini-…` cu generateContent,
 * fără tts/image/embedding/robotics/computer-use/live/transcribe/native-audio la text; audio preferă omni și native-audio.
 */
export function rankGeminiModels(models) {
  const all = (Array.isArray(models) ? models : []).filter(generates).map(shortName).filter((n) => n.startsWith("gemini-"));
  const text = [...new Set(all.filter((n) => !EXCLUDED.test(n)))].sort(rank);
  const audio = [...new Set([...GEMINI_AUDIO_PREFERRED.filter((n) => all.includes(n)), ...text])];
  return { text, audio, transcribe: all.includes(GEMINI_TRANSCRIBE_MODEL) ? GEMINI_TRANSCRIBE_MODEL : null };
}

// ── Catalogul: memorie (per izolat) → KV/R2 (24 h) → GET /models → lista statică ──
let memory = null; // { catalog, readAt, failedAt? }
export function resetGeminiCache() { memory = null; }
const staticCatalog = () => ({ source: "static", text: GEMINI_MODELS.slice(), audio: GEMINI_MODELS.slice(), transcribe: null, retired: {}, discoveredAt: 0 });

async function discover(env) {
  const models = [];
  let url = GEMINI_LIST_URL;
  for (let page = 0; page < 3 && url; page++) {
    const resp = await fetchWithTimeout(url, { method: "GET", headers: { "x-goog-api-key": readKey(env, "GEMINI_API_KEY") } }, DISCOVERY_TIMEOUT_MS);
    if (!resp.ok) throw new AiError("gemini /models a răspuns cu " + resp.status, { provider: "gemini", status: resp.status, kind: "http" });
    const data = await resp.json().catch(() => null);
    if (!data || !Array.isArray(data.models)) throw new AiError("gemini /models: răspuns fără listă", { provider: "gemini", kind: "json" });
    models.push(...data.models);
    url = data.nextPageToken ? GEMINI_LIST_URL + "&pageToken=" + encodeURIComponent(data.nextPageToken) : "";
  }
  const ranked = rankGeminiModels(models);
  if (!ranked.text.length) throw new AiError("gemini /models: niciun model de generare", { provider: "gemini", kind: "empty" });
  return { source: "discovery", ...ranked, retired: {}, discoveredAt: Date.now() };
}

/** Catalogul curent (cu `retired`), din cache sau descoperit acum; niciodată nu aruncă. */
export async function geminiCatalog(env) {
  const now = Date.now();
  if (memory && memory.catalog.source === "discovery" && now - memory.readAt < GEMINI_CACHE_TTL_MS) return memory.catalog;
  if (memory && memory.catalog.source === "static" && now - memory.readAt < STATIC_RETRY_MS) return memory.catalog;
  const stored = await readCached(env, GEMINI_CACHE_KEY);
  if (stored && Array.isArray(stored.text) && stored.text.length) {
    const catalog = { source: stored.source || "discovery", text: stored.text, audio: Array.isArray(stored.audio) && stored.audio.length ? stored.audio : stored.text, transcribe: stored.transcribe || null, retired: stored.retired && typeof stored.retired === "object" ? stored.retired : {}, discoveredAt: Number(stored.discoveredAt) || Number(stored.at) || 0 };
    memory = { catalog, readAt: now };
    return catalog;
  }
  let catalog;
  try {
    catalog = await discover(env);
    if (memory?.catalog?.retired) catalog.retired = { ...memory.catalog.retired }; // ce am retras azi rămâne retras
    await writeCached(env, GEMINI_CACHE_KEY, catalog, GEMINI_CACHE_TTL_MS);
  } catch (e) {
    catalog = staticCatalog();
    catalog.error = errorText(e);
    if (memory?.catalog?.retired) catalog.retired = { ...memory.catalog.retired };
  }
  memory = { catalog, readAt: now };
  return catalog;
}

/** Un model a răspuns 404 la generateContent: e retras (nu mai există pentru generare) — îl sărim 24 h și trecem la următorul. */
export async function retireGeminiModel(env, model) {
  const catalog = await geminiCatalog(env);
  catalog.retired = { ...(catalog.retired || {}), [model]: Date.now() + GEMINI_RETIRE_MS };
  memory = { catalog, readAt: memory?.readAt || Date.now() };
  if (catalog.source === "discovery") await writeCached(env, GEMINI_CACHE_KEY, catalog, GEMINI_CACHE_TTL_MS);
}
const isRetired = (catalog, model) => Number(catalog.retired?.[model]) > Date.now();
const retiredNow = (catalog) => Object.keys(catalog.retired || {}).filter((m) => isRetired(catalog, m));

export const gemini = {
  name: "gemini",
  timeoutMs: 90000,
  audioTimeoutMs: 180000,
  supports: { images: true, documents: true, audio: true, verifyWithImages: true },
  available: (env) => readKey(env, "GEMINI_API_KEY").length > 0,
  /** Ordinea de încercare, descoperită (audio: omni → native-audio → flash-uri; transcriere: gemini-3.5-transcribe primul, dacă există). */
  async models(env, { audio = false, task = "" } = {}) {
    const catalog = await geminiCatalog(env);
    let list = audio ? catalog.audio : catalog.text;
    if (audio && task === "transcribe" && catalog.transcribe) list = [catalog.transcribe, ...list];
    const override = readKey(env, "GEMINI_MODEL");
    const open = [...new Set([...(override ? [override] : []), ...list])].filter((m) => !isRetired(catalog, m));
    return open.length ? open : GEMINI_MODELS.filter((m) => !isRetired(catalog, m));
  },
  /** 404 la generateContent = model retras: nu mai apare 24 h. */
  retire: (env, model) => retireGeminiModel(env, model),
  // Cotele gratuite sunt PER MODEL (găleți separate): un 429 la un model nu înseamnă că următorul e epuizat. Limitele exacte nu se
  // mai presupun (modelele se schimbă des): limita e null, iar 429 trece la modelul următor.
  dailyLimit: null,
  perModelQuota: true,
  modelLimits: {},
  /** Pentru /v1/diag: ordinea reală, sursa ei, modelele retrase azi — fără chei. */
  async catalogInfo(env) {
    const c = await geminiCatalog(env);
    return { sursa: c.source, descoperitLa: c.discoveredAt ? new Date(c.discoveredAt).toISOString() : null, ordine: await this.models(env), audio: await this.models(env, { audio: true }), transcriere: c.transcribe, retrase: retiredNow(c), ...(c.error ? { eroare: c.error } : {}) };
  },
  /** Verificarea cheii (pentru diag): codul HTTP al lui GET /models (200 = bună, 401/403 = respinsă, 0 = rețea). */
  async keyCheck(env) {
    try {
      const resp = await fetchWithTimeout(GEMINI_LIST_URL.replace("pageSize=100", "pageSize=1"), { method: "GET", headers: { "x-goog-api-key": readKey(env, "GEMINI_API_KEY") } }, DISCOVERY_TIMEOUT_MS);
      return resp.status;
    } catch (_) { return 0; }
  },

  async generate(env, { model, system, prompt, images = [], documents = [], audio = null, schema, maxTokens, timeoutMs, temperature, trace = null }) {
    const parts = [];
    for (const doc of documents) {
      parts.push({ text: labelText(doc, `[document ${doc.name || ""}]`.trim()) });
      parts.push({ inline_data: { mime_type: doc.mime || "application/pdf", data: doc.b64 } });
    }
    for (const img of images) {
      parts.push({ text: labelText(img, "[imagine]") });
      parts.push({ inline_data: { mime_type: img.mime || "image/jpeg", data: img.b64 } });
    }
    if (audio && audio.b64) parts.push({ inline_data: { mime_type: audio.mime || "audio/mp4", data: audio.b64 } });
    parts.push({ text: prompt });
    const generationConfig = {
      temperature: Number.isFinite(temperature) ? temperature : 0.1,
      maxOutputTokens: Math.min(16000, Math.max(256, maxTokens || 4000)),
    };
    // Flash gândește implicit și tokenii de gândire intră în maxOutputTokens; Pro nu acceptă buget 0.
    if (/flash/.test(model)) generationConfig.thinkingConfig = { thinkingBudget: 0 };
    if (schema) { generationConfig.responseMimeType = "application/json"; generationConfig.responseSchema = geminiSchema(schema); }
    const body = { ...(system ? { system_instruction: { parts: [{ text: system }] } } : {}), contents: [{ role: "user", parts }], generationConfig };
    const deadline = Date.now() + (timeoutMs || (audio ? this.audioTimeoutMs : this.timeoutMs));
    const headers = { "x-goog-api-key": readKey(env, "GEMINI_API_KEY") };
    const count = () => { if (trace) trace.modelCalls = (trace.modelCalls || 0) + 1; };
    const post = () => { count(); return postJson(GEMINI_BASE + model + ":generateContent", headers, body, Math.max(1000, deadline - Date.now()), "gemini", model); };
    let data;
    try { data = await post(); }
    catch (e) {
      // Un 400 vine de obicei din generationConfig (un model nou care nu acceptă thinkingBudget 0, sau o schemă prea strictă):
      // reîncercăm o dată fără thinkingConfig, apoi o dată fără responseSchema — abia apoi renunțăm la model.
      if (e.status === 400 && generationConfig.thinkingConfig && deadline - Date.now() > 3000) {
        delete generationConfig.thinkingConfig;
        try { data = await post(); } catch (e2) { if (e2.status === 400 && generationConfig.responseSchema && deadline - Date.now() > 3000) { delete generationConfig.responseSchema; data = await post(); } else throw e2; }
      } else if (e.status === 400 && generationConfig.responseSchema && deadline - Date.now() > 3000) {
        delete generationConfig.responseSchema;
        data = await post();
      } else throw e;
    }
    if (data?.promptFeedback?.blockReason) throw new AiError("gemini a blocat cererea", { provider: "gemini", model, kind: "blocked" });
    const candidate = data?.candidates?.[0];
    const text = (candidate?.content?.parts ?? []).filter((p) => typeof p?.text === "string").map((p) => p.text).join("");
    if (!text.trim()) throw new AiError("gemini: răspuns gol (" + String(candidate?.finishReason || "?").toLowerCase() + ")", { provider: "gemini", model, kind: "empty" });
    return text;
  },
};
