/**
 * Render an institution-area page with a resolved InstitutionContext.
 * MSW answers /institution-admin/me and /permissions (override `capabilities`
 * to test a narrower tier); the page renders inside <InstitutionProvider> +
 * <InstitutionGate>, exactly as in the app.
 */
import type { ReactElement } from "react";
import { http, HttpResponse } from "msw";

import { InstitutionProvider } from "@/features/institution/context";
import { InstitutionGate } from "@/features/institution/components/InstitutionGate";
import { CAPABILITIES, type Capability, type InstitutionContext, type InstitutionPermissions } from "@/features/institution/types";
import type { Session } from "@/auth/session";
import { renderPage } from "./render";
import { api, server } from "./server";

export const institutionAdmin: Session = {
  authenticated: true,
  username: "hr@minagri.gov.rw",
  name: "MINAGRI HR",
  roles: ["inst:minagri:hr-admin"],
  platformAdmin: false,
  institutionAdmin: true,
  institutionSlug: "minagri",
};

export function instContext(capabilities: readonly Capability[] = CAPABILITIES): { me: InstitutionContext; perms: InstitutionPermissions } {
  return {
    me: { institution: "minagri", administered: ["minagri"], is_platform_admin: false, actor: "hr@minagri.gov.rw", capabilities: [...capabilities] },
    perms: {
      institution: "minagri",
      templates: ["hr-admin"],
      capabilities: [...capabilities],
      members: { group: "minagri", include_subgroups: true },
      roles: { allow: ["inst:minagri:*"], deny: [] },
      clients: { allow: ["minagri-*"], deny: [] },
    },
  };
}

export function institutionHandlers(capabilities: readonly Capability[] = CAPABILITIES) {
  const { me, perms } = instContext(capabilities);
  return [
    http.get(api("/institution-admin/me"), () => HttpResponse.json(me)),
    http.get(api("/institution-admin/permissions"), () => HttpResponse.json(perms)),
  ];
}

export function renderInstitutionPage(
  element: ReactElement,
  { route = "/institution", path = "/institution", capabilities }: { route?: string; path?: string; capabilities?: readonly Capability[] } = {},
) {
  server.use(...institutionHandlers(capabilities));
  return renderPage(
    <InstitutionProvider>
      <InstitutionGate>{element}</InstitutionGate>
    </InstitutionProvider>,
    { route, path, session: institutionAdmin },
  );
}
