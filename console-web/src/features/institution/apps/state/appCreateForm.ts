/**
 * The "new client" form for an institution admin — pure, no React.
 *
 * The admin types the APP PART only; the wrapper composes `<slug>-<app>`
 * from the institution the request resolved to (there is no institution
 * field, by contract). Rules mirror the wrapper's `client_id_for`:
 *   - trimmed and lower-cased;
 *   - 2..64 characters of [a-z0-9-];
 *   - must not be the slug itself or start with `<slug>-` (a pasted full
 *     client-id would otherwise become `minagri-minagri-portal`).
 * The wrapper re-checks all of it; this only explains it before the click.
 */

export const APP_MIN = 2;
export const APP_MAX = 64;
const APP_RE = /^[a-z0-9-]+$/;

export type AppPartError = "required" | "too_short" | "too_long" | "bad_chars" | "has_prefix";

export function normaliseApp(raw: string): string {
  return raw.trim().toLowerCase();
}

/** Why the app part is not usable, or null. */
export function appPartError(raw: string, slug: string): AppPartError | null {
  const app = normaliseApp(raw);
  if (!app) return "required";
  if (!APP_RE.test(app)) return "bad_chars";
  if (app.length < APP_MIN) return "too_short";
  if (app.length > APP_MAX) return "too_long";
  const s = slug.trim().toLowerCase();
  if (s && (app === s || app.startsWith(`${s}-`))) return "has_prefix";
  return null;
}

/** The client-id the wrapper will create — a preview, never sent. */
export function composeClientId(slug: string, raw: string): string {
  const app = normaliseApp(raw);
  return `${slug}-${app || "<app>"}`;
}

/** Split a textarea of redirect URIs (one per line, or comma-separated). */
export function parseUriList(raw: string): string[] {
  return raw
    .split(/[\n,]+/)
    .map((s) => s.trim())
    .filter(Boolean);
}

export type UriError = "not_absolute" | "bad_scheme" | "wildcard_host" | "fragment";

/**
 * A redirect URI Keycloak will match exactly: absolute http(s), no wildcard
 * in the host, no fragment. A trailing `*` in the path is Keycloak's own
 * wildcard and is allowed.
 */
export function uriError(uri: string): UriError | null {
  if (/^[a-z][a-z0-9+.-]*:\/\/[^/]*\*/i.test(uri)) return "wildcard_host";
  let u: URL;
  try {
    u = new URL(uri.replace(/\*$/, ""));
  } catch {
    return "not_absolute";
  }
  if (u.protocol !== "https:" && u.protocol !== "http:") return "bad_scheme";
  if (uri.includes("#")) return "fragment";
  return null;
}

/** First bad URI, with why. */
export function firstBadUri(uris: string[]): { uri: string; error: UriError } | null {
  for (const uri of uris) {
    const error = uriError(uri);
    if (error) return { uri, error };
  }
  return null;
}

/**
 * Post-logout redirect URIs: Keycloak's `##`-separated list, where `+` means
 * "same as the redirect URIs". Empty = not sent.
 */
export function postLogoutError(raw: string): { uri: string; error: UriError } | null {
  const parts = raw
    .split("##")
    .map((s) => s.trim())
    .filter(Boolean);
  return firstBadUri(parts.filter((p) => p !== "+"));
}

export interface AppCreateValues {
  app: string;
  redirectUris: string;
  postLogout: string;
}

export interface AppCreateBody {
  app: string;
  redirect_uris: string[];
  post_logout_redirect_uris?: string;
}

export type AppCreateCheck =
  | { ok: true; body: AppCreateBody; clientId: string }
  | {
      ok: false;
      field: "app" | "redirectUris" | "postLogout";
      error: AppPartError | UriError | "required";
      uri?: string;
    };

/** Validate the whole form and build the request body (app part only). */
export function buildCreateBody(values: AppCreateValues, slug: string): AppCreateCheck {
  const appErr = appPartError(values.app, slug);
  if (appErr) return { ok: false, field: "app", error: appErr };
  const uris = parseUriList(values.redirectUris);
  if (uris.length === 0) return { ok: false, field: "redirectUris", error: "required" };
  const bad = firstBadUri(uris);
  if (bad) return { ok: false, field: "redirectUris", error: bad.error, uri: bad.uri };
  const post = values.postLogout.trim();
  const badPost = post ? postLogoutError(post) : null;
  if (badPost) return { ok: false, field: "postLogout", error: badPost.error, uri: badPost.uri };
  const app = normaliseApp(values.app);
  const body: AppCreateBody = { app, redirect_uris: uris };
  if (post) body.post_logout_redirect_uris = post;
  return { ok: true, body, clientId: composeClientId(slug, app) };
}
