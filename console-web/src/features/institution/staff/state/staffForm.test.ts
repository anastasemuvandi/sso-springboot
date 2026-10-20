import { describe, expect, it } from "vitest";

import { ApiError } from "@/api/errors";
import type { Member } from "../../types";
import {
  cleanIdentifier,
  createStaffBody,
  createStaffSchema,
  filterMembers,
  identifierErrorField,
  isValidNid,
  isValidPhone,
  parseStatus,
  roleHolders,
  splitRoles,
  updateStaffBody,
} from "./staffForm";

const base = { username: "aline", email: "aline@minagri.gov.rw", firstName: "Aline", lastName: "K", national_id: "", phone: "" };

describe("identifier shapes (mirror the wrapper's join-key specs)", () => {
  it("NID: exactly 16 digits, separators ignored", () => {
    expect(isValidNid("1199080012345678")).toBe(true);
    expect(isValidNid("1 1990 8 0012345 678")).toBe(true);
    expect(isValidNid("119908001234567")).toBe(false);
    expect(isValidNid("11990800123456789")).toBe(false);
    expect(isValidNid("11990800123456AB")).toBe(false);
  });
  it("phone: 9–15 digits, optional +", () => {
    expect(isValidPhone("+250788123456")).toBe(true);
    expect(isValidPhone("0788 123-456")).toBe(true);
    expect(isValidPhone("12345678")).toBe(false);
    expect(isValidPhone("++250788123456")).toBe(false);
    expect(cleanIdentifier("(078) 812.34/56")).toBe("0788123456");
  });
});

describe("create schema and body", () => {
  it("accepts blanks for the optional identifiers", () => {
    expect(createStaffSchema.safeParse(base).success).toBe(true);
  });
  it("refuses a malformed NID / phone and a short username", () => {
    const r = createStaffSchema.safeParse({ ...base, username: "ab", national_id: "123", phone: "12" });
    expect(r.success).toBe(false);
    const msgs = r.success ? [] : r.error.issues.map((i) => `${i.path[0]}:${i.message}`);
    expect(msgs).toEqual(expect.arrayContaining(["username:usernameShort", "national_id:nid", "phone:phone"]));
  });
  it("body never names an institution; blanks are omitted; created enabled + verified", () => {
    const b = createStaffBody({ ...base, national_id: " 1199080012345678 " });
    expect(b).toEqual({ username: "aline", email: "aline@minagri.gov.rw", firstName: "Aline", lastName: "K", enabled: true, emailVerified: true, national_id: "1199080012345678", phone: undefined });
    expect(b).not.toHaveProperty("institution");
  });
  it("update body omits blank identifiers (never wipes an IPPIS-sourced NID)", () => {
    const b = updateStaffBody({ email: "a@b.rw", firstName: "A", lastName: "B", emailVerified: true, national_id: "", phone: "" });
    expect(b.national_id).toBeUndefined();
    expect(b.phone).toBeUndefined();
  });
});

describe("wrapper identifier errors → field", () => {
  it("reads the attribute the wrapper names", () => {
    expect(identifierErrorField(new ApiError(409, "conflict", "National ID '1199…' is already on another account. … 'national_id' …"))).toBe("national_id");
    expect(identifierErrorField(new ApiError(400, "bad", "Expected 9–15 digits. It is stored as the user attribute 'phone' and a client may authenticate on it"))).toBe("phone");
    expect(identifierErrorField(new ApiError(409, "conflict", "x", { field: "email" }))).toBe("email");
    expect(identifierErrorField(new ApiError(409, "conflict", "User with this username or email already exists"))).toBe("username");
    expect(identifierErrorField(new ApiError(403, "access_denied", "phone"))).toBeNull();
    expect(identifierErrorField(new Error("phone"))).toBeNull();
  });

  it("reads the wrapper's structured details", () => {
    expect(identifierErrorField(new ApiError(409, "identifier_taken", "x", { field: "national_id", column: "nid" }))).toBe("national_id");
    expect(identifierErrorField(new ApiError(400, "identifier_malformed", "x", { field: "phone", column: "phone" }))).toBe("phone");
    expect(
      identifierErrorField(new ApiError(422, "validation_error", "Request validation failed", { fields: [{ field: "nationalId", message: "bad" }] })),
    ).toBe("national_id");
  });
});

const m = (username: string, enabled: boolean, roles: string[] = []): Member => ({ id: username, username, enabled, roles });

describe("list filtering", () => {
  it("status filter + sort by username", () => {
    const list = [m("zed", true), m("amy", false), m("bob", true)];
    expect(filterMembers(list, "all").map((x) => x.username)).toEqual(["amy", "bob", "zed"]);
    expect(filterMembers(list, "enabled").map((x) => x.username)).toEqual(["bob", "zed"]);
    expect(filterMembers(list, "disabled").map((x) => x.username)).toEqual(["amy"]);
    expect(parseStatus("weird")).toBe("all");
    expect(parseStatus("disabled")).toBe("disabled");
  });
});

describe("roles: only inst:<slug>:*", () => {
  const assignable = [
    { id: "1", name: "inst:minagri:hr" },
    { id: "2", name: "inst:minagri:viewer" },
    { id: "3", name: "super_admin" }, // a misconfigured template must still not be offered
    { id: "4", name: "inst:minisante:hr" },
  ];
  it("splits held/available within the namespace, never offering anything else", () => {
    const s = splitRoles("minagri", ["inst:minagri:hr", "inst:minagri:admin", "offline_access", "super_admin"], assignable);
    expect(s.managed.map((r) => r.name)).toEqual(["inst:minagri:hr"]);
    expect(s.available.map((r) => r.name)).toEqual(["inst:minagri:viewer"]);
    expect(s.heldNotAssignable).toEqual(["inst:minagri:admin"]);
    expect(s.outsideCount).toBe(2);
  });
  it("holders map", () => {
    const h = roleHolders([m("a", true, ["inst:minagri:hr"]), m("b", true, ["inst:minagri:hr", "x"])]);
    expect(h?.get("inst:minagri:hr")).toEqual(["a", "b"]);
  });
  it("holders are unknown (null) when the list carries no roles — the wrapper's GET /users", () => {
    expect(roleHolders([{ id: "1", username: "a", enabled: true }])).toBeNull();
    expect(roleHolders([])?.size).toBe(0);
    expect(roleHolders([{ id: "1", username: "a", enabled: true, roles: ["inst:minagri:hr"] }])?.get("inst:minagri:hr")).toEqual(["a"]);
  });
});
