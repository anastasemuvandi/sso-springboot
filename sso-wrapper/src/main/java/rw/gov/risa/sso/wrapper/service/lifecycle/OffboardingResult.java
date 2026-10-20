package rw.gov.risa.sso.wrapper.service.lifecycle;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The reference's {@code OffboardingResult}, plus what the spec's offboarding also removes
 * ({@code roles_removed}, {@code client_grants_removed}).
 */
public record OffboardingResult(
        boolean success,
        @JsonProperty("user_id") String userId,
        @JsonProperty("sessions_killed") int sessionsKilled,
        @JsonProperty("groups_removed") List<GroupSnapshot> groupsRemoved,
        @JsonProperty("roles_removed") List<String> rolesRemoved,
        @JsonProperty("client_grants_removed") List<String> clientGrantsRemoved,
        @JsonProperty("account_disabled") boolean accountDisabled,
        OffsetDateTime timestamp,
        @JsonProperty("rolled_back") boolean rolledBack,
        String error) {

    public record GroupSnapshot(String id, String name) {
    }
}
