/**
 * Institution employees + provisioning: keys, queries, mutations.
 * Keys are rooted at institutionKeys.scoped(scope, "employees" | "provision-apps").
 */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrap, unwrapAs } from "@/api/client";
import type { Schemas } from "@/api/types";
import type { EmployeeListParams } from "@/features/employees/api";
import { institutionKeys, type InstitutionScope } from "../api";
import type { InstitutionApp } from "../types";

export type ProvisionReport = Schemas["ProvisionReportView"];
export type ProvisionEmployeeRow = Schemas["EmployeeReport"];
/** GET /employees query, minus `institution` (taken from the scope). */
export type EmployeeListQuery = Omit<EmployeeListParams, "institution">;

export const instEmployeeKeys = {
  list: (scope: InstitutionScope, q: EmployeeListQuery) => institutionKeys.scoped(scope, "employees", q),
  apps: (scope: InstitutionScope) => institutionKeys.scoped(scope, "provision-apps"),
};

/** GET /employees scoped to this institution (the wrapper pins a delegated admin regardless). */
export const institutionEmployeesQuery = (scope: InstitutionScope, q: EmployeeListQuery) =>
  queryOptions({
    queryKey: instEmployeeKeys.list(scope, q),
    queryFn: () =>
      unwrap(
        api.GET("/employees", {
          params: { query: { ...q, ...(scope.institution ? { institution: scope.institution } : {}) } },
        }),
      ),
  });

/** GET /institution-admin/apps — the apps this institution may assign. */
export const provisionAppsQuery = (scope: InstitutionScope) =>
  queryOptions({
    queryKey: instEmployeeKeys.apps(scope),
    queryFn: () => unwrapAs<InstitutionApp[]>(api.GET("/institution-admin/apps", { params: { query: scope } })),
  });

export interface ProvisionBody {
  employee_numbers: string[];
  client_ids: string[];
  dry_run: boolean;
}

/** POST /institution-admin/employees/provision — dry_run returns the identical report, writing nothing. */
export function provisionEmployees(scope: InstitutionScope, body: ProvisionBody): Promise<ProvisionReport> {
  return unwrap(api.POST("/institution-admin/employees/provision", { params: { query: scope }, body }));
}
