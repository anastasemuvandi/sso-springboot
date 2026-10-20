/**
 * Employee selection for provisioning — pure, no React, no storage access.
 *
 * The selection is keyed by EMPLOYEE NUMBER (an employee may have no account
 * yet, so there is no user id), and it survives paging: ticking on page 1,
 * moving to page 2 and ticking more keeps both. Each entry keeps what the
 * review step needs to name people who are no longer on screen.
 *
 * Which rows can be selected (console rules, mirroring the reference console
 * and the wrapper's provisioning service — the wrapper re-decides every one):
 *   - `ambiguous` match state — the record matches more than one account; no
 *     access can be changed until IPPIS is corrected;
 *   - `terminated` employment — a leaver is not given access;
 *   - no account AND no email — there is nothing to name a new account after
 *     (an employee WITH an account is selectable whatever IPPIS holds, they are
 *     identified by the account).
 *
 * The batch is capped (EMPLOYEE_PROVISION_MAX_BATCH = 100 in the wrapper):
 * adding past the cap is refused for the overflow and reported, never silently
 * truncated.
 */

export const DEFAULT_MAX_BATCH = 100;

/** The fields of an employee row the selection reads (EmployeeOut). */
export interface SelectableEmployee {
  employee_number: string;
  first_name?: string | null;
  last_name?: string | null;
  email?: string | null;
  status?: string | null;
  match_state?: string | null;
  keycloak_user_id?: string | null;
}

export interface SelectedEmployee {
  employee_number: string;
  /** Display name at the time of selection. */
  label: string;
  email: string | null;
  /** True/false when known; the review copy differs ("account will be created"). */
  has_account: boolean;
}

export interface SelectionState {
  /** Insertion-ordered by employee number. */
  items: Record<string, SelectedEmployee>;
  max: number;
}

export type BlockReason = "ambiguous" | "terminated" | "no_email";

export function initialSelection(max: number = DEFAULT_MAX_BATCH): SelectionState {
  return { items: {}, max: Math.max(1, Math.floor(max)) };
}

export function displayName(e: Pick<SelectableEmployee, "first_name" | "last_name" | "employee_number">): string {
  return [e.first_name, e.last_name].filter(Boolean).join(" ").trim() || e.employee_number;
}

/** Why a row cannot be selected, or null when it can. */
export function blockReason(e: SelectableEmployee): BlockReason | null {
  if (e.match_state === "ambiguous") return "ambiguous";
  if (e.status === "terminated") return "terminated";
  if (!e.keycloak_user_id && !(e.email && e.email.trim())) return "no_email";
  return null;
}

export function isSelectable(e: SelectableEmployee): boolean {
  return blockReason(e) === null;
}

export function toEntry(e: SelectableEmployee): SelectedEmployee {
  return {
    employee_number: e.employee_number,
    label: displayName(e),
    email: e.email?.trim() || null,
    has_account: Boolean(e.keycloak_user_id),
  };
}

export function count(s: SelectionState): number {
  return Object.keys(s.items).length;
}

export function isSelected(s: SelectionState, employeeNumber: string): boolean {
  return employeeNumber in s.items;
}

export function selectedList(s: SelectionState): SelectedEmployee[] {
  return Object.values(s.items);
}

export function employeeNumbers(s: SelectionState): string[] {
  return Object.keys(s.items);
}

export interface SelectionChange {
  state: SelectionState;
  /** Selectable rows that were not added because the cap was reached. */
  refusedForCap: number;
}

/** Add rows (skipping unselectable and already-selected ones) up to the cap. */
export function addMany(s: SelectionState, rows: readonly SelectableEmployee[]): SelectionChange {
  const items = { ...s.items };
  let n = Object.keys(items).length;
  let refusedForCap = 0;
  for (const r of rows) {
    if (!isSelectable(r) || r.employee_number in items) continue;
    if (n >= s.max) {
      refusedForCap += 1;
      continue;
    }
    items[r.employee_number] = toEntry(r);
    n += 1;
  }
  return { state: { ...s, items }, refusedForCap };
}

export function removeMany(s: SelectionState, employeeNumbers: readonly string[]): SelectionState {
  const items = { ...s.items };
  for (const k of employeeNumbers) delete items[k];
  return { ...s, items };
}

/** Toggle one row. Unselectable rows are a no-op; adding past the cap is refused. */
export function toggle(s: SelectionState, row: SelectableEmployee): SelectionChange {
  if (isSelected(s, row.employee_number)) return { state: removeMany(s, [row.employee_number]), refusedForCap: 0 };
  return addMany(s, [row]);
}

export type PageTri = "all" | "some" | "none" | "disabled";

/** Header checkbox state for the rows on the current page (selectable rows only). */
export function pageState(s: SelectionState, rows: readonly SelectableEmployee[]): PageTri {
  const selectable = rows.filter(isSelectable);
  if (selectable.length === 0) return "disabled";
  const on = selectable.filter((r) => isSelected(s, r.employee_number)).length;
  if (on === 0) return "none";
  return on === selectable.length ? "all" : "some";
}

/**
 * The header checkbox: when every selectable row of the page is selected,
 * clear them; otherwise select the rest (up to the cap).
 */
export function togglePage(s: SelectionState, rows: readonly SelectableEmployee[]): SelectionChange {
  if (pageState(s, rows) === "all") {
    return { state: removeMany(s, rows.map((r) => r.employee_number)), refusedForCap: 0 };
  }
  return addMany(s, rows);
}

export function clear(s: SelectionState): SelectionState {
  return { ...s, items: {} };
}

export function remaining(s: SelectionState): number {
  return Math.max(0, s.max - count(s));
}

// ---------------------------------------------------------------------------
// Persistence (the caller owns storage access; these only (de)serialise)
// ---------------------------------------------------------------------------

export const STORAGE_PREFIX = "gorsso.provision.selection";

/** One selection per institution, so switching institution never mixes them. */
export function storageKey(institution: string): string {
  return `${STORAGE_PREFIX}:${institution}`;
}

export function serialize(s: SelectionState): string {
  return JSON.stringify({ v: 1, items: selectedList(s) });
}

/** Parse what `serialize` wrote; anything malformed yields an empty selection. */
export function deserialize(raw: string | null | undefined, max: number = DEFAULT_MAX_BATCH): SelectionState {
  const empty = initialSelection(max);
  if (!raw) return empty;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return empty;
  }
  const items = (parsed as { items?: unknown })?.items;
  if (!Array.isArray(items)) return empty;
  const out: Record<string, SelectedEmployee> = {};
  for (const it of items) {
    if (Object.keys(out).length >= empty.max) break;
    const r = it as Partial<SelectedEmployee>;
    if (typeof r?.employee_number !== "string" || !r.employee_number) continue;
    out[r.employee_number] = {
      employee_number: r.employee_number,
      label: typeof r.label === "string" && r.label ? r.label : r.employee_number,
      email: typeof r.email === "string" ? r.email : null,
      has_account: Boolean(r.has_account),
    };
  }
  return { ...empty, items: out };
}

/** Storage-safe load/save (private windows and blocked storage throw). */
export function loadSelection(storage: Pick<Storage, "getItem"> | null | undefined, institution: string, max?: number): SelectionState {
  try {
    return deserialize(storage?.getItem(storageKey(institution)), max);
  } catch {
    return initialSelection(max);
  }
}

export function saveSelection(storage: Pick<Storage, "setItem" | "removeItem"> | null | undefined, institution: string, s: SelectionState): void {
  try {
    if (count(s) === 0) storage?.removeItem(storageKey(institution));
    else storage?.setItem(storageKey(institution), serialize(s));
  } catch {
    /* storage unavailable: the selection lives in memory only */
  }
}

// ---------------------------------------------------------------------------
// Provisioning capabilities (cosmetic; the wrapper asserts all three)
// ---------------------------------------------------------------------------

export const PROVISION_CAPABILITIES = ["manage-members", "manage-membership", "manage-clients"] as const;
export type ProvisionCapability = (typeof PROVISION_CAPABILITIES)[number];

/** The first provisioning capability the caller lacks, most fundamental first. */
export function missingProvisionCapability(can: (c: ProvisionCapability) => boolean): ProvisionCapability | null {
  return PROVISION_CAPABILITIES.find((c) => !can(c)) ?? null;
}
