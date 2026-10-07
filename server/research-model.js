// Shared protocol validation. All telemetry is treated as untrusted input.
export const CONSENT_VERSION = "2026-10-07";
export const MAX_EVENT_BYTES = 32768;
export const MAX_BATCH_BYTES = 1048576;
export const MAX_ORIGINAL_BYTES = 8 * 1024 * 1024;
export const COMMAND_TTL_MS = 5 * 60 * 1000;
const aliases = {
  app: "APP", apps: "APP", application: "APP", applications: "APP",
  notification: "NOTIFICATION", notifications: "NOTIFICATION", notify: "NOTIFICATION",
  media: "MEDIA", location: "LOCATION", network: "NETWORK", bluetooth: "BLUETOOTH",
  contact: "CONTACT", contacts: "CONTACT", file: "FILE", files: "FILE",
  activity: "ACTIVITY", fitness: "ACTIVITY", sleep: "SLEEP", nutrition: "NUTRITION", device: "DEVICE",
};
export class ResearchError extends Error {
  constructor(status, message, details = {}) { super(message); this.status = status; this.details = details; }
}
export const fail = (status, message, details) => { throw new ResearchError(status, message, details); };
export const reply = (data, status = 200, extra = {}) => new Response(JSON.stringify(data), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extra },
});
export function identifier(value, name = "identifier") {
  if (typeof value !== "string" || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/.test(value)) fail(400, `Invalid ${name}.`);
  return value;
}
export function sourceName(value) {
  const key = typeof value === "string" ? value.toLowerCase() : "";
  const name = Object.hasOwn(aliases, key) ? aliases[key] : null;
  if (!name) fail(400, "Unknown event source.");
  return name;
}
export function sourceFilter(value) {
  if (!value || value.toLowerCase() === "all") return [];
  return [...new Set(value.split(",").map(sourceName))];
}
export function textField(value, name, max = 160) {
  if (typeof value !== "string" || value.length > max || /[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value)) fail(400, `Invalid ${name}.`);
  return value;
}
export function objectField(value, name, maxBytes = MAX_EVENT_BYTES) {
  if (!value || typeof value !== "object" || Array.isArray(value)) fail(400, `Invalid ${name}.`);
  if (Object.keys(value).length > 128) fail(400, `${name} has too many fields.`);
  if (new TextEncoder().encode(JSON.stringify(value)).length > maxBytes) fail(413, `${name} exceeds size limit.`);
  return value;
}
export async function readJson(request, maxBytes = MAX_BATCH_BYTES) {
  const declared = Number(request.headers.get("content-length"));
  if (declared > maxBytes) fail(413, "Request exceeds size limit.");
  if (!request.body) fail(400, "JSON body required.");
  const reader = request.body.getReader(); const chunks = []; let total = 0;
  while (true) {
    const { value, done } = await reader.read(); if (done) break;
    total += value.byteLength;
    if (total > maxBytes) { await reader.cancel(); fail(413, "Request exceeds size limit."); }
    chunks.push(value);
  }
  const bytes = new Uint8Array(total); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  try { return objectField(JSON.parse(new TextDecoder().decode(bytes)), "body", maxBytes); }
  catch (e) { if (e instanceof ResearchError) throw e; fail(400, "Invalid JSON."); }
}
export function validateEvent(value, deviceId) {
  objectField(value, "event", MAX_EVENT_BYTES + 1024);
  identifier(value.eventId, "eventId");
  if (value.deviceId !== deviceId) fail(400, "Event deviceId differs from assigned device.");
  const source = sourceName(value.source);
  if (typeof value.type !== "string" || !/^[A-Za-z][A-Za-z0-9_.:-]{0,95}$/.test(value.type)) fail(400, "Invalid event type.");
  if (!Number.isSafeInteger(value.sourceTimestamp) || value.sourceTimestamp < 0 || value.sourceTimestamp > 8640000000000000) fail(400, "Invalid sourceTimestamp.");
  if (!Number.isSafeInteger(value.sequenceNumber) || value.sequenceNumber < 1) fail(400, "Invalid sequenceNumber.");
  const receivedTimestamp = value.receivedTimestamp ?? value.sourceTimestamp;
  if (!Number.isSafeInteger(receivedTimestamp) || receivedTimestamp < 0 || receivedTimestamp > 8640000000000000) fail(400, "Invalid device receivedTimestamp.");
  objectField(value.payload, "payload");
  return { eventId: value.eventId, deviceId, source, type: value.type, sourceTimestamp: value.sourceTimestamp,
    receivedTimestamp, sequenceNumber: value.sequenceNumber, payload: value.payload };
}
export function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonicalJson(value[k])}`).join(",")}}`;
  return JSON.stringify(value);
}
export function timestamp(value, fallback) {
  if (value === null || value === undefined || value === "") return fallback;
  const result = /^-?\d+(?:\.\d+)?$/.test(String(value)) ? Number(value) : Date.parse(value);
  if (!Number.isSafeInteger(result) || result < 0 || result > 8640000000000000) fail(400, "Invalid timestamp (use epoch milliseconds or ISO 8601).");
  return result;
}
export function historyQuery(url) {
  const p = url.searchParams; const from = timestamp(p.get("from"), 0); const to = timestamp(p.get("to"), 8640000000000000);
  if (from > to) fail(400, "from must precede to.");
  const limit = Number(p.get("limit") || 100); if (!Number.isInteger(limit) || limit < 1 || limit > 500) fail(400, "limit must be 1..500.");
  const order = p.get("order") || "asc"; if (!["asc", "desc"].includes(order)) fail(400, "Invalid order.");
  const query = textField(p.get("query") || "", "query"); let cursor = null;
  if (p.get("cursor")) {
    try { cursor = JSON.parse(atob(p.get("cursor"))); } catch (_) { fail(400, "Invalid cursor."); }
    if (!Array.isArray(cursor) || cursor.length !== 2 || !cursor.every(Number.isSafeInteger)) fail(400, "Invalid cursor.");
  }
  return { from, to, limit, order, query, sources: sourceFilter(p.get("source")), cursor };
}
export function validateArtifactCommand(body) {
  if (body.type !== "artifact_get") fail(400, "Only predefined artifact_get commands are supported.");
  identifier(body.artifactId, "artifactId");
  if (!["media", "files"].includes(body.source)) fail(400, "Artifact source must be media or files.");
  if (Object.keys(body).some(key => !["type", "artifactId", "source"].includes(key))) fail(400, "Unknown command parameter.");
  return { type: body.type, artifactId: body.artifactId, source: body.source };
}
export function organizer(actor, env) {
  return actor.labOrganiser === true || String(env.RESEARCH_ORGANIZER_UIDS || "").split(",").map(s => s.trim()).includes(actor.uid);
}
export async function hash(value) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(bytes)].map(v => v.toString(16).padStart(2, "0")).join("");
}
