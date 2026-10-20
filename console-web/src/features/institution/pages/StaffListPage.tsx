/**
 * /institution/staff — the institution's members.
 *
 * The scoping is the server's: GET /institution-admin/users applies a
 * mandatory institution filter and `?search=` searches within it. Status is
 * filtered locally (the set is already bounded to one institution). Buttons a
 * tier lacks are hidden — cosmetic; the wrapper refuses the call anyway.
 */
import { useState, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { CapabilityGate } from "../components/InstitutionGate";
import { useInstitution } from "../context";
import { membersQuery } from "../staff/api";
import { useDeactivate } from "../staff/components/StaffShared";
import { STATUS_VALUES, filterMembers, fullName, parseStatus } from "../staff/state/staffForm";

function StaffList() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const [sp, setSp] = useSearchParams();
  // The top bar's search sends ?q=; the page's own form uses ?search=.
  const search = (sp.get("search") ?? sp.get("q") ?? "").trim() || undefined;
  const status = parseStatus(sp.get("status"));
  const [draft, setDraft] = useState(search ?? "");
  const q = useQuery(membersQuery(inst.scope, search));
  const deactivate = useDeactivate();

  const canManage = inst.can("manage-members");
  const canMapRoles = inst.can("map-roles");

  const update = (extra: Record<string, string | undefined>) => {
    const next = new URLSearchParams(sp);
    next.delete("q");
    for (const [k, v] of Object.entries(extra)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    setSp(next);
  };

  const onSearch = (e: FormEvent) => {
    e.preventDefault();
    update({ search: draft.trim() || undefined });
  };

  const all = q.data ?? [];
  const members = filterMembers(all, status);

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("instStaff.title")}
        description={
          q.data
            ? t(perms(inst) ? "instStaff.countWithDepts" : "instStaff.countNoDepts", {
                shown: members.length.toLocaleString(),
                total: all.length.toLocaleString(),
                institution: inst.ctx.institution,
              }) + (search ? ` · ${t("instStaff.matching", { search })}` : "")
            : undefined
        }
        actions={
          <>
            {/* Flow B's entry point; changes app access, so it needs manage-clients. */}
            {inst.can("manage-clients") ? (
              <Button asChild variant="outline">
                <Link to={inst.path("/institution/access/revoke")}>{t("instStaff.removeAppAccess")}</Link>
              </Button>
            ) : null}
            {canManage && inst.can("manage-membership") ? (
              <Button asChild>
                <Link to={inst.path("/institution/staff/new")}>{t("instStaff.addUser")}</Link>
              </Button>
            ) : null}
          </>
        }
      />

      <div className="flex flex-wrap items-center justify-between gap-2">
        <form className="flex max-w-md flex-1 gap-2" onSubmit={onSearch} role="search">
          <Input value={draft} onChange={(e) => setDraft(e.target.value)} placeholder={t("instStaff.searchPlaceholder")} aria-label={t("common.search")} />
          <Button type="submit" variant="secondary">
            {t("common.search")}
          </Button>
        </form>
        <div className="flex items-center gap-1">
          {STATUS_VALUES.map((s) => (
            <Button key={s} size="sm" variant={s === status ? "secondary" : "ghost"} onClick={() => update({ status: s === "all" ? undefined : s })}>
              {t(`instStaff.status.${s}`)}
            </Button>
          ))}
        </div>
      </div>

      {q.isPending ? (
        <Loading />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : (
        <div className="rounded-xl border bg-card shadow-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("instStaff.username")}</TableHead>
                <TableHead>{t("common.name")}</TableHead>
                <TableHead>{t("instStaff.email")}</TableHead>
                <TableHead>{t("common.status")}</TableHead>
                <TableHead className="text-right">{t("common.actions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {members.map((m) => (
                <TableRow key={m.id}>
                  <TableCell className="font-mono text-xs">{m.username}</TableCell>
                  <TableCell>{fullName(m) || "—"}</TableCell>
                  <TableCell className="text-sm">{m.email ?? "—"}</TableCell>
                  <TableCell>
                    <Badge variant={m.enabled ? "secondary" : "destructive"}>{m.enabled ? t("instStaff.active") : t("instStaff.deactivatedBadge")}</Badge>
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex items-center justify-end gap-1">
                      <Button asChild variant="ghost" size="sm">
                        <Link to={inst.path(`/institution/staff/${encodeURIComponent(m.id)}`)}>{canManage ? t("common.edit") : t("instStaff.view")}</Link>
                      </Button>
                      {canMapRoles ? (
                        <Button asChild variant="ghost" size="sm">
                          <Link to={inst.path(`/institution/staff/${encodeURIComponent(m.id)}/roles`)}>{t("instStaff.roles")}</Link>
                        </Button>
                      ) : null}
                      {canManage && m.enabled ? (
                        <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" disabled={deactivate.pending} onClick={() => void deactivate.run(m)}>
                          {t("instStaff.deactivate")}
                        </Button>
                      ) : null}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
              {members.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                    {all.length === 0 ? t("instStaff.empty") : t("instStaff.noMatch")}
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}

function perms(inst: ReturnType<typeof useInstitution>): boolean {
  return Boolean(inst.perms.members?.include_subgroups);
}

export function Component() {
  return (
    <CapabilityGate capability="view-members">
      <StaffList />
    </CapabilityGate>
  );
}
