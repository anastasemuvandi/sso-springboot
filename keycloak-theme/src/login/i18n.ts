/* eslint-disable @typescript-eslint/no-unused-vars */
import { i18nBuilder } from "keycloakify/login";
import type { ThemeName } from "../kc.gen";

/**
 * Login-theme messages, ported key-for-key from the reference FreeMarker themes:
 *   themes/government-citizens/login/messages/messages_{en,rw}.properties
 *   themes/gor-admin/login/messages/messages_{en,rw}.properties
 *
 * - A plain string is used by both theme variants.
 * - A { "government-citizens": ..., "gor-admin": ... } map is used where the two
 *   reference bundles disagree. Where a key existed in only one reference theme,
 *   the other variant gets Keycloak's own default (what it showed before).
 * - Keycloakify also writes these into messages_<lang>.properties inside the jar,
 *   so messages Keycloak renders server-side (e.g. invalidUserMessage in
 *   message.summary) use them too. Apostrophes are written once here; the build
 *   doubles them for java.text.MessageFormat.
 *
 * IMPORTANT: the argument of withCustomTranslations must stay a literal object
 * (Keycloakify evaluates it statically at build time) — no spreads or variables.
 *
 * Kinyarwanda: the reference marks its rw text "drafted without a native-speaker
 * review — have it reviewed before production". gorLockoutHint and the citizens
 * accountTemporarilyDisabledMessage are new here and need the same review.
 */
const { useI18n, ofTypeI18n } = i18nBuilder
    .withThemeName<ThemeName>()
    .withExtraLanguages({
        rw: {
            label: "Kinyarwanda",
            getMessages: () => import("./i18n.rw")
        }
    })
    .withCustomTranslations({
        en: {
            // ---------------------------------------------------------------- chrome
            loginTitle: {
                "government-citizens": "Citizens Portal Login",
                "gor-admin": "Sign in · GoR SSO Administration"
            },
            loginTitleHtml: {
                "government-citizens": "<strong>Citizens Portal</strong> Login",
                "gor-admin": "{0}"
            },
            loginAccountTitle: {
                "government-citizens": "Sign in to your account",
                "gor-admin": "Sign in"
            },
            loginAccountInstructions: "Please enter your details to continue.",
            gorAdminSubtitle:
                "Use your government staff account to continue to the administration console.",
            gorAdminFooter:
                "Authorised access only. Every sign-in and administrative action is logged and audited.",
            welcomeMessage: "Welcome to Government Digital Services",
            accessMessage: "Access all government services with one account",
            secureMessage: "Your information is secure and protected",
            brandTitle: "Government of Rwanda",
            brandSubtitle: "Single Sign-On",
            footerNotice: "Authorised access only. Activity is logged and audited.",
            footerHelpLink: "Need help?",

            // ---------------------------------------------------- fields and actions
            username: "Username",
            usernameOrEmail: {
                "government-citizens": "Username or Email",
                "gor-admin": "Username or email"
            },
            password: "Password",
            doLogIn: { "government-citizens": "Sign In", "gor-admin": "Sign in" },
            doForgotPassword: {
                "government-citizens": "Forgot Password?",
                "gor-admin": "Forgot password?"
            },
            rememberMe: { "government-citizens": "Remember me", "gor-admin": "Keep me signed in" },
            backToLogin: { "government-citizens": "Back to Login", "gor-admin": "Back to sign in" },
            showPassword: "Show password",
            hidePassword: "Hide password",
            noAccount: {
                "government-citizens": "New to government services?",
                "gor-admin": "Need an account?"
            },
            doRegister: {
                "government-citizens": "Create Account",
                "gor-admin": "Contact your institution administrator"
            },
            registerTitle: { "government-citizens": "Create Account", "gor-admin": "Register" },
            register: "Create Account",
            registerWith: "Register with",
            alreadyHaveAccount: "Already have an account?",
            doClickHere: { "government-citizens": "Sign in here", "gor-admin": "Click here" },
            requiredFields: "Required fields",
            backToApplication: {
                "government-citizens": "Back to Application",
                "gor-admin": "&laquo; Back to Application"
            },
            proceedWithAction: {
                "government-citizens": "Continue",
                "gor-admin": "&raquo; Click here to proceed"
            },
            doSubmit: { "government-citizens": "Submit", "gor-admin": "Continue" },
            doCancel: "Cancel",
            doTryAnotherWay: { "government-citizens": "Try Another Way", "gor-admin": "Try another way" },

            // ------------------------------------------------------ credential flows
            emailForgotTitle: {
                "government-citizens": "Forgot Your Password?",
                "gor-admin": "Reset your password"
            },
            emailInstructions: {
                "government-citizens":
                    "Enter your username or email and we will send you instructions on how to create a new password.",
                "gor-admin":
                    "Enter your username or email address and we will send you instructions for choosing a new password."
            },
            updatePasswordTitle: {
                "government-citizens": "Update password",
                "gor-admin": "Choose a new password"
            },

            // ---------------------------------------------------------------- errors
            // Never distinguishes "no such user" from "wrong password" (brute-force
            // detection is on; a distinguishing message is a username oracle).
            invalidUserMessage: {
                "government-citizens": "Invalid username or password",
                "gor-admin": "Invalid username or password."
            },
            accountDisabledMessage: {
                "government-citizens": "Account is disabled, contact your administrator.",
                "gor-admin": "This account is disabled. Contact your institution administrator."
            },
            accountTemporarilyDisabledMessage: {
                "government-citizens":
                    "This account is temporarily locked after repeated failed sign-in attempts. Try again in 15 minutes.",
                "gor-admin":
                    "This account is temporarily locked after repeated failed sign-ins. Try again shortly, or contact your institution administrator."
            },
            expiredCodeMessage: {
                "government-citizens": "Login timeout. Please sign in again.",
                "gor-admin": "That sign-in attempt timed out. Please sign in again."
            },
            // Realm brute force: failureFactor 5, flat 900 s lockout; OTP and
            // recovery-code failures count (SPEC §3).
            gorLockoutHint:
                "For your security, 5 failed attempts (including wrong authenticator or recovery codes) lock the account for 15 minutes.",

            // ------------------------------------------------------------ TOTP / 2FA
            loginTotpTitle: {
                "government-citizens": "Set up two-step verification",
                "gor-admin": "Two-factor authentication"
            },
            loginTotpIntro:
                "Your account needs a second step at sign-in: a 6-digit code from an authenticator app on your phone.",
            loginTotpStep1:
                "Install one of these free authenticator apps on your phone. They work offline: once you have scanned the code below, the phone needs no internet, data or SIM to produce codes, and no SMS or push message is ever sent.",
            loginTotpStep2: "Open the app, choose to add an account, and scan this QR code:",
            loginTotpStep3:
                "Type the 6-digit code the app shows now, then select Submit. The code changes every 30 seconds.",
            loginTotpStep3DeviceName:
                'Give this phone a name, for example "My phone", so you can recognise it later.',
            loginTotpManualStep2: "Open the app, choose to enter a setup key, and type this key:",
            loginTotpManualStep3:
                "If the app asks, use these settings (they are the default in most apps):",
            loginTotpUnableToScan: "Cannot scan the code? Type a key instead",
            loginTotpScanBarcode: "Scan a QR code instead",
            loginTotpDeviceName: "Phone name",
            loginTotpType: "Type",
            loginTotpDigits: "Digits",
            loginTotpInterval: "Interval (seconds)",
            "loginTotp.totp": "Time-based",
            totpAppFreeOTPName: "FreeOTP (Android, iPhone)",
            totpAppGoogleName: "Google Authenticator (Android, iPhone)",
            totpAppMicrosoftAuthenticatorName: "Microsoft Authenticator (Android, iPhone)",
            authenticatorCode: "6-digit code from the app",
            loginOtpOneTime: "6-digit code from your authenticator app",
            gorOtpLoginHelp:
                'Open FreeOTP, Google Authenticator or Microsoft Authenticator on your phone and type the code shown for this account. The app works without a network connection. Lost your phone? Select "Try another way" and use one of your recovery codes.',
            invalidTotpMessage:
                "That code is not correct. Check that the time on your phone is set automatically, then try the newest code.",
            loginChooseAuthenticator: "Choose how to confirm it is you",
            "otp-display-name": "Authenticator app",
            "otp-help-text":
                "Type the 6-digit code from FreeOTP, Google Authenticator or Microsoft Authenticator.",
            "auth-recovery-authn-code-form-display-name": "Recovery code",
            "auth-recovery-authn-code-form-help-text":
                "Lost your phone? Use one of the recovery codes you saved when you set up two-step verification. You will then set up your new phone.",
            "auth-recovery-code-header": "Sign in with a recovery code",
            "auth-recovery-code-prompt": "Recovery code #{0}",
            "recovery-codes-error-invalid": "That recovery code is not correct.",
            "recovery-code-config-header": "Save your recovery codes",
            "recovery-code-config-warning-title": "These codes will not be shown again",
            "recovery-code-config-warning-message":
                "Each code works once, to sign in if you lose your phone. Print them or write them down and keep them somewhere safe, away from your phone. Cancelling removes these codes from your account.",
            "recovery-codes-print": "Print",
            "recovery-codes-download": "Download",
            "recovery-codes-copy": "Copy",
            "recovery-codes-copied": "Copied",
            "recovery-codes-confirmation-message": "I have saved these codes somewhere safe",
            "recovery-codes-action-complete": "Finish setup",
            "recovery-codes-action-cancel": "Cancel setup",
            "recovery-codes-download-file-header": "Keep these recovery codes somewhere safe.",
            "recovery-codes-download-file-description":
                "Recovery codes are single-use codes that let you sign in if you do not have your authenticator app.",
            "recovery-codes-download-file-date": "These codes were generated on",
            "recovery-codes-label-default": "Recovery codes",
            configureTotpMessage:
                "Your account needs two-step verification. Set up an authenticator app on your phone to continue.",
            configureBackupCodesMessage:
                "Save your recovery codes to finish setting up two-step verification.",
            logoutOtherSessions: "Sign out from other devices",

            // ------------------------------------- set-password link (SPEC §5.12)
            gorSetupLinkPrompt: "Didn't get the email to set your password?",
            gorSetupLinkAction: "Send it again",
            gorAccountReadyMessage:
                "Now you can use your account to sign in to any of the applications you have access to. Thank you."
        },
        rw: {
            loginTitle: {
                "government-citizens": "Kwinjira mu Rubuga rw'Abanyagihugu",
                "gor-admin": "Kwinjira · Ubuyobozi bwa GoR SSO"
            },
            loginTitleHtml: {
                "government-citizens": "Kwinjira mu <strong>Rubuga rw'Abanyagihugu</strong>",
                "gor-admin": "{0}"
            },
            loginAccountTitle: "Kwinjira",
            loginAccountInstructions: "Injiza amakuru yawe kugira ngo ukomeze.",
            gorAdminSubtitle:
                "Koresha konti yawe y'umukozi wa Leta kugira ngo ukomeze ku rubuga rw'ubuyobozi.",
            gorAdminFooter:
                "Hinjira gusa abemerewe. Kwinjira kose n'igikorwa cyose cy'ubuyobozi byandikwa kandi bigenzurwa.",
            welcomeMessage: "Murakaza neza mu Serivisi za Gikoresho za Leta",
            accessMessage: "Bona serivisi zose za leta ukoresha konti imwe",
            secureMessage: "Amakuru yawe afite ubunyangamugayo kandi arafungwa neza",
            brandTitle: "Guverinoma y'u Rwanda",
            brandSubtitle: "Kwinjira rimwe gusa",
            footerNotice: "Hinjira gusa niba wemerewe. Ibikorwa byose byandikwa kandi bigenzurwa.",
            footerHelpLink: "Ukeneye ubufasha?",

            username: "Izina ry'ukoresha",
            usernameOrEmail: {
                "government-citizens": "Izina ry'ukoresha cyangwa Imeli",
                "gor-admin": "Izina ry'ukoresha cyangwa imeli"
            },
            password: "Ijambo ry'ibanga",
            doLogIn: "Injira",
            doForgotPassword: {
                "government-citizens": "Wibagiwe Ijambo ry'ibanga?",
                "gor-admin": "Wibagiwe ijambo ry'ibanga?"
            },
            rememberMe: "Ngumisha ninjiye",
            backToLogin: "Subira ku kwinjira",
            showPassword: "Erekana ijambo ry'ibanga",
            hidePassword: "Hisha ijambo ry'ibanga",
            noAccount: {
                "government-citizens": "Ntabwo ufite konti?",
                "gor-admin": "Ukeneye konti?"
            },
            doRegister: {
                "government-citizens": "Kora Konti",
                "gor-admin": "Vugana n'umuyobozi w'ikigo cyawe"
            },
            registerTitle: "Kora Konti",
            register: "Kora Konti",
            registerWith: "Iyandikishe hamwe na",
            alreadyHaveAccount: "Usanzwe ufite konti?",
            doClickHere: "Injira hano",
            requiredFields: "Ibintu bikenewe",
            backToApplication: "Subira ku rukurikirane",
            proceedWithAction: "Komeza",
            doSubmit: { "government-citizens": "Emeza", "gor-admin": "Komeza" },
            doCancel: "Hagarika",
            doTryAnotherWay: "Gerageza ubundi buryo",

            emailForgotTitle: "Hindura ijambo ry'ibanga",
            emailInstructions: {
                "government-citizens":
                    "Injiza izina ry'ukoresha cyangwa imeli yawe tugukorehere amabwiriza yo gukora ijambo ry'ibanga rishya.",
                "gor-admin":
                    "Andika izina ry'ukoresha cyangwa imeli yawe, tukoherereze amabwiriza yo guhitamo ijambo ry'ibanga rishya."
            },
            updatePasswordTitle: "Hitamo ijambo ry'ibanga rishya",

            invalidUserMessage: {
                "government-citizens": "Izina ry'ukoresha cyangwa ijambo ry'ibanga bitemewe",
                "gor-admin": "Izina ry'ukoresha cyangwa ijambo ry'ibanga si byo."
            },
            accountDisabledMessage: "Iyi konti yarahagaritswe. Vugana n'umuyobozi w'ikigo cyawe.",
            accountTemporarilyDisabledMessage: {
                "government-citizens":
                    "Iyi konti yafunzwe by'agateganyo nyuma yo kugerageza kwinjira kenshi bikanga. Ongera ugerageze nyuma y'iminota 15.",
                "gor-admin":
                    "Iyi konti yafunzwe by'agateganyo nyuma yo kugerageza kwinjira kenshi bikanga. Ongera ugerageze nyuma y'akanya, cyangwa uvugane n'umuyobozi w'ikigo cyawe."
            },
            expiredCodeMessage: "Igihe cyo kwinjira cyarangiye. Ongera winjire.",
            gorLockoutHint:
                "Ku bw'umutekano wawe, kugerageza inshuro 5 bikanga (harimo kode za porogaramu cyangwa z'ubutabazi zitari zo) bifunga konti mu minota 15.",

            loginTotpTitle: {
                "government-citizens": "Gushyiraho igenzura ry'intambwe ebyiri",
                "gor-admin": "Igenzura ry'intambwe ebyiri"
            },
            loginTotpIntro:
                "Konti yawe isaba intambwe ya kabiri mu kwinjira: kode y'imibare 6 iva muri porogaramu iri kuri telefoni yawe.",
            loginTotpStep1:
                "Shyira kuri telefoni yawe imwe muri izi porogaramu z'ubuntu zitanga kode. Zikora nta interineti: numara gusikana kode iri hepfo, telefoni ntikenera interineti, data cyangwa SIM kugira ngo itange kode, kandi nta SMS cyangwa ubutumwa bwoherezwa.",
            loginTotpStep2: "Fungura porogaramu, uhitemo kongeramo konti, hanyuma usikane iyi kode ya QR:",
            loginTotpStep3:
                "Andika kode y'imibare 6 porogaramu yerekana ubu, hanyuma ukande Emeza. Kode ihinduka buri masegonda 30.",
            loginTotpStep3DeviceName:
                'Ha iyi telefoni izina, urugero "Telefoni yanjye", kugira ngo uzayimenye nyuma.',
            loginTotpManualStep2:
                "Fungura porogaramu, uhitemo kwandika urufunguzo, hanyuma wandike uru rufunguzo:",
            loginTotpManualStep3:
                "Porogaramu nibigusaba, koresha aya magenamiterere (ni yo asanzwe muri porogaramu nyinshi):",
            loginTotpUnableToScan: "Ntushobora gusikana kode? Andika urufunguzo",
            loginTotpScanBarcode: "Sikana kode ya QR",
            loginTotpDeviceName: "Izina rya telefoni",
            loginTotpType: "Ubwoko",
            loginTotpDigits: "Umubare w'imibare",
            loginTotpInterval: "Igihe (amasegonda)",
            "loginTotp.totp": "Bishingiye ku gihe",
            totpAppFreeOTPName: "FreeOTP (Android, iPhone)",
            totpAppGoogleName: "Google Authenticator (Android, iPhone)",
            totpAppMicrosoftAuthenticatorName: "Microsoft Authenticator (Android, iPhone)",
            authenticatorCode: "Kode y'imibare 6 iva muri porogaramu",
            loginOtpOneTime: "Kode y'imibare 6 iva muri porogaramu yawe",
            gorOtpLoginHelp:
                'Fungura FreeOTP, Google Authenticator cyangwa Microsoft Authenticator kuri telefoni yawe, wandike kode igaragara kuri iyi konti. Porogaramu ikora nta interineti. Wabuze telefoni? Hitamo "Gerageza ubundi buryo" ukoreshe imwe muri kode zawe z\'ubutabazi.',
            invalidTotpMessage:
                "Iyo kode si yo. Reba ko isaha ya telefoni yawe ishyirwaho mu buryo bwikora, hanyuma ugerageze kode nshya.",
            loginChooseAuthenticator: "Hitamo uburyo bwo kwemeza ko ari wowe",
            "otp-display-name": "Porogaramu itanga kode",
            "otp-help-text":
                "Andika kode y'imibare 6 iva muri FreeOTP, Google Authenticator cyangwa Microsoft Authenticator.",
            "auth-recovery-authn-code-form-display-name": "Kode y'ubutabazi",
            "auth-recovery-authn-code-form-help-text":
                "Wabuze telefoni? Koresha imwe muri kode z'ubutabazi wabitse igihe washyiragaho igenzura ry'intambwe ebyiri. Nyuma yaho uzashyiraho telefoni yawe nshya.",
            "auth-recovery-code-header": "Injira ukoresheje kode y'ubutabazi",
            "auth-recovery-code-prompt": "Kode y'ubutabazi #{0}",
            "recovery-codes-error-invalid": "Iyo kode y'ubutabazi si yo.",
            "recovery-code-config-header": "Bika kode zawe z'ubutabazi",
            "recovery-code-config-warning-title": "Izi kode ntizizongera kugaragara",
            "recovery-code-config-warning-message":
                "Buri kode ikoreshwa rimwe gusa, kugira ngo winjire igihe wabuze telefoni. Zicapure cyangwa uzandike, uzibike ahantu hizewe kure ya telefoni yawe. Nuhagarika iki gikorwa, izi kode zizasibwa kuri konti yawe.",
            "recovery-codes-print": "Capura",
            "recovery-codes-download": "Kuramo",
            "recovery-codes-copy": "Kopora",
            "recovery-codes-copied": "Byakoporowe",
            "recovery-codes-confirmation-message": "Nabitse izi kode ahantu hizewe",
            "recovery-codes-action-complete": "Soza",
            "recovery-codes-action-cancel": "Hagarika",
            "recovery-codes-download-file-header": "Bika izi kode z'ubutabazi ahantu hizewe.",
            "recovery-codes-download-file-description":
                "Kode z'ubutabazi ni kode zikoreshwa rimwe zigufasha kwinjira igihe udafite porogaramu itanga kode.",
            "recovery-codes-download-file-date": "Izi kode zakozwe ku wa",
            "recovery-codes-label-default": "Kode z'ubutabazi",
            configureTotpMessage:
                "Konti yawe isaba igenzura ry'intambwe ebyiri. Shyira porogaramu itanga kode kuri telefoni yawe kugira ngo ukomeze.",
            configureBackupCodesMessage:
                "Bika kode zawe z'ubutabazi kugira ngo urangize gushyiraho igenzura ry'intambwe ebyiri.",
            logoutOtherSessions: "Sohoka ku bindi bikoresho",

            gorSetupLinkPrompt: "Ntiwabonye imeli yo guhitamo ijambo ry'ibanga?",
            gorSetupLinkAction: "Ongera uyohererezwe",
            gorAccountReadyMessage:
                "Ubu ushobora gukoresha konti yawe kwinjira muri porogaramu zose wemerewe gukoresha. Murakoze."
        }
    })
    .build();

type I18n = typeof ofTypeI18n;

export { useI18n, type I18n };
