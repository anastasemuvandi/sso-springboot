import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, envelope, server } from "@/test/server";
import { renderPage } from "@/test/render";
import { Component as EmployeesPage } from "./EmployeesPage";

const NID = "1199080012345678";

const row = (n: string, over: Record<string, unknown> = {}) => ({
  id: `id-${n}`,
  employee_number: n,
  first_name: "Aline",
  last_name: "Uwase",
  email: "aline@minagri.gov.rw",
  phone: "+250788000000",
  position: "Agronomist",
  status: "active",
  institution_code: "MINAGRI",
  institution_slug: "minagri",
  keycloak_user_id: null,
  match_state: "unmatched",
  first_seen_at: "2026-10-01T00:00:00Z",
  last_seen_at: "2026-10-19T00:00:00Z",
  ...over,
});

function listHandler(seen: URLSearchParams[] = []) {
  return http.get(api("/employees"), ({ request }) => {
    seen.push(new URL(request.url).searchParams);
    return HttpResponse.json({
      institution: null,
      scoped: false,
      total: 2,
      limit: 50,
      offset: 0,
      summary: { total: 2, linked: 0, unlinked: 2, ambiguous: 0, unmapped: 0, no_email: 0 },
      items: [row("E100"), row("E200", { first_name: "Jean", last_name: "Habimana" })],
    });
  });
}

const agencies = http.get(api("/agency/"), () => HttpResponse.json([]));

describe("Employees directory — national ID only from the detail read", () => {
  it("the list shows no national id; opening a row fetches GET /employees/{n} and shows it", async () => {
    let detailCalls = 0;
    server.use(
      listHandler(),
      agencies,
      http.get(api("/employees/E100"), () => {
        detailCalls += 1;
        return HttpResponse.json({
          id: "E100",
          nationalId: NID,
          rssbNumber: "RS1",
          firstName: "Aline",
          lastName: "Uwase",
          scoped: false,
          employments: [{ employerId: "MINAGRI", employerName: "Ministry of Agriculture", institutionSlug: "minagri", isOnboarded: true, jobs: [] }],
          account: { matchState: "unmatched" },
        });
      }),
    );
    renderPage(<EmployeesPage />, { route: "/employees", path: "/employees" });

    await screen.findByTestId("employee-row-E100");
    expect(document.body).not.toHaveTextContent(NID);
    expect(detailCalls).toBe(0); // not fetched until opened

    await userEvent.click(screen.getByRole("button", { name: "Details for Aline Uwase" }));
    const drawer = await screen.findByTestId("employee-drawer");
    await waitFor(() => expect(within(drawer).getByTestId("employee-national-id")).toHaveTextContent(NID));
    expect(within(drawer).getByText("Ministry of Agriculture")).toBeInTheDocument();
    expect(detailCalls).toBe(1);
  });

  it("a 404 says the employee is not visible, without saying why", async () => {
    server.use(listHandler(), agencies, http.get(api("/employees/E200"), () => envelope(404, "not_found", "Employee not found")));
    renderPage(<EmployeesPage />, { route: "/employees", path: "/employees" });

    await userEvent.click(await screen.findByRole("button", { name: "Details for Jean Habimana" }));
    const drawer = await screen.findByTestId("employee-drawer");
    expect(await within(drawer).findByText("This employee is not visible to you (or does not exist).")).toBeInTheDocument();
    expect(within(drawer).queryByTestId("employee-national-id")).toBeNull();
    // The row's own facts still show.
    expect(within(drawer).getByText(/Agronomist/)).toBeInTheDocument();
  });

  it("URL filters reach the API (dashboard deep links)", async () => {
    const seen: URLSearchParams[] = [];
    server.use(listHandler(seen), agencies);
    renderPage(<EmployeesPage />, { route: "/employees?account=unlinked&unmapped=1", path: "/employees" });
    await screen.findByTestId("employee-row-E100");
    expect(seen[0]?.get("account")).toBe("unlinked");
    expect(seen[0]?.get("unmapped")).toBe("true");
    expect(seen[0]?.get("limit")).toBe("50");
  });

  it("a 503 says the mirror is not configured instead of showing an empty directory", async () => {
    server.use(http.get(api("/employees"), () => envelope(503, "mirror_unavailable", "IDENTITY_DATABASE_URL is not set")), agencies);
    renderPage(<EmployeesPage />, { route: "/employees", path: "/employees" });
    const panel = await screen.findByTestId("mirror-unavailable");
    expect(panel).toHaveTextContent("IDENTITY_DATABASE_URL is not set");
  });
});
