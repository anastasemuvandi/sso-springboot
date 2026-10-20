import type { AuthState, Session } from "./types";

/** The mode-independent client behind `<GorssoProvider>`. */
export interface AuthClient {
  getState(): AuthState;
  subscribe(listener: () => void): () => void;
  /** pkce: finish a login callback if the URL is one; bff: load the session. */
  init(): Promise<void>;
  /** Start login; `returnTo` is where the app lands afterwards (default: current location). */
  login(returnTo?: string): Promise<void>;
  logout(): Promise<void>;
  /** pkce: a fresh access token (refreshing if needed); bff: always `null` (cookies do it). */
  getAccessToken(): Promise<string | null>;
  dispose(): void;
}

export const ANONYMOUS: Session = { authenticated: false };

export class StateHolder {
  private state: AuthState = { status: "loading", session: ANONYMOUS, error: null };
  private readonly listeners = new Set<() => void>();

  get(): AuthState {
    return this.state;
  }

  set(next: AuthState): void {
    this.state = next;
    this.listeners.forEach((l) => l());
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }
}

export function currentLocation(): string {
  if (typeof window === "undefined") return "/";
  const { pathname, search, hash } = window.location;
  return `${pathname}${search}${hash}`;
}

export function defaultNavigate(url: string): void {
  window.location.assign(url);
}

/**
 * After the callback: drop `?code&state` from the address bar and go to `returnTo`. The
 * `popstate` event makes React Router v6's BrowserRouter pick up the new location.
 */
export function defaultRedirectCallback(returnTo: string): void {
  window.history.replaceState({}, "", returnTo || "/");
  window.dispatchEvent(new PopStateEvent("popstate"));
}

/** Only same-origin relative paths are honoured as returnTo (no open redirects). */
export function safeReturnTo(returnTo: string | undefined | null): string {
  if (!returnTo || !returnTo.startsWith("/") || returnTo.startsWith("//") || returnTo.startsWith("/\\")) return "/";
  return returnTo;
}
