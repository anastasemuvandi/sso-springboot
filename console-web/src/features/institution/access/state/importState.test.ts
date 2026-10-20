import { describe, expect, it } from "vitest";

import type { ImportItem } from "../types";
import { filterItems, grantsToApply, outcomeTone, reportCsv } from "./importState";

const item = (outcome: ImportItem["outcome"]): ImportItem => ({ row: 2, employee_number: "E1", name: "A", client_id: "c", outcome, reason: null, message: null });

describe("import helpers", () => {
  it("attention = invalid, rejected, cleared-not-revoked", () => {
    const items = (["will_grant", "invalid", "removal_ignored", "rejected", "already_has_access"] as const).map(item);
    expect(filterItems(items, "attention").map((i) => i.outcome)).toEqual(["invalid", "removal_ignored", "rejected"]);
    expect(filterItems(items, "all")).toHaveLength(5);
    expect(filterItems(items, "will_grant")).toHaveLength(1);
  });

  it("counts grants to apply, never removals", () => {
    expect(grantsToApply({ summary: { will_grant: 3, will_create_account: 2, removal_ignored: 9 } })).toBe(5);
    expect(grantsToApply({ summary: {} })).toBe(0);
    expect(outcomeTone("removal_ignored")).toBe("outline");
    expect(outcomeTone("invalid")).toBe("destructive");
  });

  it("CSV neutralises formulas and quotes", () => {
    const csv = reportCsv({
      results: [{ employee_number: "=E1", client_id: "c", status: "rejected", message: 'say "hi", ok', account_created: true, setup_email: "sent" }],
    });
    expect(csv.split("\n")[1]).toBe(`'=E1,c,rejected,,"say ""hi"", ok",yes,sent`);
  });
});
