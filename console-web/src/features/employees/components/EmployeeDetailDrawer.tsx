/**
 * Everything about one employee the row has no width for, in a drawer.
 *
 * **This is one EMPLOYMENT row**: position/institution are the row's posting;
 * identity and account fields are the person's. The listing never carries a
 * national ID — it appears here only, from GET /employees/{n}, fetched the
 * first time the drawer opens for that employee (React Query caches it).
 */
import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { ApiError, errorMessage } from "@/api/errors";
import { Badge } from "@/components/ui/badge";
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { employeeQuery } from "../api";
import { accountView, dateOnly, displayName, waitingDays, type Employee, type EmployeeDetail } from "../state/directory";

type Employment = EmployeeDetail["employments"][number];
type Job = Employment["jobs"][number];

function Item({ label, children, hint, testId }: { label: string; children: ReactNode; hint?: string; testId?: string }) {
  return (
    <div className="space-y-1" data-testid={testId}>
      <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="text-sm">{children}</dd>
      {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

function Value({ children, mono = false }: { children?: string | null; mono?: boolean }) {
  if (!children) return <span className="text-muted-foreground">—</span>;
  return <span className={mono ? "font-mono text-xs" : undefined}>{children}</span>;
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-3">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-foreground">{title}</h3>
      <dl className="grid gap-4 sm:grid-cols-2">{children}</dl>
    </section>
  );
}

function JobRow({ job }: { job: Job }) {
  const { t } = useTranslation();
  return (
    <li className="rounded-md border p-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm font-medium">{job.positionName ?? "—"}</span>
        {!job.endDate ? <Badge variant="secondary">{t("employees.drawer.current")}</Badge> : <Badge variant="outline">{t("employees.drawer.ended", { date: dateOnly(job.endDate) })}</Badge>}
      </div>
      <p className="mt-1 text-xs text-muted-foreground">{[job.unitName, job.level && t("employees.drawer.level", { level: job.level })].filter(Boolean).join(" · ") || "—"}</p>
      <p className="mt-1 text-xs text-muted-foreground">
        {dateOnly(job.startDate)} → {job.endDate ? dateOnly(job.endDate) : t("employees.drawer.present")}
        {job.employeeGroup ? ` · ${job.employeeGroup.replace(/_/g, " ")}` : ""}
      </p>
      {job.isSecondaryEmployment || job.isActing || job.hasSubordinates ? (
        <div className="mt-2 flex flex-wrap gap-1">
          {job.isSecondaryEmployment ? <Badge variant="outline">{t("employees.drawer.secondary")}</Badge> : null}
          {job.isActing ? <Badge variant="outline">{t("employees.drawer.acting")}</Badge> : null}
          {job.hasSubordinates ? <Badge variant="outline">{t("employees.drawer.subordinates")}</Badge> : null}
        </div>
      ) : null}
    </li>
  );
}

function EmploymentBlock({ employment }: { employment: Employment }) {
  const { t } = useTranslation();
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-baseline gap-2">
        <h4 className="text-sm font-semibold">{employment.employerName ?? employment.employerId}</h4>
        {/* An employer the registry does not know belongs to no institution — say so. */}
        {employment.institutionSlug ? null : (
          <span className="font-mono text-xs text-muted-foreground" title={t("employees.unmappedTitle")}>
            {t("employees.unmappedCode", { code: employment.employerId })}
          </span>
        )}
      </div>
      {employment.jobs.length ? (
        <ul className="space-y-2">
          {employment.jobs.map((j) => (
            <JobRow key={j.id} job={j} />
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">{t("employees.drawer.noJobs")}</p>
      )}
    </div>
  );
}

export function EmployeeDetailDrawer({
  employee: e,
  open,
  onOpenChange,
  accountBasePath,
  scoped,
}: {
  employee: Employee | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  accountBasePath: string;
  scoped: boolean;
}) {
  const { t } = useTranslation();
  const detail = useQuery({ ...employeeQuery(e?.employee_number ?? ""), enabled: open && Boolean(e) });
  if (!e) return null;
  const days = waitingDays(e.first_seen_at);
  const view = accountView(e);
  const notVisible = detail.error instanceof ApiError && detail.error.status === 404;
  const d = detail.data;

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent data-testid="employee-drawer">
        <SheetHeader>
          <SheetTitle>{displayName(e)}</SheetTitle>
          <SheetDescription>{t("employees.drawer.intro")}</SheetDescription>
        </SheetHeader>

        <div className="space-y-6 pt-2">
          <Section title={t("employees.drawer.identity")}>
            <Item label={t("employees.col.number")}>
              <Value mono>{e.employee_number}</Value>
            </Item>
            <Item label={t("common.name")}>
              <Value>{[e.first_name, e.last_name].filter(Boolean).join(" ") || null}</Value>
            </Item>
            <Item label={t("employees.drawer.email")} hint={e.email ? undefined : e.keycloak_user_id ? t("employees.drawer.emailHintLinked") : t("employees.drawer.emailHintNew")}>
              <Value mono>{e.email}</Value>
            </Item>
            <Item label={t("employees.drawer.phone")} hint={e.phone ? undefined : t("employees.drawer.phoneHint")}>
              <Value mono>{e.phone}</Value>
            </Item>
            {d ? (
              <>
                <Item label={t("employees.drawer.nationalId")} hint={d.nationalId ? undefined : t("employees.drawer.nationalIdHint")} testId="employee-national-id">
                  <Value mono>{d.nationalId}</Value>
                </Item>
                <Item label={t("employees.drawer.rssb")}>
                  <Value mono>{d.rssbNumber}</Value>
                </Item>
                <Item label={t("employees.drawer.dob")}>
                  <Value>{d.dateOfBirth}</Value>
                </Item>
                <Item label={t("employees.drawer.genderMarital")}>
                  <Value>{[d.gender, d.maritalStatus].filter(Boolean).join(" · ") || null}</Value>
                </Item>
              </>
            ) : null}
          </Section>

          <section className="space-y-4">
            <div className="flex items-baseline justify-between gap-2">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-foreground">{t("employees.drawer.employment")}</h3>
              {(d ? d.scoped : scoped) ? <span className="text-xs text-muted-foreground">{t("employees.drawer.yourInstitutionOnly")}</span> : null}
            </div>
            {detail.isPending ? (
              <div className="space-y-2" aria-busy="true">
                <div className="h-16 animate-pulse rounded-md bg-muted" />
                <div className="h-16 animate-pulse rounded-md bg-muted" />
              </div>
            ) : detail.isError ? (
              // The row already showed the posting, so a failed read degrades rather than empties.
              <div className="space-y-2" role="alert">
                <p className="text-sm text-destructive">{notVisible ? t("employees.drawer.notVisible") : t("employees.drawer.loadFailed", { error: errorMessage(detail.error) })}</p>
                <p className="text-sm">
                  {t("employees.drawer.fromDirectory")} {e.position ?? "—"}
                  {e.institution_slug ? ` · ${e.institution_slug}` : ""}
                </p>
              </div>
            ) : detail.data.employments.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("employees.drawer.noEmployment")}</p>
            ) : (
              <div className="space-y-5">
                {detail.data.employments.map((em) => (
                  <EmploymentBlock key={em.employerId} employment={em} />
                ))}
              </div>
            )}
          </section>

          <Section title={t("employees.drawer.account")}>
            <Item label={t("employees.col.account")} hint={view === "ambiguous" ? t("employees.ambiguousHint") : undefined}>
              {view === "ambiguous" ? (
                <Badge variant="destructive">{t("employees.account.ambiguous")}</Badge>
              ) : view === "linked" && e.keycloak_user_id ? (
                <Link to={`${accountBasePath}/${encodeURIComponent(e.keycloak_user_id)}`} className="text-sm underline decoration-dotted hover:text-foreground">
                  {t("employees.drawer.openAccount")}
                </Link>
              ) : (
                <Badge variant="outline">{t("employees.account.none")}</Badge>
              )}
            </Item>
            <Item label={t("employees.drawer.waiting")}>
              {!e.keycloak_user_id && days !== null ? <span>{t("employees.drawer.waitingDays", { count: days })}</span> : <span className="text-muted-foreground">—</span>}
            </Item>
          </Section>

          <Section title={t("employees.drawer.sync")}>
            <Item label={t("employees.col.lastSeen")} hint={t("employees.drawer.lastSeenHint")}>
              <Value>{dateOnly(e.last_seen_at)}</Value>
            </Item>
            <Item label={t("employees.drawer.firstSeen")}>
              <Value>{dateOnly(e.first_seen_at)}</Value>
            </Item>
            <Item label={t("employees.drawer.lastSynced")} hint={t("employees.drawer.lastSyncedHint")}>
              <Value>{dateOnly(e.last_synced_at)}</Value>
            </Item>
            <Item label={t("employees.drawer.sourceUpdated")}>
              <Value>{dateOnly(e.source_updated_at)}</Value>
            </Item>
          </Section>

          {e.last_sync_status || e.last_sync_error ? (
            <div className="rounded-md border bg-muted/40 p-3">
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("employees.drawer.lastSync")}</p>
              <p className="mt-1 text-sm">{e.last_sync_status ?? "—"}</p>
              {e.last_sync_error ? <p className="mt-2 break-words font-mono text-xs text-destructive">{e.last_sync_error}</p> : null}
            </div>
          ) : null}
        </div>
      </SheetContent>
    </Sheet>
  );
}
