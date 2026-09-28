// Răspunsul pentru GET /media/<cheie> din R2 (forja-media): tipul după extensie, cache după natura fișierului și
// cereri Range reale (206 + Content-Range), ca ExoPlayer să poată relua sau derula un clip fără să tragă tot fișierul.

const TYPES = { mp4: "video/mp4", png: "image/png", webp: "image/webp", json: "application/json; charset=utf-8", jpg: "image/jpeg", jpeg: "image/jpeg" };

/** Tipul MIME după extensie (implicit JPEG, ca înainte: pozele FLUX nu au altă extensie). */
export function mediaType(key) {
  const ext = String(key).toLowerCase().split(".").pop();
  return TYPES[ext] || "image/jpeg";
}

/** Cache: manifestul se schimbă (rotație lunară, clipuri noi) → 5 min; pozele și clipurile au nume stabile → 7 zile. */
export function mediaCacheControl(key) {
  return String(key).toLowerCase().endsWith(".json") ? "public, max-age=300" : "public, max-age=604800, immutable";
}

/** Opțiunile pentru env.MEDIA.get: cu antet Range, R2 întoarce doar bucata cerută (obj.range spune care). */
export function mediaGetOptions(request) {
  return request.headers.get("range") ? { range: request.headers } : undefined;
}

/**
 * Construiește Response-ul din obiectul R2. Cu Range și obj.range → 206 cu Content-Range/Content-Length; altfel 200.
 * obj.range e {offset, length} sau {suffix} (ultimii N octeți).
 */
export function mediaResponse(obj, key, request) {
  const headers = { "content-type": mediaType(key), "cache-control": mediaCacheControl(key), "accept-ranges": "bytes" };
  const size = Number(obj.size);
  const r = request.headers.get("range") ? obj.range : null;
  if (r && Number.isFinite(size) && size > 0) {
    let start, length;
    if (typeof r.suffix === "number") { length = Math.min(r.suffix, size); start = size - length; }
    else { start = Number(r.offset) || 0; length = typeof r.length === "number" ? r.length : size - start; }
    length = Math.max(0, Math.min(length, size - start));
    if (length > 0) {
      headers["content-range"] = `bytes ${start}-${start + length - 1}/${size}`;
      headers["content-length"] = String(length);
      return new Response(obj.body, { status: 206, headers });
    }
  }
  if (Number.isFinite(size) && size >= 0) headers["content-length"] = String(size);
  return new Response(obj.body, { status: 200, headers });
}
