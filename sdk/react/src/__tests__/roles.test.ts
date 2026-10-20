import { describe, expect, it } from "vitest";
import { hasRole, institutionRoles } from "../roles";

describe("role matching", () => {
  const s = { authenticated: true, roles: ["inst:minagri:staff", "inst:minagri:hr", "admin"], clientRoles: ["viewer"] };
  it("matches exact, wildcard and client roles", () => {
    expect(hasRole(s, "inst:minagri:staff")).toBe(true);
    expect(hasRole(s, "inst:minagri:*")).toBe(true);
    expect(hasRole(s, "inst:minagr:*")).toBe(false);
    expect(hasRole(s, "inst:minisante:*")).toBe(false);
    expect(hasRole(s, "client:viewer")).toBe(true);
    expect(hasRole(s, "client:editor")).toBe(false);
    expect(hasRole({ ...s, authenticated: false }, "admin")).toBe(false);
  });
  it("groups institution roles by slug", () => {
    expect(institutionRoles(s)).toEqual({ minagri: ["staff", "hr"] });
  });
});
