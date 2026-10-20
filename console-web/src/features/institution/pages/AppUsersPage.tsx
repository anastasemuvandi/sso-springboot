/**
 * /institution/apps/:clientId/users — flow A: remove many users from one app.
 *
 * Each row says HOW the person has access: a named assignment, or a group the
 * client admits. A group grant is the whole ministry's; revoking such a user
 * records a per-person denial instead (the wrapper does that). On a
 * cross-cutting app only this institution's members are listed.
 *
 * The confirmation is the server's own preview (`preview: true`) of the exact
 * call that will run, never a sentence composed here.
 */
import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { ErrorState, Loading } from "@/components/common/QueryState";
import { PageHeader } from "@/components/common/PageHeader";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useInstitution } from "../context";
import { CapabilityGate } from "../components/InstitutionGate";
import { instAppKeys, instAppsApi, instAppUsersQuery } from "../apps/api";
import { RevokeSummary } from "../access/components/RevokeSummary";
import type { RevokeReport } from "../apps/types";

function AppUsers({ clientId }: { clientId: string }) {
  const { t } = useTranslation();
  const inst = useInstitution();
  const { scope } = inst;
  const qc = useQueryClient();
  const access = useQuery(instAppUsersQuery(scope, clientId));
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const [preview, setPreview] = useState<RevokeReport | null>(null);

  const doPreview = useMutation({
    mutationFn: () => instAppsApi.revoke(scope, { user_ids: [...chosen], client_ids: [clientId], preview: true }),
    onSuccess: setPreview,
    onError: (e) => notify.error(e),
  });
  const doRevoke = useMutation({
    mutationFn: () => instAppsApi.revoke(scope, { user_ids: [...chosen], client_ids: [clientId] }),
    onSuccess: (r) => {
      notify.success(
        t("instApps.users.removed", { count: r.total_revocations, clientId }) +
          (r.total_skipped > 0 ? ` — ${t("instApps.users.nothingFor", { count: r.total_skipped })}` : ""),
      );
      setPreview(null);
      setChosen(new Set());
      void qc.invalidateQueries({ queryKey: instAppKeys.all(scope) });
    },
    onError: (e) => notify.error(e),
  });

  if (access.isPending) return <Loading />;
  if (access.isError) return <ErrorState error={access.error} onRetry={() => void access.refetch()} />;
  const data = access.data;

  const toggle = (id: string) => {
    setPreview(null);
    setChosen((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("instApps.users.title")}
        description={t("instApps.users.description", { institution: inst.ctx.institution, clientId, name: data.name ? ` (${data.name})` : "" })}
        actions={
          <Button asChild variant="outline">
            <Link to={inst.path("/institution/apps")}>{t("instApps.users.back")}</Link>
          </Button>
        }
      />

      {data.cross_cutting ? <div className="rounded-lg border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">{t("instApps.users.crossCutting", { institution: inst.ctx.institution })}</div> : null}
      {!data.revocable ? <div className="rounded-lg border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">{t("instApps.users.readOnly")}</div> : null}

      {preview ? (
        <div className="rounded-xl border border-destructive/40 bg-destructive/5 p-4" data-testid="revoke-confirm">
          <h2 className="text-sm font-semibold text-destructive">{t("instApps.users.confirmTitle")}</h2>
          <RevokeSummary report={preview} className="mt-3" />
          <div className="mt-4 flex items-center gap-2">
            <Button variant="destructive" size="sm" disabled={preview.total_revocations === 0 || doRevoke.isPending} onClick={() => doRevoke.mutate()}>
              {t("instApps.users.removeAccess")}
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setPreview(null)}>
              {t("common.cancel")}
            </Button>
          </div>
        </div>
      ) : null}

      <div className="rounded-xl border bg-card shadow-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead className="w-10" />
              <TableHead>{t("common.user")}</TableHead>
              <TableHead>Email</TableHead>
              <TableHead>{t("instApps.users.how")}</TableHead>
              <TableHead>{t("common.status")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.items.map((h) => (
              <TableRow key={h.id} data-testid={`holder-${h.id}`}>
                <TableCell>
                  <Checkbox
                    checked={chosen.has(h.id)}
                    disabled={!data.revocable}
                    onCheckedChange={() => toggle(h.id)}
                    aria-label={t("instApps.users.select", { username: h.username })}
                  />
                </TableCell>
                <TableCell className="text-sm">
                  <Link to={inst.path(`/institution/staff/${encodeURIComponent(h.id)}`)} className="font-medium hover:underline">
                    {h.username}
                  </Link>
                  {h.firstName || h.lastName ? <span className="ml-2 text-muted-foreground">{[h.firstName, h.lastName].filter(Boolean).join(" ")}</span> : null}
                </TableCell>
                <TableCell className="text-sm text-muted-foreground">{h.email ?? "—"}</TableCell>
                <TableCell className="space-x-1">
                  {h.direct ? (
                    <Badge variant="secondary" title={t("instApps.users.directHint")}>
                      {t("instApps.users.direct")}
                    </Badge>
                  ) : null}
                  {h.via_groups.map((g) => (
                    <Badge key={g} variant="outline" title={t("instApps.users.viaHint", { group: g })}>
                      {t("instApps.users.via", { group: g })}
                    </Badge>
                  ))}
                </TableCell>
                <TableCell>
                  {h.enabled ? (
                    <span className="text-xs text-muted-foreground">{t("instApps.users.active")}</span>
                  ) : (
                    <Badge variant="outline" className="opacity-70">
                      {t("instApps.users.disabled")}
                    </Badge>
                  )}
                </TableCell>
              </TableRow>
            ))}
            {data.items.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                  {t("instApps.users.empty")}
                </TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </div>

      {data.revocable && data.items.length > 0 ? (
        <div className="flex items-center justify-between gap-3">
          <p className="text-xs text-muted-foreground">{t("instApps.users.footer", { count: data.total })}</p>
          <Button variant="outline" disabled={chosen.size === 0 || doPreview.isPending} onClick={() => doPreview.mutate()}>
            {t("instApps.users.review", { count: chosen.size })}
          </Button>
        </div>
      ) : null}
    </div>
  );
}

export function Component() {
  const { clientId = "" } = useParams();
  return (
    <CapabilityGate capability="view-clients">
      <AppUsers clientId={clientId} />
    </CapabilityGate>
  );
}
