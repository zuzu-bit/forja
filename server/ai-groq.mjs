// Groq (cheie GRATUITĂ de la console.groq.com, fără card) — API compatibil OpenAI: viziune, text Llama 3.3 70B,
// audio Whisper large v3 cu timpi pe segmente (verbose_json). A doua opinie gratuită după Gemini.
//
// Lista de modele e DINAMICĂ (testul cap-coadă din 28.09, cu cheie validă: Llama 4 Scout și Maverick răspundeau 404 — retrase de pe Groq).
// GET /openai/v1/models (Bearer), cache 24 h în R2 `ai-models/groq.json`; capacitatea de viziune se deduce din id (euristici), id-urile
// necunoscute sunt doar text; 404 pe un model → retras 24 h. Fără niciun model de viziune, Groq e SĂRIT la poze (fără eroare).
import { AiError, fetchWithTimeout, readKey, readCached, writeCached, errorText } from "./ai-common.mjs";
import { chatCompletionsJson } from "./ai-openai.mjs";

export const GROQ_BASE = "https://api.groq.com/openai/v1";
export const GROQ_MODELS_URL = GROQ_BASE + "/models";
/** Listele statice de rezervă (când GET /models nu răspunde): ce era pe Groq în septembrie 2026. */
export const GROQ_VISION_MODELS = ["meta-llama/llama-4-scout-17b-16e-instruct", "meta-llama/llama-4-maverick-17b-128e-instruct"];
export const GROQ_TEXT_MODEL = "llama-3.3-70b-versatile";
export const GROQ_WHISPER_MODEL = "whisper-large-v3";
export const GROQ_CACHE_KEY = "ai-models/groq.json";
export const GROQ_CACHE_TTL_MS = 24 * 3600_000;
export const GROQ_RETIRE_MS = 24 * 3600_000;
const STATIC_RETRY_MS = 10 * 60_000;
const DISCOVERY_TIMEOUT_MS = 8000;

// Euristici pe id, în ordinea preferinței. Viziune: doar ce știm sigur că primește imagini; restul e text.
const VISION_PATTERNS = [/llama-4/, /vision/, /gemma-3/, /qwen.*(vl|omni)/, /pixtral/, /mistral.*(small|medium).*(3|4)/];
const TEXT_PATTERNS = [/llama-3\.3-70b/, /llama-4/, /qwen3/, /gpt-oss/, /kimi/, /deepseek/, /compound/];
const WHISPER_PATTERNS = [/^whisper-large-v3$/, /whisper-large-v3-turbo/, /whisper/];
const NOT_CHAT = /whisper|tts|guard|embed|moderation|safeguard/;
const patternRank = (patterns) => (id) => { const i = patterns.findIndex((re) => re.test(id)); return i < 0 ? patterns.length : i; };
const byRank = (patterns) => { const r = patternRank(patterns); return (a, b) => r(a) - r(b) || a.localeCompare(b); };

/** Din răspunsul GET /models (`data: [{id}]`) → { vision, text, whisper }. Id-urile necunoscute sunt doar text; fără viziune → `vision: []`. */
export function rankGroqModels(data) {
  const ids = [...new Set((Array.isArray(data) ? data : []).map((m) => String(m?.id || "")).filter(Boolean))];
  const chat = ids.filter((id) => !NOT_CHAT.test(id));
  const vision = chat.filter((id) => VISION_PATTERNS.some((re) => re.test(id))).sort(byRank(VISION_PATTERNS));
  const text = chat.filter((id) => TEXT_PATTERNS.some((re) => re.test(id))).sort(byRank(TEXT_PATTERNS));
  const whisper = ids.filter((id) => /whisper/.test(id)).sort(byRank(WHISPER_PATTERNS));
  return { vision, text, whisper };
}

// ── Catalogul: memorie (per izolat) → KV/R2 (24 h) → GET /models → listele statice ──
let memory = null;
export function resetGroqCache() { memory = null; }
const staticCatalog = () => ({ source: "static", vision: GROQ_VISION_MODELS.slice(), text: [GROQ_TEXT_MODEL], whisper: [GROQ_WHISPER_MODEL], retired: {}, discoveredAt: 0 });
const authHeaders = (env) => ({ authorization: "Bearer " + readKey(env, "GROQ_API_KEY") });

async function discover(env) {
  const resp = await fetchWithTimeout(GROQ_MODELS_URL, { method: "GET", headers: authHeaders(env) }, DISCOVERY_TIMEOUT_MS);
  if (!resp.ok) throw new AiError("groq /models a răspuns cu " + resp.status, { provider: "groq", status: resp.status, kind: "http" });
  const data = await resp.json().catch(() => null);
  if (!data || !Array.isArray(data.data)) throw new AiError("groq /models: răspuns fără listă", { provider: "groq", kind: "json" });
  const ranked = rankGroqModels(data.data);
  if (!ranked.text.length && !ranked.vision.length) throw new AiError("groq /models: niciun model de chat cunoscut", { provider: "groq", kind: "empty" });
  return { source: "discovery", ...ranked, retired: {}, discoveredAt: Date.now() };
}

/** Catalogul curent (cu `retired`), din cache sau descoperit acum; niciodată nu aruncă. */
export async function groqCatalog(env) {
  const now = Date.now();
  if (memory && memory.catalog.source === "discovery" && now - memory.readAt < GROQ_CACHE_TTL_MS) return memory.catalog;
  if (memory && memory.catalog.source === "static" && now - memory.readAt < STATIC_RETRY_MS) return memory.catalog;
  const stored = await readCached(env, GROQ_CACHE_KEY);
  if (stored && (Array.isArray(stored.text) || Array.isArray(stored.vision))) {
    const catalog = { source: stored.source || "discovery", vision: Array.isArray(stored.vision) ? stored.vision : [], text: Array.isArray(stored.text) ? stored.text : [], whisper: Array.isArray(stored.whisper) && stored.whisper.length ? stored.whisper : [GROQ_WHISPER_MODEL], retired: stored.retired && typeof stored.retired === "object" ? stored.retired : {}, discoveredAt: Number(stored.discoveredAt) || Number(stored.at) || 0 };
    memory = { catalog, readAt: now };
    return catalog;
  }
  let catalog;
  try {
    catalog = await discover(env);
    if (memory?.catalog?.retired) catalog.retired = { ...memory.catalog.retired };
    await writeCached(env, GROQ_CACHE_KEY, catalog, GROQ_CACHE_TTL_MS);
  } catch (e) {
    catalog = staticCatalog();
    catalog.error = errorText(e);
    if (memory?.catalog?.retired) catalog.retired = { ...memory.catalog.retired };
  }
  memory = { catalog, readAt: now };
  return catalog;
}

/** Un model a răspuns 404: e retras — îl sărim 24 h și trecem la următorul. */
export async function retireGroqModel(env, model) {
  const catalog = await groqCatalog(env);
  catalog.retired = { ...(catalog.retired || {}), [model]: Date.now() + GROQ_RETIRE_MS };
  memory = { catalog, readAt: memory?.readAt || Date.now() };
  if (catalog.source === "discovery") await writeCached(env, GROQ_CACHE_KEY, catalog, GROQ_CACHE_TTL_MS);
}
const isRetired = (catalog, model) => Number(catalog.retired?.[model]) > Date.now();
const openOnly = (catalog, list) => list.filter((m) => !isRetired(catalog, m));
const retiredNow = (catalog) => Object.keys(catalog.retired || {}).filter((m) => isRetired(catalog, m));

export const groq = {
  name: "groq",
  timeoutMs: 60000,
  supports: { images: true, documents: false, audio: false, verifyWithImages: false, transcribe: true },
  // Cheia e citită cu trim(): un secret lipit cu spații sau ghilimele dă 401 fără explicații. O cheie Groq validă începe cu `gsk_`.
  available: (env) => readKey(env, "GROQ_API_KEY").length > 0,
  /**
   * Cu imagini: modelele de viziune descoperite (LISTĂ GOALĂ = Groq nu are model de viziune acum → routerul îl sare la poze, fără eroare);
   * doar text: Llama 3.3 70B primul (mai bun la română și la JSON), apoi restul cunoscut. `GROQ_VISION_MODEL` / `GROQ_TEXT_MODEL` din env trec primele.
   */
  async models(env, { images = [] } = {}) {
    const catalog = await groqCatalog(env);
    const override = readKey(env, images.length ? "GROQ_VISION_MODEL" : "GROQ_TEXT_MODEL");
    const list = openOnly(catalog, [...new Set([...(override ? [override] : []), ...(images.length ? catalog.vision : catalog.text)])]);
    if (!list.length && !images.length) return openOnly(catalog, [GROQ_TEXT_MODEL]);
    return list;
  },
  /** 404 la chat/completions = model retras de pe Groq (Llama 4 în 28.09): nu mai apare 24 h. */
  retire: (env, model) => retireGroqModel(env, model),
  /** Pentru /v1/diag: listele descoperite, sursa, modelele retrase — fără chei. */
  async catalogInfo(env) {
    const c = await groqCatalog(env);
    const viziune = await this.models(env, { images: [{}] });
    return { sursa: c.source, descoperitLa: c.discoveredAt ? new Date(c.discoveredAt).toISOString() : null, viziune, text: await this.models(env), whisper: openOnly(c, c.whisper)[0] || null, retrase: retiredNow(c), ...(viziune.length ? {} : { nota: "fără model de viziune pe Groq: la poze e sărit, următorul furnizor preia" }), ...(c.error ? { eroare: c.error } : {}) };
  },
  dailyLimit: 14400,
  generate(env, opts) {
    return chatCompletionsJson({ ...opts, url: GROQ_BASE + "/chat/completions", apiKey: readKey(env, "GROQ_API_KEY"), provider: "groq", timeoutMs: opts.timeoutMs || this.timeoutMs });
  },
  /** Verificarea cheii (pentru diag): codul HTTP al lui GET /models (200 = bună, 401 = respinsă — cheia trebuie regenerată, format `gsk_…`; 0 = rețea). */
  async keyCheck(env) {
    try {
      const resp = await fetchWithTimeout(GROQ_MODELS_URL, { method: "GET", headers: authHeaders(env) }, 8000);
      return resp.status;
    } catch (_) { return 0; }
  },

  /** Transcriere cu timpi: {text, segments:[{startMs,endMs,text,noSpeechProb}], language}. */
  async transcribe(env, { bytes, mime = "audio/mp4", language = "", timeoutMs }) {
    const catalog = await groqCatalog(env);
    const GROQ_WHISPER_MODEL = openOnly(catalog, catalog.whisper)[0] || catalog.whisper[0] || "whisper-large-v3";
    const form = new FormData();
    const ext = /wav/.test(mime) ? "wav" : /mpeg|mp3/.test(mime) ? "mp3" : "m4a";
    form.append("file", new Blob([bytes], { type: mime }), "clip." + ext);
    form.append("model", GROQ_WHISPER_MODEL);
    form.append("response_format", "verbose_json");
    form.append("temperature", "0");
    if (language) form.append("language", language);
    const resp = await fetchWithTimeout(GROQ_BASE + "/audio/transcriptions", { method: "POST", headers: authHeaders(env), body: form }, timeoutMs || 120000)
      .catch((e) => { e.provider = "groq"; e.model = GROQ_WHISPER_MODEL; throw e; });
    if (!resp.ok) throw new AiError("groq a răspuns cu " + resp.status, { provider: "groq", model: GROQ_WHISPER_MODEL, status: resp.status, fatal: resp.status === 429 || resp.status === 401 || resp.status === 403 });
    const data = await resp.json().catch(() => { throw new AiError("groq: răspuns care nu e JSON", { provider: "groq", model: GROQ_WHISPER_MODEL, kind: "json" }); });
    const segments = (Array.isArray(data?.segments) ? data.segments : []).filter((s) => s && typeof s.text === "string" && Number.isFinite(s.start) && Number.isFinite(s.end) && s.end > s.start)
      .map((s) => ({ startMs: Math.round(s.start * 1000), endMs: Math.round(s.end * 1000), text: s.text.trim().slice(0, 400), noSpeechProb: Number.isFinite(s.no_speech_prob) ? s.no_speech_prob : null, avgLogprob: Number.isFinite(s.avg_logprob) ? s.avg_logprob : null }));
    return { text: String(data?.text || "").trim(), segments, language: String(data?.language || "").slice(0, 16), model: GROQ_WHISPER_MODEL };
  },
};
