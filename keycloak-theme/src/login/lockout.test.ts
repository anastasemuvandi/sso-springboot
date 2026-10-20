import { describe, expect, it } from "vitest";
import { LOCKOUT_FAILURE_FACTOR, LOCKOUT_SECONDS, lockoutMinutes, shouldShowLockoutHint } from "./lockout";
import { isAccountUpdatedMessage } from "./infoPage";

describe("lockout policy constants (SPEC §3)", () => {
    it("5 failures, flat 900 s = 15 min", () => {
        expect(LOCKOUT_FAILURE_FACTOR).toBe(5);
        expect(LOCKOUT_SECONDS).toBe(900);
        expect(lockoutMinutes()).toBe(15);
    });
});

describe("shouldShowLockoutHint", () => {
    it("shows on credential pages when there is an error", () => {
        for (const pageId of ["login.ftl", "login-username.ftl", "login-password.ftl", "login-otp.ftl", "login-recovery-authn-code-input.ftl"]) {
            expect(shouldShowLockoutHint({ pageId, messageType: "error", hasFieldError: false })).toBe(true);
            expect(shouldShowLockoutHint({ pageId, messageType: undefined, hasFieldError: true })).toBe(true);
        }
    });
    it("hidden without an error", () => {
        expect(shouldShowLockoutHint({ pageId: "login.ftl", messageType: undefined, hasFieldError: false })).toBe(false);
        expect(shouldShowLockoutHint({ pageId: "login.ftl", messageType: "info", hasFieldError: false })).toBe(false);
    });
    it("hidden on pages where failures do not count", () => {
        expect(shouldShowLockoutHint({ pageId: "login-reset-password.ftl", messageType: "error", hasFieldError: true })).toBe(false);
        expect(shouldShowLockoutHint({ pageId: "error.ftl", messageType: "error", hasFieldError: false })).toBe(false);
    });
});

describe("isAccountUpdatedMessage (info page account-ready line)", () => {
    it("matches the translated accountUpdatedMessage only", () => {
        expect(isAccountUpdatedMessage("Your account has been updated.", "Your account has been updated.")).toBe(true);
        expect(isAccountUpdatedMessage(" Your account has been updated. ", "Your account has been updated.")).toBe(true);
        expect(isAccountUpdatedMessage("Something else", "Your account has been updated.")).toBe(false);
        expect(isAccountUpdatedMessage(undefined, "Your account has been updated.")).toBe(false);
        expect(isAccountUpdatedMessage("", "")).toBe(false);
    });
});
