// Clasificarea acustică a unui clip WAV scurt (5 s) FĂRĂ model: energie + periodicitatea anvelopei (0,5–2 Hz = ritmul respirației).
// Se folosește când nu există Gemini/Groq: Whisper spune dacă s-a vorbit; de aici vine „sforăit / zgomot / liniște” cu intensitatea.
// Funcții pure, testate; nu decodează m4a (doar PCM 8/16 biți din WAV).

const FRAME_MS = 50;

/** Citește antetul WAV; întoarce {sampleRate, channels, bits, dataOffset, dataLength} sau null. */
export function parseWav(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (u8.length < 44) return null;
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  const tag = (o) => String.fromCharCode(u8[o], u8[o + 1], u8[o + 2], u8[o + 3]);
  if (tag(0) !== "RIFF" || tag(8) !== "WAVE") return null;
  let pos = 12, fmt = null, data = null;
  while (pos + 8 <= u8.length) {
    const id = tag(pos), size = dv.getUint32(pos + 4, true);
    if (id === "fmt ") {
      // Câmpurile fmt (format, canale, rată, biți) ocupă 16 octeți după antet: un fmt trunchiat sau prea scurt e WAV nevalid, nu o excepție.
      if (size < 16 || pos + 24 > u8.length) return null;
      fmt = { format: dv.getUint16(pos + 8, true), channels: dv.getUint16(pos + 10, true), sampleRate: dv.getUint32(pos + 12, true), bits: dv.getUint16(pos + 22, true) };
    }
    if (id === "data") { data = { offset: pos + 8, length: Math.min(size, u8.length - pos - 8) }; break; }
    pos += 8 + size + (size % 2);
  }
  if (!fmt || !data || fmt.format !== 1 || ![8, 16].includes(fmt.bits) || fmt.channels < 1 || fmt.sampleRate < 4000) return null;
  return { sampleRate: fmt.sampleRate, channels: fmt.channels, bits: fmt.bits, dataOffset: data.offset, dataLength: data.length };
}

/** RMS per cadru de 50 ms (0..1), mono (media canalelor). */
export function frameEnergies(bytes, wav) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  const bytesPer = wav.bits / 8, step = bytesPer * wav.channels, frames = [];
  const samplesPerFrame = Math.round((wav.sampleRate * FRAME_MS) / 1000);
  const total = Math.floor(wav.dataLength / step);
  for (let f = 0; f * samplesPerFrame < total; f++) {
    let sum = 0, n = 0;
    for (let i = f * samplesPerFrame; i < Math.min(total, (f + 1) * samplesPerFrame); i++) {
      let s = 0;
      for (let c = 0; c < wav.channels; c++) {
        const o = wav.dataOffset + i * step + c * bytesPer;
        s += wav.bits === 16 ? dv.getInt16(o, true) / 32768 : (u8[o] - 128) / 128;
      }
      s /= wav.channels; sum += s * s; n++;
    }
    frames.push(n ? Math.sqrt(sum / n) : 0);
  }
  return frames;
}

/** Autocorelația normalizată a anvelopei pe decalajele 0,5–2 s: vârful (0..1) și perioada lui în ms. */
export function envelopePeriodicity(frames) {
  const n = frames.length;
  if (n < 40) return { peak: 0, periodMs: 0 };
  const mean = frames.reduce((s, v) => s + v, 0) / n;
  const centered = frames.map((v) => v - mean);
  const denom = centered.reduce((s, v) => s + v * v, 0) || 1e-9;
  let peak = 0, at = 0;
  const minLag = Math.round(500 / FRAME_MS), maxLag = Math.min(Math.round(2000 / FRAME_MS), Math.floor(n / 2));
  for (let lag = minLag; lag <= maxLag; lag++) {
    let acc = 0;
    for (let i = 0; i + lag < n; i++) acc += centered[i] * centered[i + lag];
    const r = acc / denom;
    if (r > peak) { peak = r; at = lag; }
  }
  return { peak: Math.max(0, Math.min(1, peak)), periodMs: at * FRAME_MS };
}

/**
 * Verdictul acustic al unui clip: {type:"snore|noise|silence", intensity:0..1, confidence:0..1, rms, periodicity}.
 * `speech` (de la Whisper) are întâietate: dacă s-a vorbit, tipul e "talk". Pe WAV nevalid → {type:"noise", confidence:0.2}.
 */
export function classifyClip(bytes, { speech = false } = {}) {
  const wav = parseWav(bytes);
  if (!wav) return { type: speech ? "talk" : "noise", intensity: 0, confidence: 0.2, rms: 0, periodicity: 0, decoded: false };
  const frames = frameEnergies(bytes, wav);
  const rms = frames.length ? Math.sqrt(frames.reduce((s, v) => s + v * v, 0) / frames.length) : 0;
  const peakRms = frames.reduce((m, v) => Math.max(m, v), 0);
  const { peak, periodMs } = envelopePeriodicity(frames);
  const intensity = Math.max(0, Math.min(1, Math.round((peakRms / 0.5) * 100) / 100));
  const base = { rms: Math.round(rms * 10000) / 10000, periodicity: Math.round(peak * 100) / 100, periodMs, intensity, decoded: true };
  if (speech) return { type: "talk", confidence: 0.7, ...base };
  if (peakRms < 0.01) return { type: "silence", confidence: 0.8, ...base, intensity: 0 };
  // Sforăit: anvelopă care pulsează regulat la ritmul respirației (0,5–2 Hz), cu vârfuri clar peste fundal.
  const dynamic = peakRms / Math.max(rms, 1e-6);
  if (peak >= 0.45 && dynamic >= 1.6 && peakRms >= 0.03) return { type: "snore", confidence: Math.min(0.9, 0.5 + peak / 2), ...base };
  return { type: "noise", confidence: 0.5, ...base };
}
