/**
 * Which Government of Rwanda branding a page gets, decided by the Keycloak
 * theme name the realm selected (realm.loginTheme). Pure: no React, no DOM.
 *
 * Ported from the two reference FreeMarker themes:
 *  - government-citizens: parent=keycloak (PatternFly 4) + its own login.css,
 *    brand "Government of Rwanda / Single Sign-On", footer key footerNotice.
 *  - gor-admin: parent=keycloak.v2 but ships its own CSS and never loads
 *    PatternFly; every kc*Class is repointed at gor-* classes. Brand
 *    "Government of Rwanda SSO / Administration portal", footer gorAdminFooter.
 */
export type Variant = "government-citizens" | "gor-admin";

export const DEFAULT_VARIANT: Variant = "government-citizens";

/** Unknown names fall back to the public citizens look (safe default). */
export function getVariant(themeName: string | undefined | null): Variant {
    return themeName === "gor-admin" ? "gor-admin" : DEFAULT_VARIANT;
}

export type Branding = {
    variant: Variant;
    /** Load Keycloak's PatternFly 4 stylesheets underneath the variant CSS. */
    doUseDefaultCss: boolean;
    brandLine1: string;
    brandLine2: string;
    panelOrg: string;
    panelTagline: string;
    panelLede: string;
    panelList: string[];
    panelFoot: string;
    /** i18n key of the compliance footer under the form. */
    footerKey: "footerNotice" | "gorAdminFooter";
    /** i18n key of the subtitle under the page title (login pages for admin, every page for citizens). */
    subtitleKey: "loginAccountInstructions" | "gorAdminSubtitle";
};

// The brand-panel copy is hard-coded English in both reference templates
// (it is decorative and aria-hidden); kept that way here.
const BRANDING: Record<Variant, Branding> = {
    "government-citizens": {
        variant: "government-citizens",
        doUseDefaultCss: true,
        brandLine1: "Government of Rwanda",
        brandLine2: "Single Sign-On",
        panelOrg: "Republic of Rwanda",
        panelTagline: "One identity for every government service.",
        panelLede:
            "Sign in once to access ministry portals, citizen services and back-office tools — securely, on any device.",
        panelList: [],
        panelFoot: "SSO.RISA · Rwanda Information Society Authority",
        footerKey: "footerNotice",
        subtitleKey: "loginAccountInstructions"
    },
    "gor-admin": {
        variant: "gor-admin",
        doUseDefaultCss: false,
        brandLine1: "Government of Rwanda SSO",
        brandLine2: "Administration portal",
        panelOrg: "Republic of Rwanda",
        panelTagline: "One place to manage every government identity, client and access policy.",
        panelLede:
            "Provision agency users, register ministry applications, scope roles and audit every sign-in — without leaving the SSO control plane.",
        panelList: [
            "Clients & redirect URIs",
            "Users & institution membership",
            "Roles & delegated permissions",
            "Tamper-evident audit trail"
        ],
        panelFoot: "SSO.RISA · Rwanda Information Society Authority",
        footerKey: "gorAdminFooter",
        subtitleKey: "gorAdminSubtitle"
    }
};

export function getBranding(themeName: string | undefined | null): Branding {
    return BRANDING[getVariant(themeName)];
}

/** Rwanda flag palette used by the decorative art and the flag strip. */
export const RWANDA_FLAG = { blue: "#00a1de", yellow: "#fad201", green: "#20603d" } as const;

/**
 * Pages whose header subtitle is shown. The citizens template showed
 * loginAccountInstructions under every page title; gor-admin only showed its
 * subtitle on the sign-in forms.
 */
export function shouldShowSubtitle(variant: Variant, pageId: string): boolean {
    if (variant === "government-citizens") return true;
    return SIGN_IN_PAGES.includes(stripFtl(pageId));
}

export const SIGN_IN_PAGES = ["login", "login-username", "login-password"];

export function stripFtl(pageId: string): string {
    return pageId.endsWith(".ftl") ? pageId.slice(0, -".ftl".length) : pageId;
}
