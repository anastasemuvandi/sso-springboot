/**
 * Audit-trail filters <-> URL search params, and the export path — pure.
 *
 * Filters live in the URL so a filtered view can be bookmarked and shared.
 * The export forwards only the filters the wrapper's `/audit/export/{fmt}`
 * accepts (severity/offset are not export parameters there).
 */
import type { AuditEventFilters, AuditIntegrityReport, ExportFormat } from "../types";

export const CATEGORIES = ["AUTHENTICATION", "AUTHORIZATION", "ADMIN", "LIFECYCLE", "SESSION", "CERTIFICATE"] as const;
export const SEVERITIES = ["INFO", "WARNING", "CRITICAL"] as const;

export const DEFAULT_LIMIT = 100;
export const MAX_LIMIT = 1000;

/** Clamp a page size to 1..1000 (default 100), as the reference page did. */
export function clampLimit(raw: string | number | null | undefined): number {
  const n = typeof raw === "number" ? raw : parseInt(String(raw ?? ""), 10);
  if (!Number.isFinite(n) || n <= 0) return DEFAULT_LIMIT;
  return Math.min(Math.max(1, Math.floor(n)), MAX_LIMIT);
}

const TEXT_KEYS = ["event_type", "category", "user_id", "app_id", "severity", "from_date", "to_date"] as const;

export function filtersFromSearch(sp: URLSearchParams): AuditEventFilters {
  const f: AuditEventFilters = { limit: clampLimit(sp.get("limit")), offset: Math.max(0, parseInt(sp.get("offset") ?? "0", 10) || 0) };
  for (const k of TEXT_KEYS) {
    const v = sp.get(k)?.trim();
    if (v) f[k] = v;
  }
  return f;
}

export function searchFromFilters(f: AuditEventFilters): URLSearchParams {
  const sp = new URLSearchParams();
  for (const k of TEXT_KEYS) {
    const v = f[k];
    if (v) sp.set(k, v);
  }
  if (f.limit && f.limit !== DEFAULT_LIMIT) sp.set("limit", String(f.limit));
  if (f.offset) sp.set("offset", String(f.offset));
  return sp;
}

const EXPORT_KEYS = ["event_type", "category", "user_id", "app_id", "from_date", "to_date"] as const;

/** `/audit/export/<fmt>?…` with the active filters the export endpoint understands. */
export function auditExportPath(fmt: ExportFormat, f: AuditEventFilters): string {
  const sp = new URLSearchParams();
  for (const k of EXPORT_KEYS) {
    const v = f[k];
    if (v) sp.set(k, v);
  }
  if (f.limit) sp.set("limit", String(f.limit));
  const qs = sp.toString();
  return `/audit/export/${fmt}${qs ? `?${qs}` : ""}`;
}

export interface PageInfo {
  from: number;
  to: number;
  total: number;
  hasPrev: boolean;
  hasNext: boolean;
  prevOffset: number;
  nextOffset: number;
}

export function pageInfo(total: number, offset: number, limit: number, shown: number): PageInfo {
  return {
    from: shown === 0 ? 0 : offset + 1,
    to: offset + shown,
    total,
    hasPrev: offset > 0,
    hasNext: offset + shown < total,
    prevOffset: Math.max(0, offset - limit),
    nextOffset: offset + limit,
  };
}

export type IntegrityKind = "valid" | "tampered" | "empty";

/** How to present a verification report. An empty chain is not a pass to boast about. */
export function integrityKind(r: AuditIntegrityReport): IntegrityKind {
  if (!r.valid || r.tampered_count > 0) return "tampered";
  return r.total_checked === 0 ? "empty" : "valid";
}

export function severityVariant(sev: string): "destructive" | "warning" | "outline" {
  if (sev === "CRITICAL") return "destructive";
  if (sev === "WARNING") return "warning";
  return "outline";
}
