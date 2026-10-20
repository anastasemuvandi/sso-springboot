/**
 * /institution/apps/:clientId/join-key — which identifier one app finds its
 * own users by, the live session count, and which assigned staff the key in
 * force would block (`check=true`).
 *
 * A change the wrapper wants acknowledged (live sessions, or re-keying users
 * already assigned) comes back 409 with both counts; the dialog shows them and
 * re-sends with `confirm: true`. Cross-cutting apps are read-only here.
 */
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { parseJoinColumnConflict, type JoinColumnConflict } from "@/features/clients/api";
import type { JoinColumn } from "@/features/clients/types";
import { useInstitution } from "../context";
import { CapabilityGate } from "../components/InstitutionGate";
import { instAppKeys, instAppsApi, instAppsQuery, instJoinColumnQuery } from "../apps/api";

function JoinKey({ clientId }: { clientId: string }) {
  const { t } = useTranslation();
  const inst = useInstitution();
  const { scope } = inst;
  const qc = useQueryClient();
  const state = useQuery(instJoinColumnQuery(scope, clientId));
  const apps = useQuery(instAppsQuery(scope));
  const [choice, setChoice] = useState<JoinColumn | null>(null);
  const [conflict, setConflict] = useState<{ column: JoinColumn; info: JoinColumnConflict } | null>(null);

  const save = useMutation({
    mutationFn: ({ column, confirm }: { column: JoinColumn; confirm: boolean }) => instAppsApi.setJoinColumn(scope, clientId, column, confirm),
    onSuccess: (change, { column }) => {
      setConflict(null);
      notify.success(
        t("instApps.joinKey.saved", { column, mapper: change.mapper }) +
          (change.readiness.blocked > 0 ? ` — ${t("instApps.joinKey.blockedAfter", { count: change.readiness.blocked })}` : ""),
      );
      void qc.invalidateQueries({ queryKey: instAppKeys.all(scope) });
    },
    onError: (e, { column, confirm }) => {
      const info = parseJoinColumnConflict(e);
      if (info && !confirm) setConflict({ column, info });
      else notify.error(e);
    },
  });

  if (state.isPending) return <Loading />;
  if (state.isError) return <ErrorState error={state.error} onRetry={() => void state.refetch()} />;
  const s = state.data;
  const app = apps.data?.find((a) => a.clientId === clientId);
  // Read-only for a cross-cutting app (several institutions share its key) and for a tier without manage-clients.
  const editable = inst.can("manage-clients") && Boolean(app?.manageable) && !app?.cross_cutting;
  const selected = choice ?? s.setting.join_column;
  const readiness = s.readiness;
  const sessions = s.active_sessions;

  return (
    <div className="max-w-3xl space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between gap-2">
            <CardTitle className="font-mono text-base">{clientId}</CardTitle>
            <Badge variant={s.setting.explicit ? "secondary" : "outline"}>
              {s.setting.explicit ? s.setting.join_column : t("instApps.joinKey.defaulted", { column: s.setting.join_column })}
            </Badge>
          </div>
          <CardDescription>{t("instApps.joinKey.explain", { claim: s.setting.claim, source: s.setting.user_source })}</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {!s.setting.explicit ? <p className="rounded-md border bg-muted/40 px-3 py-2 text-sm text-muted-foreground">{t("instApps.joinKey.noneChosen")}</p> : null}
          {s.setting.malformed_value ? (
            <p className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-sm">{t("instApps.joinKey.malformed", { value: s.setting.malformed_value })}</p>
          ) : null}
          <fieldset className="space-y-2" disabled={!editable}>
            <legend className="text-sm font-medium">{t("instApps.joinKey.identifyBy")}</legend>
            {s.choices.map((c) => (
              <label key={c.value} className="flex cursor-pointer items-start gap-3 rounded-md border p-3 hover:bg-muted/40">
                <input type="radio" name="join_column" value={c.value} checked={selected === c.value} onChange={() => setChoice(c.value)} className="mt-1" />
                <span className="text-sm">
                  <span className="font-medium">{c.label}</span>
                  {c.default ? <span className="ml-2 text-xs text-muted-foreground">{t("instApps.joinKey.platformDefault")}</span> : null}
                  <span className="block text-xs text-muted-foreground">
                    <code className="font-mono">{c.source}</code> · {c.shape}
                  </span>
                </span>
              </label>
            ))}
          </fieldset>
          {editable ? (
            <div className="rounded-md border bg-muted/40 px-3 py-2.5 text-xs">
              <p className="font-medium">{sessions < 0 ? t("instApps.joinKey.sessionsUnknown") : t("instApps.joinKey.sessions", { count: sessions })}</p>
              <p className="mt-1 text-muted-foreground">{t("instApps.joinKey.sessionsExplain")}</p>
            </div>
          ) : (
            <p className="rounded-md border bg-muted/40 px-3 py-2 text-sm text-muted-foreground">
              {app?.cross_cutting ? t("instApps.joinKey.crossCuttingReadOnly") : t("instApps.joinKey.tierReadOnly")}
            </p>
          )}
        </CardContent>
        <CardFooter className="flex justify-between">
          <Button asChild variant="ghost">
            <Link to={inst.path("/institution/apps")}>{t("common.back")}</Link>
          </Button>
          {editable ? (
            <Button onClick={() => save.mutate({ column: selected, confirm: false })} disabled={save.isPending}>
              {t("instApps.joinKey.save")}
            </Button>
          ) : null}
        </CardFooter>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between gap-2">
            <CardTitle className="text-base">{t("instApps.joinKey.blockedTitle")}</CardTitle>
            {readiness ? (
              <Badge variant={readiness.blocked ? "destructive" : "secondary"}>{t("instApps.joinKey.ofChecked", { blocked: readiness.blocked, checked: readiness.checked })}</Badge>
            ) : null}
          </div>
          <CardDescription>{t("instApps.joinKey.blockedExplain")}</CardDescription>
        </CardHeader>
        <CardContent>
          {readiness?.truncated ? <p className="mb-3 rounded-md border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">{t("instApps.joinKey.truncated", { count: readiness.checked })}</p> : null}
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("common.user")}</TableHead>
                <TableHead>{t("instApps.joinKey.problem")}</TableHead>
                <TableHead>{t("common.details")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(readiness?.users ?? []).map((u) => (
                <TableRow key={u.user_id}>
                  <TableCell className="font-mono text-xs">
                    <Link to={inst.path(`/institution/staff/${encodeURIComponent(u.user_id)}`)} className="hover:underline">
                      {u.username ?? u.user_id}
                    </Link>
                  </TableCell>
                  <TableCell className="text-xs">
                    <code className="font-mono">{u.error}</code>
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">{u.detail}</TableCell>
                </TableRow>
              ))}
              {(readiness?.users ?? []).length === 0 ? (
                <TableRow>
                  <TableCell colSpan={3} className="py-6 text-center text-sm text-muted-foreground">
                    {!readiness ? t("instApps.joinKey.notChecked") : readiness.checked === 0 ? t("instApps.joinKey.nobodyAssigned") : t("instApps.joinKey.allUsable")}
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Dialog open={conflict !== null} onOpenChange={(o) => !o && setConflict(null)}>
        <DialogContent data-testid="join-confirm">
          <DialogHeader>
            <DialogTitle>{t("instApps.joinKey.confirmTitle", { clientId, column: conflict?.column ?? "" })}</DialogTitle>
            <DialogDescription>{conflict?.info.message}</DialogDescription>
          </DialogHeader>
          <ul className="list-disc space-y-1 pl-5 text-sm">
            <li>{t("instApps.joinKey.confirmSessions", { count: conflict?.info.activeSessions ?? 0 })}</li>
            <li>{t("instApps.joinKey.confirmAssigned", { count: conflict?.info.assignedUsers ?? 0 })}</li>
          </ul>
          <DialogFooter>
            <Button variant="outline" onClick={() => setConflict(null)}>
              {t("common.cancel")}
            </Button>
            <Button variant="destructive" disabled={save.isPending} onClick={() => conflict && save.mutate({ column: conflict.column, confirm: true })}>
              {t("instApps.joinKey.confirmApply")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export function Component() {
  const { clientId = "" } = useParams();
  return (
    <CapabilityGate capability="view-clients">
      <JoinKey clientId={clientId} />
    </CapabilityGate>
  );
}
