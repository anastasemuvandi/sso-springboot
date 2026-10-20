package rw.gov.risa.sso.audit.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.audit.api.dto.IntegrityReport;
import rw.gov.risa.sso.audit.service.IntegrityService;

@RestController
@RequestMapping("/audit/integrity")
@Tag(name = "Integrity")
public class IntegrityController {

    private final IntegrityService service;

    public IntegrityController(IntegrityService service) {
        this.service = service;
    }

    @GetMapping("/verify")
    @Operation(summary = "Recompute every hash in the window and check the links",
            description = "valid=false when any row's hash or prev_hash link fails, or (no to_date) when the "
                    + "newest row is not the recorded chain head.")
    public IntegrityReport verify(
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "from_date", required = false) String fromDate,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "to_date", required = false) String toDate) {
        return service.verify(DateParams.parse("from_date", fromDate), DateParams.parse("to_date", toDate));
    }
}
