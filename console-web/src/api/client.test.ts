import { afterEach, describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";

import { api as apiPath, envelope, server } from "@/test/server";
import { browser } from "@/auth/navigation";
import { api, unwrap, unwrapAs } from "./client";
import { ApiError, toApiError } from "./errors";
import { readCookie } from "./csrf";

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; Max-Age=0; path=/";
  vi.restoreAllMocks();
});

describe("ApiError mapping", () => {
  it("reads the wrapper envelope", () => {
    const e = toApiError(409, { error: "state_changed", message: "Changed since you looked", details: { current_state: "granted" } });
    expect(e).toBeInstanceOf(ApiError);
    expect(e.code).toBe("state_changed");
    expect(e.message).toBe("Changed since you looked");
    expect(e.details).toEqual({ current_state: "granted" });
    expect(e.isConflict).toBe(true);
  });
  it("understands the legacy FastAPI shapes", () => {
    expect(toApiError(404, { detail: "Client 'x' not found" }).message).toBe("Client 'x' not found");
    const structured = toApiError(409, { detail: { reason: "scope_narrowing", message: "m", removed_institutions: ["a"] } });
    expect(structured.code).toBe("scope_narrowing");
    expect(structured.details.removed_institutions).toEqual(["a"]);
    expect(toApiError(422, { detail: [{ msg: "field required" }, { msg: "too short" }] }).message).toBe("field required; too short");
    expect(toApiError(500, undefined, "Server Error").message).toBe("Server Error");
  });
});

describe("api client", () => {
  it("unwraps typed data and throws ApiError with the envelope", async () => {
    server.use(
      http.get(apiPath("/client/clients"), () => HttpResponse.json([{ id: "1", clientId: "a" }])),
      http.get(apiPath("/realm/mfa"), () => envelope(403, "access_denied", "Platform admins only")),
    );
    await expect(unwrap(api.GET("/client/clients"))).resolves.toEqual([{ id: "1", clientId: "a" }]);
    await expect(unwrapAs(api.GET("/realm/mfa"))).rejects.toMatchObject({ status: 403, code: "access_denied", message: "Platform admins only" });
  });

  it("sends X-XSRF-TOKEN from the cookie on mutating requests only, with credentials", async () => {
    document.cookie = "XSRF-TOKEN=tok%20123; path=/";
    expect(readCookie("XSRF-TOKEN")).toBe("tok 123");
    const seen: Array<{ method: string; xsrf: string | null; credentials: string }> = [];
    server.use(
      http.all(apiPath("/client/clients*"), ({ request }) => {
        seen.push({ method: request.method, xsrf: request.headers.get("X-XSRF-TOKEN"), credentials: request.credentials });
        return HttpResponse.json({});
      }),
    );
    await unwrap(api.GET("/client/clients"));
    await unwrap(api.DELETE("/client/clients/{client_id}", { params: { path: { client_id: "x" } } }));
    expect(seen[0]).toMatchObject({ method: "GET", xsrf: null });
    expect(seen[1]).toMatchObject({ method: "DELETE", xsrf: "tok 123" });
  });

  it("a 401 re-checks the BFF session and sends the browser to /bff/login?returnTo=…", async () => {
    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    vi.spyOn(browser, "currentPath").mockReturnValue("/clients/abc?tab=scope");
    server.use(
      http.get(apiPath("/client/clients"), () => envelope(401, "unauthorized", "expired")),
      http.get("*/bff/session", () => HttpResponse.json({ authenticated: false })),
    );
    await expect(unwrap(api.GET("/client/clients"))).rejects.toMatchObject({ status: 401 });
    await vi.waitFor(() => expect(assign).toHaveBeenCalledWith("/bff/login?returnTo=%2Fclients%2Fabc%3Ftab%3Dscope"));
  });

  it("a 401 while the BFF session is still alive does not redirect", async () => {
    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    server.use(http.get(apiPath("/client/clients"), () => envelope(401, "unauthorized", "token rejected")));
    await expect(unwrap(api.GET("/client/clients"))).rejects.toBeInstanceOf(ApiError);
    await new Promise((r) => setTimeout(r, 50));
    expect(assign).not.toHaveBeenCalled();
  });
});
