package rw.gov.risa.sso.audit.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.audit.api.dto.RetentionPolicyCreate;
import rw.gov.risa.sso.audit.api.dto.RetentionPolicyRead;
import rw.gov.risa.sso.audit.api.dto.RetentionRunResult;
import rw.gov.risa.sso.audit.service.RetentionService;

@RestController
@RequestMapping("/audit/retention")
@Tag(name = "Retention")
public class RetentionController {

    static final String API_ACTOR = "api:audit-api-key";

    private final RetentionService service;

    public RetentionController(RetentionService service) {
        this.service = service;
    }

    @GetMapping("/policies")
    @Operation(summary = "Retention policies (default policy, category null, first)")
    public List<RetentionPolicyRead> policies() {
        return service.list();
    }

    @PostMapping("/policy")
    @Operation(summary = "Create or replace the policy for one category (null = default); audited",
            description = "legal_hold=true exempts the category from every purge until it is lifted.")
    public RetentionPolicyRead upsert(@Valid @RequestBody RetentionPolicyCreate payload) {
        return service.set(payload, API_ACTOR);
    }

    @PostMapping("/apply")
    @Operation(summary = "Run the retention purge now (also runs daily at 02:00 Africa/Kigali)")
    public RetentionRunResult apply() {
        return service.apply(API_ACTOR);
    }
}
