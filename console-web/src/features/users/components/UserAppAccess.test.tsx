import { describe, expect, it } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, envelope, server } from "@/test/server";
import { renderPage } from "@/test/render";
import type { UserClientAccessItem, UserClientAccessState } from "../types";
import { UserAppAccess } from "./UserAppAccess";

const item = (client_id: string, state: UserClientAccessState, over: Partial<UserClientAccessItem> = {}): UserClientAccessItem => ({
  client_id,
  client_uuid: `uuid-${client_id}`,
  name: null,
  state,
  has_access: state === "granted" || state === "open" || state === "exempt" ? true : state === "unknown" ? null : false,
  reason: null,
  direct: state === "granted",
  via_groups: [],
  denied: state === "revoked",
  denied_by_name: state === "revoked",
  denied_groups: [],
  error: state === "unknown" ? "policies unreadable" : null,
  ...over,
});

/** A tiny fake of the wrapper: per-client state, changed by POSTs. */
function fakeWrapper(initial: Record<string, UserClientAccessState>) {
  const states = { ...initial };
  const posts: Array<{ client: string; body: { action: string; expected_state: string } }> = [];
  let reads = 0;
  const list = () => ({ user_id: "u1", username: "jdoe", groups: [], items: Object.entries(states).map(([c, s]) => item(c, s)) });
  server.use(
    http.get(api("/authorization/users/u1/client-access"), () => {
      reads += 1;
      return HttpResponse.json(list());
    }),
    http.post(api("/authorization/users/u1/client-access/:clientId"), async ({ params, request }) => {
      const client = String(params.clientId);
      const body = (await request.json()) as { action: "grant" | "revoke" | "restore"; expected_state: string };
      posts.push({ client, body });
      if (states[client] !== body.expected_state) {
        return envelope(409, "state_changed", "The access changed since you loaded it.", { current_state: states[client] });
      }
      const previous = states[client]!;
      states[client] = body.action === "revoke" ? "revoked" : "granted";
      return HttpResponse.json({ item: item(client, states[client]!), previous_state: previous, unassigned: false, denied: body.action === "revoke", lifted_deny: previous === "revoked" });
    }),
  );
  return { states, posts, reads: () => reads };
}

function row(clientId: string) {
  return screen.getByTestId(`access-row-${clientId}`);
}

describe("UserAppAccess", () => {
  it("renders every state with its label", async () => {
    fakeWrapper({ a: "granted", b: "open", c: "revoked", d: "not_granted", e: "exempt", f: "unknown" });
    renderPage(<UserAppAccess userId="u1" username="jdoe" />);
    await screen.findByTestId("access-row-a");
    const label = (c: string) => within(row(c)).getByTestId("access-state").textContent;
    expect(label("a")).toBe("Granted");
    expect(label("b")).toBe("Open");
    expect(label("c")).toBe("Revoked");
    expect(label("d")).toBe("Not granted");
    expect(label("e")).toBe("Exempt");
    expect(label("f")).toBe("Unknown");
    // exempt and unknown offer no action
    expect(within(row("e")).queryByRole("button")).toBeNull();
    expect(within(row("f")).queryByRole("button")).toBeNull();
  });

  it("grant on a not_granted row sends the state it saw, and the row becomes Granted", async () => {
    const w = fakeWrapper({ d: "not_granted" });
    renderPage(<UserAppAccess userId="u1" />);
    await userEvent.click(await within(await screen.findByTestId("access-row-d")).findByRole("button", { name: "Grant" }));
    await waitFor(() => expect(within(row("d")).getByTestId("access-state")).toHaveTextContent("Granted"));
    expect(w.posts).toEqual([{ client: "d", body: { action: "grant", expected_state: "not_granted" } }]);
  });

  it("revoke on a granted row asks first, then sends revoke and shows Revoked", async () => {
    const w = fakeWrapper({ a: "granted" });
    renderPage(<UserAppAccess userId="u1" username="jdoe" />);
    await userEvent.click(await within(await screen.findByTestId("access-row-a")).findByRole("button", { name: "Revoke" }));
    // nothing sent before the confirmation
    expect(w.posts).toEqual([]);
    const dialog = await screen.findByRole("alertdialog");
    await userEvent.click(within(dialog).getByRole("button", { name: "Revoke" }));
    await waitFor(() => expect(within(row("a")).getByTestId("access-state")).toHaveTextContent("Revoked"));
    expect(w.posts).toEqual([{ client: "a", body: { action: "revoke", expected_state: "granted" } }]);
  });

  it("cancelling the revoke sends nothing", async () => {
    const w = fakeWrapper({ a: "granted" });
    renderPage(<UserAppAccess userId="u1" />);
    await userEvent.click(await within(await screen.findByTestId("access-row-a")).findByRole("button", { name: "Revoke" }));
    await userEvent.click(within(await screen.findByRole("alertdialog")).getByRole("button", { name: "Cancel" }));
    expect(w.posts).toEqual([]);
    expect(within(row("a")).getByTestId("access-state")).toHaveTextContent("Granted");
  });

  it("restore on a revoked row sends action restore with expected_state revoked", async () => {
    const w = fakeWrapper({ c: "revoked" });
    renderPage(<UserAppAccess userId="u1" />);
    await userEvent.click(await within(await screen.findByTestId("access-row-c")).findByRole("button", { name: "Restore" }));
    await waitFor(() => expect(within(row("c")).getByTestId("access-state")).toHaveTextContent("Granted"));
    expect(w.posts).toEqual([{ client: "c", body: { action: "restore", expected_state: "revoked" } }]);
  });

  it("a 409 state_changed shows an error and re-reads the list", async () => {
    const w = fakeWrapper({ d: "not_granted" });
    renderPage(<UserAppAccess userId="u1" />);
    const grant = await within(await screen.findByTestId("access-row-d")).findByRole("button", { name: "Grant" });
    // Someone else granted it meanwhile.
    w.states.d = "granted";
    const readsBefore = w.reads();
    await userEvent.click(grant);
    expect(await screen.findByText(/changed since the page was loaded/)).toBeInTheDocument();
    await waitFor(() => expect(w.reads()).toBeGreaterThan(readsBefore));
    await waitFor(() => expect(within(row("d")).getByTestId("access-state")).toHaveTextContent("Granted"));
    expect(w.posts).toEqual([{ client: "d", body: { action: "grant", expected_state: "not_granted" } }]);
  });

  it("a revocation through a group offers no restore", async () => {
    server.use(
      http.get(api("/authorization/users/u1/client-access"), () =>
        HttpResponse.json({ user_id: "u1", username: "jdoe", groups: [], items: [item("g", "revoked", { denied_groups: [{ id: "x", name: "minagri" }], denied_by_name: false })] }),
      ),
    );
    renderPage(<UserAppAccess userId="u1" />);
    const r = await screen.findByTestId("access-row-g");
    expect(within(r).queryByRole("button")).toBeNull();
    expect(within(r).getByText("denied through a group")).toBeInTheDocument();
  });
});
