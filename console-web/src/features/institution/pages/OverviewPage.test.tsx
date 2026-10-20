import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";

import { renderInstitutionPage } from "@/test/institution";
import { api, server } from "@/test/server";
import { zeroOverview } from "@/test/fixtures/overview";
import { Component as OverviewPage } from "./OverviewPage";

function reads() {
  return [
    http.get(api("/institution-admin/users"), () => HttpResponse.json([{ id: "u1", username: "a", enabled: true }])),
    http.get(api("/employees"), () => HttpResponse.json({ institution: "minagri", scoped: true, total: 0, limit: 1, offset: 0, items: [], summary: { total: 7, linked: 3, unlinked: 4, unmapped: 0, ambiguous: 0, no_email: 1 } })),
    http.get(api("/institution-admin/apps"), () => HttpResponse.json([])),
    http.get(api("/institution-admin/apps/join-columns"), () => HttpResponse.json({ total: 0, configured: 0, unconfigured: 0, outstanding: 0, needs_platform_admin: 0, malformed: 0, items: [], choices: [], institution: "minagri" })),
    http.get(api("/institution-admin/audit"), () => HttpResponse.json({ total: 0, limit: 8, offset: 0, items: [] })),
    http.get(api("/institution-admin/roles"), () => HttpResponse.json([])),
  ];
}

describe("institution overview", () => {
  it("renders the four sections; an unavailable one says so, never 0", async () => {
    server.use(
      ...reads(),
      http.get(api("/institution-admin/overview"), () =>
        HttpResponse.json(zeroOverview({ institution: "minagri", logins: { available: false, error: "event saving is off" } })),
      ),
    );
    renderInstitutionPage(<OverviewPage />);
    const logins = await screen.findByTestId("section-logins");
    expect(within(logins).getByTestId("section-unavailable")).toHaveTextContent("event saving is off");
    expect(screen.getByTestId("section-leavers")).toHaveTextContent("Nobody IPPIS reports");
    await waitFor(() => expect(screen.getByTestId("inst-stat-employees-value")).toHaveTextContent("7"));
    expect(within(screen.getByTestId("needs-attention")).getByTestId("attention-unlinked")).toHaveTextContent("4");
    expect(within(screen.getByTestId("needs-attention")).getByTestId("attention-unavailable")).toHaveTextContent("login activity");
  });

  it("a tier without view-members makes none of the member reads", async () => {
    let overviewCalls = 0;
    server.use(
      ...reads(),
      http.get(api("/institution-admin/overview"), () => {
        overviewCalls += 1;
        return HttpResponse.json(zeroOverview());
      }),
    );
    renderInstitutionPage(<OverviewPage />, { capabilities: ["view-clients"] });
    await screen.findByText("Institution overview");
    await waitFor(() => expect(screen.getByTestId("inst-stat-apps-value")).toHaveTextContent("0"));
    expect(screen.getByTestId("inst-stat-employees-value")).toHaveTextContent("—");
    expect(screen.queryByTestId("section-leavers")).toBeNull();
    expect(overviewCalls).toBe(0);
  });

  it("503 from /me shows the fail-closed config screen", async () => {
    renderInstitutionPage(<OverviewPage />);
    server.use(http.get(api("/institution-admin/me"), () => HttpResponse.json({ error: "delegated_config_unavailable", message: "templates.json failed validation" }, { status: 503 })));
    expect(await screen.findByText("Delegated permissions are not loaded")).toBeInTheDocument();
    expect(screen.getByText("templates.json failed validation")).toBeInTheDocument();
  });
});
