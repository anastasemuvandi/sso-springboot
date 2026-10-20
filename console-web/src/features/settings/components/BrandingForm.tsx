import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Upload } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Field } from "@/components/common/Field";
import { notify } from "@/components/common/toast";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { saveBranding, settingsKeys, uploadLogo } from "../api";
import type { BrandingBody, BrandingState } from "../types";

const TEXT = ["logo_url", "favicon_url", "welcome_title", "welcome_message"] as const;
const COLORS = ["primary_color", "secondary_color", "background_color"] as const;
const hex = z.string().refine((v) => v === "" || /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(v), "hex");
const url = z.string().refine((v) => v === "" || /^(https?:\/\/|\/)/.test(v), "url");

const schema = z.object({
  logo_url: url,
  favicon_url: url,
  welcome_title: z.string().max(200),
  welcome_message: z.string().max(2000),
  primary_color: hex,
  secondary_color: hex,
  background_color: hex,
});
type Values = z.infer<typeof schema>;

/** Mirrors the wrapper (RealmSettingsService.LOGO_TYPES, 5 MB); it re-checks the content. */
export const ALLOWED_LOGO_TYPES = ["image/png", "image/jpeg", "image/svg+xml", "image/gif"];
export const MAX_LOGO_BYTES = 5 * 1024 * 1024;

/** PUT /realm/branding (BrandingConfig) + POST /realm/branding/logo. Themes are set on the realm form. */
export function BrandingForm({ branding, canEdit }: { branding: BrandingState; canEdit: boolean }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  // GET /realm/branding nests the branding attributes under `custom_attributes`.
  const custom = branding.custom_attributes ?? {};
  const s = (k: string) => (typeof custom[k] === "string" ? (custom[k] as string) : "");
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: Object.fromEntries([...TEXT, ...COLORS].map((k) => [k, s(k)])) as Values,
  });
  const [file, setFile] = useState<File | null>(null);

  const save = useMutation({
    mutationFn: (v: Values) => {
      const body: BrandingBody = Object.fromEntries(Object.entries(v).map(([k, val]) => [k, val.trim() ? val.trim() : undefined]));
      return saveBranding(body);
    },
    onSuccess: () => {
      notify.success(t("settings.branding.saved"));
      void qc.invalidateQueries({ queryKey: settingsKeys.branding() });
    },
    onError: (e) => notify.error(e),
  });

  const upload = useMutation({
    mutationFn: (f: File) => uploadLogo(f),
    onSuccess: () => {
      notify.success(t("settings.branding.logoUploaded"));
      setFile(null);
      void qc.invalidateQueries({ queryKey: settingsKeys.branding() });
    },
    onError: (e) => notify.error(e),
  });

  const fileError = file && !ALLOWED_LOGO_TYPES.includes(file.type) ? t("settings.branding.badType") : file && file.size > MAX_LOGO_BYTES ? t("settings.branding.tooLarge") : null;
  const err = form.formState.errors;
  const logo = s("logo_url");

  return (
    <div className="space-y-6">
      <form onSubmit={form.handleSubmit((v) => save.mutate(v))} className="space-y-4" aria-label={t("settings.branding.title")}>
        <fieldset disabled={!canEdit || save.isPending} className="grid gap-4 sm:grid-cols-2">
          {TEXT.map((k) => (
            <Field key={k} label={t(`settings.branding.fields.${k}`)} htmlFor={`branding-${k}`} error={err[k] ? t(`settings.branding.invalid.${k === "logo_url" || k === "favicon_url" ? "url" : "text"}`) : undefined}>
              {k === "welcome_message" ? <Textarea id={`branding-${k}`} {...form.register(k)} /> : <Input id={`branding-${k}`} {...form.register(k)} />}
            </Field>
          ))}
          {COLORS.map((k) => (
            <Field key={k} label={t(`settings.branding.fields.${k}`)} htmlFor={`branding-${k}`} error={err[k] ? t("settings.branding.invalid.hex") : undefined}>
              <div className="flex items-center gap-2">
                <Input id={`branding-${k}`} placeholder="#00A1DE" {...form.register(k)} />
                <span className="h-8 w-8 shrink-0 rounded-md border" style={{ background: form.watch(k) || "transparent" }} aria-hidden />
              </div>
            </Field>
          ))}
        </fieldset>
        {canEdit ? (
          <Button type="submit" disabled={save.isPending}>
            {save.isPending ? t("common.saving") : t("common.save")}
          </Button>
        ) : null}
      </form>

      <div className="space-y-3 rounded-md border p-3">
        <div className="text-sm font-medium">{t("settings.branding.logo")}</div>
        {logo ? <img src={logo} alt={t("settings.branding.currentLogo")} className="h-16 w-auto rounded border bg-white object-contain p-1" /> : <p className="text-xs text-muted-foreground">{t("settings.branding.noLogo")}</p>}
        {canEdit ? (
          <div className="flex flex-wrap items-center gap-2">
            <Input type="file" accept={ALLOWED_LOGO_TYPES.join(",")} onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="max-w-xs" aria-label={t("settings.branding.chooseLogo")} />
            <Button type="button" variant="secondary" disabled={!file || Boolean(fileError) || upload.isPending} onClick={() => file && upload.mutate(file)}>
              <Upload />
              {t("settings.branding.upload")}
            </Button>
            {fileError ? <span className="text-xs text-destructive">{fileError}</span> : null}
          </div>
        ) : null}
      </div>
    </div>
  );
}
