import type { ClassKey } from "keycloakify/login/lib/kcClsx";

/**
 * gor-admin: no PatternFly. Every kc*Class points at this theme's gor-* classes,
 * exactly as the reference themes/gor-admin/login/theme.properties did, so pages
 * we do not override (reset password, update password, update profile, TOTP
 * enrolment, recovery codes, select authenticator, error, logout...) render in
 * this design instead of arriving unstyled.
 *
 * government-citizens uses Keycloakify's default classes + PatternFly 4
 * (doUseDefaultCss), like its reference parent=keycloak.
 */
export const GOR_ADMIN_CLASSES: Partial<Record<ClassKey, string>> = {
    // structural
    kcHtmlClass: "gor-html",
    kcBodyClass: "gor-body",
    kcContentWrapperClass: "gor-content-wrapper",
    kcFormCardClass: "gor-card",
    kcFormHeaderClass: "gor-card__head",
    kcFormClass: "gor-form",
    kcFormGroupClass: "gor-group",
    kcFormGroupErrorClass: "gor-group--error",
    kcFormOptionsClass: "gor-form-options",
    kcFormOptionsWrapperClass: "gor-form-options__wrap",
    kcFormButtonsClass: "gor-form-buttons",
    kcFormSettingClass: "gor-form-setting",
    // fields
    kcLabelClass: "gor-label",
    kcLabelWrapperClass: "gor-label-wrap",
    kcInputClass: "gor-input",
    kcInputWrapperClass: "gor-input-wrap",
    kcInputErrorMessageClass: "gor-field-error",
    kcInputHelperTextAfterClass: "gor-help",
    kcInputHelperTextBeforeClass: "gor-help",
    kcTextareaClass: "gor-input",
    kcCheckboxInputClass: "gor-check__input",
    kcCheckInputClass: "gor-check__input",
    kcCheckLabelClass: "gor-check__label",
    kcInputGroup: "gor-input-group",
    kcFormPasswordVisibilityButtonClass: "gor-eye",
    kcFormPasswordVisibilityIconShow: "gor-eye-icon gor-eye-icon--show",
    kcFormPasswordVisibilityIconHide: "gor-eye-icon gor-eye-icon--hide",
    // buttons
    kcButtonClass: "gor-btn",
    kcButtonPrimaryClass: "gor-btn--primary",
    kcButtonDefaultClass: "gor-btn--default",
    kcButtonSecondaryClass: "gor-btn--secondary",
    kcButtonLargeClass: "gor-btn--lg",
    kcButtonBlockClass: "gor-btn--block",
    // messages
    kcAlertClass: "gor-alert",
    kcAlertTitleClass: "gor-alert__title",
    kcFeedbackAreaClass: "gor-alert",
    kcFeedbackErrorIcon: "gor-alert__icon",
    kcFeedbackWarningIcon: "gor-alert__icon",
    kcFeedbackSuccessIcon: "gor-alert__icon",
    kcFeedbackInfoIcon: "gor-alert__icon",
    kcInfoAreaWrapperClass: "gor-info__wrap",
    kcSignUpClass: "gor-signup",
    kcResetFlowIcon: "gor-reset-icon",
    kcRecoveryCodesWarning: "kc-recovery-codes-warning gor-alert gor-alert--warning",
    // identity providers / OTP
    kcFormSocialAccountListClass: "gor-social",
    kcFormSocialAccountListGridClass: "gor-social--grid",
    kcFormSocialAccountListButtonClass: "gor-btn gor-btn--default gor-btn--block",
    kcFormSocialAccountGridItem: "gor-social__item",
    kcFormSocialAccountNameClass: "gor-social__name",
    kcLoginOTPListClass: "gor-otp-list",
    kcLoginOTPListItemHeaderClass: "gor-otp-list__head",
    kcLoginOTPListItemTitleClass: "gor-otp-list__title",
    kcLoginOTPListInputClass: "gor-otp-list__input",
    // locale switcher
    kcLocaleWrapperClass: "gor-locale__wrap",
    kcLocaleDropDownClass: "gor-locale__dd",
    kcLocaleListClass: "gor-locale__list",
    kcLocaleListItemClass: "gor-locale__item",
    kcLocaleItemClass: "gor-locale__link",
    // select-authenticator (keycloak.v2 markup the reference CSS already styles)
    kcSelectAuthListClass: "select-auth-container",
    kcSelectAuthListItemClass: "select-auth-box-parent",
    kcSelectAuthListItemHeadingClass: "select-auth-box-headline",
    kcSelectAuthListItemDescriptionClass: "select-auth-box-desc"
};
