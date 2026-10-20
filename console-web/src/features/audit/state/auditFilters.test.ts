import { describe, expect, it } from "vitest";

import { auditExportPath, clampLimit, filtersFromSearch, integrityKind, pageInfo, searchFromFilters } from "./auditFilters";

describe("audit filters", () => {
  it("clamps the page size", () => {
    expect(clampLimit(undefined)).toBe(100);
    expect(clampLimit("abc")).toBe(100);
    expect(clampLimit("0")).toBe(100);
    expect(clampLimit("5000")).toBe(1000);
    expect(clampLimit(25)).toBe(25);
  });

  it("round-trips through the URL, dropping blanks", () => {
    const f = filtersFromSearch(new URLSearchParams("category=ADMIN&user_id=%20&severity=CRITICAL&limit=50&offset=100"));
    expect(f).toEqual({ category: "ADMIN", severity: "CRITICAL", limit: 50, offset: 100 });
    expect(searchFromFilters(f).toString()).toBe("category=ADMIN&severity=CRITICAL&limit=50&offset=100");
    expect(searchFromFilters({ limit: 100, offset: 0 }).toString()).toBe("");
  });

  it("export forwards only export filters", () => {
    expect(auditExportPath("csv", { category: "ADMIN", severity: "INFO", offset: 200, limit: 100, from_date: "2026-01-01" })).toBe(
      "/audit/export/csv?category=ADMIN&from_date=2026-01-01&limit=100",
    );
    expect(auditExportPath("pdf", {})).toBe("/audit/export/pdf");
  });

  it("paging", () => {
    expect(pageInfo(250, 100, 100, 100)).toEqual({ from: 101, to: 200, total: 250, hasPrev: true, hasNext: true, prevOffset: 0, nextOffset: 200 });
    expect(pageInfo(0, 0, 100, 0)).toMatchObject({ from: 0, to: 0, hasPrev: false, hasNext: false });
  });

  it("integrity kinds", () => {
    expect(integrityKind({ valid: true, total_checked: 10, tampered_count: 0 })).toBe("valid");
    expect(integrityKind({ valid: true, total_checked: 0, tampered_count: 0 })).toBe("empty");
    expect(integrityKind({ valid: false, total_checked: 10, tampered_count: 1 })).toBe("tampered");
    expect(integrityKind({ valid: true, total_checked: 10, tampered_count: 2 })).toBe("tampered");
  });
});
