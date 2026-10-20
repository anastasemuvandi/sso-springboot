import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, server } from "@/test/server";
import { renderInstitutionPage } from "@/test/institution";
import { Component as AccessMatrixPage } from "../../pages/AccessMatrixPage";
import type { AccessMatrixPage as MatrixPayload } from "../types";

const page = (): MatrixPayload => ({
  institution: "minagri",
  columns: [
    { client_id: "minagri-portal", name: "Portal", kind: "owned", type: "A", editable: true },
    { client_id: "national-payroll", name: "Payroll", kind: "shared", type: "C", editable: true },
  ],
  items: [
    {
      employee_number: "E1",
      first_name: "Alice",
      last_name: "Uwase",
      has_nid: true,
      nid_masked: "1199•••••••••012",
      account: "existing",
      status: "active",
      cells: { "minagri-portal": { has_access: true }, "national-payroll": { has_access: false } },
    },
    {
      employee_number: "E2",
      first_name: "Bob",
      last_name: "Mugisha",
      has_nid: false,
      account: "none",
      status: "active",
      cells: { "minagri-portal": { has_access: false }, "national-payroll": { has_access: false } },
    },
  ],
  page: 1,
  page_size: 50,
  total: 2,
  total_employees: 2,
  counts: { "minagri-portal": 1, "national-payroll": 0 },
});

describe("access matrix — bulk apply", () => {
  it("applied items become checked; a state_changed rejection stays pending with its message and offers a reload", async () => {
    const queries: string[] = [];
    const bodies: unknown[] = [];
    server.use(
      http.get(api("/institution-admin/access-matrix"), ({ request }) => {
        queries.push(new URL(request.url).searchParams.get("clients") ?? "");
        return HttpResponse.json(page());
      }),
      http.post(api("/institution-admin/grants/bulk"), async ({ request }) => {
        bodies.push(await request.json());
        return HttpResponse.json({
          institution: "minagri",
          action: "grant",
          channel: "ui",
          batch_id: "b1",
          summary: { applied: 1, rejected: 1, failed: 0 },
          results: [
            { employee_number: "E2", client_id: "minagri-portal", status: "applied" },
            { employee_number: "E2", client_id: "national-payroll", status: "rejected", reason: "state_changed", message: "Changed since you loaded the page" },
          ],
        });
      }),
    );
    renderInstitutionPage(<AccessMatrixPage />, {
      route: "/institution/access/matrix?client=minagri-portal&client=national-payroll",
      path: "/institution/access/matrix",
    });

    const portalBob = await screen.findByRole("checkbox", { name: "minagri-portal for Bob Mugisha" });
    const payrollBob = screen.getByRole("checkbox", { name: "national-payroll for Bob Mugisha" });
    expect(queries[0]).toBe("minagri-portal,national-payroll");
    // Alice already has the portal: not clickable in grant mode.
    expect(screen.getByRole("checkbox", { name: "minagri-portal for Alice Uwase" })).toBeDisabled();

    await userEvent.click(portalBob);
    await userEvent.click(payrollBob);
    expect(screen.getByTestId("pending-counter")).toHaveTextContent("2 grants pending");

    await userEvent.click(screen.getByRole("button", { name: "Apply" }));

    const banner = await screen.findByTestId("matrix-banner");
    expect(banner).toHaveTextContent("1 granted; 1 not applied and still pending");
    expect(within(banner).getByRole("button", { name: /Reload/ })).toBeInTheDocument();
    expect(bodies).toEqual([
      {
        action: "grant",
        channel: "ui",
        items: [
          { employeeNumber: "E2", clientId: "minagri-portal" },
          { employeeNumber: "E2", clientId: "national-payroll" },
        ],
      },
    ]);

    // Applied → now has access (checked, no longer clickable in grant mode).
    await waitFor(() => expect(screen.getByRole("checkbox", { name: "minagri-portal for Bob Mugisha" })).toBeDisabled());
    expect(screen.getByRole("checkbox", { name: "minagri-portal for Bob Mugisha" })).toBeChecked();
    // Rejected → still pending (ticked, enabled) and marked with the server's message.
    const stillPending = screen.getByRole("checkbox", { name: "national-payroll for Bob Mugisha" });
    expect(stillPending).toBeChecked();
    expect(stillPending).toBeEnabled();
    expect(screen.getByRole("img", { name: "Changed since you loaded the page" })).toBeInTheDocument();
    expect(screen.getByTestId("pending-counter")).toHaveTextContent("1 grant pending");

    // Reload refetches the page; the failed item stays pending.
    await userEvent.click(within(banner).getByRole("button", { name: /Reload/ }));
    await waitFor(() => expect(queries.length).toBeGreaterThanOrEqual(2));
    expect(screen.getByTestId("pending-counter")).toHaveTextContent("1 grant pending");
  });

  it("reveals a masked NID on request (audited server-side)", async () => {
    server.use(
      http.get(api("/institution-admin/access-matrix"), () => HttpResponse.json(page())),
      http.get(api("/institution-admin/access-matrix/nid/E1"), () => HttpResponse.json({ employee_number: "E1", nid: "1199080012345012" })),
    );
    renderInstitutionPage(<AccessMatrixPage />, { route: "/institution/access/matrix?client=minagri-portal", path: "/institution/access/matrix" });
    await userEvent.click(await screen.findByRole("button", { name: /Show the full NID.*Alice Uwase/ }));
    expect(await screen.findByTestId("nid-E1")).toHaveTextContent("1199080012345012");
  });

  it("a tier without view-clients sees the capability gate, not the matrix", async () => {
    renderInstitutionPage(<AccessMatrixPage />, {
      route: "/institution/access/matrix",
      path: "/institution/access/matrix",
      capabilities: ["view-members"],
    });
    expect(await screen.findByTestId("capability-gate")).toHaveTextContent("view-clients");
  });
});
