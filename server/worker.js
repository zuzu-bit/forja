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

// ═══════════════ ADMINISTRARE — jurnal, comenzi, panou web ═══════════════
// Jurnalul de evenimente trăiește în R2 (forja-media) sub prefixul _admin/,
// care e invizibil public: exclus din /media/_list, iar GET /media/ nu acceptă „/".
const LOG_KEY = "_admin/log.json";

async function readLog(env) {
  if (!env.MEDIA) return [];
  try {
    const obj = await env.MEDIA.get(LOG_KEY);
    if (!obj) return [];
    const data = JSON.parse(await obj.text());
    return Array.isArray(data) ? data : [];
  } catch (_) { return []; }
}

async function logEvent(env, what, status, ms) {
  if (!env.MEDIA) return;
  try {
    const events = await readLog(env);
    events.push({ at: Date.now(), what, status, ms });
    while (events.length > 300) events.shift();
    await env.MEDIA.put(LOG_KEY, JSON.stringify(events), {
      httpMetadata: { contentType: "application/json" },
    });
  } catch (_) { }
}

function fmtSize(n) {
  if (n >= 1000000) return (n / 1000000).toFixed(1).replace(".", ",") + " MB";
  if (n >= 1000) return Math.round(n / 1000) + " KB";
  return n + " B";
}

async function generateMedia(env, key, prompt) {
  if (!env.MEDIA || !env.AI) return { error: "Media/AI neconfigurate." };
  const r = await env.AI.run("@cf/black-forest-labs/flux-1-schnell", { prompt, steps: 8 });
  const b64 = r && r.image;
  if (!b64) return { error: "FLUX n-a întors imagine." };
  const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  await env.MEDIA.put(key, bytes, { httpMetadata: { contentType: "image/jpeg" } });
  return { ok: true, key, size: bytes.length };
}

async function purgeExpired(env) {
  if (!env.RECORDS) return 0;
  let n = 0;
  let cursor;
  do {
    const page = await env.RECORDS.list({ cursor, limit: 500, include: ["customMetadata"] });
    for (const obj of page.objects) {
      const at = Number(obj.customMetadata && obj.customMetadata.at) || obj.uploaded?.getTime?.() || 0;
      if (at > 0 && Date.now() - at > 24 * 3600_000) {
        await env.RECORDS.delete(obj.key);
        n++;
      }
    }
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  return n;
}

async function listBucket(bucket, skipAdmin) {
  const rows = [];
  let cursor;
  do {
    const page = await bucket.list({ cursor, limit: 1000, include: ["customMetadata"] });
    for (const o of page.objects) {
      if (skipAdmin && o.key.startsWith("_admin/")) continue;
      rows.push(o);
    }
    cursor = page.truncated ? page.cursor : undefined;
  } while (cursor);
  return rows;
}

/** Dispecerul de comenzi al terminalului de admin (merge și din curl). */
async function runCmd(env, line, host) {
  const raw = String(line || "").trim();
  const parts = raw.split(/\s+/);
  const c0 = (parts[0] || "").toLowerCase();
  const c1 = (parts[1] || "").toLowerCase();

  if (!c0 || c0 === "help") {
    return [
      "Comenzi FORJA admin:",
      "  status                       starea serviciului",
      "  diag                         diagnoza modelelor AI (durează ~30s)",
      "  media ls                     fișierele media din R2",
      "  media rm <fișier>            șterge un fișier media",
      "  media gen <fișier> | <prompt>  generează imagine cu FLUX",
      "  rec ls                       înregistrările de somn din R2",
      "  rec rm <cheie>               șterge o înregistrare",
      "  rec purge                    șterge acum înregistrările expirate (>24h)",
      "  log [n]                      ultimele n evenimente (implicit 30)",
      "  log clear                    golește jurnalul",
      "",
      "Din terminalul tău: curl -H \"X-Admin: CHEIA\" -d \"media ls\" https://" + host + "/admin/api/cmd",
      "Conturile de utilizatori se administrează în Firebase Console (proiect " + FIREBASE_PROJECT + ").",
    ].join("\n");
  }

  if (c0 === "status") {
    return [
      "serviciu:      forja-api · online",
      "adresă:        https://" + host,
      "analiza mese:  " + (env.GEMINI_API_KEY ? "gemini" : "banca de modele Cloudflare"),
      "audio (somn):  " + (env.AI ? "whisper activ" : "indisponibil"),
      "media R2:      " + (env.MEDIA ? "configurată" : "LIPSĂ"),
      "înregistrări:  " + (env.RECORDS ? "configurate (ștergere la 24h)" : "LIPSĂ"),
      "cont Firebase: " + FIREBASE_PROJECT,
    ].join("\n");
  }

  if (c0 === "diag") {
    try {
      const r = await handleDiag(env);
      const t = await r.text();
      try { return JSON.stringify(JSON.parse(t), null, 2); } catch (_) { return t; }
    } catch (e) {
      return "Diagnoza a eșuat: " + String(e && e.message ? e.message : e).slice(0, 200);
    }
  }

  if (c0 === "media" && c1 === "ls") {
    if (!env.MEDIA) return "Media R2 neconfigurată.";
    const rows = await listBucket(env.MEDIA, true);
    if (rows.length === 0) return "Niciun fișier media.";
    let total = 0;
    const lines = rows.map((o) => { total += o.size; return "  " + o.key.padEnd(34) + fmtSize(o.size); });
    lines.push("  ──");
    lines.push("  " + rows.length + " fișiere · " + fmtSize(total));
    return lines.join("\n");
  }

  if (c0 === "media" && c1 === "rm") {
    if (!env.MEDIA) return "Media R2 neconfigurată.";
    const key = String(parts[2] || "").replace(/[^0-9a-zA-Z._-]/g, "");
    if (!key) return "Folosire: media rm <fișier>";
    if (key.startsWith("_admin")) return "Refuzat.";
    const head = await env.MEDIA.head(key);
    if (!head) return "Nu există: " + key;
    await env.MEDIA.delete(key);
    await logEvent(env, "ADMIN: media rm " + key, 200, 0);
    return "Șters: " + key + " (" + fmtSize(head.size) + ")";
  }

  if (c0 === "media" && c1 === "gen") {
    const rest = raw.replace(/^\s*media\s+gen\s+/i, "");
    const bar = rest.indexOf("|");
    if (bar < 1) return "Folosire: media gen <fișier> | <prompt în engleză>";
    const key = rest.slice(0, bar).trim().replace(/[^0-9a-zA-Z._-]/g, "");
    const prompt = rest.slice(bar + 1).trim().slice(0, 1200);
    if (!key || !prompt) return "Folosire: media gen <fișier> | <prompt în engleză>";
    if (key.startsWith("_admin")) return "Refuzat.";
    try {
      const out = await generateMedia(env, key, prompt);
      if (out.ok) {
        await logEvent(env, "ADMIN: media gen " + key, 200, 0);
        return "Generat: " + key + " (" + fmtSize(out.size) + ")";
      }
      return "Eroare: " + (out.error || "necunoscută");
    } catch (e) {
      return "Generarea a eșuat: " + String(e && e.message ? e.message : e).slice(0, 200);
    }
  }

  if (c0 === "rec" && c1 === "ls") {
    if (!env.RECORDS) return "Stocarea înregistrărilor neconfigurată.";
    const rows = await listBucket(env.RECORDS, false);
    if (rows.length === 0) return "Nicio înregistrare (se șterg automat la 24h).";
    let total = 0;
    const lines = rows.map((o) => {
      total += o.size;
      const at = Number(o.customMetadata && o.customMetadata.at) || o.uploaded?.getTime?.() || 0;
      const ageH = at > 0 ? ((Date.now() - at) / 3600_000).toFixed(1) : "?";
      return "  " + o.key.padEnd(46) + fmtSize(o.size).padEnd(9) + ageH + "h";
    });
    lines.push("  ──");
    lines.push("  " + rows.length + " înregistrări · " + fmtSize(total));
    return lines.join("\n");
  }

  if (c0 === "rec" && c1 === "rm") {
    if (!env.RECORDS) return "Stocarea înregistrărilor neconfigurată.";
    const key = String(parts[2] || "").replace(/[^0-9a-zA-Z._/-]/g, "");
    if (!key) return "Folosire: rec rm <cheie>";
    const head = await env.RECORDS.head(key);
    if (!head) return "Nu există: " + key;
    await env.RECORDS.delete(key);
    await logEvent(env, "ADMIN: rec rm " + key, 200, 0);
    return "Șters: " + key;
  }

  if (c0 === "rec" && c1 === "purge") {
    const n = await purgeExpired(env);
    await logEvent(env, "ADMIN: rec purge (" + n + ")", 200, 0);
    return n === 0 ? "Nimic expirat de șters." : "Șterse: " + n + " înregistrări expirate.";
  }

  if (c0 === "log" && c1 === "clear") {
    if (!env.MEDIA) return "Jurnal indisponibil (media R2 neconfigurată).";
    await env.MEDIA.put(LOG_KEY, "[]", { httpMetadata: { contentType: "application/json" } });
    return "Jurnal golit.";
  }

  if (c0 === "log") {
    const n = Math.min(Math.max(parseInt(c1 || "30", 10) || 30, 1), 300);
    const events = (await readLog(env)).slice(-n);
    if (events.length === 0) return "Jurnal gol — folosește aplicația și revino.";
    return events.map((e) => {
      const d = new Date(e.at);
      const hh = String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0") + ":" + String(d.getSeconds()).padStart(2, "0");
      return "  " + hh + "  " + String(e.status).padEnd(4) + String(e.ms + "ms").padEnd(8) + e.what;
    }).join("\n");
  }

  return "Comandă necunoscută: „" + raw.slice(0, 60) + "”. Scrie «help».";
}

async function handleAdminApi(request, env, url) {
  if (!env.ADMIN_KEY || request.headers.get("X-Admin") !== env.ADMIN_KEY) {
    return json({ error: "Cheie de admin greșită." }, 403);
  }
  if (request.method === "GET" && url.pathname === "/admin/api/overview") {
    let mediaCount = 0, mediaBytes = 0, recCount = 0, recBytes = 0;
    try {
      if (env.MEDIA) for (const o of await listBucket(env.MEDIA, true)) { mediaCount++; mediaBytes += o.size; }
    } catch (_) { }
    try {
      if (env.RECORDS) for (const o of await listBucket(env.RECORDS, false)) { recCount++; recBytes += o.size; }
    } catch (_) { }
    const log = (await readLog(env)).slice(-30);
    return json({
      host: url.host,
      colo: (request.cf && request.cf.colo) || "",
      meals: env.GEMINI_API_KEY ? "gemini" : "banca de modele",
      audio: env.AI ? "whisper activ" : "indisponibil",
      mediaCount, mediaBytes, recCount, recBytes, log,
    });
  }
  if (request.method === "POST" && url.pathname === "/admin/api/cmd") {
    const line = (await request.text()).slice(0, 2000);
    const out = await runCmd(env, line, url.host);
    return json({ ok: true, out });
  }
  return json({ error: "Rută necunoscută." }, 404);
}

const ADMIN_HTML = String.raw`<!doctype html>
<html lang="ro"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>FORJA · Admin</title>
<style>
:root{--bg:#0A0A0B;--panel:#121214;--panel2:#1A1A1E;--line:rgba(255,255,255,.08);--txt:#F4F2EE;--dim:#A7A9AE;--dim2:#7A7D83;--amber:#FFB300;--orange:#FF7A00;--green:#2FBE71;--red:#FF4D3A}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);font:14px/1.45 system-ui,'Segoe UI',Roboto,sans-serif}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
header{position:sticky;top:0;background:rgba(10,10,11,.92);backdrop-filter:blur(8px);border-bottom:1px solid var(--line);padding:14px 20px;display:flex;align-items:center;gap:12px;z-index:5}
header h1{font-size:15px;margin:0;letter-spacing:.12em}
header h1 b{color:var(--amber)}
.dot{width:9px;height:9px;border-radius:50%;background:var(--dim2)}
.dot.on{background:var(--green);box-shadow:0 0 8px rgba(47,190,113,.8)}
.spacer{flex:1}
button{background:var(--panel2);color:var(--txt);border:1px solid var(--line);border-radius:10px;padding:8px 12px;font:600 12px system-ui;cursor:pointer}
button:hover{border-color:rgba(255,179,0,.5)}
button.primary{background:linear-gradient(92deg,var(--orange),var(--amber));color:#141008;border:none}
main{max-width:1080px;margin:0 auto;padding:20px 20px 40px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:14px}
.card h3{margin:0 0 8px;font-size:10px;letter-spacing:.14em;color:var(--dim2);font-weight:700}
.big{font-size:21px;font-weight:800}
.sub{color:var(--dim);font-size:12px;margin-top:2px}
.ok{color:var(--green)}.warn{color:var(--amber)}.err{color:var(--red)}
section{margin-top:22px}
section>h2{font-size:10px;letter-spacing:.14em;color:var(--dim2);margin:0 0 10px}
table{width:100%;border-collapse:collapse;font-size:12px}
td,th{padding:6px 10px;border-bottom:1px solid var(--line);text-align:left;white-space:nowrap}
th{color:var(--dim2);font-size:10px;letter-spacing:.1em}
td.num{text-align:right}
#term{background:#0D0D0F;border:1px solid var(--line);border-radius:14px;overflow:hidden}
#out{margin:0;padding:14px;height:300px;overflow:auto;font-size:12.5px;white-space:pre-wrap;color:#D9D7D2}
#out .in{color:var(--amber)}
.tline{display:flex;border-top:1px solid var(--line);margin:0}
.tline span{padding:12px 0 12px 14px;color:var(--amber);font-weight:700}
#cmd{flex:1;background:transparent;border:0;outline:0;color:var(--txt);padding:12px 14px;font:inherit}
#login{position:fixed;inset:0;background:rgba(6,6,7,.95);display:flex;align-items:center;justify-content:center;z-index:20}
#login .card{width:min(400px,92vw)}
#login input{width:100%;background:var(--panel2);border:1px solid var(--line);border-radius:10px;color:var(--txt);padding:11px 12px;margin:12px 0;font:inherit}
</style></head>
<body>
<header><h1>FORJA <b>ADMIN</b></h1><span class="dot" id="dot"></span><span class="sub" id="stat">se conectează…</span><span class="spacer"></span>
<button onclick="loadAll()">Reîmprospătează</button>
<button onclick="logout()">Ieșire</button></header>
<main>
<div class="grid" id="cards"></div>
<section><h2>JURNAL DE EVENIMENTE · ultimele 30 · se actualizează singur</h2>
<div class="card" style="padding:0;overflow:auto"><table id="logt"><thead><tr><th>ORA</th><th>EVENIMENT</th><th>STATUS</th><th class="num">DURATĂ</th></tr></thead><tbody></tbody></table></div></section>
<section><h2>TERMINAL — scrie «help» pentru comenzi</h2>
<div id="term" class="mono"><pre id="out">FORJA admin. Scrie «help» și apasă Enter.
</pre><form class="tline" onsubmit="return go(event)"><span>&gt;</span><input id="cmd" class="mono" autocomplete="off" spellcheck="false" placeholder="help"></form></div>
</section>
</main>
<div id="login" style="display:none"><div class="card"><h3>AUTENTIFICARE ADMIN</h3><div class="sub">Introdu cheia de administrare a serverului FORJA.</div><input id="key" type="password" placeholder="cheia de admin" onkeydown="if(event.key==='Enter')saveKey()"><button class="primary" style="width:100%" onclick="saveKey()">Intră</button></div></div>
<script>
var KEY = sessionStorage.getItem('forjaAdmin') || '';
var hist = []; var hi = 0;
function logout(){ sessionStorage.removeItem('forjaAdmin'); location.reload(); }
function needKey(){ document.getElementById('login').style.display='flex'; document.getElementById('key').focus(); }
function saveKey(){ var v = document.getElementById('key').value.trim(); if(!v) return; KEY=v; sessionStorage.setItem('forjaAdmin', v); document.getElementById('login').style.display='none'; loadAll(); }
function api(p, opt){ opt = opt || {}; opt.headers = Object.assign({'X-Admin': KEY}, opt.headers||{}); return fetch(p, opt).then(function(r){ if(r.status===403){ needKey(); throw new Error('cheie'); } return r.json(); }); }
function esc(s){ return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;'); }
function fmtB(n){ if(n>=1000000) return (n/1000000).toFixed(1).replace('.',',')+' MB'; if(n>=1000) return Math.round(n/1000)+' KB'; return n+' B'; }
function card(t, big, sub, cls){ return '<div class="card"><h3>'+t+'</h3><div class="big '+(cls||'')+'">'+big+'</div><div class="sub">'+sub+'</div></div>'; }
function loadAll(){
  if(!KEY) return;
  api('/admin/api/overview').then(function(d){
    document.getElementById('dot').className = 'dot on';
    document.getElementById('stat').textContent = 'server activ' + (d.colo ? ' · ' + d.colo : '');
    var c = '';
    c += card('SERVER', 'online', esc(d.host), 'ok');
    c += card('ANALIZA MESELOR', esc(d.meals), 'audio somn: ' + esc(d.audio));
    c += card('MEDIA (R2)', d.mediaCount + ' fișiere', fmtB(d.mediaBytes));
    c += card('ÎNREGISTRĂRI SOMN', d.recCount + ' fișiere', fmtB(d.recBytes) + ' · dispar la 24h');
    document.getElementById('cards').innerHTML = c;
    var tb = '';
    (d.log||[]).slice().reverse().forEach(function(e){
      var t = new Date(e.at);
      var hh = ('0'+t.getHours()).slice(-2)+':'+('0'+t.getMinutes()).slice(-2)+':'+('0'+t.getSeconds()).slice(-2);
      var cls = e.status>=500?'err':(e.status>=400?'warn':'ok');
      tb += '<tr><td class="mono">'+hh+'</td><td class="mono">'+esc(e.what)+'</td><td class="mono '+cls+'">'+e.status+'</td><td class="num mono">'+e.ms+' ms</td></tr>';
    });
    document.querySelector('#logt tbody').innerHTML = tb || '<tr><td colspan="4" class="sub">încă niciun eveniment — folosește aplicația și revino</td></tr>';
  }).catch(function(){});
}
function print(s, cls){ var o = document.getElementById('out'); o.innerHTML += (cls ? '<span class="'+cls+'">'+esc(s)+'</span>' : esc(s)) + '\n'; o.scrollTop = o.scrollHeight; }
function go(ev){ ev.preventDefault(); var i = document.getElementById('cmd'); var c = i.value.trim(); if(!c) return false;
  i.value=''; hist.push(c); hi = hist.length; print('> '+c, 'in');
  api('/admin/api/cmd', {method:'POST', body:c}).then(function(d){ print(d.out||''); setTimeout(loadAll, 600); }).catch(function(){ print('eroare: cheie greșită sau server indisponibil'); });
  return false; }
document.getElementById('cmd').addEventListener('keydown', function(e){
  if(e.key==='ArrowUp'){ if(hi>0){ hi--; this.value=hist[hi]; e.preventDefault(); } }
  else if(e.key==='ArrowDown'){ if(hi<hist.length-1){ hi++; this.value=hist[hi]; } else { hi=hist.length; this.value=''; } }
});
if(!KEY) needKey(); else loadAll();
setInterval(loadAll, 30000);
</script>
</body></html>`;

function adminPage() {
  return new Response(ADMIN_HTML, {
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
  });
}

async function route(request, env, url) {
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

    // ── Panoul de administrare (web) + API-ul lui — protejat cu cheia de admin ──
    if (request.method === "GET" && url.pathname === "/admin") return adminPage();
    if (url.pathname.startsWith("/admin/api/")) return handleAdminApi(request, env, url);

    // ── Media licențiată (Adobe Stock FREE, fără watermark) — publică, cache lung ──
    if (request.method === "GET" && url.pathname === "/media/_list") {
      if (!env.MEDIA) return json([]);
      try {
        const keys = [];
        let cursor;
        do {
          const page = await env.MEDIA.list({ cursor, limit: 1000 });
          for (const o of page.objects) if (!o.key.startsWith("_admin/")) keys.push(o.key);
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
        const out = await generateMedia(env, key, prompt);
        return json(out, out.ok ? 200 : 502);
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
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const t0 = Date.now();
    let resp;
    try {
      resp = await route(request, env, url);
    } catch (e) {
      resp = json({ error: "Eroare internă: " + String(e && e.message ? e.message : e).slice(0, 200) }, 500);
    }
    // Jurnal de evenimente pentru panoul de admin — doar API-ul, nu media publică.
    if (url.pathname.startsWith("/v1/") || url.pathname === "/media/_generate") {
      try {
        ctx.waitUntil(logEvent(env, request.method + " " + url.pathname, resp.status, Date.now() - t0));
      } catch (_) { }
    }
    return resp;
  },

  // Curățenie orară: orice înregistrare mai veche de 24h dispare.
  async scheduled(event, env) {
    try {
      const n = await purgeExpired(env);
      if (n > 0) await logEvent(env, "CRON: înregistrări expirate șterse (" + n + ")", 200, 0);
    } catch (_) { }
  },
};
