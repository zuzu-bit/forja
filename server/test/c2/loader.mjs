// Test loader: interceptează importul "jose" cu un stub (fără JWKS Google real).
export async function resolve(specifier, context, next) {
  if (specifier === "jose") {
    return { url: new URL("./jose-stub.mjs", import.meta.url).href, shortCircuit: true };
  }
  return next(specifier, context);
}
