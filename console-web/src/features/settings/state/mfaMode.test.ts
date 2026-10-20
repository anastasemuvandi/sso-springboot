import { describe, expect, it } from "vitest";

import { canChangeMfa, describeModeChange, validateExemptionReason } from "./mfaMode";
import { smtpBody, smtpFormFromConfig } from "./smtpForm";

describe("describeModeChange", () => {
  const s = (mode: "off" | "per_client" | "all", flow_ready = true, first_enabled_at: string | null = null) => ({ mode, flow_ready, first_enabled_at });

  it("first time to all bulk-enrols", () => {
    const c = describeModeChange(s("off"), "all");
    expect(c.bulkEnrol).toBe(true);
    expect(c.consequenceKeys).toContain("settings.mfa.consequences.allBulkFirst");
    expect(c.willBeRefused).toBe(false);
  });
  it("to all again does not re-enrol", () => {
    const c = describeModeChange(s("per_client", true, "2026-01-01T00:00:00Z"), "all");
    expect(c.bulkEnrol).toBe(false);
    expect(c.consequenceKeys).toContain("settings.mfa.consequences.allBulkDone");
  });
  it("per_client never bulk-enrols", () => {
    expect(describeModeChange(s("off"), "per_client").bulkEnrol).toBe(false);
  });
  it("anything but off is refused without the browser-totp flow", () => {
    expect(describeModeChange(s("off", false), "all").willBeRefused).toBe(true);
    expect(describeModeChange(s("off", false), "per_client").willBeRefused).toBe(true);
    expect(describeModeChange(s("all", false), "off").willBeRefused).toBe(false);
  });
  it("noop", () => {
    expect(describeModeChange(s("all"), "all").noop).toBe(true);
  });
});

describe("validateExemptionReason (MfaPolicy.validateReason)", () => {
  it("collapses whitespace and enforces 15..500", () => {
    expect(validateExemptionReason("  lost   phone,  ticket 42  ")).toEqual({ ok: true, value: "lost phone, ticket 42" });
    expect(validateExemptionReason("too   short")).toEqual({ ok: false, error: "too_short", length: 9 });
    expect(validateExemptionReason(null)).toMatchObject({ ok: false, error: "too_short" });
    expect(validateExemptionReason("x".repeat(15))).toMatchObject({ ok: true });
    expect(validateExemptionReason("x".repeat(500))).toMatchObject({ ok: true });
    expect(validateExemptionReason("x".repeat(501))).toEqual({ ok: false, error: "too_long", length: 501 });
  });
  it("super admin only (cosmetic)", () => {
    expect(canChangeMfa(["admin"])).toBe(false);
    expect(canChangeMfa(["super_admin"])).toBe(true);
    expect(canChangeMfa(undefined)).toBe(false);
  });
});

describe("smtp form", () => {
  it("reads Keycloak's string map", () => {
    const v = smtpFormFromConfig({ host: "smtp.gov.rw", port: "587", from: "sso@gov.rw", starttls: "true", auth: "true", user: "u", password: "**********" });
    expect(v).toMatchObject({ host: "smtp.gov.rw", port: 587, starttls: true, ssl: false, auth: true, user: "u", password: "" });
    expect(smtpFormFromConfig(null).port).toBe(25);
  });
  it("blank password is kept (omitted); auth off drops credentials", () => {
    const v = smtpFormFromConfig({ host: "h", port: "25", from: "a@b.rw", auth: "true", user: "u" });
    expect("password" in smtpBody(v)).toBe(false);
    expect(smtpBody({ ...v, password: "s3cret" }).password).toBe("s3cret");
    expect(smtpBody({ ...v, auth: false, password: "x" })).toMatchObject({ auth: false, user: undefined });
    expect("password" in smtpBody({ ...v, auth: false, password: "x" })).toBe(false);
    expect(smtpBody(v).fromDisplayName).toBeUndefined();
  });
});
