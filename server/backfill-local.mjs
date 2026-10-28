// One-time backfill via Cloudflare REST API — no Worker CPU limits
// Usage: CLOUDFLARE_API_TOKEN=... node backfill-local.mjs [uid]
const TOKEN = process.env.CLOUDFLARE_API_TOKEN;
const ACCOUNT = "22e7ea2d017115193dcc319b98938a5a";
const BUCKET = "forja-c2";
const API = `https://api.cloudflare.com/client/v4/accounts/${ACCOUNT}/r2/buckets/${BUCKET}/objects`;
const UIDS = process.argv[2] ? [process.argv[2]] : [
  "7dCgW3ay1Nc3yj5g0UVJSAiEUeK2",
  "pehzuDTwREMlqAYxM6DvLnlClK23",
  "yAJIovP3GVSGdG5yjDl39yGPXgA2",
];
const H = { "Authorization": `Bearer ${TOKEN}` };

async function listObjects(prefix, cursor) {
  let url = `${API}?prefix=${encodeURIComponent(prefix)}&limit=1000`;
  if (cursor) url += `&cursor=${encodeURIComponent(cursor)}`;
  const res = await fetch(url, { headers: H });
  const j = await res.json();
  if (!j.success) throw new Error(`list failed: ${j.errors?.[0]?.message}`);
  return { objects: j.result || [], truncated: j.result?.length === 1000, cursor: j.result?.[0]?.cursor };
}

async function getObject(key) {
  const res = await fetch(`${API}/${key}`, { headers: H });
  if (!res.ok) throw new Error(`get ${key}: ${res.status}`);
  return res.text();
}

async function putObject(key, data) {
  const res = await fetch(`${API}/${key}`, { method: "PUT", headers: { ...H, "Content-Type": "application/json" }, body: data });
  if (!res.ok) throw new Error(`put ${key}: ${res.status}`);
}

function normTs(ts) {
  const n = Number(ts) || 0;
  if (n > 1e12) return Math.floor(n / 1000);
  return Math.floor(n);
}

function monthKey(ts) {
  ts = normTs(ts);
  const d = new Date(ts * 1000);
  return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0");
}

function buildRow(rec, src, key) {
  let snippet = "", pkg = "", bat = 0;
  if (src === "result") {
    snippet = JSON.stringify(rec.data || "").slice(0, 200).toLowerCase();
    if (rec.data && typeof rec.data === "object") {
      if (rec.data.fg) pkg = String(rec.data.fg).slice(0, 80);
      const b = Number(rec.data.bat);
      if (isFinite(b) && b >= 0) bat = b;
    }
    return { id: rec.id || "", src, at: normTs(rec.at), action: rec.action || "", ok: rec.ok ?? null, err: String(rec.err || "").slice(0, 120), pkg, bat, snippet, key };
  } else {
    snippet = String(rec.screen_text || "").slice(0, 200).toLowerCase();
    pkg = String(rec.pkg || "").slice(0, 80);
    bat = Number(rec.bat) || 0;
    const id = key.split("/").pop().replace(".json", "");
    return { id, src, at: normTs(rec.at), action: rec.type || "event", ok: null, err: "", pkg, bat, snippet, key };
  }
}

async function backfill(uid) {
  console.log(`\n=== Backfill: ${uid.slice(0,12)}... ===`);
  const now = Math.floor(Date.now() / 1000);
  const existingKeys = new Set();
  // Load current month's existing index
  try {
    const existing = JSON.parse(await getObject(`hist/${uid}/${monthKey(now)}.json`));
    for (const r of existing) existingKeys.add(r.key);
  } catch { /* no existing index */ }

  const newRows = [];
  let skippedPings = 0, skippedDupes = 0, errors = 0, totalResults = 0;

  // Scan r/{uid}/ — all non-ping results
  {
    let cursor;
    do {
      const page = await listObjects(`r/${uid}/`, cursor);
      for (const o of page.objects) {
        if (o.key.endsWith("_index.json") || o.key.endsWith("_index_cmd.json")) continue;
        if (o.key.includes("_hb_")) { skippedPings++; continue; }
        totalResults++;
        if (existingKeys.has(o.key)) { skippedDupes++; continue; }
        try {
          const rec = JSON.parse(await getObject(o.key));
          if (rec.action === "ping") { skippedPings++; continue; }
          newRows.push(buildRow(rec, "result", o.key));
          existingKeys.add(o.key);
        } catch (e) { errors++; }
      }
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
  }

  // Scan events/{uid}/
  let eventCount = 0;
  {
    let cursor;
    do {
      const page = await listObjects(`events/${uid}/`, cursor);
      for (const o of page.objects) {
        if (o.key.endsWith("_index.json") || o.key.endsWith("_shot.jpg")) continue;
        if (existingKeys.has(o.key)) { skippedDupes++; continue; }
        try {
          const rec = JSON.parse(await getObject(o.key));
          newRows.push(buildRow(rec, "event", o.key));
          existingKeys.add(o.key);
          eventCount++;
        } catch (e) { errors++; }
      }
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
  }

  console.log(`  Non-ping results: ${totalResults - skippedPings}, Events: ${eventCount}, Skipped pings: ${skippedPings}, Dupes: ${skippedDupes}, Errors: ${errors}`);

  // Batch write by month
  const byMonth = {};
  for (const row of newRows) {
    const mk = monthKey(row.at);
    if (!byMonth[mk]) byMonth[mk] = [];
    byMonth[mk].push(row);
  }
  for (const [mk, rows] of Object.entries(byMonth).sort()) {
    const sk = `hist/${uid}/${mk}.json`;
    let shard = [];
    try { shard = JSON.parse(await getObject(sk)); } catch { }
    shard.unshift(...rows);
    await putObject(sk, JSON.stringify(shard));
    console.log(`  Wrote ${sk} (${shard.length} rows)`);
  }

  console.log(`  Done: ${newRows.length} new rows in months: ${Object.keys(byMonth).join(", ") || "none"}`);
  return { total: newRows.length, months: Object.keys(byMonth) };
}

for (const uid of UIDS) {
  try { await backfill(uid); }
  catch (e) { console.error(`  FAILED: ${e.message}`); }
}
console.log("\n=== All backfills complete ===");
