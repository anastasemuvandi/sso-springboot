/** User shapes, as the wrapper returns them (wire names). */

export interface User {
  id: string;
  username: string;
  email?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  enabled: boolean;
  emailVerified?: boolean;
  createdTimestamp?: number;
  attributes?: Record<string, string[]>;
  agencies?: Array<{ id: string; name: string; path?: string }>;
  realm_roles?: string[];
  client_roles?: Record<string, string[]>;
  requiredActions?: string[];
}

export interface UserListResponse {
  users: User[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

export interface UserSession {
  id: string;
  username?: string;
  ipAddress?: string;
  start?: number;
  lastAccess?: number;
  clients?: Record<string, string>;
}

/**
 * One person's standing on one client, decided by the token gate's own rule
 * (spec §5.4):
 * - `granted` — named on the client and/or in a group it admits
 * - `open` — the client restricts nobody (or only other people)
 * - `revoked` — on the client's deny-list, which beats every grant
 * - `not_granted` — nothing admits them
 * - `exempt` — a control-plane client everyone may use
 * - `unknown` — the client's policies could not be read
 */
export type UserClientAccessState = "granted" | "open" | "revoked" | "not_granted" | "exempt" | "unknown";

export interface UserClientAccessItem {
  client_id: string;
  client_uuid: string;
  name: string | null;
  state: UserClientAccessState;
  has_access: boolean | null;
  reason: string | null;
  direct: boolean;
  via_groups: Array<{ id: string; name: string | null }>;
  denied: boolean;
  denied_by_name: boolean;
  denied_groups: Array<{ id: string; name: string | null }>;
  error: string | null;
}

/** GET /authorization/users/{userId}/client-access */
export interface UserClientAccess {
  user_id: string;
  username: string | null;
  groups: Array<{ id: string; name: string | null }>;
  items: UserClientAccessItem[];
}

/** POST /authorization/users/{userId}/client-access/{clientId} */
export interface UserClientAccessChangeResult {
  item: UserClientAccessItem;
  previous_state: UserClientAccessState;
  unassigned: boolean;
  denied: boolean;
  lifted_deny: boolean;
}

export interface RealmRole {
  id: string;
  name: string;
  description?: string;
  composite?: boolean;
  clientRole?: boolean;
}

/** GET /roles/users/{userId}/roles */
export interface UserRoles {
  user_id?: string;
  realm_roles: Array<{ id: string; name: string; description?: string | null }>;
  client_roles: Record<string, Array<{ id: string; name: string; description?: string | null }>>;
}

/** GET /users/{userId}/groups and GET /agency/ (the subset we read). */
export interface GroupRef {
  id: string;
  name: string;
  path?: string | null;
}

/** GET /realm/mfa/exemptions → { exemptions: [...] } */
export interface MfaExemption {
  user_id: string;
  username?: string | null;
  reason?: string | null;
  granted_by?: string | null;
  granted_at?: string | null;
}

/** Query of GET /users/ (server-paged). */
export interface UserListParams {
  search?: string;
  agency_id?: string;
  client_id?: string;
  role?: string;
  enabled?: boolean;
  page?: number;
  page_size?: number;
}

/** Realm role that exempts a person from TOTP — granted only via POST /realm/mfa/exemptions. */
export const MFA_EXEMPT_ROLE = "mfa-exempt";
export const MFA_EXEMPT_REASON_ATTRIBUTE = "mfa_exempt_reason";
export const MFA_EXEMPT_GRANTED_BY_ATTRIBUTE = "mfa_exempt_granted_by";
export const MFA_EXEMPT_GRANTED_AT_ATTRIBUTE = "mfa_exempt_granted_at";
