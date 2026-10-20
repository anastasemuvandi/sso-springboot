/**
 * Ported from the reference console's tests/access-matrix-state.mjs, case for
 * case, plus the reducer.
 */
import { describe, expect, it } from "vitest";

import * as m from "./matrixState";
import type { MatrixColumn, MatrixRow } from "./matrixState";

const PORTAL: MatrixColumn = { client_id: "minagri-portal", name: "Portal", kind: "owned", type: "A", editable: true };
const PAYROLL: MatrixColumn = { client_id: "national-payroll", name: "Payroll", kind: "shared", type: "C", editable: true };
const READONLY: MatrixColumn = { client_id: "minagri-ro", name: "Read only", kind: "owned", type: "A", editable: false, reason: "view only" };
const COLS = [PORTAL, PAYROLL, READONLY];

const row = (employee_number: string, first: string, cells: Record<string, boolean>, extra: Partial<MatrixRow> = {}): MatrixRow => ({
  employee_number,
  first_name: first,
  last_name: "Test",
  cells: Object.fromEntries(Object.entries(cells).map(([c, v]) => [c, { has_access: v }])),
  ...extra,
});
const ALICE = row("alice", "Alice", { "minagri-portal": true, "national-payroll": false, "minagri-ro": false });
const BOB = row("bob", "Bob", { "minagri-portal": false, "national-payroll": false, "minagri-ro": true });
const PAGE1 = [ALICE, BOB];
const CAROL = row("carol", "Carol", { "minagri-portal": false, "national-payroll": true, "minagri-ro": false });
const NONE = { "minagri-portal": false, "national-payroll": false, "minagri-ro": false };

const revokeState = () => {
  const r = m.switchMode(m.initialState(), "revoke");
  if (r.kind !== "switched") throw new Error("expected a switch");
  return r.state;
};
const modeState = (mode: m.Mode) => (mode === "grant" ? m.initialState() : revokeState());

describe("access matrix state", () => {
  it("grant mode cannot uncheck existing access", () => {
    const s = m.initialState();
    expect(s.mode).toBe("grant");
    const view = m.cellView(s, ALICE, PORTAL, true);
    expect([view.checked, view.enabled]).toEqual([true, false]);
    expect(view.disabledReason).toEqual({ key: "alreadyHas" });
    const after = m.toggleCell(s, ALICE, PORTAL, true);
    expect(after).toBe(s);
    expect(m.pendingCount(after)).toBe(0);
  });

  it("grant mode ticks a no-access cell as pending", () => {
    const s = m.toggleCell(m.initialState(), BOB, PORTAL, true);
    const view = m.cellView(s, BOB, PORTAL, true);
    expect([view.checked, view.enabled, view.pending]).toEqual([true, true, true]);
    expect(m.counter(s)).toEqual({ key: "instAccess.counter.grant", count: 1 });
    expect(m.bulkItems(s)).toEqual([{ employeeNumber: "bob", clientId: "minagri-portal" }]);
    expect(m.pendingCount(m.toggleCell(s, BOB, PORTAL, true))).toBe(0);
  });

  it("revoke mode cannot grant", () => {
    const s = revokeState();
    const view = m.cellView(s, BOB, PORTAL, true);
    expect([view.checked, view.enabled]).toEqual([false, false]);
    expect(view.disabledReason).toEqual({ key: "hasNone" });
    expect(m.toggleCell(s, BOB, PORTAL, true)).toBe(s);
    const picked = m.toggleCell(s, ALICE, PORTAL, true);
    const v = m.cellView(picked, ALICE, PORTAL, true);
    expect([v.checked, v.pending]).toEqual([false, true]);
    expect(m.counter(picked)).toEqual({ key: "instAccess.counter.revoke", count: 1 });
  });

  it("a read-only column and a caller without manage-clients are disabled in both modes", () => {
    for (const mode of ["grant", "revoke"] as const) {
      const s = modeState(mode);
      for (const r of PAGE1) {
        const v = m.cellView(s, r, READONLY, true);
        expect(v.enabled).toBe(false);
        expect(v.disabledReason).toEqual({ text: "view only" });
        const nm = m.cellView(s, r, PORTAL, false);
        expect(nm.enabled).toBe(false);
        expect(nm.disabledReason).toEqual({ key: "noManage" });
      }
    }
    expect(m.cellView(m.initialState(), BOB, { ...READONLY, reason: null }, true).disabledReason).toEqual({ key: "readOnlyColumn" });
  });

  it("switching mode with pending changes needs a decision; it never drops or applies them", () => {
    const s = m.toggleCell(m.initialState(), BOB, PORTAL, true);
    const res = m.switchMode(s, "revoke");
    expect(res).toEqual({ kind: "needs_decision", pending: 1, to: "revoke" });
    expect(m.pendingCount(s)).toBe(1);
    const after = m.switchMode(m.discard(s), "revoke");
    expect(after.kind).toBe("switched");
    if (after.kind === "switched") expect(after.state.mode).toBe("revoke");
    expect(m.switchMode(s, "grant")).toEqual({ kind: "switched", state: s });
  });

  it("column select picks enabled cells only", () => {
    const s = m.setColumn(m.initialState(), PAGE1, PORTAL, true, true);
    expect(m.bulkItems(s)).toEqual([{ employeeNumber: "bob", clientId: "minagri-portal" }]);
    expect(m.columnSelection(s, PAGE1, PORTAL, true)).toBe("all");
    expect(m.pendingCount(m.setColumn(s, PAGE1, PORTAL, false, true))).toBe(0);
    const ro = m.setColumn(m.initialState(), PAGE1, READONLY, true, true);
    expect(m.pendingCount(ro)).toBe(0);
    expect(m.columnSelection(ro, PAGE1, READONLY, true)).toBe("disabled");
    expect(m.columnSelection(m.initialState(), PAGE1, PORTAL, true)).toBe("none");
  });

  it("row select picks enabled cells only, per mode", () => {
    const grant = m.setRow(m.initialState(), ALICE, COLS, true, true);
    expect(m.bulkItems(grant).map((i) => i.clientId)).toEqual(["national-payroll"]);
    const revoke = m.setRow(revokeState(), ALICE, COLS, true, true);
    expect(m.bulkItems(revoke).map((i) => i.clientId)).toEqual(["minagri-portal"]);
    expect(m.rowSelection(revoke, ALICE, COLS, true)).toBe("all");
    const partial = m.toggleCell(m.initialState(), BOB, PORTAL, true);
    expect(m.rowSelection(partial, BOB, COLS, true)).toBe("some");
  });

  it("pending survives paging and is counted across pages", () => {
    let s = m.toggleCell(m.initialState(), BOB, PORTAL, true);
    s = m.refreshed(s);
    s = m.toggleCell(s, CAROL, PORTAL, true);
    expect(m.pendingCount(s)).toBe(2);
    expect(m.cellView(s, BOB, PORTAL, true).pending).toBe(true);
    expect(m.bulkItems(s).map((i) => i.employeeNumber).sort()).toEqual(["bob", "carol"]);
  });

  it("applying a report: applied leaves pending with the new state, rejected stays with its error", () => {
    let s = m.toggleCell(m.initialState(), BOB, PORTAL, true);
    s = m.toggleCell(s, BOB, PAYROLL, true);
    const out = m.applyResults(s, "grant", [
      { employee_number: "bob", client_id: "minagri-portal", status: "applied" },
      { employee_number: "bob", client_id: "national-payroll", status: "rejected", reason: "state_changed", message: "changed since load" },
    ]);
    expect([out.applied, out.rejected, out.failed, out.stateChanged]).toEqual([1, 1, 0, true]);
    expect(m.pendingCount(out.state)).toBe(1);
    const portal = m.cellView(out.state, BOB, PORTAL, true);
    expect([portal.checked, portal.enabled]).toEqual([true, false]);
    const payroll = m.cellView(out.state, BOB, PAYROLL, true);
    expect(payroll.pending).toBe(true);
    expect(payroll.error).toBe("changed since load");
    expect(m.pendingCount(m.refreshed(out.state))).toBe(1);
  });

  it("a failed item stays pending and is marked", () => {
    const picked = m.toggleCell(revokeState(), ALICE, PORTAL, true);
    const out = m.applyResults(picked, "revoke", [
      { employee_number: "alice", client_id: "minagri-portal", status: "failed", reason: "keycloak_error", message: "500" },
    ]);
    expect([out.failed, out.stateChanged]).toEqual([1, false]);
    expect(m.cellView(out.state, ALICE, PORTAL, true).error).toBe("500");
  });

  it("an applied revoke shows the cell as no longer having access", () => {
    const picked = m.toggleCell(revokeState(), ALICE, PORTAL, true);
    const out = m.applyResults(picked, "revoke", [{ employee_number: "alice", client_id: "minagri-portal", status: "applied" }]);
    expect(m.baseAccess(out.state, ALICE, "minagri-portal")).toBe(false);
    expect(m.baseAccess(m.refreshed(out.state), ALICE, "minagri-portal")).toBe(true);
  });

  it("a duplicate rejection does not overwrite its twin's state", () => {
    const s = m.toggleCell(m.initialState(), BOB, PORTAL, true);
    const out = m.applyResults(s, "grant", [{ employee_number: "bob", client_id: "minagri-portal", status: "rejected", reason: "duplicate" }]);
    expect(out.rejected).toBe(1);
    expect(m.cellView(out.state, BOB, PORTAL, true).error).toBeNull();
  });

  it("the revoke confirmation counts per client and names up to ten people", () => {
    let s = revokeState();
    s = m.toggleCell(s, ALICE, PORTAL, true);
    s = m.toggleCell(s, CAROL, PAYROLL, true);
    const many = Array.from({ length: 11 }, (_, i) =>
      row(`u${i}`, `User${String(i).padStart(2, "0")}`, { "minagri-portal": false, "national-payroll": true, "minagri-ro": false }),
    );
    s = m.setColumn(s, many, PAYROLL, true, true);
    const byId = Object.fromEntries(m.revokeSummary(s, COLS).map((x) => [x.client_id, x]));
    expect(byId["minagri-portal"]?.count).toBe(1);
    expect(byId["minagri-portal"]?.names).toEqual(["Alice Test"]);
    expect(byId["national-payroll"]?.count).toBe(12);
    expect(byId["national-payroll"]?.names).toBeNull();
  });

  it("a locked row (account elsewhere / ambiguous) is disabled in both modes, with its reason", () => {
    const ghost = row("E006", "Ghost", NONE, { account: "elsewhere", locked_reason: "not yours" });
    for (const mode of ["grant", "revoke"] as const) {
      const s = modeState(mode);
      const v = m.cellView(s, ghost, PORTAL, true);
      expect([v.enabled, v.disabledReason]).toEqual([false, { text: "not yours" }]);
      expect(m.pendingCount(m.setRow(s, ghost, COLS, true, true))).toBe(0);
    }
  });

  it("a leaver cannot be granted but can be revoked", () => {
    const leo = row("E005", "Leo", { "minagri-portal": false, "national-payroll": true, "minagri-ro": false }, { status: "terminated" });
    const g = m.cellView(m.initialState(), leo, PORTAL, true);
    expect(g.enabled).toBe(false);
    expect(g.disabledReason).toEqual({ key: "leaver" });
    expect(m.cellView(revokeState(), leo, PAYROLL, true).enabled).toBe(true);
  });

  it("an employee with no account is grantable, and a grant that created one is counted", () => {
    const nadia = row("E004", "Nadia", NONE, { account: "none", sub: null });
    const s = m.toggleCell(m.initialState(), nadia, PORTAL, true);
    expect(m.bulkItems(s)).toEqual([{ employeeNumber: "E004", clientId: "minagri-portal" }]);
    const out = m.applyResults(s, "grant", [
      { employee_number: "E004", sub: "new-1", client_id: "minagri-portal", status: "applied", account_created: true },
    ]);
    expect(out.accountsCreated).toBe(1);
    expect(m.cellView(out.state, nadia, PORTAL, true).checked).toBe(true);
  });

  it("the banner says whether new accounts were emailed, and points at the rows when not", () => {
    const nadia = row("E004", "Nadia", NONE, { account: "none", sub: null });
    const ivy = row("E007", "Ivy", NONE, { account: "none", sub: null });
    let s = m.toggleCell(m.initialState(), nadia, PORTAL, true);
    s = m.toggleCell(s, ivy, PORTAL, true);
    const created = (n: string, setup_email: m.SetupEmailStatus): m.BulkResult => ({
      employee_number: n,
      sub: `new-${n}`,
      client_id: "minagri-portal",
      status: "applied",
      account_created: true,
      setup_email,
    });
    const ok = m.applyResults(s, "grant", [created("E004", "sent"), created("E007", "sent")]);
    expect(ok.emailsNotSent).toBe(0);
    expect(m.createdAccountsNote(ok)).toEqual({ kind: "emailed", created: 2 });
    const bad = m.applyResults(s, "grant", [created("E004", "failed"), created("E007", "not_configured")]);
    expect(bad.emailsNotSent).toBe(2);
    expect(m.createdAccountsNote(bad)).toEqual({ kind: "not_sent", created: 2, notSent: 2, all: true });
    const one = m.applyResults(s, "grant", [created("E004", "failed"), created("E007", "sent")]);
    expect(m.createdAccountsNote(one)).toEqual({ kind: "not_sent", created: 2, notSent: 1, all: false });
    expect(m.createdAccountsNote({ accountsCreated: 0, emailsNotSent: 0 })).toEqual({ kind: "none" });
  });

  it("only an account waiting for a password gets an email badge; only the server's flag offers a resend", () => {
    expect(m.setupEmailView(row("E1", "Set", NONE, { account: "existing" }))).toBeNull();
    expect(m.setupEmailView(row("E2", "F", NONE, { awaiting_password: true, setup_email: { status: "failed" }, setup_email_resendable: true }))).toEqual({
      kind: "failed",
      tone: "warn",
      resendable: true,
    });
    expect(
      m.setupEmailView(row("E3", "N", NONE, { awaiting_password: true, setup_email: { status: "not_configured" }, setup_email_resendable: true })),
    ).toMatchObject({ kind: "not_configured", resendable: true });
    expect(m.setupEmailView(row("E4", "S", NONE, { awaiting_password: true, setup_email: { status: "sent" }, setup_email_resendable: false }))).toEqual({
      kind: "sent",
      tone: "ok",
      resendable: false,
    });
    expect(m.setupEmailView(row("E5", "U", NONE, { awaiting_password: true }))).toEqual({ kind: "pending", tone: "ok", resendable: false });
  });

  it("displayName falls back to username, then employee number", () => {
    expect(m.displayName({ employee_number: "E9", first_name: null, last_name: null, username: "u9" })).toBe("u9");
    expect(m.displayName({ employee_number: "E9" })).toBe("E9");
  });
});

describe("matrixReducer", () => {
  it("drives toggle / select / mode / discard / apply / refresh", () => {
    let s = m.matrixReducer(m.initialState(), { type: "toggle", row: BOB, column: PORTAL, canManage: true });
    expect(m.pendingCount(s)).toBe(1);
    // setMode refuses while something is pending.
    expect(m.matrixReducer(s, { type: "setMode", mode: "revoke" })).toBe(s);
    s = m.matrixReducer(s, { type: "setColumn", rows: [CAROL], column: PORTAL, on: true, canManage: true });
    s = m.matrixReducer(s, { type: "setRow", row: BOB, columns: COLS, on: true, canManage: true });
    expect(m.bulkItems(s).length).toBe(3);
    s = m.matrixReducer(s, {
      type: "applied",
      action: "grant",
      results: [{ employee_number: "bob", client_id: "minagri-portal", status: "applied" }],
    });
    expect(m.pendingCount(s)).toBe(2);
    expect(m.baseAccess(s, BOB, "minagri-portal")).toBe(true);
    s = m.matrixReducer(s, { type: "refreshed" });
    expect(s.overrides).toEqual({});
    const switched = m.matrixReducer(s, { type: "discardAndSwitch", mode: "revoke" });
    expect([switched.mode, m.pendingCount(switched)]).toEqual(["revoke", 0]);
    expect(m.matrixReducer(m.matrixReducer(s, { type: "discard" }), { type: "setMode", mode: "revoke" }).mode).toBe("revoke");
  });
});
