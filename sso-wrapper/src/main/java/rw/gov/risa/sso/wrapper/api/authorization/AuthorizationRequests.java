package rw.gov.risa.sso.wrapper.api.authorization;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/** Request bodies of {@code /authorization/**} (reference {@code schema/authorization.py}, {@code api/authorization.py}). */
public final class AuthorizationRequests {

    private AuthorizationRequests() {
    }

    @Schema(name = "Scope")
    public record ScopeBody(
            @NotBlank String name,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("icon_uri") String iconUri) {
    }

    @Schema(name = "Policy")
    public record PolicyBody(
            String id,
            @NotBlank String name,
            String description,
            String type,
            @Pattern(regexp = "POSITIVE|NEGATIVE") String logic,
            @JsonProperty("decision_strategy") @Pattern(regexp = "UNANIMOUS|AFFIRMATIVE|CONSENSUS") String decisionStrategy,
            List<Map<String, Object>> roles,
            List<Map<String, Object>> groups) {
    }

    @Schema(name = "ClientAccessAssignment")
    public record ClientAccessAssignment(
            @JsonProperty("user_ids") List<@NotBlank String> userIds,
            @JsonProperty("group_ids") List<@NotBlank String> groupIds) {
        public List<String> users() {
            return userIds == null ? List.of() : userIds;
        }

        public List<String> groups() {
            return groupIds == null ? List.of() : groupIds;
        }
    }

    /**
     * One cell: {@code grant}, {@code revoke}, or {@code restore} (a grant on a {@code revoked} cell).
     * {@code expected_state} is the cell's state as the caller last saw it.
     */
    @Schema(name = "UserClientAccessChange")
    public record UserClientAccessChange(
            @NotBlank @Pattern(regexp = "grant|revoke|restore") String action,
            @JsonProperty("expected_state") @NotBlank String expectedState) {
    }
}
