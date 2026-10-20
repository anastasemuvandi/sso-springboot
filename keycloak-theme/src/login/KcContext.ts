/* eslint-disable @typescript-eslint/no-empty-object-type */
import type { ExtendKcContext } from "keycloakify/login";
import type { KcEnvName, ThemeName } from "../kc.gen";

export type KcContextExtension = {
    themeName: ThemeName;
    /** GORSSO_ACCOUNT_SETUP_URL is substituted by Keycloak at runtime (theme.properties ${env.X:}). */
    properties: Record<KcEnvName, string> & {};
};

export type KcContextExtensionPerPage = {};

export type KcContext = ExtendKcContext<KcContextExtension, KcContextExtensionPerPage>;
