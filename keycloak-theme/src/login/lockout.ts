import { stripFtl } from "./branding";

/**
 * Brute-force lockout (SPEC §3): failureFactor 5, a flat 900 s lockout; OTP and
 * recovery-code failures count. Keycloak deliberately answers a locked account
 * with the same generic error as a wrong password (no username oracle), or with
 * accountTemporarilyDisabledMessage. Either way, the pages where failures count
 * show a short hint about the lockout rule whenever they display an error.
 */
export const LOCKOUT_FAILURE_FACTOR = 5;
export const LOCKOUT_SECONDS = 900;

const PAGES_WHERE_FAILURES_COUNT = [
    "login",
    "login-username",
    "login-password",
    "login-otp",
    "login-recovery-authn-code-input"
];

export function shouldShowLockoutHint(params: {
    pageId: string;
    messageType: string | undefined;
    hasFieldError: boolean;
}): boolean {
    if (!PAGES_WHERE_FAILURES_COUNT.includes(stripFtl(params.pageId))) return false;
    return params.messageType === "error" || params.hasFieldError;
}

export function lockoutMinutes(seconds: number = LOCKOUT_SECONDS): number {
    return Math.ceil(seconds / 60);
}
