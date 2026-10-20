import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { CheckCircle2, Send, XCircle } from "lucide-react";
import { useTranslation } from "react-i18next";

import { errorMessage } from "@/api/errors";
import { Field } from "@/components/common/Field";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { saveEmailConfig, settingsKeys, testEmail } from "../api";
import { smtpBody, smtpFormFromConfig, type SmtpFormValues } from "../state/smtpForm";

const email = z.string().email();
const optEmail = z.string().refine((v) => v.trim() === "" || email.safeParse(v.trim()).success, "email");

const schema = z.object({
  host: z.string().trim().min(1),
  port: z.number().int().min(1).max(65535),
  from: z.string().trim().email(),
  fromDisplayName: z.string(),
  replyTo: optEmail,
  replyToDisplayName: z.string(),
  envelopeFrom: optEmail,
  ssl: z.boolean(),
  starttls: z.boolean(),
  auth: z.boolean(),
  user: z.string(),
  password: z.string(),
});

/** PUT /realm/email/config (SMTPConfig) + POST /realm/email/test. The password is write-only. */
export function SmtpForm({ config, canEdit }: { config: Record<string, unknown>; canEdit: boolean }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const form = useForm<SmtpFormValues>({ resolver: zodResolver(schema), defaultValues: smtpFormFromConfig(config) });
  const auth = form.watch("auth");

  const save = useMutation({
    mutationFn: (v: SmtpFormValues) => saveEmailConfig(smtpBody(v)),
    onSuccess: () => {
      notify.success(t("settings.smtp.saved"));
      form.setValue("password", "");
      void qc.invalidateQueries({ queryKey: settingsKeys.email() });
    },
    onError: (e) => notify.error(e),
  });
  const test = useMutation({ mutationFn: testEmail });
  const err = form.formState.errors;
  const configured = Object.keys(config).length > 0;

  return (
    <div className="space-y-4">
      {!configured ? <p className="text-sm text-muted-foreground">{t("settings.smtp.notConfigured")}</p> : null}
      <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="space-y-4" aria-label={t("settings.smtp.title")}>
        <fieldset disabled={!canEdit || save.isPending} className="grid gap-4 sm:grid-cols-2">
          <Field label={t("settings.smtp.host")} htmlFor="smtp-host" error={err.host ? t("settings.smtp.required") : undefined}>
            <Input id="smtp-host" {...form.register("host")} />
          </Field>
          <Field label={t("settings.smtp.port")} htmlFor="smtp-port" error={err.port ? t("settings.smtp.badPort") : undefined}>
            <Input id="smtp-port" type="number" {...form.register("port", { valueAsNumber: true })} />
          </Field>
          <Field label={t("settings.smtp.from")} htmlFor="smtp-from" error={err.from ? t("settings.smtp.badEmail") : undefined}>
            <Input id="smtp-from" {...form.register("from")} />
          </Field>
          <Field label={t("settings.smtp.fromDisplayName")} htmlFor="smtp-from-name">
            <Input id="smtp-from-name" {...form.register("fromDisplayName")} />
          </Field>
          <Field label={t("settings.smtp.replyTo")} htmlFor="smtp-reply" error={err.replyTo ? t("settings.smtp.badEmail") : undefined}>
            <Input id="smtp-reply" {...form.register("replyTo")} />
          </Field>
          <Field label={t("settings.smtp.replyToDisplayName")} htmlFor="smtp-reply-name">
            <Input id="smtp-reply-name" {...form.register("replyToDisplayName")} />
          </Field>
          <Field label={t("settings.smtp.envelopeFrom")} htmlFor="smtp-envelope" error={err.envelopeFrom ? t("settings.smtp.badEmail") : undefined}>
            <Input id="smtp-envelope" {...form.register("envelopeFrom")} />
          </Field>
          <div className="flex flex-wrap items-center gap-4 sm:col-span-2">
            {(["ssl", "starttls", "auth"] as const).map((k) => (
              <Controller
                key={k}
                control={form.control}
                name={k}
                render={({ field }) => (
                  <label className="flex items-center gap-2 text-sm">
                    <Switch checked={field.value} onCheckedChange={field.onChange} disabled={!canEdit} aria-label={t(`settings.smtp.${k}`)} />
                    {t(`settings.smtp.${k}`)}
                  </label>
                )}
              />
            ))}
          </div>
          {auth ? (
            <>
              <Field label={t("settings.smtp.user")} htmlFor="smtp-user">
                <Input id="smtp-user" autoComplete="off" {...form.register("user")} />
              </Field>
              <Field label={t("settings.smtp.password")} htmlFor="smtp-password" help={t("settings.smtp.passwordHelp")}>
                <Input id="smtp-password" type="password" autoComplete="new-password" {...form.register("password")} />
              </Field>
            </>
          ) : null}
        </fieldset>
        <div className="flex flex-wrap items-center gap-2">
          {canEdit ? (
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? t("common.saving") : t("common.save")}
            </Button>
          ) : null}
          <Button type="button" variant="outline" onClick={() => test.mutate()} disabled={test.isPending || !configured} data-testid="smtp-test">
            <Send />
            {t("settings.smtp.test")}
          </Button>
        </div>
      </form>
      {test.isSuccess ? (
        <p className={`flex items-center gap-2 text-sm ${test.data.success ? "text-emerald-700" : "text-destructive"}`} data-testid="smtp-test-result" role="status">
          {test.data.success ? <CheckCircle2 className="h-4 w-4" /> : <XCircle className="h-4 w-4" />}
          {test.data.success ? t("settings.smtp.testOk") : t("settings.smtp.testFailed")} {test.data.message}
        </p>
      ) : test.isError ? (
        <p className="flex items-center gap-2 text-sm text-destructive" data-testid="smtp-test-result" role="status">
          <XCircle className="h-4 w-4" />
          {t("settings.smtp.testFailed")} {errorMessage(test.error)}
        </p>
      ) : null}
      <p className="text-xs text-muted-foreground">{t("settings.smtp.testHelp")}</p>
    </div>
  );
}
