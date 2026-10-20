/**
 * /agencies/:id — one institution (Keycloak group): edit name/description,
 * realm and client roles mapped to the group, members, departments
 * (subgroups = the group's children), delete.
 *
 * Hiding or showing a control here is cosmetic; the wrapper enforces the
 * admin tier and refuses e.g. granting `mfa-exempt` through a role mapping.
 */
import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, X } from "lucide-react";
import { useTranslation } from "react-i18next";

import { ApiError } from "@/api/errors";
import { useConfirm } from "@/components/common/ConfirmDialog";
import { Field } from "@/components/common/Field";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { realmRolesQuery } from "@/features/roles/api";
import { isBuiltinRole } from "@/features/roles/state/roles";
import {
  addMember,
  agencyKeys,
  agencyMembersQuery,
  agencyQuery,
  assignRealmRoles,
  createSubgroup,
  deleteAgency,
  removeClientRoles,
  removeMember,
  removeRealmRoles,
  searchUsers,
  subgroupsQuery,
  updateAgency,
  type AgencyDetail,
} from "../api";
import { describedAs } from "../state/institutions";

const editSchema = z.object({ name: z.string().trim().min(1, "agencies.form.nameRequired"), description: z.string().trim() });
type EditValues = z.infer<typeof editSchema>;

export function Component() {
  const { id = "" } = useParams();
  const { t } = useTranslation();
  const agency = useQuery(agencyQuery(id));

  if (agency.isPending) return <Loading />;
  if (agency.isError) {
    return (
      <Card className="max-w-2xl">
        <CardHeader>
          <CardTitle>{t("agencies.notFound")}</CardTitle>
          <CardDescription>{agency.error instanceof ApiError ? agency.error.message : String(agency.error)}</CardDescription>
        </CardHeader>
        <CardFooter>
          <Button asChild variant="outline">
            <Link to="/agencies">{t("agencies.backToList")}</Link>
          </Button>
        </CardFooter>
      </Card>
    );
  }
  return <AgencyDetailView agency={agency.data} />;
}

function AgencyDetailView({ agency }: { agency: AgencyDetail }) {
  const { t } = useTranslation();
  const id = agency.id;
  const qc = useQueryClient();
  const navigate = useNavigate();
  const confirm = useConfirm();
  const refresh = () => void qc.invalidateQueries({ queryKey: agencyKeys.all });

  const form = useForm<EditValues>({ resolver: zodResolver(editSchema), defaultValues: { name: agency.name, description: describedAs(agency.attributes) } });
  useEffect(() => {
    form.reset({ name: agency.name, description: describedAs(agency.attributes) });
  }, [agency, form]);

  const save = useMutation({
    mutationFn: (v: EditValues) =>
      updateAgency(id, { name: v.name, attributes: { ...(agency.attributes ?? {}), description: v.description ? [v.description] : [] } }),
    onSuccess: () => {
      notify.success(t("common.saved"));
      refresh();
    },
    onError: (e) => notify.error(e),
  });

  const remove = useMutation({
    mutationFn: () => deleteAgency(id),
    onSuccess: () => {
      notify.success(t("agencies.deleted"));
      void qc.invalidateQueries({ queryKey: agencyKeys.all });
      navigate("/agencies");
    },
    onError: (e) => notify.error(e),
  });

  return (
    <div className="max-w-4xl space-y-6">
      <Link to="/agencies" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="h-4 w-4" /> {t("agencies.backToList")}
      </Link>

      <Card>
        <CardHeader>
          <CardTitle className="font-mono">{agency.name}</CardTitle>
          <CardDescription>
            {describedAs(agency.attributes) || t("agencies.noDescription")} · {t("agencies.path")} <code className="text-xs">{agency.path}</code>
          </CardDescription>
        </CardHeader>
        <form onSubmit={form.handleSubmit((v) => save.mutate(v))} noValidate>
          <CardContent className="space-y-4">
            <Field label={t("common.name")} htmlFor="name" error={form.formState.errors.name?.message ? t(form.formState.errors.name.message) : undefined}>
              <Input id="name" {...form.register("name")} />
            </Field>
            <Field label={t("common.description")} htmlFor="description">
              <Input id="description" {...form.register("description")} />
            </Field>
          </CardContent>
          <CardFooter className="justify-end">
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? t("common.saving") : t("common.save")}
            </Button>
          </CardFooter>
        </form>
      </Card>

      <RolesCard agency={agency} onChanged={refresh} />
      <MembersCard groupId={id} />
      <DepartmentsCard groupId={id} />

      <Card className="border-destructive/40">
        <CardHeader>
          <CardTitle className="text-destructive">{t("agencies.dangerZone")}</CardTitle>
          <CardDescription>{t("agencies.deleteBody")}</CardDescription>
        </CardHeader>
        <CardFooter>
          <Button
            variant="destructive"
            disabled={remove.isPending}
            onClick={async () => {
              if (await confirm({ title: t("agencies.deleteTitle", { name: agency.name }), description: t("agencies.deleteBody"), destructive: true, confirmLabel: t("common.delete") }))
                remove.mutate();
            }}
          >
            {t("agencies.deleteInstitution")}
          </Button>
        </CardFooter>
      </Card>
    </div>
  );
}

function RolesCard({ agency, onChanged }: { agency: AgencyDetail; onChanged: () => void }) {
  const { t } = useTranslation();
  const id = agency.id;
  const roles = useQuery(realmRolesQuery());
  const [pick, setPick] = useState("");
  const assigned = agency.realmRoles ?? [];
  const clientRoles = Object.entries(agency.clientRoles ?? {});
  // mfa-exempt is granted only through POST /realm/mfa/exemptions; never offer it here.
  const options = (roles.data ?? []).map((r) => r.name).filter((n) => !assigned.includes(n) && !isBuiltinRole(n) && n !== "mfa-exempt");

  const add = useMutation({
    mutationFn: (role: string) => assignRealmRoles(id, [role]),
    onSuccess: () => {
      setPick("");
      notify.success(t("agencies.roles.assigned"));
      onChanged();
    },
    onError: (e) => notify.error(e),
  });
  const drop = useMutation({
    mutationFn: (role: string) => removeRealmRoles(id, [role]),
    onSuccess: () => {
      notify.success(t("agencies.roles.removed"));
      onChanged();
    },
    onError: (e) => notify.error(e),
  });
  const dropClient = useMutation({
    mutationFn: ({ client, role }: { client: string; role: string }) => removeClientRoles(id, client, [role]),
    onSuccess: () => {
      notify.success(t("agencies.roles.removed"));
      onChanged();
    },
    onError: (e) => notify.error(e),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("agencies.roles.title")}</CardTitle>
        <CardDescription>{t("agencies.roles.description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap gap-2" data-testid="group-realm-roles">
          {assigned.length === 0 ? <span className="text-sm text-muted-foreground">{t("agencies.roles.none")}</span> : null}
          {assigned.map((r) => (
            <Badge key={r} variant="outline" className="gap-1">
              {r}
              <button type="button" aria-label={t("agencies.roles.removeRole", { role: r })} onClick={() => drop.mutate(r)} className="hover:text-destructive">
                <X className="h-3 w-3" />
              </button>
            </Badge>
          ))}
        </div>
        {clientRoles.length > 0 ? (
          <div className="space-y-1">
            <div className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{t("agencies.roles.clientRoles")}</div>
            {clientRoles.map(([client, names]) => (
              <div key={client} className="flex flex-wrap items-center gap-2 text-sm">
                <code className="text-xs">{client}</code>
                {names.map((r) => (
                  <Badge key={r} variant="secondary" className="gap-1">
                    {r}
                    <button type="button" aria-label={t("agencies.roles.removeRole", { role: r })} onClick={() => dropClient.mutate({ client, role: r })}>
                      <X className="h-3 w-3" />
                    </button>
                  </Badge>
                ))}
              </div>
            ))}
          </div>
        ) : null}
        <div className="flex gap-2">
          <Select value={pick} onValueChange={setPick} disabled={roles.isPending || options.length === 0}>
            <SelectTrigger className="max-w-xs" aria-label={t("agencies.roles.pick")}>
              <SelectValue placeholder={roles.isError ? t("agencies.roles.unavailable") : t("agencies.roles.pick")} />
            </SelectTrigger>
            <SelectContent>
              {options.map((n) => (
                <SelectItem key={n} value={n}>
                  {n}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Button size="sm" disabled={!pick || add.isPending} onClick={() => add.mutate(pick)}>
            {t("agencies.roles.assign")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}

function MembersCard({ groupId }: { groupId: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const members = useQuery(agencyMembersQuery(groupId));
  const [username, setUsername] = useState("");
  const invalidate = () => void qc.invalidateQueries({ queryKey: agencyKeys.all });

  const add = useMutation({
    mutationFn: async (name: string) => {
      const result = await searchUsers(name);
      // Prefer the exact username; fall back to the first hit.
      const hit = result.users.find((u) => u.username === name) ?? result.users[0];
      if (!hit) throw new Error(t("agencies.members.noMatch", { name }));
      await addMember(groupId, hit.id);
      return hit.username;
    },
    onSuccess: (who) => {
      setUsername("");
      notify.success(t("agencies.members.added", { username: who }));
      invalidate();
    },
    onError: (e) => notify.error(e),
  });
  const drop = useMutation({
    mutationFn: (userId: string) => removeMember(groupId, userId),
    onSuccess: () => {
      notify.success(t("agencies.members.removed"));
      invalidate();
    },
    onError: (e) => notify.error(e),
  });

  function onAdd(e: FormEvent) {
    e.preventDefault();
    const name = username.trim();
    if (name) add.mutate(name);
  }

  const rows = members.data ?? [];
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("agencies.members.title")}</CardTitle>
        <CardDescription>{members.data ? t("agencies.members.count", { count: rows.length }) : null}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <form onSubmit={onAdd} className="flex gap-2 border-b pb-3">
          <Input value={username} onChange={(e) => setUsername(e.target.value)} placeholder={t("agencies.members.addPlaceholder")} aria-label={t("agencies.members.addPlaceholder")} className="flex-1" />
          <Button type="submit" size="sm" disabled={add.isPending || !username.trim()}>
            {t("agencies.members.add")}
          </Button>
        </form>
        {members.isPending ? (
          <Loading />
        ) : members.isError ? (
          <ErrorState error={members.error} onRetry={() => void members.refetch()} />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("agencies.members.username")}</TableHead>
                <TableHead>{t("agencies.members.email")}</TableHead>
                <TableHead>{t("common.status")}</TableHead>
                <TableHead className="text-right">{t("common.actions")}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((m) => (
                <TableRow key={m.id}>
                  <TableCell>
                    <Link to={`/users/${encodeURIComponent(m.id)}`} className="font-mono text-xs hover:underline">
                      {m.username}
                    </Link>
                  </TableCell>
                  <TableCell className="text-sm">{m.email ?? "—"}</TableCell>
                  <TableCell>
                    <Badge variant={m.enabled ? "secondary" : "destructive"}>{m.enabled ? t("common.enabled") : t("common.disabled")}</Badge>
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={async () => {
                        if (await confirm({ title: t("agencies.members.removeTitle", { username: m.username }), destructive: true, confirmLabel: t("common.remove") }))
                          drop.mutate(m.id);
                      }}
                    >
                      {t("common.remove")}
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
              {rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={4} className="py-8 text-center text-muted-foreground">
                    {t("agencies.members.empty")}
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  );
}

function DepartmentsCard({ groupId }: { groupId: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const subgroups = useQuery(subgroupsQuery(groupId));
  const [name, setName] = useState("");
  const create = useMutation({
    mutationFn: (n: string) => createSubgroup(groupId, n),
    onSuccess: () => {
      setName("");
      notify.success(t("agencies.departments.created"));
      void qc.invalidateQueries({ queryKey: agencyKeys.subgroups(groupId) });
      void qc.invalidateQueries({ queryKey: agencyKeys.detail(groupId) });
    },
    onError: (e) => notify.error(e),
  });
  const rows = subgroups.data ?? [];
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("agencies.departments.title")}</CardTitle>
        <CardDescription>{t("agencies.departments.description")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (name.trim()) create.mutate(name.trim());
          }}
          className="flex gap-2"
        >
          <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={t("agencies.departments.placeholder")} aria-label={t("agencies.departments.placeholder")} className="flex-1" />
          <Button type="submit" size="sm" disabled={create.isPending || !name.trim()}>
            {t("agencies.departments.add")}
          </Button>
        </form>
        {subgroups.isPending ? (
          <Loading />
        ) : subgroups.isError ? (
          <ErrorState error={subgroups.error} onRetry={() => void subgroups.refetch()} />
        ) : rows.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("agencies.departments.empty")}</p>
        ) : (
          <ul className="divide-y rounded-md border">
            {rows.map((s) => (
              <li key={s.id} className="flex items-center justify-between px-3 py-2 text-sm">
                <span>{s.name}</span>
                <code className="text-xs text-muted-foreground">{s.path}</code>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
