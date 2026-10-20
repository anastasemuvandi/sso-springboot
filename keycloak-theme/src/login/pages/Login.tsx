import { useState, type ReactNode } from "react";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import { useIsPasswordRevealed } from "keycloakify/tools/useIsPasswordRevealed";
import { clsx } from "keycloakify/tools/clsx";
import type { PageProps } from "keycloakify/login/pages/PageProps";
import { getKcClsx, type KcClsx } from "keycloakify/login/lib/kcClsx";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";

/**
 * Username + password sign-in (login.ftl), after the reference gor-admin
 * login.ftl: labelled fields, an SVG password-reveal button (no icon font),
 * remember-me + forgot-password row, registration info and identity providers.
 * The "Didn't get the email…" link, subtitle and lockout hint come from Template.
 */
export default function Login(props: PageProps<Extract<KcContext, { pageId: "login.ftl" }>, I18n>) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;

    const { kcClsx } = getKcClsx({ doUseDefaultCss, classes });

    const { social, realm, url, usernameHidden, login, auth, registrationDisabled, messagesPerField } = kcContext;

    const { msg, msgStr } = i18n;

    const [isLoginButtonDisabled, setIsLoginButtonDisabled] = useState(false);

    const hasError = messagesPerField.existsError("username", "password");

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            displayMessage={!hasError}
            headerNode={msg("loginAccountTitle")}
            displayInfo={realm.password && realm.registrationAllowed && !registrationDisabled}
            infoNode={
                <div id="kc-registration">
                    <span>
                        {msg("noAccount")}{" "}
                        <a tabIndex={6} className="gor-link" href={url.registrationUrl}>
                            {msg("doRegister")}
                        </a>
                    </span>
                </div>
            }
            socialProvidersNode={
                realm.password &&
                social?.providers !== undefined &&
                social.providers.length !== 0 && (
                    <div id="kc-social-providers" className={clsx("gor-social-block", kcClsx("kcFormSocialAccountSectionClass"))}>
                        <div className="gor-divider">
                            <span>{msg("identity-provider-login-label")}</span>
                        </div>
                        <ul className={kcClsx("kcFormSocialAccountListClass", social.providers.length > 3 && "kcFormSocialAccountListGridClass")}>
                            {social.providers.map(p => (
                                <li key={p.alias} className={kcClsx("kcFormSocialAccountGridItem")}>
                                    <a id={`social-${p.alias}`} className={kcClsx("kcFormSocialAccountListButtonClass")} href={p.loginUrl}>
                                        {p.iconClasses && <i className={clsx(kcClsx("kcCommonLogoIdP"), p.iconClasses)} aria-hidden="true"></i>}
                                        <span
                                            className={kcClsx("kcFormSocialAccountNameClass")}
                                            dangerouslySetInnerHTML={{ __html: kcSanitize(p.displayName) }}
                                        ></span>
                                    </a>
                                </li>
                            ))}
                        </ul>
                    </div>
                )
            }
        >
            <div id="kc-form">
                <div id="kc-form-wrapper">
                    {realm.password && (
                        <form
                            id="kc-form-login"
                            className={kcClsx("kcFormClass")}
                            onSubmit={() => {
                                setIsLoginButtonDisabled(true);
                                return true;
                            }}
                            action={url.loginAction}
                            method="post"
                        >
                            {!usernameHidden && (
                                <div className={kcClsx("kcFormGroupClass")}>
                                    <label htmlFor="username" className={kcClsx("kcLabelClass")}>
                                        {!realm.loginWithEmailAllowed
                                            ? msg("username")
                                            : !realm.registrationEmailAsUsername
                                              ? msg("usernameOrEmail")
                                              : msg("email")}
                                    </label>
                                    <input
                                        tabIndex={1}
                                        id="username"
                                        className={kcClsx("kcInputClass")}
                                        name="username"
                                        defaultValue={login.username ?? ""}
                                        type="text"
                                        autoFocus
                                        autoComplete="username"
                                        dir="ltr"
                                        aria-invalid={hasError}
                                    />
                                    {hasError && (
                                        <span
                                            id="input-error"
                                            className={kcClsx("kcInputErrorMessageClass")}
                                            aria-live="polite"
                                            dangerouslySetInnerHTML={{ __html: kcSanitize(messagesPerField.getFirstError("username", "password")) }}
                                        />
                                    )}
                                </div>
                            )}

                            <div className={kcClsx("kcFormGroupClass")}>
                                <label htmlFor="password" className={kcClsx("kcLabelClass")}>
                                    {msg("password")}
                                </label>
                                <PasswordWrapper kcClsx={kcClsx} i18n={i18n} passwordInputId="password">
                                    <input
                                        tabIndex={2}
                                        id="password"
                                        className={kcClsx("kcInputClass")}
                                        name="password"
                                        type="password"
                                        autoComplete="current-password"
                                        aria-invalid={hasError}
                                    />
                                </PasswordWrapper>
                                {usernameHidden && hasError && (
                                    <span
                                        id="input-error"
                                        className={kcClsx("kcInputErrorMessageClass")}
                                        aria-live="polite"
                                        dangerouslySetInnerHTML={{ __html: kcSanitize(messagesPerField.getFirstError("username", "password")) }}
                                    />
                                )}
                            </div>

                            <div className={kcClsx("kcFormGroupClass", "kcFormSettingClass")}>
                                <div id="kc-form-options" className={kcClsx("kcFormOptionsClass")}>
                                    {realm.rememberMe && !usernameHidden ? (
                                        <label className="gor-check checkbox">
                                            <input
                                                tabIndex={3}
                                                id="rememberMe"
                                                name="rememberMe"
                                                type="checkbox"
                                                className={kcClsx("kcCheckInputClass")}
                                                defaultChecked={!!login.rememberMe}
                                            />{" "}
                                            <span className={kcClsx("kcCheckLabelClass")}>{msg("rememberMe")}</span>
                                        </label>
                                    ) : (
                                        <span></span>
                                    )}
                                    {realm.resetPasswordAllowed && (
                                        <a tabIndex={5} className="gor-link" href={url.loginResetCredentialsUrl}>
                                            {msg("doForgotPassword")}
                                        </a>
                                    )}
                                </div>
                            </div>

                            <div id="kc-form-buttons" className={kcClsx("kcFormButtonsClass")}>
                                <input type="hidden" id="id-hidden-input" name="credentialId" value={auth.selectedCredential ?? ""} />
                                <input
                                    tabIndex={4}
                                    disabled={isLoginButtonDisabled}
                                    className={kcClsx("kcButtonClass", "kcButtonPrimaryClass", "kcButtonBlockClass", "kcButtonLargeClass")}
                                    name="login"
                                    id="kc-login"
                                    type="submit"
                                    value={msgStr("doLogIn")}
                                />
                            </div>
                        </form>
                    )}
                </div>
            </div>
        </Template>
    );
}

function PasswordWrapper(props: { kcClsx: KcClsx; i18n: I18n; passwordInputId: string; children: ReactNode }) {
    const { kcClsx, i18n, passwordInputId, children } = props;
    const { msgStr } = i18n;
    const { isPasswordRevealed, toggleIsPasswordRevealed } = useIsPasswordRevealed({ passwordInputId });

    return (
        <div className={clsx("gor-input-group", kcClsx("kcInputGroup"))}>
            {children}
            <button
                type="button"
                className={clsx(kcClsx("kcFormPasswordVisibilityButtonClass"), "gor-eye", isPasswordRevealed && "gor-eye--on")}
                aria-label={msgStr(isPasswordRevealed ? "hidePassword" : "showPassword")}
                aria-controls={passwordInputId}
                onClick={toggleIsPasswordRevealed}
            >
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
                    <path className="gor-eye__open" d="M1.8 12s3.6-7 10.2-7 10.2 7 10.2 7-3.6 7-10.2 7S1.8 12 1.8 12Z" />
                    <circle className="gor-eye__open" cx="12" cy="12" r="3" />
                    <path className="gor-eye__slash" d="M3 3l18 18" />
                </svg>
            </button>
        </div>
    );
}
