package rw.gov.risa.sso.wrapper.service.platformops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.mfa.MfaFlowInstaller;
import rw.gov.risa.sso.wrapper.service.mfa.MfaFlowInstaller.FlowRow;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;

/**
 * {@code mfa-install} on a REAL Keycloak 26.2 carrying the gorsso-mfa provider JAR (spec §5.7): the
 * {@code browser-totp} flow Keycloak ends up with — read back through the Admin API — equals the shared
 * declarative definition (which {@code MfaFlowInstallerTerraformTest} holds equal to {@code mfa.tf}), the flow
 * is bound, a second run finds nothing to change, and the mode is never switched on by the installer.
 * Needs {@code keycloak-extensions/target/gorsso-mfa.jar} ({@code mvn -f keycloak-extensions/pom.xml package}).
 */
@Tag("it")
class MfaInstallFlowIT {

    static final String REALM = "mfa-install-it";
    static KeycloakContainer keycloak;
    static Keycloak admin;

    static File providerJar() {
        Path jar = KeycloakIT.locate("keycloak-extensions").resolve("target/gorsso-mfa.jar");
        Assumptions.assumeTrue(Files.exists(jar), "build keycloak-extensions first: " + jar);
        return jar.toFile();
    }

    @BeforeAll
    static void up() {
        File jar = providerJar();
        keycloak = new KeycloakContainer(KeycloakIT.IMAGE)
                .withFeaturesEnabled("organization", "admin-fine-grained-authz:v2", "recovery-codes")
                .withProviderLibsFrom(List.of(jar))
                .withStartupTimeout(Duration.ofMinutes(5));
        keycloak.start();
        admin = keycloak.getKeycloakAdminClient();
        RealmRepresentation r = new RealmRepresentation();
        r.setRealm(REALM);
        r.setEnabled(true);
        r.setOrganizationsEnabled(true);
        admin.realms().create(r);
    }

    @AfterAll
    static void down() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    @Test
    void theInstalledFlowEqualsTheDefinitionAndIsIdempotent() {
        RealmResource realm = admin.realm(REALM);
        MfaFlowInstaller installer = new MfaFlowInstaller(null, new KeycloakDirectory());

        MfaFlowInstaller.Report first = installer.install(realm, MfaFlowInstaller.Options.applyDefaults());
        assertThat(first.blocked()).as(String.valueOf(first.missingProviders())).isNull();
        assertThat(first.applied()).isTrue();

        String orgRequirement = organizationRequirement(realm);
        assertThat(actualRows(realm)).containsExactlyElementsOf(installer.rows(orgRequirement));

        RealmRepresentation rep = realm.toRepresentation();
        assertThat(rep.getBrowserFlow()).isEqualTo(MfaConstants.MFA_FLOW_ALIAS);
        // the installer never turns enforcement on: the mode is written only when unset, and then as off
        assertThat(rep.getAttributes().get(MfaConstants.MODE_ATTRIBUTE)).isEqualTo("off");
        assertThat(realm.roles().get(MfaConstants.EXEMPT_ROLE).toRepresentation().getName())
                .isEqualTo(MfaConstants.EXEMPT_ROLE);

        MfaFlowInstaller.Report again = installer.install(realm, MfaFlowInstaller.Options.checkOnly());
        assertThat(again.findings()).as("a second run must find nothing to change").isEmpty();
        assertThat(again.clean()).isTrue();
    }

    /** {@code browser-totp} as Keycloak holds it, in the installer's comparison form. */
    static List<FlowRow> actualRows(RealmResource realm) {
        List<FlowRow> rows = new ArrayList<>();
        for (AuthenticationExecutionInfoRepresentation e : realm.flows().getExecutions(MfaConstants.MFA_FLOW_ALIAS)) {
            boolean flow = Boolean.TRUE.equals(e.getAuthenticationFlow());
            rows.add(new FlowRow(e.getLevel(), flow ? "flow" : "exec", flow ? e.getDisplayName() : e.getProviderId(),
                    e.getRequirement()));
        }
        return rows;
    }

    /** The Organization step's requirement in the realm's built-in {@code browser} flow (null when absent). */
    static String organizationRequirement(RealmResource realm) {
        for (AuthenticationExecutionInfoRepresentation e : realm.flows().getExecutions("browser")) {
            if (Boolean.TRUE.equals(e.getAuthenticationFlow()) && e.getDisplayName() != null
                    && e.getDisplayName().endsWith("Organization")) {
                return e.getRequirement();
            }
        }
        return null;
    }
}
