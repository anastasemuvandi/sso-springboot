/** Identity (enable/disable, profile edit) and IPPIS employment (employee_number) cards. */
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslation } from "react-i18next";

import { useConfirm } from "@/components/common/ConfirmDialog";
import { Field } from "@/components/common/Field";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { formatWhen } from "@/lib/utils";
import { setUserEnabled, updateUser } from "../api";
import { fullName } from "../state/userForms";
import type { User } from "../types";
import { useUserMutation } from "./useUserMutation";

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-3 text-sm">
      <div className="w-36 shrink-0 text-muted-foreground">{label}</div>
      <div className="min-w-0 break-words">{children}</div>
    </div>
  );
}

const profileSchema = z.object({
  firstName: z.string().trim().min(1, "users.validation.required"),
  lastName: z.string().trim().min(1, "users.validation.required"),
  email: z.string().trim().email("users.validation.email"),
  emailVerified: z.boolean(),
});
type ProfileValues = z.infer<typeof profileSchema>;

export function IdentityCard({ user }: { user: User }) {
  const { t } = useTranslation();
  const confirm = useConfirm();
  const toggle = useUserMutation(user.id, (enabled: boolean) => setUserEnabled(user.id, enabled), (enabled) =>
    enabled ? t("users.toast.enabled") : t("users.toast.disabled"),
  );
  const save = useUserMutation(user.id, (v: ProfileValues) => updateUser(user.id, v), t("users.toast.profileSaved"));
  const form = useForm<ProfileValues>({
    resolver: zodResolver(profileSchema),
    values: {
      firstName: user.firstName ?? "",
      lastName: user.lastName ?? "",
      email: user.email ?? "",
      emailVerified: Boolean(user.emailVerified),
    },
  });
  const err = (k: keyof ProfileValues) => {
    const m = form.formState.errors[k]?.message;
    return m ? t(m) : undefined;
  };

  async function onToggle() {
    if (user.enabled) {
      const ok = await confirm({
        title: t("users.disableTitle", { username: user.username }),
        description: t("users.disableBody"),
        confirmLabel: t("users.disable"),
        destructive: true,
      });
      if (!ok) return;
    }
    toggle.mutate(!user.enabled);
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="font-mono">{user.username}</CardTitle>
        <CardDescription className="flex flex-wrap items-center gap-2">
          <span>{fullName(user) || t("users.noName")}</span>
          {user.email ? <span>· {user.email}</span> : null}
          <Badge variant={user.enabled ? "secondary" : "destructive"}>{user.enabled ? t("common.enabled") : t("common.disabled")}</Badge>
        </CardDescription>
      </CardHeader>
      <form onSubmit={form.handleSubmit((v) => save.mutate(v))}>
        <CardContent className="space-y-4">
          <div className="space-y-2">
            <Row label="ID">
              <code className="text-xs">{user.id}</code>
            </Row>
            <Row label={t("users.created")}>{user.createdTimestamp ? formatWhen(user.createdTimestamp) : "—"}</Row>
            {user.requiredActions?.length ? <Row label={t("users.requiredActions")}>{user.requiredActions.join(", ")}</Row> : null}
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label={t("users.fields.firstName")} htmlFor="p-first" error={err("firstName")}>
              <Input id="p-first" {...form.register("firstName")} />
            </Field>
            <Field label={t("users.fields.lastName")} htmlFor="p-last" error={err("lastName")}>
              <Input id="p-last" {...form.register("lastName")} />
            </Field>
            <Field label={t("users.fields.email")} htmlFor="p-email" error={err("email")}>
              <Input id="p-email" type="email" {...form.register("email")} />
            </Field>
            <div className="flex items-center gap-2 pt-6">
              <Checkbox
                id="p-verified"
                checked={form.watch("emailVerified")}
                onCheckedChange={(v) => form.setValue("emailVerified", v === true, { shouldDirty: true })}
              />
              <Label htmlFor="p-verified" className="font-normal">
                {t("users.fields.emailVerified")}
              </Label>
            </div>
          </div>
        </CardContent>
        <CardFooter className="flex flex-wrap justify-between gap-2">
          <Button type="submit" variant="outline" disabled={save.isPending || !form.formState.isDirty}>
            {save.isPending ? t("common.saving") : t("users.saveProfile")}
          </Button>
          <Button variant={user.enabled ? "destructive" : "default"} onClick={() => void onToggle()} disabled={toggle.isPending}>
            {user.enabled ? t("users.disableUser") : t("users.enableUser")}
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}

export function IppisCard({ user }: { user: User }) {
  const { t } = useTranslation();
  const attrs = user.attributes ?? {};
  const current = attrs.employee_number?.[0] ?? "";
  const form = useForm<{ employeeNumber: string }>({ values: { employeeNumber: current } });
  const save = useUserMutation(
    user.id,
    // Empty clears the key; attributes merge server-side, so IPPIS-sourced ones are untouched.
    (n: string) => updateUser(user.id, { attributes: { employee_number: n ? [n] : [] } }),
    t("users.toast.employeeNumberSaved"),
  );
  const sourced = [
    { label: t("users.ippis.institution"), value: attrs.institution?.[0] },
    { label: t("users.ippis.position"), value: attrs.position?.[0] },
    { label: t("users.ippis.status"), value: attrs.ippis_status?.[0] },
    { label: t("users.ippis.nationalId"), value: attrs.national_id?.[0] },
  ].filter((a) => a.value);

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("users.ippis.title")}</CardTitle>
        <CardDescription>{t("users.ippis.description")}</CardDescription>
      </CardHeader>
      <form onSubmit={form.handleSubmit((v) => save.mutate(v.employeeNumber.trim()))}>
        <CardContent className="space-y-4">
          <Field label={t("users.fields.employeeNumber")} htmlFor="emp-no">
            <Input id="emp-no" placeholder={t("users.ippis.placeholder")} {...form.register("employeeNumber")} />
          </Field>
          {sourced.length > 0 ? (
            <div className="space-y-2 rounded-md border p-3">
              <div className="text-xs font-medium text-muted-foreground">{t("users.ippis.sourced")}</div>
              {sourced.map((a) => (
                <Row key={a.label} label={a.label}>
                  {a.value}
                </Row>
              ))}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">{t("users.ippis.none")}</p>
          )}
        </CardContent>
        <CardFooter>
          <Button type="submit" variant="outline" disabled={save.isPending}>
            {t("users.ippis.save")}
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}
