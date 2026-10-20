/**
 * /institution/employees/provision — review the selection, choose apps, run
 * a DRY RUN (the identical report, nothing written), then confirm.
 *
 * Validation is per (employee, app), never all-or-nothing; the report says
 * per employee: the account (existing / created / not_created), the apps
 * granted, the apps skipped (already reachable) and the apps denied with why.
 * Confirm is offered only for the inputs that were just previewed.
 */
import { useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table";
import { errorMessage } from "@/api/errors";
import { institutionKeys } from "../api";
import { CapabilityGate } from "../components/InstitutionGate";
import { useInstitution } from "../context";
import { provisionAppsQuery, provisionEmployees, type ProvisionReport } from "../employees/api";
import { ProvisionReportView } from "../employees/components/ProvisionReportView";
import * as sel from "../employees/state/employeeSelection";
import { useProvisionSelection } from "../employees/useProvisionSelection";

function Gate({ children }: { children: ReactNode }) {
  const inst = useInstitution();
  const missing = sel.missingProvisionCapability((c) => inst.can(c));
  if (!missing) return <>{children}</>;
  return <CapabilityGate capability={missing}>{children}</CapabilityGate>;
}

export function Component() {
  return (
    <Gate>
      <ProvisionRunner />
    </Gate>
  );
}

function ProvisionRunner() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const qc = useQueryClient();
  const { state: selection, commit } = useProvisionSelection(inst.ctx.institution);
  const apps = useQuery(provisionAppsQuery(inst.scope));
  const [clients, setClients] = useState<string[]>([]);
  const [preview, setPreview] = useState<{ report: ProvisionReport; inputs: string } | null>(null);
  const [final, setFinal] = useState<ProvisionReport | null>(null);

  const employees = sel.selectedList(selection);
  const numbers = employees.map((e) => e.employee_number);
  const inputs = JSON.stringify([numbers, [...clients].sort()]);
  const previewCurrent = preview !== null && preview.inputs === inputs;
  const manageable = useMemo(() => (apps.data ?? []).filter((a) => a.manageable), [apps.data]);

  const run = useMutation({
    mutationFn: (dryRun: boolean) => provisionEmployees(inst.scope, { employee_numbers: numbers, client_ids: clients, dry_run: dryRun }),
    onSuccess: (report, dryRun) => {
      if (dryRun) {
        setPreview({ report, inputs });
        setFinal(null);
        return;
      }
      setFinal(report);
      setPreview(null);
      commit(sel.clear(selection));
      setClients([]);
      void qc.invalidateQueries({ queryKey: institutionKeys.scoped(inst.scope) });
      notify.success(t("instEmployees.provision.done", { grants: report.totals.grants, created: report.totals.created }));
    },
    onError: (e) => notify.error(e),
  });

  const needsAccount = employees.filter((e) => !e.has_account);
  const unnameable = needsAccount.filter((e) => !e.email);
  const back = inst.path("/institution/employees", { select: "1" });

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("instEmployees.provision.title")}
        description={t("instEmployees.provision.description")}
        back={<Link to={back} className="text-muted-foreground hover:text-foreground">← {t("instEmployees.provision.back")}</Link>}
      />

      {final ? <ProvisionReportView report={final} staffPath={(id) => inst.path(`/institution/staff/${encodeURIComponent(id)}`)} /> : null}

      {employees.length === 0 ? (
        !final ? (
          <Card>
            <CardContent className="space-y-3 pt-5 text-sm text-muted-foreground" data-testid="provision-empty">
              <p>{t("instEmployees.provision.empty")}</p>
              <Button asChild variant="outline" size="sm">
                <Link to={back}>{t("instEmployees.provision.pick")}</Link>
              </Button>
            </CardContent>
          </Card>
        ) : null
      ) : (
        <Card>
          <CardHeader>
            <CardTitle data-testid="provision-selected-count">{t("instEmployees.provision.selected", { count: employees.length })}</CardTitle>
            <CardDescription>
              {t("instEmployees.provision.accountsExplain", { existing: employees.length - needsAccount.length, create: needsAccount.length })}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            {unnameable.length > 0 ? (
              <p className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-sm">{t("instEmployees.provision.unnameable", { count: unnameable.length })}</p>
            ) : null}
            <div className="max-h-56 overflow-y-auto rounded-md border">
              <Table>
                <TableBody>
                  {employees.map((e) => (
                    <TableRow key={e.employee_number} data-testid={`selected-${e.employee_number}`}>
                      <TableCell className="font-mono text-xs">{e.employee_number}</TableCell>
                      <TableCell className="text-sm">{e.label}</TableCell>
                      <TableCell className="font-mono text-xs text-muted-foreground">{e.email ?? "—"}</TableCell>
                      <TableCell className="text-xs text-muted-foreground">{e.has_account ? t("instEmployees.provision.hasAccount") : t("instEmployees.provision.willCreate")}</TableCell>
                      <TableCell className="text-right">
                        <Button size="sm" variant="ghost" onClick={() => commit(sel.removeMany(selection, [e.employee_number]))} aria-label={t("instEmployees.provision.remove", { name: e.label })}>
                          {t("common.remove")}
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>

            <div className="space-y-2">
              <div className="text-sm font-medium">{t("instEmployees.provision.appsTitle")}</div>
              <p className="text-xs text-muted-foreground">{t("instEmployees.provision.appsHint")}</p>
              {apps.isPending ? (
                <Loading />
              ) : apps.isError ? (
                <ErrorState error={apps.error} onRetry={() => void apps.refetch()} />
              ) : manageable.length === 0 ? (
                <p className="rounded-md border bg-muted/40 px-3 py-2 text-sm text-muted-foreground">{t("instEmployees.provision.noApps")}</p>
              ) : (
                manageable.map((app) => {
                  const configured = Boolean(app.join_column?.explicit);
                  const id = `app-${app.clientId}`;
                  return (
                    <label key={app.clientId} htmlFor={id} className="flex cursor-pointer items-start gap-3 rounded-md border p-3 hover:bg-muted/40">
                      <Checkbox
                        id={id}
                        data-testid={`app-${app.clientId}`}
                        checked={clients.includes(app.clientId)}
                        onCheckedChange={(v) => setClients((cur) => (v ? [...new Set([...cur, app.clientId])] : cur.filter((c) => c !== app.clientId)))}
                        className="mt-0.5"
                      />
                      <span className="text-sm">
                        <span className="font-mono">{app.clientId}</span>
                        {app.name && app.name !== app.clientId ? <span className="ml-2 text-muted-foreground">{app.name}</span> : null}
                        {app.cross_cutting ? <span className="ml-2 text-xs text-muted-foreground">{t("instEmployees.provision.shared")}</span> : null}
                        {configured ? (
                          <span className="block text-xs text-muted-foreground">{t("instEmployees.provision.keysOn", { column: app.join_column.join_column })}</span>
                        ) : (
                          <span className="block text-xs text-destructive">{t("instEmployees.provision.noJoinKey")}</span>
                        )}
                      </span>
                    </label>
                  );
                })
              )}
            </div>
            {run.isError ? <p className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-sm">{errorMessage(run.error)}</p> : null}
          </CardContent>
          <CardFooter className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-xs text-muted-foreground">{previewCurrent ? t("instEmployees.provision.previewReady") : t("instEmployees.provision.previewFirst")}</span>
            <div className="flex items-center gap-2">
              <Button variant="outline" disabled={run.isPending || clients.length === 0} onClick={() => run.mutate(true)} data-testid="dry-run">
                {run.isPending && run.variables === true ? t("instEmployees.provision.working") : t("instEmployees.provision.dryRun")}
              </Button>
              <Button disabled={run.isPending || !previewCurrent} onClick={() => run.mutate(false)} data-testid="confirm-provision">
                {run.isPending && run.variables === false ? t("instEmployees.provision.working") : t("instEmployees.provision.confirm")}
              </Button>
            </div>
          </CardFooter>
        </Card>
      )}

      {preview && previewCurrent ? <ProvisionReportView report={preview.report} staffPath={(id) => inst.path(`/institution/staff/${encodeURIComponent(id)}`)} /> : null}
    </div>
  );
}
