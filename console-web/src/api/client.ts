/**
 * The typed API client. Everything the console reads or writes goes through
 * here, to the BFF:
 *
 *   browser ──cookie──► BFF `/api/**` ──Bearer (caller's own)──► wrapper
 *
 * - base `/api`: the BFF strips it and relays the rest to the wrapper, so the
 *   paths below are exactly the wrapper's (and the generated `schema.d.ts`'s).
 * - `credentials: "include"` and `X-XSRF-TOKEN` on mutating requests.
 * - errors become `ApiError` (wrapper envelope `{error, message, details}`).
 * - a 401 means the BFF could not refresh the session: we re-check
 *   `/bff/session` and send the browser to `/bff/login?returnTo=…`.
 *
 * Usage (see README): `unwrap(api.GET("/client/clients"))` for typed
 * responses, `unwrapAs<T>(api.GET(...))` where the contract has no response
 * schema (many reference endpoints return untyped JSON).
 */
import createClient, { type Middleware } from "openapi-fetch";

import type { paths } from "./schema";
import type { Present } from "./types";
import { ApiError, toApiError } from "./errors";
import { withXsrf } from "./csrf";
import { handleUnauthorized } from "@/auth/unauthorized";

export const API_BASE = "/api";

/** Absolute URL on this origin (Request() in tests needs absolute URLs). */
export function sameOrigin(path: string): string {
  const origin = typeof window !== "undefined" && window.location ? window.location.origin : "http://localhost";
  return `${origin}${path}`;
}

const middleware: Middleware = {
  onRequest({ request }) {
    withXsrf(request.method, request.headers);
    if (!request.headers.has("Accept")) request.headers.set("Accept", "application/json");
    return request;
  },
  async onResponse({ response }) {
    if (response.status === 401) {
      // Fire-and-forget: re-check the session and redirect if it is gone.
      // The caller still receives the ApiError(401).
      void handleUnauthorized();
    }
    return response;
  },
};

export const api = createClient<paths>({
  baseUrl: sameOrigin(API_BASE),
  credentials: "include",
  // Resolve fetch at call time so test doubles (MSW) installed later are used.
  fetch: (input: Request) => globalThis.fetch(input),
});
api.use(middleware);

type FetchResult<D> = { data?: D; error?: unknown; response: Response };

/**
 * Await an openapi-fetch call; return its typed data (every key present — see
 * `Present` in ./types) or throw `ApiError`.
 */
export async function unwrap<D>(p: Promise<FetchResult<D>>): Promise<Present<D>> {
  let res: FetchResult<D>;
  try {
    res = await p;
  } catch (e) {
    throw new ApiError(0, "network_error", e instanceof Error ? e.message : String(e));
  }
  if (!res.response.ok) throw toApiError(res.response.status, res.error, res.response.statusText);
  return res.data as Present<D>;
}

/** Like `unwrap`, for endpoints whose response the contract leaves untyped. */
export async function unwrapAs<T>(p: Promise<FetchResult<unknown>>): Promise<T> {
  return (await unwrap(p)) as T;
}

/** Raw fetch to `/api/<path>` with the same cookie/XSRF/401 handling — downloads, uploads. */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = withXsrf(method, new Headers(init.headers));
  let res: Response;
  try {
    res = await fetch(sameOrigin(`${API_BASE}${path}`), { ...init, method, headers, credentials: "include" });
  } catch (e) {
    throw new ApiError(0, "network_error", e instanceof Error ? e.message : String(e));
  }
  if (res.status === 401) void handleUnauthorized();
  if (!res.ok) {
    const text = await res.text();
    let body: unknown = text;
    try {
      body = text ? JSON.parse(text) : undefined;
    } catch {
      /* keep text */
    }
    throw toApiError(res.status, body, res.statusText);
  }
  return res;
}

/** Download a file from the API and hand it to the browser (CSV/PDF exports). */
export async function downloadFile(path: string, fallbackName: string): Promise<void> {
  const res = await apiFetch(path);
  const blob = await res.blob();
  const cd = res.headers.get("Content-Disposition") ?? "";
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(cd);
  const name = match?.[1] ? decodeURIComponent(match[1]) : fallbackName;
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Build `?a=1&b=2`, dropping undefined/null/"" values. */
export function toQuery(params: Record<string, string | number | boolean | null | undefined>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== "") sp.set(k, String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : "";
}
