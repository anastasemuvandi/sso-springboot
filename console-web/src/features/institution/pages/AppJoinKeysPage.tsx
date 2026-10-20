/**
 * /institution/apps/join-keys — the backfill worklist: every in-scope app's
 * join column, outstanding ones first. Cross-cutting rows are never
 * configurable here (a platform admin's decision); a 409 puts the row into a
 * confirm state that re-sends with `confirm: true`.
 */
import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { parseJoinColumnConflict } from "@/features/clients/api";
import type { JoinColumn } from "@/features/clients/types";
import { cn } from "@/lib/utils";
import { useInstitution } from "../context";
import { CapabilityGate } from "../components/InstitutionGate";
import { instAppKeys, instAppsApi, instJoinColumnsQuery } from "../apps/api";
import { JoinColumnBadge } from "../apps/components/JoinColumnBadge";
import type { JoinColumnRow } from "../apps/types";

function Counter({ label, value, hint, warn }: { label: string; value: number; hint: string; warn?: boolean }) {
  return (
    <div className={cn("rounded-lg border px-4 py-3", warn ? "border-destructive/40 bg-destructive/5" : "bg-muted/20")}>
      <p className="text-2xl font-semibold tabular-nums">{value}</p>
      <p className="text-sm font-medium">{label}</p>
      <p className="mt-0.5 text-xs text-muted-foreground">{hint}</p>
    </div>
  );
}

/** Outstanding first, then the ones the caller can act on, then by id. */
export function sortRows(rows: JoinColumnRow[]): JoinColumnRow[] {
  return [...rows].sort((a, b) => {
    if (a.configured !== b.configured) return a.configured ? 1 : -1;
    if (a.configurable !== b.configurable) return a.configurable ? -1 : 1;
    return a.clientId.localeCompare(b.clientId);
  });
}

function JoinKeys() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const { scope } = inst;
  const qc = useQueryClient();
  const backlog = useQuery(instJoinColumnsQuery(scope));
  const [picked, setPicked] = useState<Record<string, JoinColumn>>({});
  const [awaiting, setAwaiting] = useState<{ clientId: string; message: string } | null>(null);

  const save = useMutation({
    mutationFn: ({ clientId, column, confirm }: { clientId: string; column: JoinColumn; confirm: boolean }) => instAppsApi.setJoinColumn(scope, clientId, column, confirm),
    onSuccess: (_c, { clientId, column }) => {
      setAwaiting(null);
      notify.success(t("instApps.joinKeys.saved", { clientId, column }));
      void qc.invalidateQueries({ queryKey: instAppKeys.all(scope) });
    },
    onError: (e, { clientId, confirm }) => {
      const info = parseJoinColumnConflict(e);
      if (info && !confirm) setAwaiting({ clientId, message: info.message });
      else notify.error(e);
    },
  });

  if (backlog.isPending) return <Loading />;
  if (backlog.isError) return <ErrorState error={backlog.error} onRetry={() => void backlog.refetch()} />;
  const b = backlog.data;
  const canManage = inst.can("manage-clients");

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("instApps.joinKeys.title")}
        description={t("instApps.joinKeys.description")}
        actions={
          <Button asChild variant="outline">
            <Link to={inst.path("/institution/apps")}>{t("instApps.users.back")}</Link>
          </Button>
        }
      />
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Counter label={t("instApps.joinKeys.outstanding")} value={b.outstanding} hint={t("instApps.joinKeys.outstandingHint")} warn={b.outstanding > 0} />
        <Counter label={t("instApps.joinKeys.configured")} value={b.configured} hint={t("instApps.joinKeys.ofTotal", { total: b.total })} />
        <Counter label={t("instApps.joinKeys.platformAdmin")} value={b.needs_platform_admin} hint={t("instApps.joinKeys.platformAdminHint")} />
        <Counter label={t("instApps.joinKeys.malformed")} value={b.malformed} hint={t("instApps.joinKeys.malformedHint")} warn={b.malformed > 0} />
      </div>

      {awaiting ? (
        <div className="rounded-lg border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm" data-testid="join-keys-confirm">
          <p className="font-medium text-destructive">{t("instApps.joinKeys.confirmTitle", { clientId: awaiting.clientId })}</p>
          <p className="mt-1 text-muted-foreground">{awaiting.message}</p>
          <p className="mt-1 text-xs text-muted-foreground">{t("instApps.joinKeys.confirmNote")}</p>
        </div>
      ) : null}
      {!canManage ? <div className="rounded-lg border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">{t("instApps.joinKeys.readOnly")}</div> : null}

      <div className="rounded-xl border bg-card shadow-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("instApps.list.clientId")}</TableHead>
              <TableHead>{t("common.name")}</TableHead>
              <TableHead>{t("instApps.joinKeys.current")}</TableHead>
              <TableHead className="text-right">{t("instApps.joinKeys.setTo")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {sortRows(b.items).map((row) => {
              const isAwaiting = awaiting?.clientId === row.clientId;
              const value = picked[row.clientId] ?? (row.configured ? row.join_column.join_column : "");
              return (
                <TableRow key={row.clientId} className={isAwaiting ? "bg-destructive/5" : undefined} data-testid={`jk-row-${row.clientId}`}>
                  <TableCell className="font-mono text-xs">
                    <Link to={inst.path(`/institution/apps/${encodeURIComponent(row.clientId)}/join-key`)} className="hover:underline">
                      {row.clientId}
                    </Link>
                  </TableCell>
                  <TableCell className="text-sm">{row.name ?? "—"}</TableCell>
                  <TableCell>
                    <span className="flex items-baseline gap-2">
                      <JoinColumnBadge setting={row.join_column} configured={row.configured} />
                      {row.configured && row.join_column.changed_by ? (
                        <span className="text-xs text-muted-foreground">{t("instApps.joinKeys.by", { who: row.join_column.changed_by })}</span>
                      ) : null}
                    </span>
                  </TableCell>
                  <TableCell className="text-right">
                    {!row.configurable ? (
                      <span className="text-xs text-muted-foreground" title={row.cross_cutting ? t("instApps.joinKeys.crossCuttingHint") : t("instApps.joinKeys.tierHint")}>
                        {row.cross_cutting ? t("instApps.joinKeys.platformAdminShort") : "—"}
                      </span>
                    ) : (
                      <div className="flex items-center justify-end gap-2">
                        <select
                          value={value}
                          onChange={(e) => setPicked((p) => ({ ...p, [row.clientId]: e.target.value as JoinColumn }))}
                          className="h-8 rounded-md border border-input bg-background px-2 text-sm"
                          aria-label={t("instApps.joinKeys.selectFor", { clientId: row.clientId })}
                        >
                          <option value="" disabled>
                            {t("instApps.joinKeys.choose")}
                          </option>
                          {b.choices.map((c) => (
                            <option key={c.value} value={c.value}>
                              {c.label}
                            </option>
                          ))}
                        </select>
                        <Button
                          size="sm"
                          variant={isAwaiting ? "destructive" : "outline"}
                          disabled={!value || save.isPending}
                          onClick={() => save.mutate({ clientId: row.clientId, column: value as JoinColumn, confirm: isAwaiting })}
                        >
                          {isAwaiting ? t("common.confirm") : t("common.save")}
                        </Button>
                      </div>
                    )}
                  </TableCell>
                </TableRow>
              );
            })}
            {b.items.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">
                  {t("instApps.joinKeys.empty")}
                </TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </div>
      <div className="space-y-1 text-xs leading-relaxed text-muted-foreground">
        <p>{t("instApps.joinKeys.note1")}</p>
        <p>{t("instApps.joinKeys.note2")}</p>
      </div>
    </div>
  );
}

export function Component() {
  return (
    <CapabilityGate capability="view-clients">
      <JoinKeys />
    </CapabilityGate>
  );
}
