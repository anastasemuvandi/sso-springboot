/**
 * MFA (TOTP) status derivation — pure, no React.
 *
 * Mirrors the shared Java rule (`mfa-policy`, `MfaPolicy`) that Keycloak and
 * the wrapper both apply:
 *
 * - realm `mfa_totp_mode` = off | per_client | all (trimmed, any case);
 *   an unrecognised value reads as `all` (fail closed);
 * - unset/blank mode falls back to the legacy `mfa_totp_enforced`:
 *   unset = all, "false" (trimmed, any case) = off, anything else = all;
 * - in per_client mode, client attribute `sso.client.mfa_required`:
 *   unset or blank = required; only "false" (trimmed, any case) opts out.
 *
 * The console decides nothing with this — it explains what Keycloak will do.
 */
import type { Client, MfaMode, RealmMfaState } from "../types";

export const MODE_ATTRIBUTE = "mfa_totp_mode";
export const LEGACY_ATTRIBUTE = "mfa_totp_enforced";
export const CLIENT_ATTRIBUTE = "sso.client.mfa_required";
export const MFA_FLOW_ALIAS = "browser-totp";

const MODES: readonly MfaMode[] = ["off", "per_client", "all"];

function isBlank(v: string | null | undefined): v is null | undefined | "" {
  return v === null || v === undefined || v.trim() === "";
}

/** Exact match after trim + lower-case; null for blank or unknown values. */
export function parseMode(raw: string | null | undefined): MfaMode | null {
  if (raw === null || raw === undefined) return null;
  const m = raw.trim().toLowerCase();
  return (MODES as readonly string[]).includes(m) ? (m as MfaMode) : null;
}

/** Legacy switch: unset = on; "false" = off; any other value = on. */
export function legacyEnforced(raw: string | null | undefined): boolean {
  return raw === null || raw === undefined || raw.trim().toLowerCase() !== "false";
}

/** The effective realm mode from the raw attributes (MfaPolicy.effectiveMode). */
export function effectiveMode(modeRaw: string | null | undefined, legacyRaw: string | null | undefined): MfaMode {
  if (!isBlank(modeRaw)) return parseMode(modeRaw) ?? "all";
  return legacyEnforced(legacyRaw) ? "all" : "off";
}

/** Per-client setting: unset, blank or anything but "false" = required. */
export function clientRequiresValue(value: string | null | undefined): boolean {
  return value === null || value === undefined || value.trim().toLowerCase() !== "false";
}

export function clientRequires(client: Pick<Client, "attributes">): boolean {
  return clientRequiresValue(client.attributes?.[CLIENT_ATTRIBUTE]);
}

/** Does TOTP apply to a login to a client with this attribute value, under this mode? */
export function mfaApplies(mode: MfaMode | null | undefined, clientValue: string | null | undefined): boolean {
  switch (mode ?? "all") {
    case "off":
      return false;
    case "per_client":
      return clientRequiresValue(clientValue);
    default:
      return true;
  }
}

export type MfaStatusKind =
  | "required"
  | "not_required"
  | "off"
  | "not_configured"
  | "bypassed"
  | "no_user_login"
  | "unknown";

export interface MfaStatus {
  kind: MfaStatusKind;
  /** i18n key for the short label (`clients.mfaStatus.<kind>.label`). */
  labelKey: string;
  /** i18n key for the tooltip (`clients.mfaStatus.<kind>.detail`). */
  detailKey: string;
  /** Interpolation values for the detail. */
  params: Record<string, string>;
  /** Password grants are on (the wrapper's /auth/login gates them; Keycloak's own token endpoint does not). */
  passwordGrant: boolean;
}

function status(kind: MfaStatusKind, passwordGrant: boolean, params: Record<string, string> = {}): MfaStatus {
  return {
    kind,
    labelKey: `clients.mfaStatus.${kind}.label`,
    detailKey: `clients.mfaStatus.${kind}.detail`,
    params,
    passwordGrant,
  };
}

/**
 * What realm TOTP means for ONE client's logins (the clients list "2FA"
 * column). Order matters: no user login > unknown realm > browser-flow
 * override > flow not bound > mode off > per-client opt-out > required.
 */
export function mfaStatusFor(client: Client, realm: RealmMfaState | null | undefined): MfaStatus {
  const passwordGrant = Boolean(client.directAccessGrantsEnabled);
  if (!client.standardFlowEnabled && !client.directAccessGrantsEnabled) return status("no_user_login", false);
  if (!realm) return status("unknown", passwordGrant);
  if (client.authenticationFlowBindingOverrides?.browser) return status("bypassed", passwordGrant);
  if (!realm.flow_ready) return status("not_configured", passwordGrant, { flow: realm.browser_flow ?? "" });
  if (realm.mode === "off") return status("off", passwordGrant);
  if (realm.mode === "per_client" && !clientRequires(client)) return status("not_required", passwordGrant);
  return status("required", passwordGrant, { mode: realm.mode });
}
