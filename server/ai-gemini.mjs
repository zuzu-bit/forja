// Google Gemini (cheie GRATUITĂ din AI Studio, fără card) — poze, PDF și SINGURUL drum pentru AUDIO integral.
// gemini.mjs rămâne adaptorul site-ului (API neschimbat); de aici luăm doar conversia schemei.
//
// Lista de modele e DINAMICĂ (diagnosticul din 28.09: modelele 2.5 erau listate de GET /models, dar generateContent răspundea 404 —
// retrase pentru generare; cheia avea deja gemini-3.x și omni). La prima folosire din zi cerem GET /v1beta/models, păstrăm doar ce
// știe generateContent și ordonăm: flash (non-lite, non-preview) după versiune descrescător, apoi flash-lite, apoi pro, apoi
// preview-urile, iar aliasurile `-latest` ca rezervă. Un 404 pe un model îl marchează „retras” 24 h. Când descoperirea pică, lista statică.
//
// Acordajul 4.3 (sonda directă din 28.09, cheia gratuită a Lanei — server/AI.md, „Sonda Gemini”):
// - gândirea se alege pe FAMILIE (geminiThinking): 3.x flash → thinkingLevel "low" („minimal” dă 400 pe 3.8-flash), 3.x flash-lite →
//   "minimal" (thinkingBudget 0 dă 400 „invalid argument” pe 3.5-flash-lite), 2.5 flash → thinkingBudget 0, pro/preview → "low",
//   aliasurile `-latest` → fără thinkingConfig; un 400 care pomenește gândirea / „invalid argument” → o reîncercare fără thinkingConfig;
// - 503 „high demand” (UNAVAILABLE) → o reîncercare pe ACELAȘI model după 1,5 s, apoi modelul următor;
// - 429 cu „limit: N, model: X” → X (și modelul cerut) în RĂCIRE 65 s (memorie + `ai-cooldown/<model>.json`), fără retragere;
//   routerul sare modelele în răcire (hook-ul `cooling`);
// - preferința pe sarcină e o sortare peste lista descoperită (preferForTask): mese/rezumate → cel mai bun flash, curățenie în masă →
//   flash-lite întâi, audio → omni întâi.
import { AiError, fetchWithTimeout, labelText, readKey, readCached, writeCached, errorText } from "./ai-common.mjs";
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
/** 503 „high demand”: o singură reîncercare pe același model, după atât. */
export const GEMINI_503_RETRY_MS = 1500;
/** 429 (cota gratuită e pe MINUT: 5 cereri/min pe gemini-3.8-flash, sonda din 28.09): modelul stă deoparte atât. */
export const GEMINI_COOLDOWN_MS = 65_000;
/** 429 pe o cotă zilnică (quotaId …PerDay…): o oră, apoi o nouă încercare (cota se reface la miezul nopții, ora Pacificului). */
export const GEMINI_COOLDOWN_DAY_MS = 3600_000;
export const GEMINI_COOLDOWN_PREFIX = "ai-cooldown/";
const COOLDOWN_RECHECK_MS = 15_000; // cât de des un izolat recitește din R2 răcirea pusă de alt izolat
const THINKING_MEMO_MS = 6 * 3600_000; // un model care a respins configurația de gândire o primește din nou abia după atât
const STATIC_RETRY_MS = 10 * 60_000; // după o descoperire picată nu mai batem la /models la fiecare cerere
const DISCOVERY_TIMEOUT_MS = 8000;

/** Pauza dintre un 503 și reîncercare; testele o înlocuiesc (fără așteptare reală). */
export const geminiTiming = { sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)) };

// Nume care nu produc text/JSON din poze sau sunet: le excludem din listele de generare.
const EXCLUDED = /tts|image|embedding|robotics|computer-use|live|transcribe|native-audio|imagen|veo|aqa/;
const version = (name) => { const m = /(\d+(?:\.\d+)?)/.exec(name); return m ? parseFloat(m[1]) : 0; };
/** Generația din nume („gemini-3.8-flash” → 3.8); null când numele nu începe cu o versiune (omni, aliasuri `-latest`). */
const generation = (name) => { const m = /^gemini-(\d+(?:\.\d+)?)(?:-|$)/.exec(name); return m ? parseFloat(m[1]) : null; };
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
const safeModel = (m) => String(m || "").replace(/[^A-Za-z0-9._-]/g, "").slice(0, 80);

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

// ── Gândirea pe familie (sonda din 28.09) ──
/**
 * thinkingConfig pentru un model, după familie: aliasurile `-latest` → null (acceptă implicitul); 1.x/2.0 → null (nu gândesc);
 * 2.5 flash / flash-lite → {thinkingBudget: 0}; 3.x flash-lite → {thinkingLevel: "minimal"}; 3.x flash (și omni, flash-preview) →
 * {thinkingLevel: "low"}; pro / preview → {thinkingLevel: "low"}; orice altceva → null.
 */
export function geminiThinking(model) {
  const n = String(model || "").toLowerCase();
  if (/-latest$/.test(n)) return null;
  const v = generation(n);
  if (v !== null && v < 2.5) return null;
  const lite = /flash-lite/.test(n);
  const flash = /flash/.test(n);
  if (flash && v !== null && v < 3) return { thinkingBudget: 0 };
  if (lite) return { thinkingLevel: "minimal" };
  if (flash) return { thinkingLevel: "low" };
  if (/pro|preview|exp/.test(n)) return { thinkingLevel: "low" };
  return null;
}
// Tokenii de gândire intră în maxOutputTokens: la „low” și la implicit (aliasuri, reîncercarea fără thinkingConfig) lăsăm loc în plus,
// altfel răspunsul poate veni tăiat (finishReason MAX_TOKENS) sau gol.
const thinkingHeadroom = (t) => (!t ? 4096 : t.thinkingBudget === 0 || t.thinkingLevel === "minimal" ? 0 : 2048);
/** Modele care au respins configurația noastră de gândire și au mers fără ea (memorie per izolat, 6 h). */
const thinkingRejected = new Map();

// ── Erorile Gemini: citim corpul (mic) doar ca să clasificăm; textul lui NU ajunge în mesaje, jurnal sau răspunsuri ──
/**
 * {status, reason, quota:[{limit, model, perDay}], retryMs, badKey}. reason: la 400 „thinking” | „schema” | „invalid-argument” |
 * „other” | „unknown” (fără mesaj Google); la 503 „high-demand” | „unavailable”; la 429 „quota”. Cota vine din mesaj
 * („… limit: 5, model: gemini-3.8-flash”) și din details (QuotaFailure.violations), iar retryMs din RetryInfo / „retry in 38s”.
 */
export function parseGeminiError(status, data) {
  const err = data && typeof data === "object" && data.error && typeof data.error === "object" ? data.error : null;
  const message = typeof err?.message === "string" ? err.message : "";
  const gstatus = typeof err?.status === "string" ? err.status : "";
  const out = { status, reason: "", quota: [], retryMs: 0, badKey: false };
  // Un 429 poate enumera mai multe metrici încălcate (cereri, tokeni de intrare) pentru același model: cererile pe minut au prioritate.
  const found = [];
  for (const line of message.split(/\n|\*/)) {
    for (const m of line.matchAll(/limit:\s*(\d+)\s*,\s*model:\s*([A-Za-z0-9._-]+)/g)) {
      const model = safeModel(m[2].replace(/\.+$/, ""));
      if (model) found.push({ limit: Number(m[1]), model, requests: /request/i.test(line) });
    }
  }
  found.sort((a, b) => Number(b.requests) - Number(a.requests));
  for (const q of found) if (!out.quota.some((x) => x.model === q.model)) out.quota.push({ limit: q.limit, model: q.model, perDay: false });
  for (const d of Array.isArray(err?.details) ? err.details : []) {
    if (Array.isArray(d?.violations)) {
      for (const v of d.violations) {
        const model = safeModel(v?.quotaDimensions?.model);
        const perDay = /PerDay/i.test(String(v?.quotaId || ""));
        const known = out.quota.find((q) => q.model === model);
        if (known) known.perDay = known.perDay || perDay;
        else if (model) out.quota.push({ limit: Number(v?.quotaValue) || null, model, perDay });
      }
    }
    if (typeof d?.retryDelay === "string" && parseFloat(d.retryDelay) > 0) out.retryMs = Math.round(parseFloat(d.retryDelay) * 1000);
    if (d?.reason === "API_KEY_INVALID") out.badKey = true;
  }
  const retry = /retry in\s*([\d.]+)\s*s/i.exec(message);
  if (retry && !out.retryMs) out.retryMs = Math.round(parseFloat(retry[1]) * 1000);
  if (/api key not valid|API_KEY_INVALID/i.test(message)) out.badKey = true;
  if (status === 400) {
    if (!message) out.reason = "unknown";
    else if (/thinking/i.test(message)) out.reason = "thinking";
    else if (/schema/i.test(message)) out.reason = "schema";
    else if (/invalid argument/i.test(message)) out.reason = "invalid-argument";
    else out.reason = "other";
  } else if (status === 503) out.reason = /high demand|overloaded/i.test(message) ? "high-demand" : "unavailable";
  else if (status === 429) out.reason = "quota";
  else if (gstatus) out.reason = gstatus.toLowerCase();
  return out;
}
/** Nota scurtă (română, fără conținutul Google) care ajunge în `attempts` / `detalii`. */
function errorNote(info, { retried503 = false, cooledMs = 0 } = {}) {
  if (info.status === 429) {
    if (!cooledMs) return "";
    const q = info.quota[0];
    return ` (cotă${q && q.limit ? " " + q.limit : ""}${q ? " pe " + q.model : ""}${q && q.perDay ? " pe zi" : ""}; în răcire ${Math.round(cooledMs / 1000)} s)`;
  }
  if (info.status === 503) return retried503 ? " (ocupat; și reîncercarea după 1,5 s)" : " (ocupat)";
  if (info.status === 400) {
    if (info.badKey) return " (cheie respinsă)";
    return { thinking: " (gândirea respinsă)", schema: " (schema respinsă)", "invalid-argument": " (argument invalid)", other: " (cerere respinsă)" }[info.reason] || "";
  }
  return "";
}

// ── Răcirea după 429: memorie per izolat + KV/R2 `ai-cooldown/<model>.json` (cu ttl, șters de purgeExpired) ──
const cooldowns = new Map(); // model → { until, limit, checkedAt }
const cooldownKey = (model) => `${GEMINI_COOLDOWN_PREFIX}${safeModel(model)}.json`;
/** Pune modelul în răcire `ms` (păstrează o răcire mai lungă deja pusă). Nu retrage modelul. */
export async function coolGeminiModel(env, model, { ms = GEMINI_COOLDOWN_MS, limit = null } = {}) {
  const m = safeModel(model);
  if (!m) return;
  const now = Date.now();
  const until = now + ms;
  const prev = cooldowns.get(m);
  if (prev && prev.until >= until) return;
  cooldowns.set(m, { until, limit, checkedAt: now });
  await writeCached(env, cooldownKey(m), { model: m, until, limit }, ms);
}
/** {until, limit} dacă modelul e în răcire acum (din memorie sau, cel mult o dată la 15 s, din stocare); altfel null. */
export async function geminiCooldown(env, model) {
  const m = safeModel(model);
  if (!m) return null;
  const now = Date.now();
  const mem = cooldowns.get(m);
  if (mem && mem.until > now) return { until: mem.until, limit: mem.limit };
  if (mem && now - mem.checkedAt < COOLDOWN_RECHECK_MS) return null;
  const stored = await readCached(env, cooldownKey(m));
  const until = Number(stored?.until) || 0;
  const limit = stored && Number.isFinite(Number(stored.limit)) && stored.limit !== null ? Number(stored.limit) : null;
  cooldowns.set(m, { until, limit, checkedAt: now });
  return until > now ? { until, limit } : null;
}
function cooldownFor(info) {
  if (info.quota.some((q) => q.perDay)) return GEMINI_COOLDOWN_DAY_MS;
  if (info.retryMs > GEMINI_COOLDOWN_MS) return Math.min(info.retryMs + 5000, GEMINI_COOLDOWN_DAY_MS);
  return GEMINI_COOLDOWN_MS;
}

// ── Preferința pe sarcină: o sortare stabilă peste lista descoperită (nimic fixat în cod în afară de familii) ──
export const QUALITY_TASKS = new Set(["meal", "sleep-summary", "sleep-talk-summary"]);
export const BULK_TASKS = new Set(["organize", "organize-clusters"]);
function family(name) {
  const lite = /flash-lite/.test(name);
  return { alias: /-latest$/.test(name) && !version(name), lite, flash: /flash/.test(name) && !lite, stable: !/preview|exp|omni/.test(name), v: generation(name) };
}
const isModernFlash = (f) => f.flash && f.stable && !f.alias && f.v !== null && f.v >= 3;
const isModernLite = (f) => f.lite && f.stable && !f.alias && f.v !== null && f.v >= 3;
/** Mese, rezumate: cel mai bun flash (3.8 → 3.7 → 3.6 → 3.5), gemini-flash-latest, apoi flash-lite, apoi restul în ordinea descoperită. */
function qualityTier(name) {
  const f = family(name);
  if (isModernFlash(f)) return 0;
  if (f.alias && f.flash) return 1;
  if (isModernLite(f)) return 2;
  if (f.alias && f.lite) return 3;
  return 4;
}
/** Curățenie în masă: flash-lite (3.5 → 3.1 → flash-lite-latest; rapid, cotă mai mare), apoi flash-urile, apoi restul. */
function bulkTier(name) {
  const f = family(name);
  if (isModernLite(f)) return 0;
  if (f.alias && f.lite) return 1;
  if (isModernFlash(f)) return 2;
  if (f.alias && f.flash) return 3;
  return 4;
}
/**
 * Ordinea de încercare pentru o sarcină, ca sortare stabilă a listei descoperite: `audio` → modelele de sunet (transcribe, omni,
 * native-audio) întâi, apoi ca la mese; QUALITY_TASKS → qualityTier; BULK_TASKS → bulkTier; altă sarcină → ordinea descoperită.
 */
export function preferForTask(list, { task = "", audio = false } = {}) {
  const items = Array.isArray(list) ? list.slice() : [];
  const tier = audio || QUALITY_TASKS.has(task) ? qualityTier : BULK_TASKS.has(task) ? bulkTier : null;
  if (!tier) return items;
  const lead = audio ? items.filter((m) => m === GEMINI_TRANSCRIBE_MODEL || GEMINI_AUDIO_PREFERRED.includes(m)) : [];
  const rest = items.filter((m) => !lead.includes(m)).map((m, i) => [m, tier(m), i]);
  rest.sort((a, b) => a[1] - b[1] || a[2] - b[2]);
  return [...lead, ...rest.map((x) => x[0])];
}

// ── Catalogul: memorie (per izolat) → KV/R2 (24 h) → GET /models → lista statică ──
let memory = null; // { catalog, readAt, failedAt? }
export function resetGeminiCache() { memory = null; cooldowns.clear(); thinkingRejected.clear(); }
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

/** POST generateContent; la !ok citește corpul erorii DOAR pentru clasificare (err.gemini), mesajul rămâne „gemini a răspuns cu N”. */
async function geminiPost(env, model, body, timeoutMs) {
  let resp;
  try {
    resp = await fetchWithTimeout(GEMINI_BASE + model + ":generateContent", { method: "POST", headers: { "content-type": "application/json", "x-goog-api-key": readKey(env, "GEMINI_API_KEY") }, body: JSON.stringify(body) }, timeoutMs);
  } catch (e) { e.provider = "gemini"; e.model = model; throw e; }
  if (!resp.ok) {
    const status = resp.status;
    let data = null;
    try { const raw = await resp.text(); data = raw && raw.length < 65536 ? JSON.parse(raw) : null; } catch (_) { data = null; }
    const info = parseGeminiError(status, data);
    const err = new AiError(`gemini a răspuns cu ${status}`, { provider: "gemini", model, status, kind: "http", fatal: status === 429 || status === 401 || status === 403 || (status === 400 && info.badKey) });
    err.gemini = info;
    throw err;
  }
  try { return await resp.json(); } catch (_) {
    throw new AiError("gemini: răspuns care nu e JSON", { provider: "gemini", model, kind: "json" });
  }
}

export const gemini = {
  name: "gemini",
  timeoutMs: 90000,
  audioTimeoutMs: 180000,
  supports: { images: true, documents: true, audio: true, verifyWithImages: true },
  available: (env) => readKey(env, "GEMINI_API_KEY").length > 0,
  /**
   * Ordinea de încercare, descoperită și sortată pe sarcină (preferForTask): mese/rezumate → 3.8 → 3.7 → 3.6 → 3.5 flash →
   * flash-latest → flash-lite; organize/organize-clusters → 3.5 → 3.1 flash-lite → flash-lite-latest → flash-uri; audio → omni →
   * native-audio → ca la mese; transcriere → gemini-3.5-transcribe primul. `GEMINI_MODEL` din env trece înaintea tuturor.
   */
  async models(env, { audio = false, task = "" } = {}) {
    const catalog = await geminiCatalog(env);
    let list = audio ? catalog.audio : catalog.text;
    if (audio && task === "transcribe" && catalog.transcribe) list = [catalog.transcribe, ...list];
    list = preferForTask(list, { task, audio });
    const override = readKey(env, "GEMINI_MODEL");
    const open = [...new Set([...(override ? [override] : []), ...list])].filter((m) => !isRetired(catalog, m));
    return open.length ? open : GEMINI_MODELS.filter((m) => !isRetired(catalog, m));
  },
  /** 404 la generateContent = model retras: nu mai apare 24 h. */
  retire: (env, model) => retireGeminiModel(env, model),
  /** Răcirea după 429 (routerul sare modelul fără să-l încerce): {until, limit} sau null. */
  cooling: (env, model) => geminiCooldown(env, model),
  // Cotele gratuite sunt PER MODEL (găleți separate): un 429 la un model nu înseamnă că următorul e epuizat. Limitele exacte nu se
  // mai presupun (modelele se schimbă des): limita zilnică e null, iar 429 pune modelul în răcire și trece la următorul.
  dailyLimit: null,
  perModelQuota: true,
  modelLimits: {},
  /** Pentru /v1/diag: ordinea reală, sursa ei, modelele retrase azi, cele în răcire, ordinea pe sarcini — fără chei. */
  async catalogInfo(env) {
    const c = await geminiCatalog(env);
    const ordine = await this.models(env);
    const racire = {};
    for (const m of ordine) {
      const cd = await geminiCooldown(env, m);
      if (cd) racire[m] = { secunde: Math.max(1, Math.ceil((cd.until - Date.now()) / 1000)), ...(cd.limit ? { limita: cd.limit } : {}) };
    }
    const peSarcina = {};
    for (const task of ["meal", "organize-clusters"]) peSarcina[task] = (await this.models(env, { task })).slice(0, 6);
    peSarcina.audio = (await this.models(env, { audio: true })).slice(0, 4);
    const faraGandire = [...thinkingRejected].filter(([, until]) => until > Date.now()).map(([m]) => m);
    return { sursa: c.source, descoperitLa: c.discoveredAt ? new Date(c.discoveredAt).toISOString() : null, ordine, peSarcina, audio: await this.models(env, { audio: true }), transcriere: c.transcribe, retrase: retiredNow(c), racire, ...(faraGandire.length ? { faraGandire } : {}), ...(c.error ? { eroare: c.error } : {}) };
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
    const baseTokens = Math.min(16000, Math.max(256, maxTokens || 4000));
    const thinking = Number(thinkingRejected.get(model)) > Date.now() ? null : geminiThinking(model);
    const generationConfig = {
      temperature: Number.isFinite(temperature) ? temperature : 0.1,
      maxOutputTokens: baseTokens + thinkingHeadroom(thinking),
    };
    if (thinking) generationConfig.thinkingConfig = thinking;
    if (schema) { generationConfig.responseMimeType = "application/json"; generationConfig.responseSchema = geminiSchema(schema); }
    const body = { ...(system ? { system_instruction: { parts: [{ text: system }] } } : {}), contents: [{ role: "user", parts }], generationConfig };
    const deadline = Date.now() + (timeoutMs || (audio ? this.audioTimeoutMs : this.timeoutMs));
    const count = () => { if (trace) trace.modelCalls = (trace.modelCalls || 0) + 1; };
    const post = () => { count(); return geminiPost(env, model, body, Math.max(1000, deadline - Date.now())); };
    // Politica pe cod (sonda din 28.09): 503 → o reîncercare după 1,5 s pe același model; 400 care pomenește gândirea / „invalid
    // argument” (sau fără mesaj Google) → o dată fără thinkingConfig, apoi (schemă / argument invalid / fără mesaj) o dată fără
    // responseSchema — JSON-ul rămâne cerut prin responseMimeType și validat de router; 429 → răcire pe modelul numit în mesaj.
    let retried503 = false, droppedThinking = false, droppedSchema = false;
    let data;
    for (;;) {
      try { data = await post(); break; }
      catch (e) {
        const info = e.gemini || null;
        if (!info) throw e;
        const left = deadline - Date.now();
        if (e.status === 503 && !retried503 && left > GEMINI_503_RETRY_MS + 2000) {
          retried503 = true;
          await geminiTiming.sleep(GEMINI_503_RETRY_MS);
          continue;
        }
        if (e.status === 400 && !info.badKey && left > 3000) {
          if (generationConfig.thinkingConfig && !droppedThinking && ["thinking", "invalid-argument", "unknown"].includes(info.reason)) {
            delete generationConfig.thinkingConfig;
            generationConfig.maxOutputTokens = baseTokens + thinkingHeadroom(null);
            droppedThinking = true;
            continue;
          }
          if (generationConfig.responseSchema && !droppedSchema && ["schema", "invalid-argument", "unknown"].includes(info.reason)) {
            delete generationConfig.responseSchema;
            droppedSchema = true;
            continue;
          }
        }
        let cooledMs = 0;
        if (e.status === 429 && info.quota.length) {
          cooledMs = cooldownFor(info);
          const names = [...new Set([...info.quota.map((q) => q.model), safeModel(model)])];
          for (const m of names) {
            const q = info.quota.find((x) => x.model === m) || info.quota[0];
            try { await coolGeminiModel(env, m, { ms: cooledMs, limit: q.limit }); } catch (_) { }
          }
        }
        e.message = `gemini a răspuns cu ${e.status}${errorNote(info, { retried503, cooledMs })}`;
        throw e;
      }
    }
    if (droppedThinking && thinking) thinkingRejected.set(model, Date.now() + THINKING_MEMO_MS);
    if (data?.promptFeedback?.blockReason) throw new AiError("gemini a blocat cererea", { provider: "gemini", model, kind: "blocked" });
    const candidate = data?.candidates?.[0];
    const text = (candidate?.content?.parts ?? []).filter((p) => typeof p?.text === "string" && !p.thought).map((p) => p.text).join("");
    if (!text.trim()) throw new AiError("gemini: răspuns gol (" + String(candidate?.finishReason || "?").toLowerCase() + ")", { provider: "gemini", model, kind: "empty" });
    return text;
  },
};
