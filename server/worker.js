// Serverul central FORJA — Cloudflare Worker (nivel gratuit, fără card).
// Utilizatorii NU au chei: aplicația trimite pozele/sunetele aici cu tokenul
// lor de cont FORJA (Firebase), iar serverul analizează cu AI-ul companiei.

import { createRemoteJWKSet, jwtVerify } from "jose";

const FIREBASE_PROJECT = "forja-65093";
const JWKS = createRemoteJWKSet(
  new URL("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com")
);

async function requireUser(request) {
  const auth = request.headers.get("Authorization") || "";
  if (!auth.startsWith("Bearer ")) return null;
  try {
    const { payload } = await jwtVerify(auth.slice(7), JWKS, {
      issuer: `https://securetoken.google.com/${FIREBASE_PROJECT}`,
      audience: FIREBASE_PROJECT,
    });
    return payload.sub || null;
  } catch (_) {
    return null;
  }
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

const MEAL_PROMPT =
  'Analizează fotografia unei mese. Răspunde DOAR cu JSON valid, fără alt text, cu structura exactă: ' +
  '{"fel":"numele scurt al felului în română","incredere":"ridicată|medie|scăzută",' +
  '"componente":[{"nume":"...","grame":0,"kcal":0,"proteine":0,"carbo":0,"grasimi":0}]}. ' +
  "Descompune farfuria pe componente vizibile (nu un singur fel!), estimează gramaje realiste pentru porția din imagine " +
  "și calculează kcal și macronutrienții per componentă la gramajul estimat. " +
  "Ține cont de blind spots: grăsimi de gătit invizibile → încredere scăzută. " +
  'Dacă imaginea nu conține mâncare, întoarce {"fel":"","incredere":"scăzută","componente":[]}.';

function extractMealJson(text) {
  if (!text) return null;
  const cleaned = text.trim().replace(/^```json/, "").replace(/^```/, "").replace(/```$/, "").trim();
  try {
    return JSON.parse(cleaned);
  } catch (_) {
    const m = cleaned.match(/\{[\s\S]*\}/);
    if (m) {
      try { return JSON.parse(m[0]); } catch (_) { }
    }
  }
  return null;
}

// ── Analiza meselor fără NICIO cheie — pipeline în doi pași, ca profesioniștii:
//    1) modelul de VEDERE identifică alimentele și gramajele
//    2) modelul mare de TEXT (nutriționistul) completează kcal + P/C/G în română ──
async function runText(env, prompt, maxTokens = 900) {
  const models = ["@cf/meta/llama-3.3-70b-instruct-fp8-fast", "@cf/meta/llama-3.1-8b-instruct"];
  for (const model of models) {
    try {
      const r = await env.AI.run(model, {
        messages: [{ role: "user", content: prompt }],
        max_tokens: maxTokens,
        temperature: 0.2,
      });
      const out = (r && (r.response || r.text)) || "";
      if (out.trim().length > 0) return out;
    } catch (_) { }
  }
  return "";
}

function macrosMissing(parsed) {
  if (!parsed || !parsed.componente || parsed.componente.length === 0) return true;
  const p = parsed.componente.reduce((s, c) => s + (c.proteine || 0), 0);
  const cb = parsed.componente.reduce((s, c) => s + (c.carbo || 0), 0);
  const g = parsed.componente.reduce((s, c) => s + (c.grasimi || 0), 0);
  const k = parsed.componente.reduce((s, c) => s + (c.kcal || 0), 0);
  return k > 0 && p + cb + g === 0;
}

async function completeNutrition(env, draftText) {
  const prompt =
    "Ești nutriționist. Pornind de la lista de alimente văzute într-o farfurie:\n" +
    draftText +
    '\n\nConstruiește DOAR acest JSON, fără alt text: {"fel":"numele felului în ROMÂNĂ",' +
    '"incredere":"ridicată|medie|scăzută","componente":[{"nume":"numele în ROMÂNĂ","grame":0,' +
    '"kcal":0,"proteine":0,"carbo":0,"grasimi":0}]}. ' +
    "Folosește valori nutriționale REALISTE pentru gramajul fiecărei componente (kcal, proteine, carbohidrați — " +
    "inclusiv zaharurile intră la carbo — și grăsimi, toate în grame, numere întregi). " +
    "NICIODATĂ toate macronutrientele zero dacă alimentul are calorii.";
  const out = await runText(env, prompt);
  return extractMealJson(out);
}

async function mealViaModelBank(env, imageB64) {
  const bytes = Uint8Array.from(atob(imageB64), (c) => c.charCodeAt(0));
  const visionModels = ["@cf/meta/llama-3.2-11b-vision-instruct", "@cf/llava-hf/llava-1.5-7b-hf"];

  // Pasul 1: ce se vede în farfurie (sarcină simplă — la asta modelele de vedere sunt bune).
  let draft = "";
  for (const model of visionModels) {
    try {
      const r = await env.AI.run(model, {
        image: [...bytes],
        prompt:
          "List every food item visible in this photo with an estimated portion weight in grams. " +
          "Format: one item per line, 'name - grams'. Nothing else. If there is no food, answer exactly: NO_FOOD",
        max_tokens: 400,
      });
      const out = ((r && (r.response || r.description || r.text)) || "").trim();
      if (out && !out.toUpperCase().includes("NO_FOOD")) { draft = out; break; }
    } catch (_) { }
  }
  if (!draft) {
    return json({ error: "N-am recunoscut mâncare în poză. Încearcă un unghi de sus, cu lumină." }, 422);
  }

  // Pasul 2: nutriționistul (model mare de text) pune cifrele — în română.
  let parsed = await completeNutrition(env, draft);
  if (macrosMissing(parsed)) {
    parsed = await completeNutrition(
      env,
      draft + "\n\nATENȚIE: răspunsul anterior avea proteine/carbo/grăsimi zero — completează valori realiste, nenule."
    );
  }
  if (parsed && parsed.componente && parsed.componente.length > 0) {
    parsed.incredere = parsed.incredere || "medie";
    return json(parsed);
  }
  return json({ error: "Analiza n-a reușit de data asta. Mai încearcă o poză." }, 422);
}

// ── Analiza meselor: poza → serverul FORJA (Gemini dacă există cheia companiei,
//    altfel banca de modele open-source Cloudflare — zero chei) ────────────────
async function handleMeal(request, env) {
  let body;
  try {
    body = await request.json();
  } catch (_) {
    return json({ error: "Cerere invalidă." }, 400);
  }
  const image = body && body.image;
  if (!image || typeof image !== "string" || image.length < 100) {
    return json({ error: "Lipsește poza." }, 400);
  }

  if (!env.GEMINI_API_KEY) {
    return mealViaModelBank(env, image);
  }

  const prompt = MEAL_PROMPT;
  const models = ["gemini-2.0-flash", "gemini-1.5-flash"];
  let lastErr = "AI indisponibil.";
  for (const model of models) {
    try {
      const resp = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${env.GEMINI_API_KEY}`,
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({
            contents: [
              {
                parts: [
                  { text: prompt },
                  { inline_data: { mime_type: "image/jpeg", data: image } },
                ],
              },
            ],
            generationConfig: { temperature: 0.2, response_mime_type: "application/json" },
          }),
        }
      );
      if (!resp.ok) {
        lastErr =
          resp.status === 429
            ? "Limita zilnică a serverului AI e atinsă. Încearcă mai târziu."
            : `AI a răspuns cu ${resp.status}.`;
        continue;
      }
      const data = await resp.json();
      const text =
        data &&
        data.candidates &&
        data.candidates[0] &&
        data.candidates[0].content &&
        data.candidates[0].content.parts &&
        data.candidates[0].content.parts[0] &&
        data.candidates[0].content.parts[0].text;
      if (!text) {
        lastErr = "Răspuns AI gol.";
        continue;
      }
      const parsed = extractMealJson(text);
      if (!parsed || !parsed.componente || parsed.componente.length === 0) {
        return json({ error: "N-am recunoscut mâncare în poză. Încearcă un unghi de sus, cu lumină." }, 422);
      }
      return json(parsed);
    } catch (_) {
      lastErr = "Eroare la analiza AI.";
    }
  }
  // Gemini a picat — încercăm banca de modele, să nu rămână utilizatorul fără analiză.
  return mealViaModelBank(env, image);
}

// ── Sunetele de somn: clip 5s → Whisper Large (bun pe română) → vorbit vs sforăit,
//    CU transcriere — utilizatorul vede exact ce s-a auzit. ─────────────────────
const WHISPER_HALLUCINATIONS = new Set([
  "you", "thank you.", "thank you", "thanks for watching!", "thanks for watching",
  "mulțumesc.", "mulțumesc", "subtitrare", "bye.", ".",
]);

async function handleSleepAudio(request, env) {
  const buf = await request.arrayBuffer();
  if (!buf || buf.byteLength < 4000) return json({ error: "Clip prea scurt." }, 400);
  if (buf.byteLength > 2_000_000) return json({ error: "Clip prea mare." }, 413);

  const u8 = new Uint8Array(buf);
  let text = "";
  // 1) Whisper Large v3 Turbo — multilingv serios (input: base64).
  try {
    let b64 = "";
    const chunk = 0x8000;
    for (let i = 0; i < u8.length; i += chunk) {
      b64 += String.fromCharCode.apply(null, u8.subarray(i, i + chunk));
    }
    const r = await env.AI.run("@cf/openai/whisper-large-v3-turbo", {
      audio: btoa(b64),
      language: "ro",
    });
    text = ((r && r.text) || "").trim();
  } catch (_) { }
  // 2) Fallback: Whisper clasic (input: listă de octeți).
  if (!text) {
    try {
      const r = await env.AI.run("@cf/openai/whisper", { audio: [...u8] });
      text = ((r && r.text) || "").trim();
    } catch (_) { }
  }

  const lower = text.toLowerCase().trim();
  const hallucinated = WHISPER_HALLUCINATIONS.has(lower);
  const words = text.split(/\s+/).filter((w) => w.replace(/[^\p{L}\p{N}]/gu, "").length >= 2);
  const isTalk = !hallucinated && (words.length >= 2 || (words.length >= 1 && lower.length >= 8));
  return json({
    type: isTalk ? "talk" : "snore",
    words: words.length,
    transcript: isTalk ? text.slice(0, 300) : "",
    confidence: isTalk ? (words.length >= 4 ? "ridicată" : "medie") : "medie",
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      return json({
        ok: true,
        service: "forja-api",
        meals: env.GEMINI_API_KEY ? "gemini" : "banca-de-modele-cloudflare",
        audio: env.AI ? "whisper" : "indisponibil",
      });
    }
    if (request.method !== "POST") return json({ error: "Metodă greșită." }, 405);

    const uid = await requireUser(request);
    if (!uid) return json({ error: "Cont FORJA necesar." }, 401);

    if (url.pathname === "/v1/meal") return handleMeal(request, env);
    if (url.pathname === "/v1/sleep-audio") return handleSleepAudio(request, env);
    return json({ error: "Rută necunoscută." }, 404);
  },
};
