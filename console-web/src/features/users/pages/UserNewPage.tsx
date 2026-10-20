/**
 * /users/new — create an account, set its initial password, put it in its
 * institution (mandatory) and optionally grant clients that institution holds.
 * See state/createUserFlow.ts for the steps.
 */
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Link, useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";

import { Field } from "@/components/common/Field";
import { PageHeader } from "@/components/common/PageHeader";
import { ErrorState, Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  addAgencyMember,
  agenciesQuery,
  agencyClientsQuery,
  changeClientAccess,
  createUser,
  resetPassword,
  userKeys,
} from "../api";
import { api, unwrapAs } from "@/api/client";
import { NativeSelect } from "../components/NativeSelect";
import { runCreateUser } from "../state/createUserFlow";
import { createUserSchema, type CreateUserValues } from "../state/userForms";
import type { UserClientAccess } from "../types";

export function UserNewPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const agencies = useQuery(agenciesQuery());
  const form = useForm<CreateUserValues>({
    resolver: zodResolver(createUserSchema),
    defaultValues: { username: "", email: "", firstName: "", lastName: "", employeeNumber: "", password: "", temporary: true, agencyId: "", clientIds: [] },
  });
  const agencyId = form.watch("agencyId");
  const clientIds = form.watch("clientIds");
  const byAgency = useQuery({ ...agencyClientsQuery(), enabled: Boolean(agencyId) });
  const offered = agencyId ? (byAgency.data?.[agencyId] ?? []) : [];

  const create = useMutation({
    mutationFn: (v: CreateUserValues) =>
      runCreateUser(
        // Only clients assigned to the chosen institution are honoured.
        { ...v, clientIds: v.clientIds.filter((c) => offered.some((o) => o.clientId === c)) },
        {
          createUser,
          resetPassword,
          addAgencyMember,
          readAccess: (id) => unwrapAs<UserClientAccess>(api.GET("/authorization/users/{user_id}/client-access", { params: { path: { user_id: id } } })),
          changeAccess: changeClientAccess,
        },
      ),
    onSuccess: (out) => {
      void qc.invalidateQueries({ queryKey: userKeys.all });
      if (out.ok) notify.success(t("users.create.created"));
      else notify.error(t(`users.create.partial.${out.step}`, { detail: out.message }));
      navigate(`/users/${encodeURIComponent(out.userId)}`);
    },
    onError: (e) => notify.error(e),
  });

  const err = (k: keyof CreateUserValues) => {
    const m = form.formState.errors[k]?.message;
    return m ? t(m) : undefined;
  };

  return (
    <div className="max-w-2xl space-y-6">
      <PageHeader title={t("users.newUser")} />
      <Card>
        <CardHeader>
          <CardTitle>{t("users.newUser")}</CardTitle>
          <CardDescription>{t("users.create.description")}</CardDescription>
        </CardHeader>
        <form onSubmit={form.handleSubmit((v) => create.mutate(v))} noValidate>
          <CardContent className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("users.fields.username")} htmlFor="username" error={err("username")}>
                <Input id="username" autoComplete="off" {...form.register("username")} />
              </Field>
              <Field label={t("users.fields.email")} htmlFor="email" error={err("email")}>
                <Input id="email" type="email" {...form.register("email")} />
              </Field>
              <Field label={t("users.fields.firstName")} htmlFor="firstName" error={err("firstName")}>
                <Input id="firstName" {...form.register("firstName")} />
              </Field>
              <Field label={t("users.fields.lastName")} htmlFor="lastName" error={err("lastName")}>
                <Input id="lastName" {...form.register("lastName")} />
              </Field>
            </div>
            <Field label={t("users.fields.employeeNumber")} htmlFor="employeeNumber" help={t("users.create.employeeNumberHelp")}>
              <Input id="employeeNumber" placeholder={t("users.create.employeeNumberPlaceholder")} {...form.register("employeeNumber")} />
            </Field>
            <Field label={t("users.create.initialPassword")} htmlFor="password" help={t("users.password.policy")} error={err("password")}>
              <Input id="password" type="password" autoComplete="new-password" {...form.register("password")} />
            </Field>
            <div className="flex items-center gap-2">
              <Checkbox id="temporary" checked={form.watch("temporary")} onCheckedChange={(v) => form.setValue("temporary", v === true)} />
              <Label htmlFor="temporary" className="font-normal">
                {t("users.password.temporary")}
              </Label>
            </div>

            <div className="space-y-4 border-t pt-4">
              <Field label={t("nav.institutions")} htmlFor="agencyId" help={t("users.create.agencyHelp")} error={err("agencyId")}>
                {agencies.isError ? (
                  <ErrorState error={agencies.error} />
                ) : (
                  <NativeSelect
                    id="agencyId"
                    value={agencyId}
                    onChange={(e) => {
                      form.setValue("agencyId", e.target.value, { shouldValidate: true });
                      form.setValue("clientIds", []);
                    }}
                  >
                    <option value="">{t("users.create.pickAgency")}</option>
                    {[...(agencies.data ?? [])]
                      .sort((a, b) => a.name.localeCompare(b.name))
                      .map((a) => (
                        <option key={a.id} value={a.id}>
                          {a.name}
                        </option>
                      ))}
                  </NativeSelect>
                )}
              </Field>

              <div className="space-y-2">
                <Label>{t("users.create.clientAccess")}</Label>
                <p className="text-xs text-muted-foreground">{t("users.create.clientAccessHelp")}</p>
                {!agencyId ? (
                  <p className="rounded-md border border-dashed p-3 text-sm text-muted-foreground">{t("users.create.pickAgencyFirst")}</p>
                ) : byAgency.isPending ? (
                  <Loading />
                ) : byAgency.isError ? (
                  <ErrorState error={byAgency.error} />
                ) : offered.length === 0 ? (
                  <p className="rounded-md border border-dashed p-3 text-sm text-muted-foreground">{t("users.create.noClients")}</p>
                ) : (
                  <div className="max-h-48 space-y-1 overflow-y-auto rounded-md border p-2">
                    {offered.map((c) => {
                      const checked = clientIds.includes(c.clientId);
                      return (
                        <label key={c.clientId} className="flex items-center gap-2 rounded px-1 py-1 text-sm hover:bg-accent">
                          <Checkbox
                            checked={checked}
                            onCheckedChange={(v) =>
                              form.setValue("clientIds", v === true ? [...clientIds, c.clientId] : clientIds.filter((x) => x !== c.clientId))
                            }
                          />
                          <span className="font-mono text-xs">{c.clientId}</span>
                          {c.name ? <span className="text-xs text-muted-foreground">{c.name}</span> : null}
                        </label>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>
          </CardContent>
          <CardFooter className="flex justify-between">
            <Button asChild variant="ghost">
              <Link to="/users">{t("common.cancel")}</Link>
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? t("common.saving") : t("users.create.submit")}
            </Button>
          </CardFooter>
        </form>
      </Card>
    </div>
  );
}

export const Component = UserNewPage;
