/**
 * The wrapper's OIDC gateway contract (reference `src/app/api/oauth2.py`):
 *   GET  /oauth2/authorize   response_type=code, client_id, redirect_uri, scope, state, nonce,
 *                            code_challenge, code_challenge_method=S256 (only), [acr_values]
 *   POST /oauth2/token       authorization_code (+ code_verifier) | refresh_token (rotating)
 *   GET  /oauth2/logout      client_id (required with post_logout_redirect_uri),
 *                            post_logout_redirect_uri, id_token_hint, state
 */
import { decodeJwtPayload, type JwtPayload } from "./jwt";
import { GorssoAuthError, type TokenSet } from "./types";

export const DEFAULT_SCOPES = ["openid", "profile", "email"];

export interface AuthorizeParams {
  baseUrl: string;
  clientId: string;
  redirectUri: string;
  scopes: string[];
  state: string;
  nonce: string;
  codeChallenge: string;
  acrValues?: string;
}

export function trimSlash(s: string): string {
  return s.replace(/\/+$/, "");
}

export function buildAuthorizeUrl(p: AuthorizeParams): string {
  const q = new URLSearchParams({
    response_type: "code",
    client_id: p.clientId,
    redirect_uri: p.redirectUri,
    scope: p.scopes.join(" "),
    state: p.state,
    nonce: p.nonce,
    code_challenge: p.codeChallenge,
    code_challenge_method: "S256",
  });
  if (p.acrValues) q.set("acr_values", p.acrValues);
  return `${trimSlash(p.baseUrl)}/oauth2/authorize?${q.toString()}`;
}

export interface LogoutParams {
  baseUrl: string;
  clientId: string;
  postLogoutRedirectUri?: string;
  idTokenHint?: string;
  state?: string;
}

export function buildLogoutUrl(p: LogoutParams): string {
  // client_id always: the wrapper refuses post_logout_redirect_uri without it.
  const q = new URLSearchParams({ client_id: p.clientId });
  if (p.postLogoutRedirectUri) q.set("post_logout_redirect_uri", p.postLogoutRedirectUri);
  if (p.idTokenHint) q.set("id_token_hint", p.idTokenHint);
  if (p.state) q.set("state", p.state);
  return `${trimSlash(p.baseUrl)}/oauth2/logout?${q.toString()}`;
}

export interface CallbackParams {
  code: string | null;
  state: string | null;
  error: string | null;
  errorDescription: string | null;
}

export function parseCallback(url: string): CallbackParams {
  const u = new URL(url);
  const q = u.searchParams;
  return {
    code: q.get("code"),
    state: q.get("state"),
    error: q.get("error"),
    errorDescription: q.get("error_description"),
  };
}

/** Is `url` the redirect URI coming back with a code or an error? */
export function isCallbackUrl(url: string, redirectUri: string): boolean {
  try {
    const u = new URL(url);
    const r = new URL(redirectUri);
    if (u.origin !== r.origin || trimSlash(u.pathname) !== trimSlash(r.pathname)) return false;
    return u.searchParams.has("state") && (u.searchParams.has("code") || u.searchParams.has("error"));
  } catch {
    return false;
  }
}

interface TokenResponse {
  access_token?: string;
  refresh_token?: string;
  id_token?: string;
  expires_in?: number;
}

async function errorOf(res: Response, fallback: string): Promise<GorssoAuthError> {
  let code = fallback;
  let message = `${fallback} (${res.status})`;
  try {
    const body = (await res.json()) as Record<string, unknown>;
    // Platform envelope {error, message} | OAuth {error, error_description} | FastAPI {detail}
    if (typeof body.error === "string") code = body.error;
    const detail = body.message ?? body.error_description ?? body.detail;
    if (typeof detail === "string") message = detail;
    else if (detail && typeof detail === "object" && typeof (detail as { error?: unknown }).error === "string") {
      code = (detail as { error: string }).error;
    }
  } catch {
    /* not JSON */
  }
  return new GorssoAuthError(code, message, res.status);
}

export async function postToken(
  fetchFn: typeof fetch,
  baseUrl: string,
  form: Record<string, string>,
  failureCode: string,
  previous?: TokenSet,
): Promise<TokenSet> {
  const res = await fetchFn(`${trimSlash(baseUrl)}/oauth2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
    body: new URLSearchParams(form).toString(),
    credentials: "omit",
  });
  if (!res.ok) throw await errorOf(res, failureCode);
  const body = (await res.json()) as TokenResponse;
  if (!body.access_token) throw new GorssoAuthError(failureCode, "token response has no access_token");
  const expiresIn = typeof body.expires_in === "number" ? body.expires_in : expiryFromJwt(body.access_token);
  return {
    accessToken: body.access_token,
    // Refresh tokens rotate: always keep the NEW one.
    refreshToken: body.refresh_token ?? previous?.refreshToken,
    idToken: body.id_token ?? previous?.idToken,
    expiresAt: Date.now() + expiresIn * 1000,
  };
}

function expiryFromJwt(token: string): number {
  try {
    const exp = decodeJwtPayload(token).exp;
    if (typeof exp === "number") return Math.max(0, exp - Math.floor(Date.now() / 1000));
  } catch {
    /* opaque */
  }
  return 300;
}

/**
 * id_token checks for a token received directly from the token endpoint over TLS (OIDC Core
 * §3.1.3.7: TLS server validation may stand in for the signature check here): nonce, aud, exp,
 * and optionally iss.
 */
export function validateIdToken(
  idToken: string,
  expected: { nonce: string; clientId: string; issuer?: string; nowSeconds?: number },
): JwtPayload {
  let p: JwtPayload;
  try {
    p = decodeJwtPayload(idToken);
  } catch {
    throw new GorssoAuthError("invalid_id_token", "id_token is not a JWT");
  }
  if (p.nonce !== expected.nonce) throw new GorssoAuthError("nonce_mismatch", "id_token nonce does not match the login request");
  const aud = p.aud;
  const audOk = Array.isArray(aud) ? aud.includes(expected.clientId) : aud === expected.clientId;
  if (!audOk) throw new GorssoAuthError("invalid_id_token", "id_token audience is not this client");
  const now = expected.nowSeconds ?? Math.floor(Date.now() / 1000);
  if (typeof p.exp === "number" && p.exp + 60 < now) throw new GorssoAuthError("invalid_id_token", "id_token has expired");
  if (expected.issuer && p.iss !== expected.issuer) throw new GorssoAuthError("invalid_id_token", "id_token issuer mismatch");
  return p;
}
