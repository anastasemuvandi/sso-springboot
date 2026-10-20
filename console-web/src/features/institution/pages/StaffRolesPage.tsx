/**
 * /institution/staff/:id/roles — grant and revoke this institution's roles.
 *
 * Only `inst:<slug>:*` roles: the assignable set (GET /institution-admin/roles)
 * is filtered server-side by the same predicate that guards the write, and
 * again to the namespace here. Roles the account holds outside the namespace
 * are a platform admin's and are not shown, only counted.
 */
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { CapabilityGate } from "../components/InstitutionGate";
import { useInstitution } from "../context";
import { memberRoles, type AssignableRole } from "../types";
import { assignableRolesQuery, memberQuery, staffApi, staffKeys } from "../staff/api";
import { MemberReadError } from "../staff/components/StaffShared";
import { namespaceOf, splitRoles } from "../staff/state/staffForm";

function RoleRow({ role, action }: { role: AssignableRole; action: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3 rounded-md border border-border px-3 py-2" data-testid={`role-${role.name}`}>
      <div className="min-w-0">
        <div className="truncate font-mono text-sm">{role.name}</div>
        {role.description ? <div className="truncate text-xs text-muted-foreground">{role.description}</div> : null}
      </div>
      {action}
    </div>
  );
}

function StaffRoles() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const { id = "" } = useParams();
  const qc = useQueryClient();
  const member = useQuery(memberQuery(inst.scope, id));
  const roles = useQuery(assignableRolesQuery(inst.scope));

  const change = useMutation({
    mutationFn: ({ role, grant }: { role: string; grant: boolean }) =>
      grant ? staffApi.assignRole(inst.scope, id, role) : staffApi.revokeRole(inst.scope, id, role),
    onSuccess: (_d, { role, grant }) => {
      notify.success(grant ? t("instStaff.roleGranted", { role }) : t("instStaff.roleRevoked", { role }));
      void qc.invalidateQueries({ queryKey: staffKeys.all(inst.scope) });
    },
    onError: (e) => notify.error(e),
  });

  if (member.isPending || roles.isPending) return <Loading />;
  if (member.isError) return <MemberReadError error={member.error} onRetry={() => void member.refetch()} />;
  if (roles.isError) return <ErrorState error={roles.error} onRetry={() => void roles.refetch()} />;

  const split = splitRoles(inst.ctx.institution, memberRoles(member.data), roles.data);
  const allow = inst.perms.roles?.allow ?? [];
  const deny = inst.perms.roles?.deny ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("instStaff.rolesTitle", { username: member.data.username })}
        description={
          t("instStaff.rolesDescription", { allow: allow.join(", ") || namespaceOf(inst.ctx.institution) + "*" }) +
          (deny.length ? ` ${t("instStaff.rolesExcluding", { deny: deny.join(", ") })}` : "")
        }
        actions={
          <Button asChild variant="ghost">
            <Link to={inst.path(`/institution/staff/${encodeURIComponent(id)}`)}>{t("instStaff.backToMember")}</Link>
          </Button>
        }
      />
      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>{t("instStaff.assigned")}</CardTitle>
            <CardDescription>{t("instStaff.assignedHint")}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2" data-testid="roles-assigned">
            {split.managed.map((r) => (
              <RoleRow
                key={r.name}
                role={r}
                action={
                  <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" disabled={change.isPending} onClick={() => change.mutate({ role: r.name, grant: false })}>
                    {t("instStaff.revoke")}
                  </Button>
                }
              />
            ))}
            {split.managed.length === 0 ? <p className="py-4 text-center text-sm text-muted-foreground">{t("instStaff.noneAssigned")}</p> : null}
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle>{t("instStaff.available")}</CardTitle>
            <CardDescription>{t("instStaff.availableHint")}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2" data-testid="roles-available">
            {split.available.map((r) => (
              <RoleRow
                key={r.name}
                role={r}
                action={
                  <Button variant="outline" size="sm" disabled={change.isPending} onClick={() => change.mutate({ role: r.name, grant: true })}>
                    {t("instStaff.grant")}
                  </Button>
                }
              />
            ))}
            {split.available.length === 0 ? <p className="py-4 text-center text-sm text-muted-foreground">{t("instStaff.allGranted")}</p> : null}
          </CardContent>
        </Card>
      </div>
      {split.heldNotAssignable.length || split.outsideCount ? (
        <Card>
          <CardHeader>
            <CardTitle>{t("instStaff.readOnlyRoles")}</CardTitle>
            <CardDescription>{t("instStaff.readOnlyRolesHint")}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            <div className="flex flex-wrap gap-2">
              {split.heldNotAssignable.map((r) => (
                <Badge key={r} variant="outline" className="font-mono">
                  {r}
                </Badge>
              ))}
            </div>
            {split.outsideCount ? <p className="text-xs text-muted-foreground">{t("instStaff.outsideRoles", { count: split.outsideCount })}</p> : null}
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}

export function Component() {
  return (
    <CapabilityGate capability="map-roles">
      <StaffRoles />
    </CapabilityGate>
  );
}
