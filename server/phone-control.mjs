import { bad, keys, n, idPattern } from './phone-schema.mjs';
const reply = data => Response.json(data, { headers: { 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' } });
const states = ['idle', 'recording', 'start_failed', 'stopped'];
const publicPhone = p => ({ ...p, online: Date.now() - p.seen_at < 25000 });

/** The caller is already bound to one verified account. No cross-account device routes. */
export async function handlePhoneControl(request, storage, readJSON) {
  const path = new URL(request.url).pathname;
  if (path === '/internal/phones' && request.method === 'GET') {
    return reply({ phones: [...(await storage.list({ prefix: 'phone:' })).values()].map(publicPhone) });
  }
  if (path === '/internal/phone-sync' && request.method === 'POST') {
    const { value: v } = await readJSON(request, 4096);
    const required = ['id', 'grant', 'name', 'foreground', 'audio_allowed', 'state', 'handled_command'];
    keys(v, [...required, 'recording_transfer', 'audio_status'], required);
    if (!idPattern.test(v.id) || !idPattern.test(v.grant) || typeof v.name !== 'string' || v.name.length > 80 || typeof v.foreground !== 'boolean' || typeof v.audio_allowed !== 'boolean' || !states.includes(v.state) || (v.handled_command !== null && !idPattern.test(v.handled_command))) bad('Invalid phone status');
    if (v.audio_status !== undefined && (typeof v.audio_status !== 'string' || v.audio_status.length > 256)) bad('Invalid audio status');
    const transfer = v.recording_transfer ?? null;
    if (transfer !== null) {
      keys(transfer, ['id', 'state', 'from', 'duration_ms', 'pending_count', 'message']);
      if (!idPattern.test(transfer.id) || !['recording','pending','uploading','uploaded','failed','cancelled','interrupted'].includes(transfer.state) || typeof transfer.message !== 'string' || transfer.message.length > 256) bad('Invalid recording transfer');
      n(transfer.from); n(transfer.duration_ms, 0, 3602000); n(transfer.pending_count, 0, 5);
    }
    const old = await storage.get('phone:' + v.id);
    if (!old && (await storage.list({ prefix: 'phone:' })).size >= 5) bad('Maximum five paired phones', 429);
    // The phone reports execution; a web click alone never means the microphone started.
    const sameGrant = old?.grant === v.grant;
    const phone = { id: v.id, grant: v.grant, name: v.name, foreground: v.foreground, audio_allowed: v.audio_allowed, state: v.state,
      handled_command: v.handled_command, seen_at: Date.now(), collection_enabled: sameGrant && old.collection_enabled,
      revision: (old?.revision || 0) + (old && !sameGrant ? 1 : 0), command: sameGrant ? old.command : null,
      recording_transfer: transfer, audio_status: v.audio_status || '' };
    await storage.put('phone:' + phone.id, phone);
    return reply({ ...phone, server_at: Date.now() });
  }
  const match = /^\/internal\/phones\/([0-9a-f-]+)\/(command|collection)$/.exec(path);
  if (!match || request.method !== 'POST') return null;
  if (!idPattern.test(match[1])) bad('Invalid device');
  const phone = await storage.get('phone:' + match[1]); if (!phone) bad('Telefon neasociat acestui cont.', 404);
  const { value: v } = await readJSON(request, 2048);
  if (match[2] === 'collection') {
    keys(v, ['enabled', 'revision']); n(v.revision);
    if (typeof v.enabled !== 'boolean') bad('Invalid collection state');
    if (v.revision !== phone.revision) bad('Starea s-a schimbat. Actualizează panoul.', 409);
    phone.collection_enabled = v.enabled;
  } else {
    keys(v, ['id', 'action', 'minutes', 'revision', 'start_at', 'stop_at'], ['id', 'action', 'minutes', 'revision']); n(v.revision);
    if (!idPattern.test(v.id) || !['start', 'stop'].includes(v.action)) bad('Invalid recording command');
    n(v.minutes, 1, 60);
    if (phone.command?.id === v.id) {
      if (phone.command.action !== v.action || phone.command.minutes !== v.minutes ||
          (v.start_at !== undefined && (phone.command.start_at !== v.start_at || phone.command.stop_at !== v.stop_at))) bad('Command ID reused with different content', 409);
      return reply(publicPhone(phone));
    }
    if (v.revision !== phone.revision) bad('Starea s-a schimbat. Actualizează panoul.', 409);
    const now = Date.now();
    const scheduled = v.start_at !== undefined || v.stop_at !== undefined;
    let startAt = now, stopAt = now + v.minutes * 60000;
    if (scheduled) {
      n(v.start_at, now - 30000, now + 86400000); n(v.stop_at, v.start_at + 60000, v.start_at + 3600000);
      if (v.action !== 'start' || Math.ceil((v.stop_at - v.start_at) / 60000) !== v.minutes) bad('Interval invalid. Alege între 1 și 60 de minute.');
      startAt = v.start_at; stopAt = v.stop_at;
    }
    if (v.action === 'start') {
      if (!phone.audio_allowed) bad('Autorizează controlul audio o singură dată pe telefon.', 403);
      if (startAt <= now && (!phone.foreground || now - phone.seen_at > 25000)) bad('Deschide FORJA pe telefon pentru a începe înregistrarea.', 409);
      if (phone.state === 'recording' || (phone.command?.action === 'start' && phone.handled_command !== phone.command.id && now < phone.command.start_before)) bad('Există deja o înregistrare sau o pornire în așteptare.', 409);
    }
    phone.command = { id: v.id, action: v.action, minutes: v.minutes, requested_at: now, start_at: startAt,
      start_before: startAt + 30000, stop_at: stopAt };
  }
  phone.revision++;
  await storage.put('phone:' + phone.id, phone);
  return reply(publicPhone(phone));
}
