package rw.gov.risa.sso.notification.api;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "health")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "Liveness (reference shape); readiness is /actuator/health/readiness")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "notification-service");
    }
}
