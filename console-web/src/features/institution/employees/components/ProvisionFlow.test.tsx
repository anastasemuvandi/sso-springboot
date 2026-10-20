import { afterEach, describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, server } from "@/test/server";
import { renderInstitutionPage } from "@/test/institution";
import { Component as ProvisionPage } from "../../pages/EmployeesProvisionPage";
import { addMany, initialSelection, saveSelection, storageKey } from "../state/employeeSelection";

const app = (clientId: string, explicit = true) => ({
  id: clientId,
  clientId,
  name: clientId,
  manageable: true,
  cross_cutting: false,
  owned: true,
  scope: { kind: "institution", owner_institution: "minagri", cross_cutting: false, all_institutions: false, institutions: [] },
  join_column: { client_id: clientId, join_column: "nid", claim: "join_key", user_source: "national_id", explicit },
  mfa_required: true,
  mfa_applies: true,
  mfa_mode: "all",
});

function report(dryRun: boolean) {
  return {
    institution: "minagri",
    dry_run: dryRun,
    clients: [{ client_id: "minagri-kubaka", join_column: "nid", employee_field: "national_id", configured: true }],
    totals: { selected: 3, created: 1, linked: 0, skipped: 1, failed: 0, grants: 2, already_held: 1, accounts_existing: 2, denials: 1, clients_denying_everyone: [] },
    employees: [
      { employee_number: "E1", name: "Aline Uwase", username: "aline@minagri.gov.rw", action: "create", account: "created", outcome: "created", clients_granted: ["minagri-kubaka"], clients_skipped: [], clients_denied: [] },
      { employee_number: "E2", name: "Jean Mugabo", username: "jean", action: "link", account: "existing", outcome: "skipped", clients_granted: [], clients_skipped: ["minagri-kubaka"], clients_denied: [] },
      {
        employee_number: "E3",
        name: "Eric Habimana",
        action: "skip",
        account: "not_created",
        outcome: "skipped",
        clients_granted: [],
        clients_skipped: [],
        clients_denied: [{ client_id: "minagri-kubaka", join_column: "nid", qualified: false, reason: "join_value_missing", message: "No national ID in IPPIS." }],
      },
    ],
  };
}

function seedSelection() {
  const s = addMany(initialSelection(), [
    { employee_number: "E1", first_name: "Aline", last_name: "Uwase", email: "aline@minagri.gov.rw", status: "active", match_state: "unmatched" },
    { employee_number: "E2", first_name: "Jean", last_name: "Mugabo", email: "jean@minagri.gov.rw", status: "active", match_state: "matched", keycloak_user_id: "u2" },
    { employee_number: "E3", first_name: "Eric", last_name: "Habimana", email: "eric@minagri.gov.rw", status: "active", match_state: "unmatched" },
  ]).state;
  saveSelection(window.sessionStorage, "minagri", s);
}

afterEach(() => window.sessionStorage.clear());

describe("provision: dry run → report → confirm", () => {
  it("renders the four facts per employee, then confirms with dry_run:false and clears the selection", async () => {
    seedSelection();
    const bodies: Array<{ dry_run: boolean; employee_numbers: string[]; client_ids: string[] }> = [];
    server.use(
      http.get(api("/institution-admin/apps"), () => HttpResponse.json([app("minagri-kubaka"), { ...app("shared-x"), manageable: false }])),
      http.post(api("/institution-admin/employees/provision"), async ({ request }) => {
        const body = (await request.json()) as (typeof bodies)[number];
        bodies.push(body);
        return HttpResponse.json(report(body.dry_run));
      }),
    );
    renderInstitutionPage(<ProvisionPage />, { route: "/institution/employees/provision", path: "/institution/employees/provision" });

    expect(await screen.findByTestId("provision-selected-count")).toHaveTextContent("3 employees selected");
    // Non-manageable apps are not offered.
    expect(await screen.findByTestId("app-minagri-kubaka")).toBeInTheDocument();
    expect(screen.queryByTestId("app-shared-x")).toBeNull();

    // Confirm is not available before a dry run.
    expect(screen.getByTestId("confirm-provision")).toBeDisabled();
    await userEvent.click(screen.getByTestId("app-minagri-kubaka"));
    await userEvent.click(screen.getByTestId("dry-run"));

    const preview = await screen.findByTestId("provision-report-preview");
    expect(bodies[0]).toEqual({ employee_numbers: ["E1", "E2", "E3"], client_ids: ["minagri-kubaka"], dry_run: true });

    const e1 = within(preview).getByTestId("report-row-E1");
    expect(within(e1).getByTestId("report-account")).toHaveTextContent("created");
    expect(within(e1).getByTestId("report-granted")).toHaveTextContent("minagri-kubaka");
    const e2 = within(preview).getByTestId("report-row-E2");
    expect(within(e2).getByTestId("report-account")).toHaveTextContent("already had one");
    expect(within(e2).getByTestId("report-skipped")).toHaveTextContent("minagri-kubaka");
    const e3 = within(preview).getByTestId("report-row-E3");
    expect(within(e3).getByTestId("report-account")).toHaveTextContent("no account");
    expect(within(e3).getByTestId("report-denied")).toHaveTextContent("minagri-kubaka");
    expect(within(e3).getByTestId("report-denied")).toHaveTextContent("nid empty");
    expect(within(e3).getByTestId("report-denied")).toHaveTextContent("No national ID in IPPIS.");

    await userEvent.click(screen.getByTestId("confirm-provision"));
    expect(await screen.findByTestId("provision-report-final")).toBeInTheDocument();
    expect(bodies[1]).toEqual({ employee_numbers: ["E1", "E2", "E3"], client_ids: ["minagri-kubaka"], dry_run: false });
    await waitFor(() => expect(window.sessionStorage.getItem(storageKey("minagri"))).toBeNull());
  });

  it("changing the inputs after a dry run disables Confirm until a new preview", async () => {
    seedSelection();
    server.use(
      http.get(api("/institution-admin/apps"), () => HttpResponse.json([app("minagri-kubaka"), app("minagri-ibiro")])),
      http.post(api("/institution-admin/employees/provision"), () => HttpResponse.json(report(true))),
    );
    renderInstitutionPage(<ProvisionPage />, { route: "/institution/employees/provision", path: "/institution/employees/provision" });
    await userEvent.click(await screen.findByTestId("app-minagri-kubaka"));
    await userEvent.click(screen.getByTestId("dry-run"));
    await screen.findByTestId("provision-report-preview");
    expect(screen.getByTestId("confirm-provision")).toBeEnabled();
    await userEvent.click(screen.getByTestId("app-minagri-ibiro"));
    expect(screen.getByTestId("confirm-provision")).toBeDisabled();
    expect(screen.queryByTestId("provision-report-preview")).toBeNull();
  });

  it("a tier without the provisioning capabilities sees the capability gate", async () => {
    renderInstitutionPage(<ProvisionPage />, {
      route: "/institution/employees/provision",
      path: "/institution/employees/provision",
      capabilities: ["view-members", "view-clients"],
    });
    expect(await screen.findByTestId("capability-gate")).toHaveTextContent("manage-members");
  });

  it("an empty selection says so", async () => {
    server.use(http.get(api("/institution-admin/apps"), () => HttpResponse.json([])));
    renderInstitutionPage(<ProvisionPage />, { route: "/institution/employees/provision", path: "/institution/employees/provision" });
    expect(await screen.findByTestId("provision-empty")).toBeInTheDocument();
  });
});
