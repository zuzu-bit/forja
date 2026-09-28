// Node module hook: lets the harness import server/insights-worker.mjs as-is. Wrangler bundles *.txt / *.html
// imports as Text modules (default export = file contents); this hook does the same, so the local server answers
// every static route (HTML, CSP headers, the concatenated app.js, map-frame, vendor files) with the worker's own code.
import {readFile} from 'node:fs/promises';

export async function load(url, context, next) {
  if (url.startsWith('file:') && /\.(txt|html)$/.test(new URL(url).pathname)) {
    return {format: 'module', shortCircuit: true, source: 'export default ' + JSON.stringify(await readFile(new URL(url), 'utf8')) + ';'};
  }
  return next(url, context);
}
