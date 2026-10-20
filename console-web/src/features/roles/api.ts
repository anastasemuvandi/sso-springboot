/** Realm roles: keys, queries, mutation calls. */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrap } from "@/api/client";
import type { Schemas } from "@/api/types";

export type RealmRole = Schemas["RoleView"];
export type RoleStatistics = Schemas["RoleStatistics"];

export const roleKeys = {
  all: ["roles"] as const,
  realm: () => [...roleKeys.all, "realm"] as const,
  statistics: () => [...roleKeys.all, "statistics"] as const,
};

export const realmRolesQuery = () =>
  queryOptions({ queryKey: roleKeys.realm(), queryFn: () => unwrap(api.GET("/roles/realm-roles")) });

export const roleStatisticsQuery = () =>
  queryOptions({ queryKey: roleKeys.statistics(), queryFn: () => unwrap(api.GET("/roles/statistics")) });

export const createRealmRole = (name: string, description?: string) =>
  unwrap(api.POST("/roles/realm-roles", { body: { name, description: description || undefined, composite: false } }));

export const updateRealmRole = (name: string, description: string) =>
  unwrap(
    api.PUT("/roles/realm-roles/{role_name}", {
      params: { path: { role_name: name } },
      body: { name, description: description || undefined, composite: false },
    }),
  );

export const deleteRealmRole = (name: string) =>
  unwrap(api.DELETE("/roles/realm-roles/{role_name}", { params: { path: { role_name: name } } }));
