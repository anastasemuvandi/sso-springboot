/**
 * The BFF (Spring Security, CookieCsrfTokenRepository) sets a JS-readable
 * `XSRF-TOKEN` cookie; every state-changing request echoes it in
 * `X-XSRF-TOKEN`. This is the only cookie the SPA reads — the session cookie
 * is HttpOnly and the SPA never sees a token.
 */
export const XSRF_COOKIE = "XSRF-TOKEN";
export const XSRF_HEADER = "X-XSRF-TOKEN";

export function readCookie(name: string, cookieString: string = typeof document === "undefined" ? "" : document.cookie): string | null {
  for (const part of cookieString.split(";")) {
    const [k, ...v] = part.trim().split("=");
    if (k === name) return decodeURIComponent(v.join("="));
  }
  return null;
}

export const MUTATING_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/** Adds `X-XSRF-TOKEN` to `headers` when the method mutates and the cookie exists. */
export function withXsrf(method: string, headers: Headers): Headers {
  if (MUTATING_METHODS.has(method.toUpperCase())) {
    const token = readCookie(XSRF_COOKIE);
    if (token) headers.set(XSRF_HEADER, token);
  }
  return headers;
}
