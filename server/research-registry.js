import { CONSENT_VERSION, ResearchError, fail, hash, identifier, organizer, readJson, reply, textField } from "./research-model.js";

// Only the authenticated Worker can access this binding; it has no public route.
export class ResearchRegistry {
  constructor(state, env) {
    this.state = state; this.env = env; this.sql = state.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS sessions (id TEXT PRIMARY KEY, label TEXT NOT NULL, organizer_uid TEXT NOT NULL, active INTEGER NOT NULL, created_at INTEGER NOT NULL)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS invitations (digest TEXT PRIMARY KEY, session_id TEXT NOT NULL, expires_at INTEGER NOT NULL, consumed_at INTEGER)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS assignments (session_id TEXT NOT NULL, uid TEXT NOT NULL, role TEXT NOT NULL, grant_revision TEXT NOT NULL, PRIMARY KEY(session_id,uid))`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, owner_uid TEXT NOT NULL, label TEXT NOT NULL, android_version TEXT NOT NULL, active INTEGER NOT NULL, consent_version TEXT NOT NULL, created_at INTEGER NOT NULL)`);
    this.sql.exec(`CREATE INDEX IF NOT EXISTS device_owner ON devices(owner_uid, active)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS rate_limits (key TEXT PRIMARY KEY, window INTEGER NOT NULL, count INTEGER NOT NULL)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS registry_audit (id TEXT PRIMARY KEY, at INTEGER NOT NULL, actor_uid TEXT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL)`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS consent_revocations (device_id TEXT NOT NULL, owner_uid TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(device_id,owner_uid))`);
  }
  one(sql, ...args) { return this.sql.exec(sql, ...args).toArray()[0] || null; }
  audit(actor, action, target) { this.sql.exec("INSERT INTO registry_audit VALUES (?,?,?,?,?)", crypto.randomUUID(), Date.now(), actor.uid, action, target); }
  rate(key, limit) {
    const window = Math.floor(Date.now() / 60000); const old = this.one("SELECT * FROM rate_limits WHERE key=?", key);
    const count = old?.window === window ? old.count + 1 : 1;
    if (count > limit) fail(429, "Research rate limit exceeded.", { retryAfterSeconds: 60 });
    this.sql.exec("INSERT INTO rate_limits VALUES (?,?,?) ON CONFLICT(key) DO UPDATE SET window=excluded.window,count=excluded.count", key, window, count);
    // Bound persisted rate keys by removing old windows, without discarding journals.
    this.sql.exec("DELETE FROM rate_limits WHERE window < ?", window - 60);
  }
  session(id, actor) {
    const session = this.one("SELECT * FROM sessions WHERE id=? AND active=1", id);
    if (!session || session.organizer_uid !== actor.uid || !organizer(actor, this.env)) fail(403, "Active lab organizer access required.");
    return session;
  }
  authorize(actor, deviceId, action = "read") {
    const d = this.one("SELECT d.*,s.organizer_uid,s.active AS session_active FROM devices d JOIN sessions s ON s.id=d.session_id WHERE d.id=?", deviceId);
    if (!d || !d.active || !d.session_active) fail(403, "No active device assignment.");
    let role = null; let grantRevision = null;
    if (d.owner_uid === actor.uid) { role = "owner"; grantRevision = `owner:${d.id}:${d.created_at}`; }
    else if (d.organizer_uid === actor.uid && organizer(actor, this.env)) { role = "organizer"; grantRevision = `organizer:${d.session_id}`; }
    else { const grant = this.one("SELECT role,grant_revision FROM assignments WHERE session_id=? AND uid=?", d.session_id, actor.uid); role = grant?.role; grantRevision = grant?.grant_revision; }
    if (!role || (["ingest", "heartbeat", "poll", "result", "disconnect"].includes(action) && role !== "owner") || (action === "command" && role === "viewer")) fail(403, "This action is outside your device role.");
    return { deviceId: d.id, ownerUid: d.owner_uid, labSessionId: d.session_id, label: d.label,
      androidVersion: d.android_version, consentVersion: d.consent_version, role, grantRevision, active: true };
  }
  async invitation(sessionId) {
    const enrollmentCode = crypto.randomUUID() + crypto.randomUUID().replaceAll("-", "");
    const expiresAt = Date.now() + 24 * 60 * 60 * 1000;
    this.sql.exec("INSERT INTO invitations VALUES (?,?,?,NULL)", await hash(enrollmentCode), sessionId, expiresAt);
    return { enrollmentCode, expiresAt };
  }
  async fetch(request) {
    try {
      const url = new URL(request.url); const actor = JSON.parse(request.headers.get("X-Research-Actor") || "null");
      if (!actor?.uid || typeof actor.uid !== "string" || actor.uid.length > 128) fail(401, "Authenticated actor required.");
      if (actor.tokenExpiresAt && actor.tokenExpiresAt <= Date.now()) fail(401, "Authentication token expired.");
      const p = url.pathname.split("/").filter(Boolean);
      if (p[0] === "authorize") {
        const body = await readJson(request, 2048);
        return reply(this.authorize(actor, identifier(body.deviceId, "deviceId"), body.action));
      }
      if (p[0] === "grant") {
        const body = await readJson(request, 4096); const deviceId = identifier(body.deviceId, "deviceId");
        this.authorize(actor, deviceId, "poll");
        const requester = { uid: textField(body.requesterUid, "requesterUid", 128), labOrganiser: body.requesterRole === "organizer" };
        const grant = this.authorize(requester, deviceId, "command");
        if (grant.grantRevision !== body.grantRevision) fail(403, "Artifact request assignment was revoked or replaced.");
        return reply({ valid: true });
      }
      if (p[0] === "rate") {
        const body = await readJson(request, 2048);
        this.rate(`${actor.uid}:${textField(body.bucket, "bucket", 160)}`, Math.min(Number(body.limit) || 60, 300));
        return reply({ ok: true });
      }
      this.rate(`${actor.uid}:registry`, 90);
      if (p[0] === "sessions" && p.length === 1 && request.method === "POST") {
        if (!organizer(actor, this.env)) fail(403, "An organizer claim or configured organizer UID is required.");
        const body = await readJson(request, 4096); const id = crypto.randomUUID(); const label = textField(body.label || "Authorized lab", "label", 120);
        this.sql.exec("INSERT INTO sessions VALUES (?,?,?,1,?)", id, label, actor.uid, Date.now());
        this.audit(actor, "session_created", id);
        return reply({ labSessionId: id, label, ...await this.invitation(id) }, 201);
      }
      if (p[0] === "sessions" && p.length === 1 && request.method === "GET") {
        return reply({ sessions: this.sql.exec("SELECT id AS labSessionId,label,active,created_at AS createdAt FROM sessions WHERE organizer_uid=?", actor.uid).toArray() });
      }
      if (p[0] === "sessions" && p[1]) {
        const sessionId = identifier(p[1], "labSessionId"); this.session(sessionId, actor);
        if (p.length === 2 && request.method === "DELETE") {
          this.sql.exec("UPDATE sessions SET active=0 WHERE id=?", sessionId); this.audit(actor, "session_revoked", sessionId);
          return reply({ disconnected: true });
        }
        if (p[2] === "invitations" && p.length === 3 && request.method === "POST") {
          this.audit(actor, "invitation_created", sessionId); return reply(await this.invitation(sessionId), 201);
        }
        if (p[2] === "assignments" && p.length === 3 && request.method === "GET") {
          return reply({ assignments: this.sql.exec("SELECT uid,role FROM assignments WHERE session_id=?", sessionId).toArray() });
        }
        if (p[2] === "assignments" && p.length === 3 && request.method === "POST") {
          const body = await readJson(request, 4096); const uid = textField(body.uid, "uid", 128);
          if (!uid || !["viewer", "researcher"].includes(body.role)) fail(400, "Assignment requires uid and viewer/researcher role.");
          this.sql.exec("INSERT INTO assignments VALUES (?,?,?,?) ON CONFLICT(session_id,uid) DO UPDATE SET role=excluded.role,grant_revision=excluded.grant_revision", sessionId, uid, body.role, crypto.randomUUID());
          this.audit(actor, "assignment_granted", `${sessionId}:${uid}:${body.role}`); return reply({ uid, role: body.role });
        }
        if (p[2] === "assignments" && p[3] && p.length === 4 && request.method === "DELETE") {
          const uid = decodeURIComponent(p[3]); this.sql.exec("DELETE FROM assignments WHERE session_id=? AND uid=?", sessionId, uid);
          this.audit(actor, "assignment_revoked", `${sessionId}:${uid}`); return reply({ revoked: true });
        }
      }
      if (p[0] === "devices" && p[1] === "enroll" && p.length === 2 && request.method === "POST") {
        const body = await readJson(request, 16384); const deviceId = identifier(body.deviceId, "deviceId");
        if (body.consent !== true) fail(400, "Explicit participant consent required.");
        const code = textField(body.enrollmentCode, "enrollmentCode", 160); const digest = await hash(code);
        const invitation = this.one("SELECT i.*,s.active FROM invitations i JOIN sessions s ON s.id=i.session_id WHERE digest=?", digest);
        if (!invitation || !invitation.active || invitation.consumed_at || invitation.expires_at <= Date.now()) fail(403, "Invitation invalid, expired, or already used.");
        if (this.one("SELECT id FROM devices WHERE id=?", deviceId)) fail(409, "Device ID already registered; use a new ID for a new consent session.");
        const activeCount = this.one("SELECT COUNT(*) AS n FROM devices WHERE owner_uid=? AND active=1", actor.uid).n;
        if (activeCount >= 10) fail(409, "Active device limit reached.");
        const label = textField(body.label || deviceId, "label", 120); const androidVersion = textField(String(body.androidVersion || "unknown"), "androidVersion", 40);
        this.state.storage.transactionSync(() => {
          if (this.one("SELECT at FROM consent_revocations WHERE device_id=? AND owner_uid=?", deviceId, actor.uid)) fail(409, "Participant already revoked this pending enrollment; use a new device ID.");
          this.sql.exec("INSERT INTO devices VALUES (?,?,?,?,?,1,?,?)", deviceId, invitation.session_id, actor.uid, label, androidVersion, CONSENT_VERSION, Date.now());
          this.sql.exec("UPDATE invitations SET consumed_at=? WHERE digest=?", Date.now(), digest);
          this.audit(actor, "device_enrolled_with_consent", deviceId);
        });
        return reply(this.authorize(actor, deviceId), 201);
      }
      if (p[0] === "devices" && p.length === 1 && request.method === "GET") {
        const candidates = this.sql.exec("SELECT DISTINCT d.id FROM devices d JOIN sessions s ON s.id=d.session_id LEFT JOIN assignments a ON a.session_id=s.id AND a.uid=? WHERE d.active=1 AND s.active=1 AND (d.owner_uid=? OR a.uid=? OR s.organizer_uid=?) LIMIT 100", actor.uid, actor.uid, actor.uid, actor.uid).toArray();
        const devices = [];
        for (const d of candidates) { try { devices.push(this.authorize(actor, d.id)); } catch (_) {} }
        return reply({ devices });
      }
      if (p[0] === "devices" && p[1] && p[2] === "association" && p.length === 3 && request.method === "DELETE") {
        const id = identifier(p[1], "deviceId"); const row = this.one("SELECT owner_uid,active FROM devices WHERE id=?", id);
        if (!row) {
          this.sql.exec("INSERT OR IGNORE INTO consent_revocations VALUES (?,?,?)", id, actor.uid, Date.now());
          return reply({ disconnected: true });
        }
        if (row.owner_uid !== actor.uid) fail(403, "Only the participant owner can revoke device consent.");
        if (row.active) { this.sql.exec("UPDATE devices SET active=0 WHERE id=?", id); this.audit(actor, "device_consent_revoked", id); }
        return reply({ disconnected: true });
      }
      fail(404, "Unknown research registry route.");
    } catch (e) {
      if (e instanceof ResearchError) return reply({ error: e.message, ...e.details }, e.status);
      return reply({ error: "Research registry unavailable." }, 500);
    }
  }
}
