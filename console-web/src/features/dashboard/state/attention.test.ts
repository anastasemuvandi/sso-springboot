import { describe, expect, it } from "vitest";

import { zeroOverview } from "@/test/fixtures/overview";
import type { Client } from "@/features/clients/types";
import { buildAttention, joinKeysOutstanding, unavailableSources } from "./attention";

const c = (clientId: string, kind: "institution" | "cross-cutting" | "platform", explicit = false): Client => ({
  id: clientId,
  clientId,
  scope: { client_id: clientId, kind, owner_institution: null, cross_cutting: kind === "cross-cutting", all_institutions: false, institutions: [], inferred: false },
  join_column: { client_id: clientId, join_column: "email", claim: "join_key", user_source: "email", explicit },
});

describe("needs-attention", () => {
  it("join keys: scoped clients only, Keycloak internals excluded", () => {
    const { scoped, outstanding } = joinKeysOutstanding([c("minagri-a", "institution"), c("shared", "cross-cutting", true), c("sso-client", "platform"), c("account", "institution")]);
    expect(scoped.map((x) => x.clientId)).toEqual(["minagri-a", "shared"]);
    expect(outstanding.map((x) => x.clientId)).toEqual(["minagri-a"]);
  });

  it("an unavailable overview section contributes no item", () => {
    const items = buildAttention({
      summary: null,
      clients: null,
      realmMfa: null,
      overview: zeroOverview({ leavers: { available: false, error: "x", total: 0, items: [] } }),
    });
    expect(items.find((i) => i.id === "leavers")).toBeUndefined();
    expect(items.find((i) => i.id === "setup-email")?.count).toBe(0);
  });

  it("names the sources that failed", () => {
    const input = { summary: null, clients: null, realmMfa: null, overview: zeroOverview({ logins: { available: false, error: "events off" } }) };
    expect(unavailableSources(input, { employees: true, clients: false, realmMfa: true, overview: false })).toEqual([
      "dashboard.sources.employees",
      "dashboard.sources.realmMfa",
      "dashboard.sources.logins",
    ]);
  });

  it("flags an unbound TOTP flow", () => {
    const items = buildAttention({
      summary: null,
      clients: null,
      overview: null,
      realmMfa: { mode: "all", raw_mode: null, enforced: true, effective: false, raw: null, browser_flow: "browser", flow_ready: false },
    });
    expect(items).toEqual([expect.objectContaining({ id: "mfa-flow", count: 1, textParams: { flow: "browser" } })]);
  });
});
