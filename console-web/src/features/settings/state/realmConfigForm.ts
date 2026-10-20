/**
 * Realm config form <-> the Keycloak realm representation — pure.
 * The PUT DTO (RealmConfig) is a fixed subset of the representation; the
 * form edits that subset and sends it whole.
 */
import type { RealmConfigBody, RealmRepresentation } from "../types";

export const BOOLEAN_FIELDS = [
  "enabled",
  "registrationAllowed",
  "registrationEmailAsUsername",
  "rememberMe",
  "verifyEmail",
  "loginWithEmailAllowed",
  "duplicateEmailsAllowed",
  "resetPasswordAllowed",
  "editUsernameAllowed",
  "bruteForceProtected",
  "permanentLockout",
  "internationalizationEnabled",
] as const;

export const NUMBER_FIELDS = [
  "failureFactor",
  "waitIncrementSeconds",
  "quickLoginCheckMilliSeconds",
  "minimumQuickLoginWaitSeconds",
  "maxFailureWaitSeconds",
  "maxDeltaTimeSeconds",
] as const;

export const THEME_FIELDS = ["loginTheme", "accountTheme", "adminTheme", "emailTheme"] as const;

export interface RealmConfigForm {
  realm: string;
  displayName: string;
  displayNameHtml: string;
  defaultLocale: string;
  /** Comma-separated in the form. */
  supportedLocales: string;
  booleans: Record<(typeof BOOLEAN_FIELDS)[number], boolean>;
  numbers: Record<(typeof NUMBER_FIELDS)[number], number>;
  themes: Record<(typeof THEME_FIELDS)[number], string>;
}

/** Keycloak defaults, used when the representation leaves a field out. */
const NUMBER_DEFAULTS: RealmConfigForm["numbers"] = {
  failureFactor: 30,
  waitIncrementSeconds: 60,
  quickLoginCheckMilliSeconds: 1000,
  minimumQuickLoginWaitSeconds: 60,
  maxFailureWaitSeconds: 900,
  maxDeltaTimeSeconds: 43200,
};

export function realmFormFromRepresentation(r: RealmRepresentation): RealmConfigForm {
  const s = (k: string) => (typeof r[k] === "string" ? (r[k] as string) : "");
  const booleans = Object.fromEntries(BOOLEAN_FIELDS.map((k) => [k, r[k] === true])) as RealmConfigForm["booleans"];
  const numbers = Object.fromEntries(
    NUMBER_FIELDS.map((k) => [k, typeof r[k] === "number" ? (r[k] as number) : NUMBER_DEFAULTS[k]]),
  ) as RealmConfigForm["numbers"];
  const themes = Object.fromEntries(THEME_FIELDS.map((k) => [k, s(k)])) as RealmConfigForm["themes"];
  return {
    realm: s("realm"),
    displayName: s("displayName"),
    displayNameHtml: s("displayNameHtml"),
    defaultLocale: s("defaultLocale") || "en",
    supportedLocales: Array.isArray(r.supportedLocales) ? (r.supportedLocales as unknown[]).map(String).join(", ") : "",
    booleans,
    numbers,
    themes,
  };
}

export function realmConfigBody(f: RealmConfigForm): RealmConfigBody {
  const opt = (v: string) => (v.trim() ? v.trim() : undefined);
  return {
    realm: f.realm,
    displayName: opt(f.displayName),
    displayNameHtml: opt(f.displayNameHtml),
    defaultLocale: f.defaultLocale.trim() || "en",
    supportedLocales: f.supportedLocales
      .split(",")
      .map((x) => x.trim())
      .filter(Boolean),
    ...f.booleans,
    ...f.numbers,
    loginTheme: opt(f.themes.loginTheme),
    accountTheme: opt(f.themes.accountTheme),
    adminTheme: opt(f.themes.adminTheme),
    emailTheme: opt(f.themes.emailTheme),
  };
}
