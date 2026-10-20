/**
 * Institution apps: keys, queries, mutations. Every call carries the
 * institution scope; the wrapper decides (owned vs cross-cutting, the tier's
 * allow/deny) — the console only reflects it.
 */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrapAs } from "@/api/client";
import type { JoinColumn, JoinColumnChange, JoinColumnState } from "@/features/clients/types";
import { institutionKeys, type InstitutionScope } from "../api";
import type { InstitutionApp } from "../types";
import type { AppCreateBody } from "./state/appCreateForm";
import type { AppMfaChange, ClientUsers, JoinColumnBacklog, RevokeReport } from "./types";

export const instAppKeys = {
  all: (s: InstitutionScope) => institutionKeys.scoped(s, "apps"),
  list: (s: InstitutionScope) => [...instAppKeys.all(s), "list"] as const,
  joinColumns: (s: InstitutionScope) => [...instAppKeys.all(s), "join-columns"] as const,
  joinColumn: (s: InstitutionScope, clientId: string) => [...instAppKeys.all(s), "join-column", clientId] as const,
  users: (s: InstitutionScope, clientId: string) => [...instAppKeys.all(s), "users", clientId] as const,
};

const q = (s: InstitutionScope) => ({ institution: s.institution });
const path = (clientId: string) => ({ clientId });

export const instAppsQuery = (s: InstitutionScope) =>
  queryOptions({
    queryKey: instAppKeys.list(s),
    queryFn: () => unwrapAs<InstitutionApp[]>(api.GET("/institution-admin/apps", { params: { query: q(s) } })),
  });

export const instJoinColumnsQuery = (s: InstitutionScope) =>
  queryOptions({
    queryKey: instAppKeys.joinColumns(s),
    queryFn: () => unwrapAs<JoinColumnBacklog>(api.GET("/institution-admin/apps/join-columns", { params: { query: q(s) } })),
  });

/** `check: true` also reports which assigned staff the column would block. */
export const instJoinColumnQuery = (s: InstitutionScope, clientId: string) =>
  queryOptions({
    queryKey: instAppKeys.joinColumn(s, clientId),
    queryFn: () =>
      unwrapAs<JoinColumnState>(
        api.GET("/institution-admin/apps/{clientId}/join-column", { params: { path: path(clientId), query: { ...q(s), check: true } } }),
      ),
  });

export const instAppUsersQuery = (s: InstitutionScope, clientId: string) =>
  queryOptions({
    queryKey: instAppKeys.users(s, clientId),
    queryFn: () => unwrapAs<ClientUsers>(api.GET("/institution-admin/apps/{clientId}/users", { params: { path: path(clientId), query: q(s) } })),
  });

export const instAppsApi = {
  /** POST /apps — the app part only; the wrapper composes `<slug>-<app>`. */
  create: (s: InstitutionScope, body: AppCreateBody) =>
    unwrapAs<InstitutionApp>(api.POST("/institution-admin/apps", { params: { query: q(s) }, body })),
  /** Secret of an OWNED app (403 for a cross-cutting one). Never cached. */
  secret: (s: InstitutionScope, clientId: string) =>
    unwrapAs<{ client_secret: string }>(api.GET("/institution-admin/apps/{clientId}/secret", { params: { path: path(clientId), query: q(s) } })),
  /** Institution-wide grant: admits the institution's group on the client. */
  grantAccess: (s: InstitutionScope, clientId: string) =>
    unwrapAs<unknown>(api.POST("/institution-admin/apps/{clientId}/access", { params: { path: path(clientId), query: q(s) } })),
  revokeAccess: (s: InstitutionScope, clientId: string) =>
    unwrapAs<unknown>(api.DELETE("/institution-admin/apps/{clientId}/access", { params: { path: path(clientId), query: q(s) } })),
  /** Require TOTP — ON only; turning it off is a super admin's decision (403 here). */
  requireMfa: (s: InstitutionScope, clientId: string) =>
    unwrapAs<AppMfaChange>(
      api.PUT("/institution-admin/apps/{clientId}/mfa", { params: { path: path(clientId), query: q(s) }, body: { required: true } }),
    ),
  /** 409 until `confirm` when sessions are live or users would be re-keyed. */
  setJoinColumn: (s: InstitutionScope, clientId: string, join_column: JoinColumn, confirm: boolean) =>
    unwrapAs<JoinColumnChange>(
      api.PUT("/institution-admin/apps/{clientId}/join-column", { params: { path: path(clientId), query: q(s) }, body: { join_column, confirm } }),
    ),
  /** Withdraw users' access to clients; `preview: true` changes nothing. */
  revoke: (s: InstitutionScope, body: { user_ids: string[]; client_ids: string[]; preview?: boolean }) =>
    unwrapAs<RevokeReport>(
      api.POST("/institution-admin/access/revoke", {
        params: { query: q(s) },
        body: { user_ids: body.user_ids, client_ids: body.client_ids, preview: body.preview ?? false, all_clients: false, confirm_all_clients: false },
      }),
    ),
};
