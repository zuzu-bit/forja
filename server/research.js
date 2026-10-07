import { ResearchError, fail, identifier, reply } from "./research-model.js";
import { researchPage } from "./research-web.js";
import { researchCliModule } from "./research-cli.js";
export { ResearchRegistry } from "./research-registry.js";
export { ResearchDevice } from "./research-device.js";

export async function handleResearch(request, env, authenticate) {
  const url = new URL(request.url);
  try {
    if (url.protocol !== "https:" && !["localhost", "127.0.0.1", "[::1]"].includes(url.hostname)) {
      if (request.method === "GET" && url.pathname.startsWith("/research")) { url.protocol = "https:"; return Response.redirect(url.href, 308); }
      fail(400, "Research API requires TLS.");
    }
    if (request.method === "GET" && url.pathname === "/research") return researchPage(env);
    if (request.method === "GET" && url.pathname === "/research/cli.js") return researchCliModule();
    const actor = await authenticate(request);
    if (!actor) fail(401, "A valid Firebase FORJA token is required.");
    if (!env.RESEARCH_REGISTRY || !env.RESEARCH_DEVICES) fail(503, "Research Durable Object bindings are not configured.");
    const path = url.pathname.slice("/v1/research".length);
    const p = path.split("/").filter(Boolean);
    // Never pass caller-provided internal identity headers through to a Durable Object.
    const headers = new Headers({ "X-Research-Actor": JSON.stringify(actor) });
    for (const name of ["content-type", "content-length"]) if (request.headers.has(name)) headers.set(name, request.headers.get(name));
    const internal = target => new Request(`https://research.internal${target}${url.search}`, {
      method: request.method, headers, ...(request.body ? { body: request.body, duplex: "half" } : {}),
    });
    const registry = env.RESEARCH_REGISTRY.get(env.RESEARCH_REGISTRY.idFromName("registry"));
    const rate = await registry.fetch(new Request("https://research.internal/rate", { method: "POST", headers,
      body: JSON.stringify({ bucket: "public-api", limit: 240 }) }));
    if (!rate.ok) return rate;
    if (p[0] === "sessions" || (p[0] === "devices" && (p.length === 1 || p[1] === "enroll" || p[2] === "association"))) {
      const response = await registry.fetch(internal(path));
      if (p[0] === "devices" && p.length === 1 && request.method === "GET" && response.ok) {
        const body = await response.json();
        const devices = await Promise.all(body.devices.map(async device => {
          const status = await env.RESEARCH_DEVICES.get(env.RESEARCH_DEVICES.idFromName(device.deviceId)).fetch(new Request(`https://research.internal/devices/${device.deviceId}/status`, { headers }));
          if (!status.ok) return null;
          const live = await status.json(); return { ...device, online: live.online, lastSeen: live.lastSeen, state: live.state, lastSequenceNumber: live.lastSequenceNumber };
        }));
        return reply({ devices: devices.filter(Boolean) });
      }
      return response;
    }
    if (p[0] === "devices" && p[1]) {
      const deviceId = identifier(decodeURIComponent(p[1]), "deviceId");
      const permitted = await registry.fetch(new Request("https://research.internal/authorize", { method: "POST", headers,
        body: JSON.stringify({ deviceId, action: "read" }) }));
      if (!permitted.ok) return permitted;
      return env.RESEARCH_DEVICES.get(env.RESEARCH_DEVICES.idFromName(deviceId)).fetch(internal(path));
    }
    fail(404, "Unknown research route.");
  } catch (e) {
    if (e instanceof ResearchError) return reply({ error: e.message, ...e.details }, e.status, e.status === 429 ? { "retry-after": "60" } : {});
    return reply({ error: "Research API unavailable." }, 500);
  }
}
