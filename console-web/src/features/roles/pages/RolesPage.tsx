/**
 * /roles — realm roles: list, create, edit description, delete; plus
 * statistics. Built-in Keycloak roles cannot be deleted.
 */
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import type { ColumnDef } from "@tanstack/react-table";
import { useTranslation } from "react-i18next";

import { useConfirm } from "@/components/common/ConfirmDialog";
import { DataTable } from "@/components/common/DataTable";
import { Field } from "@/components/common/Field";
import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { formatCount } from "@/lib/utils";
import { createRealmRole, deleteRealmRole, realmRolesQuery, roleKeys, roleStatisticsQuery, updateRealmRole, type RealmRole } from "../api";
import { ROLE_NAME_PATTERN, roleKind, sortRoles } from "../state/roles";

const createSchema = z.object({
  name: z.string().trim().min(1, "roles.form.nameRequired").regex(ROLE_NAME_PATTERN, "roles.form.namePattern"),
  description: z.string().trim().optional(),
});
type CreateValues = z.infer<typeof createSchema>;

export function Component() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const confirm = useConfirm();
  const roles = useQuery(realmRolesQuery());
  const stats = useQuery(roleStatisticsQuery());
  const [filter, setFilter] = useState("");
  const [editing, setEditing] = useState<RealmRole | null>(null);
  const [editDescription, setEditDescription] = useState("");
  const invalidate = () => void qc.invalidateQueries({ queryKey: roleKeys.all });

  const form = useForm<CreateValues>({ resolver: zodResolver(createSchema), defaultValues: { name: "", description: "" } });
  const create = useMutation({
    mutationFn: (v: CreateValues) => createRealmRole(v.name, v.description),
    onSuccess: (_d, v) => {
      notify.success(t("roles.created", { name: v.name }));
      form.reset();
      invalidate();
    },
    onError: (e) => notify.error(e),
  });
  const update = useMutation({
    mutationFn: ({ name, description }: { name: string; description: string }) => updateRealmRole(name, description),
    onSuccess: () => {
      notify.success(t("common.saved"));
      setEditing(null);
      invalidate();
    },
    onError: (e) => notify.error(e),
  });
  const remove = useMutation({
    mutationFn: (name: string) => deleteRealmRole(name),
    onSuccess: () => {
      notify.success(t("roles.deleted"));
      invalidate();
    },
    onError: (e) => notify.error(e),
  });

  const columns = useMemo<ColumnDef<RealmRole>[]>(
    () => [
      {
        accessorKey: "name",
        header: t("common.name"),
        cell: ({ row }) => {
          const kind = roleKind(row.original.name);
          return (
            <span className="flex flex-wrap items-center gap-2">
              <code className="text-xs">{row.original.name}</code>
              {kind !== "custom" ? (
                <Badge variant={kind === "platform_admin" ? "default" : "outline"} className="text-[10px]">
                  {t(`roles.kind.${kind}`)}
                </Badge>
              ) : null}
            </span>
          );
        },
      },
      {
        accessorKey: "description",
        header: t("common.description"),
        cell: ({ row }) => <span className="text-sm text-muted-foreground">{row.original.description ?? "—"}</span>,
      },
      {
        id: "actions",
        header: () => <span className="sr-only">{t("common.actions")}</span>,
        enableSorting: false,
        cell: ({ row }) => {
          const r = row.original;
          if (roleKind(r.name) === "builtin") return null;
          return (
            <div className="flex justify-end gap-1">
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setEditing(r);
                  setEditDescription(r.description ?? "");
                }}
              >
                {t("common.edit")}
              </Button>
              <Button
                variant="ghost"
                size="sm"
                className="text-destructive hover:text-destructive"
                onClick={async () => {
                  if (await confirm({ title: t("roles.deleteTitle", { name: r.name }), description: t("roles.deleteBody"), destructive: true, confirmLabel: t("common.delete") }))
                    remove.mutate(r.name);
                }}
              >
                {t("common.delete")}
              </Button>
            </div>
          );
        },
      },
    ],
    [t, confirm, remove],
  );

  const data = useMemo(() => sortRoles(roles.data ?? []), [roles.data]);
  const err = form.formState.errors;

  return (
    <div className="space-y-6">
      <PageHeader title={t("nav.roles")} description={roles.data ? t("roles.description", { count: roles.data.length }) : undefined} />

      <div className="grid gap-3 sm:grid-cols-3" data-testid="role-stats">
        <Stat label={t("roles.stats.realm")} value={stats.data?.realm_roles_count ?? null} />
        <Stat label={t("roles.stats.client")} value={stats.data?.client_roles_count ?? null} />
        <Stat label={t("roles.stats.clients")} value={stats.data?.total_clients ?? null} />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader className="flex flex-row items-center justify-between gap-2 space-y-0">
            <CardTitle>{t("roles.all")}</CardTitle>
            <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t("roles.filter")} aria-label={t("roles.filter")} className="max-w-xs" />
          </CardHeader>
          <CardContent>
            {roles.isPending ? (
              <Loading />
            ) : roles.isError ? (
              <ErrorState error={roles.error} onRetry={() => void roles.refetch()} />
            ) : (
              <DataTable columns={columns} data={data} globalFilter={filter} getRowId={(r) => r.id ?? r.name} empty={t("roles.empty")} />
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>{t("roles.newTitle")}</CardTitle>
            <CardDescription>{t("roles.newHint")}</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={form.handleSubmit((v) => create.mutate(v))} className="space-y-4" noValidate>
              <Field label={t("common.name")} htmlFor="role-name" error={err.name?.message ? t(err.name.message) : undefined}>
                <Input id="role-name" placeholder="data_steward" {...form.register("name")} />
              </Field>
              <Field label={t("roles.form.descriptionOptional")} htmlFor="role-description">
                <Input id="role-description" {...form.register("description")} />
              </Field>
              <Button type="submit" className="w-full" disabled={create.isPending}>
                {t("common.create")}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>

      <Dialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{t("roles.editTitle", { name: editing?.name ?? "" })}</DialogTitle>
          </DialogHeader>
          <Field label={t("common.description")} htmlFor="edit-description">
            <Input id="edit-description" value={editDescription} onChange={(e) => setEditDescription(e.target.value)} />
          </Field>
          <DialogFooter>
            <Button variant="outline" onClick={() => setEditing(null)}>
              {t("common.cancel")}
            </Button>
            <Button disabled={update.isPending} onClick={() => editing && update.mutate({ name: editing.name, description: editDescription.trim() })}>
              {t("common.save")}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: number | null }) {
  return (
    <div className="rounded-xl border bg-card px-4 py-3 shadow-card">
      <div className="text-xs uppercase tracking-wider text-muted-foreground">{label}</div>
      <div className="mt-1 text-xl font-semibold tabular-nums">{formatCount(value)}</div>
    </div>
  );
}
