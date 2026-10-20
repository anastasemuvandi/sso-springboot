import { describe, expect, it } from "vitest";
import { buildAuthorizeUrl, buildLogoutUrl, isCallbackUrl, validateIdToken } from "../oidc";
import { safeReturnTo } from "../client";
import { fakeJwt, now } from "./helpers";

describe("wrapper /oauth2 contract", () => {
  it("authorize URL: code + PKCE S256 + state + nonce", () => {
    const url = new URL(
      buildAuthorizeUrl({
        baseUrl: "http://localhost:8000/",
        clientId: "minagri-kubaka-web",
        redirectUri: "http://localhost:5173/callback",
        scopes: ["openid", "profile"],
        state: "S",
        nonce: "N",
        codeChallenge: "C",
        acrValues: "2",
      }),
    );
    expect(url.origin + url.pathname).toBe("http://localhost:8000/oauth2/authorize");
    const q = url.searchParams;
    expect(q.get("response_type")).toBe("code");
    expect(q.get("code_challenge_method")).toBe("S256");
    expect(q.get("code_challenge")).toBe("C");
    expect(q.get("state")).toBe("S");
    expect(q.get("nonce")).toBe("N");
    expect(q.get("scope")).toBe("openid profile");
    expect(q.get("acr_values")).toBe("2");
  });

  it("logout URL always carries client_id", () => {
    const q = new URL(
      buildLogoutUrl({ baseUrl: "http://w", clientId: "c", postLogoutRedirectUri: "http://a/", idTokenHint: "t" }),
    ).searchParams;
    expect(q.get("client_id")).toBe("c");
    expect(q.get("post_logout_redirect_uri")).toBe("http://a/");
    expect(q.get("id_token_hint")).toBe("t");
  });

  it("recognises the callback URL only on the redirect URI", () => {
    const r = "http://localhost:5173/callback";
    expect(isCallbackUrl("http://localhost:5173/callback?code=x&state=y", r)).toBe(true);
    expect(isCallbackUrl("http://localhost:5173/callback?error=access_denied&state=y", r)).toBe(true);
    expect(isCallbackUrl("http://localhost:5173/callback?code=x", r)).toBe(false);
    expect(isCallbackUrl("http://localhost:5173/other?code=x&state=y", r)).toBe(false);
    expect(isCallbackUrl("http://evil:5173/callback?code=x&state=y", r)).toBe(false);
  });

  it("id_token: nonce, aud, exp, iss", () => {
    const good = { nonce: "N", aud: "app", exp: now() + 60, iss: "I" };
    expect(validateIdToken(fakeJwt(good), { nonce: "N", clientId: "app", issuer: "I" }).nonce).toBe("N");
    expect(() => validateIdToken(fakeJwt({ ...good, nonce: "X" }), { nonce: "N", clientId: "app" })).toThrow(/nonce/);
    expect(() => validateIdToken(fakeJwt({ ...good, aud: ["other"] }), { nonce: "N", clientId: "app" })).toThrow(/audience/);
    expect(() => validateIdToken(fakeJwt({ ...good, exp: now() - 3600 }), { nonce: "N", clientId: "app" })).toThrow(/expired/);
    expect(() => validateIdToken(fakeJwt(good), { nonce: "N", clientId: "app", issuer: "other" })).toThrow(/issuer/);
    expect(() => validateIdToken("garbage", { nonce: "N", clientId: "app" })).toThrow();
  });

  it("returnTo is restricted to same-origin paths", () => {
    expect(safeReturnTo("/records?x=1")).toBe("/records?x=1");
    expect(safeReturnTo("https://evil.example/")).toBe("/");
    expect(safeReturnTo("//evil.example/")).toBe("/");
    expect(safeReturnTo("/\\evil.example")).toBe("/");
    expect(safeReturnTo(undefined)).toBe("/");
  });
});
