#!/usr/bin/env node
// FORJA 5.1 — `forja`: telefonul tău, din terminal (vezi `forja help`). Logica e în forja-cli-lib.mjs (testată);
// aici doar legăturile cu lumea reală: fișiere, rețea (fetch + WebSocket din Node 22), tastatură, Ctrl-C.
import fs from 'node:fs';
import os from 'node:os';
import readline from 'node:readline';
import { run } from './forja-cli-lib.mjs';

const major = Number(process.versions.node.split('.')[0]);
if (major < 22) { process.stderr.write('forja cere Node 22 sau mai nou (ai ' + process.versions.node + ').\n'); process.exit(1); }

function ask(question) {
  return new Promise(resolve => { const rl = readline.createInterface({ input: process.stdin, output: process.stdout }); rl.question(question, a => { rl.close(); resolve(a.trim()); }); });
}
/** Parola, fără ecou: tastele nu apar pe ecran (și nu rămân în istoricul terminalului). */
function secret(question) {
  return new Promise(resolve => {
    process.stdout.write(question);
    if (!process.stdin.isTTY) { const rl = readline.createInterface({ input: process.stdin }); rl.once('line', l => { rl.close(); resolve(l); }); return; }
    const stdin = process.stdin; let value = '';
    stdin.setRawMode(true); stdin.resume(); stdin.setEncoding('utf8');
    const onData = ch => {
      for (const c of ch) {
        if (c === '\r' || c === '\n') { stdin.setRawMode(false); stdin.pause(); stdin.off('data', onData); process.stdout.write('\n'); resolve(value); return; }
        if (c === '\u0003') { process.stdout.write('\n'); process.exit(130); }
        if (c === '\u007f' || c === '\b') value = value.slice(0, -1); else value += c;
      }
    };
    stdin.on('data', onData);
  });
}
async function* lines(prompt) {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout, prompt, terminal: process.stdin.isTTY });
  rl.prompt();
  for await (const line of rl) { yield line; rl.prompt(); }
}
const controller = new AbortController();
process.on('SIGINT', () => { if (controller.signal.aborted) process.exit(130); controller.abort(); setTimeout(() => process.exit(130), 1500).unref(); });

const code = await run(process.argv.slice(2), {
  env: process.env, home: os.homedir(), fs,
  fetch: (url, opts) => fetch(url, opts), WebSocket: globalThis.WebSocket,
  stdout: s => process.stdout.write(s), stderr: s => process.stderr.write(s), stdoutBytes: b => process.stdout.write(Buffer.from(b)),
  ask, secret, lines, sleep: ms => new Promise(r => setTimeout(r, ms)), now: () => Date.now(), signal: controller.signal,
});
process.exit(code);
