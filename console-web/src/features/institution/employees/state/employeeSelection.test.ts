import { describe, expect, it } from "vitest";

import {
  addMany,
  blockReason,
  clear,
  count,
  deserialize,
  employeeNumbers,
  initialSelection,
  isSelectable,
  loadSelection,
  missingProvisionCapability,
  pageState,
  remaining,
  saveSelection,
  selectedList,
  serialize,
  storageKey,
  toggle,
  togglePage,
  type SelectableEmployee,
} from "./employeeSelection";

const emp = (n: string, over: Partial<SelectableEmployee> = {}): SelectableEmployee => ({
  employee_number: n,
  first_name: `First${n}`,
  last_name: "Last",
  email: `${n}@minagri.gov.rw`,
  status: "active",
  match_state: "unmatched",
  keycloak_user_id: null,
  ...over,
});

describe("selectability", () => {
  it("ambiguous, terminated and no-account-no-email rows cannot be selected", () => {
    expect(blockReason(emp("1", { match_state: "ambiguous" }))).toBe("ambiguous");
    expect(blockReason(emp("2", { status: "terminated" }))).toBe("terminated");
    expect(blockReason(emp("3", { email: null }))).toBe("no_email");
    expect(blockReason(emp("4", { email: "  " }))).toBe("no_email");
    expect(isSelectable(emp("5"))).toBe(true);
  });
  it("an employee with an account is selectable without an email", () => {
    expect(blockReason(emp("6", { email: null, keycloak_user_id: "u6", match_state: "matched" }))).toBeNull();
  });
});

describe("selection across pages", () => {
  const page1 = [emp("1"), emp("2"), emp("3", { email: null })];
  const page2 = [emp("4"), emp("5")];

  it("toggle adds and removes one, keyed by employee number, with a label", () => {
    let s = toggle(initialSelection(), page1[0]!).state;
    expect(employeeNumbers(s)).toEqual(["1"]);
    expect(selectedList(s)[0]).toEqual({ employee_number: "1", label: "First1 Last", email: "1@minagri.gov.rw", has_account: false });
    s = toggle(s, page1[0]!).state;
    expect(count(s)).toBe(0);
  });

  it("toggling an unselectable row is a no-op", () => {
    const s = initialSelection();
    expect(toggle(s, page1[2]!).state.items).toEqual({});
  });

  it("selections on page 1 survive selecting on page 2", () => {
    let s = togglePage(initialSelection(), page1).state;
    expect(employeeNumbers(s)).toEqual(["1", "2"]);
    s = toggle(s, page2[1]!).state;
    expect(employeeNumbers(s)).toEqual(["1", "2", "5"]);
    expect(pageState(s, page1)).toBe("all");
    expect(pageState(s, page2)).toBe("some");
  });

  it("select-all-on-page touches selectable rows only; second click clears only that page", () => {
    let s = toggle(initialSelection(), page2[0]!).state;
    s = togglePage(s, page1).state;
    expect(employeeNumbers(s).sort()).toEqual(["1", "2", "4"]);
    s = togglePage(s, page1).state;
    expect(employeeNumbers(s)).toEqual(["4"]);
  });

  it("tri-state: none / some / all / disabled", () => {
    const s = initialSelection();
    expect(pageState(s, page1)).toBe("none");
    expect(pageState(toggle(s, page1[0]!).state, page1)).toBe("some");
    expect(pageState(s, [emp("x", { status: "terminated" })])).toBe("disabled");
    expect(pageState(s, [])).toBe("disabled");
  });

  it("re-adding an already selected row does not duplicate or count against the cap", () => {
    const s = addMany(initialSelection(2), [emp("1"), emp("1"), emp("2")]);
    expect(count(s.state)).toBe(2);
    expect(s.refusedForCap).toBe(0);
  });

  it("clear empties", () => {
    expect(count(clear(togglePage(initialSelection(), page1).state))).toBe(0);
  });
});

describe("cap at the max batch", () => {
  it("adding past the cap is refused and reported", () => {
    const rows = Array.from({ length: 5 }, (_, i) => emp(String(i)));
    const r = addMany(initialSelection(3), rows);
    expect(count(r.state)).toBe(3);
    expect(r.refusedForCap).toBe(2);
    expect(remaining(r.state)).toBe(0);
  });
  it("select-all-on-page fills up to the cap", () => {
    let s = addMany(initialSelection(4), [emp("a"), emp("b")]).state;
    const r = togglePage(s, [emp("c"), emp("d"), emp("e"), emp("f", { status: "terminated" })]);
    s = r.state;
    expect(employeeNumbers(s)).toEqual(["a", "b", "c", "d"]);
    expect(r.refusedForCap).toBe(1);
    // Header is not "all" (e is left out), so a second click adds again — still refused.
    expect(pageState(s, [emp("c"), emp("d"), emp("e")])).toBe("some");
    expect(togglePage(s, [emp("e")]).refusedForCap).toBe(1);
  });
  it("toggle on a full selection is refused; removing still works", () => {
    const full = addMany(initialSelection(1), [emp("a")]).state;
    expect(toggle(full, emp("b")).refusedForCap).toBe(1);
    expect(count(toggle(full, emp("a")).state)).toBe(0);
  });
  it("default cap is the wrapper's 100", () => {
    const rows = Array.from({ length: 120 }, (_, i) => emp(String(i)));
    const r = addMany(initialSelection(), rows);
    expect(count(r.state)).toBe(100);
    expect(r.refusedForCap).toBe(20);
  });
  it("a nonsensical cap is clamped to 1", () => {
    expect(initialSelection(0).max).toBe(1);
  });
});

describe("persistence", () => {
  it("round-trips through JSON", () => {
    const s = addMany(initialSelection(), [emp("1"), emp("2", { keycloak_user_id: "u2", email: null, match_state: "matched" })]).state;
    const back = deserialize(serialize(s));
    expect(back.items).toEqual(s.items);
  });
  it("malformed input yields an empty selection", () => {
    expect(count(deserialize("{nope"))).toBe(0);
    expect(count(deserialize(JSON.stringify({ items: "x" })))).toBe(0);
    expect(count(deserialize(null))).toBe(0);
    const partial = deserialize(JSON.stringify({ items: [{ employee_number: "" }, { employee_number: "7" }, 3] }));
    expect(employeeNumbers(partial)).toEqual(["7"]);
    expect(partial.items["7"]!.label).toBe("7");
  });
  it("deserialize respects the cap", () => {
    const s = addMany(initialSelection(), Array.from({ length: 10 }, (_, i) => emp(String(i)))).state;
    expect(count(deserialize(serialize(s), 3))).toBe(3);
  });
  it("storage is keyed by institution and failures never throw", () => {
    const mem = new Map<string, string>();
    const storage = { getItem: (k: string) => mem.get(k) ?? null, setItem: (k: string, v: string) => void mem.set(k, v), removeItem: (k: string) => void mem.delete(k) };
    const s = addMany(initialSelection(), [emp("1")]).state;
    saveSelection(storage, "minagri", s);
    expect([...mem.keys()]).toEqual([storageKey("minagri")]);
    expect(count(loadSelection(storage, "minagri"))).toBe(1);
    expect(count(loadSelection(storage, "minisante"))).toBe(0);
    saveSelection(storage, "minagri", clear(s));
    expect(mem.size).toBe(0);
    const broken = {
      getItem: () => {
        throw new Error("denied");
      },
      setItem: () => {
        throw new Error("denied");
      },
      removeItem: () => {
        throw new Error("denied");
      },
    };
    expect(count(loadSelection(broken, "minagri"))).toBe(0);
    expect(() => saveSelection(broken, "minagri", s)).not.toThrow();
    expect(count(loadSelection(null, "minagri"))).toBe(0);
  });
});

describe("provision capabilities", () => {
  it("names the most fundamental missing one", () => {
    expect(missingProvisionCapability(() => true)).toBeNull();
    expect(missingProvisionCapability((c) => c === "manage-clients")).toBe("manage-members");
    expect(missingProvisionCapability((c) => c !== "manage-clients")).toBe("manage-clients");
  });
});
