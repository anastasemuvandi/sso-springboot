/**
 * /institution/roles — which of the institution's roles exist and who holds each.
 *
 * Assignment happens per user (Users → Roles). Holders are computed from the
 * member list (no "users with role X" endpoint on the delegated surface); a
 * map-roles-only tier lacks view-members, so holders degrade to "—".
 */
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { CapabilityGate } from "../components/InstitutionGate";
import { useInstitution } from "../context";
import { assignableRolesQuery, membersQuery } from "../staff/api";
import { inNamespace, roleHolders } from "../staff/state/staffForm";

function Roles() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const canSeeMembers = inst.can("view-members");
  const roles = useQuery(assignableRolesQuery(inst.scope));
  const members = useQuery({ ...membersQuery(inst.scope), enabled: canSeeMembers });
  const holders = members.data ? roleHolders(members.data) : null;
  const list = (roles.data ?? []).filter((r) => inNamespace(r.name, inst.ctx.institution)).sort((a, b) => a.name.localeCompare(b.name));

  return (
    <div className="space-y-6">
      <PageHeader title={t("instStaff.rolesPageTitle")} description={t("instStaff.rolesPageDescription", { institution: inst.ctx.institution })} />
      <div className="rounded-lg border border-border bg-card p-4 shadow-card">
        <div className="grid gap-3 sm:grid-cols-2">
          <div>
            <div className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{t("instStaff.allowedPatterns")}</div>
            <div className="mt-1 font-mono text-sm">{inst.perms.roles?.allow?.join(", ") || t("common.none")}</div>
          </div>
          <div>
            <div className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{t("instStaff.denied")}</div>
            <div className="mt-1 font-mono text-sm">{inst.perms.roles?.deny?.join(", ") || t("common.none")}</div>
          </div>
        </div>
        <p className="mt-3 text-xs text-muted-foreground">{t("instStaff.denyExplain")}</p>
      </div>

      {roles.isPending ? (
        <Loading />
      ) : roles.isError ? (
        <ErrorState error={roles.error} onRetry={() => void roles.refetch()} />
      ) : (
        <div className="rounded-xl border bg-card shadow-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("instStaff.role")}</TableHead>
                <TableHead>{t("common.description")}</TableHead>
                <TableHead>{t("instStaff.holders")}</TableHead>
                <TableHead className="text-right">{t("instStaff.who")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((r) => {
                const who = holders?.get(r.name) ?? [];
                return (
                  <TableRow key={r.name} data-testid={`role-row-${r.name}`}>
                    <TableCell className="font-mono text-xs">
                      {r.name}
                      {r.composite ? (
                        <Badge variant="outline" className="ml-2">
                          {t("instStaff.composite")}
                        </Badge>
                      ) : null}
                    </TableCell>
                    <TableCell className="max-w-md text-sm text-muted-foreground">{r.description ?? "—"}</TableCell>
                    <TableCell className="text-sm">{holders ? who.length : "—"}</TableCell>
                    <TableCell className="text-right text-xs text-muted-foreground">
                      {!canSeeMembers
                        ? t("instStaff.needsViewMembers")
                        : !holders
                          ? members.isError
                            ? t("common.notAvailable")
                            : "…"
                          : who.length
                            ? who.slice(0, 3).join(", ") + (who.length > 3 ? ` +${who.length - 3}` : "")
                            : t("instStaff.nobody")}
                    </TableCell>
                  </TableRow>
                );
              })}
              {list.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">
                    {t("instStaff.noRoles")}
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
        </div>
      )}
      {canSeeMembers ? (
        <div className="flex justify-end">
          <Button asChild variant="outline" size="sm">
            <Link to={inst.path("/institution/staff")}>{t("instStaff.goToUsers")}</Link>
          </Button>
        </div>
      ) : null}
    </div>
  );
}

export function Component() {
  return (
    <CapabilityGate capability="map-roles">
      <Roles />
    </CapabilityGate>
  );
}
