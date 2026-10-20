package rw.gov.risa.sso.wrapper.health;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import io.swagger.v3.oas.annotations.tags.Tag;
import rw.gov.risa.sso.wrapper.config.SsoProperties;

/** Public liveness ({@code /health/}) and readiness ({@code /health/ready}: Keycloak realm + Redis). */
@RestController
@RequestMapping("/health")
@Tag(name = "health")
public class HealthController {

    private final SsoProperties props;
    private final StringRedisTemplate redis;
    private final RestClient http = RestClient.create();

    public HealthController(SsoProperties props, StringRedisTemplate redis) {
        this.props = props;
        this.redis = redis;
    }

    @GetMapping({"", "/"})
    public Map<String, Object> health() {
        return Map.of("status", "healthy", "service", "GoR SSO");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("keycloak", check(() -> http.get().uri(props.keycloak().internalRealmUrl())
                .retrieve().toBodilessEntity()));
        checks.put("redis", check(() -> redis.getConnectionFactory().getConnection().ping()));
        boolean ok = checks.values().stream().allMatch("ok"::equals);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ok ? "ready" : "not_ready");
        body.put("checks", checks);
        return ResponseEntity.status(ok ? 200 : 503).body(body);
    }

    private static String check(Runnable r) {
        try {
            r.run();
            return "ok";
        } catch (RuntimeException e) {
            return "unavailable";
        }
    }
}
