import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { handleResearch } from "./research.js";
import { ResearchRegistry } from "./research-registry.js";
import { ResearchDevice } from "./research-device.js";
import { MAX_ORIGINAL_BYTES } from "./research-model.js";

// Local policy fixture. Actual Cloudflare SQL/SSE behavior has separate runtime tests.
function storageFixture() {
  const database = new DatabaseSync(":memory:");
  const deferred = [];
  const state = {
    storage: {
      sql: {
        exec(query, ...parameters) {
          const statement = database.prepare(query);
          parameters = parameters.map(value => value instanceof ArrayBuffer ? new Uint8Array(value) : value);
          const rows = statement.columns().length ? statement.all(...parameters) : (statement.run(...parameters), []);
          for (const row of rows) for (const [key, value] of Object.entries(row)) {
            if (value instanceof Uint8Array) row[key] = value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength);
          }
          return { toArray: () => rows, [Symbol.iterator]: () => rows[Symbol.iterator]() };
        },
      },
      transactionSync(callback) {
        database.exec("BEGIN");
        try { const value = callback(); database.exec("COMMIT"); return value; }
        catch (error) { database.exec("ROLLBACK"); throw error; }
      },
      async setAlarm() {},
    },
    waitUntil(promise) { deferred.push(promise); },
  };
  return { state, async close() { await Promise.all(deferred); database.close(); } };
}

function labFixture(environment = {}) {
  const stores = [];
  const deviceObjects = new Map();
  const registryStore = storageFixture(); stores.push(registryStore);
  const env = { ...environment };
  const registry = new ResearchRegistry(registryStore.state, env);
  env.RESEARCH_REGISTRY = { idFromName: name => name, get: () => registry };
  env.RESEARCH_DEVICES = {
    idFromName: name => name,
    get(name) {
      if (!deviceObjects.has(name)) {
        const store = storageFixture(); stores.push(store);
        deviceObjects.set(name, new ResearchDevice(store.state, env));
      }
      return deviceObjects.get(name);
    },
  };
  const actors = {
    organizer: { uid: "organizer", labOrganiser: true },
    owner: { uid: "participant" }, viewer: { uid: "viewer" },
    researcher: { uid: "researcher" }, stranger: { uid: "stranger" },
  };
  async function call(actor, path, { method = "GET", body, rawBody, headers = {}, protocol = "https:" } = {}) {
    const request = new Request(`${protocol}//lab.example/v1/research${path}`, {
      method, headers: { "content-type": "application/json", ...headers },
      ...(rawBody !== undefined ? { body: rawBody } : body !== undefined ? { body: JSON.stringify(body) } : {}),
    });
    // Identity comes exclusively from the authentication result, never internal headers.
    return handleResearch(request, env, async () => actor);
  }
  async function provision() {
    const created = await call(actors.organizer, "/sessions", { method: "POST", body: { label: "Lab" } });
    assert.equal(created.status, 201);
    const session = await created.json();
    const enrolled = await call(actors.owner, "/devices/enroll", { method: "POST", body: {
      deviceId: "LAB-04", enrollmentCode: session.enrollmentCode, consent: true, androidVersion: "15",
    } });
    assert.equal(enrolled.status, 201);
    for (const role of ["viewer", "researcher"]) {
      assert.equal((await call(actors.organizer, `/sessions/${session.labSessionId}/assignments`, {
        method: "POST", body: { uid: actors[role].uid, role },
      })).status, 200);
    }
    return session;
  }
  return { actors, call, provision, device: () => env.RESEARCH_DEVICES.get("LAB-04"), async close() {
    for (const store of stores.reverse()) await store.close();
  } };
}

function event(sequenceNumber, overrides = {}) {
  return { eventId: `event-${sequenceNumber}`, deviceId: "LAB-04", source: "APP", type: "foreground_changed",
    sourceTimestamp: 2000, receivedTimestamp: 2010, sequenceNumber,
    payload: { package: "com.lab.app", foreground: "com.lab.app" }, ...overrides };
}

test("public research boundary ignores forged identity headers and requires TLS", async () => {
  const lab = labFixture();
  try {
    const forged = { "X-Research-Actor": JSON.stringify(lab.actors.organizer) };
    assert.equal((await lab.call(lab.actors.stranger, "/sessions", { method: "POST", body: { label: "No grant" }, headers: forged })).status, 403);
    assert.equal((await lab.call(null, "/sessions", { headers: forged })).status, 401);
    assert.equal((await lab.call(lab.actors.organizer, "/sessions", { protocol: "http:" })).status, 400);
    for (const path of ["/research", "/research/cli.js"]) {
      const page = await handleResearch(new Request(`http://lab.example${path}`), {}, async () => null);
      assert.equal(page.status, 308);
      assert.equal(page.headers.get("location"), `https://lab.example${path}`);
    }
    await lab.provision();
    assert.equal((await lab.call(lab.actors.stranger, "/devices/LAB-04/events", { headers: forged })).status, 403);
    const devices = await (await lab.call(lab.actors.stranger, "/devices", { headers: forged })).json();
    assert.deepEqual(devices.devices, []);
  } finally { await lab.close(); }
});

test("single-use enrollment, role gates and owner disconnect cannot be bypassed", async () => {
  const lab = labFixture();
  try {
    const session = await lab.provision();
    assert.equal((await lab.call(lab.actors.stranger, "/devices/enroll", { method: "POST", body: {
      deviceId: "LAB-05", enrollmentCode: session.enrollmentCode, consent: true,
    } })).status, 403);
    const freshInvitation = await (await lab.call(lab.actors.organizer, `/sessions/${session.labSessionId}/invitations`, {
      method: "POST", body: {},
    })).json();
    assert.equal((await lab.call(lab.actors.stranger, "/devices/enroll", { method: "POST", body: {
      deviceId: "LAB-04", enrollmentCode: freshInvitation.enrollmentCode, consent: true,
    } })).status, 409);
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/status")).status, 200);
    assert.equal((await lab.call(lab.actors.viewer, "/devices/LAB-04/commands", {
      method: "POST", body: { type: "artifact_get", source: "media", artifactId: "media-01" },
    })).status, 403);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/events", {
      method: "POST", body: { events: [event(1)] },
    })).status, 403);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/commands")).status, 403);
    assert.equal((await lab.call(lab.actors.stranger, "/devices/LAB-04/association", { method: "DELETE" })).status, 403);
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/association", { method: "DELETE" })).status, 200);
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/association", { method: "DELETE" })).status, 200);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/events")).status, 403);
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [event(1)] } })).status, 403);
  } finally { await lab.close(); }
});

test("lab assignments do not cross sessions and session revocation closes owner and researcher access", async () => {
  const lab = labFixture();
  try {
    const first = await lab.provision();
    const second = await (await lab.call(lab.actors.organizer, "/sessions", { method: "POST", body: { label: "Other lab" } })).json();
    assert.equal((await lab.call(lab.actors.owner, "/devices/enroll", { method: "POST", body: {
      deviceId: "LAB-OTHER", enrollmentCode: second.enrollmentCode, consent: true,
    } })).status, 201);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-OTHER/status")).status, 403);
    const devices = await (await lab.call(lab.actors.researcher, "/devices")).json();
    assert.deepEqual(devices.devices.map(device => device.deviceId), ["LAB-04"]);
    assert.equal((await lab.call(lab.actors.organizer, `/sessions/${first.labSessionId}`, { method: "DELETE" })).status, 200);
    for (const actor of [lab.actors.owner, lab.actors.researcher]) {
      assert.equal((await lab.call(actor, "/devices/LAB-04/status")).status, 403);
    }
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-OTHER/status")).status, 200);
  } finally { await lab.close(); }
});

test("revoking and regranting a researcher cannot reactivate earlier original requests", async () => {
  const lab = labFixture();
  try {
    const session = await lab.provision();
    const media = event(1, { source: "MEDIA", type: "media_created", payload: { artifactId: "media-01", filename: "image.jpg" } });
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [media] } })).status, 200);
    const requestOriginal = async () => {
      const response = await lab.call(lab.actors.researcher, "/devices/LAB-04/commands", {
        method: "POST", body: { type: "artifact_get", source: "media", artifactId: "media-01" },
      });
      assert.equal(response.status, 201); return response.json();
    };
    const completed = await requestOriginal();
    const pending = await requestOriginal();
    const result = { status: "completed", filename: "image.jpg", mime: "image/jpeg", bytesBase64: "YWJj" };
    assert.equal((await lab.call(lab.actors.owner, `/devices/LAB-04/commands/${completed.commandId}/result`, { method: "POST", body: result })).status, 200);
    assert.equal((await lab.call(lab.actors.viewer, `/devices/LAB-04/commands/${completed.commandId}/download`)).status, 403);
    assert.equal((await lab.call(lab.actors.researcher, `/devices/LAB-04/commands/${completed.commandId}/download`)).status, 200);
    assert.equal((await lab.call(lab.actors.organizer, `/sessions/${session.labSessionId}/assignments/researcher`, { method: "DELETE" })).status, 200);
    assert.equal((await lab.call(lab.actors.organizer, `/sessions/${session.labSessionId}/assignments`, {
      method: "POST", body: { uid: "researcher", role: "researcher" },
    })).status, 200);
    assert.equal((await lab.call(lab.actors.researcher, `/devices/LAB-04/commands/${completed.commandId}/download`)).status, 403);
    assert.equal((await lab.call(lab.actors.owner, `/devices/LAB-04/commands/${pending.commandId}/result`, { method: "POST", body: result })).status, 403);
    const polled = await (await lab.call(lab.actors.owner, "/devices/LAB-04/commands")).json();
    assert.deepEqual(polled.commands, []);
  } finally { await lab.close(); }
});

test("pending enrollment revocation blocks delayed acceptance without blocking another participant", async () => {
  const lab = labFixture();
  try {
    const session = await lab.provision();
    const invitation = await (await lab.call(lab.actors.organizer, `/sessions/${session.labSessionId}/invitations`, {
      method: "POST", body: {},
    })).json();
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-FUTURE/association", { method: "DELETE" })).status, 200);
    const enroll = deviceId => lab.call(lab.actors.owner, "/devices/enroll", { method: "POST", body: {
      deviceId, enrollmentCode: invitation.enrollmentCode, consent: true,
    } });
    assert.equal((await enroll("LAB-FUTURE")).status, 409);
    // Unknown-device cleanup is scoped to its authenticated UID, so it cannot reserve
    // arbitrary IDs against another participant before that participant enrolls.
    assert.equal((await lab.call(lab.actors.stranger, "/devices/LAB-NEW/association", { method: "DELETE" })).status, 200);
    assert.equal((await enroll("LAB-NEW")).status, 201);
  } finally { await lab.close(); }
});

test("the journal keeps immutable evidence, distinct clocks and ordered observations during clock rollback", async () => {
  const lab = labFixture();
  try {
    await lab.provision();
    const first = event(1);
    const upload = await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [first] } });
    assert.equal(upload.status, 200); const originalAck = await upload.json();
    const replay = await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [first] } });
    assert.equal(replay.status, 200); const replayAck = await replay.json();
    assert.deepEqual(replayAck.duplicateEventIds, [first.eventId]);
    assert.equal(replayAck.eventReceivedTimestamps[first.eventId], originalAck.eventReceivedTimestamps[first.eventId]);
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [{ ...first, payload: { altered: true } }] } })).status, 409);
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [event(2), event(4)] } })).status, 409);
    assert.equal(lab.device().get("lastSequence"), 1);
    const left = event(2, { sourceTimestamp: 1000, receivedTimestamp: 1010, type: "foreground_left", payload: { package: "com.lab.app", foreground: null } });
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [left] } })).status, 200);
    const status = await (await lab.call(lab.actors.viewer, "/devices/LAB-04/status")).json();
    assert.equal(status.state.foreground, null);
    assert.equal(status.lastSequenceNumber, 2);
    const page = await (await lab.call(lab.actors.viewer, "/devices/LAB-04/events?order=asc&limit=1")).json();
    assert.equal(page.events[0].eventId, left.eventId); assert.ok(page.nextCursor);
    const next = await (await lab.call(lab.actors.viewer, `/devices/LAB-04/events?order=asc&limit=1&cursor=${encodeURIComponent(page.nextCursor)}`)).json();
    assert.equal(next.events[0].eventId, first.eventId);
    assert.equal(next.events[0].sourceTimestamp, 2000);
    assert.equal(next.events[0].receivedTimestamp, 2010);
    assert.equal(next.events[0].serverReceivedTimestamp, originalAck.eventReceivedTimestamps[first.eventId]);
    assert.notEqual(next.events[0].serverReceivedTimestamp, next.events[0].receivedTimestamp);
    assert.equal(next.events[0].syncState, "synced");
    const unavailable = event(3, { type: "source_unavailable", payload: { reason: "Usage Access revoked" } });
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [unavailable] } })).status, 200);
    const unavailableState = await (await lab.call(lab.actors.viewer, "/devices/LAB-04/status")).json();
    assert.equal(unavailableState.currentState.APP.available, false);
    assert.equal(unavailableState.state.foreground, null);
    assert.equal(unavailableState.currentState.APP.fieldReferences.foreground.eventId, left.eventId);
  } finally { await lab.close(); }
});

test("original copies expire and the alarm removes every stored chunk", async context => {
  let now = 1_800_000_000_000;
  context.mock.method(Date, "now", () => now);
  const lab = labFixture();
  try {
    await lab.provision();
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [
      event(1, { source: "MEDIA", type: "media_created", payload: { artifactId: "original-01", filename: "original.jpg" } }),
    ] } })).status, 200);
    const original = await (await lab.call(lab.actors.researcher, "/devices/LAB-04/commands", {
      method: "POST", body: { type: "artifact_get", source: "media", artifactId: "original-01" },
    })).json();
    const content = Buffer.alloc(1024 * 1024 + 1, 42);
    assert.equal((await lab.call(lab.actors.owner, `/devices/LAB-04/commands/${original.commandId}/result`, {
      method: "POST", body: { status: "completed", filename: "original.jpg", mime: "image/jpeg", bytesBase64: content.toString("base64") },
    })).status, 200);
    assert.ok(lab.device().one("SELECT COUNT(*) AS n FROM artifact_chunks").n > 1);
    const before = await lab.call(lab.actors.researcher, `/devices/LAB-04/commands/${original.commandId}/download`);
    assert.equal(before.status, 200); assert.deepEqual(Buffer.from(await before.arrayBuffer()), content);
    now = original.expiresAt;
    await lab.device().alarm();
    assert.equal(lab.device().one("SELECT COUNT(*) AS n FROM artifact_chunks").n, 0);
    assert.equal(lab.device().one("SELECT result_json FROM commands WHERE command_id=?", original.commandId).result_json, null);
    assert.equal((await lab.call(lab.actors.researcher, `/devices/LAB-04/commands/${original.commandId}/download`)).status, 410);
  } finally { await lab.close(); }
});

test("malformed input, arbitrary command parameters and oversized originals are rejected without journal writes", async () => {
  const lab = labFixture();
  try {
    await lab.provision();
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", rawBody: "{invalid" })).status, 400);
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [event(1, { payload: { text: "x".repeat(32769) } })] } })).status, 413);
    assert.equal(lab.device().get("lastSequence", 0), 0);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/commands", {
      method: "POST", body: { type: "artifact_get", source: "media", artifactId: "original-01", shell: "id" },
    })).status, 400);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/commands", {
      method: "POST", body: { type: "shell", command: "id" },
    })).status, 400);
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [
      event(1, { source: "MEDIA", type: "media_created", payload: { artifactId: "original-01", filename: "image.jpg" } }),
    ] } })).status, 200);
    const original = await (await lab.call(lab.actors.researcher, "/devices/LAB-04/commands", {
      method: "POST", body: { type: "artifact_get", source: "media", artifactId: "original-01" },
    })).json();
    for (const bytesBase64 of ["!", "A".repeat(Math.ceil(MAX_ORIGINAL_BYTES / 3) * 4 + 4)]) {
      assert.equal((await lab.call(lab.actors.owner, `/devices/LAB-04/commands/${original.commandId}/result`, {
        method: "POST", body: { status: "completed", filename: "image.jpg", mime: "image/jpeg", bytesBase64 },
      })).status, 413);
    }
    assert.equal(lab.device().one("SELECT COUNT(*) AS n FROM artifact_chunks").n, 0);
    assert.equal(lab.device().one("SELECT status FROM commands WHERE command_id=?", original.commandId).status, "pending");
  } finally { await lab.close(); }
});

test("read limits are isolated by authenticated account and reset after their bounded window", async context => {
  let now = 1_800_000_000_000;
  context.mock.method(Date, "now", () => now);
  const lab = labFixture();
  try {
    await lab.provision();
    for (let request = 0; request < 180; request++) {
      assert.equal((await lab.call(lab.actors.viewer, "/devices/LAB-04/status")).status, 200);
    }
    assert.equal((await lab.call(lab.actors.viewer, "/devices/LAB-04/status")).status, 429);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/status")).status, 200);
    now += 60_000;
    assert.equal((await lab.call(lab.actors.viewer, "/devices/LAB-04/status")).status, 200);
  } finally { await lab.close(); }
});

test("merged device state traces each value to its original event and exposes source consent separately", async () => {
  const lab = labFixture();
  try {
    await lab.provision();
    const observations = [
      event(1, { source: "DEVICE", type: "battery_changed", payload: { battery: 61 } }),
      event(2, { source: "DEVICE", type: "screen_changed", payload: { screen: "ON" } }),
      event(3, { source: "DEVICE", type: "heartbeat", payload: { observationActive: true } }),
      event(4, { source: "DEVICE", type: "source_consent_changed", payload: { source: "APP", enabled: false } }),
    ];
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: observations } })).status, 200);
    const state = await (await lab.call(lab.actors.researcher, "/devices/LAB-04/status")).json();
    assert.equal(state.state.battery, 61); assert.equal(state.state.screen, "ON");
    assert.equal(state.currentState.DEVICE.fieldReferences.battery.eventId, "event-1");
    assert.equal(state.currentState.DEVICE.fieldReferences.screen.eventId, "event-2");
    assert.equal(state.currentState.DEVICE.fieldReferences.observationActive.eventId, "event-3");
    assert.equal(state.currentState.APP.available, false);
    assert.equal(state.currentState.APP.consentEventId, "event-4");
  } finally { await lab.close(); }
});

test("an earlier slow authorization cannot reorder later live journal delivery", async () => {
  const lab = labFixture();
  let release;
  try {
    await lab.provision();
    const device = lab.device(); const delivered = []; let authorizationCount = 0;
    const blocked = new Promise(resolve => { release = resolve; });
    device.authorize = async () => { authorizationCount++; if (authorizationCount === 1) await blocked; return {}; };
    device.subscribers.add({ actor: { uid: "test-subscriber" }, deviceId: "LAB-04", replaying: false,
      sendEvent(observation) { delivered.push(observation.sequenceNumber); }, close() { assert.fail("Authorized subscriber must remain active"); } });
    device.ingest({ events: [event(1)] }, "LAB-04");
    device.ingest({ events: [event(2)] }, "LAB-04");
    await Promise.resolve();
    assert.equal(authorizationCount, 1); assert.deepEqual(delivered, []);
    release(); await device.delivery;
    assert.deepEqual(delivered, [1, 2]);
  } finally { release?.(); await lab.close(); }
});

test("changing device metadata cannot grow the current state indefinitely or erase journal evidence", async () => {
  const lab = labFixture();
  try {
    await lab.provision();
    const observations = Array.from({ length: 100 }, (_, index) => event(index + 1, {
      source: "DEVICE", type: "metadata_observed", payload: {
        model: "Laboratory device", ...Object.fromEntries(Array.from({ length: 20 }, (_, field) => [
          `metadata_${index}_${field}`, "x".repeat(128),
        ])),
      },
    }));
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: observations } })).status, 200);
    const current = lab.device().get("currentState");
    assert.equal(current.DEVICE.model, "Laboratory device");
    assert.equal(Object.hasOwn(current.DEVICE, "metadata_0_0"), false);
    assert.equal(current.DEVICE.metadata_99_0, "x".repeat(128));
    assert.ok(Buffer.byteLength(JSON.stringify(current)) < 64 * 1024);
    assert.equal(lab.device().one("SELECT COUNT(*) AS n FROM events").n, 100);
    const excessive = Object.fromEntries(Array.from({ length: 129 }, (_, field) => [`field_${field}`, 1]));
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", {
      method: "POST", body: { events: [event(101, { source: "DEVICE", payload: excessive })] },
    })).status, 400);
    assert.equal(lab.device().get("lastSequence"), 100);
  } finally { await lab.close(); }
});

test("AI planning cannot retain a revoked grant or a token that expires during the model request", async context => {
  let now = 1_800_000_000_000;
  context.mock.method(Date, "now", () => now);
  for (const change of ["regrant", "token-expiry"]) {
    let signalStarted, release;
    const started = new Promise(resolve => { signalStarted = resolve; });
    const lab = labFixture({ AI: { run() {
      signalStarted(); return new Promise(resolve => { release = () => resolve({ response: JSON.stringify({ mode: "search", query: "meeting", sources: ["APP"] }) }); });
    } } });
    let pending;
    try {
      const session = await lab.provision();
      const actor = change === "token-expiry" ? { ...lab.actors.researcher, tokenExpiresAt: now + 1000 } : lab.actors.researcher;
      pending = lab.call(actor, "/devices/LAB-04/evidence/search", { method: "POST", body: { query: "Explain investigation meeting observations" } });
      await started;
      if (change === "regrant") {
        assert.equal((await lab.call(lab.actors.organizer, `/sessions/${session.labSessionId}/assignments/researcher`, { method: "DELETE" })).status, 200);
        assert.equal((await lab.call(lab.actors.organizer, `/sessions/${session.labSessionId}/assignments`, {
          method: "POST", body: { uid: "researcher", role: "researcher" },
        })).status, 200);
      } else now += 1000;
      release(); const denied = await pending;
      assert.equal(denied.status, change === "regrant" ? 403 : 401);
      const body = await denied.json(); assert.equal(body.citations, undefined);
      assert.equal(lab.device().one("SELECT COUNT(*) AS n FROM evidence_queries").n, 0);
    } finally { release?.(); if (pending) await pending; await lab.close(); }
  }
});

test("evidence continuations pin their model plan, history snapshot, account and assignment until expiry", async context => {
  let now = 1_800_000_000_000; let planningCalls = 0;
  context.mock.method(Date, "now", () => now);
  const lab = labFixture({ AI: { async run() {
    planningCalls++; return { response: JSON.stringify({ mode: "search", query: "meeting", sources: ["APP"] }) };
  } } });
  try {
    const session = await lab.provision();
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [
      event(1, { sourceTimestamp: now - 5000, payload: { foreground: "meeting.first" } }),
      event(2, { sourceTimestamp: now - 4000, payload: { foreground: "meeting.second" } }),
    ] } })).status, 200);
    const question = "Explain investigation meeting observations";
    const firstResponse = await lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body: { query: question, limit: 1 } });
    assert.equal(firstResponse.status, 200); const first = await firstResponse.json();
    assert.equal(first.planner.provider, "workers-ai"); assert.equal(first.generated, false);
    assert.equal(first.citations[0].eventId, "event-1"); assert.ok(first.coverage.nextCursor);
    assert.equal(first.coverage.snapshotSequenceNumber, 2); assert.equal(planningCalls, 1);
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [
      event(3, { sourceTimestamp: now - 4500, payload: { foreground: "meeting.newly.synced" } }),
    ] } })).status, 200);
    const continuation = { query: question, limit: 1, planId: first.planId, cursor: first.coverage.nextCursor };
    const secondResponse = await lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body: continuation });
    assert.equal(secondResponse.status, 200); const second = await secondResponse.json();
    assert.equal(second.planId, first.planId); assert.deepEqual(second.plan, first.plan);
    assert.equal(second.citations[0].eventId, "event-2"); assert.equal(second.coverage.nextCursor, null);
    assert.equal(planningCalls, 1);
    assert.equal((await lab.call(lab.actors.viewer, "/devices/LAB-04/evidence/search", { method: "POST", body: continuation })).status, 403);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body: { ...continuation, source: "MEDIA" } })).status, 400);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body: { ...continuation, query: "Different question" } })).status, 403);
    assert.equal((await lab.call(lab.actors.organizer, `/sessions/${session.labSessionId}/assignments/researcher`, { method: "DELETE" })).status, 200);
    assert.equal((await lab.call(lab.actors.organizer, `/sessions/${session.labSessionId}/assignments`, {
      method: "POST", body: { uid: "researcher", role: "researcher" },
    })).status, 200);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body: continuation })).status, 403);
    now += 15 * 60_000;
    await lab.device().alarm();
    assert.equal(lab.device().one("SELECT COUNT(*) AS n FROM evidence_queries").n, 0);
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body: continuation })).status, 410);
  } finally { await lab.close(); }
});

test("AI compare counts obey the same source and literal filters as the pinned evidence snapshot", async context => {
  let now = 1_800_000_000_000;
  context.mock.method(Date, "now", () => now);
  const lab = labFixture({ AI: { async run() { return { response: JSON.stringify({
    mode: "compare", query: "meeting", sources: ["APP"], from: now - 86_400_000, to: now,
  }) }; } } });
  try {
    await lab.provision();
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [
      event(1, { sourceTimestamp: now - 4000, payload: { foreground: "meeting.first" } }),
      event(2, { sourceTimestamp: now - 3000, payload: { foreground: "meeting.second" } }),
      event(3, { sourceTimestamp: now - 2000, source: "NOTIFICATION", type: "notification_posted", payload: { title: "meeting" } }),
      event(4, { sourceTimestamp: now - 1000, payload: { foreground: "unrelated" } }),
    ] } })).status, 200);
    const question = "Analyze the investigation meeting pattern";
    const first = await (await lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body: { query: question, limit: 1 } })).json();
    assert.deepEqual(first.periodCounts, { APP: 2 }); assert.deepEqual(first.counts, { APP: 1 });
    assert.equal((await lab.call(lab.actors.owner, "/devices/LAB-04/events", { method: "POST", body: { events: [
      event(5, { sourceTimestamp: now - 1500, payload: { foreground: "meeting.new" } }),
    ] } })).status, 200);
    const second = await (await lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body: {
      query: question, limit: 1, planId: first.planId, cursor: first.coverage.nextCursor,
    } })).json();
    assert.deepEqual(second.periodCounts, { APP: 2 }); assert.equal(second.coverage.snapshotSequenceNumber, 4);
    assert.equal(second.citations[0].eventId, "event-2");
    assert.deepEqual(second.currentState, first.currentState);
  } finally { await lab.close(); }
});

test("assignment changes during evidence alarm scheduling cannot expose a stored query", async () => {
  const lab = labFixture(); let release, signalStarted, pending;
  const started = new Promise(resolve => { signalStarted = resolve; });
  try {
    const session = await lab.provision();
    lab.device().state.storage.setAlarm = () => {
      signalStarted(); return new Promise(resolve => { release = resolve; });
    };
    pending = lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body: { query: "meeting" } });
    await started;
    assert.equal((await lab.call(lab.actors.organizer, `/sessions/${session.labSessionId}/assignments/researcher`, { method: "DELETE" })).status, 200);
    assert.equal((await lab.call(lab.actors.organizer, `/sessions/${session.labSessionId}/assignments`, {
      method: "POST", body: { uid: "researcher", role: "researcher" },
    })).status, 200);
    release(); const response = await pending;
    assert.equal(response.status, 403); assert.equal((await response.json()).citations, undefined);
  } finally { release?.(); if (pending) await pending; await lab.close(); }
});

test("AI budget denial prevents a model invocation rather than downgrading to a free fallback", async context => {
  context.mock.method(Date, "now", () => 1_800_000_000_000);
  let modelCalls = 0;
  const lab = labFixture({ AI: { async run() {
    modelCalls++; return { response: JSON.stringify({ mode: "search", query: "meeting", sources: [] }) };
  } } });
  try {
    await lab.provision();
    const body = { query: "Explain investigation meeting observations" };
    for (let request = 0; request < 20; request++) {
      assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body })).status, 200);
    }
    assert.equal((await lab.call(lab.actors.researcher, "/devices/LAB-04/evidence/search", { method: "POST", body })).status, 429);
    assert.equal(modelCalls, 20);
  } finally { await lab.close(); }
});
