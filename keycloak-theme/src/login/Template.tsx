import { useEffect } from "react";
import { clsx } from "keycloakify/tools/clsx";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import type { TemplateProps } from "keycloakify/login/TemplateProps";
import { getKcClsx } from "keycloakify/login/lib/kcClsx";
import { useSetClassName } from "keycloakify/tools/useSetClassName";
import type { I18n } from "./i18n";
import type { KcContext } from "./KcContext";
import { getBranding, RWANDA_FLAG, shouldShowSubtitle, type Variant } from "./branding";
import { buildSetupLink } from "./setupLink";
import { shouldShowLockoutHint } from "./lockout";
import { useGorInitialize } from "./useGorInitialize";
import coatOfArmsUrl from "./assets/coat-of-arms.png";
import faviconUrl from "./assets/favicon.ico";
import citizensCss from "./styles/government-citizens.css?inline";
import adminCss from "./styles/gor-admin.css?inline";
import additionsCss from "./styles/additions.css?inline";

const CSS_BY_VARIANT: Record<Variant, string> = {
    "government-citizens": citizensCss,
    "gor-admin": adminCss
};

/**
 * One React shell for both reference templates
 * (themes/government-citizens/login/template.ftl and themes/gor-admin/login/template.ftl):
 * split screen, form panel on the left, decorative brand panel on the right.
 * Every hook of Keycloak's base template (header / form / try-another-way /
 * social providers / info) is kept, so every login-flow page renders inside it.
 */
export default function Template(props: TemplateProps<KcContext, I18n>) {
    const {
        displayInfo = false,
        displayMessage = true,
        displayRequiredFields = false,
        headerNode,
        socialProvidersNode = null,
        infoNode = null,
        documentTitle,
        bodyClassName,
        kcContext,
        i18n,
        doUseDefaultCss,
        classes,
        children
    } = props;

    const branding = getBranding(kcContext.themeName);
    const isAdmin = branding.variant === "gor-admin";

    const { kcClsx } = getKcClsx({ doUseDefaultCss, classes });

    const { msg, msgStr, currentLanguage, enabledLanguages } = i18n;

    const { realm, auth, url, message, isAppInitiatedAction, pageId, messagesPerField } = kcContext;

    useEffect(() => {
        document.title = documentTitle ?? msgStr("loginTitle", realm.displayName || realm.name);
        // favicon (the reference linked ${url.resourcesPath}/img/favicon.ico)
        let link = document.querySelector<HTMLLinkElement>("link[rel='icon']");
        if (link === null) {
            link = document.createElement("link");
            link.rel = "icon";
            document.head.appendChild(link);
        }
        link.href = faviconUrl;
    }, []);

    useSetClassName({ qualifiedName: "html", className: kcClsx("kcHtmlClass") });
    useSetClassName({
        qualifiedName: "body",
        className: bodyClassName ?? clsx(kcClsx("kcBodyClass"), "gor-body")
    });

    const { isReadyToRender } = useGorInitialize({ kcContext, withPatternFly: doUseDefaultCss });

    if (!isReadyToRender) {
        return null;
    }

    const attemptedUsername = auth?.attemptedUsername;
    const setupLink = buildSetupLink({
        setupUrl: kcContext.properties?.GORSSO_ACCOUNT_SETUP_URL,
        pageId,
        languageTag: currentLanguage.languageTag,
        username: attemptedUsername ?? ("login" in kcContext ? (kcContext.login as { username?: string })?.username : undefined),
        restartUrl: url.loginRestartFlowUrl
    });

    const showLockoutHint = shouldShowLockoutHint({
        pageId,
        messageType: message?.type,
        hasFieldError: messagesPerField?.existsError?.("username", "password", "totp", "recoveryCodeInput") ?? false
    });

    const showUsernameContext = auth !== undefined && auth.showUsername && !auth.showResetCredentials;
    const subtitle = shouldShowSubtitle(branding.variant, pageId) ? (
        <p className="gor-subtitle">{msg(branding.subtitleKey)}</p>
    ) : null;

    const localeSwitcher = realm.internationalizationEnabled && enabledLanguages.length > 1 && (
        <div className="gor-locale" id="kc-locale">
            <div id="kc-locale-wrapper" className={kcClsx("kcLocaleWrapperClass")}>
                <div id="kc-locale-dropdown" className={clsx("menu-button-links", kcClsx("kcLocaleDropDownClass"))}>
                    <button
                        type="button"
                        id="kc-current-locale-link"
                        className="gor-locale__current"
                        aria-label={msgStr("languages")}
                        aria-haspopup="true"
                        aria-expanded="false"
                        aria-controls="language-switch1"
                    >
                        {currentLanguage.label}
                    </button>
                    <ul role="menu" id="language-switch1" aria-labelledby="kc-current-locale-link" className={kcClsx("kcLocaleListClass")}>
                        {enabledLanguages.map(({ languageTag, label, href }, i) => (
                            <li key={languageTag} className={kcClsx("kcLocaleListItemClass")} role="none">
                                <a role="menuitem" id={`language-${i + 1}`} className={kcClsx("kcLocaleItemClass")} href={href}>
                                    {label}
                                </a>
                            </li>
                        ))}
                    </ul>
                </div>
            </div>
        </div>
    );

    const requiredFieldsNote = displayRequiredFields && (
        <div className={clsx(kcClsx("kcLabelWrapperClass"), "subtitle", "gor-required-note")}>
            <span className="subtitle">
                <span className="required gor-required">*</span> {msg("requiredFields")}
            </span>
        </div>
    );

    const header = isAdmin ? (
        <div className={kcClsx("kcFormHeaderClass")}>
            {requiredFieldsNote}
            <h1 id="kc-page-title" className="gor-title">
                {headerNode}
            </h1>
            {showUsernameContext && (
                <div id="kc-username" className="gor-username">
                    <span id="kc-attempted-username" className="gor-username__name">
                        {auth.attemptedUsername}
                    </span>
                    <a id="reset-login" className="gor-username__reset" href={url.loginRestartFlowUrl} aria-label={msgStr("restartLoginTooltip")}>
                        {msg("restartLoginTooltip")}
                    </a>
                </div>
            )}
        </div>
    ) : (
        <div className="gor-form-card__head">
            {requiredFieldsNote}
            {showUsernameContext ? (
                <div id="kc-username" className={clsx(kcClsx("kcFormGroupClass"), "gor-username-context")}>
                    <label id="kc-attempted-username">{auth.attemptedUsername}</label>
                    <a id="reset-login" href={url.loginRestartFlowUrl} aria-label={msgStr("restartLoginTooltip")}>
                        <i className={kcClsx("kcResetFlowIcon")}></i>
                        <span className="kc-tooltip-text">{msg("restartLoginTooltip")}</span>
                    </a>
                </div>
            ) : (
                <>
                    <h1 id="kc-page-title">{headerNode}</h1>
                    {subtitle}
                </>
            )}
        </div>
    );

    // App-initiated actions should not see warning messages about the need to complete the action.
    const alert = displayMessage && message !== undefined && (message.type !== "warning" || !isAppInitiatedAction) && (
        <div
            className={clsx(
                "gor-alert",
                `gor-alert--${message.type}`,
                `alert-${message.type}`,
                kcClsx("kcAlertClass"),
                `pf-m-${message.type === "error" ? "danger" : message.type}`
            )}
            role="alert"
        >
            {isAdmin && <span className="gor-alert__icon" aria-hidden="true"></span>}
            <span className={kcClsx("kcAlertTitleClass")} dangerouslySetInnerHTML={{ __html: kcSanitize(message.summary) }} />
        </div>
    );

    const panelPrefix = isAdmin ? "gor-panel" : "gor-brand-panel";

    return (
        <>
            {/* Variant stylesheet. Rendered in <body>, after Keycloak's PatternFly <link>s
                in <head>, so it wins on equal specificity like the reference styles= did. */}
            <style>{CSS_BY_VARIANT[branding.variant] + "\n" + additionsCss}</style>

            <div className="gor-split" data-gor-variant={branding.variant}>
                <main className="gor-split__form">
                    {localeSwitcher}

                    <div className={isAdmin ? kcClsx("kcFormCardClass") : "gor-form-card"}>
                        <div className="gor-brand" aria-label="Government of Rwanda">
                            <img src={coatOfArmsUrl} alt="" className="gor-brand__mark" />
                            <span className={isAdmin ? "gor-brand__text" : "gor-brand__wordmark"}>
                                <span className="gor-brand__line1">{branding.brandLine1}</span>
                                <span className="gor-brand__line2">{branding.brandLine2}</span>
                            </span>
                        </div>

                        {header}

                        {alert}

                        {showLockoutHint && (
                            <p id="gor-lockout-hint" className="gor-help gor-lockout-hint">
                                {msg("gorLockoutHint")}
                            </p>
                        )}

                        <div id="kc-content">
                            <div id="kc-content-wrapper" className={isAdmin ? kcClsx("kcContentWrapperClass") : undefined}>
                                {isAdmin && subtitle}

                                {children}

                                {auth !== undefined && auth.showTryAnotherWayLink && (
                                    <form id="kc-select-try-another-way-form" action={url.loginAction} method="post" className="gor-try-another">
                                        <input type="hidden" name="tryAnotherWay" value="on" />
                                        <button type="submit" id="try-another-way" className="gor-link gor-link-button">
                                            {msg("doTryAnotherWay")}
                                        </button>
                                    </form>
                                )}

                                {socialProvidersNode}

                                {setupLink !== undefined && (
                                    <p id="gor-setup-link" className="gor-info gor-setup-link">
                                        {msg("gorSetupLinkPrompt")}{" "}
                                        <a className="gor-link" href={setupLink}>
                                            {msg("gorSetupLinkAction")}
                                        </a>
                                    </p>
                                )}

                                {displayInfo && (
                                    <div id="kc-info" className={clsx(kcClsx("kcSignUpClass"), "gor-info")}>
                                        <div id="kc-info-wrapper" className={kcClsx("kcInfoAreaWrapperClass")}>
                                            {infoNode}
                                        </div>
                                    </div>
                                )}
                            </div>
                        </div>
                    </div>

                    <p className={isAdmin ? "gor-foot" : "gor-form-footer"}>{msg(branding.footerKey)}</p>
                </main>

                {/* Brand panel: decorative, aria-hidden, never load-bearing. */}
                <aside className="gor-split__brand" aria-hidden="true">
                    <div className={panelPrefix}>
                        <div className={`${panelPrefix}__head`}>
                            <img src={coatOfArmsUrl} alt="" className={`${panelPrefix}__logo`} />
                            <span className={`${panelPrefix}__org`}>{branding.panelOrg}</span>
                        </div>
                        <h2 className={`${panelPrefix}__tagline`}>{branding.panelTagline}</h2>
                        <p className={`${panelPrefix}__lede`}>{branding.panelLede}</p>

                        {branding.panelList.length > 0 && (
                            <ul className={`${panelPrefix}__list`}>
                                {branding.panelList.map(item => (
                                    <li key={item}>
                                        <span className={`${panelPrefix}__dot`}></span>
                                        {item}
                                    </li>
                                ))}
                            </ul>
                        )}

                        {!isAdmin && <BrandArt />}

                        <div className={`${panelPrefix}__foot`}>
                            <span className="gor-flag">
                                <span className="gor-flag__stripe" style={{ background: RWANDA_FLAG.blue }}></span>
                                <span className="gor-flag__stripe" style={{ background: RWANDA_FLAG.yellow }}></span>
                                <span className="gor-flag__stripe" style={{ background: RWANDA_FLAG.green }}></span>
                            </span>
                            <span>{branding.panelFoot}</span>
                        </div>
                    </div>
                </aside>
            </div>
        </>
    );
}

/** Decorative concentric arcs in the Rwanda flag palette (citizens template). */
function BrandArt() {
    return (
        <svg className="gor-brand-panel__art" viewBox="0 0 520 520" xmlns="http://www.w3.org/2000/svg">
            <defs>
                <radialGradient id="gor-rg" cx="50%" cy="50%" r="50%">
                    <stop offset="0%" stopColor="rgba(255,255,255,0.18)" />
                    <stop offset="100%" stopColor="rgba(255,255,255,0)" />
                </radialGradient>
            </defs>
            <circle cx="380" cy="380" r="260" fill="url(#gor-rg)" />
            <circle cx="380" cy="380" r="200" fill="none" stroke="rgba(255,255,255,0.15)" strokeWidth="1" />
            <circle cx="380" cy="380" r="150" fill="none" stroke="rgba(255,255,255,0.20)" strokeWidth="1" />
            <circle cx="380" cy="380" r="100" fill="none" stroke="rgba(255,255,255,0.25)" strokeWidth="1" />
            <path d="M 380 130 A 250 250 0 0 1 630 380" fill="none" stroke={RWANDA_FLAG.blue} strokeWidth="3" strokeLinecap="round" opacity="0.8" />
            <path d="M 380 180 A 200 200 0 0 1 580 380" fill="none" stroke={RWANDA_FLAG.yellow} strokeWidth="3" strokeLinecap="round" opacity="0.8" />
            <path d="M 380 230 A 150 150 0 0 1 530 380" fill="none" stroke={RWANDA_FLAG.green} strokeWidth="3" strokeLinecap="round" opacity="0.9" />
            <circle cx="380" cy="380" r="14" fill={RWANDA_FLAG.yellow} />
        </svg>
    );
}
