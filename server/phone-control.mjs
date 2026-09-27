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
    keys(v, [...required, 'recording_transfer', 'audio_status', 'audio_ready', 'audio_session', 'sleep_capable', 'sleep_analysis_allowed', 'sleep_session'], required);
    if (!idPattern.test(v.id) || !idPattern.test(v.grant) || typeof v.name !== 'string' || v.name.length > 80 || typeof v.foreground !== 'boolean' || typeof v.audio_allowed !== 'boolean' || !states.includes(v.state) || (v.handled_command !== null && !idPattern.test(v.handled_command))) bad('Invalid phone status');
    if (v.audio_ready !== undefined && typeof v.audio_ready !== 'boolean') bad('Invalid audio readiness');
    if (v.audio_session !== undefined && v.audio_session !== null && (typeof v.audio_session !== 'string' || !idPattern.test(v.audio_session))) bad('Invalid audio session');
    if (v.audio_ready === true && !v.audio_session) bad('Ready session required');
    if (v.audio_ready !== true && v.audio_session != null) bad('Inactive audio session');
    if (v.audio_ready && !v.audio_allowed) bad('Readiness requires audio authorization');
    if (v.audio_status !== undefined && (typeof v.audio_status !== 'string' || v.audio_status.length > 256)) bad('Invalid audio status');
    if(v.sleep_capable!==undefined&&typeof v.sleep_capable!=='boolean')bad('Invalid sleep capability');
    if(v.sleep_analysis_allowed!==undefined&&typeof v.sleep_analysis_allowed!=='boolean')bad('Invalid sleep analysis choice');
    const sleepSession=v.sleep_session??null;
    if(sleepSession!==null){keys(sleepSession,['id','state','planned_stop_at']);if(!v.sleep_capable||!idPattern.test(sleepSession.id)||!['recording','uploading','analyzing','complete','failed'].includes(sleepSession.state))bad('Invalid sleep status');n(sleepSession.planned_stop_at,0,Date.now()+12*3600000+60000);}
    const transfer = v.recording_transfer ?? null;
    if (transfer !== null) {
      keys(transfer, ['id', 'state', 'from', 'duration_ms', 'pending_count', 'message']);
      if (!idPattern.test(transfer.id) || !['recording','pending','uploading','uploaded','failed','cancelled','interrupted'].includes(transfer.state) || typeof transfer.message !== 'string' || transfer.message.length > 256) bad('Invalid recording transfer');
      n(transfer.from); n(transfer.duration_ms, 0, 3602000); n(transfer.pending_count, 0, v.sleep_capable?365:5);
    }
    const old = await storage.get('phone:' + v.id);
    if (!old && (await storage.list({ prefix: 'phone:' })).size >= 5) bad('Maximum five paired phones', 429);
    // The phone reports execution; a web click alone never means the microphone started.
    const sameGrant = old?.grant === v.grant;
    const sameAudioSession = (old?.audio_session ?? null) === (v.audio_session ?? null);
    const phone = { id: v.id, grant: v.grant, name: v.name, foreground: v.foreground, audio_allowed: v.audio_allowed, state: v.state,
      handled_command: v.handled_command, seen_at: Date.now(), collection_enabled: sameGrant && old.collection_enabled,
      revision: (old?.revision || 0) + (old && (!sameGrant || !sameAudioSession) ? 1 : 0), command: sameGrant && sameAudioSession ? old.command : null,
      recording_transfer: transfer, audio_status: v.audio_status || '', audio_ready: v.audio_ready === true, audio_background_capable: v.audio_ready !== undefined, audio_session: v.audio_session ?? null, sleep_capable:v.sleep_capable===true, sleep_analysis_allowed:v.sleep_capable===true&&v.sleep_analysis_allowed===true, sleep_analysis_epoch:(old?.sleep_analysis_epoch||0)+(old&&(!sameGrant||!!old.sleep_analysis_allowed!==(v.sleep_capable===true&&v.sleep_analysis_allowed===true))?1:0), sleep_session:sleepSession };
    await storage.put('phone:' + phone.id, phone);
    return reply({ ...phone, server_at: Date.now(), background_audio: 1, sleep_audio:1 });
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
    keys(v, ['id', 'action', 'minutes', 'revision', 'start_at', 'stop_at', 'purpose', 'sleep_id', 'replace_command'], ['id', 'action', 'minutes', 'revision']); n(v.revision);
    if (!idPattern.test(v.id) || !['start', 'stop'].includes(v.action)) bad('Invalid recording command');
    const purpose=v.purpose??'audio',isSleep=purpose==='sleep';
    if(!['audio','sleep'].includes(purpose))bad('Invalid recording purpose');
    if(v.sleep_id!==undefined&&!idPattern.test(v.sleep_id))bad('Invalid sleep ID');
    if(v.replace_command!==undefined&&!idPattern.test(v.replace_command))bad('Invalid replaced command');
    if(isSleep&&(!phone.sleep_capable||(v.action==='start'&&!v.sleep_id)))bad('Actualizează și activează Somn și audio pe telefon.',409);
    if(!isSleep&&(v.sleep_id!==undefined||v.replace_command!==undefined))bad('Sleep metadata requires sleep purpose');
    n(v.minutes, 1, isSleep?720:60);
    if (phone.command?.id === v.id) {
      if (phone.command.action !== v.action || phone.command.minutes !== v.minutes || (phone.command.purpose??'audio')!==purpose || (phone.command.sleep_id??null)!==(v.sleep_id??null) || !!phone.command.scheduled!==(v.start_at!==undefined||v.stop_at!==undefined) ||
          (v.start_at !== undefined && (phone.command.start_at !== v.start_at || phone.command.stop_at !== v.stop_at))) bad('Command ID reused with different content', 409);
      return reply(publicPhone(phone));
    }
    if (v.revision !== phone.revision) bad('Starea s-a schimbat. Actualizează panoul.', 409);
    const now = Date.now();
    const scheduled = v.start_at !== undefined || v.stop_at !== undefined;
    let startAt = now, stopAt = now + v.minutes * 60000;
    if (scheduled) {
      n(v.start_at, now - 30000, now + 86400000); n(v.stop_at, v.start_at + 60000, v.start_at + (isSleep?12:1)*3600000);
      if (v.action !== 'start' || Math.ceil((v.stop_at - v.start_at) / 60000) !== v.minutes) bad(isSleep?'Alege un interval de 1 minut–12 ore.':'Interval invalid. Alege între 1 și 60 de minute.');
      startAt = v.start_at; stopAt = v.stop_at;
    }
    if (v.action === 'start') {
      if (!phone.audio_allowed) bad('Autorizează controlul audio o singură dată pe telefon.', 403);
      const ready = phone.audio_background_capable ? phone.audio_ready : phone.foreground;
      if ((phone.audio_background_capable || startAt <= now) && (!ready || now - phone.seen_at >= 25000)) bad('Activează Audio din web pe telefon și așteaptă reconectarea înainte de Start.', 409);
      const pending=phone.command?.action==='start'&&phone.handled_command!==phone.command.id&&now<phone.command.start_before;
      const replacement=isSleep&&v.replace_command===phone.command?.id&&phone.command?.purpose==='sleep'&&pending&&now<phone.command.start_at;
      if(v.replace_command&&!replacement)bad('Programarea s-a schimbat. Actualizează panoul.',409);
      if (phone.state === 'recording' || phone.sleep_session?.state==='recording' || (pending&&!replacement)) bad('Există deja o înregistrare sau o pornire în așteptare.', 409);
    }
    phone.command = { id: v.id, action: v.action, minutes: v.minutes, requested_at: now, start_at: startAt,
      start_before: startAt + 30000, stop_at: stopAt, audio_session: phone.audio_session ?? null, purpose, sleep_id:v.sleep_id??null, chunk_minutes:isSleep?2:null, scheduled };
  }
  phone.revision++;
  await storage.put('phone:' + phone.id, phone);
  return reply(publicPhone(phone));
}
