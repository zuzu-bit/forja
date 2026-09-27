// Claude (Anthropic Messages API) — cea mai bună analiză foto/PDF/text, DOAR dacă există ANTHROPIC_API_KEY (cheie plătită).
import { AiError, postJson, labelText } from "./ai-common.mjs";

export const ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
const IMAGE_MIMES = new Set(["image/jpeg", "image/png", "image/gif", "image/webp"]);

export const anthropic = {
  name: "anthropic",
  timeoutMs: 60000,
  supports: { images: true, documents: true, audio: false, verifyWithImages: true },
  available: (env) => typeof env?.ANTHROPIC_API_KEY === "string" && env.ANTHROPIC_API_KEY.length > 0,
  models: (env) => [...new Set([env?.ANTHROPIC_MODEL || "claude-fable-5-1", "claude-opus-5-5", "claude-sonnet-5"])],
  dailyLimit: null,

  /** Un apel: întoarce textul modelului. Fără temperature/thinking explicit (Fable 5.1 le respinge); JSON-ul e cerut prin prompt și validat de router. */
  async generate(env, { model, system, prompt, images = [], documents = [], maxTokens, timeoutMs }) {
    const content = [];
    for (const doc of documents) {
      content.push({ type: "text", text: labelText(doc, `[document ${doc.name || ""}]`.trim()) });
      content.push({ type: "document", source: { type: "base64", media_type: doc.mime || "application/pdf", data: doc.b64 } });
    }
    for (const img of images) {
      content.push({ type: "text", text: labelText(img, "[imagine]") });
      content.push({ type: "image", source: { type: "base64", media_type: IMAGE_MIMES.has(img.mime) ? img.mime : "image/jpeg", data: img.b64 } });
    }
    content.push({ type: "text", text: prompt });
    const body = {
      model,
      max_tokens: Math.min(16000, Math.max(256, maxTokens || 4000)),
      ...(system ? { system } : {}),
      messages: [{ role: "user", content }],
    };
    const data = await postJson(ANTHROPIC_URL, { "x-api-key": env.ANTHROPIC_API_KEY, "anthropic-version": "2023-06-01" }, body, timeoutMs || this.timeoutMs, "anthropic", model);
    if (data?.stop_reason === "refusal") throw new AiError("anthropic a refuzat cererea", { provider: "anthropic", model, kind: "blocked" });
    const text = (Array.isArray(data?.content) ? data.content : []).filter((b) => b?.type === "text" && typeof b.text === "string").map((b) => b.text).join("");
    if (!text.trim()) throw new AiError("anthropic: răspuns gol", { provider: "anthropic", model, kind: "empty" });
    return text;
  },
};
