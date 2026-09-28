// OpenAI (Chat Completions, JSON mode, imagini `image_url` data:) — opțional, doar cu OPENAI_API_KEY.
// Același protocol îl vorbește și Groq (ai-groq.mjs), de aceea corpul cererii e o funcție reutilizabilă.
import { AiError, postJson, labelText, readKey } from "./ai-common.mjs";

export const OPENAI_URL = "https://api.openai.com/v1/chat/completions";

/** Un apel Chat Completions compatibil OpenAI; întoarce textul asistentului. Documentele PDF nu se trimit (doar text/metadate, în prompt). */
export async function chatCompletionsJson({ url, apiKey, provider, model, system, prompt, images = [], schema, maxTokens, timeoutMs, jsonMode = true, maxTokensField = "max_tokens" }) {
  const content = [];
  for (const img of images) {
    content.push({ type: "text", text: labelText(img, "[imagine]") });
    content.push({ type: "image_url", image_url: { url: `data:${img.mime || "image/jpeg"};base64,${img.b64}` } });
  }
  content.push({ type: "text", text: prompt });
  const body = {
    model,
    messages: [...(system ? [{ role: "system", content: system }] : []), { role: "user", content: images.length ? content : prompt }],
    [maxTokensField]: Math.min(16000, Math.max(256, maxTokens || 4000)),
  };
  // JSON mode cere ca promptul să conțină cuvântul „JSON” — prompturile noastre îl conțin; la viziune unele modele nu-l acceptă.
  if (jsonMode && schema && !images.length) body.response_format = { type: "json_object" };
  const data = await postJson(url, { authorization: "Bearer " + apiKey }, body, timeoutMs, provider, model);
  const choice = data?.choices?.[0];
  if (choice?.finish_reason === "content_filter") throw new AiError(provider + " a blocat cererea", { provider, model, kind: "blocked" });
  const text = typeof choice?.message?.content === "string" ? choice.message.content : Array.isArray(choice?.message?.content) ? choice.message.content.map((p) => p?.text || "").join("") : "";
  if (!text.trim()) throw new AiError(provider + ": răspuns gol", { provider, model, kind: "empty" });
  return text;
}

export const openai = {
  name: "openai",
  timeoutMs: 60000,
  supports: { images: true, documents: false, audio: false, verifyWithImages: true },
  available: (env) => readKey(env, "OPENAI_API_KEY").length > 0,
  models: (env) => [...new Set([env?.OPENAI_MODEL || "gpt-5", "gpt-4.1"])],
  dailyLimit: null,
  generate(env, opts) {
    // gpt-5 acceptă doar max_completion_tokens și fără temperature; gpt-4.1 le acceptă pe amândouă.
    return chatCompletionsJson({ ...opts, url: OPENAI_URL, apiKey: readKey(env, "OPENAI_API_KEY"), provider: "openai", timeoutMs: opts.timeoutMs || this.timeoutMs, maxTokensField: "max_completion_tokens" });
  },
};
