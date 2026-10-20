/**
 * /institution/employees — the institution's employees as IPPIS reports them
 * (one row per employment here), with or without an SSO account.
 *
 * Selection mode (`?select=1`) picks employees for provisioning ACROSS pages:
 * the selection is keyed by employee number and kept in sessionStorage per
 * institution, capped at the wrapper's batch limit. Continue goes to
 * /institution/employees/provision, which runs a dry run first.
 *
 * Hiding the selection for a tier without the provisioning capabilities is
 * cosmetic; the wrapper asserts all three on the provision call.
 */
import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ArrowRight, Info, ListChecks, Search, X } from "lucide-react";
import { useTranslation } from "react-i18next";

import { ApiError } from "@/api/errors";
import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { EmployeeDetailDrawer } from "@/features/employees/components/EmployeeDetailDrawer";
import { PAGE_SIZE, accountView, displayName, parseDirectoryParams, totalPages, type Employee } from "@/features/employees/state/directory";
import { cn } from "@/lib/utils";
import { useInstitution } from "../context";
import { institutionEmployeesQuery } from "../employees/api";
import * as sel from "../employees/state/employeeSelection";
import { useProvisionSelection } from "../employees/useProvisionSelection";

const TABS = [undefined, "linked", "unlinked", "ambiguous"] as const;

export function Component() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const navigate = useNavigate();
  const [sp, setSp] = useSearchParams();
  const dir = parseDirectoryParams(sp);
  const selecting = sp.get("select") === "1";
  const canProvision = sel.missingProvisionCapability((c) => inst.can(c)) === null;
  const [search, setSearch] = useState(dir.search ?? "");
  const [openRow, setOpenRow] = useState<Employee | null>(null);
  const { state: selection, commit } = useProvisionSelection(inst.ctx.institution);

  const query = {
    ...(dir.search ? { search: dir.search } : {}),
    ...(dir.status ? { status: dir.status } : {}),
    ...(dir.account ? { account: dir.account } : {}),
    sort: dir.sort,
    dir: dir.dir,
    limit: PAGE_SIZE,
    offset: (dir.page - 1) * PAGE_SIZE,
  };
  const list = useQuery({ ...institutionEmployeesQuery(inst.scope, query), placeholderData: keepPreviousData });

  const setParams = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(sp);
    for (const [k, v] of Object.entries(patch)) {
      if (v === undefined || v === "") next.delete(k);
      else next.set(k, v);
    }
    setSp(next);
  };

  const report = (r: sel.SelectionChange) => {
    commit(r.state);
    if (r.refusedForCap > 0) notify.info(t("instEmployees.list.capReached", { max: selection.max, count: r.refusedForCap }));
  };

  if (list.isError && list.error instanceof ApiError && list.error.status === 503) {
    return (
      <div className="space-y-6">
        <PageHeader title={t("instEmployees.list.title")} />
        <div className="rounded-xl border bg-card p-6 text-sm text-muted-foreground" role="alert">
          {t("instEmployees.list.mirrorOff")}
        </div>
      </div>
    );
  }

  const data = list.data;
  const items = (data?.items ?? []) as Employee[];
  const pages = totalPages(data?.total ?? 0);
  const header = sel.pageState(selection, items);

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("instEmployees.list.title")}
        description={t("instEmployees.list.description", { institution: inst.ctx.institution })}
        actions={
          canProvision && !selecting ? (
            <Button onClick={() => setParams({ select: "1" })} data-testid="start-selection">
              <ListChecks />
              {t("instEmployees.list.provision")}
            </Button>
          ) : null
        }
      />

      {selecting ? (
        <div className="sticky top-0 z-10 flex flex-wrap items-center gap-3 rounded-lg border bg-primary-soft/60 px-4 py-3" data-testid="selection-toolbar">
          <span className="text-sm font-medium" aria-live="polite" data-testid="selection-count">
            {t("instEmployees.list.selectedCount", { count: sel.count(selection), max: selection.max })}
          </span>
          <div className="ml-auto flex items-center gap-2">
            <Button size="sm" variant="ghost" onClick={() => commit(sel.clear(selection))} disabled={sel.count(selection) === 0}>
              {t("instEmployees.list.clear")}
            </Button>
            <Button size="sm" variant="outline" onClick={() => setParams({ select: undefined })}>
              <X />
              {t("common.cancel")}
            </Button>
            <Button size="sm" disabled={sel.count(selection) === 0} onClick={() => navigate(inst.path("/institution/employees/provision"))} data-testid="continue-provision">
              {t("instEmployees.list.continue")}
              <ArrowRight />
            </Button>
          </div>
          <p className="basis-full text-xs text-muted-foreground">{t("instEmployees.list.selectionHint")}</p>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-2">
        {TABS.map((tab) => {
          const active = dir.account === tab;
          const n = tab === undefined ? data?.summary.total : data?.summary[tab];
          return (
            <Button key={tab ?? "all"} size="sm" variant={active ? "default" : "outline"} onClick={() => setParams({ account: tab, page: undefined })}>
              {t(`instEmployees.list.tabs.${tab ?? "all"}`)}
              {n !== undefined ? <Badge variant="secondary" className="ml-1">{n}</Badge> : null}
            </Button>
          );
        })}
        <form
          className="ml-auto flex items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            setParams({ search: search.trim() || undefined, q: undefined, page: undefined });
          }}
        >
          <Input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={t("instEmployees.list.searchPlaceholder")} aria-label={t("common.search")} className="w-64" />
          <Button type="submit" size="sm" variant="outline">
            <Search />
            {t("common.search")}
          </Button>
        </form>
      </div>

      {list.isPending ? (
        <Loading />
      ) : list.isError ? (
        <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      ) : (
        <>
          <div className="rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  {selecting ? (
                    <TableHead className="w-10">
                      <Checkbox
                        aria-label={t("instEmployees.list.selectPage")}
                        data-testid="select-page"
                        disabled={header === "disabled"}
                        checked={header === "all" ? true : header === "some" ? "indeterminate" : false}
                        onCheckedChange={() => report(sel.togglePage(selection, items))}
                      />
                    </TableHead>
                  ) : null}
                  <TableHead>{t("instEmployees.list.number")}</TableHead>
                  <TableHead>{t("common.name")}</TableHead>
                  <TableHead>{t("instEmployees.list.email")}</TableHead>
                  <TableHead>{t("instEmployees.list.position")}</TableHead>
                  <TableHead>{t("instEmployees.list.employment")}</TableHead>
                  <TableHead>{t("instEmployees.list.account")}</TableHead>
                  <TableHead className="w-10">
                    <span className="sr-only">{t("common.details")}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={selecting ? 8 : 7} className="py-8 text-center text-sm text-muted-foreground">
                      {t("instEmployees.list.empty")}
                    </TableCell>
                  </TableRow>
                ) : (
                  items.map((e) => {
                    const block = sel.blockReason(e);
                    const acct = accountView(e);
                    return (
                      <TableRow key={e.id} data-testid={`employee-row-${e.employee_number}`}>
                        {selecting ? (
                          <TableCell>
                            <Checkbox
                              aria-label={t("instEmployees.list.selectOne", { name: displayName(e) })}
                              data-testid={`select-${e.employee_number}`}
                              disabled={block !== null}
                              title={block ? t(`instEmployees.list.block.${block}`) : undefined}
                              checked={sel.isSelected(selection, e.employee_number)}
                              onCheckedChange={() => report(sel.toggle(selection, e))}
                            />
                          </TableCell>
                        ) : null}
                        <TableCell className="font-mono text-xs">{e.employee_number}</TableCell>
                        <TableCell>{displayName(e)}</TableCell>
                        <TableCell className="text-xs">
                          {e.email ? <span className="font-mono">{e.email}</span> : <span className={cn(acct === "none" ? "text-destructive" : "text-muted-foreground")}>{t("instEmployees.list.noEmail")}</span>}
                        </TableCell>
                        <TableCell className="text-sm">{e.position ?? "—"}</TableCell>
                        <TableCell>
                          <Badge variant={e.status === "active" ? "secondary" : e.status === "terminated" ? "destructive" : "outline"}>{e.status}</Badge>
                        </TableCell>
                        <TableCell>
                          {acct === "ambiguous" ? (
                            <Badge variant="destructive">{t("instEmployees.list.acct.ambiguous")}</Badge>
                          ) : acct === "linked" ? (
                            <Badge variant="success">{t("instEmployees.list.acct.linked")}</Badge>
                          ) : (
                            <Badge variant="outline">{t("instEmployees.list.acct.none")}</Badge>
                          )}
                        </TableCell>
                        <TableCell>
                          <Button size="icon" variant="ghost" className="h-7 w-7" aria-label={t("instEmployees.list.details", { name: displayName(e) })} onClick={() => setOpenRow(e)}>
                            <Info />
                          </Button>
                        </TableCell>
                      </TableRow>
                    );
                  })
                )}
              </TableBody>
            </Table>
          </div>
          <div className="flex items-center justify-between text-sm text-muted-foreground">
            <span>{t("common.total", { count: data?.total ?? 0 })}</span>
            <div className="flex items-center gap-2">
              <Button size="sm" variant="outline" disabled={dir.page <= 1} onClick={() => setParams({ page: dir.page > 2 ? String(dir.page - 1) : undefined })} data-testid="page-prev">
                {t("common.previous")}
              </Button>
              <span data-testid="page-indicator">{t("common.page", { page: dir.page, pages })}</span>
              <Button size="sm" variant="outline" disabled={dir.page >= pages} onClick={() => setParams({ page: String(dir.page + 1) })} data-testid="page-next">
                {t("common.next")}
              </Button>
            </div>
          </div>
        </>
      )}
      {!selecting && canProvision && sel.count(selection) > 0 ? (
        <p className="text-sm text-muted-foreground">
          {t("instEmployees.list.pendingSelection", { count: sel.count(selection) })}{" "}
          <Link to={inst.path("/institution/employees/provision")} className="underline">
            {t("instEmployees.list.continue")}
          </Link>
        </p>
      ) : null}
      <EmployeeDetailDrawer employee={openRow} open={openRow !== null} onOpenChange={(o) => !o && setOpenRow(null)} accountBasePath="/institution/staff" scoped />
    </div>
  );
}
