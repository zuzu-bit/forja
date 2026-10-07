/** Shared, shell-free research command grammar. Kept browser compatible. */
export const SOURCE_ALIASES = Object.freeze({
  apps: "APP", applications: "APP", app: "APP",
  notifications: "NOTIFICATION", notification: "NOTIFICATION",
  media: "MEDIA", location: "LOCATION", network: "NETWORK", bluetooth: "BLUETOOTH",
  activity: "ACTIVITY", fitness: "ACTIVITY", sleep: "SLEEP", nutrition: "NUTRITION",
  contacts: "CONTACT", contact: "CONTACT", files: "FILE", file: "FILE", device: "DEVICE",
});

export function tokenizeCommand(input) {
  if (typeof input !== "string" || input.length > 512) throw new Error("Comanda trebuie să aibă cel mult 512 caractere.");
  // No shell, substitutions, multiline scripts or control characters, even inside quotes.
  if (/[;|&`$<>\u0000-\u001f\u007f]/u.test(input)) throw new Error("Sunt permise numai comenzi de research predefinite; operatorii shell sunt interziși.");
  const tokens = [];
  let token = "", quote = null, started = false;
  for (const char of input.trim()) {
    if (quote) {
      if (char === quote) quote = null;
      else token += char;
      started = true;
    } else if (char === '"' || char === "'") { quote = char; started = true; }
    else if (/\s/u.test(char)) {
      if (started) { tokens.push(token); token = ""; started = false; }
    } else { token += char; started = true; }
  }
  if (quote) throw new Error("Ghilimele neînchise.");
  if (started) tokens.push(token);
  if (!tokens.length) throw new Error("Introdu o comandă. Scrie help pentru exemple.");
  return tokens;
}

export function parseDuration(value, maximum = 30 * 86400000) {
  const match = /^(\d+(?:\.\d+)?)(s|m|h|d)$/u.exec(value || "");
  if (!match) throw new Error("Durată invalidă. Exemple: 30m, 2h, 1d.");
  const milliseconds = Number(match[1]) * { s: 1000, m: 60000, h: 3600000, d: 86400000 }[match[2]];
  if (!Number.isFinite(milliseconds) || milliseconds < 1000 || milliseconds > maximum) throw new Error("Durata trebuie să fie între 1 secundă și 30 de zile.");
  return milliseconds;
}

export function validateDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/u.test(value || "")) throw new Error("Data trebuie să fie YYYY-MM-DD.");
  const epoch = Date.parse(value + "T00:00:00.000Z");
  if (!Number.isFinite(epoch) || new Date(epoch).toISOString().slice(0, 10) !== value) throw new Error("Data nu există în calendar.");
  return epoch;
}

export function parseClock(value, day) {
  if (!/^([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/u.test(value || "")) throw new Error("Ora trebuie să fie HH:mm sau HH:mm:ss, în UTC.");
  const [hour, minute, second = 0] = value.split(":").map(Number);
  return day + hour * 3600000 + minute * 60000 + second * 1000;
}

export function resolveTimeRange(options = {}, now = Date.now()) {
  if (!Number.isFinite(now)) throw new Error("Ceas invalid.");
  const today = new Date(now).toISOString().slice(0, 10);
  if (options.last && (options.date || options.today || options.from || options.to)) throw new Error("--last nu se combină cu --date, --today sau --from/--to.");
  if (options.date && options.today) throw new Error("Alege --date sau --today.");
  if (options.last) return { from: now - parseDuration(options.last), to: now };
  const day = validateDate(options.date || today);
  if (Boolean(options.from) !== Boolean(options.to)) throw new Error("Intervalul necesită atât --from cât și --to.");
  if (options.from) {
    const from = parseClock(options.from, day), to = parseClock(options.to, day);
    if (to <= from) throw new Error("--to trebuie să fie după --from în aceeași zi UTC.");
    return { from, to };
  }
  if (options.date || options.today) return { from: day, to: day + 86400000 - 1 };
  return { from: now - 86400000, to: now };
}

export function parseOptions(tokens, allowed) {
  const result = {};
  for (let i = 0; i < tokens.length; i++) {
    const name = tokens[i].startsWith("--") ? tokens[i].slice(2) : "";
    if (!allowed.includes(name)) throw new Error("Opțiune necunoscută: " + tokens[i]);
    if (Object.hasOwn(result, name)) throw new Error("Opțiune repetată: --" + name);
    if (name === "today") result[name] = true;
    else {
      const value = tokens[++i];
      if (!value || value.startsWith("--")) throw new Error("Lipsește valoarea pentru --" + name);
      result[name] = value;
    }
  }
  return result;
}

export function parseResearchCommand(input, now = Date.now()) {
  const [rawName, ...args] = tokenizeCommand(input);
  const name = rawName.toLowerCase();
  const timeOptions = ["last", "date", "today", "from", "to"];
  if (["help", "devices", "status", "clear"].includes(name)) {
    if (args.length) throw new Error(name + " nu acceptă argumente.");
    return { action: name };
  }
  if (name === "stop" || name === "unwatch") {
    if (args.length && !(args.length === 1 && args[0] === "watch")) throw new Error("Folosește stop sau stop watch.");
    return { action: "stop" };
  }
  if (name === "use") {
    if (args.length !== 1 || !/^[\w.-]{1,128}$/u.test(args[0])) throw new Error("Folosește use <deviceId> din lista devices.");
    return { action: "use", deviceId: args[0] };
  }
  if (name === "watch") {
    const sourceArgument = args[0]?.toLowerCase();
    if (args.length !== 1 || !(sourceArgument === "all" || Object.hasOwn(SOURCE_ALIASES, sourceArgument))) throw new Error("Folosește watch all/apps/notifications/media/location/network/bluetooth/activity/sleep/nutrition/files/device.");
    return { action: "watch", source: sourceArgument === "all" ? "all" : SOURCE_ALIASES[sourceArgument] };
  }
  if (name === "timeline") return { action: "events", source: "all", ...resolveTimeRange(parseOptions(args, timeOptions), now) };
  if (name === "around") {
    if (!args[0]) throw new Error("Folosește around HH:mm --window 5m [--date YYYY-MM-DD].");
    const options = parseOptions(args.slice(1), ["window", "date"]);
    if (!options.window) throw new Error("around necesită --window.");
    const day = validateDate(options.date || new Date(now).toISOString().slice(0, 10));
    const center = parseClock(args[0], day), window = parseDuration(options.window);
    return { action: "events", source: "all", from: center - window, to: center + window };
  }
  if (name === "evidence" || name === "research") {
    const terms = name === "evidence" && args[0] === "search" ? args.slice(1) : name === "research" ? args : [];
    if (!terms.length || terms.some((term) => term.startsWith("--"))) throw new Error('Folosește evidence search "termen". Rezultatele citează evenimentele originale.');
    if (terms.join(" ").length > 300) throw new Error("Întrebarea de research poate avea cel mult 300 caractere.");
    return { action: "evidence", query: terms.join(" ") };
  }
  const source = Object.hasOwn(SOURCE_ALIASES, name) ? SOURCE_ALIASES[name] : null;
  if (!source) throw new Error("Comandă necunoscută: " + name + ". Scrie help.");
  const [operation, ...rest] = args;
  if (operation === "live") {
    if (rest.length) throw new Error(name + " live nu acceptă argumente.");
    return { action: "state", source };
  }
  if (operation === "history" || operation === "list") {
    if (operation === "list" && !["MEDIA", "FILE", "CONTACT"].includes(source)) throw new Error("list este disponibil pentru media, files și contacts.");
    if (operation === "list" && !rest.length && ["MEDIA", "FILE"].includes(source)) return { action: "artifacts", source };
    return { action: "events", source, ...resolveTimeRange(parseOptions(rest, timeOptions), now) };
  }
  if (operation === "search") {
    if (!rest.length || rest.some((term) => term.startsWith("--"))) throw new Error(name + ' search necesită un termen, de exemplu "Bogdan".');
    if (rest.join(" ").length > 160) throw new Error("Termenul de căutare poate avea cel mult 160 caractere.");
    return { action: source === "FILE" || source === "MEDIA" ? "artifacts" : "events", source, query: rest.join(" ") };
  }
  if ((operation === "metadata" || operation === "get") && ["MEDIA", "FILE"].includes(source)) {
    if (rest.length !== 1 || !rest[0] || rest[0].length > 256 || /[\/\\]/u.test(rest[0])) throw new Error("Folosește " + name + " " + operation + " <artifactId sau filename>, fără căi de fișiere.");
    return { action: operation === "get" ? "artifactGet" : "artifactMetadata", source, artifact: rest[0] };
  }
  throw new Error("Subcomandă necunoscută. Folosește " + name + " live/history/search; media/files acceptă metadata/get.");
}

export function parseEventStreamFrame(frame) {
  const result = { event: "message", data: "", id: undefined };
  const data = [];
  for (const line of frame.split(/\r?\n/u)) {
    if (!line || line.startsWith(":")) continue;
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "data") data.push(value);
    else if (field === "event") result.event = value;
    else if (field === "id" && !value.includes("\0")) result.id = value;
  }
  result.data = data.join("\n");
  return result;
}

export function summarizeDeviceEvent(event) {
  const payload = event.payload || {};
  const label = payload.app || payload.appLabel || payload.package || payload.packageName || "";
  if (event.source === "APP") {
    if (event.type === "source_unavailable") return "Observare indisponibilă · " + (payload.reason || "verifică permisiunile");
    const left = event.type === "foreground_left" || payload.foreground === false || payload.foreground === null;
    const transition = left ? "foreground left" : event.type === "foreground_changed" || event.type === "foreground_entered" || payload.foreground === true ? "foreground" : event.type;
    return [label, transition].filter(Boolean).join(" ");
  }
  if (event.source === "NOTIFICATION") return [label, payload.title, payload.text].filter(Boolean).join(" · ");
  if (event.source === "MEDIA" || event.source === "FILE") return [payload.filename || payload.name || payload.displayName, payload.folder || payload.relativePath, payload.mime || payload.mimeType].filter(Boolean).join(" · ");
  if (event.source === "LOCATION" && typeof payload.latitude === "number") return payload.latitude.toFixed(6) + ", " + Number(payload.longitude).toFixed(6) + " · accuracy=" + (payload.accuracy ?? "?") + "m";
  return event.type + " " + JSON.stringify(payload);
}

/** Planner provenance only. Evidence always comes from original device events. */
export function formatEvidencePlanner(planner) {
  if (!planner || typeof planner !== "object") return "Plan verificat de server";
  if (planner.provider === "workers-ai" && planner.status === "validated") return "AI · plan validat";
  const statuses = {
    recognized: "Reguli · întrebare recunoscută",
    unavailable: "Reguli · AI indisponibil",
    model_failed: "Reguli · modelul AI nu a răspuns",
    invalid_model_plan: "Reguli · planul AI nu a trecut validarea",
    timeout: "Reguli · timpul de planificare AI a expirat",
  };
  return Object.hasOwn(statuses, planner.status) ? statuses[planner.status] : "Reguli · plan determinist";
}

/** Connectivity is separate from whether observation was last reported active. */
export function describeObservationState(info = {}) {
  const active = typeof info.state?.observationActive === "boolean" ? info.state.observationActive : null;
  const online = typeof info.online === "boolean" ? info.online : null;
  const status = active === true ? "ACTIVE" : active === false ? "PAUSED" : "UNKNOWN";
  const stale = online !== true || active !== true;
  const notice = online === false ? "Telefon OFFLINE · se afișează ultimele observații sincronizate." :
    active === false ? "Observare PAUSED · se afișează ultimele observații sincronizate." :
    active === null ? "Observare UNKNOWN · starea observării nu a fost raportată." :
    online === true ? "Telefon ONLINE · observare raportată ACTIVE." : "Conectivitate UNKNOWN · ultima stare de observare raportată: ACTIVE.";
  return { status, stale, notice, badge: (online === false ? "LAST REPORT " : "OBSERVATION ") + status };
}

/** The browser imports the very same pure parser tested by Node. */
export function researchCliModule() {
  const helpers = [tokenizeCommand, parseDuration, validateDate, parseClock, resolveTimeRange, parseOptions, parseResearchCommand, parseEventStreamFrame, summarizeDeviceEvent, formatEvidencePlanner, describeObservationState];
  const source = "export const SOURCE_ALIASES = " + JSON.stringify(SOURCE_ALIASES) + ";\n" + helpers.map((fn) => "export " + fn.toString()).join("\n\n");
  // HTML and module must advance together when a deployment adds an exported helper.
  return new Response(source, { headers: { "content-type": "text/javascript; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" } });
}
