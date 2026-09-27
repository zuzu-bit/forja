// Routerul AI — un singur punct de intrare pentru tot ce cere un model pe forja-api.
// Ordinea (decizia „fără bani”): gemini (cheie gratuită) → groq (cheie gratuită) → anthropic / openai (doar cu chei plătite) → workers (fără cheie).
// Reguli: eroare de rețea/HTTP/JSON invalid → următorul model/furnizor; extragere JSON strictă + validare cu schema;
// o singură reîncercare „repară JSON-ul” per furnizor; twoPass = a doua trecere de verificare; contor zilnic per furnizor.
import { AiError, errorText, bytesToB64 } from "./ai-common.mjs";
import { extractJsonStrict, validate, TRANSCRIPT_SCHEMA } from "./ai-schemas.mjs";
import { gemini } from "./ai-gemini.mjs";
import { groq } from "./ai-groq.mjs";
import { anthropic } from "./ai-anthropic.mjs";
import { openai } from "./ai-openai.mjs";
import { workers } from "./ai-workers.mjs";

export const ALL_PROVIDERS = [gemini, groq, anthropic, openai, workers];
export const KNOWN_LIMITS = { gemini: "~250 cereri/zi la Flash (nivel gratuit)", groq: "~14 400 cereri/zi (nivel gratuit)", workers: "10 000 neuroni/zi (plan gratuit)", anthropic: "după plată", openai: "după plată" };

/** Furnizorii configurați, în ordinea de încercare. */
export function providers(env) {
  return ALL_PROVIDERS.filter((p) => p.available(env));
}

// ── Ultimul furnizor folosit per sarcină (doar nume, niciodată chei) ──
const lastUsedByTask = new Map();
export function lastUsed() { return Object.fromEntries(lastUsedByTask); }
function noteUse(task, provider, model, ms) { lastUsedByTask.set(task || "?", { provider, model, at: Date.now(), ms }); }

// ── Contor zilnic per furnizor: `ai-budget:{provider}:{day}` în KV (env.AI_BUDGET) sau R2 (env.RECORDS), cu cache în memorie ──
const budgetCache = new Map();
export const dayKey = (at = Date.now()) => new Date(at).toISOString().slice(0, 10);
const budgetStore = (env) => (env?.AI_BUDGET && typeof env.AI_BUDGET.get === "function" ? { kind: "kv", s: env.AI_BUDGET } : env?.RECORDS && typeof env.RECORDS.get === "function" ? { kind: "r2", s: env.RECORDS } : null);
async function readBudget(env, provider, day) {
  const key = `ai-budget:${provider}:${day}`;
  if (budgetCache.has(key)) return budgetCache.get(key);
  let n = 0;
  const store = budgetStore(env);
  try {
    if (store?.kind === "kv") n = Number(await store.s.get(key)) || 0;
    else if (store?.kind === "r2") { const o = await store.s.get(key); n = o ? Number(await o.text()) || 0 : 0; }
  } catch (_) { }
  budgetCache.set(key, n);
  return n;
}
async function bumpBudget(env, provider) {
  const day = dayKey();
  const n = (await readBudget(env, provider, day)) + 1;
  const key = `ai-budget:${provider}:${day}`;
  budgetCache.set(key, n);
  const store = budgetStore(env);
  try {
    if (store?.kind === "kv") await store.s.put(key, String(n), { expirationTtl: 3 * 86400 });
    else if (store?.kind === "r2") await store.s.put(key, String(n), { customMetadata: { at: String(Date.now()), ttl: String(3 * 86400_000) } });
  } catch (_) { }
  return n;
}
/** Consumul de azi per furnizor + limitele cunoscute — pentru /v1/diag. */
export async function budgetSnapshot(env) {
  const day = dayKey();
  const out = {};
  for (const p of ALL_PROVIDERS) {
    const used = await readBudget(env, p.name, day);
    out[p.name] = { azi: used, limita: p.dailyLimit, cunoscut: KNOWN_LIMITS[p.name] || "" };
  }
  return out;
}
export function resetBudgetCache() { budgetCache.clear(); lastUsedByTask.clear(); }

const REPAIR_NOTE = "\n\nATENȚIE: răspunsul anterior NU a fost JSON valid conform schemei. Probleme: ";
const VERIFY_NOTE =
  "Verifică JSON-ul de mai jos față de imagine. Corectează porțiile nerealiste și totalurile: pentru fiecare componentă kcal trebuie să fie " +
  "aproximativ 4×proteine + 4×carbo + 9×grăsimi (±15 %). Coboară încrederea acolo unde nu se vede clar (grăsimi de gătit, sosuri, umplutură). " +
  "Nu adăuga componente care nu se văd. Răspunde DOAR cu JSON-ul corectat, aceeași structură.\n\nJSON de verificat:\n";

function parseAndValidate(text, schema) {
  const json = extractJsonStrict(text);
  if (json === null || typeof json !== "object") return { json: null, errors: ["răspunsul nu conține un obiect JSON"] };
  const v = schema ? validate(schema, json) : { ok: true, errors: [] };
  return v.ok ? { json, errors: [] } : { json: null, errors: v.errors };
}

/**
 * Motorul comun: încearcă furnizorii în ordine, modelele fiecăruia în ordine, cu o singură reparare de JSON per furnizor.
 * Întoarce {json, provider, model, ms, verified?} sau aruncă AiError cu lista încercărilor (fără chei).
 */
async function runJson(env, opts, need) {
  const t0 = Date.now();
  const attempts = [];
  const list = providers(env).filter((p) => (!need.audio || p.supports.audio));
  if (!list.length) throw new AiError(need.audio ? "Nu există furnizor pentru audio (lipsă cheie Gemini)." : "Niciun furnizor AI configurat.", { kind: "unsupported" });
  const images = Array.isArray(opts.images) ? opts.images : [];
  const documents = Array.isArray(opts.documents) ? opts.documents : [];
  for (const p of list) {
    const day = dayKey();
    if (p.dailyLimit && (await readBudget(env, p.name, day)) >= p.dailyLimit) { attempts.push(`${p.name}: limita zilnică atinsă`); continue; }
    const imgs = p.supports.images ? images : [];
    const docs = p.supports.documents ? documents : [];
    const budgetMs = opts.timeoutMs || (need.audio ? p.audioTimeoutMs || p.timeoutMs : p.timeoutMs);
    let repaired = false;
    let stop = false;
    for (const model of p.models(env, { images: imgs })) {
      const deadline = Date.now() + budgetMs;
      const remaining = () => deadline - Date.now();
      const call = (prompt) => p.generate(env, { ...opts, model, prompt, images: imgs, documents: docs, audio: need.audio ? opts.audio : null, timeoutMs: Math.max(1000, remaining()) });
      let text;
      try { text = await call(opts.prompt); }
      catch (e) { attempts.push(`${p.name}/${model}: ${errorText(e)}`); if (e && e.fatal) { stop = true; break; } continue; }
      let { json, errors } = parseAndValidate(text, opts.schema);
      if (!json && !repaired && remaining() > 5000) {
        repaired = true;
        try {
          const text2 = await call(opts.prompt + REPAIR_NOTE + errors.join("; ") + ". Răspunde DOAR cu obiectul JSON cerut, fără text în plus, fără ```.");
          ({ json, errors } = parseAndValidate(text2, opts.schema));
        } catch (e) { attempts.push(`${p.name}/${model} (reparare): ${errorText(e)}`); if (e && e.fatal) { stop = true; break; } }
      }
      if (!json) { attempts.push(`${p.name}/${model}: JSON invalid (${errors.slice(0, 3).join("; ")})`); continue; }
      await bumpBudget(env, p.name);
      let verified = false;
      if (opts.twoPass && remaining() > 8000) {
        try {
          const verifyImgs = p.supports.verifyWithImages ? imgs : [];
          const text3 = await p.generate(env, { ...opts, model, prompt: (opts.verifyPrompt || VERIFY_NOTE) + JSON.stringify(json), images: verifyImgs, documents: [], audio: null, timeoutMs: Math.max(1000, remaining()) });
          const second = parseAndValidate(text3, opts.schema);
          if (second.json) { json = second.json; verified = true; }
          await bumpBudget(env, p.name);
        } catch (_) { /* pasul 1 rămâne */ }
      }
      const ms = Date.now() - t0;
      noteUse(opts.task, p.name, model, ms);
      return { json, provider: p.name, model, ms, verified, attempts };
    }
    if (stop) continue;
  }
  const err = new AiError("Niciun model n-a răspuns valid. " + attempts.slice(-3).join(" | "), { kind: "http" });
  err.attempts = attempts;
  throw err;
}

/** Poze (+PDF-uri la anthropic/gemini) → JSON validat. images:[{b64,mime,label?}], documents:[{b64,mime,name,label?}]. */
export function visionJson(env, opts) { return runJson(env, opts, { audio: false }); }
/** Doar text → JSON validat. */
export function textJson(env, opts) { return runJson(env, { ...opts, images: [], documents: [] }, { audio: false }); }
/** Audio integral → JSON validat; doar la furnizorii cu audio (Gemini). Fără ei → null. */
export async function audioJson(env, opts) {
  if (!providers(env).some((p) => p.supports.audio)) return null;
  return runJson(env, { ...opts, images: [], documents: [] }, { audio: true });
}
export const hasAudioProvider = (env) => providers(env).some((p) => p.supports.audio);

/**
 * Transcriere cu timpi: gemini (dacă există) → groq whisper → workers whisper. Întoarce {text, segments, language, provider, model, ms}
 * sau aruncă. Segmentele sunt {startMs,endMs,text} față de începutul clipului.
 */
export async function transcribe(env, { bytes, mime = "audio/mp4", language = "", b64 = null }) {
  const t0 = Date.now();
  const attempts = [];
  for (const p of providers(env)) {
    if (p.supports.audio) {
      try {
        const audio = { b64: b64 || bytesToB64(bytes), mime };
        const prompt = "Transcrie EXACT ce se aude, în limba vorbită, fără completări sau corecturi. Dacă nu se vorbește, transcript gol și segments []. " +
          "Împarte în segmente cu timpi în milisecunde de la începutul clipului. Răspunde DOAR cu JSON: {\"transcript\":\"...\",\"language\":\"ro\",\"segments\":[{\"startMs\":0,\"endMs\":0,\"text\":\"...\"}]}.";
        const r = await runJson(env, { task: "transcribe", prompt, audio, schema: TRANSCRIPT_SCHEMA, maxTokens: 4000 }, { audio: true });
        const segments = (Array.isArray(r.json.segments) ? r.json.segments : []).filter((s) => s && typeof s.text === "string" && Number.isFinite(s.startMs) && Number.isFinite(s.endMs) && s.endMs > s.startMs)
          .map((s) => ({ startMs: Math.round(s.startMs), endMs: Math.round(s.endMs), text: s.text.trim().slice(0, 400), noSpeechProb: null, avgLogprob: null }));
        return { text: String(r.json.transcript || "").trim(), segments, language: String(r.json.language || ""), provider: r.provider, model: r.model, ms: Date.now() - t0 };
      } catch (e) { attempts.push(`${p.name}: ${errorText(e)}`); continue; }
    }
    if (typeof p.transcribe !== "function") continue;
    try {
      const r = await p.transcribe(env, { bytes, mime, language });
      await bumpBudget(env, p.name);
      noteUse("transcribe", p.name, r.model, Date.now() - t0);
      return { ...r, provider: p.name, ms: Date.now() - t0 };
    } catch (e) { attempts.push(`${p.name}: ${errorText(e)}`); }
  }
  const err = new AiError("Transcrierea n-a reușit. " + attempts.slice(-2).join(" | "), { kind: "http" });
  err.attempts = attempts;
  throw err;
}

/** Raportul pentru /v1/diag: configurat/absent per furnizor, ordinea reală, consum, ultimul folosit — fără chei, niciodată. */
export async function diagProviders(env) {
  const configured = {};
  for (const p of ALL_PROVIDERS) configured[p.name] = p.available(env) ? "configured" : "absent";
  const order = providers(env).map((p) => p.name);
  const keyed = order.filter((n) => n !== "workers");
  return {
    providers: configured,
    order,
    mode: keyed.length ? "chei: " + keyed.join(", ") + " (apoi Workers AI)" : "fără chei — doar modelele Cloudflare (mai slabe); adaugă GEMINI_API_KEY sau GROQ_API_KEY (gratuite)",
    audio: gemini.available(env) ? "gemini (ascultare integrală)" : groq.available(env) ? "groq whisper (doar transcriere cu timpi)" : workers.available(env) ? "workers whisper (doar transcriere)" : "indisponibil",
    lastUsed: lastUsed(),
    budget: await budgetSnapshot(env),
  };
}
