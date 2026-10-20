/**
 * "Access — who can log in" on /clients/:id.
 *
 * Three Keycloak policies decide it (spec §5.4): `sso-access-groups`,
 * `sso-access-users` (allow) and `sso-access-denied-users` (deny, checked
 * first). Groups and named users are edited here through
 * PUT /authorization/clients/{id}/access, which REPLACES the whole assignment —
 * so every edit re-reads the current one, changes one thing and writes it back.
 * Denials are per person (POST /authorization/users/{u}/client-access/{c}):
 * "Deny" puts a named user on the deny-list; "Restore" grants again, which
 * lifts the denial. Each sends `expected_state`, so a cell that changed under
 * the admin is refused (409 state_changed) rather than overwritten.
 */
import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { useConfirm } from "@/components/common/ConfirmDialog";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { agencyOptionsQuery, clientAccessQuery, clientKeys, clientsApi, userSearchQuery, type AgencyOption } from "../api";
import type { ClientAccess } from "../types";

function useDebounced<T>(value: T, ms = 250): T {
  const [v, setV] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setV(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return v;
}

/** Searchable user picker. The institution filter only narrows candidates; it grants nothing. */
function UserPicker({ agencies, assignedIds, onAssign, busy }: { agencies: AgencyOption[]; assignedIds: string[]; onAssign: (id: string) => void; busy: boolean }) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const [agencyId, setAgencyId] = useState("");
  const dq = useDebounced(q.trim());
  const search = useQuery(userSearchQuery(dq));
  const assigned = new Set(assignedIds);
  const results = search.data ?? [];
  const filtered = agencyId ? results.filter((u) => u.agencies.some((a) => a.id === agencyId)) : results;

  return (
    <div className="space-y-2">
      <div className="flex gap-2">
        <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("clients.access.searchUsers")} aria-label={t("clients.access.searchUsers")} className="flex-1" />
        <select
          value={agencyId}
          onChange={(e) => setAgencyId(e.target.value)}
          className="h-9 rounded-md border border-input bg-background px-2 py-1 text-sm shadow-sm"
          aria-label={t("clients.access.filterByInstitution")}
        >
          <option value="">{t("clients.scope.allInstitutions")}</option>
          {agencies.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
      </div>
      {search.isError ? <p className="text-sm text-destructive">{errorMessage(search.error)}</p> : null}
      <div className="max-h-64 divide-y overflow-y-auto rounded-md border">
        {search.isPending ? (
          <p className="px-3 py-2 text-sm text-muted-foreground">{t("clients.access.searching")}</p>
        ) : filtered.length === 0 ? (
          <p className="px-3 py-2 text-sm text-muted-foreground">
            {agencyId && results.length ? t("clients.access.noneInInstitution") : t("clients.access.noMatchingUsers")}
          </p>
        ) : (
          filtered.map((u) => {
            const meta = [u.name, u.email].filter(Boolean).join(" · ");
            const names = u.agencies.map((a) => a.name).join(", ");
            return (
              <div key={u.id} className="flex items-center justify-between gap-2 px-3 py-2">
                <div className="min-w-0">
                  <div className="truncate font-mono text-xs">{u.username}</div>
                  <div className="truncate text-xs text-muted-foreground">
                    {meta || "—"}
                    {names ? ` · ${names}` : ""}
                  </div>
                </div>
                {assigned.has(u.id) ? (
                  <span className="shrink-0 text-xs text-muted-foreground">{t("clients.access.assigned")}</span>
                ) : (
                  <Button size="sm" onClick={() => onAssign(u.id)} disabled={busy}>
                    {t("clients.access.assign")}
                  </Button>
                )}
              </div>
            );
          })
        )}
      </div>
      <p className="text-xs text-muted-foreground">{t("clients.access.pickerHint")}</p>
    </div>
  );
}

/** Multi-select of active institutions (groups). Ticks survive filtering. */
function GroupPicker({ agencies, assignedIds, onAssign, busy }: { agencies: AgencyOption[]; assignedIds: string[]; onAssign: (ids: string[]) => void; busy: boolean }) {
  const { t } = useTranslation();
  const [q, setQ] = useState("");
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const assigned = useMemo(() => new Set(assignedIds), [assignedIds]);
  const filtered = useMemo(() => {
    const n = q.trim().toLowerCase();
    return n ? agencies.filter((a) => a.name.toLowerCase().includes(n)) : agencies;
  }, [agencies, q]);
  return (
    <div className="space-y-2">
      <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder={t("clients.access.searchInstitutions")} aria-label={t("clients.access.searchInstitutions")} />
      <div className="max-h-48 divide-y overflow-y-auto rounded-md border">
        {filtered.length === 0 ? (
          <p className="px-3 py-2 text-sm text-muted-foreground">{t("clients.access.noActiveInstitutions")}</p>
        ) : (
          filtered.map((a) => (
            <label key={a.id} className="flex cursor-pointer items-center justify-between gap-2 px-3 py-2 hover:bg-muted/50">
              <span className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-primary"
                  disabled={assigned.has(a.id)}
                  checked={assigned.has(a.id) || selected.has(a.id)}
                  onChange={() =>
                    setSelected((prev) => {
                      const next = new Set(prev);
                      if (next.has(a.id)) next.delete(a.id);
                      else next.add(a.id);
                      return next;
                    })
                  }
                />
                {a.name}
              </span>
              {assigned.has(a.id) ? <span className="text-xs text-muted-foreground">{t("clients.access.assigned")}</span> : null}
            </label>
          ))
        )}
      </div>
      <Button
        size="sm"
        disabled={busy || selected.size === 0}
        onClick={() => {
          onAssign([...selected]);
          setSelected(new Set());
        }}
      >
        {t("clients.access.assignGroups", { count: selected.size })}
      </Button>
    </div>
  );
}

export function AccessCard({ clientId }: { clientId: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const q = useQuery(clientAccessQuery(clientId));
  const agencies = useQuery(agencyOptionsQuery());

  const write = useMutation({
    mutationFn: async (edit: (cur: ClientAccess) => { user_ids: string[]; group_ids: string[] }) => {
      // Replace-the-whole-set: always start from the current assignment.
      const current = await qc.fetchQuery({ ...clientAccessQuery(clientId), staleTime: 0 });
      return clientsApi.setAccess(clientId, edit(current));
    },
    onSuccess: (data) => {
      qc.setQueryData(clientKeys.access(clientId), data);
      void qc.invalidateQueries({ queryKey: clientKeys.access(clientId) });
      void qc.invalidateQueries({ queryKey: clientKeys.scope(clientId) });
      notify.success(t("common.saved"));
    },
    onError: (e) => notify.error(e),
  });

  const perUser = useMutation({
    mutationFn: ({ userId, action, expected }: { userId: string; action: "grant" | "revoke" | "restore"; expected: "granted" | "revoked" }) =>
      clientsApi.changeUserAccess(userId, clientId, action, expected),
    onSuccess: (r) => {
      notify.success(r.item.state === "revoked" ? t("clients.access.denied") : t("clients.access.restored"));
      void qc.invalidateQueries({ queryKey: clientKeys.access(clientId) });
    },
    onError: (e) => notify.error(e),
  });

  const ids = (cur: ClientAccess) => ({ user_ids: cur.users.map((u) => u.id), group_ids: cur.groups.map((g) => g.id) });
  const busy = write.isPending || perUser.isPending;
  const access = q.data;
  const agencyList = agencies.data ?? [];

  return (
    <Card id="access" className="scroll-mt-24">
      <CardHeader>
        <div className="flex items-center justify-between gap-2">
          <CardTitle>{t("clients.access.title")}</CardTitle>
          {access ? (
            <Badge variant={access.enforced ? "secondary" : "destructive"}>{access.enforced ? t("clients.access.restricted") : t("clients.access.noOneAssigned")}</Badge>
          ) : null}
        </div>
        <CardDescription>{t("clients.access.description", { clientId })}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-6">
        {q.isPending ? (
          <Loading />
        ) : q.isError ? (
          <ErrorState error={q.error} title={t("clients.access.loadFailed")} onRetry={() => void q.refetch()} />
        ) : access ? (
          <>
            {!access.enforced ? (
              <p className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2 text-sm text-muted-foreground">{t("clients.access.defaultDenyWarning")}</p>
            ) : null}

            <div className="space-y-3">
              <Label>{t("clients.access.assignedUsers")}</Label>
              <UserPicker
                agencies={agencyList}
                assignedIds={access.users.map((u) => u.id)}
                busy={busy}
                onAssign={(id) => write.mutate((cur) => ({ ...ids(cur), user_ids: [...new Set([...cur.users.map((u) => u.id), id])] }))}
              />
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("clients.access.username")}</TableHead>
                    <TableHead className="text-right">{t("common.actions")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {access.users.map((u) => (
                    <TableRow key={u.id}>
                      <TableCell>
                        <Link to={`/users/${u.id}`} className="font-mono text-xs hover:underline">
                          {u.username ?? u.id}
                        </Link>
                      </TableCell>
                      <TableCell className="text-right">
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={busy}
                          onClick={() => write.mutate((cur) => ({ ...ids(cur), user_ids: cur.users.map((x) => x.id).filter((x) => x !== u.id) }))}
                        >
                          {t("common.remove")}
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="text-destructive hover:text-destructive"
                          disabled={busy}
                          onClick={async () => {
                            if (
                              await confirm({
                                title: t("clients.access.denyTitle"),
                                description: t("clients.access.denyConfirm", { user: u.username ?? u.id, clientId }),
                                destructive: true,
                                confirmLabel: t("clients.access.deny"),
                              })
                            ) {
                              perUser.mutate({ userId: u.id, action: "revoke", expected: "granted" });
                            }
                          }}
                        >
                          {t("clients.access.deny")}
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                  {access.users.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={2} className="py-4 text-center text-sm text-muted-foreground">
                        {t("clients.access.noUsers")}
                      </TableCell>
                    </TableRow>
                  ) : null}
                </TableBody>
              </Table>
            </div>

            <div className="space-y-3">
              <Label>{t("clients.access.assignedGroups")}</Label>
              <GroupPicker
                agencies={agencyList.filter((a) => a.active)}
                assignedIds={access.groups.map((g) => g.id)}
                busy={busy}
                onAssign={(gids) => write.mutate((cur) => ({ ...ids(cur), group_ids: [...new Set([...cur.groups.map((g) => g.id), ...gids])] }))}
              />
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("clients.access.group")}</TableHead>
                    <TableHead className="text-right">{t("common.actions")}</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {access.groups.map((g) => (
                    <TableRow key={g.id}>
                      <TableCell>
                        <Link to={`/agencies/${g.id}`} className="font-mono text-xs hover:underline">
                          {g.name ?? g.id}
                        </Link>
                      </TableCell>
                      <TableCell className="text-right">
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={busy}
                          onClick={() => write.mutate((cur) => ({ ...ids(cur), group_ids: cur.groups.map((x) => x.id).filter((x) => x !== g.id) }))}
                        >
                          {t("common.remove")}
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                  {access.groups.length === 0 ? (
                    <TableRow>
                      <TableCell colSpan={2} className="py-4 text-center text-sm text-muted-foreground">
                        {t("clients.access.noGroups")}
                      </TableCell>
                    </TableRow>
                  ) : null}
                </TableBody>
              </Table>
            </div>

            <div className="space-y-3">
              <Label>{t("clients.access.deniedUsers")}</Label>
              <p className="text-xs text-muted-foreground">{t("clients.access.deniedExplain")}</p>
              {access.denied_users === undefined ? (
                <p className="text-sm text-muted-foreground">{t("clients.access.deniedNotReported")}</p>
              ) : (
                <Table>
                  <TableBody>
                    {access.denied_users.map((u) => (
                      <TableRow key={u.id}>
                        <TableCell>
                          <Link to={`/users/${u.id}`} className="font-mono text-xs hover:underline">
                            {u.username ?? u.id}
                          </Link>
                        </TableCell>
                        <TableCell className="text-right">
                          <Button variant="ghost" size="sm" disabled={busy} onClick={() => perUser.mutate({ userId: u.id, action: "restore", expected: "revoked" })}>
                            {t("clients.access.restore")}
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                    {access.denied_users.length === 0 ? (
                      <TableRow>
                        <TableCell className="py-4 text-center text-sm text-muted-foreground">{t("clients.access.noDenied")}</TableCell>
                      </TableRow>
                    ) : null}
                  </TableBody>
                </Table>
              )}
            </div>
          </>
        ) : null}
      </CardContent>
    </Card>
  );
}
