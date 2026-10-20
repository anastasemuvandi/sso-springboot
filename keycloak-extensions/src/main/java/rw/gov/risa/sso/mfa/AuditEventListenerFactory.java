package rw.gov.risa.sso.mfa;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.util.JsonSerialization;
import rw.gov.risa.sso.mfa.policy.MfaConstants;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Event listener {@code gorsso-audit}. Add it to the realm's event listeners
 * (Terraform / the wrapper installer does) and configure the target:
 *
 * <pre>
 *   KC_SPI_EVENTS_LISTENER_GORSSO_AUDIT_URL=http://audit-service:8001
 *   KC_SPI_EVENTS_LISTENER_GORSSO_AUDIT_API_KEY=...   (= the wrapper's AUDIT_API_KEY)
 * </pre>
 *
 * The plain {@code AUDIT_SERVICE_URL} / {@code AUDIT_API_KEY} environment variables the
 * wrapper reads are honoured as a fallback, so one .env can feed both; {@code AUDIT_API_KEY_FILE}
 * is honoured too (secrets as files). Unset URL = forwarding off (logged once at start).
 */
public class AuditEventListenerFactory implements EventListenerProviderFactory {

    private static final Logger log = Logger.getLogger(AuditEventListenerFactory.class);

    public static final String PROVIDER_ID = MfaConstants.PROVIDER_AUDIT_LISTENER;
    /** Path appended to the configured base URL. */
    public static final String EVENTS_PATH = "/audit/events";
    /** Header the audit service authenticates writers with. */
    public static final String API_KEY_HEADER = "X-Audit-API-Key";

    private HttpClient http;
    private String url;
    private String apiKey;
    private final Map<String, String> lastSwitch = AuditEventListener.newSwitchCache();

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new AuditEventListener(session, this, lastSwitch);
    }

    @Override
    public void init(Config.Scope config) {
        configure(firstNonBlank(config.get("url"), System.getenv("AUDIT_SERVICE_URL")),
                firstNonBlank(config.get("api-key"), System.getenv("AUDIT_API_KEY"), readFile(System.getenv("AUDIT_API_KEY_FILE"))));
        if (url == null) {
            log.warn("gorsso-audit: no audit URL configured; MFA events will not be forwarded");
        } else {
            log.infof("gorsso-audit: forwarding MFA events to %s", url);
        }
    }

    /** Package-private seam for tests (and {@link #init}). */
    void configure(String url, String apiKey) {
        this.url = url;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String readFile(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path.trim())).trim();
        } catch (Exception e) {
            log.warnf("gorsso-audit: could not read AUDIT_API_KEY_FILE: %s", e.getClass().getSimpleName());
            return null;
        }
    }

    /** The full events endpoint for a base URL (trailing slashes dropped). */
    static String endpoint(String baseUrl) {
        return baseUrl.replaceAll("/+$", "") + EVENTS_PATH;
    }

    /**
     * POST one event to the audit service, asynchronously. Never throws and
     * never blocks the caller: an audit service that is down costs a WARN line,
     * not a login. Also used directly by {@link RecoveryReenrolAuthenticator}.
     *
     * @return the in-flight request, or null when nothing was sent (for tests)
     */
    public java.util.concurrent.CompletableFuture<HttpResponse<Void>> forward(Map<String, Object> payload) {
        if (url == null) {
            log.debugf("audit url not configured; dropping %s", payload.get("event_type"));
            return null;
        }
        String body;
        try {
            body = JsonSerialization.writeValueAsString(payload);
        } catch (Exception e) {
            log.warnf("could not serialise audit event %s: %s", payload.get("event_type"), e.getMessage());
            return null;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint(url)))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .header(API_KEY_HEADER, apiKey == null ? "" : apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (IllegalArgumentException e) {
            log.warnf("invalid audit url, %s not recorded there: %s", payload.get("event_type"), e.getMessage());
            return null;
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((resp, err) -> {
            if (err != null) {
                log.warnf("audit service unreachable, %s not recorded there: %s", payload.get("event_type"), err.toString());
            } else if (resp.statusCode() >= 300) {
                log.warnf("audit service answered %d for %s", resp.statusCode(), payload.get("event_type"));
            }
        });
    }

    /** The configured forwarder, or null when the listener is not installed. */
    public static AuditEventListenerFactory of(KeycloakSession session) {
        Object factory = session.getKeycloakSessionFactory()
                .getProviderFactory(EventListenerProvider.class, PROVIDER_ID);
        return factory instanceof AuditEventListenerFactory f ? f : null;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
