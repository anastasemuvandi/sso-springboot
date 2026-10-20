/**
 * Realm TOTP mode changes and exemption reasons — pure, no React.
 *
 * Mirrors the shared Java rule (`mfa-policy`): the bulk CONFIGURE_TOTP
 * enrolment runs only the FIRST time the mode becomes `all` (when
 * `mfa_totp_first_enabled_at` is not yet set) and never for exempt users; a
 * mode other than `off` is refused (409) while the realm is not on the
 * `browser-totp` flow. The console only explains; the wrapper decides.
 */
import type { MfaMode, RealmMfaState } from "@/features/clients/types";

export const MFA_MODES: readonly MfaMode[] = ["off", "per_client", "all"];

export interface ModeChange {
  from: MfaMode;
  to: MfaMode;
  /** Nothing to do. */
  noop: boolean;
  /** The wrapper will refuse it: TOTP needs the browser-totp flow bound first. */
  willBeRefused: boolean;
  /** Existing enabled users without TOTP get CONFIGURE_TOTP (first time `all` only). */
  bulkEnrol: boolean;
  /** i18n keys of the consequence paragraphs (`settings.mfa.consequences.*`). */
  consequenceKeys: string[];
}

export function describeModeChange(state: Pick<RealmMfaState, "mode" | "flow_ready" | "first_enabled_at">, to: MfaMode): ModeChange {
  const from = state.mode;
  const willBeRefused = to !== "off" && !state.flow_ready;
  const bulkEnrol = to === "all" && from !== "all" && !state.first_enabled_at;
  const keys: string[] = [];
  if (to === "all") {
    keys.push("settings.mfa.consequences.allEveryone", "settings.mfa.consequences.allNoGrace");
    keys.push(bulkEnrol ? "settings.mfa.consequences.allBulkFirst" : "settings.mfa.consequences.allBulkDone");
  } else if (to === "per_client") {
    keys.push("settings.mfa.consequences.perClientDefault", "settings.mfa.consequences.perClientStepUp", "settings.mfa.consequences.perClientNoBulk");
  } else {
    keys.push("settings.mfa.consequences.offPasswordOnly", "settings.mfa.consequences.offKept");
  }
  if (willBeRefused) keys.push("settings.mfa.consequences.flowNotReady");
  return { from, to, noop: from === to, willBeRefused, bulkEnrol, consequenceKeys: keys };
}

export const MIN_REASON_LENGTH = 15;
export const MAX_REASON_LENGTH = 500;

export type ReasonCheck = { ok: true; value: string } | { ok: false; error: "too_short" | "too_long"; length: number };

/** MfaPolicy.validateReason: whitespace collapsed, 15..500 characters. */
export function validateExemptionReason(reason: string | null | undefined): ReasonCheck {
  const text = (reason ?? "").trim().split(/\s+/).join(" ").trim();
  if (text.length < MIN_REASON_LENGTH) return { ok: false, error: "too_short", length: text.length };
  if (text.length > MAX_REASON_LENGTH) return { ok: false, error: "too_long", length: text.length };
  return { ok: true, value: text };
}

/** Super admins may change the mode and exemptions (cosmetic — the wrapper refuses anyone else). */
export function canChangeMfa(roles: readonly string[] | undefined): boolean {
  return Boolean(roles?.includes("super_admin"));
}
