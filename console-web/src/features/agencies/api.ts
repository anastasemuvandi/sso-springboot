/**
 * Agencies (institutions = top-level Keycloak groups, ADR-0001): keys,
 * queries and mutation calls. The route and the wrapper keep the old `/agency`
 * name; the console calls them "Institutions".
 */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrap } from "@/api/client";
import type { Schemas } from "@/api/types";

export type Agency = Schemas["AgencyView"];
export type AgencyDetail = Schemas["AgencyView"];
export type AgencyMember = Schemas["MemberView"];
export type Subgroup = Schemas["SubgroupInfo"];
export type InstitutionOverview = Schemas["Overview"];
export type InstitutionRow = Schemas["Row"];

export const agencyKeys = {
  all: ["agencies"] as const,
  institutions: () => [...agencyKeys.all, "institutions"] as const,
  list: () => [...agencyKeys.all, "list"] as const,
  detail: (id: string) => [...agencyKeys.all, "detail", id] as const,
  members: (id: string) => [...agencyKeys.all, "members", id] as const,
  subgroups: (id: string) => [...agencyKeys.all, "subgroups", id] as const,
};

const gid = (id: string) => ({ params: { path: { group_id: id } } });

/** GET /institutions — registry × Keycloak × IPPIS reconciliation (platform admins). */
export const institutionsQuery = () =>
  queryOptions({ queryKey: agencyKeys.institutions(), queryFn: () => unwrap(api.GET("/institutions")) });

/** GET /agency/ — every top-level group, with membership counts. */
export const agenciesQuery = () => queryOptions({ queryKey: agencyKeys.list(), queryFn: () => unwrap(api.GET("/agency/")) });

export const agencyQuery = (id: string) =>
  queryOptions({ queryKey: agencyKeys.detail(id), queryFn: () => unwrap(api.GET("/agency/{group_id}", gid(id))) });

export const agencyMembersQuery = (id: string) =>
  queryOptions({ queryKey: agencyKeys.members(id), queryFn: () => unwrap(api.GET("/agency/{group_id}/members", gid(id))) });

/** Departments are the group's children (`/groups/{id}/children` in Keycloak). */
export const subgroupsQuery = (id: string) =>
  queryOptions({ queryKey: agencyKeys.subgroups(id), queryFn: () => unwrap(api.GET("/agency/{group_id}/subgroups", gid(id))) });

export function createAgency(input: { name: string; description?: string }) {
  // AgencyCreate has no description field; it is kept as a group attribute.
  return unwrap(
    api.POST("/agency/", {
      body: {
        name: input.name,
        attributes: input.description ? { description: [input.description] } : undefined,
        realmRoles: undefined,
        clientRoles: undefined,
      },
    }),
  );
}

export function updateAgency(id: string, input: { name?: string; description?: string; attributes?: Record<string, string[]> | null }) {
  const attributes = input.attributes ?? (input.description !== undefined ? { description: input.description ? [input.description] : [] } : undefined);
  return unwrap(api.PUT("/agency/{group_id}", { ...gid(id), body: { name: input.name || undefined, ...(attributes !== undefined ? { attributes } : {}) } }));
}

export const deleteAgency = (id: string) => unwrap(api.DELETE("/agency/{group_id}", gid(id)));

export const addMember = (id: string, userId: string) =>
  unwrap(api.POST("/agency/{group_id}/members", { ...gid(id), body: { userId } }));

export const removeMember = (id: string, userId: string) =>
  unwrap(api.DELETE("/agency/{group_id}/members/{user_id}", { params: { path: { group_id: id, user_id: userId } } }));

export const assignRealmRoles = (id: string, roles: string[]) =>
  unwrap(api.POST("/agency/{group_id}/roles", { ...gid(id), body: { realmRoles: roles, clientRoles: undefined } }));

export const removeRealmRoles = (id: string, roles: string[]) =>
  unwrap(api.DELETE("/agency/{group_id}/roles", { ...gid(id), body: { realmRoles: roles, clientRoles: undefined } }));

export const assignClientRoles = (id: string, clientId: string, roles: string[]) =>
  unwrap(api.POST("/agency/{group_id}/roles", { ...gid(id), body: { realmRoles: undefined, clientRoles: { [clientId]: roles } } }));

export const removeClientRoles = (id: string, clientId: string, roles: string[]) =>
  unwrap(api.DELETE("/agency/{group_id}/roles", { ...gid(id), body: { realmRoles: undefined, clientRoles: { [clientId]: roles } } }));

export const createSubgroup = (id: string, name: string) =>
  unwrap(api.POST("/agency/{group_id}/subgroups", { ...gid(id), body: { name, attributes: undefined } }));

/** Find users to add as members: GET /users/?search=… */
export const searchUsers = (search: string) => unwrap(api.GET("/users/", { params: { query: { search, page_size: 10 } } }));
