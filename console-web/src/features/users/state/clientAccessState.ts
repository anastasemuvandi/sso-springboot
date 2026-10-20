/**
 * Per-client access states for one user — labels, tones and which actions a
 * row offers. Pure, no React.
 *
 * The state itself is decided by the wrapper with the token gate's own rule
 * (deny beats allow); the console only renders it and offers the action that
 * makes sense. Grant = `admitUser` (adds a named grant AND lifts a denial);
 * revoke = put on `sso-access-denied-users`; restore = grant on a revoked row.
 * Every change sends `expected_state`, so a cell that changed since it was
 * read is refused with 409 `state_changed` and nothing is written.
 */
import type { UserClientAccessItem, UserClientAccessState } from "../types";

export const ACCESS_STATES: readonly UserClientAccessState[] = ["granted", "open", "revoked", "not_granted", "exempt", "unknown"];

export type AccessTone = "success" | "secondary" | "destructive" | "outline" | "warning";

export interface AccessStateView {
  state: UserClientAccessState;
  /** i18n key: `users.access.state.<state>` */
  labelKey: string;
  /** i18n key: `users.access.stateHelp.<state>` */
  helpKey: string;
  tone: AccessTone;
  /** Does this person reach the client at login? null = unknown. */
  reaches: boolean | null;
}

const TONE: Record<UserClientAccessState, AccessTone> = {
  granted: "success",
  open: "secondary",
  revoked: "destructive",
  not_granted: "outline",
  exempt: "secondary",
  unknown: "warning",
};

const REACHES: Record<UserClientAccessState, boolean | null> = {
  granted: true,
  open: true,
  revoked: false,
  not_granted: false,
  exempt: true,
  unknown: null,
};

export function isAccessState(v: unknown): v is UserClientAccessState {
  return typeof v === "string" && (ACCESS_STATES as readonly string[]).includes(v);
}

/** View of a state; an unrecognised value is shown as `unknown`, never as access. */
export function accessStateView(state: string | null | undefined): AccessStateView {
  const s: UserClientAccessState = isAccessState(state) ? state : "unknown";
  return {
    state: s,
    labelKey: `users.access.state.${s}`,
    helpKey: `users.access.stateHelp.${s}`,
    tone: TONE[s],
    reaches: REACHES[s],
  };
}

export type AccessAction = "grant" | "revoke" | "restore";

/**
 * The actions a row offers.
 * - granted / open → revoke (open: the client restricts nobody; revoking denies this person)
 * - not_granted → grant
 * - revoked → restore (a grant, which lifts the denial)
 * - exempt (control-plane) / unknown (policies unreadable) → nothing
 */
export function availableActions(state: UserClientAccessState): AccessAction[] {
  switch (state) {
    case "granted":
    case "open":
      return ["revoke"];
    case "not_granted":
      return ["grant"];
    case "revoked":
      return ["restore"];
    default:
      return [];
  }
}

/**
 * The wire action. The wrapper accepts `restore` (a grant that it refuses with
 * 409 `not_applicable` unless the cell is `revoked`, and audits as a restore),
 * so the console sends it rather than a plain grant.
 */
export function wireAction(a: AccessAction): AccessAction {
  return a;
}

/** Counts per state, for the summary line. */
export function countByState(items: readonly Pick<UserClientAccessItem, "state">[]): Record<UserClientAccessState, number> {
  const out = Object.fromEntries(ACCESS_STATES.map((s) => [s, 0])) as Record<UserClientAccessState, number>;
  for (const i of items) out[isAccessState(i.state) ? i.state : "unknown"] += 1;
  return out;
}

/** Group access via a group, named: "via minagri, hr". */
export function viaGroupsText(item: Pick<UserClientAccessItem, "via_groups">): string {
  return item.via_groups.map((g) => g.name ?? g.id).join(", ");
}
