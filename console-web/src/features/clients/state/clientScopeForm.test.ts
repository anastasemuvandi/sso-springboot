import { describe, expect, it } from "vitest";

import { ApiError } from "@/api/errors";
import type { ClientScope } from "../types";
import {
  coveredInstitutions,
  formFromScope,
  institutionsLosingAccess,
  parseScopeConflict,
  scopeRequest,
  scopeSaveReducer,
  toggleSelected,
  type ScopeFormValues,
  type ScopeSaveState,
} from "./clientScopeForm";

const scope = (over: Partial<ClientScope>): ClientScope => ({
  client_id: "c",
  kind: "platform",
  owner_institution: null,
  cross_cutting: false,
  all_institutions: false,
  institutions: [],
  inferred: false,
  ...over,
});

describe("formFromScope", () => {
  it("institution", () => {
    expect(formFromScope(scope({ kind: "institution", owner_institution: "minagri" }))).toEqual({ mode: "institution", owner: "minagri", all: false, selected: [] });
  });
  it("cross-cutting, all", () => {
    expect(formFromScope(scope({ kind: "cross-cutting", cross_cutting: true, all_institutions: true, institutions: ["*"] })).all).toBe(true);
  });
  it("cross-cutting, selected", () => {
    expect(formFromScope(scope({ kind: "cross-cutting", cross_cutting: true, institutions: ["minagri", "minisante"] })).selected).toEqual(["minagri", "minisante"]);
  });
  it("platform and missing", () => {
    expect(formFromScope(scope({})).mode).toBe("platform");
    expect(formFromScope(undefined).mode).toBe("institution");
  });
});

describe("scopeRequest", () => {
  const v = (over: Partial<ScopeFormValues>): ScopeFormValues => ({ mode: "institution", owner: "", all: false, selected: [], ...over });
  it("institution needs an owner", () => {
    expect(scopeRequest(v({ owner: "" }))).toEqual({ ok: false, error: "no-owner" });
    expect(scopeRequest(v({ owner: " minagri " }))).toEqual({ ok: true, fields: { owner_institution: "minagri" } });
  });
  it("cross-cutting all = ['*'] (dynamic)", () => {
    expect(scopeRequest(v({ mode: "cross-cutting", all: true }))).toEqual({ ok: true, fields: { cross_cutting: true, institutions: ["*"] } });
  });
  it("cross-cutting with nobody is refused before the wrapper's 400", () => {
    expect(scopeRequest(v({ mode: "cross-cutting", selected: [] }))).toEqual({ ok: false, error: "no-institution" });
    expect(scopeRequest(v({ mode: "cross-cutting", selected: ["  "] }))).toEqual({ ok: false, error: "no-institution" });
  });
  it("cross-cutting selected de-duplicates", () => {
    expect(scopeRequest(v({ mode: "cross-cutting", selected: ["a", "b", "a"] }))).toEqual({ ok: true, fields: { cross_cutting: true, institutions: ["a", "b"] } });
  });
  it("platform submits nothing", () => {
    expect(scopeRequest(v({ mode: "platform", owner: "x" }))).toEqual({ ok: true, fields: {} });
  });
});

describe("removal preview", () => {
  const access = [
    { institution: "minagri", group_assigned: true, users: 0 },
    { institution: "minisante", group_assigned: false, users: 3 },
    { institution: "mineduc", group_assigned: false, users: 0 },
  ];
  const known = ["minagri", "minisante", "mineduc"];

  it("covered set", () => {
    expect([...coveredInstitutions({ mode: "cross-cutting", owner: "", all: true, selected: [] }, known)]).toEqual(known);
    expect([...coveredInstitutions({ mode: "platform", owner: "", all: false, selected: [] }, known)]).toEqual([]);
  });

  it("lists only institutions with a foothold that would lose coverage", () => {
    const losing = institutionsLosingAccess({ mode: "cross-cutting", owner: "", all: false, selected: ["minagri"] }, access, known);
    expect(losing.map((i) => i.institution)).toEqual(["minisante"]);
  });

  it("all institutions loses nobody", () => {
    expect(institutionsLosingAccess({ mode: "cross-cutting", owner: "", all: true, selected: [] }, access, known)).toEqual([]);
  });

  it("toggleSelected", () => {
    const a = toggleSelected({ mode: "cross-cutting", owner: "", all: false, selected: [] }, "x");
    expect(a.selected).toEqual(["x"]);
    expect(toggleSelected(a, "x").selected).toEqual([]);
  });
});

describe("409 confirm flow", () => {
  const conflictErr = new ApiError(409, "scope_narrowing_requires_confirmation", "minisante still has 3 users", {
    removed_institutions: ["minisante"],
    access_at_risk: [{ institution: "minisante", group_assigned: false, users: 3 }],
  });

  it("parses the wrapper's 409 details", () => {
    expect(parseScopeConflict(conflictErr)).toEqual({
      removedInstitutions: ["minisante"],
      accessAtRisk: [{ institution: "minisante", group_assigned: false, users: 3 }],
      message: "minisante still has 3 users",
    });
    expect(parseScopeConflict(new ApiError(400, "x", "y"))).toBeNull();
    expect(parseScopeConflict(new Error("x"))).toBeNull();
  });

  it("submit -> 409 -> confirming -> confirm -> saving(confirm) -> saved", () => {
    let s: ScopeSaveState = { phase: "idle" };
    s = scopeSaveReducer(s, { type: "submit" });
    expect(s).toEqual({ phase: "saving", confirm: false });
    const conflict = parseScopeConflict(conflictErr)!;
    s = scopeSaveReducer(s, { type: "conflict", conflict, fields: { cross_cutting: true, institutions: ["minagri"] } });
    expect(s.phase).toBe("confirming");
    s = scopeSaveReducer(s, { type: "confirm" });
    expect(s).toEqual({ phase: "saving", confirm: true });
    s = scopeSaveReducer(s, { type: "saved", removed: ["minisante"] });
    expect(s).toEqual({ phase: "saved", removed: ["minisante"] });
  });

  it("cancel returns to idle; nothing is sent", () => {
    let s: ScopeSaveState = { phase: "saving", confirm: false };
    s = scopeSaveReducer(s, { type: "conflict", conflict: parseScopeConflict(conflictErr)!, fields: {} });
    s = scopeSaveReducer(s, { type: "cancel" });
    expect(s).toEqual({ phase: "idle" });
  });

  it("a 409 on an already-confirmed save is an error, not another prompt", () => {
    const s = scopeSaveReducer({ phase: "saving", confirm: true }, { type: "conflict", conflict: parseScopeConflict(conflictErr)!, fields: {} });
    expect(s.phase).toBe("error");
  });

  it("confirm/cancel outside confirming are no-ops", () => {
    const idle: ScopeSaveState = { phase: "idle" };
    expect(scopeSaveReducer(idle, { type: "confirm" })).toBe(idle);
    expect(scopeSaveReducer(idle, { type: "cancel" })).toBe(idle);
  });
});
