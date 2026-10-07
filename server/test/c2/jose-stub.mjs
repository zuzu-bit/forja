// Stub pentru jose: tokenul "tok_<uid>" → uid = <uid>.
export function createRemoteJWKSet() { return async () => ({ keys: [] }); }
export async function jwtVerify(token) {
  const s = String(token);
  const sub = s.startsWith("tok_") ? s.slice(4) : "TEST_UID";
  return { payload: { sub, aud: "forja-65093" } };
}
