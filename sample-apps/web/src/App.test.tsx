import { GorssoProvider, MemoryTransactionStore } from "@gorsso/react";
import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { App } from "./App";

describe("sample SPA", () => {
  it("renders the home page and starts PKCE login when /records is opened anonymously", async () => {
    const navigate = vi.fn();
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response("{}", { status: 503 }));
    vi.stubGlobal("fetch", fetchMock);
    render(
      <MemoryRouter initialEntries={["/records"]}>
        <GorssoProvider
          config={{
            mode: "pkce",
            baseUrl: "http://localhost:8000",
            clientId: "minagri-kubaka-web",
            redirectUri: "http://localhost:5173/callback",
            navigate,
            transactionStore: new MemoryTransactionStore(),
          }}
        >
          <App />
        </GorssoProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(navigate).toHaveBeenCalled());
    const url = new URL(navigate.mock.calls[0]![0] as string);
    expect(url.pathname).toBe("/oauth2/authorize");
    expect(url.searchParams.get("client_id")).toBe("minagri-kubaka-web");
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(screen.getByText(/Sign in with GoRSSO/)).toBeInTheDocument();
    vi.unstubAllGlobals();
  });
});
