import { describe, expect, it } from "vitest";

import { accountView, dateOnly, detailName, displayName, parseDirectoryParams, sortPatch, toListQuery, toSearchParams, totalPages, waitingDays } from "./directory";

describe("employee directory URL state", () => {
  it("parses filters and ignores junk", () => {
    const s = parseDirectoryParams(new URLSearchParams("account=unlinked&status=bogus&unmapped=1&sort=name&dir=desc&page=3&institution=minagri"));
    expect(s).toEqual({ search: undefined, institution: "minagri", status: undefined, account: "unlinked", unmapped: true, sort: "name", dir: "desc", page: 3 });
    const d = parseDirectoryParams(new URLSearchParams("account=nope&sort=evil&page=-2"));
    expect(d).toMatchObject({ account: undefined, sort: "employee_number", dir: "asc", page: 1, unmapped: false });
  });

  it("accepts the top-bar `q` as a search", () => {
    expect(parseDirectoryParams(new URLSearchParams("q=aline")).search).toBe("aline");
  });

  it("builds the list query with paging", () => {
    const q = toListQuery(parseDirectoryParams(new URLSearchParams("account=ambiguous&page=2")));
    expect(q).toEqual({ account: "ambiguous", sort: "employee_number", dir: "asc", limit: 50, offset: 50 });
    expect(toListQuery(parseDirectoryParams(new URLSearchParams("unmapped=1"))).unmapped).toBe(true);
  });

  it("round-trips to the URL, patches remove keys", () => {
    const s = parseDirectoryParams(new URLSearchParams("account=unlinked&page=2&search=x"));
    expect(toSearchParams(s).toString()).toBe("search=x&account=unlinked&sort=employee_number&dir=asc&page=2");
    expect(toSearchParams(s, { account: undefined, page: undefined }).toString()).toBe("search=x&sort=employee_number&dir=asc");
    expect(toSearchParams(s, { unmapped: "1" }).get("unmapped")).toBe("1");
  });

  it("sort toggles direction on the same key", () => {
    const s = parseDirectoryParams(new URLSearchParams("sort=name&dir=asc"));
    expect(sortPatch(s, "name")).toEqual({ sort: "name", dir: "desc", page: undefined });
    expect(sortPatch(s, "position")).toEqual({ sort: "position", dir: "asc", page: undefined });
  });

  it("helpers", () => {
    expect(totalPages(0)).toBe(1);
    expect(totalPages(101)).toBe(3);
    expect(displayName({ employee_number: "E1", first_name: "Aline", last_name: null })).toBe("Aline");
    expect(displayName({ employee_number: "E1" })).toBe("E1");
    expect(detailName({ id: "E2", firstName: null, lastName: null })).toBe("E2");
    expect(dateOnly("2026-10-20T10:00:00Z")).toBe("2026-10-20");
    expect(dateOnly("nope")).toBe("—");
    expect(waitingDays("2026-10-10T00:00:00Z", Date.parse("2026-10-20T12:00:00Z"))).toBe(10);
    expect(waitingDays(null)).toBeNull();
    expect(accountView({ match_state: "ambiguous", keycloak_user_id: "x" })).toBe("ambiguous");
    expect(accountView({ match_state: "matched", keycloak_user_id: "x" })).toBe("linked");
    expect(accountView({ match_state: "unmatched", keycloak_user_id: null })).toBe("none");
  });
});
