package rw.gov.risa.sso.audit.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.audit.api.dto.ServiceStatus;

/** Reference {@code GET /health} (no API key). Readiness/liveness live under /actuator/health. */
@RestController
@Tag(name = "Health")
public class RootController {

    @GetMapping("/health")
    @Operation(summary = "Liveness ping")
    public ServiceStatus health() {
        return new ServiceStatus("ok", "audit-service");
    }
}
