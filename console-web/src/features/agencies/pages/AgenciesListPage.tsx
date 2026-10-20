/**
 * /agencies — "Institutions": the registry × Keycloak × IPPIS reconciliation
 * (GET /institutions), with membership counts from GET /agency/. Nothing here
 * creates or provisions an institution: onboarding is a registry edit plus
 * Terraform; a row's remedy text is the action.
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { Plus } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useConfirm } from "@/components/common/ConfirmDialog";
import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { agenciesQuery, agencyKeys, deleteAgency, institutionsQuery } from "../api";
import { groupNameDiffers, isManageable, membersById, rowKey, rowLabel, statusMeta } from "../state/institutions";

export function Component() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const overview = useQuery(institutionsQuery());
  // Membership is the garnish; its failure must not take the page down.
  const agencies = useQuery(agenciesQuery());

  const remove = useMutation({
    mutationFn: (id: string) => deleteAgency(id),
    onSuccess: () => {
      notify.success(t("agencies.deleted"));
      void qc.invalidateQueries({ queryKey: agencyKeys.all });
    },
    onError: (e) => notify.error(e),
  });

  const newButton = (
    <Button asChild>
      <Link to="/agencies/new">
        <Plus />
        {t("agencies.new")}
      </Link>
    </Button>
  );

  if (overview.isPending) return <Loading />;
  if (overview.isError)
    return (
      <div className="space-y-6">
        <PageHeader title={t("nav.institutions")} actions={newButton} />
        <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />
      </div>
    );

  const data = overview.data;
  const members = membersById(agencies.data ?? []);
  const attention = data.needs_attention;

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("nav.institutions")}
        description={`${t("agencies.rowsSummary", { count: data.total })} ${attention > 0 ? t("agencies.needAttention", { count: attention }) : t("agencies.allAgree")}`}
        actions={newButton}
      />

      {!data.mirror ? (
        <div className="rounded-xl border border-amber-500/40 bg-amber-500/5 p-4 text-sm" data-testid="no-mirror">
          <span className="font-medium">{t("agencies.noMirrorTitle")}</span> {t("agencies.noMirrorBody")}
        </div>
      ) : null}

      <div className="rounded-xl border bg-card shadow-card">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{t("agencies.col.institution")}</TableHead>
              <TableHead>{t("common.status")}</TableHead>
              <TableHead>{t("agencies.col.employees")}</TableHead>
              <TableHead>{t("agencies.col.members")}</TableHead>
              <TableHead className="text-right">{t("common.actions")}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {data.items.map((row, i) => {
              const meta = statusMeta(row.status);
              const m = row.group_id ? members.get(row.group_id) : undefined;
              const missing = row.missing_roles ?? [];
              return (
                <TableRow key={rowKey(row, i)}>
                  <TableCell>
                    <div className="flex flex-col gap-0.5">
                      <code className="text-xs">{rowLabel(row)}</code>
                      {row.display_name && row.display_name !== row.slug ? <span className="text-xs text-muted-foreground">{row.display_name}</span> : null}
                      {groupNameDiffers(row) ? (
                        <span className="text-xs text-amber-700 dark:text-amber-400">{t("agencies.groupNamed", { name: row.group_name })}</span>
                      ) : null}
                      {row.registry_status && row.registry_status !== "active" ? (
                        <span className="text-xs text-muted-foreground">{t("agencies.registryStatus", { status: row.registry_status })}</span>
                      ) : null}
                    </div>
                  </TableCell>
                  <TableCell>
                    <div className="flex flex-col gap-1">
                      <Badge variant={meta.variant} className={`w-fit ${meta.className ?? ""}`}>
                        {meta.raw ?? t(meta.labelKey)}
                      </Badge>
                      {row.remedy ? <span className="max-w-md text-xs text-muted-foreground">{row.remedy}</span> : null}
                      {missing.length > 0 ? (
                        <span className="max-w-md text-xs text-muted-foreground">
                          {t("agencies.missingRoles", { count: row.roles_expected - row.roles_present })} <code>{missing.join(", ")}</code>
                        </span>
                      ) : null}
                    </div>
                  </TableCell>
                  <TableCell className="text-sm">
                    {data.mirror ? (
                      <div className="flex flex-col">
                        <span>{row.employees}</span>
                        {row.employees_unlinked > 0 ? (
                          <span className="text-xs text-muted-foreground">{t("agencies.withoutAccount", { count: row.employees_unlinked })}</span>
                        ) : null}
                      </div>
                    ) : (
                      "—"
                    )}
                  </TableCell>
                  <TableCell className="text-sm">
                    {m === undefined ? (
                      "—"
                    ) : (
                      <div className="flex flex-col">
                        <span>{m.total}</span>
                        {m.active !== m.total ? <span className="text-xs text-muted-foreground">{t("agencies.activeCount", { count: m.active })}</span> : null}
                      </div>
                    )}
                  </TableCell>
                  <TableCell className="text-right">
                    {isManageable(row) ? (
                      <div className="flex items-center justify-end gap-1">
                        <Button asChild variant="ghost" size="sm">
                          <Link to={`/agencies/${encodeURIComponent(row.group_id)}`}>{t("agencies.manage")}</Link>
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          onClick={async () => {
                            const name = row.group_name ?? row.slug ?? "";
                            if (await confirm({ title: t("agencies.deleteTitle", { name }), description: t("agencies.deleteBody"), destructive: true, confirmLabel: t("common.delete") }))
                              remove.mutate(row.group_id);
                          }}
                        >
                          {t("common.delete")}
                        </Button>
                      </div>
                    ) : null}
                  </TableCell>
                </TableRow>
              );
            })}
            {data.items.length === 0 ? (
              <TableRow>
                <TableCell colSpan={5} className="py-8 text-center text-muted-foreground">
                  {t("agencies.empty")}
                </TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </div>

      <p className="text-xs text-muted-foreground">{t("agencies.sources", { source: data.registry_source })}</p>
    </div>
  );
}
