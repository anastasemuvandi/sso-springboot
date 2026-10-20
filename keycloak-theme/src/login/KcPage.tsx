import { Suspense, lazy } from "react";
import type { ClassKey } from "keycloakify/login";
import type { KcContext } from "./KcContext";
import { useI18n } from "./i18n";
import DefaultPage from "keycloakify/login/DefaultPage";
import Template from "./Template";
import { getBranding } from "./branding";
import { GOR_ADMIN_CLASSES } from "./classes";

const UserProfileFormFields = lazy(() => import("keycloakify/login/UserProfileFormFields"));
const Login = lazy(() => import("./pages/Login"));
const LoginOtp = lazy(() => import("./pages/LoginOtp"));
const Info = lazy(() => import("./pages/Info"));

const doMakeUserConfirmPassword = true;

/**
 * Page router. Overridden pages (login, login-otp, info) carry the reference
 * themes' additions; every other page (login-username, login-password,
 * login-reset-password, login-update-password, login-update-profile, error,
 * logout-confirm, login-config-totp, login-recovery-authn-code-config/-input,
 * select-authenticator, terms, login-page-expired, ...) is Keycloakify's
 * default page rendered inside our Template with the variant's classes.
 */
export default function KcPage(props: { kcContext: KcContext }) {
    const { kcContext } = props;

    const { i18n } = useI18n({ kcContext });

    const branding = getBranding(kcContext.themeName);
    const doUseDefaultCss = branding.doUseDefaultCss;
    const classes: Partial<Record<ClassKey, string>> | undefined =
        branding.variant === "gor-admin" ? GOR_ADMIN_CLASSES : undefined;

    return (
        <Suspense>
            {(() => {
                const common = { i18n, classes, Template, doUseDefaultCss };
                switch (kcContext.pageId) {
                    case "login.ftl":
                        return <Login kcContext={kcContext} {...common} />;
                    case "login-otp.ftl":
                        return <LoginOtp kcContext={kcContext} {...common} />;
                    case "info.ftl":
                        return <Info kcContext={kcContext} {...common} />;
                    default:
                        return (
                            <DefaultPage
                                kcContext={kcContext}
                                {...common}
                                UserProfileFormFields={UserProfileFormFields}
                                doMakeUserConfirmPassword={doMakeUserConfirmPassword}
                            />
                        );
                }
            })()}
        </Suspense>
    );
}
