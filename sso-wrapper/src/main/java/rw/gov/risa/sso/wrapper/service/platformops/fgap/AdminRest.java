package rw.gov.risa.sso.wrapper.service.platformops.fgap;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * A deliberately RAW Keycloak Admin REST client (status codes and JSON, nothing hidden) — the FGAP v2
 * endpoints and the {@code verify} acceptance test are about exactly the status codes a typed client
 * would hide (port of the {@code KC} class in {@code scripts/kc26_spike.py}). Paths are relative to
 * {@code /admin/realms/<realm>/} unless they start with {@code /}.
 */
public final class AdminRest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final String realm;
    private final Supplier<String> token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** One answer: status and parsed body ({@link MissingNode} when empty or not JSON). */
    public record Result(int status, JsonNode body, String raw) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** Thrown by the {@code require*} methods on a non-2xx answer. */
    public static final class AdminApiException extends RuntimeException {
        private final int status;

        public AdminApiException(String method, String path, Result r) {
            super(method + " " + path + " -> " + r.status() + " " + truncate(r.raw()));
            this.status = r.status();
        }

        public int status() {
            return status;
        }
    }

    public AdminRest(String baseUrl, String realm, Supplier<String> token) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.realm = realm;
        this.token = token;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String realm() {
        return realm;
    }

    /** The same server and realm, another identity (e.g. an institution admin's token). */
    public AdminRest as(Supplier<String> otherToken) {
        return new AdminRest(baseUrl, realm, otherToken);
    }

    public Result request(String method, String path, Object body) {
        String url = path.startsWith("/") ? baseUrl + path
                : (baseUrl + "/admin/realms/" + realm + "/" + path).replaceAll("/$", "");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token.get());
        try {
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed;
            try {
                parsed = res.body() == null || res.body().isBlank() ? MissingNode.getInstance() : JSON.readTree(res.body());
            } catch (IOException e) {
                parsed = MissingNode.getInstance();
            }
            return new Result(res.statusCode(), parsed, res.body());
        } catch (IOException e) {
            throw new IllegalStateException("Keycloak unreachable at " + baseUrl + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    public Result get(String path) {
        return request("GET", path, null);
    }

    public JsonNode require(String method, String path, Object body) {
        Result r = request(method, path, body);
        if (!r.ok()) {
            throw new AdminApiException(method, path, r);
        }
        return r.body();
    }

    public JsonNode requireGet(String path) {
        return require("GET", path, null);
    }

    // ---- lookups (null when absent) -----------------------------------------------------------

    public JsonNode findClient(String clientId) {
        JsonNode list = requireGet("clients?clientId=" + enc(clientId));
        return list.isArray() && !list.isEmpty() ? list.get(0) : null;
    }

    public JsonNode findGroup(String path) {
        Result r = get("group-by-path/" + encPath(path.startsWith("/") ? path.substring(1) : path));
        return r.ok() && r.body().isObject() ? r.body() : null;
    }

    public JsonNode findUser(String username) {
        JsonNode list = requireGet("users?username=" + enc(username) + "&exact=true");
        return list.isArray() && !list.isEmpty() ? list.get(0) : null;
    }

    public JsonNode findRealmRole(String name) {
        Result r = get("roles/" + encPath(name));
        return r.ok() && r.body().isObject() ? r.body() : null;
    }

    // ---- tokens --------------------------------------------------------------------------------

    /**
     * A password-grant access token for an ordinary realm user ({@code client_secret} optional for a
     * confidential client). Returns the raw token endpoint answer.
     */
    public static Result passwordGrant(String baseUrl, String realm, String clientId, String clientSecret,
                                       String username, String password) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        StringBuilder form = new StringBuilder("grant_type=password&scope=openid")
                .append("&client_id=").append(enc(clientId))
                .append("&username=").append(enc(username))
                .append("&password=").append(enc(password));
        if (clientSecret != null && !clientSecret.isBlank()) {
            form.append("&client_secret=").append(enc(clientSecret));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(trimmed + "/realms/" + realm
                        + "/protocol/openid-connect/token"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString())).build();
        try {
            HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body;
            try {
                body = JSON.readTree(res.body());
            } catch (IOException e) {
                body = MissingNode.getInstance();
            }
            return new Result(res.statusCode(), body, res.body());
        } catch (IOException e) {
            throw new IllegalStateException("Keycloak unreachable at " + trimmed + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    public static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Encode each path segment but keep the slashes. */
    public static String encPath(String s) {
        return java.util.Arrays.stream(s.split("/", -1))
                .map(part -> URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(java.util.stream.Collectors.joining("/"));
    }

    static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 400 ? s : s.substring(0, 400);
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
