import { describe, expect, it } from "vitest";

import type { InstitutionAuditEvent } from "../types";
import { appOf, display, eventKey, isFiltered, parseFilters, summaryParts, targetOf, toQuery, toSearch, totalPages } from "./auditView";

const ev = (over: Partial<InstitutionAuditEvent> = {}): InstitutionAuditEvent => ({
  id: "1",
  event_type: "admin.role.assigned",
  category: "admin",
  details: {},
  severity: "INFO",
  prev_hash: "a",
  event_hash: "b",
  created_at: "2026-10-20T10:00:00Z",
  ...over,
});

describe("audit filters", () => {
  it("parses the URL, dropping invalid severity/dates and clamping page", () => {
    const f = parseFilters(new URLSearchParams("event_type=admin.user.created&severity=LOUD&from=2026-10-01&to=yesterday&page=-3&target=%20u1%20"));
    expect(f).toEqual({ event_type: "admin.user.created", target: "u1", severity: undefined, from: "2026-10-01", to: undefined, page: 1 });
    expect(isFiltered(f)).toBe(true);
    expect(isFiltered(parseFilters(new URLSearchParams("page=2")))).toBe(false);
  });
  it("builds the API query with UTC day bounds and offset; never an institution", () => {
    const q = toQuery({ from: "2026-10-01", to: "2026-10-02", page: 3 });
    expect(q).toMatchObject({ from_date: "2026-10-01T00:00:00Z", to_date: "2026-10-02T23:59:59.999Z", limit: 50, offset: 100 });
    expect(q).not.toHaveProperty("institution");
  });
  it("URL params omit page 1", () => {
    expect(toSearch({ page: 1, severity: "INFO" })).toMatchObject({ page: undefined, severity: "INFO" });
    expect(toSearch({ page: 2 }).page).toBe("2");
    expect(totalPages(0)).toBe(1);
    expect(totalPages(101)).toBe(3);
  });
});

describe("event rendering", () => {
  it("names known event types only", () => {
    expect(eventKey("admin.role.assigned")).toBe("admin_role_assigned");
    expect(eventKey("weird.thing")).toBeNull();
  });
  it("target, app and summary from details", () => {
    const e = ev({ target: "u-1", app_id: null, details: { target_username: "aline", target_client: "minagri-app", action: "grant_user_access", roles: ["r1", "r2"], outcome: "failure" } });
    expect(targetOf(e)).toEqual({ name: "aline", id: "u-1" });
    expect(appOf(e)).toBe("minagri-app");
    expect(summaryParts(e)).toEqual({ action: "grant_user_access", rest: ["r1, r2", "failure"] });
    expect(targetOf(ev({ target: "x", details: { role_name: "x" } }))).toEqual({ name: undefined, id: "x" });
    expect(display({ a: 1 })).toBe('{"a":1}');
    expect(display(null)).toBe("—");
  });
});

describe("knownPages (wrapper reports the page's own count as total)", () => {
  it("offers a next page when this page is full", async () => {
    const { knownPages, PAGE_SIZE } = await import("./auditView");
    expect(knownPages(PAGE_SIZE, 1, PAGE_SIZE)).toBe(2);
    expect(knownPages(3, 2, 3)).toBe(2);
    expect(knownPages(0, 1, 0)).toBe(1);
  });
});
