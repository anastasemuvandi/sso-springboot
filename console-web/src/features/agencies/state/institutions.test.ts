import { describe, expect, it } from "vitest";

import { describedAs, groupNameDiffers, institutionSlugs, isManageable, membersById, rowKey, rowLabel, statusMeta } from "./institutions";

describe("institutions page rules", () => {
  it("status meta", () => {
    expect(statusMeta("onboarded")).toMatchObject({ labelKey: "agencies.status.onboarded", variant: "secondary" });
    expect(statusMeta("unknown_code").variant).toBe("destructive");
    expect(statusMeta("partial").className).toContain("amber");
    expect(statusMeta("weird")).toMatchObject({ labelKey: "", raw: "weird", variant: "outline" });
  });

  it("row key/label fall back in order", () => {
    expect(rowKey({ group_id: "g", slug: "s", in_keycloak: true }, 0)).toBe("g");
    expect(rowKey({ slug: "s", in_keycloak: false }, 0)).toBe("s");
    expect(rowKey({ institution_code: "C", in_keycloak: false }, 0)).toBe("C");
    expect(rowKey({ in_keycloak: false }, 3)).toBe("row-3");
    expect(rowLabel({ institution_code: "MINX", in_keycloak: false })).toBe("MINX");
    expect(rowLabel({ group_name: "G", in_keycloak: true })).toBe("G");
  });

  it("only rows with a Keycloak group are manageable", () => {
    expect(isManageable({ group_id: "g", in_keycloak: true })).toBe(true);
    expect(isManageable({ group_id: null, in_keycloak: true })).toBe(false);
    expect(isManageable({ group_id: "g", in_keycloak: false })).toBe(false);
  });

  it("flags legacy group casing", () => {
    expect(groupNameDiffers({ group_name: "MINAGRI", slug: "minagri", in_keycloak: true })).toBe(true);
    expect(groupNameDiffers({ group_name: "minagri", slug: "minagri", in_keycloak: true })).toBe(false);
  });

  it("members by id, slugs, description", () => {
    expect(membersById([{ id: "a", totalMembers: 3, activeMembers: 2 }]).get("a")).toEqual({ total: 3, active: 2 });
    expect(institutionSlugs([{ name: "MINAGRI" }, { name: "minagri" }, { name: "mineduc" }])).toEqual(["minagri", "mineduc"]);
    expect(describedAs({ description: ["Ministry"] })).toBe("Ministry");
    expect(describedAs(null)).toBe("");
  });
});
