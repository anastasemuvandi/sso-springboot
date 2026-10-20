package rw.gov.risa.sso.wrapper.api.lifecycle;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request bodies of the lifecycle endpoints — the reference's JSON names. */
public final class LifecycleRequests {
    private LifecycleRequests() {
    }

    public record TerminateEmployeeRequest(
            @JsonProperty("user_id") @NotBlank @Schema(description = "Keycloak user UUID to offboard") String userId,
            @NotBlank @Schema(description = "Business reason for offboarding") String reason,
            @JsonProperty("triggered_by") @Schema(description = "IGNORED — accepted for compatibility; the actor is "
                    + "always the authenticated caller", deprecated = true) String triggeredBy) {
    }

    public record ReassignAccessRequest(
            @JsonProperty("user_id") @NotBlank @Schema(description = "Keycloak user UUID") String userId,
            @JsonProperty("remove_groups") List<String> removeGroups,
            @JsonProperty("add_groups") List<String> addGroups,
            @JsonProperty("force_session_refresh") Boolean forceSessionRefresh,
            @NotBlank String reason,
            @JsonProperty("triggered_by") @Schema(description = "IGNORED — the actor is always the authenticated "
                    + "caller", deprecated = true) String triggeredBy) {

        public List<String> removeGroupsOrEmpty() {
            return removeGroups == null ? List.of() : removeGroups;
        }

        public List<String> addGroupsOrEmpty() {
            return addGroups == null ? List.of() : addGroups;
        }
    }
}
