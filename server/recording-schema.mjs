import { bad } from './phone-schema.mjs';
export const RECORDING_MAX_BYTES = 30 * 1024 * 1024;
export function checkRecording(b, from, to, now = Date.now()) {
  if (!Number.isSafeInteger(from) || !Number.isSafeInteger(to) || to <= from || to - from > 3602000 || from < now - 7 * 86400000 || to > now + 300000) bad('Invalid recording interval');
  if (b.length < 64 || b.length > RECORDING_MAX_BYTES) bad('Invalid recording size', 413);
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength), text = (a, z) => new TextDecoder().decode(b.subarray(a, z)); let count = 0;
  function boxes(start, end) {
    const out = [];
    while (start < end) {
      if (++count > 2000 || end - start < 8) bad('Invalid M4A boxes');
      let size = v.getUint32(start), header = 8;
      if (size === 1) { if (end - start < 16) bad('Invalid M4A size'); const wide = v.getBigUint64(start + 8); if (wide > BigInt(end - start)) bad('Invalid M4A size'); size = Number(wide); header = 16; }
      else if (!size) size = end - start;
      if (size < header || size > end - start) bad('Invalid M4A size');
      out.push({ type: text(start + 4, start + 8), start: start + header, end: start + size }); start += size;
    }
    return out;
  }
  function one(list, type) { const rows = list.filter(x => x.type === type); if (rows.length !== 1) bad('Missing or duplicate M4A ' + type); return rows[0]; }
  function enough(box, length) { if (box.end - box.start < length) bad('Truncated M4A'); }
  const children = box => boxes(box.start, box.end), top = boxes(0, b.length), brand = one(top, 'ftyp'); enough(brand, 8);
  if (!['M4A ', 'isom', 'mp41', 'mp42'].includes(text(brand.start, brand.start + 4)) || top.some(x => x.type === 'moof')) bad('M4A required');
  if (!top.some(x => x.type === 'mdat' && x.end > x.start)) bad('Empty recording');
  const mdia = children(one(children(one(children(one(top, 'moov')), 'trak')), 'mdia'));
  const handler = one(mdia, 'hdlr'); enough(handler, 12); if (text(handler.start + 8, handler.start + 12) !== 'soun') bad('Audio only');
  const mdhd = one(mdia, 'mdhd'); enough(mdhd, 4); const version = b[mdhd.start];
  if (version !== 0 && version !== 1) bad('Invalid M4A version'); enough(mdhd, version ? 32 : 20);
  const scale = v.getUint32(mdhd.start + (version ? 20 : 12)), ticks = version ? Number(v.getBigUint64(mdhd.start + 24)) : v.getUint32(mdhd.start + 16);
  const duration = Math.round(ticks * 1000 / scale);
  if (!scale || !Number.isSafeInteger(duration) || duration < 500 || duration > 3602000 || Math.abs(duration - (to - from)) > 5000) bad('Recording duration mismatch');
  const minf = children(one(mdia, 'minf')), dref = one(children(one(minf, 'dinf')), 'dref'); enough(dref, 8);
  const refs = boxes(dref.start + 8, dref.end);
  if (v.getUint32(dref.start + 4) !== 1 || refs.length !== 1) bad('External media unsupported');
  enough(refs[0], 4); if (refs[0].type !== 'url ' || refs[0].end - refs[0].start !== 4 || v.getUint32(refs[0].start) !== 1) bad('External media unsupported');
  const stsd = one(children(one(minf, 'stbl')), 'stsd'); enough(stsd, 8); const codecs = boxes(stsd.start + 8, stsd.end);
  if (v.getUint32(stsd.start + 4) !== 1 || codecs.length !== 1 || codecs[0].type !== 'mp4a') bad('AAC required');
  enough(codecs[0], 28); if (v.getUint16(codecs[0].start + 6) !== 1) bad('Invalid reference');
  return duration;
}
