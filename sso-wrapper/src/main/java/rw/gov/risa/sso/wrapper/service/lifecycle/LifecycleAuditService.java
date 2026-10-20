package rw.gov.risa.sso.wrapper.service.lifecycle;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

import rw.gov.risa.sso.wrapper.audit.AuditProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;

/**
 * Read-only proxy of the audit service's lifecycle events ({@code GET /lifecycle/audit[/{userId}]}).
 * The API key is injected server-side and never reaches the caller.
 */
@Service
public class LifecycleAuditService {

    private final AuditProperties props;
    private final RestClient http;

    public LifecycleAuditService(AuditProperties props, RestClient.Builder builder) {
        this.props = props;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(10));
        f.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.clone().requestFactory(f).build();
    }

    public JsonNode events(int limit, int offset) {
        return get("/audit/events", limit, offset);
    }

    public JsonNode userEvents(String userId, int limit, int offset) {
        return get("/audit/events/{userId}", limit, offset, userId);
    }

    private JsonNode get(String path, int limit, int offset, Object... vars) {
        String base = props.serviceUrl().endsWith("/") ? props.serviceUrl().substring(0, props.serviceUrl().length() - 1)
                : props.serviceUrl();
        var uri = UriComponentsBuilder.fromUriString(base + path)
                .queryParam("category", "LIFECYCLE").queryParam("limit", limit).queryParam("offset", offset)
                .buildAndExpand(vars).encode().toUri();
        try {
            return http.get().uri(uri).header(AuditProperties.API_KEY_HEADER, props.apiKey())
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            String body = e.getResponseBodyAsString();
            throw new ApiException(status == null ? HttpStatus.BAD_GATEWAY : status, "audit_service_error",
                    "Audit service error: " + (body.length() > 200 ? body.substring(0, 200) : body));
        } catch (RuntimeException e) {
            throw ApiException.unavailable(LifecycleErrors.AUDIT_UNAVAILABLE,
                    "Audit service unavailable: " + e.getClass().getSimpleName());
        }
    }
}
