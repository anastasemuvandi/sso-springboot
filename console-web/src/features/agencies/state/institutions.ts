/**
 * Institutions page rules — pure, no React.
 *
 * Rows come from GET /institutions (registry × Keycloak × IPPIS); membership
 * counts from GET /agency/ keyed by group id. A row only links to a group page
 * when Keycloak has the group: `unknown_code` rows are IPPIS codes nothing
 * claims and `not_onboarded` rows have no group yet.
 */
export type InstitutionStatus = "onboarded" | "partial" | "not_onboarded" | "unregistered_group" | "unknown_code";

export interface StatusMeta {
  /** i18n key */
  labelKey: string;
  variant: "secondary" | "outline" | "destructive";
  className?: string;
}

const WARN = "border-amber-500/60 text-amber-700 dark:text-amber-400";

const META: Record<InstitutionStatus, StatusMeta> = {
  onboarded: { labelKey: "agencies.status.onboarded", variant: "secondary" },
  partial: { labelKey: "agencies.status.partial", variant: "outline", className: WARN },
  not_onboarded: { labelKey: "agencies.status.not_onboarded", variant: "outline", className: WARN },
  unregistered_group: { labelKey: "agencies.status.unregistered_group", variant: "outline", className: WARN },
  unknown_code: { labelKey: "agencies.status.unknown_code", variant: "destructive" },
};

/** Label/colour for a status; an unrecognised status is shown verbatim as outline. */
export function statusMeta(status: string): StatusMeta & { raw?: string } {
  return (META as Record<string, StatusMeta>)[status] ?? { labelKey: "", variant: "outline", raw: status };
}

export interface RowLike {
  group_id?: string | null;
  slug?: string | null;
  institution_code?: string | null;
  group_name?: string | null;
  in_keycloak: boolean;
}

export function rowKey(row: RowLike, index: number): string {
  return row.group_id ?? row.slug ?? row.institution_code ?? `row-${index}`;
}

/** The text identifying a row: slug, else IPPIS code, else group name. */
export function rowLabel(row: RowLike): string {
  return row.slug ?? row.institution_code ?? row.group_name ?? "—";
}

/** Manage/delete only when there is a Keycloak group to act on. */
export function isManageable(row: RowLike): row is RowLike & { group_id: string } {
  return row.in_keycloak && Boolean(row.group_id);
}

/** A group whose name differs from the slug (pre-ADR-0001 casing) is worth showing. */
export function groupNameDiffers(row: RowLike): boolean {
  return Boolean(row.group_name && row.slug && row.group_name !== row.slug);
}

export interface MemberCounts {
  total: number;
  active: number;
}

export function membersById(agencies: ReadonlyArray<{ id: string; totalMembers: number; activeMembers: number }>): Map<string, MemberCounts> {
  return new Map(agencies.map((a) => [a.id, { total: a.totalMembers, active: a.activeMembers }]));
}

/** Institution slugs for pickers: lowercased group names (pre-ADR-0001 groups are UPPERCASE). */
export function institutionSlugs(agencies: ReadonlyArray<{ name: string }>): string[] {
  return Array.from(new Set(agencies.map((a) => a.name.toLowerCase()).filter(Boolean))).sort();
}

/** First value of the `description` group attribute, if any. */
export function describedAs(attributes: Record<string, string[]> | null | undefined): string {
  return attributes?.description?.[0] ?? "";
}
