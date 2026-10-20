/**
 * BFF mode: the backend-for-frontend holds the tokens; the SPA sees
 *   GET  {bffBase}/bff/session            → Session (401 = anonymous)
 *   GET  {bffBase}/bff/login?returnTo=…   (full-page; the BFF runs PKCE through the wrapper)
 *   POST {bffBase}/bff/logout             (cookies + Spring's XSRF-TOKEN double-submit header)
 */
import { ANONYMOUS, StateHolder, currentLocation, defaultNavigate, safeReturnTo, type AuthClient } from "./client";
import { trimSlash } from "./oidc";
import { GorssoAuthError, type AuthState, type GorssoConfig, type Session } from "./types";

export class BffAuthClient implements AuthClient {
  private readonly state = new StateHolder();
  private readonly base: string;
  private readonly fetchFn: typeof fetch;
  private readonly navigate: (url: string) => void;

  constructor(config: GorssoConfig) {
    this.base = trimSlash(config.bffBase ?? "");
    this.fetchFn = config.fetch ?? ((...a) => globalThis.fetch(...a));
    this.navigate = config.navigate ?? defaultNavigate;
  }

  getState(): AuthState {
    return this.state.get();
  }

  subscribe(listener: () => void): () => void {
    return this.state.subscribe(listener);
  }

  async init(): Promise<void> {
    await this.reload();
  }

  /** Re-read `/bff/session` (e.g. after a 401 from the API). */
  async reload(): Promise<Session> {
    try {
      const res = await this.fetchFn(`${this.base}/bff/session`, {
        credentials: "include",
        headers: { Accept: "application/json" },
      });
      if (res.status === 401) {
        this.state.set({ status: "unauthenticated", session: ANONYMOUS, error: null });
        return ANONYMOUS;
      }
      if (!res.ok) throw new GorssoAuthError("session_failed", `GET /bff/session failed (${res.status})`, res.status);
      const session = normaliseSession((await res.json()) as Record<string, unknown>);
      this.state.set({ status: session.authenticated ? "authenticated" : "unauthenticated", session, error: null });
      return session;
    } catch (e) {
      const err = e instanceof GorssoAuthError ? e : new GorssoAuthError("session_failed", String(e));
      this.state.set({ status: "error", session: ANONYMOUS, error: err });
      return ANONYMOUS;
    }
  }

  loginUrl(returnTo?: string): string {
    return `${this.base}/bff/login?returnTo=${encodeURIComponent(safeReturnTo(returnTo ?? currentLocation()))}`;
  }

  async login(returnTo?: string): Promise<void> {
    this.navigate(this.loginUrl(returnTo));
  }

  async logout(): Promise<void> {
    const headers: Record<string, string> = { Accept: "application/json" };
    const xsrf = readCookie("XSRF-TOKEN");
    if (xsrf) headers["X-XSRF-TOKEN"] = xsrf;
    let next = "/";
    try {
      const res = await this.fetchFn(`${this.base}/bff/logout`, { method: "POST", credentials: "include", headers });
      if (res.ok && (res.headers.get("content-type") ?? "").includes("application/json")) {
        const body = (await res.json()) as Record<string, unknown>;
        const url = body.logoutUrl ?? body.logout_url ?? body.redirect;
        if (typeof url === "string" && url) next = url;
      }
    } finally {
      this.state.set({ status: "unauthenticated", session: ANONYMOUS, error: null });
    }
    this.navigate(next);
  }

  async getAccessToken(): Promise<string | null> {
    return null;
  }

  dispose(): void {
    /* nothing */
  }
}

/** Accept camelCase or snake_case from the BFF. */
export function normaliseSession(body: Record<string, unknown>): Session {
  const str = (v: unknown) => (typeof v === "string" && v ? v : null);
  const arr = (v: unknown) => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : []);
  return {
    ...body,
    authenticated: Boolean(body.authenticated),
    username: str(body.username ?? body.preferred_username),
    name: str(body.name),
    email: str(body.email),
    roles: arr(body.roles),
    clientRoles: arr(body.clientRoles ?? body.client_roles),
    joinKey: str(body.joinKey ?? body.join_key),
    institutionSlug: str(body.institutionSlug ?? body.institution_slug ?? body.institution),
  };
}

function readCookie(name: string): string | null {
  if (typeof document === "undefined") return null;
  const hit = document.cookie.split("; ").find((c) => c.startsWith(`${name}=`));
  return hit ? decodeURIComponent(hit.slice(name.length + 1)) : null;
}
