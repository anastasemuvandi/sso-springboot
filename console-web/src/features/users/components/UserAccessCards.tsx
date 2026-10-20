/** Realm roles, client roles, institution (group) memberships and sessions. */
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";

import { useConfirm } from "@/components/common/ConfirmDialog";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { clientsListQuery } from "@/features/clients/api";
import { KEYCLOAK_INTERNAL_CLIENTS } from "@/features/clients/types";
import { formatWhen } from "@/lib/utils";
import {
  addToGroup,
  agenciesQuery,
  assignClientRole,
  assignRealmRole,
  clientRolesQuery,
  logoutAllSessions,
  logoutSession,
  realmRolesQuery,
  removeClientRole,
  removeFromGroup,
  removeRealmRole,
  userGroupsQuery,
  userSessionsQuery,
} from "../api";
import { HIDDEN_REALM_ROLES } from "../state/userForms";
import type { UserRoles } from "../types";
import { NativeSelect } from "./NativeSelect";
import { RoleChip } from "./RoleChip";
import { useUserMutation } from "./useUserMutation";

type RoleRef = { id?: string; name: string };

export function RealmRolesCard({ userId, roles }: { userId: string; roles: UserRoles }) {
  const { t } = useTranslation();
  const all = useQuery(realmRolesQuery());
  const [pick, setPick] = useState("");
  const assign = useUserMutation(userId, (r: RoleRef) => assignRealmRole(userId, r), (r) => t("users.roles.assigned", { role: r.name }));
  const remove = useUserMutation(userId, (r: RoleRef) => removeRealmRole(userId, r), (r) => t("users.roles.removed", { role: r.name }));
  const have = new Set(roles.realm_roles.map((r) => r.name));
  const assignable = (all.data ?? []).filter((r) => !HIDDEN_REALM_ROLES.has(r.name) && !have.has(r.name));

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("users.roles.realmTitle")}</CardTitle>
        <CardDescription>{t("users.roles.assignedCount", { count: roles.realm_roles.length })}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {roles.realm_roles.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("users.roles.noRealm")}</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {roles.realm_roles.map((r) => (
              <RoleChip
                key={r.id}
                label={r.name}
                title={t("users.roles.clickToRemove")}
                disabled={remove.isPending}
                // mfa-exempt is removed via the exemption card, never the generic role endpoint.
                onRemove={r.name === "mfa-exempt" ? undefined : () => remove.mutate({ id: r.id, name: r.name })}
              />
            ))}
          </div>
        )}
        {all.isError ? <ErrorState error={all.error} /> : null}
        {assignable.length > 0 ? (
          <form
            className="flex gap-2 border-t pt-3"
            onSubmit={(e) => {
              e.preventDefault();
              const r = assignable.find((x) => x.name === pick);
              if (r) assign.mutate({ id: r.id, name: r.name }, { onSuccess: () => setPick("") });
            }}
          >
            <NativeSelect value={pick} onChange={(e) => setPick(e.target.value)} aria-label={t("users.roles.pickRealm")}>
              <option value="">{t("users.roles.pickRealm")}</option>
              {assignable.map((r) => (
                <option key={r.id} value={r.name}>
                  {r.name}
                </option>
              ))}
            </NativeSelect>
            <Button type="submit" size="sm" disabled={!pick || assign.isPending}>
              {t("users.roles.assign")}
            </Button>
          </form>
        ) : null}
      </CardContent>
    </Card>
  );
}

export function ClientRolesCard({ userId, roles }: { userId: string; roles: UserRoles }) {
  const { t } = useTranslation();
  const clients = useQuery(clientsListQuery());
  const [clientUuid, setClientUuid] = useState("");
  const [roleKey, setRoleKey] = useState("");
  const clientRoles = useQuery({ ...clientRolesQuery(clientUuid), enabled: Boolean(clientUuid) });
  const assign = useUserMutation(
    userId,
    (v: { uuid: string; role: RoleRef }) => assignClientRole(userId, v.uuid, v.role),
    (v) => t("users.roles.assigned", { role: v.role.name }),
  );
  const remove = useUserMutation(
    userId,
    (v: { uuid: string; role: RoleRef }) => removeClientRole(userId, v.uuid, v.role),
    (v) => t("users.roles.removed", { role: v.role.name }),
  );
  const eligible = (clients.data ?? []).filter((c) => !KEYCLOAK_INTERNAL_CLIENTS.has(c.clientId));
  const uuidOf = (clientId: string) => clients.data?.find((c) => c.clientId === clientId)?.id;
  const selected = eligible.find((c) => c.id === clientUuid);
  const have = new Set((selected && roles.client_roles[selected.clientId]?.map((r) => r.name)) ?? []);
  const assignable = (clientRoles.data ?? []).filter((r) => !have.has(r.name));
  const entries = Object.entries(roles.client_roles).filter(([, list]) => list.length > 0);

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("users.roles.clientTitle")}</CardTitle>
        <CardDescription>{t("users.roles.clientDescription")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {entries.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("users.roles.noClient")}</p>
        ) : (
          <div className="space-y-3">
            {entries.map(([clientId, list]) => {
              const uuid = uuidOf(clientId);
              return (
                <div key={clientId}>
                  <code className="text-xs">{clientId}</code>
                  <div className="mt-1 flex flex-wrap gap-2">
                    {list.map((r) => (
                      <RoleChip
                        key={r.id ?? r.name}
                        label={r.name}
                        title={t("users.roles.clickToRemove")}
                        disabled={remove.isPending}
                        onRemove={uuid ? () => remove.mutate({ uuid, role: { id: r.id, name: r.name } }) : undefined}
                      />
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        )}
        <form
          className="flex flex-wrap gap-2 border-t pt-3"
          onSubmit={(e) => {
            e.preventDefault();
            const r = assignable.find((x) => x.id === roleKey);
            if (r && clientUuid) assign.mutate({ uuid: clientUuid, role: { id: r.id, name: r.name } }, { onSuccess: () => setRoleKey("") });
          }}
        >
          <NativeSelect
            className="min-w-[12rem] flex-1"
            value={clientUuid}
            onChange={(e) => {
              setClientUuid(e.target.value);
              setRoleKey("");
            }}
            aria-label={t("users.roles.pickClient")}
          >
            <option value="">{t("users.roles.pickClient")}</option>
            {eligible.map((c) => (
              <option key={c.id} value={c.id}>
                {c.clientId}
              </option>
            ))}
          </NativeSelect>
          <NativeSelect
            className="min-w-[12rem] flex-1"
            value={roleKey}
            onChange={(e) => setRoleKey(e.target.value)}
            disabled={!clientUuid || clientRoles.isPending}
            aria-label={t("users.roles.pickClientRole")}
          >
            <option value="">{clientUuid && assignable.length === 0 && !clientRoles.isPending ? t("users.roles.noneToAssign") : t("users.roles.pickClientRole")}</option>
            {assignable.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </NativeSelect>
          <Button type="submit" size="sm" disabled={!roleKey || assign.isPending}>
            {t("users.roles.assign")}
          </Button>
        </form>
        {clientRoles.isError ? <ErrorState error={clientRoles.error} /> : null}
      </CardContent>
    </Card>
  );
}

export function GroupsCard({ userId }: { userId: string }) {
  const { t } = useTranslation();
  const confirm = useConfirm();
  const groups = useQuery(userGroupsQuery(userId));
  const agencies = useQuery(agenciesQuery());
  const [pick, setPick] = useState("");
  const add = useUserMutation(userId, (g: { id: string; name: string }) => addToGroup(userId, g.id), (g) => t("users.groups.added", { name: g.name }));
  const remove = useUserMutation(userId, (g: { id: string; name: string }) => removeFromGroup(userId, g.id), (g) => t("users.groups.removed", { name: g.name }));
  const member = new Set((groups.data ?? []).map((g) => g.id));
  const addable = (agencies.data ?? []).filter((a) => !member.has(a.id)).sort((a, b) => a.name.localeCompare(b.name));

  async function onRemove(g: { id: string; name: string }) {
    const ok = await confirm({ title: t("users.groups.removeTitle", { name: g.name }), description: t("users.groups.removeBody"), confirmLabel: t("common.remove"), destructive: true });
    if (ok) remove.mutate(g);
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("users.groups.title")}</CardTitle>
        <CardDescription>{groups.data ? t("users.groups.count", { count: groups.data.length }) : t("users.groups.description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {groups.isPending ? (
          <Loading />
        ) : groups.isError ? (
          <ErrorState error={groups.error} onRetry={() => void groups.refetch()} />
        ) : groups.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("users.groups.none")}</p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {groups.data.map((g) => (
              <RoleChip key={g.id} label={g.path ?? g.name} title={t("users.groups.clickToRemove")} onRemove={() => void onRemove(g)} disabled={remove.isPending} />
            ))}
          </div>
        )}
        {addable.length > 0 ? (
          <form
            className="flex gap-2 border-t pt-3"
            onSubmit={(e) => {
              e.preventDefault();
              const g = addable.find((a) => a.id === pick);
              if (g) add.mutate({ id: g.id, name: g.name }, { onSuccess: () => setPick("") });
            }}
          >
            <NativeSelect value={pick} onChange={(e) => setPick(e.target.value)} aria-label={t("users.groups.pick")}>
              <option value="">{t("users.groups.pick")}</option>
              {addable.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </NativeSelect>
            <Button type="submit" size="sm" disabled={!pick || add.isPending}>
              {t("common.add")}
            </Button>
          </form>
        ) : null}
      </CardContent>
    </Card>
  );
}

export function SessionsCard({ userId }: { userId: string }) {
  const { t } = useTranslation();
  const confirm = useConfirm();
  const sessions = useQuery(userSessionsQuery(userId));
  const one = useUserMutation(userId, (sid: string) => logoutSession(userId, sid), t("users.sessions.loggedOutOne"));
  const all = useUserMutation(userId, () => logoutAllSessions(userId), t("users.sessions.loggedOutAll"));
  const list = sessions.data ?? [];

  async function onAll() {
    const ok = await confirm({ title: t("users.sessions.allTitle"), description: t("users.sessions.allBody"), confirmLabel: t("users.sessions.logoutAll"), destructive: true });
    if (ok) all.mutate(undefined);
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("users.sessions.title")}</CardTitle>
        <CardDescription>{sessions.data ? t("users.sessions.count", { count: list.length }) : null}</CardDescription>
      </CardHeader>
      <CardContent>
        {sessions.isPending ? (
          <Loading />
        ) : sessions.isError ? (
          <ErrorState error={sessions.error} onRetry={() => void sessions.refetch()} />
        ) : list.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("users.sessions.none")}</p>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>IP</TableHead>
                <TableHead>{t("users.sessions.started")}</TableHead>
                <TableHead>{t("users.sessions.lastAccess")}</TableHead>
                <TableHead>{t("nav.clients")}</TableHead>
                <TableHead className="text-right">{t("common.actions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((s) => (
                <TableRow key={s.id}>
                  <TableCell className="font-mono text-xs">{s.ipAddress ?? "—"}</TableCell>
                  <TableCell className="text-xs">{s.start ? formatWhen(s.start) : "—"}</TableCell>
                  <TableCell className="text-xs">{s.lastAccess ? formatWhen(s.lastAccess) : "—"}</TableCell>
                  <TableCell className="text-xs">{Object.values(s.clients ?? {}).join(", ") || "—"}</TableCell>
                  <TableCell className="text-right">
                    <Button size="sm" variant="ghost" onClick={() => one.mutate(s.id)} disabled={one.isPending}>
                      {t("users.sessions.logoutOne")}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
      {list.length > 0 ? (
        <CardFooter>
          <Button variant="destructive" size="sm" onClick={() => void onAll()} disabled={all.isPending}>
            {t("users.sessions.logoutAll")}
          </Button>
        </CardFooter>
      ) : null}
    </Card>
  );
}
