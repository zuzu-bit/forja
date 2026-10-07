import { COMMAND_TTL_MS, MAX_BATCH_BYTES, MAX_ORIGINAL_BYTES, ResearchError, canonicalJson, fail, hash, historyQuery, identifier, objectField, readJson, reply, sourceFilter, sourceName, textField, validateArtifactCommand, validateEvent } from "./research-model.js";
import { EVIDENCE_PLAN_TTL_MS, planEvidenceQuestion } from "./research-evidence-planner.js";
const DEVICE_FIELDS = ["screen", "battery", "batteryPercent", "charging", "network", "foreground", "foregroundSince", "observationActive", "model", "manufacturer", "android", "androidVersion", "capabilities", "consentVersion", "source", "enabled"];
const selectedDeviceFields = value => Object.fromEntries(Object.entries(value).filter(([key]) => DEVICE_FIELDS.includes(key)));

export class ResearchDevice {
  constructor(state, env) {
    this.state = state; this.env = env; this.sql = state.storage.sql; this.subscribers = new Set(); this.delivery = Promise.resolve();
    this.sql.exec(`CREATE TABLE IF NOT EXISTS events (event_id TEXT PRIMARY KEY, sequence INTEGER NOT NULL UNIQUE, source_timestamp INTEGER NOT NULL, source TEXT NOT NULL, event_type TEXT NOT NULL, identity TEXT NOT NULL, event_json TEXT NOT NULL)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS event_timeline ON events(source_timestamp,sequence)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS event_source ON events(source,source_timestamp,sequence)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value TEXT NOT NULL)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS artifacts (artifact_id TEXT PRIMARY KEY, source TEXT NOT NULL, event_id TEXT NOT NULL, payload TEXT NOT NULL, sequence INTEGER NOT NULL)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS commands (command_id TEXT PRIMARY KEY, actor_uid TEXT NOT NULL, artifact_id TEXT NOT NULL, source TEXT NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL, result_json TEXT, result_digest TEXT, grant_revision TEXT NOT NULL, requester_role TEXT NOT NULL)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS artifact_chunks (command_id TEXT NOT NULL, chunk_index INTEGER NOT NULL, bytes BLOB NOT NULL, PRIMARY KEY(command_id,chunk_index))`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS audit (id TEXT PRIMARY KEY, at INTEGER NOT NULL, actor_uid TEXT NOT NULL, action TEXT NOT NULL, details TEXT NOT NULL)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS evidence_queries (plan_id TEXT PRIMARY KEY, actor_uid TEXT NOT NULL, grant_revision TEXT NOT NULL, question TEXT NOT NULL, expires_at INTEGER NOT NULL, plan_json TEXT NOT NULL)`);
  }
  one(sql, ...args) { return this.sql.exec(sql, ...args).toArray()[0] || null; }
  get(key, fallback = null) { const row = this.one("SELECT value FROM state WHERE key=?", key); return row ? JSON.parse(row.value) : fallback; }
  put(key, value) { this.sql.exec("INSERT INTO state VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, JSON.stringify(value)); }
  audit(actor, action, details = {}) { this.sql.exec("INSERT INTO audit VALUES (?,?,?,?,?)", crypto.randomUUID(), Date.now(), actor.uid, action, JSON.stringify(details)); }
  registry(actor, path, body) {
    return this.env.RESEARCH_REGISTRY.get(this.env.RESEARCH_REGISTRY.idFromName("registry")).fetch(new Request(`https://research.internal/${path}`, {
      method: "POST", headers: { "X-Research-Actor": JSON.stringify(actor), "content-type": "application/json" }, body: JSON.stringify(body),
    }));
  }
  async authorize(actor, deviceId, action) {
    const response = await this.registry(actor, "authorize", { deviceId, action }); const body = await response.json();
    if (!response.ok) fail(response.status, body.error || "Device assignment revoked."); return body;
  }
  async rate(actor, deviceId, bucket, limit) {
    const response = await this.registry(actor, "rate", { bucket: `${deviceId}:${bucket}`, limit });
    if (!response.ok) { const body = await response.json(); fail(response.status, body.error, body); }
  }
  status(device) {
    const lastSeen = this.get("lastSeen", 0); const lastSequenceNumber = this.get("lastSequence", 0);
    const lastEvent = this.one("SELECT event_json FROM events ORDER BY sequence DESC LIMIT 1");
    return { device, deviceId: device.deviceId, state: this.get("deviceState", {}), currentState: this.get("currentState", {}),
      capabilities: this.get("capabilities", {}), online: !!lastSeen && Date.now() - lastSeen < 90000,
      lastSeen, lastEvent: lastEvent ? JSON.parse(lastEvent.event_json) : null, lastSequenceNumber, serverReceivedTimestamp: Date.now() };
  }
  ingest(body, deviceId) {
    if (!Array.isArray(body.events) || !body.events.length || body.events.length > 100) fail(400, "events must contain 1..100 events.");
    const input = body.events.map(e => validateEvent(e, deviceId)); let last = this.get("lastSequence", 0);
    const accepted = []; const duplicate = []; const pending = new Map(); const eventReceivedTimestamps = {}; const serverReceivedTimestamp = Date.now();
    // Validate the whole batch before committing anything. Equal IDs must mean equal evidence.
    for (const event of input) {
      const identity = canonicalJson(event); const previous = pending.get(event.eventId) || this.one("SELECT identity,sequence,event_json FROM events WHERE event_id=?", event.eventId);
      if (previous) {
        if (previous.identity !== identity) fail(409, "eventId already exists with different evidence.");
        eventReceivedTimestamps[event.eventId] = previous.serverReceivedTimestamp ?? JSON.parse(previous.event_json).serverReceivedTimestamp;
        duplicate.push(event.eventId); continue;
      }
      if (event.sequenceNumber !== last + 1) fail(409, "New events must arrive in contiguous device sequence order.", { expectedSequenceNumber: last + 1 });
      const sequenceOwner = this.one("SELECT event_id FROM events WHERE sequence=?", event.sequenceNumber);
      if (sequenceOwner) fail(409, "sequenceNumber belongs to another event.");
      last = event.sequenceNumber;
      const record = { ...event, serverReceivedTimestamp, syncState: "synced" };
      pending.set(event.eventId, { identity, sequence: last, serverReceivedTimestamp }); accepted.push(record); eventReceivedTimestamps[event.eventId] = serverReceivedTimestamp;
    }
    this.state.storage.transactionSync(() => {
      const current = this.get("currentState", {}); const deviceState = this.get("deviceState", {});
      for (const event of accepted) {
        this.sql.exec("INSERT INTO events VALUES (?,?,?,?,?,?,?)", event.eventId, event.sequenceNumber, event.sourceTimestamp, event.source, event.type, canonicalJson(validateEvent(event, deviceId)), JSON.stringify(event));
        const unavailable = ["source_unavailable", "source_disabled", "consent_disabled"].includes(event.type);
        const previous = current[event.source] || {}; const merge = event.source === "DEVICE" || unavailable;
        const carry = event.source === "DEVICE" ? selectedDeviceFields(previous) : previous;
        const fieldReferences = Object.assign(Object.create(null), merge ? Object.fromEntries(Object.entries(previous.fieldReferences || {}).filter(([key]) => event.source !== "DEVICE" || DEVICE_FIELDS.includes(key))) : {});
        const projected = unavailable ? { reason: event.payload.reason, ...(event.payload.source ? { source: event.payload.source } : {}) } : event.payload;
        for (const field of Object.keys(projected)) fieldReferences[field] = { eventId: event.eventId, sourceTimestamp: event.sourceTimestamp, sequenceNumber: event.sequenceNumber };
        current[event.source] = { ...(merge ? carry : {}), ...projected, available: !unavailable, fieldReferences,
          type: event.type, eventId: event.eventId, sourceTimestamp: event.sourceTimestamp, sequenceNumber: event.sequenceNumber };
        if (!unavailable) delete current[event.source].reason;
        if (unavailable && event.source === "APP") deviceState.foreground = null;
        if (event.source === "APP") {
          const foreground = Object.hasOwn(event.payload, "foreground") ? event.payload.foreground : event.payload.package || event.payload.packageName;
          if (event.type === "foreground_left" || foreground === null) { deviceState.foreground = null; deviceState.foregroundSince = event.sourceTimestamp; }
          else if (foreground) { deviceState.foreground = foreground; deviceState.foregroundSince = event.sourceTimestamp; }
        }
        if (event.source === "DEVICE") for (const field of ["screen", "battery", "charging", "network", "observationActive", "model", "manufacturer", "androidVersion", "capabilities"]) {
          if (Object.hasOwn(event.payload, field)) deviceState[field] = event.payload[field];
        }
        if (event.source === "DEVICE" && Object.hasOwn(event.payload, "batteryPercent")) deviceState.battery = event.payload.batteryPercent;
        if (event.source === "DEVICE" && event.payload.capabilities && typeof event.payload.capabilities === "object" && !Array.isArray(event.payload.capabilities)) this.put("capabilities", event.payload.capabilities);
        if (event.source === "DEVICE" && event.type === "source_consent_changed" && event.payload.source) {
          const target = sourceName(event.payload.source); const enabled = event.payload.enabled === true;
          current[target] = { ...current[target], consentEnabled: enabled, ...(enabled ? {} : { available: false }),
            consentEventId: event.eventId, consentTimestamp: event.sourceTimestamp };
          if (target === "APP" && !enabled) deviceState.foreground = null;
        }
        if (event.source === "DEVICE" && event.type === "source_unavailable" && event.payload.source) {
          let target; try { target = sourceName(event.payload.source); } catch (_) { target = null; }
          if (target) {
            current[target] = { ...current[target], available: false, reason: event.payload.reason,
              availabilityEventId: event.eventId, availabilityTimestamp: event.sourceTimestamp };
            if (target === "APP") deviceState.foreground = null;
          }
        }
        if (event.source === "NETWORK" && !unavailable) deviceState.network = event.payload.connectionType || event.payload.type || event.payload.transport || "unknown";
        if (["MEDIA", "FILE"].includes(event.source) && event.payload.artifactId) {
          const artifactId = identifier(event.payload.artifactId, "artifactId");
          this.sql.exec("INSERT INTO artifacts VALUES (?,?,?,?,?) ON CONFLICT(artifact_id) DO UPDATE SET source=excluded.source,event_id=excluded.event_id,payload=excluded.payload,sequence=excluded.sequence", artifactId, event.source, event.eventId, JSON.stringify(event.payload), event.sequenceNumber);
        }
      }
      this.put("lastSequence", last); this.put("lastSeen", serverReceivedTimestamp); this.put("currentState", current); this.put("deviceState", deviceState);
    });
    // A transaction has committed the durable journal before any live delivery.
    if (accepted.length) { this.delivery = this.delivery.then(() => this.broadcast(accepted)).catch(() => {}); this.state.waitUntil(this.delivery); }
    return { acceptedEventIds: accepted.map(e => e.eventId), duplicateEventIds: duplicate, eventReceivedTimestamps, lastSequenceNumber: last, serverReceivedTimestamp };
  }
  history(query) {
    const where = ["source_timestamp >= ?", "source_timestamp <= ?"]; const args = [query.from, query.to];
    if (query.maximumSequence !== undefined) { where.push("sequence <= ?"); args.push(query.maximumSequence); }
    if (query.sources.length) { where.push(`source IN (${query.sources.map(() => "?").join(",")})`); args.push(...query.sources); }
    if (query.query) { where.push("(LOWER(event_json) LIKE ? ESCAPE '\\')"); args.push(`%${query.query.toLowerCase().replace(/[\\%_]/g, "\\$&")}%`); }
    if (query.cursor) {
      const compare = query.order === "asc" ? ">" : "<";
      where.push(`(source_timestamp ${compare} ? OR (source_timestamp=? AND sequence ${compare} ?))`); args.push(query.cursor[0], query.cursor[0], query.cursor[1]);
    }
    const rows = this.sql.exec(`SELECT event_json FROM events WHERE ${where.join(" AND ")} ORDER BY source_timestamp ${query.order}, sequence ${query.order} LIMIT ?`, ...args, query.limit + 1).toArray();
    const events = rows.slice(0, query.limit).map(r => JSON.parse(r.event_json)); const last = events.at(-1);
    return { events, nextCursor: rows.length > query.limit && last ? btoa(JSON.stringify([last.sourceTimestamp, last.sequenceNumber])) : null, lastSequenceNumber: this.get("lastSequence", 0) };
  }
  async broadcast(events) {
    await Promise.all([...this.subscribers].map(async subscriber => {
      try {
        const grant = await this.authorize(subscriber.actor, subscriber.deviceId, "read");
        if (grant.grantRevision !== subscriber.grantRevision) fail(403, "Live assignment grant changed.");
        for (const event of events) {
          if (subscriber.replaying) subscriber.buffer.push(event);
          else subscriber.sendEvent(event);
        }
      } catch (_) { subscriber.close("revoked"); }
    }));
  }
  stream(actor, deviceId, url, grantRevision) {
    const after = Number(url.searchParams.get("afterSequence") || requestLastId(url) || 0);
    if (!Number.isSafeInteger(after) || after < 0 || after > this.get("lastSequence", 0)) fail(400, "Invalid replay sequence.");
    const sources = sourceFilter(url.searchParams.get("source")); const encoder = new TextEncoder();
    let controller; let finished = false; const started = Date.now(); const leaseEnd = Math.min(started + 60000, actor.tokenExpiresAt || Infinity);
    const readable = new ReadableStream({ start(c) { controller = c; }, cancel: () => subscriber.close("client_closed") });
    const subscriber = { actor, deviceId, grantRevision, replaying: true, buffer: [], cursor: after,
      send: (kind, data, id) => {
        if (finished) return false;
        // Bound each connection's queue; a slow client reconnects from its last received ID.
        if (controller.desiredSize < -256) { subscriber.close("slow_client"); return false; }
        controller.enqueue(encoder.encode(`${id !== undefined ? `id: ${id}\n` : ""}event: ${kind}\ndata: ${JSON.stringify(data)}\n\n`));
        return true;
      },
      sendEvent: event => {
        if (finished || event.sequenceNumber <= subscriber.cursor) return;
        const sent = !sources.length || sources.includes(event.source) ? subscriber.send("device-event", event, event.sequenceNumber) : subscriber.send("cursor", { lastSequenceNumber: event.sequenceNumber }, event.sequenceNumber);
        if (sent) subscriber.cursor = event.sequenceNumber;
      },
      close: reason => {
        if (finished) return; finished = true; this.subscribers.delete(subscriber);
        try { controller.enqueue(encoder.encode(`event: closed\ndata: ${JSON.stringify({ reason, lastSequenceNumber: subscriber.cursor })}\n\n`)); controller.close(); } catch (_) {}
      },
    };
    this.subscribers.add(subscriber);
    subscriber.send("ready", { deviceId, afterSequence: after, leaseSeconds: 60 });
    // Register before replay; inserts during replay are buffered and deduplicated by sequence.
    const replay = this.sql.exec("SELECT event_json FROM events WHERE sequence>? ORDER BY sequence LIMIT 201", after).toArray();
    for (const row of replay.slice(0, 200)) subscriber.sendEvent(JSON.parse(row.event_json));
    subscriber.replaying = false;
    for (const event of subscriber.buffer.sort((a, b) => a.sequenceNumber - b.sequenceNumber)) subscriber.sendEvent(event);
    subscriber.buffer = [];
    if (replay.length > 200) subscriber.close("replay_page");
    this.state.waitUntil((async () => {
      while (!finished && Date.now() < leaseEnd) {
        await new Promise(resolve => setTimeout(resolve, Math.min(15000, Math.max(0, leaseEnd - Date.now()))));
        if (finished) break;
        try {
          const grant = await this.authorize(actor, deviceId, "read"); if (grant.grantRevision !== subscriber.grantRevision) fail(403, "Live assignment grant changed.");
          subscriber.send("heartbeat", { lastSequenceNumber: subscriber.cursor, serverTimestamp: Date.now() });
        }
        catch (_) { subscriber.close("revoked"); }
      }
      subscriber.close("lease_expired");
    })());
    return new Response(readable, { headers: { "content-type": "text/event-stream", "cache-control": "no-store", "x-content-type-options": "nosniff" } });
  }
  purgeExpired() {
    this.state.storage.transactionSync(() => {
      this.sql.exec("DELETE FROM artifact_chunks WHERE command_id IN (SELECT command_id FROM commands WHERE expires_at<=?)", Date.now());
      this.sql.exec("UPDATE commands SET status='expired',result_json=NULL,result_digest=NULL WHERE expires_at<=? AND status!='expired'", Date.now());
      this.sql.exec("DELETE FROM evidence_queries WHERE expires_at<=?", Date.now());
    });
  }
  async scheduleExpiry() {
    const next = this.one("SELECT MIN(at) AS at FROM (SELECT MIN(expires_at) AS at FROM commands WHERE status!='expired' UNION ALL SELECT MIN(expires_at) AS at FROM evidence_queries)")?.at;
    if (next) await this.state.storage.setAlarm(next);
  }
  async alarm() { this.purgeExpired(); await this.scheduleExpiry(); }
  command(row) {
    return { commandId: row.command_id, type: "artifact_get", artifactId: row.artifact_id, source: row.source,
      expiresAt: row.expires_at, status: row.status, ...(row.result_json ? JSON.parse(row.result_json) : {}) };
  }
  async requestCommand(actor, body) {
    const device = await this.authorize(actor, this.get("deviceId"), "command");
    const validated = validateArtifactCommand(body); const source = validated.source === "media" ? "MEDIA" : "FILE";
    const artifact = this.one("SELECT * FROM artifacts WHERE artifact_id=? AND source=?", validated.artifactId, source);
    if (!artifact) fail(404, "Artifact is not in this device's observed index.");
    const pending = this.one("SELECT COUNT(*) AS n FROM commands WHERE status='pending' AND expires_at>?", Date.now()).n;
    if (pending >= 20) fail(429, "Too many pending artifact requests.");
    const commandId = crypto.randomUUID(); const expiresAt = Math.min(Date.now() + COMMAND_TTL_MS, actor.tokenExpiresAt || Infinity);
    this.state.storage.transactionSync(() => {
      this.sql.exec("INSERT INTO commands VALUES (?,?,?,?,?,'pending',NULL,NULL,?,?)", commandId, actor.uid, validated.artifactId, validated.source, expiresAt, device.grantRevision, device.role);
      this.audit(actor, "artifact_requested", { commandId, artifactId: validated.artifactId });
    });
    await this.scheduleExpiry(); return this.command(this.one("SELECT * FROM commands WHERE command_id=?", commandId));
  }
  async result(actor, commandId, body) {
    const row = this.one("SELECT * FROM commands WHERE command_id=?", commandId);
    if (!row) fail(404, "Unknown command for this device.");
    if (row.expires_at <= Date.now()) { this.purgeExpired(); fail(410, "Artifact request expired."); }
    if (!["completed", "failed"].includes(body.status)) fail(400, "Invalid result status.");
    const digest = await hash(canonicalJson(body));
    if (row.status !== "pending") {
      if (row.result_digest === digest) return { commandId, status: row.status, duplicate: true };
      fail(409, "Command already has a different result.");
    }
    const result = { status: body.status }; let bytes;
    if (body.status === "completed") {
      const filename = textField(body.filename || row.artifact_id, "filename", 512);
      const mime = textField(body.mime || "application/octet-stream", "mime", 120);
      if (!/^[a-zA-Z0-9.+_-]+\/[a-zA-Z0-9.+_-]+$/.test(mime)) fail(400, "Invalid MIME.");
      if (typeof body.bytesBase64 !== "string" || body.bytesBase64.length > Math.ceil(MAX_ORIGINAL_BYTES / 3) * 4 || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(body.bytesBase64)) fail(413, "Original must be valid base64 within 8 MiB.");
      const decoded = atob(body.bytesBase64); if (decoded.length > MAX_ORIGINAL_BYTES) fail(413, "Original exceeds 8 MiB.");
      bytes = Uint8Array.from(decoded, c => c.charCodeAt(0)); Object.assign(result, { filename, mime, size: bytes.length });
    } else result.error = textField(body.error || "Device could not access artifact.", "error", 500);
    // Check membership again after body processing before committing sensitive data.
    await this.authorize(actor, this.get("deviceId"), "result");
    await this.requesterGrant(actor, row);
    if (row.expires_at <= Date.now()) fail(410, "Artifact request expired.");
    const latest = this.one("SELECT status,result_digest FROM commands WHERE command_id=?", commandId);
    if (latest.status !== "pending") { if (latest.result_digest === digest) return { commandId, status: latest.status, duplicate: true }; fail(409, "Command already completed."); }
    this.state.storage.transactionSync(() => {
      if (bytes) for (let offset = 0, index = 0; offset < bytes.length; offset += 512 * 1024, index++) {
        this.sql.exec("INSERT INTO artifact_chunks VALUES (?,?,?)", commandId, index, bytes.slice(offset, offset + 512 * 1024).buffer);
      }
      this.sql.exec("UPDATE commands SET status=?,result_json=?,result_digest=? WHERE command_id=?", result.status, JSON.stringify(result), digest, commandId);
    });
    return { commandId, status: result.status };
  }
  async requesterGrant(actor, row) {
    const response = await this.registry(actor, "grant", { deviceId: this.get("deviceId"), requesterUid: row.actor_uid, requesterRole: row.requester_role, grantRevision: row.grant_revision });
    if (!response.ok) { this.sql.exec("UPDATE commands SET status='revoked' WHERE command_id=?", row.command_id); fail(403, "Artifact requester's assignment was revoked or replaced."); }
  }
  download(actor, row) {
    if (row.expires_at <= Date.now()) fail(410, "Artifact download expired.");
    if (row.status !== "completed") fail(409, "Artifact original is not ready.");
    const result = JSON.parse(row.result_json); const bytes = new Uint8Array(result.size); let offset = 0;
    for (const chunk of this.sql.exec("SELECT bytes FROM artifact_chunks WHERE command_id=? ORDER BY chunk_index", row.command_id)) {
      const data = new Uint8Array(chunk.bytes); bytes.set(data, offset); offset += data.byteLength;
    }
    if (offset !== result.size) fail(410, "Original is no longer available.");
    this.audit(actor, "artifact_downloaded", { commandId: row.command_id, artifactId: row.artifact_id });
    return new Response(bytes, { headers: { "content-type": "application/octet-stream", "cache-control": "no-store",
      "content-disposition": `attachment; filename*=UTF-8''${encodeURIComponent(result.filename)}`, "x-content-type-options": "nosniff" } });
  }
  resolveEvidencePlan(body, planning) {
    const requested = planning.plan; const url = new URL("https://research.internal/events");
    let mode = requested.mode; let literal = requested.query; let aroundArtifact = null; let anchorCitation = null;
    if (requested.from !== null) { url.searchParams.set("from", String(requested.from)); url.searchParams.set("to", String(requested.to)); }
    if (requested.sources.length) url.searchParams.set("source", requested.sources.join(","));
    const maximumSequence = this.get("lastSequence", 0);
    if (mode === "around_artifact") {
      const artifactTerm = requested.artifact; const like = `%${artifactTerm.toLowerCase().replace(/[\\%_]/g, "\\$&")}%`;
      const matches = this.sql.exec("SELECT event_json,MIN(sequence) AS first_sequence FROM events WHERE sequence<=? AND source IN ('MEDIA','FILE') AND (LOWER(json_extract(event_json,'$.payload.filename')) LIKE ? ESCAPE '\\' OR json_extract(event_json,'$.payload.artifactId')=?) GROUP BY COALESCE(json_extract(event_json,'$.payload.artifactId'),event_id) ORDER BY first_sequence DESC LIMIT 2", maximumSequence, like, artifactTerm).toArray();
      if (matches.length === 1) {
        const event = JSON.parse(matches[0].event_json); const window = requested.windowMinutes * 60000;
        literal = ""; aroundArtifact = event.eventId; anchorCitation = event;
        url.searchParams.set("from", String(Math.max(0, event.sourceTimestamp - window))); url.searchParams.set("to", String(Math.min(8640000000000000, event.sourceTimestamp + window)));
      } else { literal = artifactTerm; mode = matches.length ? "ambiguous_artifact" : "artifact_not_observed"; }
    }
    if (body.from !== undefined) url.searchParams.set("from", String(body.from));
    if (body.to !== undefined) url.searchParams.set("to", String(body.to));
    if (body.source) url.searchParams.set("source", body.source);
    url.searchParams.set("query", literal); url.searchParams.set("limit", String(body.limit || 100));
    return { query: { ...historyQuery(url), maximumSequence }, mode, aroundArtifact, anchorCitation, planner: planning.planner,
      currentState: mode === "compare" ? this.get("currentState", {}) : undefined };
  }
  async evidence(actor, device, body) {
    const question = textField(body.query, "query", 300); let planId; let resolved;
    if (body.planId) {
      planId = identifier(body.planId, "planId");
      if (["from", "to", "source"].some(key => body[key] !== undefined)) fail(400, "A continuation cannot override its original evidence filters.");
      const cached = this.one("SELECT * FROM evidence_queries WHERE plan_id=?", planId);
      if (!cached || cached.expires_at <= Date.now()) fail(410, "Evidence plan expired; start a new query.");
      if (cached.actor_uid !== actor.uid || cached.grant_revision !== device.grantRevision || cached.question !== question) fail(403, "Evidence plan belongs to a different investigator, assignment, or question.");
      resolved = JSON.parse(cached.plan_json);
    } else {
      if (body.cursor) fail(400, "Evidence continuation requires its original planId.");
      const planning = await planEvidenceQuestion(question, this.env, { beforeAI: async () => {
        const response = await this.registry(actor, "rate", { bucket: "evidence-ai", limit: 20 });
        if (!response.ok) { const error = await response.json(); fail(response.status, error.error, error); }
      } });
      const grant = await this.authorize(actor, device.deviceId, "read");
      if (grant.grantRevision !== device.grantRevision) fail(403, "Investigator assignment changed during evidence planning.");
      resolved = this.resolveEvidencePlan(body, planning); planId = crypto.randomUUID();
      this.sql.exec("INSERT INTO evidence_queries VALUES (?,?,?,?,?,?)", planId, actor.uid, device.grantRevision, question, Date.now() + EVIDENCE_PLAN_TTL_MS, JSON.stringify(resolved));
      // This is a temporary continuation cache; the durable device journal is never evicted.
      this.sql.exec("DELETE FROM evidence_queries WHERE plan_id IN (SELECT plan_id FROM evidence_queries ORDER BY rowid DESC LIMIT -1 OFFSET 128)");
      await this.scheduleExpiry();
    }
    // Long-running planning, body reading and alarm scheduling never grant stale access.
    const fresh = await this.authorize(actor, device.deviceId, "read");
    if (fresh.grantRevision !== device.grantRevision) fail(403, "Investigator assignment changed during evidence planning.");
    const queryUrl = new URL("https://research.internal/events");
    queryUrl.searchParams.set("limit", String(body.limit || resolved.query.limit));
    if (body.cursor) queryUrl.searchParams.set("cursor", body.cursor);
    const page = historyQuery(queryUrl); const parsed = { ...resolved.query, limit: page.limit, cursor: page.cursor };
    const result = this.history(parsed); const counts = {};
    for (const e of result.events) counts[e.source] = (counts[e.source] || 0) + 1;
    let periodCounts;
    if (resolved.mode === "compare") {
      periodCounts = {};
      const clauses = ["source_timestamp>=?", "source_timestamp<=?", "sequence<=?"]; const args = [parsed.from, parsed.to, parsed.maximumSequence];
      if (parsed.sources.length) { clauses.push(`source IN (${parsed.sources.map(() => "?").join(",")})`); args.push(...parsed.sources); }
      if (parsed.query) { clauses.push("LOWER(event_json) LIKE ? ESCAPE '\\'"); args.push(`%${parsed.query.toLowerCase().replace(/[\\%_]/g, "\\$&")}%`); }
      for (const row of this.sql.exec(`SELECT source,COUNT(*) AS count FROM events WHERE ${clauses.join(" AND ")} GROUP BY source`, ...args)) periodCounts[row.source] = row.count;
    }
    const summary = resolved.mode === "ambiguous_artifact" ? "Multiple observed artifacts match; specify an exact filename or inspect the cited events." : resolved.mode === "artifact_not_observed" ? "No matching artifact has been observed; no surrounding activity is inferred." : `${result.events.length} observed events in this evidence page.`;
    this.audit(actor, "evidence_query", { query: question, planId, planner: resolved.planner, mode: resolved.mode });
    return { summary, generated: false, mode: resolved.mode, planId, planner: resolved.planner,
      plan: { literal: parsed.query, from: parsed.from, to: parsed.to, sources: parsed.sources, aroundEventId: resolved.aroundArtifact },
      citations: result.events, anchorCitation: resolved.anchorCitation, counts, periodCounts, currentState: resolved.currentState,
      coverage: { truncated: !!result.nextCursor, nextCursor: result.nextCursor, snapshotSequenceNumber: parsed.maximumSequence,
        description: "Only events observed and synchronized by FORJA at query creation; no inference of unobserved activity." } };
  }
  async fetch(request) {
    try {
      const url = new URL(request.url); const p = url.pathname.split("/").filter(Boolean); const deviceId = identifier(p[1], "deviceId");
      const actor = JSON.parse(request.headers.get("X-Research-Actor") || "null");
      if (!actor?.uid) fail(401, "Authenticated actor required.");
      const action = p[2] === "events" && request.method === "POST" ? "ingest" : p[2] === "heartbeat" ? "heartbeat" : p[2] === "commands" && p[4] === "result" ? "result" : p[2] === "commands" && (request.method === "POST" || p[4] === "download") ? "command" : p[2] === "commands" && p.length === 3 && request.method === "GET" ? "poll" : "read";
      await this.authorize(actor, deviceId, action); this.put("deviceId", deviceId);
      await this.rate(actor, deviceId, action, action === "ingest" ? 120 : action === "poll" ? 120 : action === "heartbeat" ? 90 : action === "command" ? 20 : 180);
      const device = await this.authorize(actor, deviceId, action);
      this.purgeExpired();
      if (p[2] === "status" && request.method === "GET") { this.audit(actor, "status_read"); return reply(this.status(device)); }
      if (p[2] === "heartbeat" && request.method === "POST") {
        const body = await readJson(request, 16384); objectField(body.state || {}, "state", 8192); objectField(body.capabilities || {}, "capabilities", 8192);
        await this.authorize(actor, deviceId, "heartbeat");
        this.put("deviceState", { ...selectedDeviceFields(this.get("deviceState", {})), ...selectedDeviceFields(body.state || {}) }); this.put("capabilities", body.capabilities || {}); this.put("lastSeen", Date.now());
        return reply({ serverReceivedTimestamp: Date.now(), lastSequenceNumber: this.get("lastSequence", 0) });
      }
      if (p[2] === "events" && request.method === "POST") {
        const body = await readJson(request, MAX_BATCH_BYTES); await this.authorize(actor, deviceId, "ingest"); return reply(this.ingest(body, deviceId));
      }
      if (p[2] === "events" && request.method === "GET") {
        const query = historyQuery(url); this.audit(actor, "history_read", { from: query.from, to: query.to, sources: query.sources, query: query.query }); return reply(this.history(query));
      }
      if (p[2] === "stream" && request.method === "GET") { this.audit(actor, "live_stream_opened"); return this.stream(actor, deviceId, url, device.grantRevision); }
      if (p[2] === "audit" && request.method === "GET") {
        return reply({ actions: this.sql.exec("SELECT id,at,actor_uid AS actorUid,action,details FROM audit ORDER BY at DESC LIMIT 200").toArray().map(r => ({ ...r, details: JSON.parse(r.details) })) });
      }
      if (p[2] === "artifacts" && request.method === "GET") {
        this.audit(actor, "artifact_metadata_read", { artifactId: p[3] || null });
        if (p[3]) {
          const row = this.one("SELECT artifact_id AS artifactId,source,event_id AS eventId,payload FROM artifacts WHERE artifact_id=?", identifier(decodeURIComponent(p[3]), "artifactId"));
          if (!row) fail(404, "Artifact not observed on this device."); return reply({ ...row, payload: JSON.parse(row.payload) });
        }
        const sources = sourceFilter(url.searchParams.get("source")); const query = textField(url.searchParams.get("query") || "", "query"); const limit = Number(url.searchParams.get("limit") || 100);
        if (!Number.isInteger(limit) || limit < 1 || limit > 500) fail(400, "limit must be 1..500.");
        const where = ["1=1"]; const args = [];
        const cursor = url.searchParams.get("cursor");
        if (cursor) { const before = Number(cursor); if (!Number.isSafeInteger(before) || before < 1) fail(400, "Invalid artifact cursor."); where.push("sequence < ?"); args.push(before); }
        if (sources.length) { where.push(`source IN (${sources.map(() => "?").join(",")})`); args.push(...sources); }
        if (query) {
          if (/[?*]/.test(query)) { where.push("json_extract(payload,'$.filename') GLOB ?"); args.push(query); }
          else { where.push("LOWER(payload) LIKE ? ESCAPE '\\'"); args.push(`%${query.toLowerCase().replace(/[\\%_]/g, "\\$&")}%`); }
        }
        const rows = this.sql.exec(`SELECT artifact_id AS artifactId,source,event_id AS eventId,payload,sequence FROM artifacts WHERE ${where.join(" AND ")} ORDER BY sequence DESC LIMIT ?`, ...args, limit + 1).toArray();
        const artifacts = rows.slice(0, limit).map(r => ({ ...r, payload: JSON.parse(r.payload) }));
        return reply({ artifacts, truncated: rows.length > limit, nextCursor: rows.length > limit ? String(artifacts.at(-1).sequence) : null });
      }
      if (p[2] === "commands") {
        if (p.length === 3 && request.method === "POST") return reply(await this.requestCommand(actor, await readJson(request, 4096)), 201);
        if (p.length === 3 && request.method === "GET") {
          const commands = [];
          for (const row of this.sql.exec("SELECT * FROM commands WHERE status='pending' AND expires_at>? ORDER BY expires_at LIMIT 20", Date.now()).toArray()) {
            try { await this.requesterGrant(actor, row); commands.push(this.command(row)); } catch (_) {}
          }
          return reply({ commands, serverTimestamp: Date.now() });
        }
        const commandId = identifier(p[3], "commandId"); const row = this.one("SELECT * FROM commands WHERE command_id=?", commandId);
        if (!row) fail(404, "Command does not belong to this device.");
        if (p[4] !== "result" && (row.actor_uid !== actor.uid || row.grant_revision !== device.grantRevision)) fail(403, "Artifact request is scoped to its original investigator grant.");
        if (p[4] === "result" && request.method === "POST") return reply(await this.result(actor, commandId, await readJson(request, Math.ceil(MAX_ORIGINAL_BYTES / 3) * 4 + 4096)));
        if (p[4] === "download" && request.method === "GET") return this.download(actor, row);
        if (p.length === 4 && request.method === "GET") return reply(this.command(row));
      }
      if (p[2] === "evidence" && p[3] === "search" && request.method === "POST") {
        const body = await readJson(request, 4096); return reply(await this.evidence(actor, device, body));
      }
      fail(404, "Unknown research device route.");
    } catch (e) {
      if (e instanceof ResearchError) return reply({ error: e.message, ...e.details }, e.status);
      return reply({ error: "Research device service unavailable." }, 500);
    }
  }
}
function requestLastId(url) { return url.searchParams.get("lastEventId"); }
