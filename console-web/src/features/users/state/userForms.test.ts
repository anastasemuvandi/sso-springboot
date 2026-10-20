import { describe, expect, it } from "vitest";

import { createUserSchema, exemptionSchema, fullName, normaliseReason, passwordSchema } from "./userForms";

describe("password policy (length 10, upper, lower, digit)", () => {
  it.each([
    ["Abcdefghi1", true],
    ["Abcdefgh1", false],
    ["abcdefghi1", false],
    ["ABCDEFGHI1", false],
    ["Abcdefghij", false],
  ])("%s -> %s", (pw, ok) => {
    expect(passwordSchema.safeParse(pw).success).toBe(ok);
  });
});

describe("exemption reason (MfaPolicy.validateReason)", () => {
  it("collapses whitespace", () => {
    expect(normaliseReason("  a   b\n c ")).toBe("a b c");
  });
  it("needs 15..500 characters after collapsing", () => {
    expect(exemptionSchema.safeParse({ reason: "too     short  " }).success).toBe(false);
    const ok = exemptionSchema.safeParse({ reason: "  Asked by DG;   no phone until 2027  " });
    expect(ok.success && ok.data.reason).toBe("Asked by DG; no phone until 2027");
    expect(exemptionSchema.safeParse({ reason: "x".repeat(501) }).success).toBe(false);
  });
});

describe("create user", () => {
  const base = {
    username: "jdoe",
    email: "j@risa.gov.rw",
    firstName: "J",
    lastName: "Doe",
    employeeNumber: "",
    password: "Abcdefghi1",
    temporary: true,
    agencyId: "g1",
    clientIds: [],
  };
  it("accepts a complete form", () => {
    expect(createUserSchema.safeParse(base).success).toBe(true);
  });
  it("requires an institution", () => {
    expect(createUserSchema.safeParse({ ...base, agencyId: "" }).success).toBe(false);
  });
  it("fullName", () => {
    expect(fullName({ firstName: "A", lastName: null })).toBe("A");
  });
});
