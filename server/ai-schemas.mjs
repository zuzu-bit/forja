// Schemele răspunsurilor AI + validator pur (fără librării). Rulează în Workers și în node:test.
// Subsetul acceptat: type (string sau listă), nullable, enum, const, properties, required, items,
// minItems/maxItems, minimum/maximum, minLength/maxLength, anyOf. additionalProperties NU se impune:
// modelele adaugă câmpuri în plus și nu vrem să pice o analiză bună pentru un câmp în plus.

const isObject = (v) => v !== null && typeof v === "object" && !Array.isArray(v);

function typeMatches(type, value) {
  switch (type) {
    case "object": return isObject(value);
    case "array": return Array.isArray(value);
    case "string": return typeof value === "string";
    case "number": return typeof value === "number" && Number.isFinite(value);
    case "integer": return typeof value === "number" && Number.isFinite(value);
    case "boolean": return typeof value === "boolean";
    case "null": return value === null;
    default: return true;
  }
}

/** Validează `value` după `schema`; întoarce {ok, errors:["cale: motiv", …]} (cel mult 12 erori, ca să încapă în promptul de reparare). */
export function validate(schema, value, path = "$", errors = []) {
  if (!isObject(schema)) return { ok: true, errors };
  const push = (msg) => { if (errors.length < 12) errors.push(`${path}: ${msg}`); };
  if (value === null && schema.nullable) return { ok: errors.length === 0, errors };
  if (schema.const !== undefined && value !== schema.const) push(`trebuie să fie ${JSON.stringify(schema.const)}`);
  if (schema.type) {
    const types = Array.isArray(schema.type) ? schema.type : [schema.type];
    if (!types.some((t) => typeMatches(t, value))) { push(`trebuie să fie ${types.join("|")}`); return { ok: false, errors }; }
  }
  if (Array.isArray(schema.enum) && !schema.enum.includes(value)) push(`valoare permisă: ${schema.enum.map((e) => JSON.stringify(e)).join(", ")}`);
  if (typeof value === "number") {
    if (typeof schema.minimum === "number" && value < schema.minimum) push(`minim ${schema.minimum}`);
    if (typeof schema.maximum === "number" && value > schema.maximum) push(`maxim ${schema.maximum}`);
  }
  if (typeof value === "string") {
    if (typeof schema.minLength === "number" && value.length < schema.minLength) push(`cel puțin ${schema.minLength} caractere`);
    if (typeof schema.maxLength === "number" && value.length > schema.maxLength) push(`cel mult ${schema.maxLength} caractere`);
  }
  if (Array.isArray(value)) {
    if (typeof schema.minItems === "number" && value.length < schema.minItems) push(`cel puțin ${schema.minItems} elemente`);
    if (typeof schema.maxItems === "number" && value.length > schema.maxItems) push(`cel mult ${schema.maxItems} elemente`);
    if (schema.items) value.forEach((item, i) => validate(schema.items, item, `${path}[${i}]`, errors));
  }
  if (isObject(value)) {
    for (const key of schema.required || []) if (!(key in value)) push(`lipsește „${key}”`);
    if (isObject(schema.properties)) {
      for (const [key, sub] of Object.entries(schema.properties)) if (key in value) validate(sub, value[key], `${path}.${key}`, errors);
    }
  }
  if (Array.isArray(schema.anyOf) && !schema.anyOf.some((sub) => validate(sub, value, path, []).ok)) push("nu se potrivește cu niciuna dintre variante");
  return { ok: errors.length === 0, errors };
}

/**
 * Extragere strictă de JSON din textul unui model: fără ```json, fără text în jur; primul obiect/listă complet(ă).
 * Întoarce null dacă nu există JSON valid — nimic nu se „ghicește”.
 */
export function extractJsonStrict(text) {
  if (typeof text !== "string" || !text.trim()) return null;
  let cleaned = text.trim();
  const fence = cleaned.match(/^```(?:json)?\s*([\s\S]*?)\s*```$/i);
  if (fence) cleaned = fence[1].trim();
  try { return JSON.parse(cleaned); } catch (_) { }
  const start = Math.min(...["{", "["].map((c) => cleaned.indexOf(c)).filter((i) => i >= 0));
  if (!Number.isFinite(start)) return null;
  const open = cleaned[start], close = open === "{" ? "}" : "]";
  let depth = 0, inStr = false, esc = false;
  for (let i = start; i < cleaned.length; i++) {
    const ch = cleaned[i];
    if (inStr) { if (esc) esc = false; else if (ch === "\\") esc = true; else if (ch === '"') inStr = false; continue; }
    if (ch === '"') inStr = true;
    else if (ch === open) depth++;
    else if (ch === close && --depth === 0) {
      try { return JSON.parse(cleaned.slice(start, i + 1)); } catch (_) { return null; }
    }
  }
  return null;
}

const INCREDERE = ["ridicată", "medie", "scăzută"];
const num = (extra = {}) => ({ type: "number", minimum: 0, ...extra });
const str = (maxLength) => ({ type: "string", maxLength });

// ── Mese v2 ──
export const MEAL_COMPONENT_SCHEMA = {
  type: "object",
  required: ["nume", "grame", "kcal", "proteine", "carbo", "grasimi"],
  properties: {
    nume: str(80), grame: num({ maximum: 5000 }), kcal: num({ maximum: 10000 }),
    proteine: num({ maximum: 1000 }), carbo: num({ maximum: 1000 }), grasimi: num({ maximum: 1000 }), fibre: num({ maximum: 300 }),
    incredere: { type: "string", enum: INCREDERE },
  },
};
export const MEAL_SCHEMA = {
  type: "object",
  required: ["fel", "incredere", "componente"],
  properties: {
    fel: str(120),
    incredere: { type: "string", enum: INCREDERE },
    componente: { type: "array", maxItems: 30, items: MEAL_COMPONENT_SCHEMA },
    total: { type: "object", properties: { kcal: num(), proteine: num(), carbo: num(), grasimi: num(), fibre: num() } },
    scor: { type: "object", properties: { valoare: num({ minimum: 1, maximum: 10 }), motiv: str(200) } },
    sfat: str(300),
    observatii: { type: "array", maxItems: 8, items: str(160) },
    portie: str(120),
  },
};

// ── Curățenie v2 ──
export const ORGANIZE_SCHEMA = {
  type: "object",
  required: ["items"],
  properties: {
    items: {
      type: "array", maxItems: 40,
      items: {
        type: "object",
        required: ["id", "suggestion"],
        properties: {
          id: str(80),
          suggestion: { type: "string", enum: ["keep", "delete", "move"] },
          folder: { type: "string", nullable: true, maxLength: 120 },
          reason: str(200),
          confidence: { type: "string", enum: INCREDERE },
          rezumat: str(160),
          categorie: str(40),
          dosar: str(60),
          sterge: { type: "object", properties: { recomandat: { type: "boolean" }, motiv: str(160), incredere: { type: "string", enum: INCREDERE } } },
          duplicatDe: { type: "string", nullable: true, maxLength: 80 },
        },
      },
    },
    summary: str(400),
  },
};

// ── Somn: un clip de 5 s ──
export const SLEEP_AUDIO_SCHEMA = {
  type: "object",
  required: ["type", "speech", "confidence"],
  properties: {
    type: { type: "string", enum: ["talk", "snore", "cough", "noise", "silence"] },
    transcript: str(300),
    words: num({ maximum: 200 }),
    speech: { type: "boolean" },
    confidence: num({ maximum: 1 }),
    intensity: num({ maximum: 1 }),
  },
};

// ── Somn: evenimentele unui chunk de până la 35 min ──
export const SLEEP_EVENTS_SCHEMA = {
  type: "object",
  required: ["events"],
  properties: {
    events: {
      type: "array", maxItems: 400,
      items: {
        type: "object",
        required: ["type", "startMs", "endMs"],
        properties: {
          type: { type: "string", enum: ["talk", "snore", "cough", "noise"] },
          startMs: num({ maximum: 4 * 3600_000 }),
          endMs: num({ maximum: 4 * 3600_000 }),
          transcript: str(400),
          language: str(16),
          intensity: num({ maximum: 1 }),
          confidence: num({ maximum: 1 }),
        },
      },
    },
  },
};

// ── Rezumate scurte (dimineață / vorbit în somn) ──
export const SUMMARY_SCHEMA = { type: "object", required: ["summary"], properties: { summary: str(600) } };

// ── Transcriere simplă (Gemini ca înlocuitor de Whisper) ──
export const TRANSCRIPT_SCHEMA = {
  type: "object", required: ["transcript"],
  properties: {
    transcript: str(4000), language: str(16),
    segments: { type: "array", maxItems: 400, items: { type: "object", required: ["startMs", "endMs", "text"], properties: { startMs: num(), endMs: num(), text: str(400) } } },
  },
};

const round = (v, digits = 0) => { const n = Number(v); if (!Number.isFinite(n) || n < 0) return 0; const f = 10 ** digits; return Math.round(n * f) / f; };
const label = (v, fallback = "medie") => (INCREDERE.includes(v) ? v : fallback);

/** Totalul din componente (kcal, P, C, G, fibre), rotunjit. */
export function mealTotals(componente) {
  const t = { kcal: 0, proteine: 0, carbo: 0, grasimi: 0, fibre: 0 };
  for (const c of componente || []) for (const k of Object.keys(t)) t[k] += round(c[k], 1);
  for (const k of Object.keys(t)) t[k] = round(t[k]);
  return t;
}

/** kcal ≈ 4P + 4C + 9G (±15 %): spune dacă cifrele se contrazic. */
export function mealTotalsConsistent(total, tolerance = 0.15) {
  const expected = 4 * total.proteine + 4 * total.carbo + 9 * total.grasimi;
  if (expected <= 0 && total.kcal <= 0) return true;
  if (expected <= 0) return false;
  return Math.abs(total.kcal - expected) / expected <= tolerance;
}

/**
 * Normalizează răspunsul unui model la contractul v2: numere rotunjite, etichete valide, totaluri recalculate,
 * versiune 2. Câmpurile vechi (fel, incredere, componente[nume, grame, kcal, proteine, carbo, grasimi]) rămân identice.
 */
export function normalizeMeal(parsed, model = "") {
  const componente = (Array.isArray(parsed?.componente) ? parsed.componente : []).slice(0, 30).map((c) => ({
    nume: String(c?.nume ?? "").slice(0, 80).trim() || "component",
    grame: round(c?.grame), kcal: round(c?.kcal), proteine: round(c?.proteine), carbo: round(c?.carbo), grasimi: round(c?.grasimi),
    fibre: round(c?.fibre), incredere: label(c?.incredere, label(parsed?.incredere)),
  }));
  const total = mealTotals(componente);
  const observatii = (Array.isArray(parsed?.observatii) ? parsed.observatii : []).filter((o) => typeof o === "string" && o.trim()).slice(0, 8).map((o) => o.trim().slice(0, 160));
  let incredere = label(parsed?.incredere, "scăzută");
  if (componente.length && !mealTotalsConsistent(total) && incredere === "ridicată") incredere = "medie";
  const scorValoare = Math.min(10, Math.max(1, round(parsed?.scor?.valoare) || (componente.length ? 5 : 1)));
  return {
    fel: String(parsed?.fel ?? "").slice(0, 120).trim(),
    incredere,
    componente,
    total,
    scor: { valoare: scorValoare, motiv: String(parsed?.scor?.motiv ?? "").slice(0, 200).trim() },
    sfat: String(parsed?.sfat ?? "").slice(0, 300).trim(),
    observatii,
    portie: String(parsed?.portie ?? "").slice(0, 120).trim(),
    model: String(model || "").slice(0, 60),
    versiune: 2,
  };
}

export const SCHEMAS = { MEAL_SCHEMA, ORGANIZE_SCHEMA, SLEEP_AUDIO_SCHEMA, SLEEP_EVENTS_SCHEMA, SUMMARY_SCHEMA, TRANSCRIPT_SCHEMA };
