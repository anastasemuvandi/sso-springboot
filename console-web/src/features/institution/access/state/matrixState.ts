/**
 * The access matrix's console-side rules — pure, no React, no fetch.
 * Ported rule-for-rule from the reference console (lib/access-matrix-state.ts).
 *
 *   * a cell's displayed state, and whether it is enabled — grant mode only on
 *     cells without access, revoke mode only on cells with access, never on a
 *     read-only column, a locked row, or for a caller without manage-clients;
 *     a leaver may lose access but not be given it;
 *   * clicks build a PENDING set (no write per click), keyed by employee
 *     number + client so it survives paging (rows are employees; some have no
 *     account yet);
 *   * row / column select touch enabled cells only;
 *   * switching mode with anything pending is a decision (apply or discard),
 *     never a silent drop;
 *   * applying a server report moves applied items out of pending and keeps
 *     rejected/failed ones, with their error.
 *
 * The wrapper enforces the same rules again on every item — this module is how
 * the console behaves, not what keeps the boundary.
 *
 * Human text: console-authored reasons are i18n keys (`instAccess.cell.*`);
 * server-provided reasons (`column.reason`, `row.locked_reason`, result
 * messages) are passed through verbatim as `text`.
 */

export type Mode = "grant" | "revoke";

export interface MatrixColumn {
  client_id: string;
  name?: string | null;
  kind: "owned" | "shared";
  /** A = this institution's own, C = cross-cutting shared with it. */
  type: "A" | "C";
  editable: boolean;
  reason?: string | null;
}

export interface MatrixCell {
  has_access: boolean;
  direct?: boolean;
  via_groups?: string[];
}

export type SetupEmailStatus = "sent" | "failed" | "not_configured";

export interface SetupEmail {
  status: SetupEmailStatus;
  via?: string | null;
  at?: string | null;
}

/** One EMPLOYEE (IPPIS mirror), keyed by employee number. */
export interface MatrixRow {
  employee_number: string;
  sub?: string | null;
  username?: string | null;
  first_name?: string | null;
  last_name?: string | null;
  email?: string | null;
  gender?: "M" | "F" | null;
  nid_masked?: string | null;
  has_nid?: boolean;
  status?: "active" | "terminated" | null;
  /** `none`: a grant creates the account; `elsewhere`/`ambiguous` lock the row. */
  account?: "existing" | "none" | "elsewhere" | "ambiguous";
  account_enabled?: boolean | null;
  locked_reason?: string | null;
  awaiting_password?: boolean;
  setup_email?: SetupEmail | null;
  setup_email_resendable?: boolean;
  cells: Record<string, MatrixCell>;
}

export interface PendingEntry {
  employee_number: string;
  client_id: string;
  action: Mode;
  /** Display name, kept so a confirmation can name people on other pages. */
  label: string;
  /** Set when the last Apply did not go through for this item. */
  error?: string | null;
  reason?: string | null;
}

export interface MatrixState {
  mode: Mode;
  pending: Record<string, PendingEntry>;
  /** Access now known after an Apply, until the next fetch replaces the page. */
  overrides: Record<string, boolean>;
}

export interface BulkResult {
  employee_number: string;
  sub?: string | null;
  client_id: string;
  status: "applied" | "rejected" | "failed";
  reason?: string | null;
  message?: string | null;
  account_created?: boolean;
  setup_email?: SetupEmailStatus | "skipped_shared_password" | null;
  setup_email_error?: string | null;
}

/** Why a cell cannot be clicked: an i18n key, or the server's own text. */
export type DisabledKey = "noManage" | "readOnlyColumn" | "leaver" | "alreadyHas" | "hasNone";
export interface DisabledReason {
  key?: DisabledKey;
  text?: string;
}

export interface CellView {
  checked: boolean;
  enabled: boolean;
  pending: boolean;
  disabledReason?: DisabledReason;
  error?: string | null;
}

export function initialState(mode: Mode = "grant"): MatrixState {
  return { mode, pending: {}, overrides: {} };
}

export function key(employeeNumber: string, clientId: string): string {
  return `${employeeNumber}\u0000${clientId}`;
}

export function displayName(row: Pick<MatrixRow, "first_name" | "last_name" | "username" | "employee_number">): string {
  const full = [row.first_name, row.last_name].filter(Boolean).join(" ").trim();
  return full || row.username || row.employee_number;
}

/** A cell's access: an applied change if any, else the server's. */
export function baseAccess(state: MatrixState, row: MatrixRow, clientId: string): boolean {
  const k = key(row.employee_number, clientId);
  if (k in state.overrides) return Boolean(state.overrides[k]);
  return Boolean(row.cells[clientId]?.has_access);
}

export function cellView(state: MatrixState, row: MatrixRow, column: MatrixColumn, canManage: boolean): CellView {
  const has = baseAccess(state, row, column.client_id);
  const entry = state.pending[key(row.employee_number, column.client_id)];
  const pending = Boolean(entry && entry.action === state.mode);
  const checked = state.mode === "grant" ? has || pending : has && !pending;

  if (!canManage) return { checked: has, enabled: false, pending: false, disabledReason: { key: "noManage" } };
  if (!column.editable) {
    return {
      checked: has,
      enabled: false,
      pending: false,
      disabledReason: column.reason ? { text: column.reason } : { key: "readOnlyColumn" },
    };
  }
  if (row.locked_reason) return { checked: has, enabled: false, pending: false, disabledReason: { text: row.locked_reason } };
  if (state.mode === "grant" && !has && row.status === "terminated") {
    return { checked: false, enabled: false, pending: false, disabledReason: { key: "leaver" } };
  }
  if (state.mode === "grant" && has) return { checked: true, enabled: false, pending: false, disabledReason: { key: "alreadyHas" } };
  if (state.mode === "revoke" && !has) return { checked: false, enabled: false, pending: false, disabledReason: { key: "hasNone" } };
  return { checked, enabled: true, pending, error: entry?.error ?? null };
}

function withPending(state: MatrixState, next: Record<string, PendingEntry>): MatrixState {
  return { ...state, pending: next };
}

function entryFor(state: MatrixState, row: MatrixRow, column: MatrixColumn): PendingEntry {
  return { employee_number: row.employee_number, client_id: column.client_id, action: state.mode, label: displayName(row) };
}

/** Toggle one cell's pending change. A disabled cell is a no-op (same object back). */
export function toggleCell(state: MatrixState, row: MatrixRow, column: MatrixColumn, canManage: boolean): MatrixState {
  if (!cellView(state, row, column, canManage).enabled) return state;
  const k = key(row.employee_number, column.client_id);
  const next = { ...state.pending };
  if (next[k]) delete next[k];
  else next[k] = entryFor(state, row, column);
  return withPending(state, next);
}

function setCells(state: MatrixState, pairs: Array<[MatrixRow, MatrixColumn]>, on: boolean, canManage: boolean): MatrixState {
  const next = { ...state.pending };
  for (const [row, column] of pairs) {
    if (!cellView(state, row, column, canManage).enabled) continue;
    const k = key(row.employee_number, column.client_id);
    if (on) next[k] = next[k] ?? entryFor(state, row, column);
    else delete next[k];
  }
  return withPending(state, next);
}

/** Select (on) or clear (off) every ENABLED cell of a column on these rows. */
export function setColumn(state: MatrixState, rows: MatrixRow[], column: MatrixColumn, on: boolean, canManage: boolean): MatrixState {
  return setCells(state, rows.map((r) => [r, column] as [MatrixRow, MatrixColumn]), on, canManage);
}

/** Select (on) or clear (off) every ENABLED cell of a row across these columns. */
export function setRow(state: MatrixState, row: MatrixRow, columns: MatrixColumn[], on: boolean, canManage: boolean): MatrixState {
  return setCells(state, columns.map((c) => [row, c] as [MatrixRow, MatrixColumn]), on, canManage);
}

export type Tri = "all" | "some" | "none" | "disabled";

/** Header/row checkbox state, over the enabled cells only. */
export function selection(state: MatrixState, pairs: Array<[MatrixRow, MatrixColumn]>, canManage: boolean): Tri {
  let enabled = 0;
  let picked = 0;
  for (const [row, column] of pairs) {
    const view = cellView(state, row, column, canManage);
    if (!view.enabled) continue;
    enabled += 1;
    if (view.pending) picked += 1;
  }
  if (enabled === 0) return "disabled";
  if (picked === 0) return "none";
  return picked === enabled ? "all" : "some";
}

export function columnSelection(state: MatrixState, rows: MatrixRow[], column: MatrixColumn, canManage: boolean): Tri {
  return selection(state, rows.map((r) => [r, column] as [MatrixRow, MatrixColumn]), canManage);
}

export function rowSelection(state: MatrixState, row: MatrixRow, columns: MatrixColumn[], canManage: boolean): Tri {
  return selection(state, columns.map((c) => [row, c] as [MatrixRow, MatrixColumn]), canManage);
}

export function pendingCount(state: MatrixState): number {
  return Object.keys(state.pending).length;
}

/** "1 grant pending" / "3 revocations pending": an i18n key + count. */
export function counter(state: MatrixState): { key: "instAccess.counter.grant" | "instAccess.counter.revoke"; count: number } {
  return { key: state.mode === "grant" ? "instAccess.counter.grant" : "instAccess.counter.revoke", count: pendingCount(state) };
}

export type ModeSwitch = { kind: "switched"; state: MatrixState } | { kind: "needs_decision"; pending: number; to: Mode };

/** Ask to change mode. With anything pending this does NOT switch, drop or apply. */
export function switchMode(state: MatrixState, to: Mode): ModeSwitch {
  if (to === state.mode) return { kind: "switched", state };
  const pending = pendingCount(state);
  if (pending > 0) return { kind: "needs_decision", pending, to };
  return { kind: "switched", state: { ...state, mode: to } };
}

export function discard(state: MatrixState): MatrixState {
  return { ...state, pending: {} };
}

/** Body items for POST /grants/bulk. */
export function bulkItems(state: MatrixState): Array<{ employeeNumber: string; clientId: string }> {
  return Object.values(state.pending)
    .filter((p) => p.action === state.mode)
    .map((p) => ({ employeeNumber: p.employee_number, clientId: p.client_id }));
}

export interface ClientSummary {
  client_id: string;
  name: string;
  count: number;
  /** Names of the people, when 10 or fewer for this client. */
  names: string[] | null;
}

/** The revoke confirmation: per client, how many — and who, when it is a few. */
export function revokeSummary(state: MatrixState, columns: MatrixColumn[]): ClientSummary[] {
  const byClient = new Map<string, PendingEntry[]>();
  for (const p of Object.values(state.pending)) {
    if (p.action !== state.mode) continue;
    byClient.set(p.client_id, [...(byClient.get(p.client_id) ?? []), p]);
  }
  const nameOf = (id: string) => columns.find((c) => c.client_id === id)?.name || id;
  return [...byClient.entries()]
    .map(([client_id, entries]) => ({
      client_id,
      name: nameOf(client_id),
      count: entries.length,
      names: entries.length <= 10 ? entries.map((e) => e.label).sort((a, b) => a.localeCompare(b)) : null,
    }))
    .sort((a, b) => a.name.localeCompare(b.name));
}

export interface ApplyOutcome {
  state: MatrixState;
  applied: number;
  rejected: number;
  failed: number;
  /** Any item rejected because someone else changed it — offer a reload. */
  stateChanged: boolean;
  /** Employees who had no account and now have one. */
  accountsCreated: number;
  /** Of those, the ones whose set-password email did not go out. */
  emailsNotSent: number;
}

/**
 * Fold the server's per-item report into the state. Applied items leave
 * pending and their new access becomes an override; rejected and failed items
 * STAY pending, marked with the server's message.
 */
export function applyResults(state: MatrixState, action: Mode, results: BulkResult[]): ApplyOutcome {
  const pending = { ...state.pending };
  const overrides = { ...state.overrides };
  let applied = 0;
  let rejected = 0;
  let failed = 0;
  let stateChanged = false;
  const created = new Set<string>();
  const notSent = new Set<string>();
  for (const r of results) {
    const k = key(r.employee_number, r.client_id);
    if (r.status === "applied") {
      applied += 1;
      if (r.account_created) created.add(r.employee_number);
      if (r.account_created && (r.setup_email === "failed" || r.setup_email === "not_configured")) notSent.add(r.employee_number);
      delete pending[k];
      overrides[k] = action === "grant";
      continue;
    }
    if (r.status === "rejected") rejected += 1;
    else failed += 1;
    if (r.reason === "state_changed") stateChanged = true;
    if (r.reason === "duplicate") continue; // its twin carries the real outcome
    const existing = pending[k];
    if (existing) pending[k] = { ...existing, error: r.message || r.reason || r.status, reason: r.reason ?? null };
  }
  return {
    state: { ...state, pending, overrides },
    applied,
    rejected,
    failed,
    stateChanged,
    accountsCreated: created.size,
    emailsNotSent: notSent.size,
  };
}

/**
 * What the banner says about accounts this Apply created:
 * none / all emailed / some or all not emailed (point at the rows).
 */
export type CreatedNote =
  | { kind: "none" }
  | { kind: "emailed"; created: number }
  | { kind: "not_sent"; created: number; notSent: number; all: boolean };

export function createdAccountsNote(out: Pick<ApplyOutcome, "accountsCreated" | "emailsNotSent">): CreatedNote {
  const n = out.accountsCreated;
  if (n === 0) return { kind: "none" };
  if (out.emailsNotSent === 0) return { kind: "emailed", created: n };
  return { kind: "not_sent", created: n, notSent: out.emailsNotSent, all: out.emailsNotSent === n };
}

export interface SetupEmailView {
  /** i18n suffix under instAccess.setupEmail.* */
  kind: "failed" | "not_configured" | "sent" | "pending";
  tone: "ok" | "warn";
  /** Offer the admin's resend button — the SERVER decides, never inferred from status. */
  resendable: boolean;
}

/** The badge for an account still waiting for its first password, or null. */
export function setupEmailView(row: MatrixRow): SetupEmailView | null {
  if (!row.awaiting_password) return null;
  const status = row.setup_email?.status;
  const resendable = Boolean(row.setup_email_resendable);
  if (status === "failed") return { kind: "failed", tone: "warn", resendable };
  if (status === "not_configured") return { kind: "not_configured", tone: "warn", resendable };
  return { kind: status === "sent" ? "sent" : "pending", tone: "ok", resendable };
}

/** A fresh page arrived: overrides describe the old one, drop them (pending stays). */
export function refreshed(state: MatrixState): MatrixState {
  return { ...state, overrides: {} };
}

// ---------------------------------------------------------------------------
// Reducer — the component's single entry point over the functions above.
// ---------------------------------------------------------------------------

export type MatrixAction =
  | { type: "toggle"; row: MatrixRow; column: MatrixColumn; canManage: boolean }
  | { type: "setColumn"; rows: MatrixRow[]; column: MatrixColumn; on: boolean; canManage: boolean }
  | { type: "setRow"; row: MatrixRow; columns: MatrixColumn[]; on: boolean; canManage: boolean }
  /** Only switches when nothing is pending (use `switchMode` first to ask). */
  | { type: "setMode"; mode: Mode }
  | { type: "discard" }
  /** Discard pending and switch in one step (the "Discard and switch" choice). */
  | { type: "discardAndSwitch"; mode: Mode }
  | { type: "applied"; action: Mode; results: BulkResult[] }
  | { type: "refreshed" };

export function matrixReducer(state: MatrixState, action: MatrixAction): MatrixState {
  switch (action.type) {
    case "toggle":
      return toggleCell(state, action.row, action.column, action.canManage);
    case "setColumn":
      return setColumn(state, action.rows, action.column, action.on, action.canManage);
    case "setRow":
      return setRow(state, action.row, action.columns, action.on, action.canManage);
    case "setMode": {
      const res = switchMode(state, action.mode);
      return res.kind === "switched" ? res.state : state;
    }
    case "discard":
      return discard(state);
    case "discardAndSwitch":
      return { ...discard(state), mode: action.mode };
    case "applied":
      return applyResults(state, action.action, action.results).state;
    case "refreshed":
      return refreshed(state);
  }
}
