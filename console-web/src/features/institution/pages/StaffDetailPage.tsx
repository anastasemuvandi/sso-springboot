/**
 * /institution/staff/:id — edit a member, deactivate/reactivate.
 *
 * A cross-institution id is refused by the wrapper with 403 (the per-object
 * membership check); this page renders that refusal rather than running its
 * own ownership test. Read-only without manage-members (cosmetic).
 */
import { useEffect } from "react";
import { Link, useParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { Field } from "@/components/common/Field";
import { PageHeader } from "@/components/common/PageHeader";
import { Loading } from "@/components/common/QueryState";
import { notify } from "@/components/common/toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { CapabilityGate } from "../components/InstitutionGate";
import { useInstitution } from "../context";
import type { Member } from "../types";
import { memberQuery, staffApi, staffKeys } from "../staff/api";
import { IdentifierHelp } from "../staff/components/IdentifierHelp";
import { MemberReadError, useDeactivate } from "../staff/components/StaffShared";
import { fullName, identifierErrorField, updateDefaults, updateStaffSchema, type UpdateStaffValues } from "../staff/state/staffForm";

function DetailsForm({ member, canManage }: { member: Member; canManage: boolean }) {
  const { t } = useTranslation();
  const inst = useInstitution();
  const qc = useQueryClient();
  const form = useForm<UpdateStaffValues>({ resolver: zodResolver(updateStaffSchema), defaultValues: updateDefaults(member) });
  const { errors } = form.formState;
  const msg = (k?: string) => (k ? t(`instStaff.validation.${k}`, { defaultValue: k }) : undefined);
  const { reset } = form;
  useEffect(() => {
    reset(updateDefaults(member));
  }, [member, reset]);

  const save = useMutation({
    mutationFn: (v: UpdateStaffValues) => staffApi.update(inst.scope, member.id, v),
    onSuccess: () => {
      notify.success(t("instStaff.updated"));
      void qc.invalidateQueries({ queryKey: staffKeys.all(inst.scope) });
    },
    onError: (e) => {
      const field = identifierErrorField(e);
      if (field && field !== "username") form.setError(field, { message: errorMessage(e) });
      else notify.error(e);
    },
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("common.details")}</CardTitle>
        <CardDescription>{canManage ? t("instStaff.usernameFixed") : t("instStaff.readOnly")}</CardDescription>
      </CardHeader>
      <form onSubmit={form.handleSubmit((v) => save.mutate(v))} noValidate>
        <fieldset disabled={!canManage} className="contents">
          <CardContent className="space-y-4">
            <Field label={t("instStaff.username")} htmlFor="username">
              <Input id="username" value={member.username} disabled readOnly className="font-mono" />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("instStaff.firstName")} htmlFor="firstName" error={msg(errors.firstName?.message)}>
                <Input id="firstName" {...form.register("firstName")} />
              </Field>
              <Field label={t("instStaff.lastName")} htmlFor="lastName" error={msg(errors.lastName?.message)}>
                <Input id="lastName" {...form.register("lastName")} />
              </Field>
            </div>
            <Field label={t("instStaff.email")} htmlFor="email" error={msg(errors.email?.message)}>
              <Input id="email" type="email" {...form.register("email")} />
            </Field>
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label={t("instStaff.nid")} htmlFor="national_id" error={msg(errors.national_id?.message)}>
                <Input id="national_id" inputMode="numeric" placeholder={t("instStaff.nidPlaceholder")} className="font-mono" {...form.register("national_id")} />
              </Field>
              <Field label={t("instStaff.phone")} htmlFor="phone" error={msg(errors.phone?.message)}>
                <Input id="phone" type="tel" placeholder="+250788123456" className="font-mono" {...form.register("phone")} />
              </Field>
            </div>
            <IdentifierHelp />
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" className="h-4 w-4 rounded border-input accent-primary" {...form.register("emailVerified")} />
              {t("instStaff.emailVerified")}
            </label>
          </CardContent>
          {canManage ? (
            <CardFooter className="justify-end">
              <Button type="submit" disabled={save.isPending}>
                {save.isPending ? t("common.saving") : t("instStaff.saveChanges")}
              </Button>
            </CardFooter>
          ) : null}
        </fieldset>
      </form>
    </Card>
  );
}

function StaffDetail() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const { id = "" } = useParams();
  const qc = useQueryClient();
  const q = useQuery(memberQuery(inst.scope, id));
  const deactivate = useDeactivate();
  const reactivate = useMutation({
    mutationFn: () => staffApi.reactivate(inst.scope, id),
    onSuccess: () => {
      notify.success(t("instStaff.reactivated"));
      void qc.invalidateQueries({ queryKey: staffKeys.all(inst.scope) });
    },
    onError: (e) => notify.error(e),
  });

  if (q.isPending) return <Loading />;
  if (q.isError) return <MemberReadError error={q.error} onRetry={() => void q.refetch()} />;
  const member = q.data;
  const canManage = inst.can("manage-members");

  return (
    <div className="space-y-6">
      <PageHeader
        title={member.username}
        description={`${fullName(member) || t("instStaff.noName")} · ${inst.ctx.institution}`}
        actions={
          <>
            {inst.can("map-roles") ? (
              <Button asChild variant="outline">
                <Link to={inst.path(`/institution/staff/${encodeURIComponent(id)}/roles`)}>{t("instStaff.roles")}</Link>
              </Button>
            ) : null}
            <Button asChild variant="ghost">
              <Link to={inst.path("/institution/staff")}>{t("common.back")}</Link>
            </Button>
          </>
        }
      />
      <div className="flex flex-wrap items-center gap-3">
        <Badge variant={member.enabled ? "secondary" : "destructive"}>{member.enabled ? t("instStaff.active") : t("instStaff.deactivatedBadge")}</Badge>
        <Badge variant="outline" className={member.emailVerified ? undefined : "opacity-60"}>
          {member.emailVerified ? t("instStaff.emailVerifiedBadge") : t("instStaff.emailUnverifiedBadge")}
        </Badge>
        {member.createdTimestamp ? (
          <span className="text-xs text-muted-foreground">{t("instStaff.createdOn", { date: new Date(member.createdTimestamp).toISOString().slice(0, 10) })}</span>
        ) : null}
      </div>
      <div className="max-w-2xl space-y-6">
        <DetailsForm member={member} canManage={canManage} />
        {canManage ? (
          <Card className="border-destructive/30">
            <CardHeader>
              <CardTitle>{member.enabled ? t("instStaff.deactivate") : t("instStaff.reactivate")}</CardTitle>
              <CardDescription>{member.enabled ? t("instStaff.deactivateExplain") : t("instStaff.reactivateExplain")}</CardDescription>
            </CardHeader>
            <CardFooter>
              {member.enabled ? (
                <Button variant="outline" className="text-destructive hover:text-destructive" disabled={deactivate.pending} onClick={() => void deactivate.run(member)}>
                  {t("instStaff.deactivateAccount")}
                </Button>
              ) : (
                <Button variant="outline" disabled={reactivate.isPending} onClick={() => reactivate.mutate()}>
                  {t("instStaff.reactivateAccount")}
                </Button>
              )}
            </CardFooter>
          </Card>
        ) : null}
      </div>
    </div>
  );
}

export function Component() {
  return (
    <CapabilityGate capability="view-members">
      <StaffDetail />
    </CapabilityGate>
  );
}
