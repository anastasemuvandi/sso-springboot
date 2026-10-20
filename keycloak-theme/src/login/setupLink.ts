import { SIGN_IN_PAGES, stripFtl } from "./branding";

/**
 * "Didn't get the email to set your password?" (SPEC §5.12, reference
 * template.ftl #gor-setup-link).
 *
 * The base URL comes from the Keycloak container's GORSSO_ACCOUNT_SETUP_URL,
 * substituted into theme.properties at Keycloak runtime by Keycloakify's
 * `environmentVariables` (same ${env.GORSSO_ACCOUNT_SETUP_URL:} mechanism as
 * the reference). Empty/unset => the link is not shown.
 *
 * Deployments set the full page URL (".../account/setup-link"), as in the
 * reference compose files; a bare origin (e.g. "https://sso.gov.rw") is also
 * accepted and gets "/account/setup-link" appended.
 *
 * Shown on the sign-in pages only: login, login-username, login-password.
 */
export const SETUP_LINK_PATH = "/account/setup-link";

export function normalizeSetupBaseUrl(raw: string | undefined | null): string | undefined {
    const value = (raw ?? "").trim();
    if (value === "") return undefined;
    let url: URL;
    try {
        url = new URL(value);
    } catch {
        return undefined;
    }
    // Never render a javascript:/data: link from configuration.
    if (url.protocol !== "https:" && url.protocol !== "http:") return undefined;
    const base = value.replace(/\/+$/, "");
    return base.endsWith(SETUP_LINK_PATH) ? base : base + SETUP_LINK_PATH;
}

export function buildSetupLink(params: {
    setupUrl: string | undefined | null;
    pageId: string;
    languageTag: string | undefined;
    username: string | undefined;
    restartUrl: string;
}): string | undefined {
    const base = normalizeSetupBaseUrl(params.setupUrl);
    if (base === undefined) return undefined;
    if (!SIGN_IN_PAGES.includes(stripFtl(params.pageId))) return undefined;
    const query = [
        `lang=${encodeURIComponent(params.languageTag || "en")}`,
        `username=${encodeURIComponent(params.username ?? "")}`,
        `return_to=${encodeURIComponent(params.restartUrl)}`
    ].join("&");
    return `${base}?${query}`;
}
