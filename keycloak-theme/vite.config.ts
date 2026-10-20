import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { keycloakify } from "keycloakify/vite-plugin";

// Government of Rwanda SSO login + email theme.
// `npm run build-keycloak-theme` -> dist_keycloak/gorsso-keycloak-theme.jar (Keycloak 26.x).
export default defineConfig({
    plugins: [
        react(),
        keycloakify({
            // First name is the "main" theme; the others are variants built from the
            // same bundle. Branding is switched at runtime on kcContext.themeName.
            // These are the loginTheme names the realm JSONs reference.
            themeName: ["government-citizens", "gor-admin"],
            accountThemeImplementation: "none",
            groupId: "rw.gov.risa.sso",
            artifactId: "gorsso-keycloak-theme",
            // Same mechanism as the reference theme.properties
            //   gorAccountSetupUrl=${env.GORSSO_ACCOUNT_SETUP_URL:}
            // Keycloak substitutes it at runtime; read as kcContext.properties.GORSSO_ACCOUNT_SETUP_URL.
            environmentVariables: [{ name: "GORSSO_ACCOUNT_SETUP_URL", default: "" }],
            keycloakVersionTargets: {
                "22-to-25": false,
                "all-other-versions": "gorsso-keycloak-theme.jar"
            }
        })
    ]
});
