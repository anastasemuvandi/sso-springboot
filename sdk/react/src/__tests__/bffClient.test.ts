import { describe, expect, it, vi } from "vitest";
import { BffAuthClient } from "../bffClient";
import { jsonResponse } from "./helpers";

describe("BffAuthClient", () => {
  it("loads /bff/session with cookies and normalises snake_case", async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      jsonResponse({ authenticated: true, username: "jdoe", roles: ["inst:minisante:staff"], join_key: "0788000000", institution_slug: "minisante", platformAdmin: false }),
    );
    const c = new BffAuthClient({ mode: "bff", bffBase: "http://bff/", fetch: fetchMock });
    await c.init();
    expect(fetchMock.mock.calls[0]![0]).toBe("http://bff/bff/session");
    expect(fetchMock.mock.calls[0]![1]!.credentials).toBe("include");
    expect(c.getState().status).toBe("authenticated");
    expect(c.getState().session).toMatchObject({ joinKey: "0788000000", institutionSlug: "minisante", platformAdmin: false });
    expect(await c.getAccessToken()).toBeNull();
  });

  it("401 means anonymous; 5xx is an error", async () => {
    const c = new BffAuthClient({ mode: "bff", fetch: vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 401 })) });
    await c.init();
    expect(c.getState().status).toBe("unauthenticated");
    const d = new BffAuthClient({ mode: "bff", fetch: vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 502 })) });
    await d.init();
    expect(d.getState().status).toBe("error");
  });

  it("login goes to /bff/login?returnTo= (path only)", async () => {
    const navigate = vi.fn();
    const c = new BffAuthClient({ mode: "bff", navigate });
    await c.login("/records?page=2");
    expect(navigate).toHaveBeenCalledWith("/bff/login?returnTo=%2Frecords%3Fpage%3D2");
    await c.login("https://evil.example/");
    expect(navigate).toHaveBeenLastCalledWith("/bff/login?returnTo=%2F");
  });

  it("logout POSTs /bff/logout with the XSRF header and follows logoutUrl", async () => {
    document.cookie = "XSRF-TOKEN=tok123";
    const navigate = vi.fn();
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ logoutUrl: "http://localhost:8000/oauth2/logout?client_id=x" }));
    const c = new BffAuthClient({ mode: "bff", navigate, fetch: fetchMock });
    await c.logout();
    const init = fetchMock.mock.calls[0]![1]!;
    expect(fetchMock.mock.calls[0]![0]).toBe("/bff/logout");
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>)["X-XSRF-TOKEN"]).toBe("tok123");
    expect(navigate).toHaveBeenCalledWith("http://localhost:8000/oauth2/logout?client_id=x");
    expect(c.getState().status).toBe("unauthenticated");
  });
});
