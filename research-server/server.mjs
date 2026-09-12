import http from 'node:http';
import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';
import { mkdir, writeFile, readFile, readdir, unlink } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

export const MAX_BYTES = 128 * 1024;
export const RETENTION_MS = 24 * 60 * 60 * 1000;
const categories = ['sleep', 'meals', 'activities', 'diagnostics'];
const permissionNames = ['camera', 'microphone', 'fine_location', 'background_location', 'images'];
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const fail = (message, status = 400) => { throw Object.assign(new Error(message), { status }); };
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
function keys(value, allowed, required = allowed) {
  if (!object(value) || Object.keys(value).some(k => !allowed.includes(k)) ||
      required.some(k => !Object.hasOwn(value, k))) fail('Unexpected or missing fields');
}
function number(value, max = 1_000_000, integer = true) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > max ||
      (integer && !Number.isSafeInteger(value))) fail('Invalid numeric value');
}
function times(row) {
  number(row.start_at, 253402300799999); number(row.end_at, 253402300799999);
  if (row.end_at < row.start_at) fail('Invalid time interval');
}
export function validate(payload) {
  keys(payload, ['schema_version', 'run_id', 'source', 'consent', 'data']);
  if (payload.schema_version !== 1 || typeof payload.run_id !== 'string' || !uuid.test(payload.run_id))
    fail('Invalid schema or run ID');
  keys(payload.source, ['app', 'version', 'data_mode']);
  if (payload.source.app !== 'com.forja.app.research' ||
      typeof payload.source.version !== 'string' || !/^[0-9A-Za-z.-]{1,40}$/.test(payload.source.version) ||
      !['synthetic', 'local'].includes(payload.source.data_mode)) fail('Invalid source');
  keys(payload.consent, categories);
  if (categories.some(k => typeof payload.consent[k] !== 'boolean') ||
      !categories.some(k => payload.consent[k])) fail('Select at least one category');
  keys(payload.data, categories, []);
  for (const category of categories) {
    if (Object.hasOwn(payload.data, category) !== payload.consent[category])
      fail('Data must exactly match selected categories');
    if (!payload.consent[category]) continue;
    const value = payload.data[category];
    if (category === 'diagnostics') {
      keys(value, ['sdk_int', 'permissions']); number(value.sdk_int, 1000);
      keys(value.permissions, permissionNames);
      if (permissionNames.some(k => typeof value.permissions[k] !== 'boolean')) fail('Invalid permission state');
      continue;
    }
    if (!Array.isArray(value) || value.length > 50) fail('At most 50 rows per category');
    for (const row of value) {
      if (category === 'sleep') {
        keys(row, ['start_at', 'end_at', 'score', 'movements', 'deep_min', 'light_min', 'rem_min', 'event_count']);
        times(row); number(row.score, 100);
        for (const k of ['movements', 'deep_min', 'light_min', 'rem_min', 'event_count']) number(row[k]);
      } else if (category === 'meals') {
        keys(row, ['recorded_at', 'meal_type', 'kcal', 'protein_g', 'carbs_g', 'fat_g', 'grams']);
        number(row.recorded_at, 253402300799999); number(row.meal_type, 3);
        for (const k of ['kcal', 'protein_g', 'carbs_g', 'fat_g', 'grams']) number(row[k]);
      } else {
        keys(row, ['start_at', 'end_at', 'type', 'distance_m', 'duration_s', 'kcal']);
        times(row);
        if (!['walk', 'run', 'ride'].includes(row.type)) fail('Invalid activity type');
        number(row.distance_m, 100_000_000, false); number(row.duration_s, 100_000_000); number(row.kcal);
      }
    }
  }
  return Object.fromEntries(categories.filter(k => payload.consent[k])
    .map(k => [k, Array.isArray(payload.data[k]) ? payload.data[k].length : 1]));
}

export async function createResearchServer({ token, dataDir, now = Date.now }) {
  if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{32,256}$/.test(token))
    throw new Error('Set FORJA_RESEARCH_TOKEN to a random 32–256 character URL-safe token');
  if (!dataDir) throw new Error('dataDir is required');
  await mkdir(dataDir, { recursive: true, mode: 0o700 });
  const expected = createHash('sha256').update(`Bearer ${token}`).digest();
  const fileFor = id => join(dataDir, `${id}.json`);
  async function purge() {
    for (const name of await readdir(dataDir)) {
      if (!name.endsWith('.json') || !uuid.test(name.slice(0, -5))) continue;
      try {
        const record = JSON.parse(await readFile(join(dataDir, name), 'utf8'));
        if (record.expires_at <= now()) await unlink(join(dataDir, name));
      } catch (e) { if (e.code !== 'ENOENT') throw e; }
    }
  }
  await purge();
  const timer = setInterval(() => purge().catch(() => {
    console.error('Research retention cleanup failed; check the data directory');
  }), 60_000).unref();
  const server = http.createServer({ requestTimeout: 15_000, headersTimeout: 10_000 }, async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    const json = (status, value) => {
      res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value));
    };
    try {
      if (req.method === 'GET' && req.url === '/health') return json(200, { status: 'ok', schema_version: 1 });
      const supplied = createHash('sha256').update(req.headers.authorization || '').digest();
      if (!timingSafeEqual(expected, supplied)) return json(401, { error: 'Pairing token required' });
      if (req.method === 'POST' && req.url === '/v1/research-export') {
        if (req.headers['content-type']?.split(';')[0].trim() !== 'application/json')
          fail('Content-Type must be application/json', 415);
        if (req.headers['content-encoding']) fail('Compressed bodies are not supported', 415);
        if (Number(req.headers['content-length']) > MAX_BYTES) fail('Payload too large', 413);
        const chunks = []; let size = 0;
        for await (const chunk of req) {
          size += chunk.length;
          if (size > MAX_BYTES) { json(413, { error: 'Payload too large' }); return; }
          chunks.push(chunk);
        }
        const bytes = Buffer.concat(chunks);
        let raw, payload;
        try { raw = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes); payload = JSON.parse(raw); }
        catch { fail('Invalid UTF-8 JSON'); }
        const counts = validate(payload);
        const id = randomUUID(); const received_at = now();
        const receipt = { receipt_id: id, run_id: payload.run_id, sha256: createHash('sha256').update(bytes).digest('hex'),
          bytes: bytes.length, counts, received_at, expires_at: received_at + RETENTION_MS };
        await writeFile(fileFor(id), JSON.stringify({ ...receipt, raw }), { mode: 0o600, flag: 'wx' });
        return json(201, receipt);
      }
      const match = /^\/v1\/research-export\/([^/?]+)$/.exec(req.url || '');
      if (!match || !uuid.test(match[1]) || !['GET', 'DELETE'].includes(req.method)) return json(404, { error: 'Not found' });
      let record;
      try { record = JSON.parse(await readFile(fileFor(match[1]), 'utf8')); }
      catch (e) { if (e.code === 'ENOENT') return json(404, { error: 'Not found' }); throw e; }
      if (record.expires_at <= now()) {
        await unlink(fileFor(match[1])).catch(e => { if (e.code !== 'ENOENT') throw e; });
        return json(404, { error: 'Expired' });
      }
      if (req.method === 'DELETE') {
        await unlink(fileFor(match[1])).catch(e => { if (e.code !== 'ENOENT') throw e; });
        return json(200, { deleted: true, receipt_id: match[1] });
      }
      res.writeHead(200, { 'Content-Type': 'application/json', 'X-Payload-SHA256': record.sha256 });
      res.end(record.raw);
    } catch (e) {
      // Do not log request bodies, health records, or credentials.
      if (!res.headersSent) json(e.status || 500, { error: e.status ? e.message : 'Storage or server error' });
      else res.end();
    }
  });
  server.on('close', () => clearInterval(timer));
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const server = await createResearchServer({ token: process.env.FORJA_RESEARCH_TOKEN,
    dataDir: resolve(process.env.FORJA_RESEARCH_DATA_DIR || './data') });
  const host = process.env.FORJA_RESEARCH_HOST || '127.0.0.1';
  const port = Number(process.env.FORJA_RESEARCH_PORT || 8787);
  server.listen(port, host, () => console.log(`FORJA research receiver listening on ${host}:${port}`));
}
