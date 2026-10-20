package rw.gov.risa.sso.mfa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.keycloak.authentication.AuthenticationFlowCallbackFactory;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.Constants;
import rw.gov.risa.sso.mfa.policy.MfaConstants;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Registration (META-INF/services, ids), small pure decisions, and the HTTP forwarder. */
class ProvidersTest {

    /** Instances of the classes OUR META-INF/services file lists (Keycloak's own jars list hundreds). */
    static <T> List<T> ourServices(Class<T> spi) {
        try {
            return Files.readAllLines(Path.of("src/main/resources/META-INF/services/" + spi.getName())).stream()
                    .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(n -> {
                        try {
                            return spi.cast(Class.forName(n).getDeclaredConstructor().newInstance());
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(n, e);
                        }
                    }).toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void authenticatorFactoriesAreRegisteredWithTheSharedIds() {
        Map<String, AuthenticatorFactory> byId = ourServices(AuthenticatorFactory.class).stream()
                .collect(Collectors.toMap(AuthenticatorFactory::getId, f -> f));
        assertThat(byId.keySet()).containsExactlyInAnyOrderElementsOf(MfaConstants.REQUIRED_AUTHENTICATORS);
        // the two whose callbacks carry acr / remove the lost OTP after the flow
        assertThat(byId.get(MfaConstants.PROVIDER_REALM_ATTRIBUTE_CONDITION))
                .isInstanceOf(AuthenticationFlowCallbackFactory.class);
        assertThat(byId.get(MfaConstants.PROVIDER_RECOVERY_REENROL))
                .isInstanceOf(AuthenticationFlowCallbackFactory.class);
        byId.values().forEach(f -> {
            assertThat(f.getDisplayType()).isNotBlank();
            assertThat(f.getHelpText()).isNotBlank();
            assertThat(f.isUserSetupAllowed()).isFalse();
            assertThat(f.getRequirementChoices()).isNotEmpty();
        });
    }

    @Test
    void eventListenerIsRegistered() {
        List<String> ids = ourServices(EventListenerProviderFactory.class).stream()
                .map(EventListenerProviderFactory::getId).toList();
        assertThat(ids).contains(MfaConstants.PROVIDER_AUDIT_LISTENER);
    }

    @Test
    void configDefaultsMatchTheFlowDefinition() {
        var cfg = new RealmAttributeConditionFactory().getConfigProperties();
        assertThat(cfg).extracting(p -> p.getName()).containsExactly("attribute", "expected", "when_unset", "loa");
        assertThat(cfg.get(0).getDefaultValue()).isEqualTo(MfaConstants.LEGACY_ATTRIBUTE);
        assertThat(new CredentialConditionFactory().getConfigProperties()).extracting(p -> p.getName())
                .containsExactly("credential_type", "present");
    }

    @ParameterizedTest(name = "requested={0} -> {1}")
    @CsvSource(nullValues = "NULL", value = {"NULL, 2", "'', 2", "x, 2", "0, 2", "1, 2", "2, NULL", "3, NULL", "' 2 ', NULL"})
    void stepUpRaisesNeverLowers(String current, String expected) {
        assertThat(StepUpAuthenticator.raisedLevel(current)).isEqualTo(expected);
        assertThat(Constants.NO_LOA).isLessThan(StepUpAuthenticator.LEVEL);
    }

    @ParameterizedTest(name = "loa={0} -> {1}")
    @CsvSource(nullValues = "NULL", value = {"NULL, NULL", "'', NULL", "2, 2", "' 1 ', 1", "two, NULL"})
    void conditionLevel(String raw, Integer expected) {
        Map<String, String> cfg = new HashMap<>();
        if (raw != null) {
            cfg.put("loa", raw);
        }
        assertThat(RealmAttributeCondition.level(cfg)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "remove_existing={0} -> {1}")
    @CsvSource(nullValues = "NULL", value = {"NULL, true", "true, true", "false, false", "FALSE, false", "no, true"})
    void reenrolRemovesUnlessTurnedOff(String raw, boolean expected) {
        Map<String, String> cfg = new HashMap<>();
        cfg.put("remove_existing", raw);
        assertThat(RecoveryReenrolAuthenticator.removeExisting(cfg)).isEqualTo(expected);
        assertThat(RecoveryReenrolAuthenticator.removeExisting(null)).isTrue();
    }

    @Test
    void forwarderPostsTheJsonWithTheApiKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CompletableFuture<Map<String, Object>> got = new CompletableFuture<>();
        server.createContext("/audit/events", ex -> {
            try (InputStream in = ex.getRequestBody()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = new ObjectMapper().readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
                body.put("_key", ex.getRequestHeaders().getFirst("X-Audit-API-Key"));
                body.put("_method", ex.getRequestMethod());
                body.put("_ct", ex.getRequestHeaders().getFirst("Content-Type"));
                got.complete(body);
            }
            ex.sendResponseHeaders(201, -1);
            ex.close();
        });
        server.start();
        try {
            AuditEventListenerFactory f = new AuditEventListenerFactory();
            f.configure("http://127.0.0.1:" + server.getAddress().getPort() + "//", "k3y");
            var inflight = f.forward(AuditPayloads.recoveryCodeUsed("alice", "uid", "app", "realm", "1.2.3.4", false));
            assertThat(inflight).isNotNull();
            assertThat(inflight.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            Map<String, Object> body = got.get(5, TimeUnit.SECONDS);
            assertThat(body).containsEntry("_key", "k3y").containsEntry("_method", "POST")
                    .containsEntry("_ct", "application/json")
                    .containsEntry("event_type", "auth.mfa.recovery_code_used")
                    .containsEntry("ip_address", "1.2.3.4");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void forwarderIsOffWithoutUrlAndNeverThrows() throws Exception {
        AuditEventListenerFactory off = new AuditEventListenerFactory();
        off.configure(null, null);
        assertThat(off.forward(Map.of("event_type", "x"))).isNull();

        AuditEventListenerFactory down = new AuditEventListenerFactory();
        down.configure("http://127.0.0.1:1", null);
        var inflight = down.forward(Map.of("event_type", "x"));
        // completes exceptionally (connection refused) without throwing at the caller
        assertThat(inflight).isNotNull();
        assertThat(inflight.handle((r, e) -> e != null).get(10, TimeUnit.SECONDS)).isTrue();

        AuditEventListenerFactory bad = new AuditEventListenerFactory();
        bad.configure("not a url", null);
        assertThat(bad.forward(Map.of("event_type", "x"))).isNull();
    }

    @Test
    void helpers() {
        assertThat(AuditEventListenerFactory.firstNonBlank(null, "  ", " a ")).isEqualTo("a");
        assertThat(AuditEventListenerFactory.firstNonBlank(null, "")).isNull();
        assertThat(AuditEventListenerFactory.endpoint("http://audit:8001/")).isEqualTo("http://audit:8001/audit/events");
    }
}
