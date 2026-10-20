import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, envelope, server } from "@/test/server";
import { renderPage } from "@/test/render";
import type { ClientScopeState } from "../types";
import { ScopeEditor } from "./ScopeEditor";

const CLIENT = "shared-portal";

const scopeState: ClientScopeState = {
  scope: {
    client_id: CLIENT,
    kind: "cross-cutting",
    owner_institution: null,
    cross_cutting: true,
    all_institutions: false,
    institutions: ["minagri", "minisante"],
    inferred: false,
  },
  // A stale snapshot: minisante looks empty here, but the wrapper knows better.
  access: [
    { institution: "minagri", group_assigned: true, users: 2 },
    { institution: "minisante", group_assigned: false, users: 0 },
  ],
};

function handlers(puts: Array<Record<string, unknown>>) {
  return [
    http.get(api(`/client/clients/${CLIENT}/scope`), () => HttpResponse.json(scopeState)),
    http.get(api("/institutions"), () =>
      HttpResponse.json({
        mirror: true,
        registry_source: "configs/institutions.yaml",
        total: 2,
        needs_attention: 0,
        counts: {},
        items: [
          { slug: "minagri", display_name: "MINAGRI", registry_status: "active" },
          { slug: "minisante", display_name: "MINISANTE", registry_status: "active" },
        ],
      }),
    ),
    http.put(api(`/client/clients/${CLIENT}/scope`), async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      puts.push(body);
      if (!body.confirm) {
        return envelope(409, "scope_narrowing_requires_confirmation", "minisante still has users on shared-portal.", {
          removed_institutions: ["minisante"],
          access_at_risk: [{ institution: "minisante", group_assigned: false, users: 3 }],
        });
      }
      return HttpResponse.json({
        scope: { ...scopeState.scope, institutions: ["minagri"] },
        previous: scopeState.scope,
        removed_institutions: ["minisante"],
        access_withdrawn: [{ institution: "minisante", group_assigned: false, users: 3 }],
      });
    }),
  ];
}

async function narrowToMinagri() {
  renderPage(<ScopeEditor clientId={CLIENT} />);
  const minisante = await screen.findByRole("checkbox", { name: "minisante" });
  expect(minisante).toBeChecked();
  await userEvent.click(minisante);
  await userEvent.click(screen.getByTestId("scope-save"));
}

describe("ScopeEditor — 409 confirmation flow", () => {
  it("409 → dialog with the wrapper's numbers → confirm re-sends with confirm: true", async () => {
    const puts: Array<Record<string, unknown>> = [];
    server.use(...handlers(puts));
    await narrowToMinagri();

    const dialog = await screen.findByTestId("scope-confirm-dialog");
    expect(within(dialog).getByText("minisante still has users on shared-portal.")).toBeInTheDocument();
    expect(within(dialog).getByTestId("scope-at-risk")).toHaveTextContent("minisante — 3 users on the client");
    expect(puts).toEqual([{ confirm: false, cross_cutting: true, institutions: ["minagri"] }]);

    await userEvent.click(within(dialog).getByTestId("scope-confirm"));
    await waitFor(() => expect(puts).toHaveLength(2));
    expect(puts[1]).toEqual({ confirm: true, cross_cutting: true, institutions: ["minagri"] });
    await waitFor(() => expect(screen.queryByTestId("scope-confirm-dialog")).toBeNull());
    expect(await screen.findByText("Institution scope updated; access withdrawn from minisante.")).toBeInTheDocument();
  });

  it("cancel closes the dialog and sends nothing more", async () => {
    const puts: Array<Record<string, unknown>> = [];
    server.use(...handlers(puts));
    await narrowToMinagri();

    const dialog = await screen.findByTestId("scope-confirm-dialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByTestId("scope-confirm-dialog")).toBeNull());
    expect(puts).toHaveLength(1);
    expect(puts[0]?.confirm).toBe(false);
  });

  it("a cross-cutting scope with no institution cannot be saved", async () => {
    server.use(...handlers([]));
    renderPage(<ScopeEditor clientId={CLIENT} />);
    await userEvent.click(await screen.findByRole("checkbox", { name: "minagri" }));
    await userEvent.click(screen.getByRole("checkbox", { name: "minisante" }));
    expect(screen.getByTestId("scope-save")).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("must be available to at least one institution");
  });
});
