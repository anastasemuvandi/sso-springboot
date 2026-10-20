package rw.gov.risa.sso.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.mfa.policy.MfaFlowDefinition;
import rw.gov.risa.sso.mfa.policy.MfaPolicy;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots quay.io/keycloak/keycloak:26.2 with the gorsso-mfa provider JAR and the
 * features the image freezes (organization, admin-fine-grained-authz:v2, recovery-codes), then
 * <ol>
 *   <li>asserts every provider is registered (admin {@code /admin/serverinfo});</li>
 *   <li>builds the {@code browser-totp} flow from {@code gorsso/mfa-flow.json} through the Admin API
 *       (as the wrapper installer will), binds it, and reads it back in the same shape.</li>
 * </ol>
 * Run with {@code mvn -Pit -f keycloak-extensions/pom.xml verify}.
 */
@Tag("it")
class KeycloakProviderIntegrationTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String REALM = "it-mfa";

    static KeycloakContainer keycloak;
    static String token;

    @BeforeAll
    static void boot() throws Exception {
        keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:26.2")
                .withFeaturesEnabled("organization", "admin-fine-grained-authz:v2", "recovery-codes")
                .withProviderLibsFrom(List.of(providerJar()));
        keycloak.start();
        token = adminToken();
    }

    @AfterAll
    static void stop() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    /**
     * The shaded target/gorsso-mfa.jar when `package` has run; otherwise (surefire runs before
     * `package`) the same content assembled from target/classes plus mfa-policy's classes.
     */
    static File providerJar() throws IOException {
        Path shaded = Path.of("target", "gorsso-mfa.jar");
        Path classes = Path.of("target", "classes");
        if (Files.exists(shaded) && Files.getLastModifiedTime(shaded).compareTo(newest(classes)) >= 0) {
            return shaded.toFile();
        }
        Path out = Files.createTempFile("gorsso-mfa-it", ".jar");
        Path policy;
        try {
            policy = Path.of(MfaPolicy.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IOException(e);
        }
        Set<String> seen = new HashSet<>();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(out))) {
            addTree(jar, classes, seen);
            if (Files.isDirectory(policy)) {
                addTree(jar, policy, seen);
            } else {
                try (var zip = new java.util.zip.ZipFile(policy.toFile())) {
                    for (var e : java.util.Collections.list(zip.entries())) {
                        if (e.isDirectory() || e.getName().startsWith("META-INF/") || !seen.add(e.getName())) {
                            continue;
                        }
                        jar.putNextEntry(new JarEntry(e.getName()));
                        zip.getInputStream(e).transferTo(jar);
                        jar.closeEntry();
                    }
                }
            }
        }
        out.toFile().deleteOnExit();
        return out.toFile();
    }

    private static java.nio.file.attribute.FileTime newest(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).map(p -> {
                try {
                    return Files.getLastModifiedTime(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).max(java.nio.file.attribute.FileTime::compareTo)
                    .orElse(java.nio.file.attribute.FileTime.fromMillis(0));
        }
    }

    private static void addTree(JarOutputStream jar, Path root, Set<String> seen) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                String name = root.relativize(p).toString().replace(File.separatorChar, '/');
                if (!seen.add(name)) {
                    continue;
                }
                jar.putNextEntry(new JarEntry(name));
                Files.copy(p, jar);
                jar.closeEntry();
            }
        }
    }

    // ---- HTTP helpers ------------------------------------------------------------------

    static String adminToken() throws Exception {
        String form = "grant_type=password&client_id=admin-cli&username="
                + URLEncoder.encode(keycloak.getAdminUsername(), StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(keycloak.getAdminPassword(), StandardCharsets.UTF_8);
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(
                        URI.create(keycloak.getAuthServerUrl() + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return JSON.readTree(r.body()).get("access_token").asText();
    }

    static JsonNode call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(keycloak.getAuthServerUrl() + "/admin" + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as("%s %s -> %s", method, path, r.body()).isBetween(200, 299);
        return r.body() == null || r.body().isBlank() ? null : JSON.readTree(r.body());
    }

    static String q(String alias) {
        return URLEncoder.encode(alias, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static Set<String> names(JsonNode providers) {
        Set<String> out = new HashSet<>();
        providers.fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ---- tests ---------------------------------------------------------------------------

    @Test
    void providersAreRegistered() throws Exception {
        JsonNode info = call("GET", "/serverinfo", null);
        Set<String> authenticators = names(info.at("/providers/authenticator/providers"));
        assertThat(authenticators).containsAll(MfaConstants.REQUIRED_AUTHENTICATORS)
                .contains(MfaConstants.RECOVERY_CODE_FORM);
        assertThat(names(info.at("/providers/eventsListener/providers")))
                .contains(MfaConstants.PROVIDER_AUDIT_LISTENER);
        assertThat(names(info.at("/providers/required-action/providers")))
                .contains(MfaConstants.CONFIGURE_RECOVERY_AUTHN_CODES, MfaConstants.CONFIGURE_TOTP);
    }

    @Test
    void theDeclaredFlowBuildsAndReadsBackIdentically() throws Exception {
        call("POST", "/realms", Map.of("realm", REALM, "enabled", true, "organizationsEnabled", true,
                "attributes", Map.of(MfaConstants.ACR_LOA_MAP_ATTRIBUTE, MfaConstants.ACR_LOA_MAP_VALUE)));
        call("POST", "/realms/" + REALM + "/roles", Map.of("name", MfaConstants.EXEMPT_ROLE));
        JsonNode def = JSON.readTree(MfaFlowDefinition.json());
        String alias = def.at("/flow/alias").asText();
        String base = "/realms/" + REALM + "/authentication";

        call("POST", base + "/flows", Map.of("alias", alias, "providerId", "basic-flow", "topLevel", true,
                "builtIn", false, "description", def.at("/flow/description").asText()));
        for (JsonNode e : def.get("executions")) {
            String parent = e.get("parent").asText();
            Set<String> before = new HashSet<>();
            call("GET", base + "/flows/" + q(parent) + "/executions", null).forEach(r -> before.add(r.get("id").asText()));
            if ("flow".equals(e.get("kind").asText())) {
                call("POST", base + "/flows/" + q(parent) + "/executions/flow", Map.of("alias", e.get("alias").asText(),
                        "type", "basic-flow", "description", e.get("alias").asText(), "provider", "registration-page-form"));
            } else {
                call("POST", base + "/flows/" + q(parent) + "/executions/execution",
                        Map.of("provider", e.get("provider_id").asText()));
            }
            JsonNode added = null;
            for (JsonNode r : call("GET", base + "/flows/" + q(parent) + "/executions", null)) {
                if (!before.contains(r.get("id").asText()) && r.get("level").asInt() == 0) {
                    added = r;
                }
            }
            assertThat(added).as("execution added for #%s", e.get("index")).isNotNull();
            if (!e.get("requirement").asText().equals(added.get("requirement").asText())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = JSON.convertValue(added, Map.class);
                row.put("requirement", e.get("requirement").asText());
                call("PUT", base + "/flows/" + q(parent) + "/executions", row);
            }
            if (e.has("config")) {
                call("POST", base + "/executions/" + added.get("id").asText() + "/config",
                        Map.of("alias", e.at("/config/alias").asText(), "config", JSON.convertValue(e.at("/config/values"), Map.class)));
            }
        }

        List<String> live = new ArrayList<>();
        for (JsonNode r : call("GET", base + "/flows/" + q(alias) + "/executions", null)) {
            boolean flow = r.path("authenticationFlow").asBoolean(false);
            live.add(r.get("level").asInt() + "|" + (flow ? "flow|" + r.get("displayName").asText()
                    : "execution|" + r.get("providerId").asText()) + "|" + r.get("requirement").asText());
        }
        List<String> want = new ArrayList<>();
        for (Iterator<JsonNode> it = def.get("executions").elements(); it.hasNext(); ) {
            JsonNode e = it.next();
            want.add(e.get("level").asInt() + "|" + e.get("kind").asText() + "|"
                    + (e.has("provider_id") ? e.get("provider_id").asText() : e.get("alias").asText())
                    + "|" + e.get("requirement").asText());
        }
        assertThat(live).containsExactlyElementsOf(want);

        // bind it; Keycloak validates the flow on binding
        JsonNode realm = call("GET", "/realms/" + REALM, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> rep = JSON.convertValue(realm, Map.class);
        rep.put("browserFlow", alias);
        call("PUT", "/realms/" + REALM, rep);
        assertThat(call("GET", "/realms/" + REALM, null).get("browserFlow").asText()).isEqualTo(alias);
    }
}
