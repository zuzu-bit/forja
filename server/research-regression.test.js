import test from "node:test";
import assert from "node:assert/strict";
import worker from "./worker.js";

async function fetchRoute(path, env = {}, options = {}) {
  const deferred = [];
  const response = await worker.fetch(new Request("https://forja.example" + path, options), env,
    { waitUntil: task => deferred.push(task) });
  await Promise.all(deferred);
  return response;
}

test("existing service metadata and public media listing keep their response contracts", async () => {
  const metadata = await fetchRoute("/");
  assert.equal(metadata.status, 200);
  assert.deepEqual(await metadata.json(), {
    ok: true, service: "forja-api", meals: "banca-de-modele-cloudflare", audio: "indisponibil", records: false,
  });
  const media = await fetchRoute("/media/_list");
  assert.equal(media.status, 200);
  assert.deepEqual(await media.json(), []);
});

test("existing admin shell stays available and private admin data still requires its key", async () => {
  const page = await fetchRoute("/admin");
  assert.equal(page.status, 200);
  assert.match(page.headers.get("content-type"), /text\/html/);
  const privateData = await fetchRoute("/admin/api/overview", { ADMIN_KEY: "test-only-key" });
  assert.equal(privateData.status, 403);
});

test("fitness meal and sleep routes reject missing Firebase tokens before analysis or storage", async () => {
  let analysisCalls = 0;
  const env = { AI: { run() { analysisCalls++; throw new Error("Unauthenticated analysis must not run"); } } };
  for (const path of ["/v1/meal", "/v1/sleep-audio", "/v1/sleep-summary", "/v1/sleep-talk-summary", "/v1/sleep-recording?session=s123"]) {
    assert.equal((await fetchRoute(path, env, { method: "POST", body: "{}" })).status, 401, path);
  }
  assert.equal((await fetchRoute("/v1/sleep-recording?session=s123")).status, 401);
  assert.equal(analysisCalls, 0);
});

test("research entrypoints are separate and public API never accepts a malformed token", async () => {
  const page = await fetchRoute("/research");
  assert.equal(page.status, 200);
  assert.match(page.headers.get("content-security-policy"), /frame-ancestors 'none'/);
  assert.equal((await fetchRoute("/v1/research/devices")).status, 401);
  assert.equal((await fetchRoute("/v1/research/devices", {}, { headers: { Authorization: "Bearer not-a-jwt" } })).status, 401);
});
