import { useCallback, useContext, useMemo, useSyncExternalStore } from "react";
import type { AuthClient } from "./client";
import { GorssoContext } from "./context";
import { hasAnyRole, hasRole } from "./roles";
import type { AuthState, AuthStatus, GorssoAuthError, Session } from "./types";

export function useGorssoClient(): AuthClient {
  const client = useContext(GorssoContext);
  if (!client) throw new Error("GoRSSO hooks must be used inside <GorssoProvider>");
  return client;
}

function useAuthState(): AuthState {
  const client = useGorssoClient();
  return useSyncExternalStore(
    useCallback((l: () => void) => client.subscribe(l), [client]),
    () => client.getState(),
    () => client.getState(),
  );
}

export interface UseAuth {
  status: AuthStatus;
  isLoading: boolean;
  isAuthenticated: boolean;
  session: Session;
  error: GorssoAuthError | null;
  login: (returnTo?: string) => Promise<void>;
  logout: () => Promise<void>;
  /** pkce: current (auto-refreshed) access token; bff: `null`. */
  getAccessToken: () => Promise<string | null>;
  hasRole: (role: string) => boolean;
  hasAnyRole: (roles: string[]) => boolean;
}

export function useAuth(): UseAuth {
  const client = useGorssoClient();
  const state = useAuthState();
  return useMemo(
    () => ({
      status: state.status,
      isLoading: state.status === "loading",
      isAuthenticated: state.status === "authenticated" && state.session.authenticated,
      session: state.session,
      error: state.error,
      login: (returnTo?: string) => client.login(returnTo),
      logout: () => client.logout(),
      getAccessToken: () => client.getAccessToken(),
      hasRole: (role: string) => hasRole(state.session, role),
      hasAnyRole: (roles: string[]) => hasAnyRole(state.session, roles),
    }),
    [client, state],
  );
}

/**
 * The session in the BFF's shape, whatever the mode. In `bff` mode it is `GET /bff/session`;
 * in `pkce` mode it is derived from the in-memory tokens.
 */
export function useSession(): { session: Session; status: AuthStatus; error: GorssoAuthError | null } {
  const state = useAuthState();
  return { session: state.session, status: state.status, error: state.error };
}

/** The caller's `join_key` (nid | phone | email value, per the app's join column), or null. */
export function useJoinKey(): string | null {
  return useAuthState().session.joinKey ?? null;
}

/**
 * `fetch` that authenticates API calls: a fresh bearer token in pkce mode, cookies in bff mode.
 */
export function useAuthFetch(): typeof fetch {
  const client = useGorssoClient();
  return useCallback<typeof fetch>(
    async (input, init) => {
      const token = await client.getAccessToken();
      const headers = new Headers(init?.headers);
      if (token) headers.set("Authorization", `Bearer ${token}`);
      return fetch(input, { ...init, headers, credentials: token ? init?.credentials : "include" });
    },
    [client],
  );
}
