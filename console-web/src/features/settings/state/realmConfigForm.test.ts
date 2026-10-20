import { describe, expect, it } from "vitest";

import { realmConfigBody, realmFormFromRepresentation } from "./realmConfigForm";

describe("realm config form", () => {
  it("round-trips the editable subset and ignores the rest", () => {
    const f = realmFormFromRepresentation({
      realm: "government-internal",
      displayName: "GoR",
      enabled: true,
      bruteForceProtected: true,
      failureFactor: 5,
      maxFailureWaitSeconds: 900,
      supportedLocales: ["en", "rw"],
      loginTheme: "gor-admin",
      accessTokenLifespan: 300,
    });
    expect(f.booleans.bruteForceProtected).toBe(true);
    expect(f.booleans.registrationAllowed).toBe(false);
    expect(f.numbers.failureFactor).toBe(5);
    expect(f.numbers.waitIncrementSeconds).toBe(60);
    expect(f.supportedLocales).toBe("en, rw");
    const body = realmConfigBody(f);
    expect(body).toMatchObject({ realm: "government-internal", displayName: "GoR", displayNameHtml: undefined, supportedLocales: ["en", "rw"], loginTheme: "gor-admin", accountTheme: undefined, failureFactor: 5 });
    expect("accessTokenLifespan" in body).toBe(false);
  });
});
