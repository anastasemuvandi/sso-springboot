/**
 * /dashboard — the platform overview: stat cards, quick actions, needs
 * attention, recent activity, the four overview sections (from
 * `/dashboard/overview`, each with available/error), and live metrics.
 *
 * Every read is independent: one failing never blanks the page, and a value
 * that could not be read renders "—" (never 0).
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ArrowRight, Building2, Fingerprint, IdCard, KeyRound, RefreshCw, ScrollText, SquareStack, UserPlus, Users } from "lucide-react";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { auditEventsQuery } from "@/features/audit/api";
import { clientsListQuery } from "@/features/clients/api";
import { mfaStatusFor } from "@/features/clients/state/mfaStatus";
import { employeesQuery } from "@/features/employees/api";
import { OverviewSections } from "@/features/overview/OverviewSections";
import { NeedsAttention, QuickActions, RecentActivity, StatCard, type QuickAction } from "@/features/overview/widgets";
import { realmMfaQuery } from "@/features/settings/api";
import { formatCount, formatWhen } from "@/lib/utils";
import { dashboardKeys, fetchOverview, metricsQuery, overviewQuery, recentLoginsQuery, sessionsInfoQuery } from "../api";
import { applications, buildAttention, joinKeysOutstanding, unavailableSources } from "../state/attention";

export function DashboardPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();

  const metrics = useQuery(metricsQuery());
  const sessions = useQuery(sessionsInfoQuery());
  const recent = useQuery(recentLoginsQuery(10));
  const employees = useQuery(employeesQuery({ limit: 1 }));
  const clients = useQuery(clientsListQuery());
  const realmMfa = useQuery(realmMfaQuery());
  const audit = useQuery(auditEventsQuery({ limit: 8 }));
  const overview = useQuery(overviewQuery());

  const refresh = useMutation({
    mutationFn: () => fetchOverview(true),
    onSuccess: (data) => {
      qc.setQueryData(dashboardKeys.overview(), data);
      notify.success(t("dashboard.refreshed"));
    },
    onError: (e) => notify.error(e),
  });

  const summary = employees.data?.summary ?? null;
  const apps = clients.data ? applications(clients.data) : null;
  const byKind = (kind: string) => (apps ? apps.filter((c) => (c.scope?.kind ?? "platform") === kind).length : null);
  const withMfa = apps && realmMfa.data ? apps.filter((c) => mfaStatusFor(c, realmMfa.data).kind === "required").length : null;
  const join = clients.data ? joinKeysOutstanding(clients.data) : null;

  const input = { summary, clients: clients.data ?? null, realmMfa: realmMfa.data ?? null, overview: overview.data ?? null };
  const attention = buildAttention(input).map((a) => ({
    id: a.id,
    count: a.count,
    text: t(a.textKey, a.textParams),
    href: a.href,
    cta: a.ctaKey ? t(a.ctaKey) : undefined,
  }));
  const unavailable = unavailableSources(input, {
    employees: employees.isError,
    clients: clients.isError,
    realmMfa: realmMfa.isError,
    overview: overview.isError,
  }).map((k) => t(k));

  const actions: QuickAction[] = [
    { label: t("dashboard.actions.registerClient"), href: "/clients/new", icon: KeyRound, description: t("dashboard.actions.registerClientHint") },
    { label: t("dashboard.actions.addUser"), href: "/users/new", icon: UserPlus, description: t("dashboard.actions.addUserHint") },
    { label: t("nav.employees"), href: "/employees", icon: IdCard, description: t("dashboard.actions.employeesHint") },
    { label: t("nav.institutions"), href: "/agencies", icon: Building2, description: t("dashboard.actions.institutionsHint") },
  ];

  const m = metrics.data;
  const recentRows = recent.data ?? [];

  return (
    <div className="space-y-10">
      <PageHeader
        title={t("dashboard.title")}
        description={t("dashboard.subtitle")}
        actions={
          <Button variant="outline" size="sm" onClick={() => refresh.mutate()} disabled={refresh.isPending} data-testid="overview-refresh">
            <RefreshCw className={refresh.isPending ? "animate-spin" : undefined} />
            {t("dashboard.refresh")}
          </Button>
        }
      />

      <section className="space-y-4">
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard
            testId="stat-employees"
            icon={IdCard}
            label={t("nav.employees")}
            value={summary?.total ?? null}
            breakdown={[
              { label: t("dashboard.cards.withAccount"), value: summary?.linked ?? null },
              { label: t("dashboard.cards.noAccount"), value: summary?.unlinked ?? null, tone: "warn" },
              { label: t("dashboard.cards.unmapped"), value: summary?.unmapped ?? null, tone: "warn" },
            ]}
            hint={employees.isError ? t("dashboard.cards.employeesUnavailable") : t("dashboard.cards.employeesHint")}
            href="/employees"
            cta={t("dashboard.cards.viewEmployees")}
          />
          <StatCard testId="stat-users" icon={Users} label={t("dashboard.cards.userAccounts")} value={m?.total_users ?? null} href="/users" cta={t("dashboard.cards.manageUsers")} />
          <StatCard
            testId="stat-clients"
            icon={SquareStack}
            label={t("nav.clients")}
            value={apps ? apps.length : null}
            breakdown={[
              { label: t("dashboard.cards.institutionOwned"), value: byKind("institution") },
              { label: t("dashboard.cards.crossCutting"), value: byKind("cross-cutting") },
              { label: t("dashboard.cards.platform"), value: byKind("platform") },
              { label: t("dashboard.cards.mfaAsked"), value: withMfa },
            ]}
            href="/clients"
            cta={t("dashboard.cards.manageClients")}
          />
          <StatCard
            testId="stat-join-keys"
            icon={KeyRound}
            label={t("dashboard.cards.joinKeysToSet")}
            value={join ? join.outstanding.length : null}
            tone={join && join.outstanding.length > 0 ? "warn" : "default"}
            breakdown={[{ label: t("dashboard.cards.configured"), value: join ? join.scoped.length - join.outstanding.length : null }]}
            hint={t("dashboard.cards.joinKeysHint")}
            href="/clients/join-keys"
            cta={t("dashboard.cards.reviewJoinKeys")}
          />
        </div>

        <QuickActions actions={actions} />

        <div className="grid gap-4 lg:grid-cols-2">
          <NeedsAttention items={attention} unavailable={unavailable} />
          <RecentActivity
            href="/system-audit"
            loading={audit.isPending}
            error={audit.isError ? errorMessage(audit.error) : null}
            rows={(audit.data?.items ?? []).map((e) => ({
              id: e.id,
              at: e.created_at,
              event: e.event_type,
              actor: e.performed_by,
              target: e.app_id ?? e.user_id,
              severity: e.severity,
            }))}
          />
        </div>

        {overview.isPending ? (
          <Loading />
        ) : overview.isError ? (
          <ErrorState error={overview.error} title={t("dashboard.overviewUnavailable")} onRetry={() => void overview.refetch()} />
        ) : (
          <OverviewSections
            data={overview.data}
            links={{ user: (sub) => `/users/${encodeURIComponent(sub)}`, app: (clientId) => `/clients/${encodeURIComponent(clientId)}` }}
          />
        )}
      </section>

      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <FeatureTile gradient="tile-violet" icon={<Users className="h-5 w-5 text-white" />} title={t("nav.users")} body={t("dashboard.tiles.users")} href="/users" cta={t("dashboard.cards.manageUsers")} />
        <FeatureTile gradient="tile-pink" icon={<Building2 className="h-5 w-5 text-white" />} title={t("nav.institutions")} body={t("dashboard.tiles.institutions")} href="/agencies" cta={t("dashboard.tiles.manageInstitutions")} />
        <FeatureTile gradient="tile-sky" icon={<KeyRound className="h-5 w-5 text-white" />} title={t("nav.clients")} body={t("dashboard.tiles.clients")} href="/clients" cta={t("dashboard.cards.manageClients")} />
        <FeatureTile gradient="tile-amber" icon={<Fingerprint className="h-5 w-5 text-white" />} title={t("nav.auditLog")} body={t("dashboard.tiles.audit")} href="/system-audit" cta={t("dashboard.tiles.viewAudit")} />
      </section>

      <section className="space-y-4">
        <h2 className="text-base font-semibold text-foreground">{t("dashboard.live.title")}</h2>
        {metrics.isError ? <p className="text-sm text-muted-foreground">{t("dashboard.live.metricsUnavailable", { error: errorMessage(metrics.error) })}</p> : null}
        <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <Metric label={t("nav.users")} value={m?.total_users} />
          <Metric label={t("nav.clients")} value={m?.total_clients} />
          <Metric label={t("nav.institutions")} value={m?.total_agencies} />
          <Metric label={t("dashboard.live.activeSessions")} value={m?.active_sessions ?? sessions.data?.total_sessions} />
          <Metric label={t("dashboard.live.loginsToday")} value={m?.logins_today} />
          <Metric label={t("dashboard.live.newUsers")} value={m?.new_users_this_week} />
        </div>

        <Card>
          <CardHeader className="flex flex-row items-start justify-between gap-2 space-y-0">
            <div>
              <CardTitle className="flex items-center gap-2">
                <ScrollText className="h-4 w-4 text-muted-foreground" />
                {t("dashboard.live.recentLogins")}
              </CardTitle>
              <CardDescription>{t("dashboard.live.recentLoginsHint")}</CardDescription>
            </div>
            <Button asChild size="sm" variant="ghost">
              <Link to="/system-audit">
                {t("overview.fullAuditLog")} <ArrowRight className="h-3.5 w-3.5" />
              </Link>
            </Button>
          </CardHeader>
          <CardContent>
            {recent.isPending ? (
              <Loading />
            ) : recent.isError ? (
              <ErrorState error={recent.error} />
            ) : recentRows.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("dashboard.live.noLogins")}</p>
            ) : (
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("common.time")}</TableHead>
                    <TableHead>{t("common.user")}</TableHead>
                    <TableHead>{t("common.client")}</TableHead>
                    <TableHead>IP</TableHead>
                    <TableHead>{t("common.status")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {recentRows.map((row, i) => (
                    <TableRow key={i}>
                      <TableCell className="font-mono text-xs">{formatWhen(row.timestamp)}</TableCell>
                      <TableCell>{row.user}</TableCell>
                      <TableCell className="font-mono text-xs">{row.client_id ?? "—"}</TableCell>
                      <TableCell className="font-mono text-xs">{row.ip_address ?? "—"}</TableCell>
                      <TableCell>
                        <Badge variant={row.status === "success" ? "secondary" : "destructive"}>{row.status}</Badge>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
          </CardContent>
        </Card>
      </section>
    </div>
  );
}

function FeatureTile({ gradient, icon, title, body, href, cta }: { gradient: string; icon: React.ReactNode; title: string; body: string; href: string; cta: string }) {
  return (
    <Link to={href} className="group flex h-full flex-col justify-between rounded-xl border border-border bg-background p-5 shadow-card transition-shadow hover:shadow-tile">
      <div>
        <div className={`grid h-11 w-11 place-items-center rounded-xl ${gradient} shadow-tile`}>{icon}</div>
        <h3 className="mt-4 text-base font-semibold text-foreground">{title}</h3>
        <p className="mt-1 text-sm text-muted-foreground">{body}</p>
      </div>
      <span className="mt-5 inline-flex items-center text-sm font-medium text-primary">
        {cta}
        <ArrowRight className="ml-1 h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5" />
      </span>
    </Link>
  );
}

function Metric({ label, value }: { label: string; value?: number | null }) {
  return (
    <Card>
      <CardHeader className="pb-4">
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl">{formatCount(value)}</CardTitle>
      </CardHeader>
    </Card>
  );
}

export const Component = DashboardPage;
