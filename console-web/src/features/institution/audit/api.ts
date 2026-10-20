/** Institution audit trail (scoped server-side; no institution filter argument). */
import { keepPreviousData, queryOptions } from "@tanstack/react-query";

import { api, unwrapAs } from "@/api/client";
import { institutionKeys, type InstitutionScope } from "../api";
import { toQuery, type AuditFilters } from "./state/auditView";
import type { InstitutionAuditPage } from "./types";

export const instAuditKeys = {
  list: (scope: InstitutionScope, f: AuditFilters) => institutionKeys.scoped(scope, "audit", f),
};

export const institutionAuditQuery = (scope: InstitutionScope, f: AuditFilters) =>
  queryOptions({
    queryKey: instAuditKeys.list(scope, f),
    queryFn: () => unwrapAs<InstitutionAuditPage>(api.GET("/institution-admin/audit", { params: { query: { ...toQuery(f), ...scope } } })),
    placeholderData: keepPreviousData,
  });
