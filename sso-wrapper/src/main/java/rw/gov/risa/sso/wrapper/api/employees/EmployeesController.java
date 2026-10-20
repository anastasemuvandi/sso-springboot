package rw.gov.risa.sso.wrapper.api.employees;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.policy.institutioncontext.DirectoryScope;
import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.directory.EmployeeDirectoryService;
import rw.gov.risa.sso.wrapper.service.directory.EmployeeViews.EmployeeDetail;
import rw.gov.risa.sso.wrapper.service.directory.EmployeeViews.EmployeePage;

/**
 * The employee directory — the IPPIS mirror, read. NOT behind the admin tier: a platform admin
 * reads the whole realm, a delegated admin only their institution (resolved server-side from their
 * {@code inst:<slug>:*} role); anyone else gets 403. Nothing returned here decides access.
 */
@RestController
@RequestMapping("/employees")
@Validated
@Tag(name = "Employees")
public class EmployeesController {

    private final EmployeeDirectoryService directory;

    public EmployeesController(EmployeeDirectoryService directory) {
        this.directory = directory;
    }

    @GetMapping
    @RateLimit("120/minute")
    @Operation(summary = "List employees IPPIS has reported (one row per employment), scoped to the caller")
    public EmployeePage list(
            @AuthenticationPrincipal CallerPrincipal caller,
            @Parameter(description = "Platform admins only: read one institution. A delegated admin may only name "
                    + "their own.") @RequestParam(required = false) String institution,
            @Parameter(description = "Match employee number, name, email or position (case-insensitive)")
            @RequestParam(defaultValue = "") String search,
            @Parameter(description = "Employment status: active / suspended / terminated")
            @RequestParam(required = false) String status,
            @Parameter(description = "Account state: linked / unlinked / ambiguous")
            @RequestParam(required = false) String account,
            @Parameter(description = "Only employments whose employer the registry does not know")
            @RequestParam(defaultValue = "false") boolean unmapped,
            @Parameter(description = "employee_number, name, institution, position, status, first_seen_at, "
                    + "last_seen_at, last_synced_at") @RequestParam(required = false) String sort,
            @Parameter(description = "asc or desc") @RequestParam(defaultValue = "asc") String dir,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) int offset) {
        DirectoryScope scope = directory.scope(caller, institution);
        return directory.list(scope, search, status, account, unmapped, sort, "desc".equalsIgnoreCase(dir), limit,
                offset);
    }

    @GetMapping("/{employeeNumber}")
    @RateLimit("120/minute")
    @Operation(summary = "One employee in full (the only place nationalId is returned). 404 when not visible to "
            + "the caller, never 403")
    public EmployeeDetail get(@AuthenticationPrincipal CallerPrincipal caller, @PathVariable String employeeNumber,
                              @RequestParam(required = false) String institution) {
        DirectoryScope scope = directory.scope(caller, institution);
        return directory.detail(scope, employeeNumber);
    }
}
