/** Reads the institution overview page makes (keys under "overview-reads" so they never collide with the feature pages' own caches). */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrap, unwrapAs } from "@/api/client";
import type { AuditEventPage } from "@/features/audit/types";
import { institutionKeys, type InstitutionScope } from "../api";
import type { InstitutionApp, Member } from "../types";

export interface JoinColumnBacklogLite {
  total: number;
  configured: number;
  unconfigured: number;
  outstanding: number;
  needs_platform_admin: number;
  malformed: number;
}

const k = (scope: InstitutionScope, what: string) => institutionKeys.scoped(scope, "overview-reads", what);

export const ovMembersQuery = (scope: InstitutionScope) =>
  queryOptions({ queryKey: k(scope, "members"), queryFn: () => unwrapAs<Member[]>(api.GET("/institution-admin/users", { params: { query: scope } })) });

export const ovEmployeesQuery = (scope: InstitutionScope) =>
  queryOptions({ queryKey: k(scope, "employees"), queryFn: () => unwrap(api.GET("/employees", { params: { query: { ...scope, limit: 1 } } })) });

export const ovAppsQuery = (scope: InstitutionScope) =>
  queryOptions({ queryKey: k(scope, "apps"), queryFn: () => unwrapAs<InstitutionApp[]>(api.GET("/institution-admin/apps", { params: { query: scope } })) });

export const ovJoinColumnsQuery = (scope: InstitutionScope) =>
  queryOptions({ queryKey: k(scope, "join-columns"), queryFn: () => unwrapAs<JoinColumnBacklogLite>(api.GET("/institution-admin/apps/join-columns", { params: { query: scope } })) });

export const ovAuditQuery = (scope: InstitutionScope) =>
  queryOptions({ queryKey: k(scope, "audit"), queryFn: () => unwrapAs<AuditEventPage>(api.GET("/institution-admin/audit", { params: { query: { ...scope, limit: 8 } } })) });

export const ovRolesQuery = (scope: InstitutionScope) =>
  queryOptions({ queryKey: k(scope, "roles"), queryFn: () => unwrapAs<unknown[]>(api.GET("/institution-admin/roles", { params: { query: scope } })) });
