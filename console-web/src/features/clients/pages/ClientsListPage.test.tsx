import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";

import { api, envelope, server } from "@/test/server";
import { renderPage } from "@/test/render";
import { ClientsListPage } from "./ClientsListPage";

const scope = (clientId: string, kind: "institution" | "platform") => ({
  client_id: clientId,
  kind,
  owner_institution: kind === "institution" ? "minagri" : null,
  cross_cutting: false,
  all_institutions: false,
  institutions: [],
  inferred: false,
});

describe("ClientsListPage", () => {
  it("hides Keycloak internals, flags unset join keys and shows the 2FA column", async () => {
    server.use(
      http.get(api("/client/clients"), () =>
        HttpResponse.json([
          { id: "1", clientId: "minagri-kubaka", standardFlowEnabled: true, attributes: { "sso.client.mfa_required": "false" }, scope: scope("minagri-kubaka", "institution") },
          { id: "2", clientId: "sso-client", standardFlowEnabled: false, scope: scope("sso-client", "platform") },
          { id: "3", clientId: "account-console", standardFlowEnabled: true },
        ]),
      ),
      http.get(api("/realm/mfa"), () =>
        HttpResponse.json({ mode: "per_client", raw_mode: "per_client", enforced: true, effective: true, raw: null, browser_flow: "browser-totp", flow_ready: true }),
      ),
      http.get(api("/agency/"), () => HttpResponse.json([])),
      http.get(api("/authorization/clients/minagri-kubaka/access"), () =>
        HttpResponse.json({ client_id: "minagri-kubaka", enforced: true, users: [{ id: "u1", username: "jean" }], groups: [] }),
      ),
      http.get(api("/authorization/clients/sso-client/access"), () => envelope(403, "forbidden", "no authz")),
    );
    renderPage(<ClientsListPage />, { route: "/clients", path: "/clients" });

    expect(await screen.findByTestId("client-row-minagri-kubaka")).toBeInTheDocument();
    expect(screen.queryByTestId("client-row-account-console")).toBeNull();
    expect(screen.getByTestId("join-banner")).toHaveTextContent("1 institution or cross-cutting client has no join column set");
    await waitFor(() => expect(screen.getByTestId("mfa-minagri-kubaka")).toHaveAttribute("data-kind", "not_required"));
    expect(screen.getByTestId("mfa-sso-client")).toHaveAttribute("data-kind", "no_user_login");
  });
});
