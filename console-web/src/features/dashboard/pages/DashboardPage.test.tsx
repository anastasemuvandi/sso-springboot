import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, envelope, server } from "@/test/server";
import { renderPage } from "@/test/render";
import { zeroOverview } from "@/test/fixtures/overview";
import { DashboardPage } from "./DashboardPage";

function baseHandlers({ employeesFail = false }: { employeesFail?: boolean } = {}) {
  return [
    http.get(api("/dashboard/metrics"), () =>
      HttpResponse.json({ total_users: 0, total_clients: 0, total_agencies: 0, active_sessions: 0, logins_today: 0, new_users_this_week: 0 }),
    ),
    http.get(api("/dashboard/sessions"), () => HttpResponse.json({ total_sessions: 0, client_sessions: [] })),
    http.get(api("/dashboard/logins/recent"), () => HttpResponse.json([])),
    http.get(api("/employees"), () =>
      employeesFail
        ? envelope(503, "mirror_unavailable", "The employee mirror is not configured")
        : HttpResponse.json({
            total: 0,
            limit: 1,
            offset: 0,
            items: [],
            summary: { total: 0, linked: 0, unlinked: 0, unmapped: 0, ambiguous: 0, no_email: 0 },
          }),
    ),
    http.get(api("/client/clients"), () => HttpResponse.json([])),
    http.get(api("/realm/mfa"), () =>
      HttpResponse.json({ mode: "all", raw_mode: "all", enforced: true, effective: true, raw: null, browser_flow: "browser-totp", flow_ready: true }),
    ),
    http.get(api("/audit/events"), () => HttpResponse.json({ total: 0, limit: 8, offset: 0, items: [] })),
  ];
}

describe("DashboardPage — overview sections never show a false zero", () => {
  it("an unavailable section says so; an available zero says zero", async () => {
    server.use(
      ...baseHandlers(),
      http.get(api("/dashboard/overview"), () =>
        HttpResponse.json(
          zeroOverview({
            leavers: { available: false, error: "the employee mirror is not configured", total: 0, items: [] },
          }),
        ),
      ),
    );
    renderPage(<DashboardPage />, { route: "/dashboard", path: "/dashboard" });

    const leavers = await screen.findByTestId("section-leavers");
    expect(within(leavers).getByTestId("section-unavailable")).toHaveTextContent("Not available — the employee mirror is not configured.");
    // No count badge for a section that was not read.
    expect(within(leavers).queryByTestId("leavers-total")).toBeNull();

    const setup = screen.getByTestId("section-password-setup");
    expect(within(setup).queryByTestId("section-unavailable")).toBeNull();
    expect(within(setup).getByText("Every account has had its password set.")).toBeInTheDocument();

    const logins = screen.getByTestId("section-logins");
    expect(within(logins).getByTestId("logins-24h")).toHaveTextContent("0");

    // Needs attention names what could not be checked instead of claiming all clear.
    const attention = screen.getByTestId("needs-attention");
    expect(within(attention).getByTestId("attention-unavailable")).toHaveTextContent("leavers");
    expect(within(attention).getByText("Nothing outstanding in what could be checked.")).toBeInTheDocument();
  });

  it("a failed read renders — in its card, not 0", async () => {
    server.use(...baseHandlers({ employeesFail: true }), http.get(api("/dashboard/overview"), () => HttpResponse.json(zeroOverview())));
    renderPage(<DashboardPage />, { route: "/dashboard", path: "/dashboard" });

    await waitFor(() => expect(screen.getByTestId("stat-employees-value")).toHaveTextContent("—"));
    await waitFor(() => expect(screen.getByTestId("stat-users-value")).toHaveTextContent("0"));
    expect(within(screen.getByTestId("needs-attention")).getByTestId("attention-unavailable")).toHaveTextContent("the employee list");
  });

  it("a whole-overview failure shows an error, not four empty sections", async () => {
    server.use(...baseHandlers(), http.get(api("/dashboard/overview"), () => envelope(502, "keycloak_unavailable", "Keycloak did not answer")));
    renderPage(<DashboardPage />, { route: "/dashboard", path: "/dashboard" });
    expect(await screen.findByText(/Keycloak did not answer/)).toBeInTheDocument();
    expect(screen.queryByTestId("section-leavers")).toBeNull();
  });

  it("refresh re-reads with ?refresh=true and shows the new numbers", async () => {
    const seen: string[] = [];
    server.use(
      ...baseHandlers(),
      http.get(api("/dashboard/overview"), ({ request }) => {
        const refresh = new URL(request.url).searchParams.get("refresh");
        seen.push(refresh ?? "none");
        return HttpResponse.json(
          zeroOverview(
            refresh === "true"
              ? { leavers: { available: true, total: 2, items: [{ employee_number: "E1", name: "Jean", enabled: true, applications: [], reasons: ["account_enabled"] }, { employee_number: "E2", name: "Aline", enabled: true, applications: [], reasons: ["account_enabled"] }] } }
              : {},
          ),
        );
      }),
    );
    renderPage(<DashboardPage />, { route: "/dashboard", path: "/dashboard" });
    await screen.findByTestId("section-leavers");
    expect(screen.getByTestId("leavers-total")).toHaveTextContent("0");

    await userEvent.click(screen.getByTestId("overview-refresh"));
    await waitFor(() => expect(screen.getByTestId("leavers-total")).toHaveTextContent("2"));
    expect(seen).toEqual(["none", "true"]);
    expect(within(screen.getByTestId("needs-attention")).getByTestId("attention-leavers")).toHaveTextContent("2");
  });
});
