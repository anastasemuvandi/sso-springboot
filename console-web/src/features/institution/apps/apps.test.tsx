import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, envelope, server } from "@/test/server";
import { renderInstitutionPage } from "@/test/institution";
import { Component as AppNewPage } from "../pages/AppNewPage";
import { Component as AppJoinKeyPage } from "../pages/AppJoinKeyPage";
import { Component as AppsListPage } from "../pages/AppsListPage";

const setting = (over: Record<string, unknown> = {}) => ({
  client_id: "minagri-portal",
  join_column: "email",
  claim: "join_key",
  user_source: "email",
  explicit: false,
  ...over,
});

const app = (over: Record<string, unknown> = {}) => ({
  id: "u1",
  clientId: "minagri-portal",
  name: "Portal",
  manageable: true,
  cross_cutting: false,
  owned: true,
  scope: { kind: "institution", owner_institution: "minagri", cross_cutting: false, all_institutions: false, institutions: [] },
  join_column: setting(),
  mfa_required: false,
  mfa_applies: false,
  mfa_mode: "per_client",
  ...over,
});

describe("institution apps/new", () => {
  it("shows the fixed slug prefix, previews <slug>-<app>, and POSTs the app part only", async () => {
    const bodies: unknown[] = [];
    server.use(
      http.post(api("/institution-admin/apps"), async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json(app(), { status: 201 });
      }),
      http.get(api("/institution-admin/apps"), () => HttpResponse.json([])),
    );
    const { router } = renderInstitutionPage(<AppNewPage />, { route: "/institution/apps/new", path: "/institution/apps/new" });

    expect(await screen.findByTestId("slug-prefix")).toHaveTextContent("minagri-");
    expect(screen.getByTestId("client-id-preview")).toHaveTextContent("minagri-<app>");
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("App name"), "Portal");
    expect(screen.getByTestId("client-id-preview")).toHaveTextContent("minagri-portal");
    await user.type(screen.getByLabelText("Redirect URIs"), "https://minagri.gov.rw/cb");
    await user.click(screen.getByRole("button", { name: "Create client" }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual({ app: "portal", redirect_uris: ["https://minagri.gov.rw/cb"] });
    await waitFor(() => expect(router.state.location.pathname).toBe("/institution/apps"));
  });

  it("refuses a pasted full client-id before any request", async () => {
    let posted = false;
    server.use(
      http.post(api("/institution-admin/apps"), () => {
        posted = true;
        return HttpResponse.json({});
      }),
    );
    renderInstitutionPage(<AppNewPage />, { route: "/institution/apps/new", path: "/institution/apps/new" });
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("App name"), "minagri-portal");
    await user.type(screen.getByLabelText("Redirect URIs"), "https://minagri.gov.rw/cb");
    await user.click(screen.getByRole("button", { name: "Create client" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(/app part only/);
    expect(posted).toBe(false);
  });

  it("a tier without manage-clients gets the capability gate", async () => {
    renderInstitutionPage(<AppNewPage />, { route: "/institution/apps/new", path: "/institution/apps/new", capabilities: ["view-clients"] });
    expect(await screen.findByTestId("capability-gate")).toHaveTextContent("manage-clients");
  });
});

describe("institution apps/:clientId/join-key — 409 confirm", () => {
  it("shows the wrapper's counts and re-sends with confirm: true", async () => {
    const puts: Array<{ join_column: string; confirm: boolean }> = [];
    server.use(
      http.get(api("/institution-admin/apps"), () => HttpResponse.json([app()])),
      http.get(api("/institution-admin/apps/minagri-portal/join-column"), () =>
        HttpResponse.json({
          setting: setting({ explicit: true }),
          choices: [
            { value: "email", label: "Email", source: "email", shape: "address", default: true },
            { value: "nid", label: "National ID", source: "national_id", shape: "16 digits", default: false },
          ],
          active_sessions: 4,
          readiness: { join_column: "email", checked: 2, blocked: 0, truncated: false, users: [] },
        }),
      ),
      http.put(api("/institution-admin/apps/minagri-portal/join-column"), async ({ request }) => {
        const body = (await request.json()) as { join_column: string; confirm: boolean };
        puts.push(body);
        if (!body.confirm) return envelope(409, "confirmation_required", "4 sessions are live and 2 users are assigned.", { active_sessions: 4, assigned_users: 2 });
        return HttpResponse.json({
          setting: setting({ explicit: true, join_column: "nid" }),
          previous: setting({ explicit: true }),
          active_sessions: 4,
          assigned_users: 2,
          mapper: "updated",
          readiness: { join_column: "nid", checked: 2, blocked: 0, truncated: false, users: [] },
        });
      }),
    );
    renderInstitutionPage(<AppJoinKeyPage />, { route: "/institution/apps/minagri-portal/join-key", path: "/institution/apps/:clientId/join-key" });
    const user = userEvent.setup();
    await user.click(await screen.findByRole("radio", { name: /National ID/ }));
    await user.click(screen.getByRole("button", { name: "Save join key" }));

    const dialog = await screen.findByTestId("join-confirm");
    expect(within(dialog).getByText(/4 active sessions keep the previous key/)).toBeInTheDocument();
    expect(within(dialog).getByText(/2 assigned users are re-matched/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Apply anyway" }));

    await waitFor(() => expect(puts).toEqual([{ join_column: "nid", confirm: false }, { join_column: "nid", confirm: true }]));
  });
});

describe("institution apps list", () => {
  it("offers secret only on owned apps and no 2FA-off control", async () => {
    server.use(
      http.get(api("/institution-admin/apps"), () =>
        HttpResponse.json([
          app(),
          app({ clientId: "shared-hr", owned: false, cross_cutting: true, mfa_required: true, scope: { kind: "cross-cutting", owner_institution: null, cross_cutting: true, all_institutions: true, institutions: ["*"] } }),
        ]),
      ),
    );
    renderInstitutionPage(<AppsListPage />, { route: "/institution/apps", path: "/institution/apps" });
    const own = await screen.findByTestId("app-row-minagri-portal");
    const shared = screen.getByTestId("app-row-shared-hr");
    expect(within(own).getByRole("button", { name: "Secret" })).toBeInTheDocument();
    expect(within(shared).queryByRole("button", { name: "Secret" })).toBeNull();
    expect(within(own).getByRole("button", { name: "Require 2FA" })).toBeInTheDocument();
    expect(within(shared).queryByRole("button", { name: "Require 2FA" })).toBeNull();
    expect(screen.queryByRole("button", { name: /turn off|disable 2fa/i })).toBeNull();
  });
});
