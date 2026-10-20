/** How the app talks to GoRSSO. */
export type GorssoMode = "pkce" | "bff";

/** Where the SPA should land after login, and how to get there. */
export type RedirectCallback = (returnTo: string) => void;

export interface GorssoConfig {
  /**
   * `pkce`: the SPA is a public OIDC client and runs the authorization-code + PKCE (S256) flow
   * through the wrapper's `/oauth2/*` gateway itself; tokens live in memory only.
   * `bff`: a backend-for-frontend holds the tokens; the SPA only sees `GET /bff/session`.
   */
  mode: GorssoMode;

  // ---- pkce ---------------------------------------------------------------
  /** Wrapper base URL (browser-facing), e.g. `http://localhost:8000`. No trailing slash. */
  baseUrl?: string;
  /** The app's client id, e.g. `minagri-kubaka-web`. */
  clientId?: string;
  /** Registered redirect URI (exact match), e.g. `http://localhost:5173/callback`. */
  redirectUri?: string;
  /** Requested scopes. Default `["openid", "profile", "email"]`. */
  scopes?: string[];
  /** Registered post-logout redirect URI. Default: the origin of `redirectUri` + `/`. */
  postLogoutRedirectUri?: string;
  /** Optional `acr_values` (`"2"` asks for password + TOTP). */
  acrValues?: string;
  /** Expected `iss` of the id_token (optional extra check). */
  issuer?: string;
  /** Refresh this many seconds before the access token expires. Default 30. */
  refreshLeewaySeconds?: number;

  // ---- bff ----------------------------------------------------------------
  /** BFF origin; `""` (default) means same origin. Endpoints: `/bff/session|login|logout`. */
  bffBase?: string;

  // ---- common -------------------------------------------------------------
  /** Called after a successful login callback. Default: `history.replaceState` + `popstate`. */
  onRedirectCallback?: RedirectCallback;
  /** Full-page navigation (login/logout). Default `window.location.assign`. */
  navigate?: (url: string) => void;
  /** `fetch` to use (tests, instrumentation). Default the global `fetch`. */
  fetch?: typeof fetch;
  /** Where the short-lived login transaction (state, verifier, nonce) waits out the redirect. */
  transactionStore?: TransactionStore;
}

/** The caller as the app sees it — same shape in both modes. */
export interface Session {
  authenticated: boolean;
  username?: string | null;
  name?: string | null;
  email?: string | null;
  /** Realm roles (e.g. `inst:minagri:staff`). */
  roles?: string[];
  /** Roles of this app's client (`resource_access.<clientId>.roles`). */
  clientRoles?: string[];
  /** The `join_key` claim (nid | phone | email value, per the client's join column). */
  joinKey?: string | null;
  /** The `institution` slug, when known. */
  institutionSlug?: string | null;
  /** Anything else the BFF reports. */
  [extra: string]: unknown;
}

export type AuthStatus = "loading" | "authenticated" | "unauthenticated" | "error";

export interface AuthState {
  status: AuthStatus;
  session: Session;
  error: GorssoAuthError | null;
}

export interface TokenSet {
  accessToken: string;
  refreshToken?: string;
  idToken?: string;
  /** epoch millis */
  expiresAt: number;
}

/** What waits in the transaction store between /authorize and the callback. */
export interface LoginTransaction {
  codeVerifier: string;
  nonce: string;
  returnTo: string;
  redirectUri: string;
  createdAt: number;
}

export interface TransactionStore {
  save(state: string, tx: LoginTransaction): void;
  /** Return and DELETE the transaction (single use). */
  take(state: string): LoginTransaction | null;
}

export type GorssoErrorCode =
  | "state_mismatch"
  | "nonce_mismatch"
  | "invalid_id_token"
  | "missing_id_token"
  | "token_exchange_failed"
  | "refresh_failed"
  | "session_failed"
  | "misconfigured"
  | string;

export class GorssoAuthError extends Error {
  readonly code: GorssoErrorCode;
  readonly status?: number;
  constructor(code: GorssoErrorCode, message: string, status?: number) {
    super(message);
    this.name = "GorssoAuthError";
    this.code = code;
    this.status = status;
  }
}
