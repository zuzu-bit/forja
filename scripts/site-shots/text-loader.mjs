// Node module hook: lets the harness import server/insights-worker.mjs as-is. Wrangler bundles *.txt / *.html imports
// as Text modules (default export = file contents) and *.woff2 as Data modules (default export = ArrayBuffer); this hook
// does the same, so the local server answers every static route (HTML + CSP, the concatenated app.js, MapLibre, fonts,
// pdf.js) with the worker's own code.
import {readFile} from 'node:fs/promises';

export async function load(url, context, next) {
  if (url.startsWith('file:')) {
    const pathname = new URL(url).pathname;
    if (/\.(txt|html)$/.test(pathname)) return {format: 'module', shortCircuit: true, source: 'export default ' + JSON.stringify(await readFile(new URL(url), 'utf8')) + ';'};
    if (/\.woff2$/.test(pathname)) return {format: 'module', shortCircuit: true, source: 'const b = Buffer.from(' + JSON.stringify((await readFile(new URL(url))).toString('base64')) + ", 'base64'); export default b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);"};
  }
  return next(url, context);
}
