// Google Gemini (cheie GRATUITĂ din AI Studio, fără card) — poze, PDF și SINGURUL drum pentru AUDIO integral.
// gemini.mjs rămâne adaptorul site-ului (API neschimbat); de aici luăm doar conversia schemei.
import { AiError, postJson, labelText } from "./ai-common.mjs";
import { geminiSchema } from "./gemini.mjs";

export const GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
// 2.5-pro nu mai e pe nivelul gratuit: îl încercăm ultimul și sărim peste el dacă nu răspunde.
export const GEMINI_MODELS = ["gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.5-pro"];

export const gemini = {
  name: "gemini",
  timeoutMs: 90000,
  audioTimeoutMs: 180000,
  supports: { images: true, documents: true, audio: true, verifyWithImages: true },
  available: (env) => typeof env?.GEMINI_API_KEY === "string" && env.GEMINI_API_KEY.length > 0,
  models: (env) => [...new Set([env?.GEMINI_MODEL || GEMINI_MODELS[0], ...GEMINI_MODELS])],
  // Cotele gratuite sunt PER MODEL (găleți separate): un 429 la Flash nu înseamnă că Flash-Lite e epuizat. Routerul numără și sare per model.
  dailyLimit: null,
  perModelQuota: true,
  modelLimits: { "gemini-2.5-flash": 250, "gemini-2.5-flash-lite": 1000, "gemini-2.5-pro": 50 }, // cereri/zi, orientativ

  async generate(env, { model, system, prompt, images = [], documents = [], audio = null, schema, maxTokens, timeoutMs, temperature }) {
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
    const budget = timeoutMs || (audio ? this.audioTimeoutMs : this.timeoutMs);
    const data = await postJson(GEMINI_BASE + model + ":generateContent", { "x-goog-api-key": env.GEMINI_API_KEY }, body, budget, "gemini", model);
    if (data?.promptFeedback?.blockReason) throw new AiError("gemini a blocat cererea", { provider: "gemini", model, kind: "blocked" });
    const candidate = data?.candidates?.[0];
    const text = (candidate?.content?.parts ?? []).filter((p) => typeof p?.text === "string").map((p) => p.text).join("");
    if (!text.trim()) throw new AiError("gemini: răspuns gol (" + String(candidate?.finishReason || "?").toLowerCase() + ")", { provider: "gemini", model, kind: "empty" });
    return text;
  },
};
