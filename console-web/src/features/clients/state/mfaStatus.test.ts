import { describe, expect, it } from "vitest";

import type { Client, RealmMfaState } from "../types";
import { clientRequires, clientRequiresValue, effectiveMode, legacyEnforced, mfaApplies, mfaStatusFor, parseMode } from "./mfaStatus";

describe("effectiveMode (MfaPolicy.effectiveMode)", () => {
  it.each([
    ["off", null, "off"],
    ["per_client", null, "per_client"],
    ["all", null, "all"],
    ["  PER_CLIENT ", null, "per_client"],
    ["OFF", "true", "off"],
    ["all", "false", "all"],
  ] as const)("mode %j legacy %j -> %s", (mode, legacy, want) => {
    expect(effectiveMode(mode, legacy)).toBe(want);
  });

  it("an unknown mode fails closed to all", () => {
    expect(effectiveMode("sometimes", "false")).toBe("all");
    expect(effectiveMode("yes", null)).toBe("all");
  });

  it("unset/blank mode falls back to the legacy switch; unset legacy = all", () => {
    expect(effectiveMode(null, null)).toBe("all");
    expect(effectiveMode(undefined, undefined)).toBe("all");
    expect(effectiveMode("", null)).toBe("all");
    expect(effectiveMode("   ", undefined)).toBe("all");
    expect(effectiveMode(null, "false")).toBe("off");
    expect(effectiveMode("", " FALSE ")).toBe("off");
    expect(effectiveMode(null, "true")).toBe("all");
    // Keycloak's reading: any non-"false" legacy value is ON (fail closed).
    expect(effectiveMode(null, "yes")).toBe("all");
    expect(effectiveMode(null, "")).toBe("all");
  });

  it("legacyEnforced: only 'false' is off", () => {
    expect(legacyEnforced(undefined)).toBe(true);
    expect(legacyEnforced(null)).toBe(true);
    expect(legacyEnforced("false")).toBe(false);
    expect(legacyEnforced("False ")).toBe(false);
    expect(legacyEnforced("no")).toBe(true);
  });

  it("parseMode is exact after trim/lower-case", () => {
    expect(parseMode("All")).toBe("all");
    expect(parseMode("per-client")).toBeNull();
    expect(parseMode(null)).toBeNull();
  });
});

describe("client requirement (sso.client.mfa_required)", () => {
  it("unset or blank = required; only 'false' opts out", () => {
    expect(clientRequiresValue(undefined)).toBe(true);
    expect(clientRequiresValue(null)).toBe(true);
    expect(clientRequiresValue("")).toBe(true);
    expect(clientRequiresValue("  ")).toBe(true);
    expect(clientRequiresValue("true")).toBe(true);
    expect(clientRequiresValue("no")).toBe(true);
    expect(clientRequiresValue("0")).toBe(true);
    expect(clientRequiresValue("false")).toBe(false);
    expect(clientRequiresValue(" FALSE ")).toBe(false);
  });

  it("reads the attribute off a client", () => {
    expect(clientRequires({})).toBe(true);
    expect(clientRequires({ attributes: { "sso.client.mfa_required": "false" } })).toBe(false);
  });

  it("mfaApplies by mode", () => {
    expect(mfaApplies("off", undefined)).toBe(false);
    expect(mfaApplies("all", "false")).toBe(true);
    expect(mfaApplies("per_client", undefined)).toBe(true);
    expect(mfaApplies("per_client", "false")).toBe(false);
    expect(mfaApplies(null, "false")).toBe(true);
  });
});

describe("mfaStatusFor", () => {
  const realm = (over: Partial<RealmMfaState> = {}): RealmMfaState => ({
    mode: "all",
    raw_mode: "all",
    enforced: true,
    effective: true,
    raw: null,
    browser_flow: "browser-totp",
    flow_ready: true,
    ...over,
  });
  const client = (over: Partial<Client> = {}): Client => ({ id: "u1", clientId: "minagri-app", standardFlowEnabled: true, ...over });

  it("no user login when neither browser nor password login is on", () => {
    expect(mfaStatusFor(client({ standardFlowEnabled: false, directAccessGrantsEnabled: false }), realm()).kind).toBe("no_user_login");
  });
  it("unknown when the realm state could not be read", () => {
    expect(mfaStatusFor(client(), null).kind).toBe("unknown");
    expect(mfaStatusFor(client(), undefined).kind).toBe("unknown");
  });
  it("bypassed by a browser-flow override", () => {
    expect(mfaStatusFor(client({ authenticationFlowBindingOverrides: { browser: "x" } }), realm()).kind).toBe("bypassed");
  });
  it("not configured when the flow is not bound", () => {
    const s = mfaStatusFor(client(), realm({ flow_ready: false, browser_flow: "browser" }));
    expect(s.kind).toBe("not_configured");
    expect(s.params.flow).toBe("browser");
  });
  it("off realm-wide", () => {
    expect(mfaStatusFor(client(), realm({ mode: "off" })).kind).toBe("off");
  });
  it("per_client: unset = required, false = not required", () => {
    expect(mfaStatusFor(client(), realm({ mode: "per_client" })).kind).toBe("required");
    expect(mfaStatusFor(client({ attributes: { "sso.client.mfa_required": "false" } }), realm({ mode: "per_client" })).kind).toBe("not_required");
  });
  it("all: required even when the client opts out", () => {
    expect(mfaStatusFor(client({ attributes: { "sso.client.mfa_required": "false" } }), realm()).kind).toBe("required");
  });
  it("flags password grants", () => {
    const s = mfaStatusFor(client({ directAccessGrantsEnabled: true }), realm());
    expect(s.passwordGrant).toBe(true);
    expect(s.labelKey).toBe("clients.mfaStatus.required.label");
  });
});
