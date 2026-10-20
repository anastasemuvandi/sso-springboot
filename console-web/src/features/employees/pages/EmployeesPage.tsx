/**
 * /employees — the realm-wide employee directory (the IPPIS mirror, read).
 * These are not accounts: the rows with no account are the point of the screen.
 *
 * Filters live in the URL (see state/directory.ts). Scope is the wrapper's;
 * this page only renders what `GET /employees` returns. The institution-only
 * selection/provisioning flow lives in the institution area, not here.
 */
import { useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { ArrowDown, ArrowUp, Info } from "lucide-react";
import { useTranslation } from "react-i18next";

import { ApiError } from "@/api/errors";
import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { agenciesQuery } from "@/features/agencies/api";
import { institutionSlugs } from "@/features/agencies/state/institutions";
import { employeesQuery } from "../api";
import { EmployeeDetailDrawer } from "../components/EmployeeDetailDrawer";
import {
  accountView,
  dateOnly,
  displayName,
  EMPLOYMENT_STATUSES,
  parseDirectoryParams,
  sortPatch,
  toListQuery,
  toSearchParams,
  totalPages,
  waitingDays,
  type AccountState,
  type DirectoryState,
  type Employee,
  type SortKey,
} from "../state/directory";

const ALL = "__all";
const ACCOUNT_BASE = "/users";

export function Component() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const state = parseDirectoryParams(sp);
  const list = useQuery(employeesQuery(toListQuery(state)));
  // Only to populate the picker: a failure costs the picker, not the page.
  const agencies = useQuery(agenciesQuery());
  const institutions = institutionSlugs(agencies.data ?? []);
  const [searchText, setSearchText] = useState(state.search ?? "");
  const [openRow, setOpenRow] = useState<Employee | null>(null);

  const go = (patch: Parameters<typeof toSearchParams>[1]) => setSp(toSearchParams(state, patch));

  function onSearch(e: FormEvent) {
    e.preventDefault();
    go({ search: searchText.trim() || undefined, page: undefined });
  }

  if (list.isPending) return <Loading />;
  if (list.isError) {
    const err = list.error;
    if (err instanceof ApiError && err.status === 503) {
      return (
        <div className="space-y-6">
          <PageHeader title={t("nav.employees")} description={t("employees.mirror.title")} />
          <div className="rounded-xl border bg-card p-6 shadow-card" data-testid="mirror-unavailable">
            <p className="text-sm leading-relaxed text-muted-foreground">{t("employees.mirror.body")}</p>
            <p className="mt-3 rounded-md bg-muted/60 px-3 py-2 font-mono text-xs text-muted-foreground">{err.message}</p>
            <p className="mt-4 text-sm text-muted-foreground">{t("employees.mirror.fix")}</p>
          </div>
        </div>
      );
    }
    return (
      <div className="space-y-6">
        <PageHeader title={t("nav.employees")} description={t("employees.unreadable")} />
        <ErrorState error={err} onRetry={() => void list.refetch()} />
      </div>
    );
  }

  const { summary, items, total, scoped } = list.data;
  const pages = totalPages(total);
  const current = Math.min(state.page, pages);
  const tabs: Array<{ key: AccountState | "all"; label: string; count: number }> = [
    { key: "all", label: t("employees.tabs.all"), count: summary.total },
    { key: "linked", label: t("employees.tabs.linked"), count: summary.linked },
    { key: "unlinked", label: t("employees.tabs.unlinked"), count: summary.unlinked },
    { key: "ambiguous", label: t("employees.tabs.ambiguous"), count: summary.ambiguous },
  ];
  const colCount = scoped ? 7 : 8;

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("nav.employees")}
        description={
          <>
            {scoped ? t("employees.summaryScoped", { count: summary.total, institution: list.data.institution ?? "" }) : t("employees.summaryAll", { count: summary.total })}
            {summary.unlinked > 0 ? <span className="text-foreground"> · {t("employees.withoutAccount", { count: summary.unlinked })}</span> : null}
          </>
        }
      />

      {!scoped && summary.unmapped > 0 ? (
        <div className="rounded-xl border bg-card p-4 text-sm text-muted-foreground shadow-card" data-testid="unmapped-banner">
          <span className="font-medium text-foreground">{t("employees.unmappedBanner", { count: summary.unmapped })}</span> {t("employees.unmappedExplain")}{" "}
          <button type="button" className="underline hover:text-foreground" onClick={() => go({ unmapped: state.unmapped ? undefined : "1", page: undefined })}>
            {state.unmapped ? t("employees.showAll") : t("employees.showThem")}
          </button>{" "}
          {t("employees.unmappedFix")}
        </div>
      ) : null}

      <div className="flex flex-wrap items-center justify-between gap-2">
        <form className="flex max-w-xl flex-1 gap-2" onSubmit={onSearch} role="search">
          <Input value={searchText} onChange={(e) => setSearchText(e.target.value)} placeholder={t("employees.searchPlaceholder")} aria-label={t("employees.searchPlaceholder")} />
          {institutions.length ? (
            <Select value={state.institution ?? ALL} onValueChange={(v) => go({ institution: v === ALL ? undefined : v, page: undefined })}>
              <SelectTrigger className="w-44" aria-label={t("employees.institution")}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {!scoped ? <SelectItem value={ALL}>{t("employees.allInstitutions")}</SelectItem> : null}
                {institutions.map((slug) => (
                  <SelectItem key={slug} value={slug}>
                    {slug}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : null}
          <Button type="submit" variant="secondary">
            {t("common.search")}
          </Button>
        </form>
        <div className="flex flex-wrap items-center gap-1">
          {EMPLOYMENT_STATUSES.map((s) => (
            <Button key={s} size="sm" variant={s === state.status ? "secondary" : "ghost"} onClick={() => go({ status: s === state.status ? undefined : s, page: undefined })}>
              {t(`employees.status.${s}`)}
            </Button>
          ))}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-1 border-b pb-2" role="tablist">
        {tabs.map((tab) => {
          const active = (state.account ?? "all") === tab.key;
          return (
            <Button
              key={tab.key}
              role="tab"
              aria-selected={active}
              size="sm"
              variant={active ? "secondary" : "ghost"}
              onClick={() => go({ account: tab.key === "all" ? undefined : tab.key, page: undefined })}
            >
              {tab.label}
              <span className="ml-1 text-xs text-muted-foreground">{tab.count.toLocaleString()}</span>
            </Button>
          );
        })}
      </div>

      <div className="rounded-xl border bg-card shadow-card">
        <Table>
          <TableHeader>
            <TableRow>
              <SortHead state={state} k="employee_number" label={t("employees.col.number")} onSort={go} />
              <SortHead state={state} k="name" label={t("common.name")} onSort={go} />
              <SortHead state={state} k="position" label={t("employees.col.position")} onSort={go} />
              {!scoped ? <SortHead state={state} k="institution" label={t("employees.institution")} onSort={go} /> : null}
              <SortHead state={state} k="status" label={t("employees.col.employment")} onSort={go} />
              <TableHead>{t("employees.col.account")}</TableHead>
              <SortHead state={state} k="last_seen_at" label={t("employees.col.lastSeen")} onSort={go} />
              <TableHead className="w-10">
                <span className="sr-only">{t("common.details")}</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {items.map((e) => (
              <TableRow key={e.id} data-testid={`employee-row-${e.employee_number}`}>
                <TableCell className="font-mono text-xs">{e.employee_number}</TableCell>
                <TableCell>{displayName(e)}</TableCell>
                <TableCell className="text-sm">{e.position ?? "—"}</TableCell>
                {!scoped ? (
                  <TableCell className="text-xs">
                    {e.institution_slug ? (
                      <span className="font-mono">{e.institution_slug}</span>
                    ) : (
                      <span className="font-mono text-muted-foreground" title={t("employees.unmappedTitle")}>
                        {t("employees.unmappedCode", { code: e.institution_code ?? "—" })}
                      </span>
                    )}
                  </TableCell>
                ) : null}
                <TableCell>
                  <Badge variant={e.status === "active" ? "secondary" : "destructive"}>{t(`employees.status.${e.status}`, { defaultValue: e.status })}</Badge>
                </TableCell>
                <TableCell>
                  <AccountCell e={e} />
                </TableCell>
                <TableCell className="text-xs text-muted-foreground" title={e.last_sync_error ?? (e.last_sync_status ? `last sync: ${e.last_sync_status}` : undefined)}>
                  {dateOnly(e.last_seen_at)}
                </TableCell>
                <TableCell className="w-10 text-right">
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-7 w-7 text-muted-foreground hover:text-foreground"
                    aria-label={t("employees.detailsFor", { name: displayName(e) })}
                    onClick={() => setOpenRow(e)}
                  >
                    <Info />
                  </Button>
                </TableCell>
              </TableRow>
            ))}
            {items.length === 0 ? (
              <TableRow>
                <TableCell colSpan={colCount} className="py-8 text-center text-muted-foreground">
                  {summary.total === 0 ? t("employees.emptyMirror") : t("employees.noMatch")}
                </TableCell>
              </TableRow>
            ) : null}
          </TableBody>
        </Table>
      </div>

      {pages > 1 ? (
        <div className="flex items-center justify-end gap-2">
          <span className="text-xs text-muted-foreground">{t("employees.paging", { total: total.toLocaleString(), page: current, pages })}</span>
          <Button variant="outline" size="sm" disabled={current <= 1} onClick={() => go({ page: current - 1 })}>
            {t("common.previous")}
          </Button>
          <Button variant="outline" size="sm" disabled={current >= pages} onClick={() => go({ page: current + 1 })}>
            {t("common.next")}
          </Button>
        </div>
      ) : null}

      <EmployeeDetailDrawer employee={openRow} open={openRow !== null} onOpenChange={(o) => !o && setOpenRow(null)} accountBasePath={ACCOUNT_BASE} scoped={scoped} />
    </div>
  );
}

function SortHead({ state, k, label, onSort }: { state: DirectoryState; k: SortKey; label: string; onSort: (p: ReturnType<typeof sortPatch>) => void }) {
  const active = state.sort === k;
  return (
    <TableHead aria-sort={active ? (state.dir === "asc" ? "ascending" : "descending") : undefined}>
      <button type="button" className="inline-flex items-center gap-1 uppercase hover:text-foreground" onClick={() => onSort(sortPatch(state, k))}>
        {label}
        {active ? state.dir === "asc" ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" /> : null}
      </button>
    </TableHead>
  );
}

function AccountCell({ e }: { e: Employee }) {
  const { t } = useTranslation();
  const view = accountView(e);
  if (view === "ambiguous") {
    return (
      <div className="space-y-1">
        <Badge variant="destructive">{t("employees.account.ambiguous")}</Badge>
        <div className="text-xs text-muted-foreground">{t("employees.ambiguousRow")}</div>
      </div>
    );
  }
  if (view === "linked" && e.keycloak_user_id) {
    return (
      <Link to={`${ACCOUNT_BASE}/${encodeURIComponent(e.keycloak_user_id)}`} className="text-sm underline decoration-dotted hover:text-foreground">
        {t("employees.account.linked")}
      </Link>
    );
  }
  const days = waitingDays(e.first_seen_at);
  return (
    <div className="space-y-1">
      <Badge variant="outline">{t("employees.account.none")}</Badge>
      {days !== null ? <div className="text-xs text-muted-foreground">{t("employees.waiting", { count: days })}</div> : null}
    </div>
  );
}
