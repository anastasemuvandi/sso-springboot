/**
 * The provisioning report — the same shape for a dry run and a real run.
 * Per employee, the four facts: the account (existing / created /
 * not_created), clients granted, clients skipped (already reachable), and
 * clients denied with the reason per client.
 */
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { ProvisionReport } from "../api";

const ACCOUNT_VARIANT: Record<string, "secondary" | "outline" | "destructive"> = {
  existing: "outline",
  created: "secondary",
  not_created: "destructive",
};

function List({ items, muted, testId }: { items: string[]; muted?: boolean; testId: string }) {
  if (items.length === 0) return <span className="text-xs text-muted-foreground" data-testid={testId}>—</span>;
  return (
    <ul className="space-y-0.5" data-testid={testId}>
      {items.map((c) => (
        <li key={c} className={muted ? "font-mono text-xs text-muted-foreground" : "font-mono text-xs"}>
          {c}
        </li>
      ))}
    </ul>
  );
}

export function ProvisionReportView({ report, staffPath }: { report: ProvisionReport; staffPath: (userId: string) => string }) {
  const { t } = useTranslation();
  const tot = report.totals;
  const rows = report.employees ?? [];
  const denyingEveryone = tot.clients_denying_everyone ?? [];
  return (
    <Card className={report.dry_run ? "" : "border-primary/40"} data-testid={report.dry_run ? "provision-report-preview" : "provision-report-final"}>
      <CardHeader>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle>{report.dry_run ? t("instEmployees.report.previewTitle") : t("instEmployees.report.finalTitle")}</CardTitle>
          <div className="flex flex-wrap gap-1">
            <Badge variant="secondary">{t("instEmployees.report.grants", { count: tot.grants })}</Badge>
            {tot.created > 0 ? <Badge variant="secondary">{t("instEmployees.report.created", { count: tot.created })}</Badge> : null}
            {tot.already_held > 0 ? <Badge variant="outline">{t("instEmployees.report.alreadyHeld", { count: tot.already_held })}</Badge> : null}
            {tot.skipped > 0 ? <Badge variant="outline">{t("instEmployees.report.skipped", { count: tot.skipped })}</Badge> : null}
            {tot.failed > 0 ? <Badge variant="destructive">{t("instEmployees.report.failed", { count: tot.failed })}</Badge> : null}
          </div>
        </div>
        <CardDescription>{t("instEmployees.report.summary", { grants: tot.grants, held: tot.already_held, denials: tot.denials })}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {denyingEveryone.length > 0 ? (
          <p className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-sm">{t("instEmployees.report.denyingEveryone", { clients: denyingEveryone.join(", ") })}</p>
        ) : null}
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("instEmployees.report.employee")}</TableHead>
              <TableHead>{t("instEmployees.report.account")}</TableHead>
              <TableHead>{t("instEmployees.report.granted")}</TableHead>
              <TableHead>{t("instEmployees.report.alreadyHad")}</TableHead>
              <TableHead>{t("instEmployees.report.refused")}</TableHead>
              {!report.dry_run ? <TableHead>{t("instEmployees.report.email")}</TableHead> : null}
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((row) => (
              <TableRow key={row.employee_number} data-testid={`report-row-${row.employee_number}`}>
                <TableCell className="align-top">
                  <div className="text-sm">{row.name}</div>
                  <div className="font-mono text-xs text-muted-foreground">{row.username ?? row.employee_number}</div>
                </TableCell>
                <TableCell className="align-top">
                  <Badge variant={ACCOUNT_VARIANT[row.account] ?? "outline"} data-testid="report-account">
                    {t(`instEmployees.report.accountState.${row.account}`, { defaultValue: row.account })}
                  </Badge>
                  {row.outcome === "failed" ? (
                    <div className="mt-1">
                      <Badge variant="destructive">{t("instEmployees.report.outcomeFailed")}</Badge>
                    </div>
                  ) : null}
                  {row.message && row.outcome !== "created" ? <div className="mt-1 max-w-xs text-xs text-muted-foreground">{row.message}</div> : null}
                  {row.user_id && !report.dry_run ? (
                    <div className="mt-1 text-xs">
                      <Link to={staffPath(row.user_id)} className="underline decoration-dotted">
                        {t("instEmployees.report.openAccount")}
                      </Link>
                    </div>
                  ) : null}
                </TableCell>
                <TableCell className="align-top">
                  <List items={row.clients_granted ?? []} testId="report-granted" />
                </TableCell>
                <TableCell className="align-top">
                  <List items={row.clients_skipped ?? []} muted testId="report-skipped" />
                </TableCell>
                <TableCell className="align-top">
                  {(row.clients_denied ?? []).length === 0 ? (
                    <span className="text-xs text-muted-foreground" data-testid="report-denied">—</span>
                  ) : (
                    <ul className="space-y-1" data-testid="report-denied">
                      {(row.clients_denied ?? []).map((d) => (
                        <li key={d.client_id} className="text-xs">
                          <span className="font-mono">{d.client_id}</span>
                          <span className="text-muted-foreground">
                            {" — "}
                            {t(`instEmployees.report.denial.${d.reason ?? "join_value_missing"}`, { column: d.join_column, defaultValue: d.reason ?? "" })}
                          </span>
                          {d.message ? <div className="text-muted-foreground">{d.message}</div> : null}
                        </li>
                      ))}
                    </ul>
                  )}
                </TableCell>
                {!report.dry_run ? (
                  <TableCell className="align-top text-xs text-muted-foreground">
                    {row.password === "shared_dev" ? (
                      <div className="text-destructive">{t("instEmployees.report.sharedDev")}</div>
                    ) : (
                      <div>{t("instEmployees.report.setPassword", { value: row.password_email ?? "—" })}</div>
                    )}
                    <div>{t("instEmployees.report.notified", { value: row.welcome_email ?? "—" })}</div>
                    {row.mirror_error ? <div className="text-destructive">{t("instEmployees.report.mirrorError")}</div> : null}
                  </TableCell>
                ) : null}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
