import { describe, expect, it } from "vitest";

import { isBuiltinRole, ROLE_NAME_PATTERN, roleKind, sortRoles } from "./roles";

describe("realm role rules", () => {
  it("built-ins", () => {
    expect(isBuiltinRole("offline_access")).toBe(true);
    expect(isBuiltinRole("default-roles-anything")).toBe(true);
    expect(isBuiltinRole("auditor")).toBe(false);
  });
  it("kinds", () => {
    expect(roleKind("super_admin")).toBe("platform_admin");
    expect(roleKind("inst:minagri:hr")).toBe("institution");
    expect(roleKind("mfa-exempt")).toBe("mfa_exempt");
    expect(roleKind("uma_authorization")).toBe("builtin");
    expect(roleKind("data_steward")).toBe("custom");
  });
  it("name pattern", () => {
    expect(ROLE_NAME_PATTERN.test("data_steward")).toBe(true);
    expect(ROLE_NAME_PATTERN.test("inst:minagri:viewer")).toBe(true);
    expect(ROLE_NAME_PATTERN.test("Data Steward")).toBe(false);
    expect(ROLE_NAME_PATTERN.test("")).toBe(false);
  });
  it("sorts admin roles first, built-ins last", () => {
    expect(sortRoles([{ name: "offline_access" }, { name: "zeta" }, { name: "admin" }, { name: "inst:a:b" }]).map((r) => r.name)).toEqual([
      "admin",
      "zeta",
      "inst:a:b",
      "offline_access",
    ]);
  });
});
