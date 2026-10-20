/**
 * Users feature: query keys, queries and mutation helpers. Keys are rooted at
 * ["users"]; `userKeys.detail(id)` covers everything shown on one user page
 * (invalidate it after any change to that user).
 */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrap, unwrapAs } from "@/api/client";
import { KEYCLOAK_INTERNAL_CLIENTS, type Client, type ClientAccess } from "@/features/clients/types";
import type {
  GroupRef,
  MfaExemption,
  RealmRole,
  User,
  UserClientAccess,
  UserClientAccessChangeResult,
  UserClientAccessState,
  UserListParams,
  UserListResponse,
  UserRoles,
  UserSession,
} from "./types";

export const userKeys = {
  all: ["users"] as const,
  list: (params: UserListParams) => [...userKeys.all, "list", params] as const,
  detail: (id: string) => [...userKeys.all, "detail", id] as const,
  profile: (id: string) => [...userKeys.detail(id), "profile"] as const,
  roles: (id: string) => [...userKeys.detail(id), "roles"] as const,
  groups: (id: string) => [...userKeys.detail(id), "groups"] as const,
  sessions: (id: string) => [...userKeys.detail(id), "sessions"] as const,
  clientAccess: (id: string) => [...userKeys.detail(id), "client-access"] as const,
  realmRoles: () => [...userKeys.all, "realm-roles"] as const,
  clientRoles: (clientUuid: string) => [...userKeys.all, "client-roles", clientUuid] as const,
  agencies: () => [...userKeys.all, "agencies"] as const,
  agencyClients: () => [...userKeys.all, "agency-clients"] as const,
  exemptions: () => [...userKeys.all, "mfa-exemptions"] as const,
};

const path = (user_id: string) => ({ params: { path: { user_id } } });

// ---------------------------------------------------------------- reads

/** GET /users/ — the wrapper pages and filters (one agency/client/role at a time). */
export const usersListQuery = (params: UserListParams) =>
  queryOptions({
    queryKey: userKeys.list(params),
    queryFn: () => unwrapAs<UserListResponse>(api.GET("/users/", { params: { query: params } })),
  });

export const userQuery = (id: string) =>
  queryOptions({ queryKey: userKeys.profile(id), queryFn: () => unwrapAs<User>(api.GET("/users/{user_id}", path(id))) });

export const userRolesQuery = (id: string) =>
  queryOptions({
    queryKey: userKeys.roles(id),
    queryFn: () => unwrapAs<UserRoles>(api.GET("/roles/users/{user_id}/roles", path(id))),
  });

export const userGroupsQuery = (id: string) =>
  queryOptions({
    queryKey: userKeys.groups(id),
    queryFn: () => unwrapAs<GroupRef[]>(api.GET("/users/{user_id}/groups", path(id))),
  });

export const userSessionsQuery = (id: string) =>
  queryOptions({
    queryKey: userKeys.sessions(id),
    queryFn: () => unwrapAs<UserSession[]>(api.GET("/users/{user_id}/sessions", path(id))),
  });

/** GET /authorization/users/{id}/client-access — the token gate's own answer, deny-list included. */
export const userClientAccessQuery = (id: string) =>
  queryOptions({
    queryKey: userKeys.clientAccess(id),
    queryFn: () => unwrapAs<UserClientAccess>(api.GET("/authorization/users/{user_id}/client-access", path(id))),
  });

export const realmRolesQuery = () =>
  queryOptions({ queryKey: userKeys.realmRoles(), queryFn: () => unwrapAs<RealmRole[]>(api.GET("/roles/realm-roles")) });

/** GET /roles/clients/{uuid}/roles — `client_id` here is the client's UUID. */
export const clientRolesQuery = (clientUuid: string) =>
  queryOptions({
    queryKey: userKeys.clientRoles(clientUuid),
    queryFn: () =>
      unwrapAs<RealmRole[]>(api.GET("/roles/clients/{client_id}/roles", { params: { path: { client_id: clientUuid } } })),
  });

/** GET /agency/ — the institutions (top-level groups). */
export const agenciesQuery = () =>
  queryOptions({ queryKey: userKeys.agencies(), queryFn: () => unwrapAs<GroupRef[]>(api.GET("/agency/")) });

/** GET /realm/mfa/exemptions */
export const mfaExemptionsQuery = () =>
  queryOptions({
    queryKey: userKeys.exemptions(),
    queryFn: async () => (await unwrapAs<{ exemptions: MfaExemption[] }>(api.GET("/realm/mfa/exemptions"))).exemptions ?? [],
  });

/** GET /authorization/clients/{clientId}/access for one client. */
export function fetchClientAccess(clientId: string) {
  return unwrapAs<ClientAccess>(api.GET("/authorization/clients/{client_id}/access", { params: { path: { client_id: clientId } } }));
}

/**
 * Institution → the clients it is assigned to (its group is on the client's
 * access policy), read from every user-facing client's access set. Used by
 * the new-user picker: only clients the chosen institution holds are offered.
 */
export const agencyClientsQuery = () =>
  queryOptions({
    queryKey: userKeys.agencyClients(),
    queryFn: async () => {
      const clients = (await unwrapAs<Client[]>(api.GET("/client/clients"))).filter((c) => !KEYCLOAK_INTERNAL_CLIENTS.has(c.clientId));
      const byAgency: Record<string, Array<{ clientId: string; name?: string | null }>> = {};
      await Promise.all(
        clients.map(async (c) => {
          try {
            const access = await fetchClientAccess(c.clientId);
            for (const g of access.groups) (byAgency[g.id] ??= []).push({ clientId: c.clientId, name: c.name });
          } catch {
            // A client whose access set cannot be read is not offered.
          }
        }),
      );
      for (const list of Object.values(byAgency)) list.sort((a, b) => a.clientId.localeCompare(b.clientId));
      return byAgency;
    },
  });

// ---------------------------------------------------------------- writes

export interface CreateUserBody {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
  enabled: boolean;
  emailVerified: boolean;
  attributes?: Record<string, string[]>;
}

export function createUser(body: CreateUserBody) {
  return unwrapAs<User>(api.POST("/users/", { body }));
}

export interface UpdateUserBody {
  email?: string;
  firstName?: string;
  lastName?: string;
  enabled?: boolean;
  emailVerified?: boolean;
  /** Merged server-side with existing attributes; an empty list clears a key. */
  attributes?: Record<string, string[]>;
}

export function updateUser(id: string, body: UpdateUserBody) {
  return unwrapAs<User>(api.PATCH("/users/{user_id}", { ...path(id), body }));
}

export function setUserEnabled(id: string, enabled: boolean) {
  return updateUser(id, { enabled });
}

export function resetPassword(id: string, value: string, temporary: boolean) {
  return unwrap(api.PUT("/users/{user_id}/reset-password", { ...path(id), body: { type: "password", value, temporary } }));
}

export function addToGroup(id: string, groupId: string) {
  return unwrap(api.PUT("/users/{user_id}/groups/{group_id}", { params: { path: { user_id: id, group_id: groupId } } }));
}

export function removeFromGroup(id: string, groupId: string) {
  return unwrap(api.DELETE("/users/{user_id}/groups/{group_id}", { params: { path: { user_id: id, group_id: groupId } } }));
}

/** POST /agency/{groupId}/members — how the create flow puts a new user in their institution. */
export function addAgencyMember(groupId: string, userId: string) {
  return unwrap(api.POST("/agency/{group_id}/members", { params: { path: { group_id: groupId } }, body: { userId } }));
}

type RoleRef = { id?: string; name: string };

export function assignRealmRole(id: string, role: RoleRef) {
  return unwrap(api.POST("/roles/users/{user_id}/realm-roles", { ...path(id), body: { user_id: id, roles: [role] } }));
}

export function removeRealmRole(id: string, role: RoleRef) {
  return unwrap(api.DELETE("/roles/users/{user_id}/realm-roles", { ...path(id), body: { user_id: id, roles: [role] } }));
}

export function assignClientRole(id: string, clientUuid: string, role: RoleRef) {
  return unwrap(
    api.POST("/roles/users/{user_id}/client-roles/{client_id}", {
      params: { path: { user_id: id, client_id: clientUuid } },
      body: { user_id: id, roles: [role] },
    }),
  );
}

export function removeClientRole(id: string, clientUuid: string, role: RoleRef) {
  return unwrap(
    api.DELETE("/roles/users/{user_id}/client-roles/{client_id}", {
      params: { path: { user_id: id, client_id: clientUuid } },
      body: { user_id: id, roles: [role] },
    }),
  );
}

export function logoutAllSessions(id: string) {
  return unwrap(api.DELETE("/users/{user_id}/sessions", path(id)));
}

export function logoutSession(id: string, sessionId: string) {
  return unwrap(api.DELETE("/users/{user_id}/sessions/{session_id}", { params: { path: { user_id: id, session_id: sessionId } } }));
}

/**
 * POST /authorization/users/{id}/client-access/{clientId} — one cell. The
 * wrapper refuses with 409 `state_changed` (nothing written) when the cell is
 * no longer `expectedState`.
 */
export function changeClientAccess(id: string, clientId: string, action: "grant" | "revoke" | "restore", expectedState: UserClientAccessState) {
  return unwrapAs<UserClientAccessChangeResult>(
    api.POST("/authorization/users/{user_id}/client-access/{client_id}", {
      params: { path: { user_id: id, client_id: clientId } },
      body: { action, expected_state: expectedState },
    }),
  );
}

/** POST /realm/mfa/exemptions — the only path that assigns `mfa-exempt` (super admin; reason required). */
export function grantMfaExemption(userId: string, reason: string) {
  return unwrap(api.POST("/realm/mfa/exemptions", { body: { user_id: userId, reason } }));
}

export function revokeMfaExemption(userId: string) {
  return unwrap(api.DELETE("/realm/mfa/exemptions/{user_id}", path(userId)));
}
