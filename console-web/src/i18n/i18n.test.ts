import { describe, expect, it } from "vitest";

import { resources } from "./index";

function keys(tree: unknown, prefix = ""): string[] {
  if (tree && typeof tree === "object" && !Array.isArray(tree)) {
    return Object.entries(tree as Record<string, unknown>).flatMap(([k, v]) => keys(v, prefix ? `${prefix}.${k}` : k));
  }
  return [prefix];
}

describe("i18n catalogues", () => {
  it("en and rw have exactly the same keys", () => {
    const en = keys(resources.en.translation).sort();
    const rw = keys(resources.rw.translation).sort();
    expect(rw.filter((k) => !en.includes(k))).toEqual([]);
    expect(en.filter((k) => !rw.includes(k))).toEqual([]);
  });

  it("no empty strings", () => {
    for (const lang of ["en", "rw"] as const) {
      const flat = JSON.stringify(resources[lang].translation);
      expect(flat).not.toContain('""');
    }
  });
});
