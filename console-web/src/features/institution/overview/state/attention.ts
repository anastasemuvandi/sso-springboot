/**
 * The institution overview's "Needs attention" list — pure. A read that
 * failed (or that the tier may not make) contributes no items; failed reads
 * are named instead, so nothing reads as a false zero.
 */
import type { Schemas } from "@/api/types";
import type { Overview } from "@/features/overview/types";
import type { JoinColumnBacklogLite } from "../api";

export interface InstAttentionInput {
  overview: Overview | null;
  summary: Schemas["EmployeeSummary"] | null;
  joinColumns: JoinColumnBacklogLite | null;
}

export interface InstAttentionSpec {
  id: string;
  count: number;
  textKey: string;
  /** Path (scoped by the caller) or "#anchor". */
  href?: string;
  extra?: Record<string, string>;
}

export function buildInstitutionAttention({ overview, summary, joinColumns }: InstAttentionInput): InstAttentionSpec[] {
  const out: InstAttentionSpec[] = [];
  if (overview) {
    if (overview.leavers.available) out.push({ id: "leavers", count: overview.leavers.total, textKey: "instOverview.attention.leavers", href: "#leavers" });
    if (overview.password_setup.available && overview.password_setup.counts) {
      out.push({
        id: "setup-email",
        count: overview.password_setup.counts.failed + overview.password_setup.counts.not_configured,
        textKey: "instOverview.attention.setupEmail",
        href: "/institution/access/matrix",
      });
    }
    if (overview.logins.available) out.push({ id: "locked", count: overview.logins.locked_now?.length ?? 0, textKey: "instOverview.attention.locked", href: "#logins" });
  }
  if (summary) {
    out.push(
      { id: "unlinked", count: summary.unlinked, textKey: "instOverview.attention.unlinked", href: "/institution/employees", extra: { account: "unlinked" } },
      { id: "ambiguous", count: summary.ambiguous, textKey: "instOverview.attention.ambiguous", href: "/institution/employees", extra: { account: "ambiguous" } },
      { id: "no-email", count: summary.no_email ?? 0, textKey: "instOverview.attention.noEmail", href: "/institution/employees", extra: { account: "unlinked" } },
    );
  }
  if (joinColumns) {
    out.push(
      { id: "join-own", count: joinColumns.outstanding, textKey: "instOverview.attention.joinOwn", href: "/institution/apps/join-keys" },
      { id: "join-shared", count: joinColumns.needs_platform_admin, textKey: "instOverview.attention.joinShared" },
    );
  }
  return out;
}
