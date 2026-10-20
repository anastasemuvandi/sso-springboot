/**
 * Clients feature: query keys, queries and mutations. Keys start with
 * "clients" so `invalidateQueries({ queryKey: clientKeys.all })` refreshes
 * every client read.
 *
 * Every rule (admin tier, scope narrowing, join-key re-keying, super-admin-only
 * MFA changes) is enforced by the wrapper; these helpers only carry the
 * request and surface its answer (ApiError on failure).
 */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrap, unwrapAs } from "@/api/client";
import { ApiError } from "@/api/errors";
import type { UserClientAccessChangeResult, UserClientAccessState } from "@/features/users/types";
import type {
  Client,
  ClientAccess,
  ClientMfaState,
  ClientScopeChange,
  ClientScopeFields,
  ClientScopeState,
  JoinColumn,
  JoinColumnChange,
  JoinColumnState,
} from "./types";

export const clientKeys = {
  all: ["clients"] as const,
  list: () => [...clientKeys.all, "list"] as const,
  detail: (id: string) => [...clientKeys.all, "detail", id] as const,
  scope: (id: string) => [...clientKeys.all, "scope", id] as const,
  access: (id: string) => [...clientKeys.all, "access", id] as const,
  joinColumn: (id: string, check: boolean) => [...clientKeys.all, "join-column", id, check] as const,
  joinColumnAny: (id: string) => [...clientKeys.all, "join-column", id] as const,
  mfa: (id: string) => [...clientKeys.all, "mfa", id] as const,
  institutions: () => [...clientKeys.all, "institution-options"] as const,
  agencies: () => [...clientKeys.all, "agency-options"] as const,
  userSearch: (q: string) => [...clientKeys.all, "user-search", q] as const,
};

const path = (client_id: string) => ({ params: { path: { client_id } } });

/** GET /client/clients — every client in the realm, with parsed `scope` and `join_column`. */
export const clientsListQuery = () =>
  queryOptions({
    queryKey: clientKeys.list(),
    queryFn: () => unwrapAs<Client[]>(api.GET("/client/clients")),
  });

/** GET /client/clients/{client_id} */
export const clientQuery = (clientId: string) =>
  queryOptions({
    queryKey: clientKeys.detail(clientId),
    queryFn: () => unwrapAs<Client>(api.GET("/client/clients/{client_id}", path(clientId))),
  });

/** GET /client/clients/{client_id}/scope — scope + per-institution foothold (the removal warning's numbers). */
export const clientScopeQuery = (clientId: string) =>
  queryOptions({
    queryKey: clientKeys.scope(clientId),
    queryFn: () => unwrapAs<ClientScopeState>(api.GET("/client/clients/{client_id}/scope", path(clientId))),
  });

/** GET /authorization/clients/{client_id}/access — who may log in. */
export const clientAccessQuery = (clientId: string) =>
  queryOptions({
    queryKey: clientKeys.access(clientId),
    queryFn: () => unwrapAs<ClientAccess>(api.GET("/authorization/clients/{client_id}/access", path(clientId))),
  });

/**
 * GET /client/clients/{client_id}/join-column. `check` also reports which
 * assigned users the column would block — a read per assigned user, so only
 * on demand.
 */
export const joinColumnQuery = (clientId: string, check = false) =>
  queryOptions({
    queryKey: clientKeys.joinColumn(clientId, check),
    queryFn: () =>
      unwrapAs<JoinColumnState>(
        api.GET("/client/clients/{client_id}/join-column", { params: { path: { client_id: clientId }, query: { check } } }),
      ),
  });

/** GET /client/clients/{client_id}/mfa */
export const clientMfaQuery = (clientId: string) =>
  queryOptions({
    queryKey: clientKeys.mfa(clientId),
    queryFn: () => unwrapAs<ClientMfaState>(api.GET("/client/clients/{client_id}/mfa", path(clientId))),
  });

export interface InstitutionOption {
  slug: string;
  displayName: string;
}

/**
 * Candidates for the scope chooser: `/institutions` (registry × Keycloak ×
 * IPPIS), active registry entries only — a suspended institution has no group
 * to assign.
 */
export const institutionOptionsQuery = () =>
  queryOptions({
    queryKey: clientKeys.institutions(),
    queryFn: async (): Promise<InstitutionOption[]> => {
      const data = await unwrap(api.GET("/institutions"));
      return (data.items ?? [])
        .filter((i) => i.slug && i.registry_status === "active")
        .map((i) => ({ slug: i.slug as string, displayName: i.display_name ?? (i.slug as string) }))
        .sort((a, b) => a.slug.localeCompare(b.slug));
    },
  });

export interface AgencyOption {
  id: string;
  name: string;
  active: boolean;
}

/** GET /agency/ — groups for the access picker and the list's institution filter. */
export const agencyOptionsQuery = () =>
  queryOptions({
    queryKey: clientKeys.agencies(),
    queryFn: async (): Promise<AgencyOption[]> => {
      const data = await unwrap(api.GET("/agency/"));
      return (data ?? [])
        .map((a) => {
          const r = a as { id: string; name: string; activeMembers?: number | null; totalMembers?: number | null; member_count?: number | null };
          return { id: r.id, name: r.name, active: (r.activeMembers ?? r.totalMembers ?? r.member_count ?? 0) > 0 };
        })
        .sort((a, b) => a.name.localeCompare(b.name));
    },
  });

export interface UserCandidate {
  id: string;
  username: string;
  name: string;
  email: string | null;
  agencies: Array<{ id: string; name: string }>;
}

/** GET /users/?search= — typeahead for the access picker (first 25). */
export const userSearchQuery = (q: string) =>
  queryOptions({
    queryKey: clientKeys.userSearch(q),
    queryFn: async (): Promise<UserCandidate[]> => {
      const data = await unwrap(api.GET("/users/", { params: { query: { search: q || undefined, page: 1, page_size: 25 } } }));
      return (data.users ?? []).map((u) => ({
        id: u.id,
        username: u.username,
        name: [u.firstName, u.lastName].filter(Boolean).join(" "),
        email: u.email ?? undefined,
        agencies: (u.agencies ?? []).map((a) => ({ id: a.id, name: a.name })),
      }));
    },
  });

// ---------------------------------------------------------------------------
// Mutations
// ---------------------------------------------------------------------------

export interface ClientCreateBody extends ClientScopeFields {
  client_name: string;
  redirect_uris: string[];
  post_logout_redirect_uris?: string;
  protocol: "openid-connect" | "saml";
}

export const clientsApi = {
  create: (body: ClientCreateBody) =>
    unwrapAs<Client>(
      api.POST("/client/clients", {
        body: {
          client_name: body.client_name,
          redirect_uris: body.redirect_uris,
          post_logout_redirect_uris: body.post_logout_redirect_uris ?? undefined,
          protocol: body.protocol,
          cross_cutting: Boolean(body.cross_cutting),
          institutions: body.institutions ?? undefined,
          owner_institution: body.owner_institution ?? undefined,
        },
      }),
    ),
  remove: (clientId: string) => unwrap(api.DELETE("/client/clients/{client_id}", path(clientId))),
  setRedirectUris: (clientId: string, redirect_uris: string[]) =>
    unwrapAs<Client>(api.PUT("/client/clients/{client_id}/redirect-uris", { ...path(clientId), body: { redirect_uris } })),
  setPostLogoutUris: (clientId: string, post_logout_redirect_uris: string[]) =>
    unwrapAs<Client>(
      api.PUT("/client/clients/{client_id}/post-logout-redirect-uris", { ...path(clientId), body: { post_logout_redirect_uris } }),
    ),
  getSecret: (clientId: string) =>
    unwrapAs<{ client_secret: string }>(api.GET("/client/clients/{client_id}/secret", path(clientId))),
  regenerateSecret: (clientId: string) =>
    unwrapAs<{ client_secret: string }>(api.POST("/client/clients/{client_id}/regenerate-secret", path(clientId))),
  /**
   * PUT /client/clients/{id}/scope. Without `confirm` a narrowing that removes
   * an institution with users answers 409 (see `parseScopeConflict`).
   */
  setScope: (clientId: string, fields: ClientScopeFields, confirm: boolean) =>
    unwrapAs<ClientScopeChange>(
      api.PUT("/client/clients/{client_id}/scope", {
        ...path(clientId),
        body: {
          confirm,
          cross_cutting: Boolean(fields.cross_cutting),
          institutions: fields.institutions ?? undefined,
          owner_institution: fields.owner_institution ?? undefined,
        },
      }),
    ),
  /** PUT /client/clients/{id}/join-column — 409 with active_sessions / assigned_users until confirmed. */
  setJoinColumn: (clientId: string, join_column: JoinColumn, confirm: boolean) =>
    unwrapAs<JoinColumnChange>(api.PUT("/client/clients/{client_id}/join-column", { ...path(clientId), body: { join_column, confirm } })),
  /** PUT /client/clients/{id}/mfa — super admin only (wrapper-enforced). */
  setMfa: (clientId: string, required: boolean) =>
    unwrapAs<ClientMfaState>(api.PUT("/client/clients/{client_id}/mfa", { ...path(clientId), body: { required } })),
  /** PUT /authorization/clients/{id}/access — replaces the whole assignment. */
  setAccess: (clientId: string, body: { user_ids: string[]; group_ids: string[] }) =>
    unwrapAs<ClientAccess>(api.PUT("/authorization/clients/{client_id}/access", { ...path(clientId), body })),
  /**
   * POST /authorization/users/{user}/client-access/{client} — one person, one
   * client: `revoke` puts them on `sso-access-denied-users`, `grant` admits
   * them and lifts a denial. 409 `state_changed` if not `expected_state`.
   */
  changeUserAccess: (userId: string, clientId: string, action: "grant" | "revoke" | "restore", expected_state: UserClientAccessState) =>
    unwrapAs<UserClientAccessChangeResult>(
      api.POST("/authorization/users/{user_id}/client-access/{client_id}", {
        params: { path: { user_id: userId, client_id: clientId } },
        body: { action, expected_state },
      }),
    ),
};

/** Join-key 409 details: live sessions and users a re-key re-matches. */
export interface JoinColumnConflict {
  activeSessions: number;
  assignedUsers: number;
  message: string;
}

export function parseJoinColumnConflict(e: unknown): JoinColumnConflict | null {
  if (!(e instanceof ApiError) || e.status !== 409) return null;
  const d = e.details;
  const err = e;
  return {
    activeSessions: typeof d.active_sessions === "number" ? d.active_sessions : 0,
    assignedUsers: typeof d.assigned_users === "number" ? d.assigned_users : 0,
    message: err.message,
  };
}

/** Post-logout URIs live in the `post.logout.redirect.uris` attribute, `##`-separated. */
export function postLogoutUris(client: Client): string[] {
  const raw = client.attributes?.["post.logout.redirect.uris"] ?? "";
  return raw ? raw.split("##").filter(Boolean) : [];
}

/** Split a textarea into URIs: newline- or comma-separated, trimmed, no blanks. */
export function splitUris(text: string): string[] {
  return text
    .split(/[\n,]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}
