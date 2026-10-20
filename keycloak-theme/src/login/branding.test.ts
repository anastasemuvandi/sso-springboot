import { describe, expect, it } from "vitest";
import { getBranding, getVariant, shouldShowSubtitle, stripFtl } from "./branding";

describe("getVariant", () => {
    it("maps the two realm loginTheme names", () => {
        expect(getVariant("government-citizens")).toBe("government-citizens");
        expect(getVariant("gor-admin")).toBe("gor-admin");
    });
    it("falls back to the public citizens look for anything else", () => {
        expect(getVariant(undefined)).toBe("government-citizens");
        expect(getVariant(null)).toBe("government-citizens");
        expect(getVariant("keycloak")).toBe("government-citizens");
        expect(getVariant("GOR-ADMIN")).toBe("government-citizens");
    });
});

describe("getBranding", () => {
    it("citizens: PatternFly underneath, citizens footer + subtitle", () => {
        const b = getBranding("government-citizens");
        expect(b.doUseDefaultCss).toBe(true);
        expect(b.brandLine1).toBe("Government of Rwanda");
        expect(b.brandLine2).toBe("Single Sign-On");
        expect(b.footerKey).toBe("footerNotice");
        expect(b.subtitleKey).toBe("loginAccountInstructions");
        expect(b.panelList).toEqual([]);
    });
    it("gor-admin: no PatternFly, admin footer + subtitle, feature list", () => {
        const b = getBranding("gor-admin");
        expect(b.doUseDefaultCss).toBe(false);
        expect(b.brandLine1).toBe("Government of Rwanda SSO");
        expect(b.brandLine2).toBe("Administration portal");
        expect(b.footerKey).toBe("gorAdminFooter");
        expect(b.subtitleKey).toBe("gorAdminSubtitle");
        expect(b.panelList).toHaveLength(4);
    });
});

describe("shouldShowSubtitle", () => {
    it("citizens shows it on every page", () => {
        expect(shouldShowSubtitle("government-citizens", "login.ftl")).toBe(true);
        expect(shouldShowSubtitle("government-citizens", "login-otp.ftl")).toBe(true);
    });
    it("gor-admin only on the sign-in forms", () => {
        expect(shouldShowSubtitle("gor-admin", "login.ftl")).toBe(true);
        expect(shouldShowSubtitle("gor-admin", "login-username.ftl")).toBe(true);
        expect(shouldShowSubtitle("gor-admin", "login-password.ftl")).toBe(true);
        expect(shouldShowSubtitle("gor-admin", "login-otp.ftl")).toBe(false);
        expect(shouldShowSubtitle("gor-admin", "info.ftl")).toBe(false);
    });
    it("stripFtl", () => {
        expect(stripFtl("login.ftl")).toBe("login");
        expect(stripFtl("login")).toBe("login");
    });
});
