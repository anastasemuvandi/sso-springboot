import { describe, expect, it } from "vitest";
import { base64UrlEncode, codeChallengeS256, createPkcePair, generateCodeVerifier, isValidCodeVerifier, randomString } from "../pkce";

// RFC 7636 Appendix B.
const RFC_OCTETS = [
  116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186, 22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5,
  88, 83, 132, 141, 121,
];
const RFC_VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
const RFC_CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

describe("PKCE (RFC 7636)", () => {
  it("base64url-encodes the Appendix B octets to the Appendix B verifier", () => {
    expect(base64UrlEncode(new Uint8Array(RFC_OCTETS))).toBe(RFC_VERIFIER);
  });

  it("derives the Appendix B S256 challenge", async () => {
    await expect(codeChallengeS256(RFC_VERIFIER)).resolves.toBe(RFC_CHALLENGE);
  });

  it("generates verifiers of 43..128 unreserved characters, never repeating", () => {
    const seen = new Set<string>();
    for (let i = 0; i < 50; i++) {
      const v = generateCodeVerifier();
      expect(v).toMatch(/^[A-Za-z0-9\-._~]{43,128}$/);
      expect(v).not.toContain("=");
      seen.add(v);
    }
    expect(seen.size).toBe(50);
    expect(generateCodeVerifier(96)).toHaveLength(128);
    expect(() => generateCodeVerifier(31)).toThrow(RangeError);
  });

  it("refuses an invalid verifier", async () => {
    expect(isValidCodeVerifier("short")).toBe(false);
    await expect(codeChallengeS256("a".repeat(42))).rejects.toThrow();
    await expect(codeChallengeS256("a b".repeat(20))).rejects.toThrow();
  });

  it("pairs are S256 and consistent", async () => {
    const p = await createPkcePair();
    expect(p.codeChallengeMethod).toBe("S256");
    expect(await codeChallengeS256(p.codeVerifier)).toBe(p.codeChallenge);
  });

  it("random strings for state/nonce are url-safe and unique", () => {
    const a = randomString();
    expect(a).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(randomString()).not.toBe(a);
  });
});
