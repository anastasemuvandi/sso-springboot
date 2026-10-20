package rw.gov.risa.sso.wrapper.service.serviceaccount;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.ws.rs.NotFoundException;

import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.config.SsoProperties;

/**
 * Startup self-heal of the {@code sso-client} service account's {@code realm-management} roles (port of
 * {@code core/service_account_roles.py}). Keycloak's {@code --import-realm} skips an existing realm, so a
 * realm imported before a grant was added keeps missing it; this adds only what is missing.
 *
 * <p>Uses the MASTER realm admin ({@code KEYCLOAK_ADMIN_USERNAME}/{@code KEYCLOAK_ADMIN_PASSWORD}): a
 * service account cannot grant itself admin roles it does not hold. Best-effort and off the startup
 * path (runs on a background thread after the application is ready); never throws. Disabled with
 * {@code RECONCILE_SERVICE_ACCOUNT_ROLES=false}; skipped (logged) when no admin password is configured.
 */
@Component
public class ServiceAccountRoleReconciler {

    private static final Logger log = LoggerFactory.getLogger(ServiceAccountRoleReconciler.class);

    /**
     * The roles the wrapper needs. The reference list (view-realm, view-users, view-clients, view-events,
     * query-users, query-clients, query-groups, manage-clients, manage-authorization) plus manage-users
     * (user writes), manage-realm (realm config, MFA mode, user profile), query-realms and
     * view-authorization.
     */
    public static final List<String> REQUIRED_REALM_MANAGEMENT_ROLES = List.of(
            "view-realm", "view-users", "view-clients", "view-events", "view-authorization",
            "query-users", "query-clients", "query-groups", "query-realms",
            "manage-users", "manage-clients", "manage-authorization", "manage-realm");

    /**
     * @param reconcile     RECONCILE_SERVICE_ACCOUNT_ROLES
     * @param adminRealm    realm of the bootstrap admin (master)
     * @param adminUsername KEYCLOAK_ADMIN_USERNAME
     * @param adminPassword KEYCLOAK_ADMIN_PASSWORD (empty → skip)
     */
    @ConfigurationProperties(prefix = "sso.service-account-roles")
    public record Properties(@DefaultValue("true") boolean reconcile, @DefaultValue("master") String adminRealm,
                             @DefaultValue("admin") String adminUsername, @DefaultValue("") String adminPassword) {
        @Override
        public String toString() {
            return "Properties[reconcile=" + reconcile + ", adminRealm=" + adminRealm + ", adminUsername="
                    + adminUsername + "]";
        }
    }

    /** What a run did. {@code skipped} carries the reason when nothing could be checked. */
    public record Result(List<String> granted, List<String> alreadyHeld, List<String> unknown, String skipped) {
    }

    private final Properties props;
    private final SsoProperties.Keycloak kc;

    public ServiceAccountRoleReconciler(Properties props, SsoProperties sso) {
        this.props = props;
        this.kc = sso.keycloak();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.reconcile()) {
            log.info("sa-roles: reconciliation disabled via RECONCILE_SERVICE_ACCOUNT_ROLES");
            return;
        }
        Thread t = new Thread(this::reconcileQuietly, "sa-roles-reconcile");
        t.setDaemon(true);
        t.start();
    }

    /** Never throws. */
    public Result reconcileQuietly() {
        try {
            return reconcile();
        } catch (RuntimeException e) {
            log.warn("sa-roles: reconciliation skipped — {}. The wrapper still runs; if admin/authz operations 403, "
                    + "grant the service account its realm-management roles manually.", e.getMessage());
            return new Result(List.of(), List.of(), List.of(), String.valueOf(e.getMessage()));
        }
    }

    /** One pass with the master admin; may throw on infrastructure errors. */
    public Result reconcile() {
        if (props.adminPassword() == null || props.adminPassword().isBlank()) {
            log.info("sa-roles: KEYCLOAK_ADMIN_PASSWORD not set — skipping service-account role reconciliation");
            return new Result(List.of(), List.of(), List.of(), "no admin credentials");
        }
        try (Keycloak admin = KeycloakBuilder.builder().serverUrl(kc.serverUrl()).realm(props.adminRealm())
                .grantType(OAuth2Constants.PASSWORD).clientId("admin-cli")
                .username(props.adminUsername()).password(props.adminPassword()).build()) {
            return reconcile(admin.realm(kc.realm()), kc.clientId());
        }
    }

    /** The reconciliation against an already-authorised realm resource. */
    public static Result reconcile(RealmResource realm, String clientId) {
        ClientRepresentation rm = realm.clients().findByClientId("realm-management").stream().findFirst().orElse(null);
        if (rm == null) {
            log.warn("sa-roles: 'realm-management' client not found — skipping");
            return new Result(List.of(), List.of(), List.of(), "realm-management not found");
        }
        ClientRepresentation wrapper = realm.clients().findByClientId(clientId).stream()
                .filter(c -> clientId.equals(c.getClientId())).findFirst().orElse(null);
        if (wrapper == null) {
            log.warn("sa-roles: client '{}' not found — skipping", clientId);
            return new Result(List.of(), List.of(), List.of(), "client not found");
        }
        UserRepresentation sa = realm.clients().get(wrapper.getId()).getServiceAccountUser();
        Set<String> held = realm.users().get(sa.getId()).roles().clientLevel(rm.getId()).listAll().stream()
                .map(RoleRepresentation::getName).collect(Collectors.toSet());
        List<String> already = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<RoleRepresentation> toAdd = new ArrayList<>();
        for (String name : REQUIRED_REALM_MANAGEMENT_ROLES) {
            if (held.contains(name)) {
                already.add(name);
                continue;
            }
            try {
                toAdd.add(realm.clients().get(rm.getId()).roles().get(name).toRepresentation());
            } catch (NotFoundException e) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            log.warn("sa-roles: realm-management has no role(s) {} on this version — skipped", unknown);
        }
        if (!toAdd.isEmpty()) {
            realm.users().get(sa.getId()).roles().clientLevel(rm.getId()).add(toAdd);
            log.info("sa-roles: granted {} to the service account of '{}' (self-healed realm drift)",
                    toAdd.stream().map(RoleRepresentation::getName).toList(), clientId);
        } else {
            log.info("sa-roles: the service account of '{}' already holds all {} required roles", clientId,
                    REQUIRED_REALM_MANAGEMENT_ROLES.size());
        }
        return new Result(toAdd.stream().map(RoleRepresentation::getName).toList(), already, unknown, null);
    }
}
