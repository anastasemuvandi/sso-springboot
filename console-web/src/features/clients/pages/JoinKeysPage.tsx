/**
 * /clients/join-keys — the platform-side backfill worklist.
 *
 * Unconfigured is NOT email: a client with nothing recorded has no mapper, its
 * tokens carry no `join_key` and provisioning skips it, so the row says
 * "Not set" until someone picks a column (picking email is a decision too).
 * Per row, never a bulk default. Scoped clients first; platform ones behind
 * "show all". A change that re-keys users or live sessions comes back 409 from
 * the wrapper and the row waits in a confirm state with the wrapper's message.
 */
import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { clientKeys, clientsApi, clientsListQuery, joinColumnQuery, parseJoinColumnConflict } from "../api";
import { JoinKeyBadge, ScopeCell } from "../components/cells";
import { KEYCLOAK_INTERNAL_CLIENTS, type Client, type JoinColumn } from "../types";

function Counter({ label, value, hint, warn }: { label: string; value: number; hint: string; warn?: boolean }) {
  return (
    <div className={cn("rounded-lg border px-4 py-3", warn ? "border-destructive/40 bg-destructive/5" : "bg-muted/20")}>
      <p className="text-2xl font-semibold tabular-nums">{value}</p>
      <p className="text-sm font-medium">{label}</p>
      <p className="mt-0.5 text-xs text-muted-foreground">{hint}</p>
    </div>
  );
}

const isOutstanding = (c: Client) => !c.join_column?.explicit || Boolean(c.join_column?.malformed_value);

export function JoinKeysPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [sp, setSp] = useSearchParams();
  const showAll = sp.get("show") === "all";
  const clientsQ = useQuery(clientsListQuery());
  const [picked, setPicked] = useState<Record<string, JoinColumn>>({});
  const [awaiting, setAwaiting] = useState<{ clientId: string; column: JoinColumn; message: string } | null>(null);

  const all = (clientsQ.data ?? []).filter((c) => !KEYCLOAK_INTERNAL_CLIENTS.has(c.clientId));
  const scoped = all.filter((c) => c.scope && c.scope.kind !== "platform");
  const platformOnly = all.filter((c) => !c.scope || c.scope.kind === "platform");
  const rows = showAll ? all : scoped;
  const outstanding = scoped.filter(isOutstanding);
  const crossOutstanding = outstanding.filter((c) => c.scope?.kind === "cross-cutting");
  const malformed = scoped.filter((c) => c.join_column?.malformed_value);
  const sorted = [...rows].sort((a, b) => {
    const ao = isOutstanding(a);
    const bo = isOutstanding(b);
    if (ao !== bo) return ao ? -1 : 1;
    return a.clientId.localeCompare(b.clientId);
  });

  // The columns on offer are policy, read from the wrapper (one client's state carries them).
  const first = rows[0]?.clientId;
  const choicesQ = useQuery({ ...joinColumnQuery(first ?? ""), enabled: Boolean(first) });
  const choices = choicesQ.data?.choices ?? [];

  const save = useMutation({
    mutationFn: ({ clientId, column, confirm }: { clientId: string; column: JoinColumn; confirm: boolean }) => clientsApi.setJoinColumn(clientId, column, confirm),
    onSuccess: (_r, v) => {
      setAwaiting(null);
      notify.success(t("clients.joinKeys.saved", { clientId: v.clientId, column: v.column }));
      void qc.invalidateQueries({ queryKey: clientKeys.list() });
      void qc.invalidateQueries({ queryKey: clientKeys.joinColumnAny(v.clientId) });
    },
    onError: (e, v) => {
      const c = !v.confirm ? parseJoinColumnConflict(e) : null;
      if (c) {
        setAwaiting({ clientId: v.clientId, column: v.column, message: c.message });
        return;
      }
      notify.error(e);
    },
  });

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("clients.joinKeys.title")}
        description={t("clients.joinKeys.description")}
        actions={
          <Button asChild variant="outline">
            <Link to="/clients">{t("clients.detail.backToClients")}</Link>
          </Button>
        }
      />
      {clientsQ.isPending ? (
        <Loading />
      ) : clientsQ.isError ? (
        <ErrorState error={clientsQ.error} onRetry={() => void clientsQ.refetch()} />
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Counter label={t("clients.joinKeys.needDecision")} value={outstanding.length} hint={t("clients.joinKeys.needDecisionHint")} warn={outstanding.length > 0} />
            <Counter label={t("clients.joinKeys.crossCutting")} value={crossOutstanding.length} hint={t("clients.joinKeys.crossCuttingHint")} warn={crossOutstanding.length > 0} />
            <Counter label={t("dashboard.cards.configured")} value={scoped.length - outstanding.length} hint={t("clients.joinKeys.ofScoped", { count: scoped.length })} />
            <Counter label={t("clients.joinKey.invalid")} value={malformed.length} hint={t("clients.joinKeys.invalidHint")} warn={malformed.length > 0} />
          </div>

          {awaiting ? (
            <div className="rounded-lg border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm" role="alert">
              <p className="font-medium text-destructive">{t("clients.joinKeys.confirmTitle", { clientId: awaiting.clientId })}</p>
              <p className="mt-1 text-muted-foreground">{awaiting.message}</p>
              <p className="mt-1 text-xs text-muted-foreground">{t("clients.joinKeys.nothingChanged")}</p>
            </div>
          ) : null}

          {choicesQ.isError ? (
            <div className="rounded-lg border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm">
              <p className="font-medium text-destructive">{t("clients.joinKeys.choicesFailed")}</p>
              <p className="mt-1 text-muted-foreground">{t("clients.joinKeys.choicesFailedHint", { error: errorMessage(choicesQ.error) })}</p>
            </div>
          ) : null}

          <div className="flex items-center justify-between gap-3 text-sm">
            <p className="text-muted-foreground">
              {showAll ? t("clients.joinKeys.showingAll", { count: platformOnly.length }) : t("clients.joinKeys.showingScoped", { count: scoped.length, hidden: platformOnly.length })}
            </p>
            <Button variant="ghost" size="sm" className="shrink-0" onClick={() => setSp(showAll ? {} : { show: "all" }, { replace: true })}>
              {showAll ? t("clients.joinKeys.scopedOnly") : t("clients.joinKeys.showPlatform")}
            </Button>
          </div>

          <div className="rounded-xl border bg-card shadow-card">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("clients.list.clientId")}</TableHead>
                  <TableHead>{t("common.name")}</TableHead>
                  <TableHead>{t("clients.joinKeys.belongsTo")}</TableHead>
                  <TableHead>{t("clients.joinKeys.current")}</TableHead>
                  <TableHead className="text-right">{t("clients.joinKeys.setTo")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {sorted.map((c) => {
                  const isAwaiting = awaiting?.clientId === c.clientId;
                  const value = isAwaiting ? awaiting.column : (picked[c.clientId] ?? (c.join_column?.explicit ? c.join_column.join_column : ""));
                  return (
                    <TableRow key={c.id ?? c.clientId} className={isAwaiting ? "bg-destructive/5" : undefined}>
                      <TableCell className="font-mono text-xs">
                        <Link to={`/clients/${encodeURIComponent(c.clientId)}#join-key`} className="hover:underline">
                          {c.clientId}
                        </Link>
                      </TableCell>
                      <TableCell className="text-sm">{c.name ?? "—"}</TableCell>
                      <TableCell>
                        <ScopeCell scope={c.scope} />
                      </TableCell>
                      <TableCell>
                        <JoinKeyBadge client={c} withChanger />
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex items-center justify-end gap-2">
                          <select
                            value={value}
                            disabled={choices.length === 0}
                            onChange={(e) => setPicked((p) => ({ ...p, [c.clientId]: e.target.value as JoinColumn }))}
                            className="h-8 rounded-md border border-input bg-background px-2 text-sm shadow-sm disabled:opacity-50"
                            aria-label={t("clients.joinKeys.columnFor", { clientId: c.clientId })}
                          >
                            <option value="" disabled>
                              {t("clients.joinKeys.choose")}
                            </option>
                            {choices.map((ch) => (
                              <option key={ch.value} value={ch.value}>
                                {ch.label}
                              </option>
                            ))}
                          </select>
                          <Button
                            size="sm"
                            variant={isAwaiting ? "destructive" : "outline"}
                            disabled={choices.length === 0 || !value || save.isPending}
                            onClick={() => value && save.mutate({ clientId: c.clientId, column: value, confirm: isAwaiting })}
                          >
                            {isAwaiting ? t("common.confirm") : t("common.save")}
                          </Button>
                          {isAwaiting ? (
                            <Button size="sm" variant="ghost" onClick={() => setAwaiting(null)}>
                              {t("common.cancel")}
                            </Button>
                          ) : null}
                        </div>
                      </TableCell>
                    </TableRow>
                  );
                })}
                {sorted.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                      {t("clients.joinKeys.empty")}
                    </TableCell>
                  </TableRow>
                ) : null}
              </TableBody>
            </Table>
          </div>

          <div className="space-y-1 text-xs leading-relaxed text-muted-foreground">
            <p>{t("clients.joinKeys.footer1")}</p>
            <p>{t("clients.joinKeys.footer2")}</p>
          </div>
        </>
      )}
    </div>
  );
}

export const Component = JoinKeysPage;
