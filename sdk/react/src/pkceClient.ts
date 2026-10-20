/**
 * Public-client authorization-code + PKCE (S256) through the wrapper's /oauth2 gateway.
 * Tokens are held in memory only — never localStorage/sessionStorage — so a reload means a new
 * (usually silent, SSO-session-backed) login round trip.
 */
import {
  ANONYMOUS,
  StateHolder,
  currentLocation,
  defaultNavigate,
  defaultRedirectCallback,
  safeReturnTo,
  type AuthClient,
} from "./client";
import { claimString, clientRoles, decodeJwtPayload, realmRoles, type JwtPayload } from "./jwt";
import {
  DEFAULT_SCOPES,
  buildAuthorizeUrl,
  buildLogoutUrl,
  isCallbackUrl,
  parseCallback,
  postToken,
  validateIdToken,
} from "./oidc";
import { codeChallengeS256, generateCodeVerifier, randomString } from "./pkce";
import { defaultTransactionStore } from "./transaction";
import { GorssoAuthError, type AuthState, type GorssoConfig, type Session, type TokenSet, type TransactionStore } from "./types";

export class PkceAuthClient implements AuthClient {
  private readonly state = new StateHolder();
  private tokens: TokenSet | null = null;
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;
  private refreshing: Promise<TokenSet | null> | null = null;
  private callback: Promise<void> | null = null;
  private readonly fetchFn: typeof fetch;
  private readonly store: TransactionStore;
  private readonly navigate: (url: string) => void;
  private readonly baseUrl: string;
  private readonly clientId: string;
  private readonly redirectUri: string;
  private readonly scopes: string[];

  constructor(private readonly config: GorssoConfig) {
    if (!config.baseUrl || !config.clientId || !config.redirectUri) {
      throw new GorssoAuthError("misconfigured", "pkce mode needs baseUrl, clientId and redirectUri");
    }
    this.baseUrl = config.baseUrl;
    this.clientId = config.clientId;
    this.redirectUri = config.redirectUri;
    this.scopes = config.scopes ?? DEFAULT_SCOPES;
    this.fetchFn = config.fetch ?? ((...a) => globalThis.fetch(...a));
    this.store = config.transactionStore ?? defaultTransactionStore();
    this.navigate = config.navigate ?? defaultNavigate;
  }

  getState(): AuthState {
    return this.state.get();
  }

  subscribe(listener: () => void): () => void {
    return this.state.subscribe(listener);
  }

  async init(): Promise<void> {
    const href = typeof window === "undefined" ? "" : window.location.href;
    if (href && isCallbackUrl(href, this.redirectUri)) {
      await this.handleRedirectCallback(href);
      return;
    }
    if (!this.tokens) this.state.set({ status: "unauthenticated", session: ANONYMOUS, error: null });
  }

  async login(returnTo?: string): Promise<void> {
    const state = randomString(32);
    const nonce = randomString(32);
    const codeVerifier = generateCodeVerifier();
    const codeChallenge = await codeChallengeS256(codeVerifier);
    this.store.save(state, {
      codeVerifier,
      nonce,
      returnTo: safeReturnTo(returnTo ?? currentLocation()),
      redirectUri: this.redirectUri,
      createdAt: Date.now(),
    });
    this.navigate(
      buildAuthorizeUrl({
        baseUrl: this.baseUrl,
        clientId: this.clientId,
        redirectUri: this.redirectUri,
        scopes: this.scopes,
        state,
        nonce,
        codeChallenge,
        acrValues: this.config.acrValues,
      }),
    );
  }

  /** Idempotent per page load (React StrictMode runs effects twice). */
  handleRedirectCallback(url: string): Promise<void> {
    if (!this.callback) this.callback = this.doCallback(url);
    return this.callback;
  }

  private async doCallback(url: string): Promise<void> {
    const cb = parseCallback(url);
    // The transaction is consumed whatever happens next: a state is good for one callback.
    const tx = cb.state ? this.store.take(cb.state) : null;
    try {
      if (!tx) throw new GorssoAuthError("state_mismatch", "Unknown, expired or already used login state");
      if (cb.error) throw new GorssoAuthError(cb.error, cb.errorDescription ?? cb.error);
      if (!cb.code) throw new GorssoAuthError("token_exchange_failed", "callback has no code");
      const tokens = await postToken(
        this.fetchFn,
        this.baseUrl,
        {
          grant_type: "authorization_code",
          code: cb.code,
          redirect_uri: tx.redirectUri,
          client_id: this.clientId,
          code_verifier: tx.codeVerifier,
        },
        "token_exchange_failed",
      );
      if (this.scopes.includes("openid")) {
        if (!tokens.idToken) throw new GorssoAuthError("missing_id_token", "token response has no id_token");
        validateIdToken(tokens.idToken, { nonce: tx.nonce, clientId: this.clientId, issuer: this.config.issuer });
      }
      this.setTokens(tokens);
      (this.config.onRedirectCallback ?? defaultRedirectCallback)(tx.returnTo);
    } catch (e) {
      this.clear(toAuthError(e, "token_exchange_failed"));
    }
  }

  async getAccessToken(): Promise<string | null> {
    if (!this.tokens) return null;
    const leeway = (this.config.refreshLeewaySeconds ?? 30) * 1000;
    if (this.tokens.expiresAt - leeway > Date.now()) return this.tokens.accessToken;
    const t = await this.refresh();
    return t?.accessToken ?? null;
  }

  /** Rotate: send the current refresh token, keep the new one. Concurrent calls share one request. */
  refresh(): Promise<TokenSet | null> {
    if (!this.tokens?.refreshToken) return Promise.resolve(null);
    if (!this.refreshing) {
      const previous = this.tokens;
      this.refreshing = postToken(
        this.fetchFn,
        this.baseUrl,
        { grant_type: "refresh_token", refresh_token: previous.refreshToken!, client_id: this.clientId },
        "refresh_failed",
        previous,
      )
        .then((t) => {
          this.setTokens(t);
          return t;
        })
        .catch((e: unknown) => {
          this.clear(toAuthError(e, "refresh_failed"));
          return null;
        })
        .finally(() => {
          this.refreshing = null;
        });
    }
    return this.refreshing;
  }

  async logout(): Promise<void> {
    const idToken = this.tokens?.idToken;
    this.clear(null);
    this.navigate(
      buildLogoutUrl({
        baseUrl: this.baseUrl,
        clientId: this.clientId,
        postLogoutRedirectUri: this.config.postLogoutRedirectUri ?? `${new URL(this.redirectUri).origin}/`,
        idTokenHint: idToken,
      }),
    );
  }

  dispose(): void {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.refreshTimer = null;
  }

  /** Test seam: tokens currently held (memory only). */
  peekTokens(): TokenSet | null {
    return this.tokens;
  }

  private setTokens(tokens: TokenSet): void {
    this.tokens = tokens;
    this.state.set({ status: "authenticated", session: sessionFromTokens(tokens, this.clientId), error: null });
    this.scheduleRefresh();
  }

  private clear(error: GorssoAuthError | null): void {
    this.dispose();
    this.tokens = null;
    this.state.set({ status: error ? "error" : "unauthenticated", session: ANONYMOUS, error });
  }

  private scheduleRefresh(): void {
    this.dispose();
    if (!this.tokens?.refreshToken) return;
    const leeway = (this.config.refreshLeewaySeconds ?? 30) * 1000;
    const delay = Math.max(1000, this.tokens.expiresAt - leeway - Date.now());
    this.refreshTimer = setTimeout(() => void this.refresh(), delay);
  }
}

function toAuthError(e: unknown, fallback: string): GorssoAuthError {
  return e instanceof GorssoAuthError ? e : new GorssoAuthError(fallback, e instanceof Error ? e.message : String(e));
}

/** Access-token claims (roles, join_key) + id_token claims (name, email). */
export function sessionFromTokens(tokens: TokenSet, clientId: string): Session {
  let at: JwtPayload = {};
  let id: JwtPayload = {};
  try {
    at = decodeJwtPayload(tokens.accessToken);
  } catch {
    /* opaque access token */
  }
  try {
    if (tokens.idToken) id = decodeJwtPayload(tokens.idToken);
  } catch {
    /* ignore */
  }
  const pick = (k: string) => claimString(at, k) ?? claimString(id, k);
  return {
    authenticated: true,
    username: pick("preferred_username"),
    name: pick("name"),
    email: pick("email"),
    roles: realmRoles(at),
    clientRoles: clientRoles(at, clientId),
    joinKey: pick("join_key"),
    institutionSlug: pick("institution")?.toLowerCase() ?? null,
  };
}
