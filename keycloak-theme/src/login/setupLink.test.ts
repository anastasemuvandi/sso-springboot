import { describe, expect, it } from "vitest";
import { buildSetupLink, normalizeSetupBaseUrl } from "./setupLink";

const base = {
    pageId: "login.ftl",
    languageTag: "rw",
    username: "jane.doe@minagri.gov.rw",
    restartUrl: "https://sso.gov.rw/realms/government-internal/login-actions/restart?client_id=x&tab_id=y"
};

describe("normalizeSetupBaseUrl", () => {
    it("unset or blank => hidden", () => {
        expect(normalizeSetupBaseUrl(undefined)).toBeUndefined();
        expect(normalizeSetupBaseUrl(null)).toBeUndefined();
        expect(normalizeSetupBaseUrl("")).toBeUndefined();
        expect(normalizeSetupBaseUrl("   ")).toBeUndefined();
    });
    it("keeps a full page URL as deployed in the reference compose files", () => {
        expect(normalizeSetupBaseUrl("https://sso.gov.rw/account/setup-link")).toBe("https://sso.gov.rw/account/setup-link");
        expect(normalizeSetupBaseUrl("https://sso.gov.rw/account/setup-link/")).toBe("https://sso.gov.rw/account/setup-link");
    });
    it("appends /account/setup-link to a bare origin", () => {
        expect(normalizeSetupBaseUrl("http://localhost:8000")).toBe("http://localhost:8000/account/setup-link");
        expect(normalizeSetupBaseUrl("http://localhost:8000/")).toBe("http://localhost:8000/account/setup-link");
    });
    it("rejects non-http(s) and garbage", () => {
        expect(normalizeSetupBaseUrl("javascript:alert(1)")).toBeUndefined();
        expect(normalizeSetupBaseUrl("data:text/html,x")).toBeUndefined();
        expect(normalizeSetupBaseUrl("not a url")).toBeUndefined();
    });
});

describe("buildSetupLink", () => {
    it("is hidden when GORSSO_ACCOUNT_SETUP_URL is not set", () => {
        expect(buildSetupLink({ ...base, setupUrl: "" })).toBeUndefined();
        expect(buildSetupLink({ ...base, setupUrl: undefined })).toBeUndefined();
    });
    it("is shown on login, login-username and login-password only", () => {
        const setupUrl = "https://sso.gov.rw/account/setup-link";
        for (const pageId of ["login.ftl", "login-username.ftl", "login-password.ftl"]) {
            expect(buildSetupLink({ ...base, setupUrl, pageId })).toBeDefined();
        }
        for (const pageId of ["login-otp.ftl", "info.ftl", "error.ftl", "login-reset-password.ftl", "login-update-password.ftl"]) {
            expect(buildSetupLink({ ...base, setupUrl, pageId })).toBeUndefined();
        }
    });
    it("carries lang, username and return_to, URL-encoded, like the reference", () => {
        const href = buildSetupLink({ ...base, setupUrl: "https://sso.gov.rw/account/setup-link" })!;
        const u = new URL(href);
        expect(u.origin + u.pathname).toBe("https://sso.gov.rw/account/setup-link");
        expect(u.searchParams.get("lang")).toBe("rw");
        expect(u.searchParams.get("username")).toBe("jane.doe@minagri.gov.rw");
        expect(u.searchParams.get("return_to")).toBe(base.restartUrl);
        expect(href).toContain("username=jane.doe%40minagri.gov.rw");
    });
    it("defaults lang to en and username to empty", () => {
        const href = buildSetupLink({ ...base, setupUrl: "https://x.rw", languageTag: undefined, username: undefined })!;
        const u = new URL(href);
        expect(u.searchParams.get("lang")).toBe("en");
        expect(u.searchParams.get("username")).toBe("");
    });
});
