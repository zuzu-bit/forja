import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { Miniflare } from "miniflare";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

let runtime; let bindings; let registry; let persist;
const owner = { uid: "runtime-owner", tokenExpiresAt: Date.now() + 3600000 };
const organizer = { uid: "runtime-organizer", labOrganiser: true, tokenExpiresAt: Date.now() + 3600000 };
const researcher = { uid: "runtime-investigator", tokenExpiresAt: Date.now() + 3600000 };
function request(stub, method, path, actor, body) {
  return stub.fetch(`https://research.internal${path}`, { method,
    headers: { "X-Research-Actor": JSON.stringify(actor), "content-type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
}
async function lab(id) {
  const created = await request(registry, "POST", "/sessions", organizer, { label: "Runtime authorized lab" });
  assert.equal(created.status, 201); const session = await created.json();
  const enrolled = await request(registry, "POST", "/devices/enroll", owner, { deviceId: id, enrollmentCode: session.enrollmentCode, consent: true, label: id, androidVersion: "15" });
  assert.equal(enrolled.status, 201); await request(registry, "POST", `/sessions/${session.labSessionId}/assignments`, organizer, { uid: researcher.uid, role: "researcher" });
  return { session, device: bindings.RESEARCH_DEVICES.get(bindings.RESEARCH_DEVICES.idFromName(id)) };
}
function event(deviceId, sequenceNumber, source = "APP", payload = { foreground: "com.youtube" }, sourceTimestamp = 1000 * sequenceNumber) {
  return { eventId: `${deviceId}-event-${sequenceNumber}`, deviceId, source, type: source === "APP" ? "foreground_changed" : "media_created", sourceTimestamp, receivedTimestamp: sourceTimestamp + 20, sequenceNumber, payload, syncState: "pending" };
}
async function start() {
  runtime = new Miniflare({ modules: true, scriptPath: new URL("./research-runtime-fixture.js", import.meta.url).pathname, durableObjectsPersist: persist,
    modulesRules: [{ type: "ESModule", include: ["**/*.js"], fallthrough: true }],
    compatibilityDate: "2025-06-01", durableObjects: { RESEARCH_REGISTRY: { className: "ResearchRegistry", useSQLite: true }, RESEARCH_DEVICES: { className: "ResearchDevice", useSQLite: true } } });
  bindings = await runtime.getBindings(); registry = bindings.RESEARCH_REGISTRY.get(bindings.RESEARCH_REGISTRY.idFromName("registry"));
}
before(async () => { persist = await mkdtemp(join(tmpdir(), "forja-research-runtime-")); await start(); });
after(async () => { await runtime?.dispose(); if (persist) await rm(persist, { recursive: true, force: true }); });

test("Workers SQLite commits ordered journal, exact dedup acknowledgements, clock rollback, and pagination", async () => {
  const id = "runtime-journal"; const { device } = await lab(id); const first = event(id, 1); const second = event(id, 2, "APP", { foreground: "com.camera" }, 500);
  const uploaded = await request(device, "POST", `/devices/${id}/events`, owner, { events: [first, second] }); assert.equal(uploaded.status, 200);
  const ack = await uploaded.json(); assert.deepEqual(ack.acceptedEventIds, [first.eventId, second.eventId]);
  const replay = await request(device, "POST", `/devices/${id}/events`, owner, { events: [first, second] }); assert.equal(replay.status, 200);
  const duplicate = await replay.json(); assert.deepEqual(duplicate.duplicateEventIds, ack.acceptedEventIds); assert.deepEqual(duplicate.eventReceivedTimestamps, ack.eventReceivedTimestamps);
  const conflict = await request(device, "POST", `/devices/${id}/events`, owner, { events: [{ ...first, payload: { foreground: "forged" } }] }); assert.equal(conflict.status, 409);
  const gap = await request(device, "POST", `/devices/${id}/events`, owner, { events: [event(id, 4)] }); assert.equal(gap.status, 409); assert.equal((await gap.json()).expectedSequenceNumber, 3);
  const page = await request(device, "GET", `/devices/${id}/events?limit=1`, researcher); const history = await page.json(); assert.equal(history.events[0].eventId, second.eventId); assert.ok(history.nextCursor);
  const next = await request(device, "GET", `/devices/${id}/events?limit=1&cursor=${encodeURIComponent(history.nextCursor)}`, researcher); assert.equal((await next.json()).events[0].eventId, first.eventId);
  const status = await request(device, "GET", `/devices/${id}/status`, researcher); assert.equal((await status.json()).state.foreground, "com.camera");
});

test("Workers live SSE replays durable sequence pages, independent of source clock and filter", async () => {
  const id = "runtime-live"; const { device } = await lab(id);
  for (let start = 1; start <= 240; start += 80) {
    const events = Array.from({ length: 80 }, (_, index) => event(id, start + index, (start + index) % 2 ? "APP" : "NETWORK", { foreground: "com.range", connectionType: "Wi-Fi" }, 2000 - start - index));
    const response = await request(device, "POST", `/devices/${id}/events`, owner, { events }); assert.equal(response.status, 200);
  }
  const response = await request(device, "GET", `/devices/${id}/stream?afterSequence=0&source=apps`, researcher);
  assert.equal(response.status, 200); assert.equal(response.headers.get("content-type"), "text/event-stream");
  const text = await response.text(); const ids = [...text.matchAll(/^id: (\d+)$/gm)].map(match => Number(match[1]));
  assert.equal(ids.length, 200); assert.deepEqual(ids, Array.from({ length: 200 }, (_, index) => index + 1)); assert.ok(text.includes('"reason":"replay_page"'));
  assert.ok(text.includes("event: cursor")); assert.ok(text.includes("event: device-event"));
  // The renewed stream starts strictly after the last delivered cursor.
  const resumed = await request(device, "GET", `/devices/${id}/stream?afterSequence=200`, researcher); const reader = resumed.body.getReader();
  let buffer = ""; while (!buffer.includes('"sequenceNumber":240')) { const chunk = await reader.read(); assert.equal(chunk.done, false); buffer += new TextDecoder().decode(chunk.value); }
  assert.ok(buffer.includes('"sequenceNumber":201')); assert.ok(!buffer.includes('"sequenceNumber":200,')); await reader.cancel();
});

test("Workers on-demand originals are requester scoped, split into bounded SQLite rows and expire", async () => {
  const id = "runtime-artifact"; const { session, device } = await lab(id);
  const metadata = event(id, 1, "FILE", { artifactId: "lab-pdf", filename: "meeting.pdf", mime: "application/pdf", size: 2100000 });
  assert.equal((await request(device, "POST", `/devices/${id}/events`, owner, { events: [metadata] })).status, 200);
  const files = await request(device, "GET", `/devices/${id}/artifacts?source=files&query=*.pdf`, researcher); assert.equal((await files.json()).artifacts.length, 1);
  const created = await request(device, "POST", `/devices/${id}/commands`, researcher, { type: "artifact_get", artifactId: "lab-pdf", source: "files" }); assert.equal(created.status, 201); const command = await created.json();
  const commands = await request(device, "GET", `/devices/${id}/commands`, owner); const polled = await commands.json(); assert.equal(polled.commands[0].commandId, command.commandId); assert.ok(polled.serverTimestamp);
  const bytes = Buffer.alloc(2100000, 71); // Bigger than a single SQLite row; exercises chunk storage.
  const upload = await request(device, "POST", `/devices/${id}/commands/${command.commandId}/result`, owner, { status: "completed", mime: "application/pdf", filename: "meeting.pdf", bytesBase64: bytes.toString("base64") }); assert.equal(upload.status, 200);
  const download = await request(device, "GET", `/devices/${id}/commands/${command.commandId}/download`, researcher); assert.equal(download.status, 200); assert.deepEqual(Buffer.from(await download.arrayBuffer()), bytes);
  const evidence = await request(device, "POST", `/devices/${id}/evidence/search`, researcher, { query: "Găsește toate artefactele care menționează meeting." }); const found = await evidence.json(); assert.equal(found.generated, false); assert.equal(found.citations[0].eventId, metadata.eventId);
  // Revocation followed by a new grant must not revive this old original request.
  await request(registry, "DELETE", `/sessions/${session.labSessionId}/assignments/${researcher.uid}`, organizer);
  await request(registry, "POST", `/sessions/${session.labSessionId}/assignments`, organizer, { uid: researcher.uid, role: "researcher" });
  const revoked = await request(device, "GET", `/devices/${id}/commands/${command.commandId}/download`, researcher); assert.equal(revoked.status, 403);
  const audit = await request(device, "GET", `/devices/${id}/audit`, researcher); const actions = (await audit.json()).actions; assert.ok(actions.some(action => action.action === "artifact_requested"));
  const history = await request(device, "GET", `/devices/${id}/events`, researcher); assert.equal((await history.json()).events.length, 1); // Investigator actions never enter the device journal.
});

test("Workers live revocation closes prior grant before another durable event is disclosed", async () => {
  const id = "runtime-revocation"; const { session, device } = await lab(id);
  assert.equal((await request(device, "POST", `/devices/${id}/events`, owner, { events: [event(id, 1)] })).status, 200);
  const response = await request(device, "GET", `/devices/${id}/stream?afterSequence=0`, researcher); const reader = response.body.getReader(); let text = "";
  while (!text.includes('"sequenceNumber":1')) { const chunk = await reader.read(); assert.equal(chunk.done, false); text += new TextDecoder().decode(chunk.value); }
  await request(registry, "DELETE", `/sessions/${session.labSessionId}/assignments/${researcher.uid}`, organizer);
  // Regrant before the next callback: an old lease must still terminate on revision mismatch.
  await request(registry, "POST", `/sessions/${session.labSessionId}/assignments`, organizer, { uid: researcher.uid, role: "researcher" });
  assert.equal((await request(device, "POST", `/devices/${id}/events`, owner, { events: [event(id, 2)] })).status, 200);
  while (true) { const chunk = await reader.read(); if (chunk.done) break; text += new TextDecoder().decode(chunk.value); }
  assert.ok(text.includes('"reason":"revoked"')); assert.ok(!text.includes('"sequenceNumber":2'));
  const history = await request(device, "GET", `/devices/${id}/events`, researcher); assert.equal((await history.json()).events.length, 2);
});

test("Workers registry and journal survive a full runtime restart without duplicate acceptance", async () => {
  await runtime.dispose(); await start();
  const device = bindings.RESEARCH_DEVICES.get(bindings.RESEARCH_DEVICES.idFromName("runtime-journal"));
  const history = await request(device, "GET", "/devices/runtime-journal/events", researcher); assert.equal(history.status, 200); assert.equal((await history.json()).events.length, 2);
  const duplicate = await request(device, "POST", "/devices/runtime-journal/events", owner, { events: [event("runtime-journal", 1)] }); assert.equal(duplicate.status, 200);
  const ack = await duplicate.json(); assert.equal(ack.acceptedEventIds.length, 0); assert.deepEqual(ack.duplicateEventIds, ["runtime-journal-event-1"]);
});
