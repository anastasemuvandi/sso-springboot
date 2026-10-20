/** Settings feature (realm config, branding, email, MFA): keys, queries, mutations. */
import { queryOptions } from "@tanstack/react-query";

import { api, apiFetch, unwrap, unwrapAs } from "@/api/client";
import type { MfaMode, RealmMfaChange, RealmMfaState } from "@/features/clients/types";
import type { BrandingBody, BrandingState, MfaExemption, RealmConfigBody, RealmRepresentation, SmtpBody, SmtpTestResult } from "./types";

export const settingsKeys = {
  all: ["settings"] as const,
  realmMfa: () => [...settingsKeys.all, "mfa"] as const,
  exemptions: () => [...settingsKeys.all, "mfa", "exemptions"] as const,
  config: () => [...settingsKeys.all, "config"] as const,
  branding: () => [...settingsKeys.all, "branding"] as const,
  themes: () => [...settingsKeys.all, "themes"] as const,
  email: () => [...settingsKeys.all, "email"] as const,
};

/** GET /realm/mfa — the realm's TOTP mode and whether the flow is bound. */
export const realmMfaQuery = () =>
  queryOptions({
    queryKey: settingsKeys.realmMfa(),
    queryFn: () => unwrapAs<RealmMfaState>(api.GET("/realm/mfa")),
  });

/** GET /realm/mfa/exemptions → { exemptions } */
export const exemptionsQuery = () =>
  queryOptions({
    queryKey: settingsKeys.exemptions(),
    queryFn: async () => (await unwrapAs<{ exemptions?: MfaExemption[] }>(api.GET("/realm/mfa/exemptions"))).exemptions ?? [],
  });

export const realmConfigQuery = () =>
  queryOptions({ queryKey: settingsKeys.config(), queryFn: () => unwrapAs<RealmRepresentation>(api.GET("/realm/config")) });

export const brandingQuery = () =>
  queryOptions({ queryKey: settingsKeys.branding(), queryFn: () => unwrapAs<BrandingState>(api.GET("/realm/branding")) });

export const themesQuery = () => queryOptions({ queryKey: settingsKeys.themes(), queryFn: () => unwrap(api.GET("/realm/themes")) });

export const emailConfigQuery = () =>
  queryOptions({ queryKey: settingsKeys.email(), queryFn: () => unwrapAs<Record<string, unknown>>(api.GET("/realm/email/config")) });

/** PUT /realm/mfa {mode} — super admin only; 409 while browser-totp is not bound. */
export const setRealmMfaMode = (mode: MfaMode) => unwrapAs<RealmMfaChange>(api.PUT("/realm/mfa", { body: { mode } }));

/** POST /realm/mfa/exemptions — the only path that assigns `mfa-exempt`. */
export const grantExemption = (user_id: string, reason: string) => unwrapAs<unknown>(api.POST("/realm/mfa/exemptions", { body: { user_id, reason } }));

/** DELETE /realm/mfa/exemptions/{userId} */
export const revokeExemption = (userId: string) =>
  unwrapAs<unknown>(api.DELETE("/realm/mfa/exemptions/{user_id}", { params: { path: { user_id: userId } } }));

export const saveRealmConfig = (body: RealmConfigBody) => unwrapAs<unknown>(api.PUT("/realm/config", { body }));

export const saveBranding = (body: BrandingBody) => unwrapAs<unknown>(api.PUT("/realm/branding", { body }));

/** POST /realm/branding/logo (multipart, field `file`). */
export async function uploadLogo(file: File): Promise<unknown> {
  const fd = new FormData();
  fd.append("file", file);
  const res = await apiFetch("/realm/branding/logo", { method: "POST", body: fd });
  const text = await res.text();
  try {
    return text ? JSON.parse(text) : null;
  } catch {
    return text;
  }
}

export const saveEmailConfig = (body: SmtpBody) => unwrapAs<unknown>(api.PUT("/realm/email/config", { body }));

/** POST /realm/email/test — tries the stored SMTP settings. */
export const testEmail = () => unwrap(api.POST("/realm/email/test")) as Promise<SmtpTestResult>;
