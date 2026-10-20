/**
 * Client-scope form state — pure, no React. Shared by `/clients/new` and the
 * scope editor on `/clients/:id`.
 *
 * Three shapes, and only one is ever submitted (the wrapper accepts one):
 *   institution   -> { owner_institution: <slug> }
 *   cross-cutting -> { cross_cutting: true, institutions: ["*"] | [<slug>...] }
 *   platform      -> {}                     (no scope recorded)
 *
 * "All institutions" (`*`) is DYNAMIC: later institutions are included. Ticking
 * every box is a fixed snapshot.
 *
 * Narrowing a scope is a two-step the WRAPPER enforces: without `confirm` it
 * answers 409 listing the institutions that still have users on the client;
 * the admin confirms and the request is re-sent with `confirm: true`. None of
 * this is the boundary — it only puts the explanation next to the field.
 */
import { ApiError } from "@/api/errors";
import type { ClientScope, ClientScopeFields, ClientScopeImpact } from "../types";

export type ScopeMode = "institution" | "cross-cutting" | "platform";

export interface ScopeFormValues {
  mode: ScopeMode;
  /** Owner slug (institution mode). */
  owner: string;
  /** Cross-cutting: all institutions (dynamic `*`). */
  all: boolean;
  /** Cross-cutting, not all: the ticked slugs. */
  selected: string[];
}

export const EMPTY_SCOPE_FORM: ScopeFormValues = { mode: "institution", owner: "", all: false, selected: [] };

/** Form values from the wrapper's parsed scope. */
export function formFromScope(scope: ClientScope | null | undefined): ScopeFormValues {
  if (!scope) return { ...EMPTY_SCOPE_FORM };
  if (scope.kind === "cross-cutting") {
    return { mode: "cross-cutting", owner: "", all: scope.all_institutions, selected: scope.all_institutions ? [] : [...scope.institutions] };
  }
  if (scope.kind === "institution") {
    return { mode: "institution", owner: scope.owner_institution ?? "", all: false, selected: [] };
  }
  return { mode: "platform", owner: "", all: false, selected: [] };
}

export type ScopeFormError = "no-institution" | "no-owner";

export type ScopeRequest = { ok: true; fields: ClientScopeFields } | { ok: false; error: ScopeFormError };

/** The request body for the form, or why it cannot be submitted. */
export function scopeRequest(values: ScopeFormValues): ScopeRequest {
  if (values.mode === "cross-cutting") {
    if (values.all) return { ok: true, fields: { cross_cutting: true, institutions: ["*"] } };
    const institutions = [...new Set(values.selected.map((s) => s.trim()).filter(Boolean))];
    if (institutions.length === 0) return { ok: false, error: "no-institution" };
    return { ok: true, fields: { cross_cutting: true, institutions } };
  }
  if (values.mode === "institution") {
    const owner = values.owner.trim();
    if (!owner) return { ok: false, error: "no-owner" };
    return { ok: true, fields: { owner_institution: owner } };
  }
  return { ok: true, fields: {} };
}

/** Slugs the form's scope would cover, given the known institutions (for `all`). */
export function coveredInstitutions(values: ScopeFormValues, known: readonly string[]): Set<string> {
  if (values.mode === "cross-cutting") return new Set(values.all ? known : values.selected);
  if (values.mode === "institution" && values.owner) return new Set([values.owner]);
  return new Set();
}

/** Has a foothold: its group is admitted, or it has named users on the client. */
export function hasFoothold(i: ClientScopeImpact): boolean {
  return i.group_assigned || i.users > 0;
}

/**
 * Institutions that are on the client today and would not be covered by the
 * form's scope — the client-side preview of the wrapper's 409.
 */
export function institutionsLosingAccess(
  values: ScopeFormValues,
  access: readonly ClientScopeImpact[],
  known: readonly string[],
): ClientScopeImpact[] {
  const covered = coveredInstitutions(values, known);
  return access.filter((i) => hasFoothold(i) && !covered.has(i.institution));
}

export function toggleSelected(values: ScopeFormValues, slug: string): ScopeFormValues {
  const s = new Set(values.selected);
  if (s.has(slug)) s.delete(slug);
  else s.add(slug);
  return { ...values, selected: [...s] };
}

// ---------------------------------------------------------------------------
// Save flow (409 confirmation)
// ---------------------------------------------------------------------------

export interface ScopeConflict {
  removedInstitutions: string[];
  accessAtRisk: ClientScopeImpact[];
  message: string;
}

/**
 * Reads the wrapper's 409 for an unconfirmed narrowing. The details carry
 * `removed_institutions` and `access_at_risk` (reference field names). Null
 * for anything else.
 */
export function parseScopeConflict(e: unknown): ScopeConflict | null {
  if (!(e instanceof ApiError) || e.status !== 409) return null;
  const d = e.details;
  const removed = Array.isArray(d.removed_institutions) ? (d.removed_institutions as unknown[]).map(String) : [];
  const atRisk = Array.isArray(d.access_at_risk) ? (d.access_at_risk as ClientScopeImpact[]) : [];
  return { removedInstitutions: removed, accessAtRisk: atRisk, message: e.message };
}

export type ScopeSaveState =
  | { phase: "idle" }
  | { phase: "saving"; confirm: boolean }
  | { phase: "confirming"; conflict: ScopeConflict; fields: ClientScopeFields }
  | { phase: "saved"; removed: string[] }
  | { phase: "error"; message: string };

export type ScopeSaveAction =
  | { type: "submit"; confirm?: boolean }
  | { type: "conflict"; conflict: ScopeConflict; fields: ClientScopeFields }
  | { type: "confirm" }
  | { type: "cancel" }
  | { type: "saved"; removed: string[] }
  | { type: "failed"; message: string }
  | { type: "reset" };

export function scopeSaveReducer(state: ScopeSaveState, action: ScopeSaveAction): ScopeSaveState {
  switch (action.type) {
    case "submit":
      return { phase: "saving", confirm: Boolean(action.confirm) };
    case "conflict":
      // Only a save that was not already confirmed can be asked to confirm.
      if (state.phase === "saving" && !state.confirm) return { phase: "confirming", conflict: action.conflict, fields: action.fields };
      return { phase: "error", message: action.conflict.message };
    case "confirm":
      return state.phase === "confirming" ? { phase: "saving", confirm: true } : state;
    case "cancel":
      return state.phase === "confirming" ? { phase: "idle" } : state;
    case "saved":
      return { phase: "saved", removed: action.removed };
    case "failed":
      return { phase: "error", message: action.message };
    case "reset":
      return { phase: "idle" };
  }
}
