/**
 * PKCE (RFC 7636) — S256 only, the only method the wrapper accepts.
 * Uses Web Crypto (`crypto.getRandomValues`, `crypto.subtle.digest`).
 */

const UNRESERVED = /^[A-Za-z0-9\-._~]{43,128}$/;

export function base64UrlEncode(bytes: Uint8Array): string {
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]!);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function randomBytes(length: number): Uint8Array {
  const out = new Uint8Array(length);
  globalThis.crypto.getRandomValues(out);
  return out;
}

/** URL-safe random string with `byteLength` bytes of entropy (state, nonce). */
export function randomString(byteLength = 32): string {
  return base64UrlEncode(randomBytes(byteLength));
}

/** A fresh code_verifier: 32 random octets → 43 chars (RFC 7636 §4.1). `byteLength` 32..96. */
export function generateCodeVerifier(byteLength = 32): string {
  if (byteLength < 32 || byteLength > 96) throw new RangeError("code_verifier needs 32..96 random bytes");
  return base64UrlEncode(randomBytes(byteLength));
}

export function isValidCodeVerifier(v: string): boolean {
  return UNRESERVED.test(v);
}

/** BASE64URL(SHA256(ASCII(code_verifier))) — RFC 7636 §4.2. */
export async function codeChallengeS256(verifier: string): Promise<string> {
  if (!isValidCodeVerifier(verifier)) throw new Error("invalid code_verifier");
  const data = new TextEncoder().encode(verifier);
  const digest = await globalThis.crypto.subtle.digest("SHA-256", data);
  return base64UrlEncode(new Uint8Array(digest));
}

export interface PkcePair {
  codeVerifier: string;
  codeChallenge: string;
  codeChallengeMethod: "S256";
}

export async function createPkcePair(): Promise<PkcePair> {
  const codeVerifier = generateCodeVerifier();
  return { codeVerifier, codeChallenge: await codeChallengeS256(codeVerifier), codeChallengeMethod: "S256" };
}
