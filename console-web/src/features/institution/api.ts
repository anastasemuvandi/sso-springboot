/**
 * Institution console API base. Every call goes to `/institution-admin/*`
 * (scoped server-side by InstitutionContext — the UI is not the boundary).
 *
 * `?institution=<slug>` is threaded through every call when the caller needs
 * it (a platform admin acting for an institution, or an admin of several);
 * `useInstitution().scope` carries it. Keys are rooted at ["institution", slug]
 * so switching institution never shows another institution's cache.
 *
 * Feature areas extend this with their own files (staff/api.ts, apps/api.ts,
 * employees/api.ts, access/api.ts) using `institutionKeys.scoped(scope, ...)`.
 */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrap, unwrapAs } from "@/api/client";
import type { Overview } from "@/features/overview/types";
import type { InstitutionContext, InstitutionPermissions } from "./types";

/** `{ institution }` when the URL names one, else `{}` (the wrapper pins a single-institution admin). */
export interface InstitutionScope {
  institution?: string;
}

export const institutionKeys = {
  all: ["institution"] as const,
  /** Root for everything read under one scope. */
  scoped: (scope: InstitutionScope, ...rest: unknown[]) => ["institution", scope.institution ?? "", ...rest] as const,
};

export const meQuery = (scope: InstitutionScope) =>
  queryOptions({
    queryKey: institutionKeys.scoped(scope, "me"),
    queryFn: () => unwrapAs<InstitutionContext>(api.GET("/institution-admin/me", { params: { query: scope } })),
    staleTime: 60_000,
  });

export const permissionsQuery = (scope: InstitutionScope) =>
  queryOptions({
    queryKey: institutionKeys.scoped(scope, "permissions"),
    queryFn: () => unwrapAs<InstitutionPermissions>(api.GET("/institution-admin/permissions", { params: { query: scope } })),
    staleTime: 60_000,
  });

export const institutionOverviewQuery = (scope: InstitutionScope) =>
  queryOptions({
    queryKey: institutionKeys.scoped(scope, "overview"),
    queryFn: () => unwrap(api.GET("/institution-admin/overview", { params: { query: scope } })) as Promise<Overview>,
  });
