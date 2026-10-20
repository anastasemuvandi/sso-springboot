import { act, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Outlet, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { BffAuthClient } from "../bffClient";
import { StateHolder, type AuthClient } from "../client";
import { GorssoProvider } from "../GorssoProvider";
import { RequireAuth, RequireRole } from "../guards";
import { useAuth, useJoinKey, useSession } from "../hooks";
import type { AuthState, Session } from "../types";
import { jsonResponse } from "./helpers";

/** A controllable client for rendering tests. */
class FakeClient implements AuthClient {
  readonly holder = new StateHolder();
  login = vi.fn(async (_returnTo?: string) => {});
  logout = vi.fn(async () => {});
  getState = () => this.holder.get();
  subscribe = (l: () => void) => this.holder.subscribe(l);
  init = vi.fn(async () => {});
  getAccessToken = async () => "at";
  dispose = () => {};
  set(state: AuthState) {
    act(() => this.holder.set(state));
  }
  signIn(session: Partial<Session>) {
    this.set({ status: "authenticated", session: { authenticated: true, ...session }, error: null });
  }
}

const cfg = { mode: "bff" as const };

function renderWith(client: AuthClient, ui: React.ReactNode, path = "/") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <GorssoProvider config={cfg} client={client}>
        {ui}
      </GorssoProvider>
    </MemoryRouter>,
  );
}

describe("<RequireAuth>", () => {
  it("shows the fallback while loading, then the children once authenticated", () => {
    const c = new FakeClient();
    renderWith(c, <RequireAuth fallback={<p>loading…</p>}>secret</RequireAuth>);
    expect(screen.getByText("loading…")).toBeInTheDocument();
    expect(screen.queryByText("secret")).toBeNull();
    c.signIn({ username: "jdoe" });
    expect(screen.getByText("secret")).toBeInTheDocument();
    expect(c.login).not.toHaveBeenCalled();
  });

  it("starts login with the current location when anonymous", async () => {
    const c = new FakeClient();
    window.history.replaceState({}, "", "/records?page=2");
    renderWith(c, <RequireAuth>secret</RequireAuth>);
    c.set({ status: "unauthenticated", session: { authenticated: false }, error: null });
    await waitFor(() => expect(c.login).toHaveBeenCalledWith("/records?page=2"));
    expect(screen.queryByText("secret")).toBeNull();
  });

  it("does not redirect when redirect=false", () => {
    const c = new FakeClient();
    renderWith(c, <RequireAuth redirect={false} fallback={<p>please sign in</p>}>secret</RequireAuth>);
    c.set({ status: "unauthenticated", session: { authenticated: false }, error: null });
    expect(screen.getByText("please sign in")).toBeInTheDocument();
    expect(c.login).not.toHaveBeenCalled();
  });

  it("works as a React Router v6 layout route with <Outlet/>", () => {
    const c = new FakeClient();
    c.holder.set({ status: "authenticated", session: { authenticated: true }, error: null });
    renderWith(
      c,
      <Routes>
        <Route element={<RequireAuth><Outlet /></RequireAuth>}>
          <Route path="/records" element={<p>records page</p>} />
        </Route>
      </Routes>,
      "/records",
    );
    expect(screen.getByText("records page")).toBeInTheDocument();
  });

  it("shows an error when sign-in failed", () => {
    const c = new FakeClient();
    renderWith(c, <RequireAuth>secret</RequireAuth>);
    act(() =>
      c.holder.set({ status: "error", session: { authenticated: false }, error: Object.assign(new Error("x"), { code: "nonce_mismatch", name: "GorssoAuthError" }) as never }),
    );
    expect(screen.getByRole("alert")).toHaveTextContent("nonce_mismatch");
  });
});

describe("<RequireRole>", () => {
  const minagriStaff = { roles: ["inst:minagri:viewer", "offline_access"], clientRoles: ["editor"] };

  it("renders for the exact institution role", () => {
    const c = new FakeClient();
    c.holder.set({ status: "authenticated", session: { authenticated: true, ...minagriStaff }, error: null });
    renderWith(c, <RequireRole role="inst:minagri:viewer">records</RequireRole>);
    expect(screen.getByText("records")).toBeInTheDocument();
  });

  it("renders for an institution wildcard and client roles", () => {
    const c = new FakeClient();
    c.holder.set({ status: "authenticated", session: { authenticated: true, ...minagriStaff }, error: null });
    renderWith(
      c,
      <>
        <RequireRole role="inst:minagri:*"><p>wild</p></RequireRole>
        <RequireRole role="client:editor"><p>client</p></RequireRole>
        <RequireRole anyOf={["inst:minagri:admin", "inst:minagri:viewer"]}><p>any</p></RequireRole>
      </>,
    );
    expect(screen.getByText("wild")).toBeInTheDocument();
    expect(screen.getByText("client")).toBeInTheDocument();
    expect(screen.getByText("any")).toBeInTheDocument();
  });

  it("shows forbidden for another institution's role", () => {
    const c = new FakeClient();
    c.holder.set({ status: "authenticated", session: { authenticated: true, ...minagriStaff }, error: null });
    renderWith(
      c,
      <>
        <RequireRole role="inst:minisante:viewer" forbidden={<p>no access</p>}>
          records
        </RequireRole>
        <RequireRole role="inst:minisante:*">wild</RequireRole>
      </>,
    );
    expect(screen.getByText("no access")).toBeInTheDocument();
    expect(screen.queryByText("records")).toBeNull();
    expect(screen.queryByText("wild")).toBeNull();
    expect(screen.getByRole("alert")).toHaveTextContent("403");
  });

  it("anonymous callers are sent to login, not shown forbidden", async () => {
    const c = new FakeClient();
    renderWith(c, <RequireRole role="inst:minagri:viewer" forbidden={<p>no access</p>}>records</RequireRole>);
    c.set({ status: "unauthenticated", session: { authenticated: false }, error: null });
    await waitFor(() => expect(c.login).toHaveBeenCalled());
    expect(screen.queryByText("no access")).toBeNull();
  });
});

describe("hooks over the real BFF client", () => {
  function Probe() {
    const { session, status } = useSession();
    const joinKey = useJoinKey();
    const auth = useAuth();
    return (
      <p>
        {status}|{session.username ?? "-"}|{joinKey ?? "-"}|{String(auth.hasRole("inst:minisante:*"))}
      </p>
    );
  }

  it("useSession/useJoinKey/useAuth read GET /bff/session", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({ authenticated: true, username: "amina", joinKey: "amina@moh.gov.rw", roles: ["inst:minisante:staff"] }),
    );
    render(
      <GorssoProvider config={{ mode: "bff", fetch: fetchMock }}>
        <Probe />
      </GorssoProvider>,
    );
    await waitFor(() => expect(screen.getByText("authenticated|amina|amina@moh.gov.rw|true")).toBeInTheDocument());
  });

  it("hooks outside the provider throw a clear error", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    expect(() => render(<Probe />)).toThrow(/GorssoProvider/);
    spy.mockRestore();
  });

  it("an injected BffAuthClient is used as-is", async () => {
    const client = new BffAuthClient({ mode: "bff", fetch: vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 401 })) });
    render(
      <GorssoProvider config={cfg} client={client}>
        <Probe />
      </GorssoProvider>,
    );
    await waitFor(() => expect(screen.getByText("unauthenticated|-|-|false")).toBeInTheDocument());
  });
});
