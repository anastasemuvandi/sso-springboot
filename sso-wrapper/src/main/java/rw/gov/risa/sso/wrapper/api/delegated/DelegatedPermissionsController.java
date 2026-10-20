package rw.gov.risa.sso.wrapper.api.delegated;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.platformops.delegated.DelegatedOpsService;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * {@code /delegated-permissions}: the platform side of the delegation model (admin tier). Templates are
 * edited as a reviewed file, so there is deliberately no endpoint that edits one; this is everything
 * needed to operate them. A malformed/unsafe template file → 400.
 */
@RestController
@RequestMapping("/delegated-permissions")
@Tag(name = "Delegated Permissions")
public class DelegatedPermissionsController {

    private final DelegatedOpsService service;
    private final AuditEmitter audit;

    public DelegatedPermissionsController(DelegatedOpsService service, AuditEmitter audit) {
        this.service = service;
        this.audit = audit;
    }

    @Schema(name = "ApplyIn")
    public record ApplyIn(
            @Schema(description = "Canonical lowercase institution slugs to provision roles for.")
            @NotEmpty List<String> institutions,
            @Schema(description = "Report the plan without creating anything.")
            @JsonProperty("dry_run") boolean dryRun) {
    }

    @GetMapping
    @RateLimit("60/minute")
    @Operation(summary = "The loaded templates, guardrail and assignments — the review surface")
    public Map<String, Object> describe() {
        return service.describe();
    }

    @GetMapping("/institutions/{slug}")
    @RateLimit("60/minute")
    @Operation(summary = "What delegation would look like for one institution, before appointing anyone")
    public Map<String, Object> preview(@PathVariable("slug") String slug) {
        return service.preview(slug);
    }

    @PostMapping("/reload")
    @RateLimit("10/minute")
    @Operation(summary = "Re-read the template file. A bad edit is rejected and the old config stays")
    public Map<String, Object> reload() {
        return service.reload();
    }

    @PostMapping("/apply")
    @RateLimit("10/minute")
    @Operation(summary = "Create the realm roles the named institutions' templates require (additive, idempotent)")
    public Map<String, Object> apply(@Valid @RequestBody ApplyIn body,
                                     @AuthenticationPrincipal CallerPrincipal caller) {
        Map<String, Object> result = service.apply(body.institutions(), body.dryRun());
        if (!body.dryRun()) {
            audit.emit(AuditEvent.of(AuditEventType.DELEGATED_APPLIED).actor(caller.username())
                    .requestIp(RequestInfo.clientIp()).put("institutions", body.institutions())
                    .put("created", result.get("created")).build());
        }
        return result;
    }

    @GetMapping("/audit")
    @RateLimit("20/minute")
    @Operation(summary = "Guardrail violations, console-made grants and missing roles (clean=false on any finding)")
    public Map<String, Object> audit(@RequestParam(required = false) String institution) {
        return service.audit(institution);
    }
}
