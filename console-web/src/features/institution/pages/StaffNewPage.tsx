/**
 * /institution/staff/new — create an account in this institution.
 *
 * The institution is never a field: POST /institution-admin/users pins the
 * account (attribute and group) server-side. Creating needs manage-members AND
 * manage-membership (creation places the user in the institution's group).
 * No password is set here — this layer issues no credentials.
 */
import { useNavigate } from "react-router-dom";
import { Link } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { Field } from "@/components/common/Field";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { CapabilityGate } from "../components/InstitutionGate";
import { useInstitution } from "../context";
import { staffApi, staffKeys } from "../staff/api";
import { IdentifierHelp } from "../staff/components/IdentifierHelp";
import { createStaffSchema, identifierErrorField, type CreateStaffValues } from "../staff/state/staffForm";

function NewStaff() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const form = useForm<CreateStaffValues>({
    resolver: zodResolver(createStaffSchema),
    defaultValues: { username: "", email: "", firstName: "", lastName: "", national_id: "", phone: "" },
  });
  const { errors } = form.formState;
  const msg = (k?: string) => (k ? t(`instStaff.validation.${k}`, { defaultValue: k }) : undefined);

  const create = useMutation({
    mutationFn: (v: CreateStaffValues) => staffApi.create(inst.scope, v),
    onSuccess: (created, v) => {
      void qc.invalidateQueries({ queryKey: staffKeys.all(inst.scope) });
      notify.success(t("instStaff.created", { username: v.username }));
      navigate(inst.path(`/institution/staff/${encodeURIComponent(created.id)}`));
    },
    onError: (e) => {
      // 400 malformed / 409 already held by another account → next to the field.
      const field = identifierErrorField(e);
      if (field) form.setError(field, { message: errorMessage(e) });
      else notify.error(e);
    },
  });

  return (
    <div className="max-w-2xl">
      <Card>
        <CardHeader>
          <CardTitle>{t("instStaff.addUser")}</CardTitle>
          <CardDescription>{t("instStaff.newDescription", { institution: inst.ctx.institution, group: inst.perms.members?.group ?? inst.ctx.institution })}</CardDescription>
        </CardHeader>
        <form onSubmit={form.handleSubmit((v) => create.mutate(v))} noValidate>
          <CardContent className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("instStaff.username")} htmlFor="username" error={msg(errors.username?.message)}>
                <Input id="username" autoComplete="off" {...form.register("username")} />
              </Field>
              <Field label={t("instStaff.email")} htmlFor="email" error={msg(errors.email?.message)}>
                <Input id="email" type="email" autoComplete="off" {...form.register("email")} />
              </Field>
              <Field label={t("instStaff.firstName")} htmlFor="firstName" error={msg(errors.firstName?.message)}>
                <Input id="firstName" {...form.register("firstName")} />
              </Field>
              <Field label={t("instStaff.lastName")} htmlFor="lastName" error={msg(errors.lastName?.message)}>
                <Input id="lastName" {...form.register("lastName")} />
              </Field>
              <Field label={t("instStaff.nidOptional")} htmlFor="national_id" error={msg(errors.national_id?.message)}>
                <Input id="national_id" inputMode="numeric" placeholder={t("instStaff.nidPlaceholder")} autoComplete="off" className="font-mono" {...form.register("national_id")} />
              </Field>
              <Field label={t("instStaff.phoneOptional")} htmlFor="phone" error={msg(errors.phone?.message)}>
                <Input id="phone" type="tel" placeholder="+250788123456" autoComplete="off" className="font-mono" {...form.register("phone")} />
              </Field>
            </div>
            <IdentifierHelp />
            <div className="rounded-md border border-border bg-muted/40 px-3 py-2.5 text-xs leading-relaxed text-muted-foreground">
              <p className="font-medium text-foreground">{t("instStaff.noPasswordTitle")}</p>
              <p className="mt-1">{t("instStaff.noPasswordBody")}</p>
            </div>
          </CardContent>
          <CardFooter className="flex justify-between">
            <Button asChild variant="ghost">
              <Link to={inst.path("/institution/staff")}>{t("common.cancel")}</Link>
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? t("common.saving") : t("instStaff.createStaff")}
            </Button>
          </CardFooter>
        </form>
      </Card>
    </div>
  );
}

export function Component() {
  // Both: manage-members to write a user, manage-membership to place them in the group.
  return (
    <CapabilityGate capability="manage-members">
      <CapabilityGate capability="manage-membership">
        <NewStaff />
      </CapabilityGate>
    </CapabilityGate>
  );
}
