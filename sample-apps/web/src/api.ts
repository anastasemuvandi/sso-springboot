import { useAuthFetch } from "@gorsso/react";
import { useEffect, useState } from "react";
import { apiBase } from "./config";

export type Loadable<T> =
  | { state: "loading" }
  | { state: "ok"; data: T }
  | { state: "error"; status: number; error: string };

/** GET an API path with the caller's bearer token (auto-refreshed by the SDK). */
export function useApi<T>(path: string): Loadable<T> {
  const authFetch = useAuthFetch();
  const [result, setResult] = useState<Loadable<T>>({ state: "loading" });
  useEffect(() => {
    let cancelled = false;
    authFetch(`${apiBase}${path}`, { headers: { Accept: "application/json" } })
      .then(async (res) => {
        if (cancelled) return;
        if (!res.ok) {
          const body = (await res.json().catch(() => ({}))) as { error?: string };
          setResult({ state: "error", status: res.status, error: body.error ?? res.statusText });
          return;
        }
        setResult({ state: "ok", data: (await res.json()) as T });
      })
      .catch((e: unknown) => !cancelled && setResult({ state: "error", status: 0, error: String(e) }));
    return () => {
      cancelled = true;
    };
  }, [authFetch, path]);
  return result;
}
