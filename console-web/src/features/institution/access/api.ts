/**
 * Access area (matrix, import, revoke): keys, queries, mutations. Every call
 * carries the institution scope; the wrapper scopes and re-checks each one.
 * Keys sit under `institutionKeys.scoped(scope, "access", ...)`.
 */
import { queryOptions } from "@tanstack/react-query";

import { api, apiFetch, downloadFile, toQuery, unwrap, unwrapAs } from "@/api/client";
import { institutionKeys, type InstitutionScope } from "../api";
import type { InstitutionApp, Member } from "../types";
import type {
  AccessMatrixPage,
  AccessMatrixQuery,
  BulkAccessReport,
  ClientUsers,
  ImportPreview,
  ImportReport,
  Mode,
  RevokeReport,
  RevokeRequestBody,
  SetupEmailResend,
  UserClients,
} from "./types";

export const accessKeys = {
  all: (scope: InstitutionScope) => institutionKeys.scoped(scope, "access"),
  apps: (scope: InstitutionScope) => institutionKeys.scoped(scope, "access", "apps"),
  matrix: (scope: InstitutionScope, q: AccessMatrixQuery) => institutionKeys.scoped(scope, "access", "matrix", q),
  members: (scope: InstitutionScope, search: string) => institutionKeys.scoped(scope, "access", "members", search),
  appUsers: (scope: InstitutionScope, clientId: string) => institutionKeys.scoped(scope, "access", "app-users", clientId),
  clientsForUsers: (scope: InstitutionScope, userIds: string[]) => institutionKeys.scoped(scope, "access", "clients-for-users", [...userIds].sort()),
  revokePreview: (scope: InstitutionScope, body: RevokeRequestBody) => institutionKeys.scoped(scope, "access", "revoke-preview", body),
};

/** GET /institution-admin/apps — the apps this institution may use (own + shared). */
export const accessAppsQuery = (scope: InstitutionScope) =>
  queryOptions({
    queryKey: accessKeys.apps(scope),
    queryFn: () => unwrapAs<InstitutionApp[]>(api.GET("/institution-admin/apps", { params: { query: scope } })),
  });

function matrixParams(scope: InstitutionScope, q: AccessMatrixQuery) {
  return {
    ...scope,
    clients: q.clients.join(","),
    page: q.page,
    page_size: q.page_size,
    q: q.q || undefined,
    access: q.access || undefined,
    access_client: q.access ? q.access_client || undefined : undefined,
    gender: q.gender || undefined,
  };
}

/** GET /institution-admin/access-matrix — one page of employees × the chosen apps. */
export const matrixQuery = (scope: InstitutionScope, q: AccessMatrixQuery) =>
  queryOptions({
    queryKey: accessKeys.matrix(scope, q),
    queryFn: () => unwrap(api.GET("/institution-admin/access-matrix", { params: { query: matrixParams(scope, q) } })) as Promise<unknown> as Promise<AccessMatrixPage>,
    enabled: q.clients.length > 0,
  });

/** POST /institution-admin/grants/bulk — per-item results; 403 for the whole request if any item is out of scope. */
export function bulkAccess(scope: InstitutionScope, action: Mode, items: Array<{ employeeNumber: string; clientId: string }>) {
  return unwrap(
    api.POST("/institution-admin/grants/bulk", { params: { query: scope }, body: { action, items, channel: "ui" } }),
  ) as Promise<unknown> as Promise<BulkAccessReport>;
}

/** GET /access-matrix/nid/{n} — one full NID. Audited by the wrapper on every call. */
export function revealNid(scope: InstitutionScope, employeeNumber: string) {
  return unwrapAs<{ employee_number: string; nid: string | null }>(
    api.GET("/institution-admin/access-matrix/nid/{employee_number}", { params: { path: { employee_number: employeeNumber }, query: scope } }),
  );
}

/** POST /access-matrix/setup-email/{n} — 409 when the last email did go out (the holder resends it). */
export function resendSetupEmail(scope: InstitutionScope, employeeNumber: string) {
  return unwrapAs<SetupEmailResend>(
    api.POST("/institution-admin/access-matrix/setup-email/{employee_number}", { params: { path: { employee_number: employeeNumber }, query: scope } }),
  );
}

/** GET /access-matrix/export — a protected .xlsx of the chosen apps. */
export function exportMatrix(scope: InstitutionScope, clients: string[]) {
  return downloadFile(`/institution-admin/access-matrix/export${toQuery({ ...scope, clients: clients.join(",") })}`, "access-matrix.xlsx");
}

/**
 * The import preview request, as the wrapper declares it: the multipart part
 * is `file` only; `approved_by` and `institution` are query parameters
 * (AccessMatrixController#preview).
 */
export function importFormData(file: File): FormData {
  const form = new FormData();
  form.set("file", file, file.name || "access-matrix.xlsx");
  return form;
}

export function importPath(scope: InstitutionScope, approvedBy: string): string {
  return `/institution-admin/access-matrix/import${toQuery({ ...scope, approved_by: approvedBy })}`;
}

/** POST /access-matrix/import (multipart) — the preview; writes nothing. */
export async function previewImport(scope: InstitutionScope, file: File, approvedBy: string): Promise<ImportPreview> {
  const form = importFormData(file);
  const res = await apiFetch(importPath(scope, approvedBy), {
    method: "POST",
    body: form,
    headers: { Accept: "application/json" },
  });
  return (await res.json()) as ImportPreview;
}

/** POST /access-matrix/import/{id}/apply — applies the server's stored plan, not the file. */
export function applyImport(scope: InstitutionScope, importId: string) {
  return unwrapAs<ImportReport>(
    api.POST("/institution-admin/access-matrix/import/{import_id}/apply", { params: { path: { import_id: importId }, query: scope } }),
  );
}

/** GET /institution-admin/users — members of this institution. */
export const membersQuery = (scope: InstitutionScope, search: string) =>
  queryOptions({
    queryKey: accessKeys.members(scope, search),
    queryFn: () => unwrapAs<Member[]>(api.GET("/institution-admin/users", { params: { query: { ...scope, search: search || undefined } } })),
  });

/** GET /apps/{id}/users — everyone in the institution who can use this app (flow A). */
export const appUsersQuery = (scope: InstitutionScope, clientId: string) =>
  queryOptions({
    queryKey: accessKeys.appUsers(scope, clientId),
    queryFn: () => unwrapAs<ClientUsers>(api.GET("/institution-admin/apps/{clientId}/users", { params: { path: { clientId }, query: scope } })),
    enabled: Boolean(clientId),
  });

/** POST /access/clients-for-users — which apps the selected users hold (a POST for a read). */
export const clientsForUsersQuery = (scope: InstitutionScope, userIds: string[]) =>
  queryOptions({
    queryKey: accessKeys.clientsForUsers(scope, userIds),
    queryFn: () => unwrapAs<UserClients>(api.POST("/institution-admin/access/clients-for-users", { params: { query: scope }, body: { user_ids: userIds } })),
    enabled: userIds.length > 0,
  });

/** POST /access/revoke — `preview: true` for the confirmation, then the real call. */
export function revokeAccess(scope: InstitutionScope, body: RevokeRequestBody) {
  return unwrapAs<RevokeReport>(
    api.POST("/institution-admin/access/revoke", {
      params: { query: scope },
      body: {
        user_ids: body.user_ids,
        client_ids: body.client_ids ?? undefined,
        all_clients: body.all_clients ?? false,
        confirm_all_clients: body.confirm_all_clients ?? false,
        preview: body.preview ?? false,
      },
    }),
  );
}

export const revokePreviewQuery = (scope: InstitutionScope, body: RevokeRequestBody | null) =>
  queryOptions({
    queryKey: accessKeys.revokePreview(scope, body ?? { user_ids: [] }),
    queryFn: () => revokeAccess(scope, body!),
    enabled: body !== null,
    staleTime: 0,
  });
