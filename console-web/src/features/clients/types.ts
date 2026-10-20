/**
 * Client (application) shapes, as the wrapper returns them. Field names are the
 * wire names (Keycloak camelCase for the representation, snake_case for the
 * wrapper's own objects). Where the generated schema types an endpoint, prefer
 * it; these cover the endpoints the contract leaves untyped and the parsed
 * `scope` / `join_column` objects.
 */

/** The realm's TOTP mode. `per_client`: a client requires TOTP unless set otherwise on it. */
export type MfaMode = "off" | "per_client" | "all";

/** GET /realm/mfa */
export interface RealmMfaState {
  mode: MfaMode;
  /** `mfa_totp_mode` exactly as stored, or null when unset. */
  raw_mode: string | null;
  enforced: boolean;
  effective: boolean;
  /** Legacy `mfa_totp_enforced` exactly as stored. */
  raw: string | null;
  browser_flow: string;
  flow_ready: boolean;
  first_enabled_at?: string | null;
  exempt_count?: number;
}

/** GET/PUT /client/clients/{id}/mfa */
export interface ClientMfaState {
  client_id: string;
  required: boolean;
  raw?: string | null;
  mode: MfaMode;
  applies_now: boolean;
  previous?: boolean;
  changed?: boolean;
}

/** PUT /realm/mfa */
export interface RealmMfaChange {
  mode: MfaMode;
  previous_mode: MfaMode;
  enforced: boolean;
  previous: boolean;
  changed: boolean;
  bulk_enrol: null | { started: boolean; reason: string };
}

export type ClientScopeKind = "institution" | "cross-cutting" | "platform";

export interface ClientScope {
  client_id: string;
  kind: ClientScopeKind;
  owner_institution: string | null;
  cross_cutting: boolean;
  /** Dynamic: institutions onboarded later are included. */
  all_institutions: boolean;
  institutions: string[];
  /** Owner read from a `<slug>-` prefix rather than an attribute. */
  inferred: boolean;
}

export type JoinColumn = "nid" | "phone" | "email";

export interface JoinColumnSetting {
  client_id: string;
  join_column: JoinColumn;
  claim: string;
  user_source: string;
  /** False when nothing is recorded and the column is the platform default (email). */
  explicit: boolean;
  changed_by?: string | null;
  changed_at?: string | null;
  malformed_value?: string | null;
}

export interface Client {
  id: string;
  clientId: string;
  name?: string | null;
  description?: string | null;
  enabled?: boolean;
  redirectUris?: string[];
  webOrigins?: string[];
  baseUrl?: string | null;
  rootUrl?: string | null;
  publicClient?: boolean;
  protocol?: string;
  standardFlowEnabled?: boolean;
  directAccessGrantsEnabled?: boolean;
  serviceAccountsEnabled?: boolean;
  attributes?: Record<string, string>;
  authenticationFlowBindingOverrides?: Record<string, string>;
  scope?: ClientScope;
  join_column?: JoinColumnSetting;
}

/** One institution's current foothold on a client (the removal warning). */
export interface ClientScopeImpact {
  institution: string;
  group_assigned: boolean;
  users: number;
}

export interface ClientScopeState {
  scope: ClientScope;
  access: ClientScopeImpact[];
}

export interface ClientScopeChange {
  scope: ClientScope;
  previous: ClientScope;
  removed_institutions: string[];
  access_withdrawn: ClientScopeImpact[];
}

/** Body of PUT /client/clients/{id}/scope (and the scope half of create). */
export interface ClientScopeFields {
  cross_cutting?: boolean;
  institutions?: string[];
  owner_institution?: string;
}

export interface JoinColumnChoice {
  value: JoinColumn;
  label: string;
  source: string;
  shape: string;
  default: boolean;
}

export interface JoinKeyBlockedUser {
  user_id: string;
  username?: string | null;
  error: "join_key_missing" | "join_key_invalid" | "join_key_not_unique";
  detail: string;
}

export interface JoinKeyReadiness {
  join_column: JoinColumn;
  checked: number;
  blocked: number;
  truncated: boolean;
  users: JoinKeyBlockedUser[];
}

export interface JoinColumnState {
  setting: JoinColumnSetting;
  choices: JoinColumnChoice[];
  /** -1 when the count could not be read. */
  active_sessions: number;
  readiness?: JoinKeyReadiness | null;
}

export interface JoinColumnChange {
  setting: JoinColumnSetting;
  previous: JoinColumnSetting;
  active_sessions: number;
  assigned_users: number;
  mapper: "created" | "updated" | "unchanged";
  readiness: JoinKeyReadiness;
}

/** GET/PUT /authorization/clients/{clientId}/access */
export interface ClientAccess {
  client_id: string;
  enforced: boolean;
  users: Array<{ id: string; username: string | null }>;
  groups: Array<{ id: string; name: string | null }>;
  /** Deny-list (`sso-access-denied-users`), when the wrapper reports it. */
  denied_users?: Array<{ id: string; username: string | null }>;
}

/**
 * Clients Keycloak creates for itself; hidden from the client list and the
 * join-key worklist (reference: lib/internal-clients.ts).
 */
export const KEYCLOAK_INTERNAL_CLIENTS = new Set([
  "account",
  "account-console",
  "admin-cli",
  "broker",
  "realm-management",
  "security-admin-console",
  "admin-permissions",
]);
