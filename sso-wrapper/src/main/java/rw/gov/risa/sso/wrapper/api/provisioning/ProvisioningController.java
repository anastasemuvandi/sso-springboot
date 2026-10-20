package rw.gov.risa.sso.wrapper.api.provisioning;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.institution.CurrentInstitution;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeProvisioningService;
import rw.gov.risa.sso.wrapper.service.provisioning.ProvisionViews;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * {@code POST /institution-admin/employees/provision} (spec §5.11). Distinct from the other
 * {@code /institution-admin/*} controllers; the institution is resolved from the caller's own grant
 * ({@code ?institution=} is only needed by someone administering several, and is checked, never honoured
 * blindly).
 */
@RestController
@RequestMapping("/institution-admin")
@Tag(name = "Institution admin — provisioning")
public class ProvisioningController {

    private final EmployeeProvisioningService provisioning;

    public ProvisioningController(EmployeeProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    /** The request body (reference {@code ProvisionRequest}). */
    public record ProvisionRequest(
            @JsonProperty("employee_numbers") @NotNull @Schema(description = "IPPIS employee numbers, with or "
                    + "without an SSO account") List<String> employeeNumbers,
            @JsonProperty("client_ids") @NotNull List<String> clientIds,
            @JsonProperty("dry_run") @Schema(description = "Produce the identical report and write nothing")
            boolean dryRun) {
    }

    @PostMapping("/employees/provision")
    @RateLimit("10/minute")
    @Operation(summary = "Give selected employees access to selected applications, creating the accounts that do "
            + "not exist yet", description = "Validation is per (employee, application), never all-or-nothing; "
            + "apps already held are skipped; an existing account is never recreated or altered (only the apps are "
            + "granted, and the person gets an 'access granted' email); a new account is named after the email, gets "
            + "a temporary password nobody is told and the wrapper's set-password link. An application outside your "
            + "scope is 403 for the whole request. 503 when the employee mirror is not configured.")
    public ProvisionViews.ProvisionReportView provision(
            @AuthenticationPrincipal CallerPrincipal caller,
            @Parameter(hidden = true) @CurrentInstitution(require = Capability.MANAGE_MEMBERS) InstitutionContext ctx,
            @Parameter(description = AccessMatrixController.INSTITUTION_PARAM) @RequestParam(required = false)
            String institution,
            @Valid @RequestBody ProvisionRequest body) {
        return ProvisionViews.of(provisioning.run(ctx, EmployeeProvisioningService.Command.of(body.employeeNumbers(),
                body.clientIds(), body.dryRun(), RequestInfo.clientIp())));
    }
}
