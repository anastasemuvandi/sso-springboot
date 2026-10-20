import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, envelope, server } from "@/test/server";
import { renderInstitutionPage } from "@/test/institution";
import { Component as StaffRolesPage } from "./StaffRolesPage";
import { Component as StaffNewPage } from "./StaffNewPage";
import { Component as AuditPage } from "./AuditPage";

const member = {
  id: "u1",
  username: "aline",
  enabled: true,
  roles: ["inst:minagri:hr", "offline_access", "super_admin"],
};

describe("institution staff roles", () => {
  it("offers only inst:minagri:* roles and POSTs {role}", async () => {
    const posted: unknown[] = [];
    let held = [...member.roles];
    server.use(
      http.get(api("/institution-admin/users/u1"), () => HttpResponse.json({ ...member, roles: held })),
      http.get(api("/institution-admin/roles"), () =>
        HttpResponse.json([
          { id: "1", name: "inst:minagri:hr" },
          { id: "2", name: "inst:minagri:viewer", description: "Read only" },
          { id: "3", name: "super_admin" },
        ]),
      ),
      http.post(api("/institution-admin/users/u1/roles"), async ({ request }) => {
        const body = (await request.json()) as { role: string };
        posted.push(body);
        held = [...held, body.role];
        return HttpResponse.json({});
      }),
    );
    renderInstitutionPage(<StaffRolesPage />, { route: "/institution/staff/u1/roles", path: "/institution/staff/:id/roles" });

    const available = await screen.findByTestId("roles-available");
    expect(within(available).getByText("inst:minagri:viewer")).toBeInTheDocument();
    expect(within(available).queryByText("super_admin")).toBeNull();
    expect(within(screen.getByTestId("roles-assigned")).getByText("inst:minagri:hr")).toBeInTheDocument();
    // Roles outside the namespace are counted, not listed.
    expect(screen.getByText("2 roles outside your institution's namespace are not shown.")).toBeInTheDocument();

    await userEvent.click(within(screen.getByTestId("role-inst:minagri:viewer")).getByRole("button", { name: "Grant" }));
    await waitFor(() => expect(posted).toEqual([{ role: "inst:minagri:viewer" }]));
    await waitFor(() => expect(within(screen.getByTestId("roles-assigned")).getByText("inst:minagri:viewer")).toBeInTheDocument());
  });

  it("a tier without map-roles gets the capability gate and makes no role calls", async () => {
    let called = false;
    server.use(
      http.get(api("/institution-admin/roles"), () => {
        called = true;
        return HttpResponse.json([]);
      }),
    );
    renderInstitutionPage(<StaffRolesPage />, {
      route: "/institution/staff/u1/roles",
      path: "/institution/staff/:id/roles",
      capabilities: ["view-members", "manage-members"],
    });
    const gate = await screen.findByTestId("capability-gate");
    expect(gate).toHaveTextContent("map-roles");
    expect(called).toBe(false);
  });
});

describe("new staff", () => {
  it("a 409 for a National ID already held lands on the NID field; no institution in the body", async () => {
    let body: Record<string, unknown> | null = null;
    server.use(
      http.post(api("/institution-admin/users"), async ({ request }) => {
        body = (await request.json()) as Record<string, unknown>;
        return envelope(409, "conflict", "National ID '1199080012345678' is already on another account. … 'national_id' …");
      }),
    );
    renderInstitutionPage(<StaffNewPage />, { route: "/institution/staff/new", path: "/institution/staff/new" });
    await userEvent.type(await screen.findByLabelText("Username"), "aline");
    await userEvent.type(screen.getByLabelText("Email"), "aline@minagri.gov.rw");
    await userEvent.type(screen.getByLabelText("First name"), "Aline");
    await userEvent.type(screen.getByLabelText("Last name"), "K");
    await userEvent.type(screen.getByLabelText("National ID (optional)"), "1199080012345678");
    await userEvent.click(screen.getByRole("button", { name: "Create staff" }));

    const alert = await screen.findByText(/already on another account/);
    expect(alert).toHaveAttribute("role", "alert");
    expect(body).toMatchObject({ username: "aline", national_id: "1199080012345678", enabled: true, emailVerified: true });
    expect(body).not.toHaveProperty("institution");
  });

  it("a malformed NID is refused before any request", async () => {
    let called = false;
    server.use(
      http.post(api("/institution-admin/users"), () => {
        called = true;
        return HttpResponse.json({ id: "x" });
      }),
    );
    renderInstitutionPage(<StaffNewPage />, { route: "/institution/staff/new", path: "/institution/staff/new" });
    await userEvent.type(await screen.findByLabelText("Username"), "aline");
    await userEvent.type(screen.getByLabelText("Email"), "aline@minagri.gov.rw");
    await userEvent.type(screen.getByLabelText("First name"), "Aline");
    await userEvent.type(screen.getByLabelText("Last name"), "K");
    await userEvent.type(screen.getByLabelText("National ID (optional)"), "123");
    await userEvent.click(screen.getByRole("button", { name: "Create staff" }));
    expect(await screen.findByText("A National ID is exactly 16 digits.")).toBeInTheDocument();
    expect(called).toBe(false);
  });
});

describe("institution audit", () => {
  it("never sends an institution filter and surfaces scope_filtered_out", async () => {
    const seen: URLSearchParams[] = [];
    server.use(
      http.get(api("/institution-admin/audit"), ({ request }) => {
        seen.push(new URL(request.url).searchParams);
        return HttpResponse.json({
          total: 1,
          limit: 50,
          offset: 0,
          scope_filtered_out: 2,
          items: [
            {
              id: "e1",
              event_type: "admin.role.assigned",
              category: "admin",
              target: "u1",
              performed_by: "hr@minagri.gov.rw",
              details: { target_username: "aline", role: "inst:minagri:hr" },
              severity: "INFO",
              prev_hash: "0",
              event_hash: "abcdef0123456789abcdef",
              created_at: "2026-10-20T10:00:00Z",
            },
          ],
        });
      }),
    );
    renderInstitutionPage(<AuditPage />, { route: "/institution/audit?severity=INFO", path: "/institution/audit" });
    expect(await screen.findByTestId("scope-filtered-out")).toHaveTextContent("2 events withheld.");
    expect(screen.getByText("Role granted")).toBeInTheDocument();
    expect(screen.getByText("aline")).toBeInTheDocument();
    expect(seen[0]?.has("institution")).toBe(false);
    expect(seen[0]?.get("severity")).toBe("INFO");
    expect(seen[0]?.get("limit")).toBe("50");
  });
});
