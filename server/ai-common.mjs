// Unelte comune adaptoarelor AI: erori tipizate (fără chei în mesaj), fetch cu timeout, base64 pe Workers.

export class AiError extends Error {
  /** kind: network | http | json | empty | unsupported | blocked | budget; fatal = 429/401/403: routerul sare restul modelelor furnizorului
   *  (excepție: 429 la un furnizor cu `perModelQuota`, ca Gemini, unde fiecare model are cota lui → doar următorul model). */
  constructor(message, { provider = "", model = "", status = 0, kind = "http", fatal = false } = {}) {
    super(message);
    this.name = "AiError";
    this.provider = provider; this.model = model; this.status = status; this.kind = kind; this.fatal = fatal;
  }
}

export const errorText = (e) => String(e && e.message ? e.message : e).slice(0, 200);

/** Cere `fetch` din globalThis la fiecare apel — testele îl înlocuiesc. Timeout prin AbortSignal. */
export async function fetchWithTimeout(url, init, timeoutMs) {
  const signal = AbortSignal.timeout(Math.max(1000, timeoutMs || 60000));
  try {
    return await globalThis.fetch(url, { ...init, signal });
  } catch (e) {
    throw new AiError("rețea: " + errorText(e), { kind: "network" });
  }
}

/** POST JSON → JSON; pe !ok aruncă AiError cu status (corpul răspunsului nu ajunge în mesaj: poate conține ecouri ale cererii). */
export async function postJson(url, headers, body, timeoutMs, provider, model) {
  let resp;
  try {
    resp = await fetchWithTimeout(url, { method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body) }, timeoutMs);
  } catch (e) { e.provider = provider; e.model = model; throw e; }
  if (!resp.ok) {
    const status = resp.status;
    throw new AiError(`${provider} a răspuns cu ${status}`, { provider, model, status, kind: "http", fatal: status === 429 || status === 401 || status === 403 });
  }
  try { return await resp.json(); } catch (_) {
    throw new AiError(`${provider}: răspuns care nu e JSON`, { provider, model, kind: "json" });
  }
}

export function bytesToB64(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let binary = "";
  for (let i = 0; i < u8.length; i += 0x8000) binary += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(binary);
}

export function b64ToBytes(b64) {
  const bin = atob(String(b64 || "").replace(/\s+/g, ""));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Dimensiunea decodată (octeți) a unui șir base64, fără a-l decoda. */
export function b64Size(b64) {
  const s = String(b64 || "");
  if (!s) return 0;
  const pad = s.endsWith("==") ? 2 : s.endsWith("=") ? 1 : 0;
  return Math.floor((s.length * 3) / 4) - pad;
}

export const looksLikeB64 = (s) => typeof s === "string" && s.length >= 16 && /^[A-Za-z0-9+/=]+$/.test(s.slice(0, 512));

/** Rândurile „[eticheta]” + imagine, ca modelul să știe care poză e care. */
export const labelText = (item, fallback) => (item && item.label ? `[${String(item.label).slice(0, 80)}]` : fallback);
