package rw.gov.risa.sso.notification.api;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rw.gov.risa.sso.notification.config.NotificationConfig;
import rw.gov.risa.sso.notification.service.AlertService;

@RestController
@RequestMapping("/alerts")
@Tag(name = "alerts")
@SecurityRequirement(name = NotificationConfig.API_KEY_SCHEME)
public class AlertsController {

    private final AlertService alerts;

    public AlertsController(AlertService alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    @Operation(summary = "List alerts, newest first")
    public List<AlertRead> list(
            @Parameter(description = "Filter by CRITICAL/WARNING/INFO") @RequestParam(required = false) String severity,
            @Parameter(description = "Filter by acknowledged status") @RequestParam(required = false) Boolean acknowledged,
            @RequestParam(name = "rule_name", required = false) String ruleName,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return alerts.list(severity, acknowledged, ruleName, limit, offset);
    }

    @GetMapping("/{alert_id}")
    @Operation(summary = "Get one alert")
    public AlertRead get(@PathVariable("alert_id") UUID alertId) {
        return alerts.get(alertId);
    }

    @PutMapping("/{alert_id}/acknowledge")
    @Operation(summary = "Acknowledge an alert (re-arms its rule's cooldown for that subject)")
    public AlertRead acknowledge(@PathVariable("alert_id") UUID alertId,
                                 @Valid @RequestBody AcknowledgeRequest body) {
        return alerts.acknowledge(alertId, body.acknowledgedBy());
    }
}
