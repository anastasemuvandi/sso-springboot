/**
 * Spreadsheet import — pure helpers (labels, ordering, attention filter, the
 * confirm count, a formula-safe CSV of the result). The rules are the
 * wrapper's; the preview writes nothing and "Confirm" applies the server's
 * stored plan, not the file. Imports only GRANT: a cleared YES is reported as
 * `removal_ignored` ("not revoked"), never revoked.
 */
import type { ImportItem, ImportOutcome, ImportPreview, ImportReport } from "../types";

/** The order the preview is read in: what needs attention first. */
export const OUTCOME_ORDER: ImportOutcome[] = ["invalid", "rejected", "removal_ignored", "will_create_account", "will_grant", "already_has_access"];

export const ATTENTION: readonly ImportOutcome[] = ["invalid", "rejected", "removal_ignored"];

export type ImportFilter = ImportOutcome | "attention" | "all";

export function filterItems(items: readonly ImportItem[], filter: ImportFilter): ImportItem[] {
  if (filter === "all") return [...items];
  if (filter === "attention") return items.filter((i) => ATTENTION.includes(i.outcome));
  return items.filter((i) => i.outcome === filter);
}

/** Grants a confirm would make (accounts created where needed). */
export function grantsToApply(preview: Pick<ImportPreview, "summary">): number {
  return (preview.summary.will_grant ?? 0) + (preview.summary.will_create_account ?? 0);
}

export function outcomeTone(o: ImportOutcome): "default" | "secondary" | "destructive" | "outline" {
  if (o === "will_grant" || o === "will_create_account") return "default";
  if (o === "rejected" || o === "invalid") return "destructive";
  if (o === "removal_ignored") return "outline";
  return "secondary";
}

/** A CSV of the apply report; a leading = + - @ is neutralised (no formula injection in Excel). */
export function reportCsv(report: Pick<ImportReport, "results">): string {
  const cell = (v: unknown) => {
    const s = v === null || v === undefined ? "" : String(v);
    const safe = /^[=+\-@\t\r]/.test(s) ? `'${s}` : s;
    return /[",\n]/.test(safe) ? `"${safe.replaceAll('"', '""')}"` : safe;
  };
  const header = ["employee_number", "client_id", "status", "reason", "message", "account_created", "setup_email"];
  const lines = report.results.map((r) =>
    [r.employee_number, r.client_id, r.status, r.reason, r.message, r.account_created ? "yes" : "", r.setup_email].map(cell).join(","),
  );
  return [header.join(","), ...lines].join("\n");
}
