package rw.gov.risa.sso.audit.workers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import rw.gov.risa.sso.audit.config.AuditProperties;

/**
 * Reads users carrying a {@code certExpiry} attribute through the Keycloak Admin REST API
 * (reference workers/cert_expiry.py). Fresh admin token per run; pages through every user
 * (the reference stopped at the first 1000).
 */
@Component
public class KeycloakUserSource {

    /** One user with a certificate. {@code certExpiry} is the raw attribute (YYYY-MM-DD expected). */
    public record CertUser(String userId, String employeeId, String email, String certExpiry) {
    }

    static final int PAGE = 500;
    static final int MAX_USERS = 200_000;

    private final AuditProperties.Keycloak kc;
    private final RestClient rest;

    public KeycloakUserSource(AuditProperties props, RestClient.Builder builder) {
        this.kc = props.keycloak();
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(10_000);
        rf.setReadTimeout(15_000);
        this.rest = builder.requestFactory(rf).baseUrl(kc.serverUrl()).build();
    }

    public boolean configured() {
        return kc.adminPassword() != null && !kc.adminPassword().isBlank();
    }

    public List<CertUser> usersWithCertExpiry() {
        String token = token();
        List<CertUser> out = new ArrayList<>();
        for (int first = 0; first < MAX_USERS; first += PAGE) {
            final int offset = first;
            List<Map<String, Object>> page = rest.get()
                    .uri(u -> u.path("/admin/realms/{realm}/users").queryParam("first", offset)
                            .queryParam("max", PAGE).queryParam("briefRepresentation", false).build(kc.realm()))
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<>() {
                    });
            if (page == null || page.isEmpty()) {
                break;
            }
            for (Map<String, Object> user : page) {
                CertUser c = toCertUser(user);
                if (c != null) {
                    out.add(c);
                }
            }
            if (page.size() < PAGE) {
                break;
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static CertUser toCertUser(Map<String, Object> user) {
        if (!(user.get("attributes") instanceof Map<?, ?> attrs)) {
            return null;
        }
        String expiry = first(attrs.get("certExpiry"));
        if (expiry == null) {
            return null;
        }
        return new CertUser(String.valueOf(user.get("id")), first(attrs.get("employeeId")),
                user.get("email") instanceof String s ? s : null, expiry);
    }

    private static String first(Object v) {
        if (v instanceof List<?> l && !l.isEmpty() && l.get(0) != null) {
            return String.valueOf(l.get(0));
        }
        return null;
    }

    private String token() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", kc.adminClientId());
        form.add("username", kc.adminUsername());
        form.add("password", kc.adminPassword());
        form.add("grant_type", "password");
        Map<String, Object> body = rest.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", kc.adminRealm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<>() {
                });
        if (body == null || !(body.get("access_token") instanceof String t)) {
            throw new IllegalStateException("Keycloak token response had no access_token");
        }
        return t;
    }
}
