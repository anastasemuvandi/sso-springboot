/**
 * The four overview sections — leavers still holding access, accounts waiting
 * for a first password, login activity, access per application. Rendered by
 * both consoles; what differs is where rows link to (`links`).
 *
 * A section the wrapper could not read shows its reason ("Not available — …"),
 * never zeros.
 */
import { Link } from "react-router-dom";
import { KeyRound, LogIn, ShieldAlert, SquareStack } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Unavailable } from "@/components/common/QueryState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn, formatCount, formatWhen } from "@/lib/utils";
import type { Overview } from "./types";
import { SectionCard } from "./widgets";

export interface OverviewLinks {
  user: (sub: string) => string;
  app: (clientId: string) => string;
  /** Where a failed set-password email is resent from (institution access matrix), if anywhere. */
  resend?: string;
}

function Num({ label, value, tone, testId }: { label: string; value?: number | null; tone?: "warn"; testId?: string }) {
  return (
    <div className="rounded-lg border bg-muted/20 px-3 py-2" data-testid={testId}>
      <div className={cn("text-xl font-semibold tabular-nums", tone === "warn" && (value ?? 0) > 0 && "text-amber-700 dark:text-amber-400")}>
        {formatCount(value)}
      </div>
      <div className="text-xs text-muted-foreground">{label}</div>
    </div>
  );
}

export function OverviewSections({ data, links }: { data: Overview; links: OverviewLinks }) {
  const { t } = useTranslation();
  const { leavers, password_setup: setup, logins, access } = data;
  const leaverItems = leavers.items ?? [];
  const setupItems = setup.items ?? [];
  const accessItems = access.items ?? [];
  const failedEmails = (setup.counts?.failed ?? 0) + (setup.counts?.not_configured ?? 0);
  const inst = Boolean(data.institution);

  return (
    <div className="space-y-4">
      <div className="grid gap-4 lg:grid-cols-2">
        {/* ---- leavers still holding access ---- */}
        <SectionCard
          id="leavers"
          icon={ShieldAlert}
          title={t("overview.leavers.title")}
          tone={leavers.available && leavers.total > 0 ? "danger" : undefined}
          badge={
            leavers.available ? (
              <Badge variant={leavers.total > 0 ? "destructive" : "secondary"} className="ml-1" data-testid="leavers-total">
                {leavers.total}
                {leavers.truncated ? "+" : ""}
              </Badge>
            ) : null
          }
        >
          {!leavers.available ? (
            <Unavailable error={leavers.error} />
          ) : leavers.total === 0 ? (
            <p className="text-sm text-muted-foreground">{t("overview.leavers.none")}</p>
          ) : (
            <>
              <p className="mb-2 text-xs text-muted-foreground">{t("overview.leavers.explain")}</p>
              <ul className="divide-y">
                {leaverItems.map((r) => (
                  <li key={r.employee_number} className="py-2 text-sm">
                    <div className="flex flex-wrap items-center gap-1.5">
                      {r.sub ? (
                        <Link to={links.user(r.sub)} className="font-medium hover:underline">
                          {r.name}
                        </Link>
                      ) : (
                        <span className="font-medium">{r.name}</span>
                      )}
                      <span className="font-mono text-xs text-muted-foreground">{r.employee_number}</span>
                      {r.institution ? (
                        <Badge variant="outline" className="px-1 py-0 text-[10px]">
                          {r.institution}
                        </Badge>
                      ) : null}
                      {(r.reasons ?? []).map((reason) => (
                        <Badge key={reason} variant={reason === "account_enabled" ? "destructive" : "outline"} className="px-1 py-0 text-[10px]">
                          {t(`overview.leavers.reason.${reason}`, { defaultValue: reason })}
                        </Badge>
                      ))}
                    </div>
                    {(r.applications ?? []).length > 0 ? (
                      <div className="mt-0.5 font-mono text-xs text-muted-foreground">{(r.applications ?? []).join(", ")}</div>
                    ) : null}
                  </li>
                ))}
              </ul>
              {leavers.total > leaverItems.length ? (
                <p className="mt-2 text-xs text-muted-foreground">{t("overview.showingOf", { shown: leaverItems.length, total: leavers.total })}</p>
              ) : null}
            </>
          )}
          {leavers.available && leavers.truncated ? <p className="mt-2 text-xs text-muted-foreground">{t("overview.leavers.truncated")}</p> : null}
          {leavers.available && leavers.applications_checked === false ? (
            <p className="mt-2 text-xs text-muted-foreground">{t("overview.leavers.appsUnchecked")}</p>
          ) : null}
        </SectionCard>

        {/* ---- accounts waiting for their first password ---- */}
        <SectionCard
          id="password-setup"
          icon={KeyRound}
          title={t("overview.setup.title")}
          tone={failedEmails > 0 ? "warn" : undefined}
          badge={
            setup.available && setup.counts ? (
              <Badge variant="secondary" className="ml-1">
                {setup.counts.total}
              </Badge>
            ) : null
          }
        >
          {!setup.available || !setup.counts ? (
            <Unavailable error={setup.error} />
          ) : setup.counts.total === 0 ? (
            <p className="text-sm text-muted-foreground">{t("overview.setup.none")}</p>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                <Num label={t("overview.setup.failed")} value={setup.counts.failed} tone="warn" />
                <Num label={t("overview.setup.notConfigured")} value={setup.counts.not_configured} tone="warn" />
                <Num label={t("overview.setup.unknown")} value={setup.counts.unknown} />
                <Num label={t("overview.setup.sent")} value={setup.counts.sent} />
              </div>
              <ul className="mt-3 divide-y">
                {setupItems.slice(0, 8).map((r, i) => (
                  <li key={r.sub ?? r.username ?? i} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
                    <span className="min-w-0">
                      {r.sub ? (
                        <Link to={links.user(r.sub)} className="hover:underline">
                          {r.username ?? r.email ?? r.sub}
                        </Link>
                      ) : (
                        r.username
                      )}
                      {r.at ? <span className="ml-2 text-xs text-muted-foreground">{formatWhen(r.at)}</span> : null}
                    </span>
                    <Badge
                      variant="outline"
                      className={cn("px-1 py-0 text-[10px]", (r.setup_email === "failed" || r.setup_email === "not_configured") && "text-amber-700")}
                    >
                      {t(`overview.setup.bucket.${r.setup_email}`, { defaultValue: r.setup_email })}
                    </Badge>
                  </li>
                ))}
              </ul>
              {failedEmails > 0 && links.resend ? (
                <Button asChild size="sm" variant="outline" className="mt-3">
                  <Link to={links.resend}>{t("overview.setup.resend")}</Link>
                </Button>
              ) : null}
              <p className="mt-2 text-xs text-muted-foreground">{t("overview.setup.explain")}</p>
            </>
          )}
        </SectionCard>
      </div>

      {/* ---- login activity ---- */}
      <SectionCard id="logins" icon={LogIn} title={t("overview.logins.title")} tone={logins.available && (logins.locked_now?.length ?? 0) > 0 ? "warn" : undefined}>
        {!logins.available ? (
          <>
            <Unavailable error={logins.error} />
            {logins.active_sessions !== undefined && logins.active_sessions !== null ? (
              <p className="mt-2 text-sm">{t("overview.logins.activeSessionsLine", { count: logins.active_sessions })}</p>
            ) : null}
          </>
        ) : (
          <>
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
              <Num label={t("overview.logins.logins24h")} value={logins.logins_24h} testId="logins-24h" />
              <Num label={t("overview.logins.logins7d")} value={logins.logins_7d} />
              <Num label={t("overview.logins.people7d")} value={logins.people_7d} />
              <Num label={t("overview.logins.failed24h")} value={logins.failed_24h} tone="warn" />
              <Num label={t("overview.logins.failed7d")} value={logins.failed_7d} />
              <Num label={t("overview.logins.lockedNow")} value={logins.locked_now?.length ?? 0} tone="warn" />
              <Num label={inst ? t("overview.logins.sessionsOnApps") : t("overview.logins.activeSessions")} value={logins.active_sessions} />
            </div>
            <div className="mt-3 grid gap-4 sm:grid-cols-2">
              <div>
                <h3 className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{t("overview.logins.lockedNow")}</h3>
                {(logins.locked_now?.length ?? 0) === 0 ? (
                  <p className="mt-1 text-sm text-muted-foreground">{t("overview.logins.nobody")}</p>
                ) : (
                  <ul className="mt-1 space-y-1 text-sm">
                    {(logins.locked_now ?? []).map((u) => (
                      <li key={u.sub}>
                        <Link to={links.user(u.sub)} className="hover:underline">
                          {u.username ?? u.sub}
                        </Link>
                        {u.failures ? <span className="text-xs text-muted-foreground"> — {t("overview.logins.failures", { count: u.failures })}</span> : null}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
              <div>
                <h3 className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{t("overview.logins.mostFailures")}</h3>
                {(logins.most_failures_7d?.length ?? 0) === 0 ? (
                  <p className="mt-1 text-sm text-muted-foreground">{t("common.none")}</p>
                ) : (
                  <ul className="mt-1 space-y-1 text-sm">
                    {(logins.most_failures_7d ?? []).map((u) => (
                      <li key={u.sub}>
                        <Link to={links.user(u.sub)} className="hover:underline">
                          {u.username ?? u.sub}
                        </Link>
                        <span className="text-xs text-muted-foreground"> — {u.failures}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </div>
            <p className="mt-3 text-xs text-muted-foreground">
              {inst ? t("overview.logins.sourceInstitution") : t("overview.logins.source")}
              {logins.truncated ? ` ${t("overview.logins.truncated")}` : ""} {t("overview.logins.eventsOff")}
            </p>
          </>
        )}
      </SectionCard>

      {/* ---- access per application ---- */}
      <SectionCard id="access" icon={SquareStack} title={inst ? t("overview.access.titleInstitution") : t("overview.access.title")}>
        {!access.available && accessItems.length === 0 ? (
          <Unavailable error={access.error} />
        ) : accessItems.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("overview.access.none")}</p>
        ) : (
          <>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{inst ? t("overview.access.application") : t("overview.access.client")}</TableHead>
                  {inst ? <TableHead className="text-right">{t("overview.access.people")}</TableHead> : null}
                  <TableHead className="text-right">{t("overview.access.named")}</TableHead>
                  <TableHead className={inst ? "text-right" : undefined}>{inst ? t("overview.access.viaGroup") : t("overview.access.groupsAdmitted")}</TableHead>
                  <TableHead className="text-right">{t("overview.access.revoked")}</TableHead>
                  <TableHead className="text-right">{t("overview.access.sessions")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {accessItems.map((r, i) => (
                  <TableRow key={r.client_id ?? r.name ?? i}>
                    <TableCell>
                      {r.client_id ? (
                        <Link to={links.app(r.client_id)} className="font-mono text-xs hover:underline">
                          {r.client_id}
                        </Link>
                      ) : null}
                      {r.name && r.name !== r.client_id ? <span className="ml-2 text-xs text-muted-foreground">{r.name}</span> : null}
                      {r.cross_cutting ? (
                        <Badge variant="outline" className="ml-2 px-1 py-0 text-[10px]">
                          {t("overview.access.shared")}
                        </Badge>
                      ) : null}
                    </TableCell>
                    {inst ? <TableCell className="text-right font-medium tabular-nums">{formatCount(r.people)}</TableCell> : null}
                    <TableCell className="text-right tabular-nums">{r.named}</TableCell>
                    <TableCell className={cn("text-xs", inst && "text-right tabular-nums")}>
                      {inst ? formatCount(r.via_group) : r.groups?.length ? r.groups.join(", ") : "—"}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{r.revoked}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatCount(r.active_sessions)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {access.error ? <p className="mt-2 text-xs text-muted-foreground">{access.error}</p> : null}
            <p className="mt-2 text-xs text-muted-foreground">{inst ? t("overview.access.noteInstitution") : t("overview.access.notePlatform")}</p>
          </>
        )}
      </SectionCard>

      <p className="text-right text-xs text-muted-foreground">{t("overview.asOf", { when: formatWhen(data.generated_at) })}</p>
    </div>
  );
}
