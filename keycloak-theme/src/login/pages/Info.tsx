import type { PageProps } from "keycloakify/login/pages/PageProps";
import { kcSanitize } from "keycloakify/lib/kcSanitize";
import type { KcContext } from "../KcContext";
import type { I18n } from "../i18n";
import { isAccountUpdatedMessage } from "../infoPage";

/**
 * Keycloak's info page plus one line (reference info.ftl #gor-account-ready):
 * after someone sets their password from the set-password email ("Your account
 * has been updated."), tell them what to do next — the link carries no
 * application to send them back to, so without it the page is a dead end.
 */
export default function Info(props: PageProps<Extract<KcContext, { pageId: "info.ftl" }>, I18n>) {
    const { kcContext, i18n, doUseDefaultCss, Template, classes } = props;

    const { advancedMsgStr, msg, msgStr } = i18n;

    const { messageHeader, message, requiredActions, skipLink, pageRedirectUri, actionUri, client } = kcContext;

    return (
        <Template
            kcContext={kcContext}
            i18n={i18n}
            doUseDefaultCss={doUseDefaultCss}
            classes={classes}
            displayMessage={false}
            headerNode={<span dangerouslySetInnerHTML={{ __html: kcSanitize(messageHeader ? advancedMsgStr(messageHeader) : message.summary) }} />}
        >
            <div id="kc-info-message">
                <p
                    className="instruction"
                    dangerouslySetInnerHTML={{
                        __html: kcSanitize(
                            (() => {
                                let html = message.summary?.trim() ?? "";
                                if (requiredActions) {
                                    html += ": <b>";
                                    html += requiredActions.map(a => advancedMsgStr(`requiredAction.${a}`)).join(", ");
                                    html += "</b>";
                                }
                                return html;
                            })()
                        )
                    }}
                />
                {isAccountUpdatedMessage(message.summary, msgStr("accountUpdatedMessage")) && (
                    <p id="gor-account-ready" className="instruction">
                        {msg("gorAccountReadyMessage")}
                    </p>
                )}
                {(() => {
                    if (skipLink) return null;
                    if (pageRedirectUri) {
                        return (
                            <p>
                                <a href={pageRedirectUri}>{msg("backToApplication")}</a>
                            </p>
                        );
                    }
                    if (actionUri) {
                        return (
                            <p>
                                <a href={actionUri}>{msg("proceedWithAction")}</a>
                            </p>
                        );
                    }
                    if (client.baseUrl) {
                        return (
                            <p>
                                <a href={client.baseUrl}>{msg("backToApplication")}</a>
                            </p>
                        );
                    }
                    return null;
                })()}
            </div>
        </Template>
    );
}
