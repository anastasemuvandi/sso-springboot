/**
 * /institution/apps/new — integrate an application for THIS institution.
 *
 * The admin gives the app part only; the slug prefix is shown and fixed and
 * the wrapper composes `<slug>-<app>` itself. There is no institution or
 * cross-cutting field — by contract, not by omission.
 */
import { useNavigate, Link } from "react-router-dom";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";

import { Field } from "@/components/common/Field";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { useInstitution } from "../context";
import { CapabilityGate } from "../components/InstitutionGate";
import { instAppKeys, instAppsApi } from "../apps/api";
import { buildCreateBody, composeClientId, type AppCreateBody, type AppCreateValues } from "../apps/state/appCreateForm";

function NewApp() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const slug = inst.ctx.institution;
  const navigate = useNavigate();
  const qc = useQueryClient();
  const form = useForm<AppCreateValues>({ defaultValues: { app: "", redirectUris: "", postLogout: "" } });
  const app = form.watch("app");

  const create = useMutation({
    mutationFn: (body: AppCreateBody) => instAppsApi.create(inst.scope, body),
    onSuccess: (created, body) => {
      notify.success(t("instApps.new.created", { clientId: created?.clientId ?? composeClientId(slug, body.app) }));
      void qc.invalidateQueries({ queryKey: instAppKeys.all(inst.scope) });
      navigate(inst.path("/institution/apps"));
    },
    onError: (e) => notify.error(e),
  });

  const onSubmit = form.handleSubmit((values) => {
    const r = buildCreateBody(values, slug);
    if (!r.ok) {
      form.setError(r.field, { message: t(`instApps.new.errors.${r.error}`, { slug, uri: r.uri ?? "" }) });
      return;
    }
    create.mutate(r.body);
  });

  return (
    <div className="max-w-2xl">
      <Card>
        <CardHeader>
          <CardTitle>{t("instApps.new.title")}</CardTitle>
          <CardDescription>{t("instApps.new.description", { institution: slug })}</CardDescription>
        </CardHeader>
        <form onSubmit={onSubmit} noValidate>
          <CardContent className="space-y-4">
            <Field label={t("instApps.new.appName")} htmlFor="app" error={form.formState.errors.app?.message} help={t("instApps.new.appHelp", { slug })}>
              <div className="flex items-center gap-1">
                <span className="rounded-md border border-input bg-muted px-2 py-1.5 font-mono text-sm text-muted-foreground" data-testid="slug-prefix">
                  {slug}-
                </span>
                <Input id="app" autoComplete="off" placeholder="portal" maxLength={64} {...form.register("app", { onChange: () => form.clearErrors("app") })} />
              </div>
            </Field>
            <p className="text-sm">
              {t("instApps.new.willBe")}{" "}
              <code className="rounded bg-muted px-1.5 py-0.5 font-mono" data-testid="client-id-preview">
                {composeClientId(slug, app)}
              </code>
            </p>
            <Field label={t("instApps.new.redirectUris")} htmlFor="redirectUris" error={form.formState.errors.redirectUris?.message} help={t("instApps.new.redirectHelp")}>
              <Textarea id="redirectUris" rows={3} className="font-mono text-xs" placeholder={`https://${slug}.gov.rw/auth/callback`} {...form.register("redirectUris")} />
            </Field>
            <Field label={t("instApps.new.postLogout")} htmlFor="postLogout" error={form.formState.errors.postLogout?.message} help={t("instApps.new.postLogoutHelp")}>
              <Input id="postLogout" placeholder="+" autoComplete="off" {...form.register("postLogout")} />
            </Field>
            <div className="rounded-md border bg-muted/40 px-3 py-2.5 text-xs leading-relaxed text-muted-foreground">
              <p className="font-medium text-foreground">{t("instApps.new.nextTitle")}</p>
              <p className="mt-1">{t("instApps.new.nextBody")}</p>
              <p className="mt-1.5">{t("instApps.new.crossCuttingNote", { institution: slug })}</p>
            </div>
          </CardContent>
          <CardFooter className="flex justify-between">
            <Button asChild variant="ghost">
              <Link to={inst.path("/institution/apps")}>{t("common.cancel")}</Link>
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {t("instApps.new.create")}
            </Button>
          </CardFooter>
        </form>
      </Card>
    </div>
  );
}

export function Component() {
  return (
    <CapabilityGate capability="manage-clients">
      <NewApp />
    </CapabilityGate>
  );
}
