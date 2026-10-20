package rw.gov.risa.sso.audit.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import rw.gov.risa.sso.audit.api.dto.AuditEventCreate;
import rw.gov.risa.sso.audit.api.dto.AuditEventRead;
import rw.gov.risa.sso.audit.api.dto.EventPage;
import rw.gov.risa.sso.audit.repository.EventFilter;
import rw.gov.risa.sso.audit.service.EventService;

@RestController
@RequestMapping("/audit/events")
@Tag(name = "Events")
@Validated
public class EventsController {

    private final EventService service;

    public EventsController(EventService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Append one event to the hash chain",
            description = "Writers: the wrapper, the Keycloak listener. institution/target, when absent, are lifted "
                    + "from details.institution and details.target_user / details.target.")
    public AuditEventRead create(@Valid @RequestBody AuditEventCreate payload) {
        return service.ingest(payload);
    }

    @GetMapping
    @Operation(summary = "List events, newest first", description = "Returns {total, limit, offset, items}.")
    public EventPage list(
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "user_id", required = false) String userId,
            @RequestParam(name = "app_id", required = false) String appId,
            @RequestParam(name = "realm", required = false) String realm,
            @Parameter(description = "Institution slug (ADR-0001). Exact match on the column.")
            @RequestParam(name = "institution", required = false) String institution,
            @Parameter(description = "What was acted on — user id, client id, or role name.")
            @RequestParam(name = "target", required = false) String target,
            @RequestParam(name = "severity", required = false) String severity,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "from_date", required = false) String fromDate,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "to_date", required = false) String toDate,
            @RequestParam(name = "limit", defaultValue = "100") @Min(1) @Max(1000) int limit,
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset) {
        EventFilter f = new EventFilter(eventType, category, userId, appId, realm, institution, target, severity,
                DateParams.parse("from_date", fromDate), DateParams.parse("to_date", toDate));
        return service.list(f, limit, offset);
    }

    @GetMapping("/{user_id}")
    @Operation(summary = "Events of one user, newest first")
    public List<AuditEventRead> forUser(
            @PathVariable("user_id") String userId,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "from_date", required = false) String fromDate,
            @Parameter(schema = @Schema(type = "string", format = "date-time"))
            @RequestParam(name = "to_date", required = false) String toDate,
            @RequestParam(name = "limit", defaultValue = "200") @Min(1) @Max(1000) int limit) {
        return service.forUser(userId, DateParams.parse("from_date", fromDate), DateParams.parse("to_date", toDate),
                limit);
    }
}
