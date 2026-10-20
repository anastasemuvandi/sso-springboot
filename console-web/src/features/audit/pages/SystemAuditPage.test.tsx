import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";

import { api, envelope, server } from "@/test/server";
import { renderPage } from "@/test/render";
import { Component as SystemAuditPage } from "./SystemAuditPage";

const event = {
  id: "e1",
  event_type: "admin.client.scope.changed",
  category: "ADMIN",
  performed_by: "admin@risa.gov.rw",
  app_id: "minagri-kubaka-web",
  details: {},
  severity: "WARNING",
  prev_hash: "a",
  event_hash: "b",
  created_at: "2026-10-20T09:00:00Z",
};

function handlers(integrity: () => Response) {
  return [
    http.get(api("/audit/events"), () => HttpResponse.json({ total: 1, limit: 100, offset: 0, items: [event] })),
    http.get(api("/audit/retention/policies"), () => HttpResponse.json([{ id: "p", category: null, retention_days: 365, legal_hold: true, created_at: "", updated_at: "" }])),
    http.get(api("/audit/integrity/verify"), integrity),
  ];
}

afterEach(() => vi.restoreAllMocks());

describe("SystemAuditPage — integrity verification", () => {
  it("valid chain", async () => {
    server.use(...handlers(() => HttpResponse.json({ valid: true, total_checked: 1234, tampered_count: 0 })));
    renderPage(<SystemAuditPage />, { route: "/system-audit", path: "/system-audit" });
    expect(await screen.findByTestId("integrity-valid")).toHaveTextContent("1,234 records verified");
    expect(await screen.findByText("admin.client.scope.changed")).toBeInTheDocument();
    expect(screen.getByText("Held")).toBeInTheDocument();
  });

  it("tampered chain names the first bad record", async () => {
    server.use(...handlers(() => HttpResponse.json({ valid: false, total_checked: 50, tampered_count: 2, first_tampered_id: "evt-42", first_tampered_at: "2026-10-01T00:00:00Z" })));
    renderPage(<SystemAuditPage />, { route: "/system-audit", path: "/system-audit" });
    const banner = await screen.findByTestId("integrity-tampered");
    expect(banner).toHaveTextContent("2 of 50");
    expect(banner).toHaveTextContent("evt-42");
    expect(screen.queryByTestId("integrity-valid")).toBeNull();
  });

  it("a failed verification is never shown as intact", async () => {
    server.use(...handlers(() => envelope(502, "audit_unavailable", "audit service down")));
    renderPage(<SystemAuditPage />, { route: "/system-audit", path: "/system-audit" });
    expect(await screen.findByTestId("integrity-error")).toHaveTextContent("audit service down");
    expect(screen.queryByTestId("integrity-valid")).toBeNull();
  });

  it("re-verifies over a date window", async () => {
    const seen: string[] = [];
    server.use(
      ...handlers(() => {
        throw new Error("unused");
      }),
    );
    server.use(
      http.get(api("/audit/integrity/verify"), ({ request }) => {
        seen.push(new URL(request.url).search);
        return HttpResponse.json({ valid: true, total_checked: 1, tampered_count: 0 });
      }),
    );
    const { container } = renderPage(<SystemAuditPage />, { route: "/system-audit", path: "/system-audit" });
    await screen.findByTestId("integrity-valid");
    const dates = container.querySelectorAll<HTMLInputElement>('section input[type="date"]');
    fireEvent.change(dates[0]!, { target: { value: "2026-10-01" } });
    await userEvent.click(screen.getByTestId("integrity-verify"));
    await waitFor(() => expect(seen).toEqual(["", "?from_date=2026-10-01"]));
  });

  it("exports CSV with the active filters", async () => {
    let exported = "";
    server.use(
      ...handlers(() => HttpResponse.json({ valid: true, total_checked: 1, tampered_count: 0 })),
      http.get(api("/audit/export/csv"), ({ request }) => {
        exported = new URL(request.url).search;
        return new HttpResponse("a,b\n", { headers: { "Content-Type": "text/csv", "Content-Disposition": 'attachment; filename="audit.csv"' } });
      }),
    );
    const create = vi.fn(() => "blob:x");
    Object.assign(URL, { createObjectURL: create, revokeObjectURL: vi.fn() });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    renderPage(<SystemAuditPage />, { route: "/system-audit?category=ADMIN&severity=WARNING", path: "/system-audit" });
    await screen.findByText("admin.client.scope.changed");
    await userEvent.click(screen.getByTestId("export-csv"));
    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(exported).toBe("?category=ADMIN&limit=100");
  });
});
