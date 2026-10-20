/**
 * /clients — every application in the realm (Keycloak's own clients hidden),
 * with 2FA status (realm mode × client setting), join key, named users, scope
 * and assigned groups. Search, institution filter, sort and paging live in the
 * URL. The realm MFA read failing degrades the 2FA column to "n/a"; a client's
 * access read failing degrades its cells to "n/a".
 */
import { useMemo } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { PlusIcon } from "lucide-react";
import { useTranslation } from "react-i18next";

import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { useConfirm } from "@/components/common/ConfirmDialog";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { realmMfaQuery } from "@/features/settings/api";
import { agencyOptionsQuery, clientAccessQuery, clientKeys, clientsApi, clientsListQuery } from "../api";
import { JoinKeyCell, ScopeCell, TwoFactorCell } from "../components/cells";
import { mfaStatusFor } from "../state/mfaStatus";
import { KEYCLOAK_INTERNAL_CLIENTS, type Client } from "../types";

const PAGE_SIZES = [10, 20, 50];
const SORT_KEYS = ["clientId", "name", "enabled"] as const;
type SortKey = (typeof SORT_KEYS)[number];

function compare(a: Client, b: Client, key: SortKey): number {
  if (key === "name") return (a.name ?? "").localeCompare(b.name ?? "");
  if (key === "enabled") return Number(Boolean(a.enabled)) - Number(Boolean(b.enabled));
  return a.clientId.localeCompare(b.clientId);
}

export function ClientsListPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const [sp, setSp] = useSearchParams();

  const sortKey: SortKey = (SORT_KEYS as readonly string[]).includes(sp.get("sort") ?? "") ? (sp.get("sort") as SortKey) : "clientId";
  const dir = sp.get("dir") === "desc" ? "desc" : "asc";
  const pageSize = PAGE_SIZES.includes(Number(sp.get("page_size"))) ? Number(sp.get("page_size")) : 20;
  const requestedPage = Math.max(1, parseInt(sp.get("page") ?? "1", 10) || 1);
  const q = (sp.get("q") ?? "").trim();
  const agencyIds = sp.getAll("agency");
  const filtersActive = q !== "" || agencyIds.length > 0;

  const clientsQ = useQuery(clientsListQuery());
  const mfaQ = useQuery(realmMfaQuery());
  const agenciesQ = useQuery(agencyOptionsQuery());

  const all = useMemo(() => clientsQ.data ?? [], [clientsQ.data]);
  const visible = useMemo(() => all.filter((c) => !KEYCLOAK_INTERNAL_CLIENTS.has(c.clientId)), [all]);
  const needsJoin = visible.filter((c) => c.scope && c.scope.kind !== "platform" && (!c.join_column?.explicit || c.join_column?.malformed_value));
  const needle = q.toLowerCase();
  const nameMatched = needle ? visible.filter((c) => c.clientId.toLowerCase().includes(needle) || (c.name ?? "").toLowerCase().includes(needle)) : visible;

  // Access per client powers the Users/Assigned columns and the institution filter.
  const accessQs = useQueries({ queries: nameMatched.map((c) => ({ ...clientAccessQuery(c.clientId), retry: false })) });
  const accessOf = new Map(nameMatched.map((c, i) => [c.clientId, accessQs[i]]));

  const agencySet = new Set(agencyIds);
  const matched = agencySet.size === 0 ? nameMatched : nameMatched.filter((c) => (accessOf.get(c.clientId)?.data?.groups ?? []).some((g) => agencySet.has(g.id)));
  const sorted = [...matched].sort((a, b) => compare(a, b, sortKey));
  if (dir === "desc") sorted.reverse();
  const totalPages = Math.max(1, Math.ceil(sorted.length / pageSize));
  const page = Math.min(requestedPage, totalPages);
  const pageRows = sorted.slice((page - 1) * pageSize, page * pageSize);

  const update = (patch: Record<string, string | string[] | null>) => {
    const next = new URLSearchParams(sp);
    for (const [k, v] of Object.entries(patch)) {
      next.delete(k);
      if (Array.isArray(v)) v.forEach((x) => next.append(k, x));
      else if (v !== null && v !== "") next.set(k, v);
    }
    setSp(next, { replace: true });
  };
  const sortBy = (key: SortKey) => update({ sort: key, dir: sortKey === key && dir === "asc" ? "desc" : "asc", page: null });
  const arrow = (key: SortKey) => (sortKey === key ? (dir === "asc" ? " ▲" : " ▼") : "");

  const del = useMutation({
    mutationFn: (clientId: string) => clientsApi.remove(clientId),
    onSuccess: (_d, clientId) => {
      notify.success(t("clients.deleted", { clientId }));
      void qc.invalidateQueries({ queryKey: clientKeys.all });
    },
    onError: (e) => notify.error(e),
  });

  async function onDelete(clientId: string) {
    if (await confirm({ title: t("clients.deleteTitle"), description: t("clients.deleteConfirm", { clientId }), destructive: true, confirmLabel: t("common.delete") })) {
      del.mutate(clientId);
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("nav.clients")}
        description={
          clientsQ.data
            ? t("clients.list.summary", {
                count: matched.length,
                of: filtersActive ? t("clients.list.matchingOf", { total: visible.length }) : t("clients.list.registered"),
                hidden: all.length - visible.length,
                page,
                pages: totalPages,
              })
            : undefined
        }
        actions={
          <Button asChild>
            <Link to="/clients/new">
              <PlusIcon />
              {t("clients.list.new")}
            </Link>
          </Button>
        }
      />

      {needsJoin.length > 0 ? (
        <div className="flex flex-col gap-2 rounded-lg border border-destructive/40 bg-destructive/5 px-4 py-3 text-sm sm:flex-row sm:items-center sm:justify-between" data-testid="join-banner">
          <div>
            <p className="font-medium text-destructive">{t("clients.list.joinBanner", { count: needsJoin.length })}</p>
            <p className="mt-0.5 text-muted-foreground">{t("clients.list.joinBannerHint")}</p>
          </div>
          <Button asChild variant="outline" className="shrink-0">
            <Link to="/clients/join-keys">{t("dashboard.cards.reviewJoinKeys")}</Link>
          </Button>
        </div>
      ) : null}

      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex flex-1 flex-col gap-2">
          <Input
            defaultValue={q}
            placeholder={t("clients.list.search")}
            aria-label={t("clients.list.search")}
            className="max-w-sm"
            onChange={(e) => update({ q: e.target.value, page: null })}
          />
          {agenciesQ.data && agenciesQ.data.length > 0 ? (
            <div className="flex flex-wrap gap-1.5" aria-label={t("clients.list.filterInstitution")}>
              {agenciesQ.data.map((a) => {
                const on = agencySet.has(a.id);
                return (
                  <button
                    key={a.id}
                    type="button"
                    aria-pressed={on}
                    onClick={() => update({ agency: on ? agencyIds.filter((x) => x !== a.id) : [...agencyIds, a.id], page: null })}
                    className={`rounded-full border px-2.5 py-0.5 text-xs ${on ? "border-primary bg-primary/10 text-primary" : "text-muted-foreground hover:bg-accent"}`}
                  >
                    {a.name}
                  </button>
                );
              })}
              {agencyIds.length > 0 ? (
                <button type="button" className="text-xs underline" onClick={() => update({ agency: [], page: null })}>
                  {t("clients.list.clearFilter")}
                </button>
              ) : null}
            </div>
          ) : null}
        </div>
        <label className="flex items-center gap-2 text-sm text-muted-foreground">
          {t("clients.list.perPage")}
          <select value={pageSize} onChange={(e) => update({ page_size: e.target.value, page: null })} className="h-8 rounded-md border bg-background px-2 text-sm">
            {PAGE_SIZES.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
        </label>
      </div>

      {clientsQ.isPending ? (
        <Loading />
      ) : clientsQ.isError ? (
        <ErrorState error={clientsQ.error} onRetry={() => void clientsQ.refetch()} />
      ) : (
        <div className="rounded-xl border bg-card shadow-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>
                  <button type="button" onClick={() => sortBy("clientId")} className="uppercase hover:text-foreground">
                    {t("clients.list.clientId")}
                    {arrow("clientId")}
                  </button>
                </TableHead>
                <TableHead>
                  <button type="button" onClick={() => sortBy("name")} className="uppercase hover:text-foreground">
                    {t("common.name")}
                    {arrow("name")}
                  </button>
                </TableHead>
                <TableHead>
                  <button type="button" onClick={() => sortBy("enabled")} className="uppercase hover:text-foreground">
                    {t("common.enabled")}
                    {arrow("enabled")}
                  </button>
                </TableHead>
                <TableHead>2FA</TableHead>
                <TableHead>{t("clients.list.joinKey")}</TableHead>
                <TableHead>{t("nav.users")}</TableHead>
                <TableHead>{t("clients.list.scope")}</TableHead>
                <TableHead>{t("clients.list.assigned")}</TableHead>
                <TableHead className="text-right">{t("common.actions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {pageRows.map((c) => {
                const acc = accessOf.get(c.clientId);
                const access = acc?.data;
                const na = <span className="text-xs text-muted-foreground">{acc?.isPending ? "…" : "n/a"}</span>;
                return (
                  <TableRow key={c.id ?? c.clientId} data-testid={`client-row-${c.clientId}`}>
                    <TableCell className="font-mono text-xs">
                      <Link to={`/clients/${encodeURIComponent(c.clientId)}`} className="hover:underline">
                        {c.clientId}
                      </Link>
                      {c.protocol && c.protocol !== "openid-connect" ? (
                        <span className="ml-1.5 rounded bg-muted px-1 py-0.5 text-[10px] uppercase text-muted-foreground">{c.protocol}</span>
                      ) : null}
                    </TableCell>
                    <TableCell>{c.name ?? "—"}</TableCell>
                    <TableCell>{c.enabled ? "✓" : "—"}</TableCell>
                    <TableCell>
                      <TwoFactorCell clientId={c.clientId} status={mfaStatusFor(c, mfaQ.data)} />
                    </TableCell>
                    <TableCell>
                      <JoinKeyCell client={c} />
                    </TableCell>
                    <TableCell>
                      {access ? (
                        <Link to={`/clients/${encodeURIComponent(c.clientId)}#access`} className="tabular-nums hover:underline" title={t("clients.list.usersHint", { count: access.users.length })}>
                          {access.users.length}
                        </Link>
                      ) : (
                        na
                      )}
                    </TableCell>
                    <TableCell>
                      <ScopeCell scope={c.scope} />
                    </TableCell>
                    <TableCell>
                      {!access ? (
                        na
                      ) : access.groups.length === 0 ? (
                        <span className="text-muted-foreground">—</span>
                      ) : (
                        <div className="flex flex-wrap gap-1">
                          {access.groups.map((g) => (
                            <Link key={g.id} to={`/agencies/${g.id}`} className="rounded bg-muted px-1.5 py-0.5 font-mono text-xs hover:underline">
                              {g.name ?? g.id}
                            </Link>
                          ))}
                        </div>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      <div className="flex items-center justify-end gap-1">
                        <Button asChild variant="ghost" size="sm">
                          <Link to={`/clients/${encodeURIComponent(c.clientId)}`}>{t("common.edit")}</Link>
                        </Button>
                        <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" onClick={() => void onDelete(c.clientId)} disabled={del.isPending}>
                          {t("common.delete")}
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
              {pageRows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={9} className="py-8 text-center text-muted-foreground">
                    {filtersActive ? t("clients.list.noMatch") : t("clients.list.empty")}
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
        </div>
      )}

      <p className="text-xs leading-relaxed text-muted-foreground">
        {t("clients.list.joinNote")}{" "}
        <Link to="/clients/join-keys" className="underline">
          {t("clients.list.worklist")}
        </Link>
        .
      </p>
      <p className="text-xs leading-relaxed text-muted-foreground">{t("clients.list.mfaNote")}</p>

      {totalPages > 1 ? (
        <div className="flex justify-end gap-2">
          <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => update({ page: String(page - 1) })}>
            ← {t("common.previous")}
          </Button>
          <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => update({ page: String(page + 1) })}>
            {t("common.next")} →
          </Button>
        </div>
      ) : null}
    </div>
  );
}

export const Component = ClientsListPage;
