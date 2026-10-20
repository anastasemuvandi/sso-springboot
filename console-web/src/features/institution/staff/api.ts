/**
 * Institution staff (members) and roles: keys, queries, mutations.
 * Every call carries the institution scope; the wrapper applies the mandatory
 * institution filter (and a per-object 403 for another institution's id).
 */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrapAs } from "@/api/client";
import { institutionKeys, type InstitutionScope } from "../api";
import type { AssignableRole, Member } from "../types";
import { createStaffBody, updateStaffBody, type CreateStaffValues, type UpdateStaffValues } from "./state/staffForm";

export const staffKeys = {
  all: (scope: InstitutionScope) => institutionKeys.scoped(scope, "staff"),
  list: (scope: InstitutionScope, search?: string) => institutionKeys.scoped(scope, "staff", "list", search ?? ""),
  detail: (scope: InstitutionScope, id: string) => institutionKeys.scoped(scope, "staff", "detail", id),
  roles: (scope: InstitutionScope) => institutionKeys.scoped(scope, "roles"),
};

/** GET /institution-admin/users — `search` searches within the institution. */
export const membersQuery = (scope: InstitutionScope, search?: string) =>
  queryOptions({
    queryKey: staffKeys.list(scope, search),
    queryFn: () => unwrapAs<Member[]>(api.GET("/institution-admin/users", { params: { query: { ...scope, search: search || undefined } } })),
  });

export const memberQuery = (scope: InstitutionScope, id: string) =>
  queryOptions({
    queryKey: staffKeys.detail(scope, id),
    queryFn: () => unwrapAs<Member>(api.GET("/institution-admin/users/{userId}", { params: { path: { userId: id }, query: scope } })),
  });

/** GET /institution-admin/roles — already filtered by the predicate that guards assignment. */
export const assignableRolesQuery = (scope: InstitutionScope) =>
  queryOptions({
    queryKey: staffKeys.roles(scope),
    queryFn: () => unwrapAs<AssignableRole[]>(api.GET("/institution-admin/roles", { params: { query: scope } })),
  });

export const staffApi = {
  create: (scope: InstitutionScope, v: CreateStaffValues) =>
    unwrapAs<Member>(api.POST("/institution-admin/users", { params: { query: scope }, body: createStaffBody(v) })),
  update: (scope: InstitutionScope, id: string, v: UpdateStaffValues) =>
    unwrapAs<Member>(api.PATCH("/institution-admin/users/{userId}", { params: { path: { userId: id }, query: scope }, body: updateStaffBody(v) })),
  /** Re-enabling is an ordinary field update; deactivating also ends sessions (dedicated endpoint). */
  reactivate: (scope: InstitutionScope, id: string) =>
    unwrapAs<Member>(api.PATCH("/institution-admin/users/{userId}", { params: { path: { userId: id }, query: scope }, body: { enabled: true } })),
  /** Disables the account and terminates its sessions. There is no hard delete. */
  deactivate: (scope: InstitutionScope, id: string) =>
    unwrapAs<unknown>(api.POST("/institution-admin/users/{userId}/deactivate", { params: { path: { userId: id }, query: scope } })),
  assignRole: (scope: InstitutionScope, id: string, role: string) =>
    unwrapAs<unknown>(api.POST("/institution-admin/users/{userId}/roles", { params: { path: { userId: id }, query: scope }, body: { role } })),
  revokeRole: (scope: InstitutionScope, id: string, role: string) =>
    unwrapAs<unknown>(api.DELETE("/institution-admin/users/{userId}/roles", { params: { path: { userId: id }, query: scope }, body: { role } })),
};
