import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, platformAdmin, server } from "@/test/server";
import { renderPage } from "@/test/render";
import { Component as SettingsPage } from "./SettingsPage";

const superAdmin = { ...platformAdmin, roles: ["super_admin"] };

function handlers(mfa: Record<string, unknown>, onPut?: (body: unknown) => void, exemptions: unknown[] = []) {
  let current = { ...mfa };
  return [
    http.get(api("/realm/mfa"), () => HttpResponse.json(current)),
    http.put(api("/realm/mfa"), async ({ request }) => {
      const body = (await request.json()) as { mode: string };
      onPut?.(body);
      const previous = current.mode;
      current = { ...current, mode: body.mode, first_enabled_at: "2026-10-20T10:00:00Z" };
      return HttpResponse.json({ mode: body.mode, previous_mode: previous, enforced: true, previous: false, changed: true, bulk_enrol: { started: true, reason: "first" } });
    }),
    http.get(api("/realm/mfa/exemptions"), () => HttpResponse.json({ exemptions })),
    http.get(api("/realm/config"), () => HttpResponse.json({ realm: "government-internal", enabled: true, failureFactor: 5 })),
    http.get(api("/realm/themes"), () => HttpResponse.json({ login: ["keycloak", "gor-admin"], account: [], admin: [], email: [] })),
    http.get(api("/realm/branding"), () => HttpResponse.json({ themes: {}, display: {}, custom_attributes: {} })),
    http.get(api("/realm/email/config"), () => HttpResponse.json({})),
  ];
}

const offState = { mode: "off", raw_mode: "off", enforced: false, effective: false, raw: null, browser_flow: "browser-totp", flow_ready: true, first_enabled_at: null, exempt_count: 0 };

describe("SettingsPage — realm MFA mode", () => {
  it("choosing a mode opens a confirmation; confirming PUTs {mode}", async () => {
    const puts: unknown[] = [];
    server.use(...handlers(offState, (b) => puts.push(b)));
    renderPage(<SettingsPage />, { route: "/settings", path: "/settings", session: superAdmin });

    await userEvent.click(await screen.findByRole("radio", { name: /All clients/ }));
    const dialog = await screen.findByRole("dialog");
    // First time to `all`: the one-time bulk CONFIGURE_TOTP is spelled out.
    expect(within(dialog).getByTestId("mfa-consequences")).toHaveTextContent("CONFIGURE_TOTP is also assigned");
    expect(puts).toEqual([]);

    await userEvent.click(within(dialog).getByTestId("mfa-confirm"));
    await waitFor(() => expect(puts).toEqual([{ mode: "all" }]));
    await waitFor(() => expect(screen.getByTestId("mfa-current-mode")).toHaveTextContent("All clients"));
  });

  it("cancel sends nothing", async () => {
    const puts: unknown[] = [];
    server.use(...handlers(offState, (b) => puts.push(b)));
    renderPage(<SettingsPage />, { route: "/settings", path: "/settings", session: superAdmin });
    await userEvent.click(await screen.findByRole("radio", { name: /Per client/ }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(puts).toEqual([]);
  });

  it("warns that a change will be refused while browser-totp is not bound", async () => {
    server.use(...handlers({ ...offState, flow_ready: false, browser_flow: "browser" }));
    renderPage(<SettingsPage />, { route: "/settings", path: "/settings", session: superAdmin });
    expect(await screen.findByTestId("mfa-flow-not-ready")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("radio", { name: /All clients/ }));
    expect(within(await screen.findByRole("dialog")).getByTestId("mfa-consequences")).toHaveTextContent("will be refused");
  });

  it("non-super admins see no mode controls (cosmetic)", async () => {
    server.use(...handlers(offState));
    renderPage(<SettingsPage />, { route: "/settings", path: "/settings", session: { ...platformAdmin, roles: ["admin"] } });
    await screen.findByTestId("mfa-current-mode");
    expect(screen.queryByRole("radio", { name: /All clients/ })).toBeNull();
  });
});

describe("SettingsPage — MFA exemptions", () => {
  it("a short justification is refused before any request; a valid one is POSTed collapsed", async () => {
    const posts: unknown[] = [];
    server.use(
      ...handlers(offState),
      http.get(api("/users/"), () => HttpResponse.json({ users: [{ id: "u-1", username: "jean@minagri.gov.rw", enabled: true }], total: 1, page: 1, pageSize: 10, totalPages: 1 })),
      http.post(api("/realm/mfa/exemptions"), async ({ request }) => {
        posts.push(await request.json());
        return HttpResponse.json({ user_id: "u-1" }, { status: 201 });
      }),
    );
    renderPage(<SettingsPage />, { route: "/settings", path: "/settings", session: superAdmin });
    const panel = await screen.findByTestId("mfa-exemptions");
    await userEvent.type(within(panel).getByLabelText(/Search users/), "jean");
    await userEvent.click(within(panel).getByRole("button", { name: "Search" }));
    await userEvent.click(await within(panel).findByText("jean@minagri.gov.rw"));

    const reason = within(panel).getByLabelText("Justification");
    await userEvent.type(reason, "lost phone");
    await userEvent.click(within(panel).getByRole("button", { name: "Grant exemption" }));
    expect(await within(panel).findByText(/at least 15 characters/)).toBeInTheDocument();
    expect(posts).toEqual([]);

    await userEvent.clear(reason);
    await userEvent.type(reason, "  lost   phone, ticket RISA-42 until Friday ");
    await userEvent.click(within(panel).getByRole("button", { name: "Grant exemption" }));
    await waitFor(() => expect(posts).toEqual([{ user_id: "u-1", reason: "lost phone, ticket RISA-42 until Friday" }]));
  });
});
