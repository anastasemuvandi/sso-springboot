/** Shapes of the institution apps endpoints (reference lib/institution-client.ts). */
import type { JoinColumnChoice, JoinColumnSetting } from "@/features/clients/types";

/** One user who currently has access to a client. */
export interface AccessHolder {
  id: string;
  username: string;
  email?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  enabled: boolean;
  /** Assigned by name on this client. */
  direct: boolean;
  /** Groups of theirs the client admits — usually the institution's own. */
  via_groups: string[];
}

/** GET /institution-admin/apps/{clientId}/users — flow A. Only this institution's members. */
export interface ClientUsers {
  client_id: string;
  name?: string | null;
  institution: string;
  cross_cutting: boolean;
  /** False when the tier cannot change access here. */
  revocable: boolean;
  total: number;
  items: AccessHolder[];
}

export interface RevokeSkip {
  client_id: string;
  /** not_assigned | already_revoked | failed */
  reason: string;
  message?: string | null;
}

export interface RevokeUserResult {
  user_id: string;
  username?: string | null;
  revoked: string[];
  skipped: RevokeSkip[];
}

/** POST /institution-admin/access/revoke — the outcome, or its preview. */
export interface RevokeReport {
  institution: string;
  preview: boolean;
  all_clients: boolean;
  clients_considered: string[];
  users: RevokeUserResult[];
  total_revocations: number;
  total_skipped: number;
  users_affected: number;
}

/** One row of the join-column backfill worklist. */
export interface JoinColumnRow {
  clientId: string;
  name?: string | null;
  owned: boolean;
  cross_cutting: boolean;
  /** False for a cross-cutting app and for a tier without manage-clients. */
  configurable: boolean;
  configured: boolean;
  join_column: JoinColumnSetting;
}

/** GET /institution-admin/apps/join-columns */
export interface JoinColumnBacklog {
  institution: string;
  choices: JoinColumnChoice[];
  total: number;
  configured: number;
  unconfigured: number;
  outstanding: number;
  needs_platform_admin: number;
  malformed: number;
  items: JoinColumnRow[];
}

/** PUT /institution-admin/apps/{clientId}/mfa */
export interface AppMfaChange {
  client_id: string;
  required: boolean;
  previous: boolean;
  changed: boolean;
  mode: "off" | "per_client" | "all";
  applies_now: boolean;
}
