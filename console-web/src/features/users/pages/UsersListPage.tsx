/**
 * /users — the realm-wide user list, paged and filtered by the wrapper
 * (GET /users/: search, agency, client, role, enabled, page, page_size). The
 * top bar's search lands here as `?q=`. Users are never deleted: the row
 * action is a reversible enable/disable (disable is confirm-guarded).
 */
import { useState, type FormEvent } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { Plus } from "lucide-react";
import { useTranslation } from "react-i18next";

import { ApiError } from "@/api/errors";
import { useConfirm } from "@/components/common/ConfirmDialog";
import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { clientsListQuery } from "@/features/clients/api";
import { KEYCLOAK_INTERNAL_CLIENTS } from "@/features/clients/types";
import { agenciesQuery, realmRolesQuery, setUserEnabled, usersListQuery } from "../api";
import { NativeSelect } from "../components/NativeSelect";
import { useUserMutation } from "../components/useUserMutation";
import { fullName, HIDDEN_REALM_ROLES } from "../state/userForms";
import type { User, UserListParams } from "../types";

export const PAGE_SIZE_OPTIONS = [10, 20, 50, 100] as const;
const DEFAULT_PAGE_SIZE = 20;

/** URL search params → the wrapper's query. */
export function listParamsFrom(sp: URLSearchParams): UserListParams {
  const size = Number(sp.get("page_size"));
  const enabled = sp.get("enabled");
  const p: UserListParams = {
    page: Math.max(1, parseInt(sp.get("page") ?? "1", 10) || 1),
    page_size: (PAGE_SIZE_OPTIONS as readonly number[]).includes(size) ? size : DEFAULT_PAGE_SIZE,
  };
  const q = sp.get("q")?.trim();
  if (q) p.search = q;
  const agency = sp.get("agency");
  if (agency) p.agency_id = agency;
  const client = sp.get("client");
  if (client) p.client_id = client;
  const role = sp.get("role");
  if (role) p.role = role;
  if (enabled === "true" || enabled === "false") p.enabled = enabled === "true";
  return p;
}

export function UsersListPage() {
  const { t } = useTranslation();
  const [sp, setSp] = useSearchParams();
  const params = listParamsFrom(sp);
  const list = useQuery({ ...usersListQuery(params), placeholderData: keepPreviousData });
  const agencies = useQuery(agenciesQuery());
  const clients = useQuery(clientsListQuery());
  const roles = useQuery(realmRolesQuery());
  const [q, setQ] = useState(params.search ?? "");

  function setParam(key: string, value: string | null) {
    const next = new URLSearchParams(sp);
    if (value) next.set(key, value);
    else next.delete(key);
    if (key !== "page") next.delete("page");
    setSp(next);
  }

  function onSearch(e: FormEvent) {
    e.preventDefault();
    setParam("q", q.trim() || null);
  }

  const data = list.data;
  const page = data?.page ?? params.page ?? 1;
  const totalPages = Math.max(1, data?.totalPages ?? 1);

  return (
    <div className="space-y-6">
      <PageHeader
        title={t("nav.users")}
        description={data ? t("users.list.summary", { total: data.total.toLocaleString(), page, pages: totalPages }) : t("users.list.description")}
        actions={
          <Button asChild>
            <Link to="/users/new">
              <Plus /> {t("users.newUser")}
            </Link>
          </Button>
        }
      />

      <div className="flex flex-wrap items-end gap-2">
        <form onSubmit={onSearch} className="flex min-w-[16rem] max-w-md flex-1 gap-2" role="search">
          <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("users.list.searchPlaceholder")} aria-label={t("common.search")} />
          <Button type="submit" variant="secondary">
            {t("common.search")}
          </Button>
        </form>
        <NativeSelect className="w-44" value={params.agency_id ?? ""} onChange={(e) => setParam("agency", e.target.value || null)} aria-label={t("users.list.agency")}>
          <option value="">{t("users.list.allAgencies")}</option>
          {(agencies.data ?? []).map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </NativeSelect>
        <NativeSelect className="w-44" value={params.client_id ?? ""} onChange={(e) => setParam("client", e.target.value || null)} aria-label={t("users.list.client")}>
          <option value="">{t("users.list.allClients")}</option>
          {(clients.data ?? [])
            .filter((c) => !KEYCLOAK_INTERNAL_CLIENTS.has(c.clientId))
            .map((c) => (
              <option key={c.id} value={c.clientId}>
                {c.clientId}
              </option>
            ))}
        </NativeSelect>
        <NativeSelect className="w-40" value={params.role ?? ""} onChange={(e) => setParam("role", e.target.value || null)} aria-label={t("users.list.role")}>
          <option value="">{t("users.list.allRoles")}</option>
          {(roles.data ?? [])
            .filter((r) => !HIDDEN_REALM_ROLES.has(r.name) || r.name === "mfa-exempt")
            .map((r) => (
              <option key={r.id} value={r.name}>
                {r.name}
              </option>
            ))}
        </NativeSelect>
        <NativeSelect
          className="w-36"
          value={params.enabled === undefined ? "" : String(params.enabled)}
          onChange={(e) => setParam("enabled", e.target.value || null)}
          aria-label={t("common.status")}
        >
          <option value="">{t("users.list.anyStatus")}</option>
          <option value="true">{t("common.enabled")}</option>
          <option value="false">{t("common.disabled")}</option>
        </NativeSelect>
        <NativeSelect className="w-28" value={String(params.page_size)} onChange={(e) => setParam("page_size", e.target.value)} aria-label={t("users.list.pageSize")}>
          {PAGE_SIZE_OPTIONS.map((n) => (
            <option key={n} value={n}>
              {t("users.list.perPage", { count: n })}
            </option>
          ))}
        </NativeSelect>
      </div>

      {list.isPending ? (
        <Loading />
      ) : list.isError ? (
        <div className="space-y-2">
          <ErrorState error={list.error} title={t("users.list.loadFailed")} onRetry={() => void list.refetch()} />
          {list.error instanceof ApiError && list.error.status === 403 ? (
            <p className="text-sm text-muted-foreground">
              {t("users.list.forbiddenHint")}{" "}
              <Link to="/institution/staff" className="underline">
                {t("users.list.institutionList")}
              </Link>
            </p>
          ) : null}
        </div>
      ) : (
        <div className="rounded-xl border bg-card shadow-card">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("users.fields.username")}</TableHead>
                <TableHead>{t("common.name")}</TableHead>
                <TableHead>{t("users.fields.email")}</TableHead>
                <TableHead>{t("common.status")}</TableHead>
                <TableHead>{t("nav.institutions")}</TableHead>
                <TableHead className="text-right">{t("common.actions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.data.users.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="py-8 text-center text-muted-foreground">
                    {t("users.list.none")}
                  </TableCell>
                </TableRow>
              ) : (
                list.data.users.map((u) => <UserRow key={u.id} user={u} />)
              )}
            </TableBody>
          </Table>
        </div>
      )}

      {totalPages > 1 ? (
        <div className="flex items-center justify-end gap-2 text-sm">
          <span className="text-muted-foreground">{t("common.page", { page, pages: totalPages })}</span>
          <Button variant="outline" size="sm" disabled={page <= 1} onClick={() => setParam("page", String(page - 1))}>
            {t("common.previous")}
          </Button>
          <Button variant="outline" size="sm" disabled={page >= totalPages} onClick={() => setParam("page", String(page + 1))}>
            {t("common.next")}
          </Button>
        </div>
      ) : null}
    </div>
  );
}

function UserRow({ user }: { user: User }) {
  const { t } = useTranslation();
  const confirm = useConfirm();
  const toggle = useUserMutation(user.id, (enabled: boolean) => setUserEnabled(user.id, enabled), (enabled) =>
    enabled ? t("users.toast.enabled") : t("users.toast.disabled"),
  );
  async function onToggle() {
    if (user.enabled) {
      const ok = await confirm({
        title: t("users.disableTitle", { username: user.username }),
        description: t("users.disableBody"),
        confirmLabel: t("users.disable"),
        destructive: true,
      });
      if (!ok) return;
    }
    toggle.mutate(!user.enabled);
  }
  return (
    <TableRow>
      <TableCell className="font-mono text-xs">
        <Link to={`/users/${encodeURIComponent(user.id)}`} className="hover:underline">
          {user.username}
        </Link>
      </TableCell>
      <TableCell>{fullName(user) || "—"}</TableCell>
      <TableCell className="text-sm">{user.email ?? "—"}</TableCell>
      <TableCell>
        <Badge variant={user.enabled ? "secondary" : "destructive"}>{user.enabled ? t("common.enabled") : t("common.disabled")}</Badge>
      </TableCell>
      <TableCell className="text-xs">{(user.agencies ?? []).map((a) => a.name).join(", ") || "—"}</TableCell>
      <TableCell className="text-right">
        <div className="flex items-center justify-end gap-1">
          <Button asChild variant="ghost" size="sm">
            <Link to={`/users/${encodeURIComponent(user.id)}`}>{t("users.view")}</Link>
          </Button>
          <Button
            variant="ghost"
            size="sm"
            disabled={toggle.isPending}
            className={user.enabled ? "text-destructive hover:text-destructive" : "text-emerald-600 hover:text-emerald-600"}
            onClick={() => void onToggle()}
          >
            {user.enabled ? t("users.disable") : t("users.enable")}
          </Button>
        </div>
      </TableCell>
    </TableRow>
  );
}

export const Component = UsersListPage;
