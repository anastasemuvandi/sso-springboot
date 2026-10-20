package rw.gov.risa.sso.wrapper.service.clientaccess;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.PoliciesResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.GroupPolicyRepresentation;
import org.keycloak.representations.idm.authorization.Logic;
import org.keycloak.representations.idm.authorization.PolicyRepresentation;
import org.keycloak.representations.idm.authorization.UserPolicyRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.clientaccess.AccessPolicy;
import rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies;
import rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies.PolicyBody;
import rw.gov.risa.sso.wrapper.policy.clientaccess.CanonicalPolicies.PolicyChange;

/**
 * The Keycloak half of a client's {@code sso-access-*} authorization policies: read them (by type —
 * the policy module decides), enable the resource server, and apply a {@link CanonicalPolicies} plan.
 * Works on whatever {@link RealmResource} the caller opened (service account or as-caller).
 */
@Component
public class AccessPolicyStore {

    private static final Logger log = LoggerFactory.getLogger(AccessPolicyStore.class);

    /** Every authorization policy on the client; empty when authorization services are not enabled. */
    public List<AccessPolicy> read(RealmResource realm, String clientUuid) {
        List<PolicyRepresentation> reps;
        try {
            reps = realm.clients().get(clientUuid).authorization().policies().policies();
        } catch (NotFoundException e) {
            return List.of();
        }
        List<AccessPolicy> out = new ArrayList<>();
        if (reps != null) {
            for (PolicyRepresentation p : reps) {
                if (p != null) {
                    out.add(adapt(p));
                }
            }
        }
        return out;
    }

    static AccessPolicy adapt(PolicyRepresentation p) {
        Logic logic = p.getLogic();
        Map<String, String> config = p.getConfig();
        return new AccessPolicy(p.getId(), p.getName(), p.getType(), logic == null ? null : logic.name(), config);
    }

    /**
     * Ensure Authorization Services (the resource server) are on, so policies can be attached.
     * Idempotent. They need a confidential client with service accounts; a public client → 409.
     */
    public void enableAuthorizationServices(RealmResource realm, String clientUuid) {
        ClientResource resource = realm.clients().get(clientUuid);
        ClientRepresentation rep = resource.toRepresentation();
        boolean publicClient = Boolean.TRUE.equals(rep.isPublicClient());
        if (Boolean.TRUE.equals(rep.getAuthorizationServicesEnabled())
                && Boolean.TRUE.equals(rep.isServiceAccountsEnabled()) && !publicClient) {
            return;
        }
        if (publicClient) {
            throw ApiException.conflict(ErrorCodes.CONFLICT, "Authorization services require a confidential client. "
                    + "Client '" + rep.getClientId() + "' is public; set client authentication on it first.", Map.of());
        }
        rep.setAuthorizationServicesEnabled(true);
        rep.setServiceAccountsEnabled(true);
        resource.update(rep);
    }

    /** Apply a write plan in order. Enables authorization services first when anything is created/updated. */
    public void apply(RealmResource realm, String clientUuid, List<PolicyChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        if (CanonicalPolicies.needsAuthorizationServices(changes)) {
            enableAuthorizationServices(realm, clientUuid);
        }
        PoliciesResource policies = realm.clients().get(clientUuid).authorization().policies();
        for (PolicyChange change : changes) {
            switch (change.op()) {
                case DELETE -> {
                    try {
                        policies.policy(change.existingId()).remove();
                    } catch (NotFoundException e) {
                        log.info("client access: policy {} already gone", change.existingId());
                    }
                }
                case CREATE -> create(policies, change.body());
                case UPDATE -> update(policies, change.existingId(), change.body());
            }
        }
    }

    private static void create(PoliciesResource policies, PolicyBody body) {
        Response response;
        if (AccessPolicy.TYPE_GROUP.equals(body.type())) {
            response = policies.group().create(groupRep(null, body));
        } else {
            response = policies.user().create(userRep(null, body));
        }
        try (response) {
            int status = response.getStatus();
            if (status < 200 || status >= 300) {
                String text = response.hasEntity() ? response.readEntity(String.class) : "";
                throw writeFailed("create authorization policy '" + body.name() + "'", status, text);
            }
        }
    }

    private static void update(PoliciesResource policies, String id, PolicyBody body) {
        // Keycloak 26.2 answers a policy PUT with 201; the admin client accepts any 2xx.
        if (AccessPolicy.TYPE_GROUP.equals(body.type())) {
            policies.group().findById(id).update(groupRep(id, body));
        } else {
            policies.user().findById(id).update(userRep(id, body));
        }
    }

    static UserPolicyRepresentation userRep(String id, PolicyBody body) {
        UserPolicyRepresentation rep = new UserPolicyRepresentation();
        rep.setId(id);
        rep.setName(body.name());
        rep.setLogic(Logic.valueOf(body.logic()));
        rep.setDecisionStrategy(DecisionStrategy.valueOf(body.decisionStrategy()));
        rep.setUsers(new LinkedHashSet<>(body.userIds()));
        return rep;
    }

    static GroupPolicyRepresentation groupRep(String id, PolicyBody body) {
        GroupPolicyRepresentation rep = new GroupPolicyRepresentation();
        rep.setId(id);
        rep.setName(body.name());
        rep.setLogic(Logic.valueOf(body.logic()));
        rep.setDecisionStrategy(DecisionStrategy.valueOf(body.decisionStrategy()));
        for (String g : body.groupIds()) {
            rep.addGroup(g, false);
        }
        return rep;
    }

    /** A write Keycloak did not accept is never reported with a 2xx (reference {@code _require_written}). */
    static ApiException writeFailed(String action, int status, String body) {
        String message = "Failed to " + action + ": " + (body == null ? "" : body);
        return switch (status) {
            case 403 -> new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, message);
            case 404 -> new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, message);
            case 409 -> new ApiException(HttpStatus.CONFLICT, ErrorCodes.CONFLICT, message);
            case 400 -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_REQUEST, message);
            default -> new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.KEYCLOAK_ERROR, message);
        };
    }
}
