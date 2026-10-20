import { useEffect } from "react";
import { useInsertScriptTags } from "keycloakify/tools/useInsertScriptTags";
import { useInsertLinkTags } from "keycloakify/tools/useInsertLinkTags";
import type { KcContext } from "./KcContext";

/**
 * Keycloakify's Template.useInitialize, minus Keycloak's own css/login.css.
 *
 * The reference government-citizens theme had parent=keycloak and
 * `styles=css/login.css` (its own file), so the browser got PatternFly's
 * stylesCommon + the GoR stylesheet, but never keycloak's login.css (background
 * image, absolute-positioned locale menu, card sizing). Loading only the
 * PatternFly sheets keeps that behaviour. gor-admin loads no PatternFly at all.
 */
export function useGorInitialize(params: { kcContext: KcContext; withPatternFly: boolean }) {
    const { kcContext, withPatternFly } = params;
    const { url, scripts } = kcContext;

    const { areAllStyleSheetsLoaded } = useInsertLinkTags({
        componentOrHookName: "Template",
        hrefs: !withPatternFly
            ? []
            : [
                  `${url.resourcesCommonPath}/node_modules/@patternfly/patternfly/patternfly.min.css`,
                  `${url.resourcesCommonPath}/node_modules/patternfly/dist/css/patternfly.min.css`,
                  `${url.resourcesCommonPath}/node_modules/patternfly/dist/css/patternfly-additions.min.css`,
                  `${url.resourcesCommonPath}/lib/pficon/pficon.css`
              ]
    });

    const { insertScriptTags } = useInsertScriptTags({
        componentOrHookName: "Template",
        scriptTags: [
            { type: "module", src: `${url.resourcesPath}/js/menu-button-links.js` },
            ...(scripts === undefined ? [] : scripts.map(src => ({ type: "text/javascript" as const, src }))),
            {
                // Cross-tab session check, as Keycloak's own template does.
                type: "module",
                textContent: [
                    `import { startSessionPolling, checkAuthSession } from "${url.resourcesPath}/js/authChecker.js";`,
                    ``,
                    `startSessionPolling("${url.ssoLoginInOtherTabsUrl}");`,
                    kcContext.authenticationSession === undefined
                        ? ""
                        : `checkAuthSession("${kcContext.authenticationSession.authSessionIdHash}");`
                ].join("\n")
            }
        ]
    });

    useEffect(() => {
        if (areAllStyleSheetsLoaded) {
            insertScriptTags();
        }
    }, [areAllStyleSheetsLoaded]);

    return { isReadyToRender: areAllStyleSheetsLoaded };
}
