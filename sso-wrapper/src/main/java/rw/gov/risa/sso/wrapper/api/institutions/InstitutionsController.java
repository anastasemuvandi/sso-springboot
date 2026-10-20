package rw.gov.risa.sso.wrapper.api.institutions;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.wrapper.ratelimit.RateLimit;
import rw.gov.risa.sso.wrapper.service.directory.InstitutionOverviewService;
import rw.gov.risa.sso.wrapper.service.directory.InstitutionOverviewService.Overview;

/** {@code GET /institutions}: registry vs Keycloak vs IPPIS-mirror reconciliation (admin tier only). */
@RestController
@Tag(name = "Institutions")
public class InstitutionsController {

    private final InstitutionOverviewService overview;

    public InstitutionsController(InstitutionOverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/institutions")
    @RateLimit("60/minute")
    @Operation(summary = "Reconcile the institution registry, Keycloak and the IPPIS mirror (worst rows first)")
    public Overview list() {
        return overview.overview();
    }
}
