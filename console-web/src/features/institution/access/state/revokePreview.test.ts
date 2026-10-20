import { describe, expect, it } from "vitest";

import type { RevokeReport } from "../types";
import { canConfirm, clientFirstBody, CONFIRM_PHRASE, MAX_REVOKE_USERS, revocableSelection, summarizeRevoke, userFirstBody } from "./revokePreview";

const report = (over: Partial<RevokeReport> = {}): RevokeReport => ({
  institution: "minagri",
  preview: true,
  all_clients: false,
  clients_considered: ["minagri-portal"],
  users: [
    { user_id: "u1", username: "alice", revoked: ["minagri-portal"], skipped: [] },
    { user_id: "u2", username: "bob", revoked: [], skipped: [{ client_id: "minagri-portal", reason: "not_assigned" }] },
  ],
  total_revocations: 1,
  total_skipped: 1,
  users_affected: 1,
  ...over,
});

describe("revoke request bodies", () => {
  it("user-first with chosen apps: preview then real call", () => {
    expect(userFirstBody({ userIds: ["u1", "u1", " "], clientIds: ["a", "b"], allClients: false, preview: true })).toEqual({
      ok: true,
      body: { user_ids: ["u1"], client_ids: ["a", "b"], preview: true },
    });
    expect(userFirstBody({ userIds: ["u1"], clientIds: ["a"], allClients: false, preview: false })).toEqual({
      ok: true,
      body: { user_ids: ["u1"], client_ids: ["a"] },
    });
  });

  it("refuses empty selections and more than 500 users", () => {
    expect(userFirstBody({ userIds: [], clientIds: ["a"], allClients: false, preview: true })).toEqual({ ok: false, error: "no-users" });
    expect(userFirstBody({ userIds: ["u1"], clientIds: [], allClients: false, preview: true })).toEqual({ ok: false, error: "no-clients" });
    const many = Array.from({ length: MAX_REVOKE_USERS + 1 }, (_, i) => `u${i}`);
    expect(userFirstBody({ userIds: many, clientIds: ["a"], allClients: false, preview: true })).toEqual({ ok: false, error: "too-many-users" });
    expect(userFirstBody({ userIds: many.slice(0, MAX_REVOKE_USERS), clientIds: ["a"], allClients: false, preview: true }).ok).toBe(true);
  });

  it("all clients: the preview never confirms; the real call needs the typed phrase", () => {
    expect(userFirstBody({ userIds: ["u1"], clientIds: [], allClients: true, preview: true })).toEqual({
      ok: true,
      body: { user_ids: ["u1"], all_clients: true, preview: true },
    });
    expect(userFirstBody({ userIds: ["u1"], clientIds: [], allClients: true, preview: false, typedPhrase: "remove all" })).toEqual({ ok: false, error: "phrase" });
    expect(userFirstBody({ userIds: ["u1"], clientIds: [], allClients: true, preview: false, typedPhrase: ` ${CONFIRM_PHRASE} ` })).toEqual({
      ok: true,
      body: { user_ids: ["u1"], all_clients: true, confirm_all_clients: true },
    });
  });

  it("client-first: one app, chosen holders", () => {
    expect(clientFirstBody("minagri-portal", ["u1", "u2"], true)).toEqual({ ok: true, body: { user_ids: ["u1", "u2"], client_ids: ["minagri-portal"], preview: true } });
    expect(clientFirstBody("minagri-portal", ["u1"], false)).toEqual({ ok: true, body: { user_ids: ["u1"], client_ids: ["minagri-portal"] } });
    expect(clientFirstBody("minagri-portal", [], true)).toEqual({ ok: false, error: "no-users" });
    expect(clientFirstBody("", ["u1"], true)).toEqual({ ok: false, error: "no-clients" });
  });

  it("keeps only revocable apps in a selection", () => {
    const held = [
      { client_id: "a", cross_cutting: false, revocable: true, user_ids: [], holders: 1 },
      { client_id: "b", cross_cutting: true, revocable: false, user_ids: [], holders: 1 },
    ];
    expect(revocableSelection(["a", "b", "c"], held)).toEqual(["a"]);
  });
});

describe("revoke confirmation", () => {
  it("summarizes from the server's report and shows skips", () => {
    const s = summarizeRevoke(report());
    expect(s.losing.map((u) => u.username)).toEqual(["alice"]);
    expect(s.untouched.map((u) => u.username)).toEqual(["bob"]);
    expect([s.totalRevocations, s.usersAffected, s.clientsConsidered, s.totalSkipped, s.nothingToDo]).toEqual([1, 1, 1, 1, false]);
  });

  it("nothing to do cannot be confirmed; all-clients needs the phrase", () => {
    expect(canConfirm(report({ total_revocations: 0 }), false, "")).toBe(false);
    expect(canConfirm(null, false, "")).toBe(false);
    expect(canConfirm(report(), false, "")).toBe(true);
    expect(canConfirm(report({ all_clients: true }), true, "")).toBe(false);
    expect(canConfirm(report({ all_clients: true }), true, CONFIRM_PHRASE)).toBe(true);
  });
});
