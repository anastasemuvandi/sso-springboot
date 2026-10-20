/**
 * Institution audit log: URL filters ↔ API query, and readable rendering of
 * events — pure, no React.
 *
 * There is no `institution` filter on purpose: the wrapper pins it to the
 * caller's resolved institution and re-filters the response.
 */
import type { InstitutionAuditEvent } from "../types";

export const PAGE_SIZE = 50;
export const SEVERITIES = ["INFO", "WARNING", "CRITICAL"] as const;

/** One-click event-type filters; labels are i18n keys `instAudit.quick.<id>`. */
export const QUICK_FILTERS: ReadonlyArray<{ id: string; event_type?: string }> = [
  { id: "all" },
  { id: "rolesGranted", event_type: "admin.role.assigned" },
  { id: "rolesRevoked", event_type: "admin.role.removed" },
  { id: "userCreated", event_type: "admin.user.created" },
  { id: "userUpdated", event_type: "admin.user.updated" },
  { id: "userDisabled", event_type: "admin.user.disabled" },
  { id: "appAccess", event_type: "admin.client.updated" },
  { id: "appCreated", event_type: "admin.client.created" },
  { id: "mfa", event_type: "admin.mfa.client_requirement_changed" },
  { id: "nidViewed", event_type: "admin.employee.nid_revealed" },
  { id: "setupEmail", event_type: "admin.setup_link.resend" },
  { id: "offboarding", event_type: "lifecycle.offboard" },
  { id: "accessReviews", event_type: "admin.access_review.due" },
];

/** Event types with a readable name: i18n key `instAudit.event.<key>` where key = type with dots → underscores. */
export const KNOWN_EVENTS = new Set([
  "admin.user.created",
  "admin.user.updated",
  "admin.user.disabled",
  "admin.user.deleted",
  "admin.role.created",
  "admin.role.assigned",
  "admin.role.removed",
  "admin.client.created",
  "admin.client.updated",
  "admin.client.deleted",
  "admin.group.created",
  "admin.group.updated",
  "admin.group.deleted",
  "admin.group.member_added",
  "admin.group.member_removed",
  "admin.mfa.client_requirement_changed",
  "admin.mfa.exempt_granted",
  "admin.mfa.exempt_revoked",
  "admin.employee.nid_revealed",
  "admin.setup_link.resend",
  "admin.access_review.due",
  "lifecycle.offboard",
  "lifecycle.reassign",
  "session.terminated",
]);

/** `action` values on `admin.client.updated`: i18n `instAudit.action.<action>`. */
export const KNOWN_ACTIONS = new Set([
  "grant_user_access",
  "revoke_user_access",
  "employee_access_assigned",
  "employee_provisioning_failed",
  "scope_changed",
  "scope_institution_removed",
  "join_column_changed",
  "redirect_uris_updated",
  "post_logout_redirect_uris_updated",
  "secret_rotated",
  "password_reset",
]);

export function eventKey(type: string): string | null {
  return KNOWN_EVENTS.has(type) ? type.replace(/\./g, "_") : null;
}

export interface AuditFilters {
  event_type?: string;
  target?: string;
  severity?: string;
  /** YYYY-MM-DD (UTC calendar day) */
  from?: string;
  to?: string;
  page: number;
}

const DAY = /^\d{4}-\d{2}-\d{2}$/;

export function parseFilters(sp: URLSearchParams): AuditFilters {
  const str = (k: string) => sp.get(k)?.trim() || undefined;
  const severity = str("severity");
  const from = str("from");
  const to = str("to");
  return {
    event_type: str("event_type"),
    target: str("target"),
    severity: severity && (SEVERITIES as readonly string[]).includes(severity) ? severity : undefined,
    from: from && DAY.test(from) ? from : undefined,
    to: to && DAY.test(to) ? to : undefined,
    page: Math.max(1, parseInt(sp.get("page") ?? "1", 10) || 1),
  };
}

export function isFiltered(f: AuditFilters): boolean {
  return Boolean(f.event_type || f.target || f.severity || f.from || f.to);
}

/** The API query for a filter set — calendar days become UTC bounds. */
export function toQuery(f: AuditFilters) {
  return {
    event_type: f.event_type,
    target: f.target,
    severity: f.severity,
    from_date: f.from ? `${f.from}T00:00:00Z` : undefined,
    to_date: f.to ? `${f.to}T23:59:59.999Z` : undefined,
    limit: PAGE_SIZE,
    offset: (f.page - 1) * PAGE_SIZE,
  };
}

/** URL params for a filter set (page 1 omitted). */
export function toSearch(f: Partial<AuditFilters>): Record<string, string | undefined> {
  return {
    event_type: f.event_type,
    target: f.target,
    severity: f.severity,
    from: f.from,
    to: f.to,
    page: f.page && f.page > 1 ? String(f.page) : undefined,
  };
}

export function totalPages(total: number): number {
  return Math.max(1, Math.ceil(total / PAGE_SIZE));
}

/**
 * Pages to offer. The wrapper's scoped audit read reports `total` as the
 * number of events kept on THIS page (InstitutionAuditService
 * .enforceInstitutionScope, as the reference did), not the overall count — so
 * a full page means there may be another one.
 */
export function knownPages(total: number, page: number, itemsOnPage: number): number {
  return Math.max(totalPages(total), page, itemsOnPage >= PAGE_SIZE ? page + 1 : page);
}

function str(v: unknown): string | undefined {
  return typeof v === "string" && v ? v : undefined;
}

/** The app an event concerns: the client it was recorded against, else the one it names. */
export function appOf(e: InstitutionAuditEvent): string | undefined {
  return str(e.app_id) ?? str(e.details?.target_client);
}

/** What was acted on: a readable name when recorded, and the id. */
export function targetOf(e: InstitutionAuditEvent): { name?: string; id?: string } {
  const d = e.details ?? {};
  const name = str(d.target_username) ?? str(d.group_name) ?? str(d.role_name);
  const id = str(e.target);
  return { name: name && name !== id ? name : undefined, id };
}

/**
 * The pieces of a one-line summary. `action` is returned raw so the caller can
 * translate it (`instAudit.action.<action>` when known).
 */
export function summaryParts(e: InstitutionAuditEvent): { action?: string; rest: string[] } {
  const d = e.details ?? {};
  const rest: string[] = [];
  const role = str(d.role);
  if (role) rest.push(role);
  const roles = d.roles;
  if (Array.isArray(roles) && roles.length) rest.push(roles.join(", "));
  const outcome = str(d.outcome);
  if (outcome && outcome !== "success") rest.push(outcome);
  return { action: str(d.action), rest };
}

/** A details value as text: strings as-is, everything else compact JSON. */
export function display(v: unknown): string {
  if (v === null || v === undefined) return "—";
  if (typeof v === "string") return v;
  return JSON.stringify(v);
}

export function severityVariant(s: string): "secondary" | "destructive" | "outline" {
  if (s === "CRITICAL") return "destructive";
  if (s === "WARNING") return "outline";
  return "secondary";
}
