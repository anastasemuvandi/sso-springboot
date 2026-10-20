/** Password reset and MFA exemption cards. */
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useTranslation } from "react-i18next";

import { useConfirm } from "@/components/common/ConfirmDialog";
import { Field } from "@/components/common/Field";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { formatWhen } from "@/lib/utils";
import { grantMfaExemption, resetPassword, revokeMfaExemption } from "../api";
import { exemptionSchema, REASON_MAX, REASON_MIN, resetPasswordSchema, type ExemptionValues, type ResetPasswordValues } from "../state/userForms";
import {
  MFA_EXEMPT_GRANTED_AT_ATTRIBUTE,
  MFA_EXEMPT_GRANTED_BY_ATTRIBUTE,
  MFA_EXEMPT_REASON_ATTRIBUTE,
  MFA_EXEMPT_ROLE,
  type User,
  type UserRoles,
} from "../types";
import { useUserMutation } from "./useUserMutation";

export function PasswordCard({ userId }: { userId: string }) {
  const { t } = useTranslation();
  const form = useForm<ResetPasswordValues>({ resolver: zodResolver(resetPasswordSchema), defaultValues: { password: "", temporary: true } });
  const save = useUserMutation(userId, (v: ResetPasswordValues) => resetPassword(userId, v.password, v.temporary), t("users.toast.passwordReset"));
  const msg = form.formState.errors.password?.message;
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("users.password.title")}</CardTitle>
        <CardDescription>{t("users.password.description")}</CardDescription>
      </CardHeader>
      <form onSubmit={form.handleSubmit((v) => save.mutate(v, { onSuccess: () => form.reset({ password: "", temporary: v.temporary }) }))}>
        <CardContent className="space-y-4">
          <Field label={t("users.password.new")} htmlFor="new-password" help={t("users.password.policy")} error={msg ? t(msg) : undefined}>
            <Input id="new-password" type="password" autoComplete="new-password" {...form.register("password")} />
          </Field>
          <div className="flex items-center gap-2">
            <Checkbox id="pw-temporary" checked={form.watch("temporary")} onCheckedChange={(v) => form.setValue("temporary", v === true)} />
            <Label htmlFor="pw-temporary" className="font-normal">
              {t("users.password.temporary")}
            </Label>
          </div>
        </CardContent>
        <CardFooter>
          <Button type="submit" variant="outline" disabled={save.isPending}>
            {t("users.password.submit")}
          </Button>
        </CardFooter>
      </form>
    </Card>
  );
}

/**
 * MFA exemption. Exempt = holds realm role `mfa-exempt`; the justification,
 * granter and time are user attributes the wrapper writes. Only
 * POST /realm/mfa/exemptions grants it (super admin; the wrapper refuses the
 * generic role endpoints for this role).
 */
export function MfaExemptionCard({ user, roles }: { user: User; roles: UserRoles | undefined }) {
  const { t } = useTranslation();
  const confirm = useConfirm();
  const exempt = Boolean(roles?.realm_roles.some((r) => r.name === MFA_EXEMPT_ROLE));
  const attrs = user.attributes ?? {};
  const form = useForm<ExemptionValues>({ resolver: zodResolver(exemptionSchema), defaultValues: { reason: "" } });
  const grant = useUserMutation(user.id, (reason: string) => grantMfaExemption(user.id, reason), t("users.mfa.granted"));
  const revoke = useUserMutation(user.id, () => revokeMfaExemption(user.id), t("users.mfa.revoked"));
  const msg = form.formState.errors.reason?.message;

  async function onRevoke() {
    const ok = await confirm({ title: t("users.mfa.revokeTitle"), description: t("users.mfa.revokeBody"), confirmLabel: t("users.mfa.revoke"), destructive: true });
    if (ok) revoke.mutate(undefined);
  }

  return (
    <Card data-testid="mfa-exemption">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          {t("users.mfa.title")}
          {roles ? <Badge variant={exempt ? "warning" : "secondary"}>{exempt ? t("users.mfa.exempt") : t("users.mfa.notExempt")}</Badge> : null}
        </CardTitle>
        <CardDescription>{t("users.mfa.description")}</CardDescription>
      </CardHeader>
      {exempt ? (
        <>
          <CardContent className="space-y-1 text-sm">
            <div>
              <span className="text-muted-foreground">{t("users.mfa.reason")}: </span>
              {attrs[MFA_EXEMPT_REASON_ATTRIBUTE]?.[0] ?? "—"}
            </div>
            <div>
              <span className="text-muted-foreground">{t("users.mfa.grantedBy")}: </span>
              {attrs[MFA_EXEMPT_GRANTED_BY_ATTRIBUTE]?.[0] ?? "—"}
            </div>
            <div>
              <span className="text-muted-foreground">{t("users.mfa.grantedAt")}: </span>
              {formatWhen(attrs[MFA_EXEMPT_GRANTED_AT_ATTRIBUTE]?.[0])}
            </div>
          </CardContent>
          <CardFooter>
            <Button variant="destructive" size="sm" onClick={() => void onRevoke()} disabled={revoke.isPending}>
              {t("users.mfa.revoke")}
            </Button>
          </CardFooter>
        </>
      ) : (
        <form onSubmit={form.handleSubmit((v) => grant.mutate(v.reason, { onSuccess: () => form.reset({ reason: "" }) }))}>
          <CardContent>
            <Field
              label={t("users.mfa.reason")}
              htmlFor="mfa-reason"
              help={t("users.mfa.reasonHelp", { min: REASON_MIN, max: REASON_MAX })}
              error={msg ? t(msg, { min: REASON_MIN, max: REASON_MAX }) : undefined}
            >
              <Textarea id="mfa-reason" maxLength={REASON_MAX + 200} {...form.register("reason")} />
            </Field>
          </CardContent>
          <CardFooter>
            <Button type="submit" variant="outline" disabled={grant.isPending || !roles}>
              {t("users.mfa.grant")}
            </Button>
          </CardFooter>
        </form>
      )}
    </Card>
  );
}
