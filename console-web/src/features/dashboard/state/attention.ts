/**
 * The dashboard's "Needs attention" list — pure, no React.
 *
 * Built from four independent reads (employee summary, clients, realm MFA,
 * overview). A read that failed contributes NO items and is named in
 * `unavailable` instead, so a quiet list is never mistaken for a clean one
 * (and a failed section never turns into a false zero).
 */
import type { Schemas } from "@/api/types";
import type { Client, RealmMfaState } from "@/features/clients/types";
import { KEYCLOAK_INTERNAL_CLIENTS } from "@/features/clients/types";
import type { Overview } from "@/features/overview/types";

export interface AttentionInput {
  summary: Schemas["EmployeeSummary"] | null;
  clients: Client[] | null;
  realmMfa: RealmMfaState | null;
  overview: Overview | null;
}

export interface AttentionSpec {
  id: string;
  count: number;
  /** i18n key for the text. */
  textKey: string;
  textParams?: Record<string, string>;
  href?: string;
  /** i18n key for the call to action. */
  ctaKey?: string;
}

/** Real applications: Keycloak's own clients are not part of the surface. */
export function applications(clients: Client[]): Client[] {
  return clients.filter((c) => !KEYCLOAK_INTERNAL_CLIENTS.has(c.clientId));
}

/** Scoped clients (institution or cross-cutting) whose join key is unset or malformed. */
export function joinKeysOutstanding(clients: Client[]): { scoped: Client[]; outstanding: Client[] } {
  const scoped = applications(clients).filter((c) => c.scope && c.scope.kind !== "platform");
  const outstanding = scoped.filter((c) => !c.join_column?.explicit || Boolean(c.join_column?.malformed_value));
  return { scoped, outstanding };
}

export function buildAttention(input: AttentionInput): AttentionSpec[] {
  const out: AttentionSpec[] = [];
  const { summary, clients, realmMfa, overview } = input;
  if (summary) {
    out.push(
      { id: "unmapped", count: summary.unmapped, textKey: "dashboard.attention.unmapped", href: "/employees?unmapped=1", ctaKey: "dashboard.attention.mapThem" },
      { id: "ambiguous", count: summary.ambiguous, textKey: "dashboard.attention.ambiguous", href: "/employees?account=ambiguous" },
      { id: "no-email", count: summary.no_email ?? 0, textKey: "dashboard.attention.noEmail", href: "/employees?account=unlinked" },
    );
  }
  if (clients) {
    const { outstanding } = joinKeysOutstanding(clients);
    out.push(
      {
        id: "join-shared",
        count: outstanding.filter((c) => c.scope?.kind === "cross-cutting").length,
        textKey: "dashboard.attention.joinShared",
        href: "/clients/join-keys",
        ctaKey: "dashboard.attention.setJoinKeys",
      },
      {
        id: "join-institution",
        count: outstanding.filter((c) => c.scope?.kind === "institution").length,
        textKey: "dashboard.attention.joinInstitution",
        href: "/clients/join-keys",
      },
    );
  }
  if (realmMfa && !realmMfa.flow_ready) {
    out.push({
      id: "mfa-flow",
      count: 1,
      textKey: "dashboard.attention.mfaFlow",
      textParams: { flow: realmMfa.browser_flow },
      href: "/settings",
      ctaKey: "nav.settings",
    });
  }
  if (overview) {
    // Each overview section only contributes when it was actually read.
    if (overview.leavers.available) {
      out.push({ id: "leavers", count: overview.leavers.total, textKey: "dashboard.attention.leavers", href: "#leavers", ctaKey: "dashboard.attention.seeThem" });
    }
    if (overview.password_setup.available && overview.password_setup.counts) {
      out.push({
        id: "setup-email",
        count: overview.password_setup.counts.failed + overview.password_setup.counts.not_configured,
        textKey: "dashboard.attention.setupEmail",
        href: "#password-setup",
      });
    }
    if (overview.logins.available) {
      out.push({ id: "locked", count: overview.logins.locked_now?.length ?? 0, textKey: "dashboard.attention.locked", href: "#logins" });
    }
  }
  return out;
}

/** i18n keys of the sources that could not be read. */
export function unavailableSources(input: AttentionInput, errors: { employees: boolean; clients: boolean; realmMfa: boolean; overview: boolean }): string[] {
  const out: string[] = [];
  if (errors.employees) out.push("dashboard.sources.employees");
  if (errors.clients) out.push("dashboard.sources.clients");
  if (errors.realmMfa) out.push("dashboard.sources.realmMfa");
  if (errors.overview) out.push("dashboard.sources.overview");
  if (input.overview && !errors.overview) {
    if (!input.overview.leavers.available) out.push("dashboard.sources.leavers");
    if (!input.overview.password_setup.available) out.push("dashboard.sources.passwordSetup");
    if (!input.overview.logins.available) out.push("dashboard.sources.logins");
  }
  return out;
}
