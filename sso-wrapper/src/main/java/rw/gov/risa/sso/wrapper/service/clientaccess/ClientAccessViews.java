package rw.gov.risa.sso.wrapper.service.clientaccess;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessReason;

/** Read models returned by {@link ClientAccessService}; serialise with snake_case keys. */
public final class ClientAccessViews {

    private ClientAccessViews() {
    }

    /** An id with a display label ({@code username} for a user, {@code name} for a group); label may be null. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record NamedRef(String id, String name) {
    }

    /**
     * {@code get_client_assignments}: who the client's POSITIVE policies admit, by name, plus the
     * deny-list. {@code enforced} = any user or group assigned. Unresolvable names are null, never an error.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AssignmentView(String clientId, boolean enforced, List<NamedRef> users, List<NamedRef> groups,
                                 List<String> deniedUserIds) {
    }

    /**
     * The login gate's verdict ({@code validate_user_client_access}). {@code reason} is null when the
     * check itself failed (then {@code hasAccess=false} and {@code message} says why) or for an exempt client.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LoginCheck(boolean hasAccess, AccessReason reason, boolean exempt, List<String> userGroups,
                             List<String> requiredGroups, String message) {
    }

    /**
     * One row of the per-user view. {@code state} is the {@code AccessState} wire value
     * ({@code granted|open|revoked|not_granted|exempt|unknown}); for {@code unknown} the decision
     * fields are empty and {@code error} says why.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AccessItem(String clientId, String clientUuid, String name, String state, Boolean hasAccess,
                             String reason, boolean direct, List<NamedRef> viaGroups, boolean denied,
                             boolean deniedByName, List<NamedRef> deniedGroups, String error) {
    }

    /** One person across every non-built-in client. Items sorted: exempt last, then by client id. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UserAccessView(String userId, String username, List<NamedRef> groups, List<AccessItem> items) {
    }

    /**
     * {@code removeNamedGrantsEverywhere}: clients whose {@code sso-access-users} entry for the person
     * was removed, and clients that could not be processed (clientId → reason).
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record GrantRemoval(String userId, List<String> changedClientIds, Map<String, String> failures) {
    }
}
