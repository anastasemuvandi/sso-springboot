package rw.gov.risa.sso.mfa;

import org.keycloak.credential.CredentialModel;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.AuthDetails;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.mfa.policy.MfaPolicy;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forwards the MFA-relevant Keycloak events to the audit service.
 *
 * <p>Fire-and-forget on the JDK HTTP client's own executor: the POST never runs
 * on the login thread, and an audit service that is down or slow costs a log
 * line, never a login.
 *
 * <p>Two admin-side observations, both about changes made outside the wrapper
 * (which audits its own, with the actor):
 * <ul>
 *   <li>any realm-role mapping that names {@code mfa-exempt};</li>
 *   <li>a realm update that changed the effective TOTP mode — detected by
 *       comparing with the value this node last saw. The first realm update a
 *       node sees after a restart only primes that memory.</li>
 * </ul>
 */
public class AuditEventListener implements EventListenerProvider {

    static final String EXEMPT_ROLE = MfaConstants.EXEMPT_ROLE;

    private final KeycloakSession session;
    private final AuditEventListenerFactory forwarder;
    private final Map<String, String> lastSwitch;

    AuditEventListener(KeycloakSession session, AuditEventListenerFactory forwarder, Map<String, String> lastSwitch) {
        this.session = session;
        this.forwarder = forwarder;
        this.lastSwitch = lastSwitch;
    }

    @Override
    public void onEvent(Event event) {
        String type = event.getType() == null ? null : event.getType().name();
        if (type == null) {
            return;
        }
        Map<String, String> d = event.getDetails() == null ? Map.of() : event.getDetails();
        String selectedType = "LOGIN_ERROR".equals(type) ? selectedCredentialType(event, d) : null;
        String auditType = AuditMapping.userEvent(type, d, selectedType);
        if (auditType == null) {
            return;
        }
        String username = d.get("username");
        if (username == null) {
            // A failed OTP carries the user id but not the username.
            username = usernameOf(event.getRealmId(), event.getUserId());
        }
        post(AuditPayloads.userEvent(auditType, type, username, event.getUserId(), event.getSessionId(),
                event.getError(), selectedType, d, event.getClientId(), realmName(event.getRealmId()),
                event.getIpAddress()));
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        String adminType = AuditMapping.adminEvent(
                event.getResourceType() == null ? null : event.getResourceType().name(),
                event.getOperationType() == null ? null : event.getOperationType().name(),
                event.getResourcePath());
        if (adminType != null) {
            String[] parts = event.getResourcePath().split("/");
            String userId = parts[1];
            Map<String, Object> details = adminDetails(event);
            details.put("resource_path", event.getResourcePath());
            details.put("keycloak_user_id", userId);
            details.put("credential_id", parts[3]);
            // The credential is already gone, so its type cannot be read back;
            // the audit row says a credential was removed and by whom.
            post(adminPayload(adminType, event, usernameOf(event.getRealmId(), userId), details));
            return;
        }
        if (AuditMapping.namesExemptRole(
                event.getResourceType() == null ? null : event.getResourceType().name(),
                event.getRepresentation())) {
            Map<String, Object> details = adminDetails(event);
            details.put("operation", String.valueOf(event.getOperationType()));
            details.put("resource_path", event.getResourcePath());
            post(adminPayload(AuditPayloads.EXEMPT_ROLE_MAPPING, event, event.getResourcePath(), details));
            return;
        }
        if (event.getResourceType() == ResourceType.REALM) {
            RealmModel realm = session.realms().getRealm(event.getRealmId());
            if (realm == null) {
                return;
            }
            // The EFFECTIVE mode (mfa_totp_mode, else the legacy switch), so a
            // write that changes the stored value but not the behaviour — or a
            // branding edit that re-PUTs the whole realm — reports nothing.
            String now = MfaPolicy.mode(
                    realm.getAttribute(MfaConstants.MODE_ATTRIBUTE),
                    realm.getAttribute(MfaConstants.LEGACY_ATTRIBUTE));
            String before = lastSwitch.put(realm.getId(), now);
            if (!AuditMapping.switchChanged(before, now)) {
                return;
            }
            Map<String, Object> details = adminDetails(event);
            details.put("old_value", before);
            details.put("new_value", now);
            details.put("timestamp", Instant.ofEpochMilli(event.getTime()).toString());
            details.put("note", AuditPayloads.SWITCH_OBSERVED_NOTE);
            post(adminPayload(AuditPayloads.SWITCH_OBSERVED, event, MfaConstants.MODE_ATTRIBUTE, details));
        }
    }

    /** Type of the credential the event's {@code selected_credential_id} names, or null. */
    private String selectedCredentialType(Event event, Map<String, String> d) {
        String credentialId = d.get("selected_credential_id");
        if (credentialId == null || event.getUserId() == null) {
            return null;
        }
        try {
            RealmModel realm = session.realms().getRealm(event.getRealmId());
            UserModel user = realm == null ? null : session.users().getUserById(realm, event.getUserId());
            CredentialModel credential = user == null ? null
                    : user.credentialManager().getStoredCredentialById(credentialId);
            return credential == null ? null : credential.getType();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String usernameOf(String realmId, String userId) {
        if (userId == null) {
            return null;
        }
        try {
            RealmModel realm = session.realms().getRealm(realmId);
            UserModel user = realm == null ? null : session.users().getUserById(realm, userId);
            return user == null ? userId : user.getUsername();
        } catch (RuntimeException e) {
            return userId;
        }
    }

    private static Map<String, Object> adminDetails(AdminEvent event) {
        AuthDetails a = event.getAuthDetails();
        return AuditPayloads.adminDetails(a == null ? null : a.getUserId(), a == null ? null : a.getClientId(),
                a == null ? null : a.getRealmId(), a != null);
    }

    private Map<String, Object> adminPayload(String type, AdminEvent event, String target, Map<String, Object> details) {
        AuthDetails a = event.getAuthDetails();
        return AuditPayloads.admin(type, a == null ? null : a.getUserId(), a == null ? null : a.getClientId(),
                realmName(event.getRealmId()), target, a == null ? null : a.getIpAddress(), details, "WARNING");
    }

    private String realmName(String realmId) {
        try {
            RealmModel realm = realmId == null ? null : session.realms().getRealm(realmId);
            return realm == null ? realmId : realm.getName();
        } catch (RuntimeException e) {
            return realmId;
        }
    }

    private void post(Map<String, Object> payload) {
        forwarder.forward(payload);
    }

    @Override
    public void close() {
    }

    static Map<String, String> newSwitchCache() {
        return new ConcurrentHashMap<>();
    }
}
