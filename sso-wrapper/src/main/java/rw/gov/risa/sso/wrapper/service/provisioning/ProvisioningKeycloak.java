package rw.gov.risa.sso.wrapper.service.provisioning;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.error.KeycloakErrors;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.AccountResolver;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * The Keycloak half of employee provisioning, on the wrapper's SERVICE ACCOUNT (reference: creating a realm
 * user and writing a client's policy need realm-wide roles a delegated admin structurally does not hold —
 * ADR-0001 §4). The institution boundary is therefore held by {@link EmployeeProvisioningService} BEFORE any
 * call here: the mirror query filters on the caller's institution and every client passes
 * {@code assertClientInScope}.
 *
 * <p>Lookups answer empty on "none" <b>and on failure</b> (logged), like the reference: a failed lookup
 * falls through to the next one. No method ever sees or logs a password except {@link #setPassword}, which
 * logs nothing about the value.
 */
@Component
public class ProvisioningKeycloak {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningKeycloak.class);

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;

    public ProvisioningKeycloak(KeycloakExecutors executors, KeycloakDirectory directory) {
        this.executors = executors;
        this.directory = directory;
    }

    private KeycloakExec sa() {
        return executors.serviceAccount();
    }

    /** Every client in the realm (the catalogue the selected targets are looked up in). */
    public List<ClientRepresentation> catalogue() {
        return sa().call(directory::listClients);
    }

    /** The full user by id; empty when gone (a stale mirror link) or unreadable. */
    public Optional<UserRepresentation> userById(String userId) {
        return quietly("user " + userId, () -> sa().call(realm -> directory.getUser(realm, userId)));
    }

    /** The account whose username is exactly {@code username}. */
    public Optional<UserRepresentation> userByUsername(String username) {
        return quietly("username lookup", () -> sa().call(realm -> directory.findUserByUsername(realm, username)
                .flatMap(u -> directory.getUser(realm, u.getId()))));
    }

    /** The first account carrying {@code email} (exact). */
    public Optional<UserRepresentation> userByEmail(String email) {
        return quietly("email lookup", () -> sa().call(realm -> directory.findUsersByEmail(realm, email, 2).stream()
                .findFirst().flatMap(u -> directory.getUser(realm, u.getId()))));
    }

    /** The first account carrying the {@code employee_number} attribute. */
    public Optional<UserRepresentation> userByEmployeeNumber(String employeeNumber) {
        return quietly("employee_number lookup", () -> sa().call(realm -> directory
                .findUsersByAttribute(realm, AccountResolver.ATTR_EMPLOYEE_NUMBER, employeeNumber, 2).stream()
                .findFirst()));
    }

    /** The user's DIRECT group ids; empty on failure (held-client computation treats it as "none"). */
    public Set<String> directGroupIds(String userId) {
        return quietly("groups of " + userId, () -> sa().call(realm -> {
            Set<String> ids = new LinkedHashSet<>();
            for (GroupRepresentation g : directory.userGroups(realm, userId)) {
                if (g.getId() != null) {
                    ids.add(g.getId());
                }
            }
            return Optional.of((Set<String>) ids);
        })).orElse(Set.of());
    }

    /** The institution's member group id. */
    public Optional<String> groupIdByPath(String path) {
        return sa().call(realm -> directory.groupByPath(realm, path)).map(GroupRepresentation::getId);
    }

    /**
     * Create the user; returns its id. A 409 (username/email taken) becomes {@code conflict}. The
     * representation carries NO credential — {@link #setPassword} is a separate call.
     */
    public String createUser(UserRepresentation rep) {
        return sa().call(realm -> {
            try (Response r = realm.users().create(rep)) {
                int status = r.getStatus();
                if (status == 409) {
                    throw ApiException.conflict(ErrorCodes.CONFLICT,
                            "An account named '" + rep.getUsername() + "' already exists.", java.util.Map.of());
                }
                if (status < 200 || status >= 300) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.KEYCLOAK_ERROR,
                            "Keycloak refused to create the account (HTTP " + status + ").");
                }
                return CreatedResponseUtil.getCreatedId(r);
            }
        });
    }

    /** Set a password. The value is never logged, and a refusal's message never carries it. */
    public void setPassword(String userId, String password, boolean temporary) {
        try {
            sa().run(realm -> {
                CredentialRepresentation cred = new CredentialRepresentation();
                cred.setType(CredentialRepresentation.PASSWORD);
                cred.setValue(password);
                cred.setTemporary(temporary);
                realm.users().get(userId).resetPassword(cred);
            });
        } catch (WebApplicationException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, ErrorCodes.KEYCLOAK_ERROR,
                    "Keycloak refused the account's initial credential (HTTP " + KeycloakErrors.status(e) + ").");
        }
    }

    /** Put a NEW account in the institution's group (membership, never access). */
    public void addToGroup(String userId, String groupId) {
        sa().run(realm -> realm.users().get(userId).joinGroup(groupId));
    }

    private static <T> Optional<T> quietly(String what, java.util.function.Supplier<Optional<T>> s) {
        try {
            return s.get();
        } catch (WebApplicationException | ProcessingException | ApiException e) {
            log.warn("employee provisioning: {} failed: {}", what, e.getMessage());
            return Optional.empty();
        }
    }
}
