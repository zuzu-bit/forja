/** Google Gemini adapter for the insights worker. Used only when GEMINI_API_KEY exists; callers fall back to Workers AI on any error. */
export const GEMINI_MODEL = 'gemini-2.5-flash';
export const GEMINI_ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta/models/' + GEMINI_MODEL + ':generateContent';
const TIMEOUT_MS = 45000;
// The Gemini responseSchema accepts only this OpenAPI subset: const, oneOf and additionalProperties are rejected,
// `enum` is a list of STRINGS (a boolean or number inside it is an HTTP 400) and `format` allows only a few named values.
const SCHEMA_KEYS = new Set(['type','format','description','nullable','enum','items','properties','required','minItems','maxItems','minLength','maxLength','minimum','maximum','pattern','anyOf']);
const FORMATS = new Set(['enum','date-time','float','double','int32','int64']);
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const typeOf = value => typeof value === 'boolean' ? 'boolean' : typeof value === 'number' ? (Number.isInteger(value) ? 'integer' : 'number') : 'string';
const fixed = value => 'Valoare fixă: ' + JSON.stringify(value) + '.';

const geminiKey = env => (typeof env?.GEMINI_API_KEY === 'string' ? env.GEMINI_API_KEY : '').trim().replace(/^["'`]+|["'`]+$/g, '');
export function geminiAvailable(env) { return geminiKey(env).length > 0; }

/**
 * Derives a relaxed schema Gemini accepts: const → single-value string enum (non-string or empty constants become a typed field
 * with the value in `description`), oneOf → anyOf, `type` arrays → type + nullable, additionalProperties/unknown keywords/unsupported formats dropped.
 * Callers keep their own validators as the hard gate.
 */
export function geminiSchema(schema) {
  if (Array.isArray(schema)) return schema.map(geminiSchema);
  if (!object(schema)) return schema;
  const out = {};
  for (const [key, value] of Object.entries(schema)) {
    if (key === 'const') {
      if (typeof value === 'string' && value.length) out.enum = [value];
      else { out.type = out.type ?? typeOf(value); out.description = [schema.description, fixed(value)].filter(Boolean).join(' '); }
      continue;
    }
    if (key === 'description' && out.description) continue;
    if (key === 'oneOf') { out.anyOf = value.map(geminiSchema); continue; }
    if (!SCHEMA_KEYS.has(key)) continue;
    if (key === 'type' && Array.isArray(value)) { const kinds = value.filter(t => t !== 'null'); if (kinds.length) out.type = kinds[0]; if (value.includes('null')) out.nullable = true; continue; }
    if (key === 'format') { if (FORMATS.has(value)) out.format = value; continue; }
    if (key === 'enum') {
      if (Array.isArray(value) && value.length && value.every(v => typeof v === 'string' && v.length)) out.enum = value;
      else if (Array.isArray(value) && value.length) out.description = [schema.description, 'Valori permise: ' + value.map(v => JSON.stringify(v)).join(', ') + '.'].filter(Boolean).join(' ');
      continue;
    }
    if (key === 'properties') { out.properties = Object.fromEntries(Object.entries(value).map(([name, sub]) => [name, geminiSchema(sub)])); continue; }
    if (key === 'items' || key === 'anyOf') { out[key] = geminiSchema(value); continue; }
    out[key] = value;
  }
  // Gemini needs `type` on every node; an enum without a declared type is a string enum.
  if (out.enum && !out.type) out.type = 'string';
  if (Array.isArray(schema.enum) && schema.enum.length && !out.enum && !out.type) out.type = typeOf(schema.enum[0]);
  return out;
}

/** Converts an OpenAI-style message content (string, or [{type:'text'},{type:'image_url'}]) into Gemini parts. */
export function geminiParts(content) {
  if (typeof content === 'string') return [{ text: content }];
  if (!Array.isArray(content)) throw new Error('gemini_unsupported_content');
  return content.map(piece => {
    if (piece?.type === 'text') return { text: String(piece.text ?? '') };
    if (piece?.type === 'image_url') {
      const match = /^data:([\w.+-]+\/[\w.+-]+);base64,([A-Za-z0-9+/=]+)$/.exec(String(piece.image_url?.url ?? ''));
      if (!match) throw new Error('gemini_unsupported_image');
      return { inline_data: { mime_type: match[1], data: match[2] } };
    }
    throw new Error('gemini_unsupported_content');
  });
}

export function inlineData(bytes, mimeType) {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 32768) binary += String.fromCharCode(...bytes.subarray(i, i + 32768));
  return { inline_data: { mime_type: mimeType, data: btoa(binary) } };
}

/**
 * Runs one generateContent call. input = {system, parts, schema?, maxTokens?, temperature?}.
 * Resolves to {response: text} so JSON parsers written for Workers AI work unchanged. Throws on any HTTP, safety or shape error.
 */
export async function geminiGenerate(env, input, fetcher = fetch) {
  if (!geminiAvailable(env)) throw new Error('gemini_key_missing');
  if (!object(input) || !Array.isArray(input.parts) || !input.parts.length) throw new Error('gemini_input_invalid');
  const generationConfig = { temperature: Number.isFinite(input.temperature) ? input.temperature : 0.1, maxOutputTokens: Number.isSafeInteger(input.maxTokens) && input.maxTokens > 0 ? input.maxTokens : 2000,
    // Gemini 2.5 Flash thinks by default and the thinking tokens count against maxOutputTokens; short budgets (250–500) would come back empty.
    thinkingConfig: { thinkingBudget: 0 } };
  if (input.schema) { generationConfig.responseMimeType = 'application/json'; generationConfig.responseSchema = geminiSchema(input.schema); }
  const body = { ...(input.system ? { system_instruction: { parts: [{ text: String(input.system) }] } } : {}), contents: [{ role: 'user', parts: input.parts }], generationConfig };
  const response = await fetcher(GEMINI_ENDPOINT, { method: 'POST', headers: { 'content-type': 'application/json', 'x-goog-api-key': geminiKey(env) }, body: JSON.stringify(body), signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!response.ok) { const error = new Error('gemini_http_' + response.status); error.httpStatus = response.status; throw error; }
  const data = await response.json();
  if (data?.promptFeedback?.blockReason) throw new Error('gemini_blocked');
  const candidate = data?.candidates?.[0];
  const text = (candidate?.content?.parts ?? []).filter(p => typeof p?.text === 'string').map(p => p.text).join('');
  if (!text.trim()) throw new Error('gemini_empty_' + String(candidate?.finishReason ?? 'response').toLowerCase());
  return { response: text, model: GEMINI_MODEL };
}
