/**
 * The console's session, as the BFF reports it. The SPA never holds a token:
 * `GET /bff/session` answers from the BFF's server-side session.
 *
 * Everything here is COSMETIC. The flags decide which console to render and
 * which links to show; the wrapper enforces every rule on every call.
 */
import { queryOptions, useQuery } from "@tanstack/react-query";

export interface Session {
  authenticated: boolean;
  username?: string | null;
  name?: string | null;
  roles?: string[];
  platformAdmin?: boolean;
  institutionAdmin?: boolean;
  institutionSlug?: string | null;
}

export const SESSION_URL = "/bff/session";
export const LOGIN_URL = "/bff/login";
export const LOGOUT_URL = "/bff/logout";

export async function fetchSession(): Promise<Session> {
  const origin = typeof window !== "undefined" ? window.location.origin : "http://localhost";
  const res = await fetch(`${origin}${SESSION_URL}`, { credentials: "include", headers: { Accept: "application/json" } });
  if (res.status === 401) return { authenticated: false };
  if (!res.ok) throw new Error(`GET ${SESSION_URL} failed (${res.status})`);
  const body = (await res.json()) as Session;
  return { ...body, authenticated: Boolean(body.authenticated) };
}

export const sessionKeys = { all: ["session"] as const };

export const sessionQuery = queryOptions({
  queryKey: sessionKeys.all,
  queryFn: fetchSession,
  staleTime: 60_000,
  retry: false,
});

export function useSession(): Session {
  const { data } = useQuery(sessionQuery);
  // Route loaders ensure the session before any guarded page renders.
  return data ?? { authenticated: false };
}

/** `/bff/login?returnTo=<path>` — where the BFF starts PKCE through the wrapper. */
export function loginUrl(returnTo: string): string {
  return `${LOGIN_URL}?returnTo=${encodeURIComponent(returnTo || "/")}`;
}

/** Which console a session belongs in. */
export function homeFor(session: Session): "/dashboard" | "/institution" | "/no-access" {
  if (session.platformAdmin) return "/dashboard";
  if (session.institutionAdmin) return "/institution";
  return "/no-access";
}
