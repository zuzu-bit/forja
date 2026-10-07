import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { build } from "esbuild";
import { researchPage } from "./research-web.js";

test("research page isolates authentication and protects the additive console with a per-response CSP nonce", async () => {
  const first = researchPage(), second = researchPage();
  assert.equal(first.headers.get("cache-control"), "no-store");
  assert.equal(first.headers.get("x-frame-options"), "DENY");
  assert.equal(first.headers.get("referrer-policy"), "no-referrer");
  const html = await first.text();
  const nonce = html.match(/<script type="module" nonce="([a-f0-9]+)">/u)?.[1];
  assert.ok(nonce);
  assert.match(first.headers.get("content-security-policy"), new RegExp("'nonce-" + nonce + "'"));
  assert.notEqual(first.headers.get("content-security-policy"), second.headers.get("content-security-policy"));
  assert.match(html, /id="app" class="shell" hidden/u);
  assert.match(html, /import .* from '\/research\/cli\.js'/u);
  assert.doesNotMatch(html, /innerHTML|insertAdjacentHTML|eval\(|new Function\(|localStorage\.|sessionStorage\.|\?token=/u);
  assert.match(html, /Authorization:'Bearer '\+token/u);
});

test("public Firebase config cannot break out of the module script", async () => {
  const html = await researchPage({ FIREBASE_WEB_API_KEY: '</script><script>alert("unsafe")</script>' }).text();
  assert.equal((html.match(/<script /gu) || []).length, 1);
  assert.ok(html.includes('\\u003c/script>'));
});

test("complete inline browser module parses without a bundler", async () => {
  const html = await researchPage().text();
  const script = html.match(/<script type="module" nonce="[^"]+">([\s\S]*?)<\/script>/u)?.[1];
  assert.ok(script);
  const temporary = await mkdtemp(join(tmpdir(), "forja-research-module-"));
  try {
    const path = join(temporary, "console.mjs");
    await writeFile(path, script);
    execFileSync(process.execPath, ["--check", path], { stdio: "pipe" });
  } finally { await rm(temporary, { recursive: true, force: true }); }
});

test("bundled Worker serves a standalone CLI module without compiler-helper dependencies", async () => {
  // Wrangler's keepNames transform used to inject __name into a serialized local closure.
  // Exercise the deployed module boundary, rather than only the original Node functions.
  const compiled = await build({ entryPoints: [new URL("./worker.js", import.meta.url).pathname], bundle: true, write: false, format: "esm", platform: "browser", target: "es2022", keepNames: true });
  const worker = await import("data:text/javascript;base64," + Buffer.from(compiled.outputFiles[0].text).toString("base64"));
  const response = await worker.default.fetch(new Request("https://forja.example/research/cli.js"), {}, { waitUntil() {} });
  assert.equal(response.status, 200);
  const parser = await import("data:text/javascript;base64," + Buffer.from(await response.text()).toString("base64"));
  assert.deepEqual(parser.parseResearchCommand("help"), { action: "help" });
  assert.deepEqual(parser.parseResearchCommand("watch apps"), { action: "watch", source: "APP" });
  assert.equal(parser.formatEvidencePlanner({ provider: "workers-ai", status: "validated" }), "AI · plan validat");
  assert.equal(parser.describeObservationState({ online: true, state: { observationActive: false } }).status, "PAUSED");
  assert.equal(parser.parseResearchCommand("timeline --date 2026-10-04").from, Date.parse("2026-10-04T00:00:00Z"));
  assert.throws(() => parser.parseResearchCommand("shell id"));
});
