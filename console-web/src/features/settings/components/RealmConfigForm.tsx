import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslation } from "react-i18next";

import { Field } from "@/components/common/Field";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { saveRealmConfig, settingsKeys } from "../api";
import { BOOLEAN_FIELDS, NUMBER_FIELDS, THEME_FIELDS, realmConfigBody, realmFormFromRepresentation, type RealmConfigForm } from "../state/realmConfigForm";
import type { RealmRepresentation, ThemesResponse } from "../types";

const nonNeg = z.number({ invalid_type_error: "number" }).int().min(0);

const schema = z.object({
  realm: z.string().min(1),
  displayName: z.string().max(255),
  displayNameHtml: z.string().max(2000),
  defaultLocale: z.string().min(2).max(10),
  supportedLocales: z.string(),
  booleans: z.object(Object.fromEntries(BOOLEAN_FIELDS.map((k) => [k, z.boolean()])) as Record<(typeof BOOLEAN_FIELDS)[number], z.ZodBoolean>),
  numbers: z.object({
    ...(Object.fromEntries(NUMBER_FIELDS.map((k) => [k, nonNeg])) as Record<(typeof NUMBER_FIELDS)[number], typeof nonNeg>),
    failureFactor: z.number().int().min(1),
  }),
  themes: z.object(Object.fromEntries(THEME_FIELDS.map((k) => [k, z.string()])) as Record<(typeof THEME_FIELDS)[number], z.ZodString>),
});

const THEME_KIND: Record<(typeof THEME_FIELDS)[number], keyof ThemesResponse> = {
  loginTheme: "login",
  accountTheme: "account",
  adminTheme: "admin",
  emailTheme: "email",
};

/** PUT /realm/config — the editable subset of the realm (RealmConfig DTO). */
export function RealmConfigFormCard({ realm, themes, canEdit }: { realm: RealmRepresentation; themes?: ThemesResponse; canEdit: boolean }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const form = useForm<RealmConfigForm>({ resolver: zodResolver(schema), defaultValues: realmFormFromRepresentation(realm) });
  const save = useMutation({
    mutationFn: (v: RealmConfigForm) => saveRealmConfig(realmConfigBody(v)),
    onSuccess: () => {
      notify.success(t("settings.realm.saved"));
      void qc.invalidateQueries({ queryKey: settingsKeys.config() });
    },
    onError: (e) => notify.error(e),
  });
  const err = form.formState.errors;

  return (
    <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="space-y-6" aria-label={t("settings.realm.title")}>
      <fieldset disabled={!canEdit || save.isPending} className="space-y-6">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t("settings.realm.realm")} htmlFor="realm-name">
            <Input id="realm-name" {...form.register("realm")} readOnly className="bg-muted" />
          </Field>
          <Field label={t("settings.realm.displayName")} htmlFor="realm-display" error={err.displayName?.message}>
            <Input id="realm-display" {...form.register("displayName")} />
          </Field>
          <Field label={t("settings.realm.displayNameHtml")} htmlFor="realm-display-html">
            <Input id="realm-display-html" {...form.register("displayNameHtml")} />
          </Field>
          <Field label={t("settings.realm.defaultLocale")} htmlFor="realm-locale" error={err.defaultLocale?.message}>
            <Input id="realm-locale" {...form.register("defaultLocale")} />
          </Field>
          <Field label={t("settings.realm.supportedLocales")} htmlFor="realm-locales" help={t("settings.realm.supportedLocalesHelp")}>
            <Input id="realm-locales" {...form.register("supportedLocales")} />
          </Field>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold">{t("settings.realm.loginPolicy")}</h3>
          <div className="grid gap-2 sm:grid-cols-2">
            {BOOLEAN_FIELDS.map((k) => (
              <Controller
                key={k}
                control={form.control}
                name={`booleans.${k}`}
                render={({ field }) => (
                  <label className="flex items-center justify-between gap-3 rounded-md border px-3 py-2 text-sm">
                    <span>
                      {t(`settings.realm.fields.${k}`)}
                      <span className="ml-2 font-mono text-[10px] text-muted-foreground">{k}</span>
                    </span>
                    <Switch checked={field.value} onCheckedChange={field.onChange} disabled={!canEdit} aria-label={k} />
                  </label>
                )}
              />
            ))}
          </div>
        </div>

        <div>
          <h3 className="mb-1 text-sm font-semibold">{t("settings.realm.bruteForce")}</h3>
          <p className="mb-2 text-xs text-muted-foreground">{t("settings.realm.bruteForceHelp")}</p>
          <div className="grid gap-4 sm:grid-cols-3">
            {NUMBER_FIELDS.map((k) => (
              <Field key={k} label={t(`settings.realm.fields.${k}`)} htmlFor={`realm-${k}`} error={err.numbers?.[k]?.message ? t("settings.realm.invalidNumber") : undefined}>
                <Input id={`realm-${k}`} type="number" min={0} {...form.register(`numbers.${k}`, { valueAsNumber: true })} />
              </Field>
            ))}
          </div>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-semibold">{t("settings.realm.themes")}</h3>
          <div className="grid gap-4 sm:grid-cols-4">
            {THEME_FIELDS.map((k) => {
              const options = themes?.[THEME_KIND[k]] ?? [];
              return (
                <Field key={k} label={t(`settings.realm.fields.${k}`)} htmlFor={`realm-${k}`}>
                  <select id={`realm-${k}`} {...form.register(`themes.${k}`)} className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm">
                    <option value="">{t("settings.realm.serverDefault")}</option>
                    {[...new Set([...options, form.getValues(`themes.${k}`)].filter(Boolean))].map((o) => (
                      <option key={o} value={o}>
                        {o}
                      </option>
                    ))}
                  </select>
                </Field>
              );
            })}
          </div>
        </div>
      </fieldset>
      {canEdit ? (
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? t("common.saving") : t("common.save")}
        </Button>
      ) : (
        <p className="text-xs text-muted-foreground">{t("settings.superAdminOnly")}</p>
      )}
    </form>
  );
}
