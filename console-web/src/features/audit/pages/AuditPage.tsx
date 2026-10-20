/**
 * /audit — sign-in activity from Keycloak's own event store (GET
 * /dashboard/events, /dashboard/activity/24h, /dashboard/logins/stats).
 *
 * The reference console redirected /audit to /system-audit because a raw
 * event list reads like an audit log but is neither tamper-evident nor a
 * record of admin actions. This page keeps the activity view but says so
 * plainly and links to the real trail.
 */
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ArrowRight, Info } from "lucide-react";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { EmptyState, ErrorState, Loading } from "@/components/common/QueryState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatCount, formatWhen } from "@/lib/utils";
import { activity24hQuery, keycloakEventsQuery, loginStatsQuery } from "../api";

const EVENT_TYPES = ["LOGIN", "LOGIN_ERROR", "LOGOUT", "CODE_TO_TOKEN", "REFRESH_TOKEN", "UPDATE_PASSWORD", "UPDATE_TOTP", "REMOVE_TOTP"] as const;
const PERIODS = ["day", "week", "month"] as const;
const SELECT = "h-9 rounded-md border border-input bg-background px-2 text-sm shadow-sm";

export function Component() {
  const { t } = useTranslation();
  const [type, setType] = useState<string>("");
  const [max, setMax] = useState(100);
  const [period, setPeriod] = useState<(typeof PERIODS)[number]>("day");

  const activity = useQuery(activity24hQuery());
  const stats = useQuery(loginStatsQuery(period));
  const events = useQuery(keycloakEventsQuery({ ...(type ? { event_types: type } : {}), max_events: max }));
  const a = activity.data;

  return (
    <div className="space-y-6">
      <PageHeader title={t("audit.activity.title")} description={t("audit.activity.description")} />

      <div className="flex items-start gap-3 rounded-lg border border-primary/30 bg-primary/5 p-4 text-sm">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
        <div className="flex-1">{t("audit.activity.notTheAuditLog")}</div>
        <Button asChild size="sm" variant="outline">
          <Link to="/system-audit">
            {t("nav.auditLog")} <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </Button>
      </div>

      <section className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5" data-testid="activity-24h">
        {activity.isError ? (
          <div className="sm:col-span-2 lg:col-span-5">
            <ErrorState error={activity.error} title={t("audit.activity.summaryUnavailable")} />
          </div>
        ) : (
          (
            [
              ["logins", a?.logins],
              ["failedLogins", a?.failed_logins],
              ["logouts", a?.logouts],
              ["registrations", a?.registrations],
              ["totalEvents", a?.total_events],
            ] as const
          ).map(([k, v]) => (
            <Card key={k}>
              <CardHeader className="pb-4">
                <CardDescription>{t(`audit.activity.${k}`)}</CardDescription>
                <CardTitle className="text-2xl tabular-nums">{formatCount(v)}</CardTitle>
              </CardHeader>
            </Card>
          ))
        )}
      </section>

      <Card>
        <CardHeader className="flex flex-row items-center justify-between space-y-0">
          <div>
            <CardTitle>{t("audit.activity.statsTitle")}</CardTitle>
            <CardDescription>{t("audit.activity.statsDescription")}</CardDescription>
          </div>
          <select aria-label={t("audit.activity.period")} value={period} onChange={(e) => setPeriod(e.target.value as (typeof PERIODS)[number])} className={SELECT}>
            {PERIODS.map((p) => (
              <option key={p} value={p}>
                {t(`audit.activity.periods.${p}`)}
              </option>
            ))}
          </select>
        </CardHeader>
        <CardContent>
          {stats.isPending ? (
            <Loading />
          ) : stats.isError ? (
            <ErrorState error={stats.error} />
          ) : (
            <div className="flex flex-wrap gap-8 text-sm">
              <div>
                <div className="text-2xl font-semibold tabular-nums">{formatCount(stats.data.total_logins)}</div>
                <div className="text-xs text-muted-foreground">{t("audit.activity.totalLogins")}</div>
              </div>
              <div>
                <div className="text-2xl font-semibold tabular-nums">{formatCount(stats.data.unique_users)}</div>
                <div className="text-xs text-muted-foreground">{t("audit.activity.uniqueUsers")}</div>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex flex-row flex-wrap items-end justify-between gap-3 space-y-0">
          <div>
            <CardTitle>{t("audit.activity.eventsTitle")}</CardTitle>
            <CardDescription>{t("audit.activity.eventsDescription")}</CardDescription>
          </div>
          <div className="flex flex-wrap gap-2">
            <select aria-label={t("audit.filters.eventType")} value={type} onChange={(e) => setType(e.target.value)} className={SELECT}>
              <option value="">{t("audit.filters.all")}</option>
              {EVENT_TYPES.map((x) => (
                <option key={x} value={x}>
                  {x}
                </option>
              ))}
            </select>
            <select aria-label={t("audit.filters.max")} value={max} onChange={(e) => setMax(Number(e.target.value))} className={SELECT}>
              {[50, 100, 500, 1000].map((n) => (
                <option key={n} value={n}>
                  {n}
                </option>
              ))}
            </select>
          </div>
        </CardHeader>
        <CardContent>
          {events.isPending ? (
            <Loading />
          ) : events.isError ? (
            <ErrorState error={events.error} onRetry={() => void events.refetch()} />
          ) : events.data.length === 0 ? (
            <EmptyState>{t("audit.activity.noEvents")}</EmptyState>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("common.time")}</TableHead>
                  <TableHead>{t("audit.events.type")}</TableHead>
                  <TableHead>{t("common.client")}</TableHead>
                  <TableHead>{t("common.user")}</TableHead>
                  <TableHead>IP</TableHead>
                  <TableHead>{t("audit.activity.error")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {events.data.map((e, i) => (
                  <TableRow key={`${e.time}-${i}`}>
                    <TableCell className="whitespace-nowrap font-mono text-xs">{formatWhen(e.time)}</TableCell>
                    <TableCell>
                      <Badge variant={e.error || e.type.endsWith("_ERROR") ? "destructive" : "secondary"}>{e.type}</Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{e.clientId ?? "—"}</TableCell>
                    <TableCell className="font-mono text-xs">
                      {e.userId ? (
                        <Link to={`/users/${encodeURIComponent(e.userId)}`} className="hover:underline">
                          {String(e.details?.username ?? e.userId)}
                        </Link>
                      ) : (
                        "—"
                      )}
                    </TableCell>
                    <TableCell className="font-mono text-xs">{e.ipAddress ?? "—"}</TableCell>
                    <TableCell className="font-mono text-xs text-destructive">{e.error ?? ""}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
