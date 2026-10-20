import { describe, expect, it } from "vitest";

import { accessStateView, availableActions, countByState, isAccessState, viaGroupsText, wireAction } from "./clientAccessState";

describe("client access state labels", () => {
  it.each([
    ["granted", "success", true],
    ["open", "secondary", true],
    ["revoked", "destructive", false],
    ["not_granted", "outline", false],
    ["exempt", "secondary", true],
    ["unknown", "warning", null],
  ] as const)("%s", (state, tone, reaches) => {
    const v = accessStateView(state);
    expect(v.state).toBe(state);
    expect(v.tone).toBe(tone);
    expect(v.reaches).toBe(reaches);
    expect(v.labelKey).toBe(`users.access.state.${state}`);
    expect(v.helpKey).toBe(`users.access.stateHelp.${state}`);
  });

  it("an unrecognised state is unknown, never access", () => {
    expect(accessStateView("granted_maybe").state).toBe("unknown");
    expect(accessStateView(undefined).reaches).toBeNull();
    expect(isAccessState("open")).toBe(true);
    expect(isAccessState(3)).toBe(false);
  });

  it("actions per state", () => {
    expect(availableActions("granted")).toEqual(["revoke"]);
    expect(availableActions("open")).toEqual(["revoke"]);
    expect(availableActions("not_granted")).toEqual(["grant"]);
    expect(availableActions("revoked")).toEqual(["restore"]);
    expect(availableActions("exempt")).toEqual([]);
    expect(availableActions("unknown")).toEqual([]);
  });

  it("restore is sent as the wrapper's restore action", () => {
    expect(wireAction("restore")).toBe("restore");
    expect(wireAction("grant")).toBe("grant");
    expect(wireAction("revoke")).toBe("revoke");
  });

  it("counts and group text", () => {
    const c = countByState([{ state: "granted" }, { state: "granted" }, { state: "revoked" }, { state: "weird" as never }]);
    expect(c.granted).toBe(2);
    expect(c.revoked).toBe(1);
    expect(c.unknown).toBe(1);
    expect(c.open).toBe(0);
    expect(viaGroupsText({ via_groups: [{ id: "1", name: "minagri" }, { id: "2", name: null }] })).toBe("minagri, 2");
  });
});
