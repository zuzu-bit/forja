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
// Modelele Meta cer o acceptare de licență unică per cont: promptul „agree”.
async function runWithAgree(env, model, input) {
  try {
    return await env.AI.run(model, input);
  } catch (e) {
    const msg = String(e && e.message ? e.message : e);
    if (msg.includes("5016") || msg.toLowerCase().includes("agree")) {
      try { await env.AI.run(model, { prompt: "agree" }); } catch (_) { }
      return await env.AI.run(model, input);
    }
    throw e;
  }
}

async function runText(env, prompt, maxTokens = 900) {
  const models = [
    "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    "@cf/meta/llama-3.1-70b-instruct",
  ];
  for (const model of models) {
    try {
      const r = await runWithAgree(env, model, {
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
  let visionErr = "";
  for (const model of visionModels) {
    try {
      const r = await runWithAgree(env, model, {
        image: [...bytes],
        prompt:
          "Describe every food item visible in this photo and estimate the portion weight in grams for each. " +
          "One item per line, format: name - grams.",
        max_tokens: 400,
      });
      const out = ((r && (r.response || r.description || r.text)) || "").trim();
      // „Fără mâncare” doar dacă e răspunsul întreg, nu un ecou al instrucțiunii.
      const upper = out.toUpperCase();
      if (out.length > 3 && !upper.startsWith("NO_FOOD") && upper !== "NO FOOD") {
        draft = out;
        break;
      }
    } catch (e) {
      visionErr = `${model}: ${String(e && e.message ? e.message : e).slice(0, 160)}`;
    }
  }
  if (!draft) {
    return json({ error: `[vedere] Modelele n-au putut citi poza.${visionErr ? " Detaliu: " + visionErr : ""} Mai încearcă o poză cu lumină.` }, 422);
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
  return json({ error: "[nutriționist] Modelul de text n-a produs valorile. Mai încearcă o dată." }, 422);
}

// ── Autodiagnoză: care modele răspund pe acest cont (citită de CI la deploy) ──
const TINY_JPEG_B64 =
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a" +
  "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA" +
  "AAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AVN//2Q==";

async function handleDiag(env) {
  const results = {};
  const tiny = Uint8Array.from(atob(TINY_JPEG_B64), (c) => c.charCodeAt(0));
  const visionModels = ["@cf/meta/llama-3.2-11b-vision-instruct", "@cf/llava-hf/llava-1.5-7b-hf"];
  for (const m of visionModels) {
    try {
      const r = await runWithAgree(env, m, { image: [...tiny], prompt: "one word: color?", max_tokens: 10 });
      results[m] = "OK: " + String((r && (r.response || r.description || r.text)) || "?").slice(0, 40);
    } catch (e) {
      results[m] = "ERR: " + String(e && e.message ? e.message : e).slice(0, 120);
    }
  }
  const textModels = [
    "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
    "@cf/meta/llama-3.1-70b-instruct",
  ];
  for (const m of textModels) {
    try {
      const r = await runWithAgree(env, m, { messages: [{ role: "user", content: "Say OK" }], max_tokens: 5 });
      results[m] = "OK: " + String((r && (r.response || r.text)) || "?").slice(0, 40);
    } catch (e) {
      results[m] = "ERR: " + String(e && e.message ? e.message : e).slice(0, 120);
    }
  }
  results["r2"] = env.RECORDS ? "OK: binding prezent" : "ERR: lipsă binding";
  results["gemini"] = env.GEMINI_API_KEY ? "configurat" : "absent (banca de modele)";
  return json(results);
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
// Fraze cu care Whisper „halucinează" pe zgomot/sforăit/tăcere — nu sunt vorbire.
const WHISPER_HALLUCINATIONS = new Set([
  "you", "thank you.", "thank you", "thanks for watching!", "thanks for watching",
  "thanks for watching.", "please subscribe", "subscribe", "bye.", "bye", ".", "...",
  "mulțumesc.", "mulțumesc", "mulțumesc pentru vizionare", "mulțumesc pentru vizionare.",
  "abonați-vă", "abonează-te", "subtitrare", "subtitrări", "subtitrarea", "subtitrare refuz",
  "amara", "amara.", "da.", "da", "nu.", "nu", "așa", "aha", "mhm", "hmm", "îhî", "ok", "oke",
  "www.", "the", "so", "și", "eu", "a", "e",
]);

// Curăță ieșirea Whisper: întoarce vorbire REALĂ sau nimic (fără invenții pe sforăit/zgomot).
function cleanTranscript(raw) {
  const text = (raw || "").trim();
  if (!text) return { speech: false, transcript: "", words: 0 };
  const lower = text.toLowerCase().replace(/\s+/g, " ").trim();
  if (WHISPER_HALLUCINATIONS.has(lower)) return { speech: false, transcript: "", words: 0 };
  const toks = lower.split(/\s+/).map((w) => w.replace(/[^\p{L}\p{N}]/gu, "")).filter((w) => w.length >= 2);
  if (toks.length === 0) return { speech: false, transcript: "", words: 0 };
  // Repetiție: prea puține cuvinte unice → Whisper a intrat în buclă (halucinație).
  const uniq = new Set(toks).size;
  if (toks.length >= 4 && uniq / toks.length < 0.4) return { speech: false, transcript: "", words: 0 };
  // O bigramă repetată de multe ori → tot buclă.
  if (toks.length >= 6) {
    const big = {};
    let maxBig = 0;
    for (let i = 0; i + 1 < toks.length; i++) {
      const k = toks[i] + " " + toks[i + 1];
      big[k] = (big[k] || 0) + 1;
      if (big[k] > maxBig) maxBig = big[k];
    }
    if (maxBig >= 3) return { speech: false, transcript: "", words: 0 };
  }
  // Vorbire reală: ≥2 cuvinte, sau un singur cuvânt clar (≥4 litere).
  const ok = toks.length >= 2 || (toks.length === 1 && toks[0].length >= 4);
  if (!ok) return { speech: false, transcript: "", words: toks.length };
  return { speech: true, transcript: text.slice(0, 300), words: toks.length };
}

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

  const clean = cleanTranscript(text);
  return json({
    type: clean.speech ? "talk" : "sound",
    speech: clean.speech,
    words: clean.words,
    transcript: clean.transcript,
    confidence: clean.words >= 4 ? "ridicată" : (clean.speech ? "medie" : "scăzută"),
  });
}

// ── Rezumatul cald al vorbelor din somn (din frazele reale, nu inventat) ──
async function handleSleepTalkSummary(request, env) {
  let s;
  try { s = await request.json(); } catch (_) { return json({ error: "Cerere invalidă." }, 400); }
  const phrases = Array.isArray(s.phrases)
    ? s.phrases.filter((p) => typeof p === "string" && p.trim()).slice(0, 20)
    : [];
  if (!phrases.length) return json({ summary: "" });
  const joined = phrases.map((p, i) => `(${i + 1}) ${p}`).join(" ");
  const prompt =
    "Ești un ghid cald și onest care scrie în română. Cineva a vorbit în somn; frazele auzite: " +
    joined + ". " +
    "Scrie EXACT două propoziții scurte și blânde: prima rezumă despre ce pare să fi vorbit (NU inventa nimic în plus), " +
    "a doua e o încurajare caldă (vorbitul în somn e frecvent și normal, nu e un diagnostic). " +
    "Fără emoji, fără listă, fără introducere.";
  const out = (await runText(env, prompt, 160)).trim();
  return json({ summary: out.slice(0, 400) });
}

// ── Înregistrarea completă a nopții → R2 (contul companiei), ștearsă la 24h ──
async function handleRecordingUpload(request, env, uid, sessionId) {
  if (!env.RECORDS) return json({ error: "Stocarea R2 nu e configurată încă." }, 503);
  const buf = await request.arrayBuffer();
  if (!buf || buf.byteLength < 1000) return json({ error: "Înregistrare goală." }, 400);
  if (buf.byteLength > 80_000_000) return json({ error: "Înregistrare prea mare." }, 413);
  await env.RECORDS.put(`${uid}/${sessionId}.m4a`, buf, {
    httpMetadata: { contentType: "audio/mp4" },
    customMetadata: { at: String(Date.now()) },
  });
  return json({ ok: true, expiresInHours: 24 });
}

async function handleRecordingGet(env, uid, sessionId) {
  if (!env.RECORDS) return json({ error: "Stocarea R2 nu e configurată încă." }, 503);
  const obj = await env.RECORDS.get(`${uid}/${sessionId}.m4a`);
  if (!obj) return json({ error: "Înregistrarea a expirat (se șterge automat după 24h)." }, 404);
  const at = Number(obj.customMetadata && obj.customMetadata.at) || 0;
  if (at > 0 && Date.now() - at > 24 * 3600_000) {
    await env.RECORDS.delete(`${uid}/${sessionId}.m4a`);
    return json({ error: "Înregistrarea a expirat (se șterge automat după 24h)." }, 404);
  }
  return new Response(obj.body, {
    headers: { "content-type": "audio/mp4", "cache-control": "no-store" },
  });
}

// ── Rezumatul de dimineață — două propoziții calde, din cifre reale ──────────
async function handleSleepSummary(request, env) {
  let s;
  try { s = await request.json(); } catch (_) { return json({ error: "Cerere invalidă." }, 400); }
  const prompt =
    "Ești un coach de somn cald și onest, care scrie în română. Din datele: " +
    `durată ${s.minutes || 0} minute, scor ${s.score || 0}/100, profund ${s.deepMin || 0} min, ` +
    `REM ${s.remMin || 0} min, ${s.movements || 0} mișcări, ${s.snoreEvents || 0} episoade de sforăit, ` +
    `${s.talkEvents || 0} episoade de vorbit. ` +
    "Scrie EXACT două propoziții scurte: prima descrie noaptea, a doua dă un sfat blând și concret. " +
    "Fără diagnostice medicale, fără emoji, fără introducere.";
  const out = (await runText(env, prompt, 160)).trim();
  return json({ summary: out.slice(0, 400) });
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
        records: !!env.RECORDS,
      });
    }
    if (request.method === "GET" && url.pathname === "/v1/diag") {
      return handleDiag(env);
    }

    // ── Media licențiată (Adobe Stock FREE, fără watermark) — publică, cache lung ──
    if (request.method === "GET" && url.pathname === "/media/_list") {
      if (!env.MEDIA) return json([]);
      try {
        const keys = [];
        let cursor;
        do {
          const page = await env.MEDIA.list({ cursor, limit: 1000 });
          for (const o of page.objects) keys.push(o.key);
          cursor = page.truncated ? page.cursor : undefined;
        } while (cursor);
        return new Response(JSON.stringify(keys), {
          headers: { "content-type": "application/json", "cache-control": "public, max-age=300" },
        });
      } catch (_) {
        return json([]);
      }
    }
    // Administrare (doar CI, cu cheia de admin): generează o poză cu FLUX direct în R2.
    if (request.method === "POST" && url.pathname === "/media/_generate") {
      if (!env.ADMIN_KEY || request.headers.get("X-Admin") !== env.ADMIN_KEY) {
        return json({ error: "Interzis." }, 403);
      }
      if (!env.MEDIA || !env.AI) return json({ error: "Media/AI neconfigurate." }, 503);
      let body;
      try { body = await request.json(); } catch (_) { return json({ error: "Cerere invalidă." }, 400); }
      const key = String(body.key || "").replace(/[^0-9a-zA-Z._-]/g, "");
      const prompt = String(body.prompt || "").slice(0, 1200);
      if (!key || !prompt) return json({ error: "Lipsește key/prompt." }, 400);
      try {
        const r = await env.AI.run("@cf/black-forest-labs/flux-1-schnell", { prompt, steps: 8 });
        const b64 = r && r.image;
        if (!b64) return json({ error: "FLUX n-a întors imagine." }, 502);
        const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
        await env.MEDIA.put(key, bytes, { httpMetadata: { contentType: "image/jpeg" } });
        return json({ ok: true, key, size: bytes.length });
      } catch (e) {
        return json({ error: "Generarea a eșuat: " + String(e && e.message ? e.message : e).slice(0, 200) }, 502);
      }
    }

    if (request.method === "GET" && url.pathname.startsWith("/media/")) {
      if (!env.MEDIA) return json({ error: "Media neconfigurată." }, 404);
      const key = decodeURIComponent(url.pathname.slice("/media/".length)).replace(/[^0-9a-zA-Z._-]/g, "");
      if (!key) return json({ error: "Lipsește fișierul." }, 400);
      const obj = await env.MEDIA.get(key);
      if (!obj) return json({ error: "Nu există." }, 404);
      const type = key.endsWith(".mp4") ? "video/mp4" : key.endsWith(".png") ? "image/png" : "image/jpeg";
      return new Response(obj.body, {
        headers: {
          "content-type": type,
          "cache-control": "public, max-age=604800, immutable",
          "accept-ranges": "bytes",
        },
      });
    }

    const uid = await requireUser(request);
    if (!uid) return json({ error: "Cont FORJA necesar." }, 401);

    const session = (url.searchParams.get("session") || "").replace(/[^0-9a-zA-Z_-]/g, "").slice(0, 40);

    if (request.method === "GET" && url.pathname === "/v1/sleep-recording") {
      if (!session) return json({ error: "Lipsește sesiunea." }, 400);
      return handleRecordingGet(env, uid, session);
    }
    if (request.method !== "POST") return json({ error: "Metodă greșită." }, 405);

    if (url.pathname === "/v1/meal") return handleMeal(request, env);
    if (url.pathname === "/v1/sleep-audio") return handleSleepAudio(request, env);
    if (url.pathname === "/v1/sleep-summary") return handleSleepSummary(request, env);
    if (url.pathname === "/v1/sleep-talk-summary") return handleSleepTalkSummary(request, env);
    if (url.pathname === "/v1/sleep-recording") {
      if (!session) return json({ error: "Lipsește sesiunea." }, 400);
      return handleRecordingUpload(request, env, uid, session);
    }
    return json({ error: "Rută necunoscută." }, 404);
  },

  // Curățenie orară: orice înregistrare mai veche de 24h dispare.
  async scheduled(event, env) {
    if (!env.RECORDS) return;
    try {
      let cursor;
      do {
        const page = await env.RECORDS.list({ cursor, limit: 500 });
        for (const obj of page.objects) {
          const at = Number(obj.customMetadata && obj.customMetadata.at) || obj.uploaded?.getTime?.() || 0;
          if (at > 0 && Date.now() - at > 24 * 3600_000) {
            await env.RECORDS.delete(obj.key);
          }
        }
        cursor = page.truncated ? page.cursor : undefined;
      } while (cursor);
    } catch (_) { }
  },
};
