/**
 * The institution console's context: who the caller is in this institution
 * (`/me`) and what their delegated tier lets them do (`/permissions`).
 *
 * Render from `permissions`, never from role names. Everything decided here
 * is presentation — the wrapper re-checks the capability on every call.
 */
import { createContext, useContext, useMemo, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";

import type { ApiError } from "@/api/errors";
import { meQuery, permissionsQuery, type InstitutionScope } from "./api";
import type { Capability, InstitutionContext, InstitutionPermissions } from "./types";

export interface InstitutionState {
  ctx: InstitutionContext;
  perms: InstitutionPermissions;
  /** Pass to every /institution-admin call. */
  scope: InstitutionScope;
  /** "?institution=…" or "". */
  qs: string;
  can: (c: Capability) => boolean;
  /** A console path carrying the institution scope (and extra params). */
  path: (p: string, extra?: Record<string, string | undefined>) => string;
}

export type InstitutionStatus =
  | { kind: "loading" }
  | { kind: "ready"; value: InstitutionState }
  /** 503: delegated-permission templates not loaded — the surface fails closed. */
  | { kind: "config_unavailable"; detail: string }
  /** 403/400: no institution grant (or a platform admin who named none). */
  | { kind: "not_admin"; detail: string }
  | { kind: "error"; error: unknown };

const Ctx = createContext<InstitutionStatus>({ kind: "loading" });

/** Build a path keeping `?institution=` and adding/removing params. */
export function hrefWith(path: string, scopeQs: string, extra: Record<string, string | undefined> = {}): string {
  const params = new URLSearchParams(scopeQs.replace(/^\?/, ""));
  for (const [k, v] of Object.entries(extra)) {
    if (v === undefined || v === "") params.delete(k);
    else params.set(k, v);
  }
  const s = params.toString();
  return s ? `${path}?${s}` : path;
}

export function statusFromErrors(err: unknown): InstitutionStatus {
  const e = err as ApiError | undefined;
  if (e && typeof e === "object" && "status" in e) {
    if (e.status === 503) return { kind: "config_unavailable", detail: e.message };
    if (e.status === 403 || e.status === 400) return { kind: "not_admin", detail: e.message };
  }
  return { kind: "error", error: err };
}

export function InstitutionProvider({ children }: { children: ReactNode }) {
  const [params] = useSearchParams();
  const requested = params.get("institution") ?? undefined;
  const scope = useMemo<InstitutionScope>(() => (requested ? { institution: requested } : {}), [requested]);
  const me = useQuery(meQuery(scope));
  const perms = useQuery(permissionsQuery(scope));

  const status = useMemo<InstitutionStatus>(() => {
    if (me.isError) return statusFromErrors(me.error);
    if (perms.isError) return statusFromErrors(perms.error);
    if (!me.data || !perms.data) return { kind: "loading" };
    const ctx = { ...me.data, capabilities: me.data.capabilities ?? [] };
    const p = { ...perms.data, capabilities: perms.data.capabilities ?? [], templates: perms.data.templates ?? [] };
    // Carry the param only when it matters (platform admin, or several institutions).
    const needsScope = ctx.is_platform_admin || ctx.administered.length > 1;
    const effective = needsScope ? (requested ?? ctx.institution) : undefined;
    const effScope: InstitutionScope = effective ? { institution: effective } : {};
    const qs = effective ? `?institution=${encodeURIComponent(effective)}` : "";
    return {
      kind: "ready",
      value: {
        ctx,
        perms: p,
        scope: effScope,
        qs,
        can: (c) => p.capabilities.includes(c),
        path: (path, extra) => hrefWith(path, qs, extra),
      },
    };
  }, [me.isError, me.error, me.data, perms.isError, perms.error, perms.data, requested]);

  return <Ctx.Provider value={status}>{children}</Ctx.Provider>;
}

export function useInstitutionStatus(): InstitutionStatus {
  return useContext(Ctx);
}

/** The resolved context. Only call below <InstitutionGate> (it renders nothing until ready). */
export function useInstitution(): InstitutionState {
  const s = useContext(Ctx);
  if (s.kind !== "ready") throw new Error("useInstitution() used outside a ready <InstitutionGate>");
  return s.value;
}

/** For tests: provide a resolved context directly. */
export function InstitutionTestProvider({ value, children }: { value: InstitutionState; children: ReactNode }) {
  return <Ctx.Provider value={{ kind: "ready", value }}>{children}</Ctx.Provider>;
}
