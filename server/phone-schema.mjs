// Shared bounded schema for local and online phone-data receivers.
export const TTL = 86400000;
export const idPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export const categories = ['location', 'app_usage', 'files', 'photos', 'audio'];
export const bad = (message, status = 400) => { throw Object.assign(new Error(message), { status }); };
export function keys(v, names, required = names) {
  if (!v || typeof v !== 'object' || Array.isArray(v) || Object.keys(v).some(k => !names.includes(k)) || required.some(k => !Object.hasOwn(v, k))) bad('Unexpected or missing fields');
}
export function n(v, min = 0, max = 253402300799999, integer = true) {
  if (typeof v !== 'number' || !Number.isFinite(v) || v < min || v > max || (integer && !Number.isSafeInteger(v))) bad('Invalid number');
}
function times(v, start, end) { n(v[start]); n(v[end]); if (v[end] < v[start]) bad('Invalid interval'); }
function rows(v, max) { if (!Array.isArray(v) || v.length > max) bad('Too many rows'); }
export function validatePhoneData(data, consent) {
  const expected = [...(consent.location ? ['locations', 'visits'] : []), ...(consent.app_usage ? ['app_usage', 'usage_window'] : [])];
  if (!expected.length) bad('No consent for phone metrics');
  // usage_backfill (opțional, mirror D): zilele încheiate, calculate pe telefon din istoricul Android, o dată pe zi.
  keys(data, [...expected, ...(consent.app_usage ? ['usage_backfill'] : [])], expected);
  if (consent.location) {
    rows(data.locations, 300); rows(data.visits, 300);
    for (const p of data.locations) {
      keys(p, ['at', 'latitude', 'longitude', 'accuracy_m', 'segment']); n(p.at); n(p.segment, 0, 100000);
      n(p.latitude, -90, 90, false); n(p.longitude, -180, 180, false); n(p.accuracy_m, 0, 10000, false);
    }
    for (const p of data.visits) {
      keys(p, ['first_seen', 'last_seen', 'latitude', 'longitude', 'observed_ms', 'samples']);
      times(p, 'first_seen', 'last_seen'); n(p.latitude, -90, 90, false); n(p.longitude, -180, 180, false);
      n(p.observed_ms, 0, p.last_seen - p.first_seen); n(p.samples, 1, 300);
    }
  }
  if (consent.app_usage) {
    keys(data.usage_window, ['from', 'to', 'method']); times(data.usage_window, 'from', 'to');
    if (data.usage_window.to - data.usage_window.from > TTL || data.usage_window.method !== 'activity_events') bad('Invalid usage window');
    rows(data.app_usage, 100);
    for (const p of data.app_usage) {
      keys(p, ['package', 'label', 'foreground_ms', 'opens', 'last_used']);
      if (typeof p.package !== 'string' || !/^[A-Za-z0-9_.]{1,200}$/.test(p.package) ||
          typeof p.label !== 'string' || p.label.length > 200) bad('Invalid app name');
      n(p.foreground_ms, 0, data.usage_window.to - data.usage_window.from); n(p.opens, 0, 100000);
      n(p.last_used, data.usage_window.from, data.usage_window.to);
    }
    if (data.usage_backfill !== undefined) validateUsageBackfill(data.usage_backfill);
  }
}
/** Cel mult 8 zile încheiate × 40 de aplicații, cu orele (24, ms, fără FORJA) și prima / ultima folosire a zilei. */
export const BACKFILL_RULES = Object.freeze({ days: 8, apps: 40, day_ms: 25 * 3600000 });
function validateUsageBackfill(days) {
  rows(days, BACKFILL_RULES.days);
  for (const d of days) {
    keys(d, ['date', 'first_at', 'last_at', 'hours', 'apps']);
    if (typeof d.date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(d.date)) bad('Invalid day');
    n(d.first_at); n(d.last_at);
    if (!Array.isArray(d.hours) || d.hours.length !== 24) bad('Invalid hours');
    for (const x of d.hours) n(x, 0, 2 * 3600000);
    rows(d.apps, BACKFILL_RULES.apps);
    for (const p of d.apps) {
      keys(p, ['package', 'label', 'foreground_ms', 'opens']);
      if (typeof p.package !== 'string' || !/^[A-Za-z0-9_.]{1,200}$/.test(p.package) ||
          typeof p.label !== 'string' || p.label.length > 200) bad('Invalid app name');
      n(p.foreground_ms, 0, BACKFILL_RULES.day_ms); n(p.opens, 0, 100000);
    }
  }
}
