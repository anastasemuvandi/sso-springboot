import { describe, expect, it } from "vitest";

import { zeroOverview } from "@/test/fixtures/overview";
import { buildInstitutionAttention } from "./attention";

describe("institution needs-attention", () => {
  it("an unavailable section contributes nothing (no false zero)", () => {
    const items = buildInstitutionAttention({
      overview: zeroOverview({ leavers: { available: false, error: "mirror off" }, logins: { available: false, error: "events off" } }),
      summary: null,
      joinColumns: null,
    });
    expect(items.map((i) => i.id)).toEqual(["setup-email"]);
  });
  it("counts from every source", () => {
    const items = buildInstitutionAttention({
      overview: zeroOverview({ leavers: { total: 2 }, password_setup: { counts: { total: 3, failed: 1, not_configured: 1, sent: 1, unknown: 0 } } }),
      summary: { total: 10, linked: 5, unlinked: 4, unmapped: 0, ambiguous: 1, no_email: 2 },
      joinColumns: { total: 3, configured: 1, unconfigured: 2, outstanding: 1, needs_platform_admin: 1, malformed: 0 },
    });
    const by = Object.fromEntries(items.map((i) => [i.id, i.count]));
    expect(by).toEqual({ leavers: 2, "setup-email": 2, locked: 0, unlinked: 4, ambiguous: 1, "no-email": 2, "join-own": 1, "join-shared": 1 });
  });
});
