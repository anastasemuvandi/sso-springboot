/**
 * /institution — the institution overview: stat cards, quick actions, needs
 * attention, recent activity and the four overview sections from
 * GET /institution-admin/overview (each with available/error).
 *
 * Each read is made only if the tier includes its capability (cosmetic — the
 * wrapper would refuse it anyway) and fails independently: "—", never 0.
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Grid3x3, IdCard, KeyRound, RefreshCw, ShieldCheck, SquareStack, UserPlus, Users } from "lucide-react";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { OverviewSections } from "@/features/overview/OverviewSections";
import { NeedsAttention, QuickActions, RecentActivity, StatCard, type QuickAction } from "@/features/overview/widgets";
import { institutionOverviewQuery } from "../api";
import { useInstitution } from "../context";
import { ovAppsQuery, ovAuditQuery, ovEmployeesQuery, ovJoinColumnsQuery, ovMembersQuery, ovRolesQuery } from "../overview/api";
import { buildInstitutionAttention } from "../overview/state/attention";

export function Component() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const inst = useInstitution();
  const { scope, can } = inst;
  const members = can("view-members");
  const clients = can("view-clients");

  const overview = useQuery({ ...institutionOverviewQuery(scope), enabled: members });
  const staff = useQuery({ ...ovMembersQuery(scope), enabled: members });
  const employees = useQuery({ ...ovEmployeesQuery(scope), enabled: members });
  const apps = useQuery({ ...ovAppsQuery(scope), enabled: clients });
  const joins = useQuery({ ...ovJoinColumnsQuery(scope), enabled: clients });
  const audit = useQuery({ ...ovAuditQuery(scope), enabled: members });
  const roles = useQuery({ ...ovRolesQuery(scope), enabled: can("map-roles") });

  // GET /institution-admin/overview has no cache to bypass (unlike the
  // platform `/dashboard/overview?refresh=true`): refresh is a plain re-read.
  const refresh = useMutation({
    mutationFn: () => qc.fetchQuery({ ...institutionOverviewQuery(scope), staleTime: 0 }),
    onSuccess: () => notify.success(t("dashboard.refreshed")),
    onError: (e) => notify.error(e),
  });

  const summary = employees.data?.summary ?? null;
  const attention = buildInstitutionAttention({ overview: overview.data ?? null, summary, joinColumns: joins.data ?? null }).map((a) => ({
    id: a.id,
    count: a.count,
    text: t(a.textKey),
    href: a.href ? (a.href.startsWith("#") ? a.href : inst.path(a.href, a.extra)) : undefined,
  }));
  const unavailable = [
    overview.isError ? t("dashboard.sources.overview") : null,
    employees.isError ? t("dashboard.sources.employees") : null,
    joins.isError ? t("instOverview.sources.joinKeys") : null,
    overview.data && !overview.data.leavers.available ? t("dashboard.sources.leavers") : null,
    overview.data && !overview.data.password_setup.available ? t("dashboard.sources.passwordSetup") : null,
    overview.data && !overview.data.logins.available ? t("dashboard.sources.logins") : null,
  ].filter((x): x is string => x !== null);

  const actions = ([
    clients ? { label: t("institution.overview.actions.matrix"), href: inst.path("/institution/access/matrix"), icon: Grid3x3, description: t("institution.overview.actions.matrixHint") } : null,
    can("manage-members") && can("manage-clients")
      ? { label: t("institution.overview.actions.provision"), href: inst.path("/institution/employees"), icon: UserPlus, description: t("institution.overview.actions.provisionHint") }
      : null,
    clients ? { label: t("institution.overview.actions.apps"), href: inst.path("/institution/apps"), icon: SquareStack, description: t("institution.overview.actions.appsHint") } : null,
    members ? { label: t("institution.overview.actions.staff"), href: inst.path("/institution/staff"), icon: Users, description: t("institution.overview.actions.staffHint") } : null,
  ] as Array<QuickAction | null>).filter((x): x is QuickAction => x !== null);

  const appList = apps.data ?? null;

  return (
    <div className="space-y-8">
      <PageHeader
        title={t("institution.overview.title")}
        description={
          <>
            {t("institution.overview.subtitle", { institution: inst.ctx.institution })}
            {inst.ctx.is_platform_admin ? <span className="mt-1 block text-xs">{t("institution.overview.actingAs", { institution: inst.ctx.institution })}</span> : null}
          </>
        }
        actions={
          members ? (
            <Button variant="outline" size="sm" onClick={() => refresh.mutate()} disabled={refresh.isPending} data-testid="overview-refresh">
              <RefreshCw className={refresh.isPending ? "animate-spin" : undefined} />
              {t("institution.overview.refresh")}
            </Button>
          ) : null
        }
      />

      <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        {t("institution.overview.capabilities")}:
        {inst.perms.capabilities.map((c) => (
          <Badge key={c} variant="outline" className="font-mono text-[10px]">
            {c}
          </Badge>
        ))}
      </div>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard
          testId="inst-stat-employees"
          icon={IdCard}
          label={t("nav.employees")}
          value={summary?.total ?? null}
          breakdown={[
            { label: t("dashboard.cards.withAccount"), value: summary?.linked ?? null },
            { label: t("dashboard.cards.noAccount"), value: summary?.unlinked ?? null, tone: "warn" },
          ]}
          href={members ? inst.path("/institution/employees") : undefined}
          cta={t("dashboard.cards.viewEmployees")}
        />
        <StatCard testId="inst-stat-members" icon={Users} label={t("institution.overview.members")} value={staff.data ? staff.data.length : null} href={members ? inst.path("/institution/staff") : undefined} cta={t("dashboard.cards.manageUsers")} />
        <StatCard
          testId="inst-stat-apps"
          icon={SquareStack}
          label={t("nav.clients")}
          value={appList ? appList.length : null}
          breakdown={[
            { label: t("instOverview.owned"), value: appList ? appList.filter((a) => a.owned).length : null },
            { label: t("dashboard.cards.crossCutting"), value: appList ? appList.filter((a) => a.cross_cutting).length : null },
          ]}
          href={clients ? inst.path("/institution/apps") : undefined}
          cta={t("dashboard.cards.manageClients")}
        />
        <StatCard
          icon={KeyRound}
          label={t("dashboard.cards.joinKeysToSet")}
          value={joins.data ? joins.data.outstanding : null}
          tone={joins.data && joins.data.outstanding > 0 ? "warn" : "default"}
          breakdown={[{ label: t("dashboard.cards.configured"), value: joins.data?.configured ?? null }]}
          href={clients ? inst.path("/institution/apps/join-keys") : undefined}
          cta={t("dashboard.cards.reviewJoinKeys")}
        />
        <StatCard icon={ShieldCheck} label={t("instOverview.assignableRoles")} value={roles.data ? roles.data.length : null} href={can("map-roles") ? inst.path("/institution/roles") : undefined} cta={t("nav.roles")} />
      </div>

      <QuickActions actions={actions} />

      {members ? (
        <>
          <div className="grid gap-4 lg:grid-cols-2">
            <NeedsAttention items={attention} unavailable={unavailable} />
            <RecentActivity
              href={inst.path("/institution/audit")}
              loading={audit.isPending}
              error={audit.isError ? errorMessage(audit.error) : null}
              rows={(audit.data?.items ?? []).map((e) => ({ id: e.id, at: e.created_at, event: e.event_type, actor: e.performed_by, target: e.target ?? e.app_id ?? e.user_id, severity: e.severity }))}
            />
          </div>
          {overview.isPending ? (
            <Loading />
          ) : overview.isError ? (
            <ErrorState error={overview.error} title={t("institution.overview.unavailable")} onRetry={() => void overview.refetch()} />
          ) : (
            <OverviewSections
              data={overview.data}
              links={{
                user: (sub) => inst.path(`/institution/staff/${encodeURIComponent(sub)}`),
                app: (clientId) => inst.path(`/institution/apps/${encodeURIComponent(clientId)}/users`),
                resend: inst.path("/institution/access/matrix"),
              }}
            />
          )}
        </>
      ) : null}
    </div>
  );
}
