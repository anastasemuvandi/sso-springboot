package rw.gov.risa.sso.wrapper.api.lifecycle;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.api.lifecycle.LifecycleRequests.ReassignAccessRequest;
import rw.gov.risa.sso.wrapper.api.lifecycle.LifecycleRequests.TerminateEmployeeRequest;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.lifecycle.LifecycleAuditService;
import rw.gov.risa.sso.wrapper.service.lifecycle.LifecycleService;
import rw.gov.risa.sso.wrapper.service.lifecycle.OffboardingResult;
import rw.gov.risa.sso.wrapper.service.lifecycle.ReassignmentResult;

/**
 * Platform-admin lifecycle routes (admin-tier gate in {@code RouteRules}). The actor recorded in the
 * audit trail is ALWAYS the authenticated caller — a {@code triggered_by} in the body is ignored.
 */
@RestController
@Validated
@Tag(name = "Lifecycle")
public class LifecycleController {

    private final LifecycleService lifecycle;
    private final LifecycleAuditService auditProxy;

    public LifecycleController(LifecycleService lifecycle, LifecycleAuditService auditProxy) {
        this.lifecycle = lifecycle;
        this.auditProxy = auditProxy;
    }

    @PostMapping("/offboarding/terminate")
    @Operation(summary = "Full employee offboarding: end sessions, disable, remove groups, strip inst:<slug>:* "
            + "roles and sso-access-users grants; rolled back on failure")
    public OffboardingResult terminate(@AuthenticationPrincipal CallerPrincipal caller,
                                       @Valid @RequestBody TerminateEmployeeRequest body) {
        return lifecycle.terminate(body.userId(), body.reason(), caller.username());
    }

    @PostMapping("/access/reassign")
    @Operation(summary = "Department transfer / access reassignment between groups (atomic)")
    public ReassignmentResult reassign(@AuthenticationPrincipal CallerPrincipal caller,
                                       @Valid @RequestBody ReassignAccessRequest body) {
        return lifecycle.reassign(body.userId(), body.removeGroupsOrEmpty(), body.addGroupsOrEmpty(),
                Boolean.TRUE.equals(body.forceSessionRefresh()), body.reason(), caller.username());
    }

    @GetMapping("/lifecycle/audit")
    @Operation(summary = "All lifecycle audit events (proxied from the audit service)")
    public JsonNode audit(@RequestParam(defaultValue = "100") @Min(1) @Max(1000) int limit,
                          @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return auditProxy.events(limit, offset);
    }

    @GetMapping("/lifecycle/audit/{userId}")
    @Operation(summary = "One user's lifecycle history (proxied from the audit service)")
    public JsonNode userAudit(@PathVariable String userId,
                              @RequestParam(defaultValue = "50") @Min(1) @Max(1000) int limit,
                              @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return auditProxy.userEvents(userId, limit, offset);
    }
}
