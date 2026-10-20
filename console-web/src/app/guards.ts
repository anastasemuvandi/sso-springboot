/**
 * Route-level guards (React Router loaders).
 *
 * COSMETIC ONLY. They decide which console to render and spare an admin a page
 * of 403s; they are not a security boundary. The BFF holds the session and the
 * wrapper enforces the admin tier, the institution filter and every delegated
 * capability on every call, whatever this SPA renders.
 */
import { redirect, redirectDocument, type LoaderFunctionArgs } from "react-router-dom";
import type { QueryClient } from "@tanstack/react-query";

import { homeFor, loginUrl, sessionQuery, type Session } from "@/auth/session";

function pathOf(request: Request): string {
  const u = new URL(request.url);
  return u.pathname + u.search;
}

/** An authenticated session, or a full-page redirect to the BFF's login. */
export async function requireSession(qc: QueryClient, request: Request): Promise<Session> {
  const session = await qc.ensureQueryData(sessionQuery);
  if (!session.authenticated) {
    // /bff/login is served by the BFF, not this SPA: leave the document.
    throw redirectDocument(loginUrl(pathOf(request)));
  }
  return session;
}

export const guards = (qc: QueryClient) => ({
  /** `/`: send each admin to their own console. */
  home: async ({ request }: LoaderFunctionArgs) => {
    const s = await requireSession(qc, request);
    return redirect(homeFor(s));
  },
  authenticated: async ({ request }: LoaderFunctionArgs) => {
    await requireSession(qc, request);
    return null;
  },
  /** Platform area: platform-admin tier (super_admin | admin | system_admin). */
  platform: async ({ request }: LoaderFunctionArgs) => {
    const s = await requireSession(qc, request);
    if (!s.platformAdmin) return redirect(s.institutionAdmin ? "/institution" : "/no-access");
    return null;
  },
  /**
   * Institution area: an institution grant, or a platform admin acting for an
   * institution (`/institution?institution=<slug>`, as in the reference).
   */
  institution: async ({ request }: LoaderFunctionArgs) => {
    const s = await requireSession(qc, request);
    if (!s.institutionAdmin && !s.platformAdmin) return redirect("/no-access");
    return null;
  },
});
