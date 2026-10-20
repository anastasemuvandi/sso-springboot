/**
 * /institution/apps — the applications this institution may use: its own
 * `<slug>-<app>` clients and cross-cutting ones shared with it.
 *
 * "Access" here is the institution-wide grant (the institution's group on the
 * client's access policy). Buttons follow the tier (`manage-clients`) and each
 * row's `manageable` flag — hiding them is cosmetic, the wrapper refuses
 * anything the tier lacks.
 */
import { useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil } from "lucide-react";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { useConfirm } from "@/components/common/ConfirmDialog";
import { CopyButton } from "@/components/common/CopyButton";
import { notify } from "@/components/common/toast";
import { errorMessage } from "@/api/errors";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useInstitution } from "../context";
import { CapabilityGate } from "../components/InstitutionGate";
import type { InstitutionApp } from "../types";
import { instAppKeys, instAppsApi, instAppsQuery } from "../apps/api";
import { JoinColumnBadge } from "../apps/components/JoinColumnBadge";
import { TwoFactorCell } from "../apps/components/TwoFactorCell";

function AppsList() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const { scope, ctx, perms } = inst;
  const qc = useQueryClient();
  const confirm = useConfirm();
  const apps = useQuery(instAppsQuery(scope));
  const canManage = inst.can("manage-clients");
  const [secret, setSecret] = useState<{ clientId: string; value: string } | null>(null);

  const invalidate = () => qc.invalidateQueries({ queryKey: instAppKeys.all(scope) });
  const grant = useMutation({
    mutationFn: (clientId: string) => instAppsApi.grantAccess(scope, clientId),
    onSuccess: (_d, clientId) => {
      notify.success(t("instApps.list.granted", { clientId }));
      void invalidate();
    },
    onError: (e) => notify.error(e),
  });
  const revoke = useMutation({
    mutationFn: (clientId: string) => instAppsApi.revokeAccess(scope, clientId),
    onSuccess: (_d, clientId) => {
      notify.success(t("instApps.list.revoked", { clientId }));
      void invalidate();
    },
    onError: (e) => notify.error(e),
  });
  const requireMfa = useMutation({
    mutationFn: (clientId: string) => instAppsApi.requireMfa(scope, clientId),
    onSuccess: (_d, clientId) => {
      notify.success(t("instApps.mfa.nowRequired", { clientId }));
      void invalidate();
    },
    onError: (e) => notify.error(e),
  });
  const reveal = useMutation({
    mutationFn: (clientId: string) => instAppsApi.secret(scope, clientId),
    onSuccess: (d, clientId) => setSecret({ clientId, value: d.client_secret }),
    onError: (e, clientId) => setSecret({ clientId, value: t("instApps.list.secretUnavailable", { error: errorMessage(e) }) }),
  });

  async function onRevoke(a: InstitutionApp) {
    const ok = await confirm({
      title: t("instApps.list.revokeTitle", { institution: ctx.institution, clientId: a.clientId }),
      description: (
        <div className="space-y-2">
          <p>{t("instApps.list.revokeBody")}</p>
          {a.cross_cutting ? <p>{t("instApps.list.revokeCrossCutting", { institution: ctx.institution })}</p> : null}
        </div>
      ),
      confirmLabel: t("instApps.list.revoke"),
      destructive: true,
    });
    if (ok) revoke.mutate(a.clientId);
  }

  async function onRequireMfa(a: InstitutionApp) {
    const ok = await confirm({
      title: t("instApps.mfa.requireTitle", { clientId: a.clientId }),
      description: t("instApps.mfa.requireBody"),
      confirmLabel: t("instApps.mfa.require"),
    });
    if (ok) requireMfa.mutate(a.clientId);
  }

  if (apps.isPending) return <Loading />;
  if (apps.isError) return <ErrorState error={apps.error} onRetry={() => void apps.refetch()} />;
  const rows = apps.data;
  const unconfigured = rows.filter((a) => !a.join_column.explicit);
  const mine = unconfigured.filter((a) => a.owned && a.manageable);

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("instApps.list.title")}
        description={t("instApps.list.description", { institution: ctx.institution, allow: perms.clients.allow.join(", ") || t("common.none") })}
        actions={
          canManage ? (
            <>
              <Button asChild variant="outline">
                <Link to={inst.path("/institution/apps/join-keys")}>{t("instApps.list.reviewJoinKeys")}</Link>
              </Button>
              <Button asChild>
                <Link to={inst.path("/institution/apps/new")}>{t("instApps.list.newClient")}</Link>
              </Button>
            </>
          ) : null
        }
      />

      {secret ? (
        <div className="rounded-lg border bg-muted/40 px-4 py-3 text-sm" data-testid="app-secret">
          <div className="flex items-center justify-between gap-2">
            <p className="font-medium">{t("instApps.list.secretFor", { clientId: secret.clientId })}</p>
            <div className="flex gap-2">
              <CopyButton value={secret.value} />
              <Button size="sm" variant="ghost" onClick={() => setSecret(null)}>
                {t("instApps.list.hide")}
              </Button>
            </div>
          </div>
          <code className="mt-1 block break-all rounded bg-background px-2 py-1 font-mono text-xs">{secret.value}</code>
          <p className="mt-1 text-xs text-muted-foreground">{t("instApps.list.secretNote")}</p>
        </div>
      ) : null}

      {unconfigured.length > 0 ? (
        <div className="flex flex-col gap-2 rounded-lg border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="font-medium text-destructive">
              {t("instApps.list.unconfigured", { count: unconfigured.length, total: rows.length })}
              {mine.length > 0 && mine.length !== unconfigured.length ? ` ${t("instApps.list.yoursToFix", { count: mine.length })}` : ""}
            </p>
            <p className="mt-0.5 text-muted-foreground">{t("instApps.list.unconfiguredBody")}</p>
          </div>
          <Button asChild variant="outline" className="shrink-0">
            <Link to={inst.path("/institution/apps/join-keys")}>{t("instApps.list.reviewJoinKeys")}</Link>
          </Button>
        </div>
      ) : null}

      {!canManage ? <div className="rounded-lg border bg-muted/40 px-4 py-3 text-sm text-muted-foreground">{t("instApps.list.readOnly")}</div> : null}

      <div className="rounded-xl border bg-card shadow-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("instApps.list.clientId")}</TableHead>
              <TableHead>{t("common.name")}</TableHead>
              <TableHead>{t("instApps.list.belongsTo")}</TableHead>
              <TableHead>{t("instApps.list.joinKey")}</TableHead>
              <TableHead>2FA</TableHead>
              <TableHead>{t("instApps.list.scope")}</TableHead>
              <TableHead className="text-right">{t("instApps.list.access")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((a) => {
              // The wrapper's own rule: a cross-cutting app's key is the platform's.
              const joinKeyEditable = canManage && a.owned && a.manageable;
              return (
                <TableRow key={a.clientId} data-testid={`app-row-${a.clientId}`}>
                  <TableCell className="font-mono text-xs">{a.clientId}</TableCell>
                  <TableCell className="text-sm">{a.name ?? "—"}</TableCell>
                  <TableCell>
                    {a.cross_cutting ? (
                      <Badge
                        variant="outline"
                        title={a.scope.all_institutions ? t("instApps.list.sharedAll") : t("instApps.list.sharedWith", { list: a.scope.institutions.join(", ") })}
                      >
                        {t("instApps.list.crossCutting")}
                      </Badge>
                    ) : (
                      <Badge variant="secondary">{ctx.institution}</Badge>
                    )}
                  </TableCell>
                  <TableCell>
                    <Link
                      to={inst.path(`/institution/apps/${encodeURIComponent(a.clientId)}/join-key`)}
                      className="group inline-flex items-center gap-1.5 hover:underline"
                      aria-label={joinKeyEditable ? t("instApps.list.changeJoinKey", { clientId: a.clientId }) : t("instApps.list.viewJoinKey", { clientId: a.clientId })}
                    >
                      <JoinColumnBadge setting={a.join_column} />
                      {joinKeyEditable ? <Pencil aria-hidden className="h-3.5 w-3.5 text-muted-foreground group-hover:text-foreground" /> : null}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <TwoFactorCell
                      app={a}
                      canRequire={canManage && a.manageable && a.owned && !a.cross_cutting}
                      onRequire={() => void onRequireMfa(a)}
                      busy={requireMfa.isPending}
                    />
                  </TableCell>
                  <TableCell>
                    {a.manageable ? (
                      <Badge variant="secondary">{t("instApps.list.manageable")}</Badge>
                    ) : (
                      <Badge variant="outline" className="opacity-70">
                        {t("instApps.list.viewOnly")}
                      </Badge>
                    )}
                  </TableCell>
                  <TableCell className="text-right">
                    <div className="flex items-center justify-end gap-1">
                      <Button asChild variant="ghost" size="sm">
                        <Link to={inst.path(`/institution/apps/${encodeURIComponent(a.clientId)}/users`)}>{t("instApps.list.users")}</Link>
                      </Button>
                      {canManage && a.manageable ? (
                        <>
                          {/* Owned apps only: a cross-cutting secret is the platform's (403). */}
                          {a.owned ? (
                            <Button variant="ghost" size="sm" onClick={() => reveal.mutate(a.clientId)} disabled={reveal.isPending}>
                              {t("instApps.list.secret")}
                            </Button>
                          ) : null}
                          <Button variant="outline" size="sm" onClick={() => grant.mutate(a.clientId)} disabled={grant.isPending}>
                            {t("instApps.list.grant")}
                          </Button>
                          <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" onClick={() => void onRevoke(a)}>
                            {t("instApps.list.revoke")}
                          </Button>
                        </>
                      ) : null}
                    </div>
                  </TableCell>
                </TableRow>
              );
            })}
            {rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7} className="py-8 text-center text-muted-foreground">
                  {t("instApps.list.empty")} {canManage ? t("instApps.list.emptyManage", { institution: ctx.institution }) : t("instApps.list.emptyReadOnly")}
                </TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </div>

      <div className="space-y-2 text-xs leading-relaxed text-muted-foreground">
        <p>{t("instApps.list.noteJoinKey")}</p>
        <p>{t("instApps.list.note2fa")}</p>
        <p>{t("instApps.list.noteAccess")}</p>
      </div>
    </div>
  );
}

export function Component() {
  return (
    <CapabilityGate capability="view-clients">
      <AppsList />
    </CapabilityGate>
  );
}
