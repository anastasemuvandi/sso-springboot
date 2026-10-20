import { describe, expect, it } from "vitest";

import { appPartError, buildCreateBody, composeClientId, normaliseApp, parseUriList, postLogoutError, uriError } from "./appCreateForm";

describe("app part (mirrors client_id_for)", () => {
  it.each([
    ["portal", null],
    [" Portal ", null],
    ["e-services-2", null],
    ["", "required"],
    ["   ", "required"],
    ["p", "too_short"],
    ["a".repeat(65), "too_long"],
    ["a".repeat(64), null],
    ["my app", "bad_chars"],
    ["my_app", "bad_chars"],
    ["café", "bad_chars"],
    ["minagri-portal", "has_prefix"],
    ["MINAGRI-portal", "has_prefix"],
    ["minagri", "has_prefix"],
    ["minagriportal", null],
  ] as const)("%j → %s", (raw, want) => {
    expect(appPartError(raw, "minagri")).toBe(want);
  });

  it("normalises and previews the composed client-id", () => {
    expect(normaliseApp("  Portal ")).toBe("portal");
    expect(composeClientId("minagri", " Portal ")).toBe("minagri-portal");
    expect(composeClientId("minagri", "")).toBe("minagri-<app>");
  });
});

describe("redirect URIs", () => {
  it("splits on newlines and commas", () => {
    expect(parseUriList("https://a/cb\n https://b/cb ,https://c/cb\n\n")).toEqual(["https://a/cb", "https://b/cb", "https://c/cb"]);
  });
  it.each([
    ["https://minagri.gov.rw/auth/callback", null],
    ["http://localhost:3000/cb", null],
    ["https://minagri.gov.rw/app/*", null],
    ["/auth/callback", "not_absolute"],
    ["minagri.gov.rw/cb", "not_absolute"],
    ["ftp://x/cb", "bad_scheme"],
    ["https://*.gov.rw/cb", "wildcard_host"],
    ["https://minagri.gov.rw/cb#x", "fragment"],
  ] as const)("%s → %s", (uri, want) => {
    expect(uriError(uri)).toBe(want);
  });
  it("post-logout: ## list, + allowed", () => {
    expect(postLogoutError("+")).toBeNull();
    expect(postLogoutError("https://a/out##+")).toBeNull();
    expect(postLogoutError("https://a/out##nope")).toEqual({ uri: "nope", error: "not_absolute" });
  });
});

describe("buildCreateBody", () => {
  const ok = { app: "Portal", redirectUris: "https://minagri.gov.rw/cb", postLogout: "" };
  it("sends the app part only — no institution, no cross-cutting", () => {
    const r = buildCreateBody(ok, "minagri");
    expect(r).toEqual({ ok: true, clientId: "minagri-portal", body: { app: "portal", redirect_uris: ["https://minagri.gov.rw/cb"] } });
    if (r.ok) expect(Object.keys(r.body).sort()).toEqual(["app", "redirect_uris"]);
  });
  it("includes post-logout when given", () => {
    const r = buildCreateBody({ ...ok, postLogout: " + " }, "minagri");
    expect(r.ok && r.body.post_logout_redirect_uris).toBe("+");
  });
  it("reports the first failing field", () => {
    expect(buildCreateBody({ ...ok, app: "minagri-portal" }, "minagri")).toEqual({ ok: false, field: "app", error: "has_prefix" });
    expect(buildCreateBody({ ...ok, redirectUris: " \n" }, "minagri")).toEqual({ ok: false, field: "redirectUris", error: "required" });
    expect(buildCreateBody({ ...ok, redirectUris: "https://a/cb\n/rel" }, "minagri")).toEqual({ ok: false, field: "redirectUris", error: "not_absolute", uri: "/rel" });
    expect(buildCreateBody({ ...ok, postLogout: "ftp://x" }, "minagri")).toEqual({ ok: false, field: "postLogout", error: "bad_scheme", uri: "ftp://x" });
  });
});
