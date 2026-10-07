import test from "node:test";
import assert from "node:assert/strict";
import { parseResearchCommand, parseEventStreamFrame, resolveTimeRange, researchCliModule, summarizeDeviceEvent, formatEvidencePlanner, describeObservationState } from "./research-cli.js";

const now = Date.parse("2026-10-07T22:30:00Z");
test("predefined commands and source aliases never resolve to a shell", () => {
  assert.deepEqual(parseResearchCommand("devices"), { action: "devices" });
  assert.deepEqual(parseResearchCommand("use LAB-S23-04"), { action: "use", deviceId: "LAB-S23-04" });
  assert.deepEqual(parseResearchCommand("watch notifications"), { action: "watch", source: "NOTIFICATION" });
  assert.deepEqual(parseResearchCommand("apps live"), { action: "state", source: "APP" });
  assert.deepEqual(parseResearchCommand("stop watch"), { action: "stop" });
  for (const command of ["shell id", "devices; id", "watch all | cat", "files get $(id)", "use ../lab", "watch all\nstatus", "use lab && id", "apps live unexpected", "watch bogus", "watch constructor", "constructor live", "watch __proto__", "status --last 2h", "watch all\u0000", 'notifications search "a;id"']) {
    assert.throws(() => parseResearchCommand(command), undefined, command);
  }
});
test("quoted evidence terms and artifact filenames remain literal", () => {
  assert.deepEqual(parseResearchCommand('notifications search "Bogdan Pop"'), { action: "events", source: "NOTIFICATION", query: "Bogdan Pop" });
  assert.deepEqual(parseResearchCommand('files search "*.pdf"'), { action: "artifacts", source: "FILE", query: "*.pdf" });
  assert.deepEqual(parseResearchCommand('files list'), { action: "artifacts", source: "FILE" });
  assert.deepEqual(parseResearchCommand('media metadata "IMG 2041.jpg"'), { action: "artifactMetadata", source: "MEDIA", artifact: "IMG 2041.jpg" });
  assert.deepEqual(parseResearchCommand('evidence search "YouTube"'), { action: "evidence", query: "YouTube" });
  assert.throws(() => parseResearchCommand('media get ../../private'));
  assert.throws(() => parseResearchCommand('evidence search --unvalidated'));
  assert.throws(() => parseResearchCommand('notifications search "open'));
  assert.throws(() => parseResearchCommand("x".repeat(513)));
  assert.throws(() => parseResearchCommand('notifications search "' + 'x'.repeat(161) + '"'));
});
test("app summaries distinguish foreground exit and unavailable observation", () => {
  assert.equal(summarizeDeviceEvent({ source: "APP", type: "foreground_changed", payload: { package: "com.whatsapp", foreground: "com.whatsapp" } }), "com.whatsapp foreground");
  assert.equal(summarizeDeviceEvent({ source: "APP", type: "foreground_left", payload: { package: "com.whatsapp", foreground: null } }), "com.whatsapp foreground left");
  assert.match(summarizeDeviceEvent({ source: "APP", type: "source_unavailable", payload: { reason: "usage access absent" } }), /Observare indisponibilă/u);
});
test("planner metadata distinguishes validated AI from actual fallback without using generated narrative", () => {
  assert.equal(formatEvidencePlanner({ provider: "workers-ai", status: "validated" }), "AI · plan validat");
  assert.equal(formatEvidencePlanner({ provider: "deterministic", status: "recognized" }), "Reguli · întrebare recunoscută");
  assert.equal(formatEvidencePlanner({ provider: "deterministic", status: "unavailable" }), "Reguli · AI indisponibil");
  assert.equal(formatEvidencePlanner({ provider: "deterministic", status: "timeout" }), "Reguli · timpul de planificare AI a expirat");
  assert.equal(formatEvidencePlanner({ provider: "deterministic", status: "invalid_model_plan" }), "Reguli · planul AI nu a trecut validarea");
  assert.equal(formatEvidencePlanner({ provider: "deterministic", status: "validated" }), "Reguli · plan determinist");
  assert.equal(formatEvidencePlanner({ status: "constructor", model: '<img src=x onerror="alert(1)">' }), "Reguli · plan determinist");
});
test("online presence does not imply observation and offline snapshots retain last reported flags", () => {
  assert.deepEqual(describeObservationState({ online: true, state: { observationActive: false } }), { status: "PAUSED", stale: true, notice: "Observare PAUSED · se afișează ultimele observații sincronizate.", badge: "OBSERVATION PAUSED" });
  const offline = { online: false, state: { observationActive: true, foreground: "com.whatsapp" } };
  assert.equal(describeObservationState(offline).status, "ACTIVE");
  assert.equal(describeObservationState(offline).badge, "LAST REPORT ACTIVE");
  assert.equal(describeObservationState(offline).stale, true);
  assert.match(describeObservationState(offline).notice, /OFFLINE/u);
  assert.equal(offline.state.foreground, "com.whatsapp");
  assert.equal(describeObservationState({ online: true, state: {} }).status, "UNKNOWN");
  assert.equal(describeObservationState({ online: true, state: { observationActive: true } }).stale, false);
});
test("timeline ranges have explicit UTC clock and valid calendar dates", () => {
  assert.deepEqual(parseResearchCommand("timeline --last 2h", now), { action: "events", source: "all", from: now - 7200000, to: now });
  assert.deepEqual(parseResearchCommand("timeline --date 2026-10-04", now), { action: "events", source: "all", from: Date.parse("2026-10-04T00:00:00Z"), to: Date.parse("2026-10-04T23:59:59.999Z") });
  assert.deepEqual(parseResearchCommand("location history --from 18:00 --to 22:00", now), { action: "events", source: "LOCATION", from: Date.parse("2026-10-07T18:00:00Z"), to: Date.parse("2026-10-07T22:00:00Z") });
  assert.deepEqual(parseResearchCommand("around 19:06 --window 10m --date 2026-10-04", now), { action: "events", source: "all", from: Date.parse("2026-10-04T18:56:00Z"), to: Date.parse("2026-10-04T19:16:00Z") });
  for (const command of ["timeline --date 2026-02-30", "timeline --date 2026-13-01", "timeline --from 24:00 --to 25:00", "timeline --from 18:00", "timeline --from 22:00 --to 18:00", "timeline --last 2h --date 2026-10-04", "timeline --last 31d", "timeline --last 0m", "timeline --last 1h --last 2h", "around 19:06", "timeline --date --today", "timeline --oops 2h"]) {
    assert.throws(() => parseResearchCommand(command, now), undefined, command);
  }
  assert.deepEqual(resolveTimeRange({ today: true }, now), { from: Date.parse("2026-10-07T00:00:00Z"), to: Date.parse("2026-10-07T23:59:59.999Z") });
});
test("SSE preserves multiline JSON, CRLF, ID, comments and Unicode", () => {
  assert.deepEqual(parseEventStreamFrame(': keepalive\r\nevent: device-event\r\nid: 1044\r\ndata: {"text":\r\ndata: "Bogdan, ai ajuns?"}\r\n'), { event: "device-event", id: "1044", data: '{"text":\n"Bogdan, ai ajuns?"}' });
  assert.equal(parseEventStreamFrame("id: bad\u0000id\ndata: ok").id, undefined);
});
test("served browser parser uses the same tested grammar", async () => {
  const response = researchCliModule();
  assert.equal(response.headers.get("content-type"), "text/javascript; charset=utf-8");
  assert.equal(response.headers.get("cache-control"), "no-store");
  const text = await response.text();
  const browser = await import("data:text/javascript;base64," + Buffer.from(text).toString("base64"));
  assert.deepEqual(browser.parseResearchCommand('notifications search "Bogdan"'), parseResearchCommand('notifications search "Bogdan"'));
  assert.throws(() => browser.parseResearchCommand("shell id"));
});
