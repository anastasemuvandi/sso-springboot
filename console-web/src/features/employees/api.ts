/** Employee directory (IPPIS mirror): keys, queries. */
import { queryOptions } from "@tanstack/react-query";

import { api, unwrap } from "@/api/client";
import type { paths } from "@/api/schema";

export type EmployeeListParams = NonNullable<paths["/employees"]["get"]["parameters"]["query"]>;

export const employeeKeys = {
  all: ["employees"] as const,
  list: (params: EmployeeListParams) => [...employeeKeys.all, "list", params] as const,
  detail: (employeeNumber: string) => [...employeeKeys.all, "detail", employeeNumber] as const,
};

/**
 * GET /employees — one row per employment, with `summary`. Scope is the
 * wrapper's: a platform admin reads every institution, a delegated admin only
 * their own. 503 = the mirror database is not configured (not "no employees").
 */
export const employeesQuery = (params: EmployeeListParams = {}) =>
  queryOptions({
    queryKey: employeeKeys.list(params),
    queryFn: () => unwrap(api.GET("/employees", { params: { query: params } })),
  });

/**
 * GET /employees/{employeeNumber} — the full record, and the ONLY read that
 * returns `nationalId`. 404 means "not visible to you" and deliberately does
 * not distinguish "does not exist" from "another institution's".
 */
export const employeeQuery = (employeeNumber: string) =>
  queryOptions({
    queryKey: employeeKeys.detail(employeeNumber),
    queryFn: () => unwrap(api.GET("/employees/{employeeNumber}", { params: { path: { employeeNumber } } })),
    staleTime: 5 * 60_000,
  });
