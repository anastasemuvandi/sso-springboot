/**
 * Employee directory URL state and display helpers — pure, no React.
 *
 * The directory's filters live in the URL (`?search=&institution=&status=
 * &account=&unmapped=1&sort=&dir=&page=`) so links from the dashboard
 * ("Needs attention" → `/employees?account=unlinked`) land on a filtered view
 * and a refresh keeps it. Whatever the URL says, the wrapper decides scope:
 * `?institution=` is checked against the caller's grant server-side.
 *
 * National IDs are never part of a listing; only the detail read returns one.
 */
import type { Schemas } from "@/api/types";

export type Employee = Schemas["EmployeeRow"];
export type EmployeeDetail = Schemas["EmployeeDetail"];
export type EmployeePage = Schemas["EmployeePage"];

export const PAGE_SIZE = 50;

export const ACCOUNT_STATES = ["linked", "unlinked", "ambiguous"] as const;
export type AccountState = (typeof ACCOUNT_STATES)[number];

export const EMPLOYMENT_STATUSES = ["active", "suspended", "terminated"] as const;
export type EmploymentStatus = (typeof EMPLOYMENT_STATUSES)[number];

export const SORT_KEYS = ["employee_number", "name", "position", "institution", "status", "last_seen_at", "last_synced_at"] as const;
export type SortKey = (typeof SORT_KEYS)[number];

export interface DirectoryState {
  search?: string;
  institution?: string;
  status?: EmploymentStatus;
  account?: AccountState;
  unmapped: boolean;
  sort: SortKey;
  dir: "asc" | "desc";
  page: number;
}

function oneOf<T extends string>(list: readonly T[], v: string | null): T | undefined {
  return v !== null && (list as readonly string[]).includes(v) ? (v as T) : undefined;
}

/** Parse the URL; anything unrecognised falls back to the default, never errors. */
export function parseDirectoryParams(sp: URLSearchParams): DirectoryState {
  const page = Number.parseInt(sp.get("page") ?? "1", 10);
  return {
    search: sp.get("search")?.trim() || sp.get("q")?.trim() || undefined,
    institution: sp.get("institution")?.trim() || undefined,
    status: oneOf(EMPLOYMENT_STATUSES, sp.get("status")),
    account: oneOf(ACCOUNT_STATES, sp.get("account")),
    unmapped: sp.get("unmapped") === "1",
    sort: oneOf(SORT_KEYS, sp.get("sort")) ?? "employee_number",
    dir: sp.get("dir") === "desc" ? "desc" : "asc",
    page: Number.isFinite(page) && page > 0 ? page : 1,
  };
}

/** The GET /employees query for a state. */
export function toListQuery(s: DirectoryState) {
  return {
    ...(s.search ? { search: s.search } : {}),
    ...(s.institution ? { institution: s.institution } : {}),
    ...(s.status ? { status: s.status } : {}),
    ...(s.account ? { account: s.account } : {}),
    ...(s.unmapped ? { unmapped: true } : {}),
    sort: s.sort,
    dir: s.dir,
    limit: PAGE_SIZE,
    offset: (s.page - 1) * PAGE_SIZE,
  };
}

/** URL params for a state, with `patch` applied (undefined/"" removes a key). Page 1 is omitted. */
export function toSearchParams(s: DirectoryState, patch: Partial<Record<keyof DirectoryState, string | number | boolean | undefined>> = {}): URLSearchParams {
  const merged: Record<string, string | number | boolean | undefined> = {
    search: s.search,
    institution: s.institution,
    status: s.status,
    account: s.account,
    unmapped: s.unmapped ? "1" : undefined,
    sort: s.sort,
    dir: s.dir,
    page: s.page > 1 ? s.page : undefined,
    ...patch,
  };
  const out = new URLSearchParams();
  for (const [k, v] of Object.entries(merged)) {
    if (v === undefined || v === "" || v === false) continue;
    if (k === "page" && Number(v) <= 1) continue;
    out.set(k, v === true ? "1" : String(v));
  }
  return out;
}

/** Clicking a header: same key toggles direction, a new key sorts ascending; back to page 1. */
export function sortPatch(s: DirectoryState, key: SortKey) {
  return { sort: key, dir: s.sort === key && s.dir === "asc" ? "desc" : "asc", page: undefined };
}

export function totalPages(total: number): number {
  return Math.max(1, Math.ceil(total / PAGE_SIZE));
}

/** A name if IPPIS gave one, else the employee number. */
export function displayName(e: { employee_number: string; first_name?: string | null; last_name?: string | null }): string {
  return [e.first_name, e.last_name].filter(Boolean).join(" ").trim() || e.employee_number;
}

export function detailName(d: { id: string; firstName?: string | null; lastName?: string | null }): string {
  return [d.firstName, d.lastName].filter(Boolean).join(" ").trim() || d.id;
}

/** "2026-10-20", or "—". */
export function dateOnly(value?: string | null): string {
  if (!value) return "—";
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? "—" : d.toISOString().slice(0, 10);
}

/** Whole days an employee has been reported by IPPIS (the joiner's wait). */
export function waitingDays(firstSeenAt: string | null | undefined, now: number = Date.now()): number | null {
  if (!firstSeenAt) return null;
  const seen = new Date(firstSeenAt).getTime();
  if (Number.isNaN(seen)) return null;
  return Math.floor((now - seen) / 86_400_000);
}

export type AccountView = "ambiguous" | "linked" | "none";

export function accountView(e: { match_state: string; keycloak_user_id?: string | null }): AccountView {
  if (e.match_state === "ambiguous") return "ambiguous";
  return e.keycloak_user_id ? "linked" : "none";
}
