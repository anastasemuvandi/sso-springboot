package rw.gov.risa.sso.wrapper.service.lifecycle;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The reference's {@code ReassignmentResult}. */
public record ReassignmentResult(
        boolean success,
        @JsonProperty("user_id") String userId,
        @JsonProperty("groups_removed") List<String> groupsRemoved,
        @JsonProperty("groups_added") List<String> groupsAdded,
        @JsonProperty("groups_not_found") List<String> groupsNotFound,
        @JsonProperty("session_refreshed") boolean sessionRefreshed,
        OffsetDateTime timestamp,
        @JsonProperty("rolled_back") boolean rolledBack,
        String error) {
}
