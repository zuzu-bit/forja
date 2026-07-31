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

// ── Analiza meselor: poza → Gemini cu CHEIA COMPANIEI ─────────────────────────
async function handleMeal(request, env) {
  if (!env.GEMINI_API_KEY) {
    return json({ error: "Serverul nu are încă cheia AI configurată." }, 503);
  }
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

  const prompt =
    'Analizează fotografia unei mese. Răspunde DOAR cu JSON valid, fără alt text, cu structura exactă: ' +
    '{"fel":"numele scurt al felului în română","incredere":"ridicată|medie|scăzută",' +
    '"componente":[{"nume":"...","grame":0,"kcal":0,"proteine":0,"carbo":0,"grasimi":0}]}. ' +
    "Descompune farfuria pe componente vizibile (nu un singur fel!), estimează gramaje realiste pentru porția din imagine " +
    "și calculează kcal și macronutrienții per componentă la gramajul estimat. " +
    "Ține cont de blind spots: grăsimi de gătit invizibile → încredere scăzută. " +
    'Dacă imaginea nu conține mâncare, întoarce {"fel":"","incredere":"scăzută","componente":[]}.';

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
      const cleaned = text.trim().replace(/^```json/, "").replace(/^```/, "").replace(/```$/, "").trim();
      const parsed = JSON.parse(cleaned);
      if (!parsed.componente || parsed.componente.length === 0) {
        return json({ error: "N-am recunoscut mâncare în poză. Încearcă un unghi de sus, cu lumină." }, 422);
      }
      return json(parsed);
    } catch (_) {
      lastErr = "Eroare la analiza AI.";
    }
  }
  return json({ error: lastErr }, 502);
}

// ── Sunetele de somn: clip 5s → Whisper → vorbit real vs sforăit ─────────────
async function handleSleepAudio(request, env) {
  const buf = await request.arrayBuffer();
  if (!buf || buf.byteLength < 4000) return json({ error: "Clip prea scurt." }, 400);
  if (buf.byteLength > 2_000_000) return json({ error: "Clip prea mare." }, 413);
  try {
    const audio = [...new Uint8Array(buf)];
    const result = await env.AI.run("@cf/openai/whisper", { audio });
    const text = ((result && result.text) || "").trim();
    // Whisper halucinează uneori pe zgomot — cerem vorbire clară.
    const words = text.split(/\s+/).filter((w) => w.replace(/[^\p{L}\p{N}]/gu, "").length >= 2);
    const isTalk = words.length >= 3 || (words.length >= 2 && text.length >= 10);
    return json({
      type: isTalk ? "talk" : "snore",
      words: words.length,
      confidence: isTalk ? (words.length >= 4 ? "ridicată" : "medie") : "medie",
    });
  } catch (_) {
    return json({ error: "Analiza audio a eșuat." }, 502);
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/") {
      return json({ ok: true, service: "forja-api", ai: !!env.AI, gemini: !!env.GEMINI_API_KEY });
    }
    if (request.method !== "POST") return json({ error: "Metodă greșită." }, 405);

    const uid = await requireUser(request);
    if (!uid) return json({ error: "Cont FORJA necesar." }, 401);

    if (url.pathname === "/v1/meal") return handleMeal(request, env);
    if (url.pathname === "/v1/sleep-audio") return handleSleepAudio(request, env);
    return json({ error: "Rută necunoscută." }, 404);
  },
};
