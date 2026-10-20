package rw.gov.risa.sso.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Minimal Keycloak Admin REST client: master-realm password grant on {@code admin-cli}, a token
 * refreshed before it expires, and JSON in/out. Never logs the password or the token.
 */
final class KeycloakAdmin {

    /** A response: status plus parsed body (MissingNode when empty or not JSON). */
    record Result(int status, JsonNode body, String raw) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    static final class AdminApiException extends RuntimeException {
        AdminApiException(String method, String path, Result r) {
            super(method + " " + path + " -> " + r.status() + " " + abbreviate(r.raw()));
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String realm;
    private final String username;
    private final String password;

    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    KeycloakAdmin(ObjectMapper json, String baseUrl, String realm, String username, String password) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.realm = realm;
        this.username = username;
        this.password = password;
    }

    String realm() {
        return realm;
    }

    String baseUrl() {
        return baseUrl;
    }

    /** Polls {@code /realms/master} until Keycloak answers, or gives up after {@code timeout}. */
    void waitUntilReady(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        int attempt = 0;
        while (true) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/realms/master"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // not up yet
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Keycloak at " + baseUrl + " did not become ready within " + timeout);
            }
            if (attempt++ % 6 == 0) {
                System.out.println("  waiting for Keycloak at " + baseUrl + " ...");
            }
            Thread.sleep(5_000);
        }
    }

    private String token() throws IOException, InterruptedException {
        if (token != null && Instant.now().isBefore(tokenExpires)) {
            return token;
        }
        String form = "grant_type=password&client_id=admin-cli"
                + "&username=" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/realms/master/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("admin login to the master realm failed: HTTP " + r.statusCode()
                    + " (check KEYCLOAK_ADMIN_USERNAME / KEYCLOAK_ADMIN_PASSWORD)");
        }
        JsonNode body = json.readTree(r.body());
        token = body.path("access_token").asText();
        long ttl = body.path("expires_in").asLong(60);
        tokenExpires = Instant.now().plusSeconds(Math.max(5, ttl - 15));
        return token;
    }

    /** Realm-scoped path: {@code users} → {@code /admin/realms/<realm>/users}. Empty = the realm itself. */
    private String url(String path, boolean realmScoped) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (!realmScoped) {
            return baseUrl + "/admin/" + p;
        }
        return baseUrl + "/admin/realms/" + realm + (p.isEmpty() ? "" : "/" + p);
    }

    Result call(String method, String path, Object body, boolean realmScoped) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path, realmScoped)))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token())
                    .header("Accept", "application/json");
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                String payload = body instanceof String s ? json.writeValueAsString(s) : json.writeValueAsString(body);
                b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(payload));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = MissingNode.getInstance();
            String raw = r.body() == null ? "" : r.body();
            if (!raw.isBlank()) {
                try {
                    parsed = json.readTree(raw);
                } catch (IOException notJson) {
                    // keep MissingNode
                }
            }
            return new Result(r.statusCode(), parsed, raw);
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    Result call(String method, String path, Object body) {
        return call(method, path, body, true);
    }

    /** GET; 404 → MissingNode; other errors throw. */
    JsonNode get(String path) {
        Result r = call("GET", path, null);
        if (r.status() == 404) {
            return MissingNode.getInstance();
        }
        if (!r.ok()) {
            throw new AdminApiException("GET", path, r);
        }
        return r.body();
    }

    Result post(String path, Object body) {
        return expectOk("POST", path, body, true);
    }

    Result put(String path, Object body) {
        return expectOk("PUT", path, body, true);
    }

    Result expectOk(String method, String path, Object body, boolean realmScoped) {
        Result r = call(method, path, body, realmScoped);
        if (!r.ok()) {
            throw new AdminApiException(method, path, r);
        }
        return r;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 300 ? t : t.substring(0, 300) + "...";
    }
}
