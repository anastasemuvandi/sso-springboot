package rw.gov.risa.sso.wrapper.service.gateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.gateway.TokenGates.Door;

/**
 * {@code /auth/*} (port of {@code api/auth.py} + {@code services/auth_service.py}): password login through
 * the three gates, logout, the fail-closed verify, and the enriched userinfo.
 */
@Service
public class AuthGatewayService {

    private static final Logger log = LoggerFactory.getLogger(AuthGatewayService.class);

    public static final String TOKEN_TYPE = "bearer";
    public static final String MSG_LOGOUT_SUCCESS = "Successfully logged out";
    public static final String MSG_INVALID_CREDENTIALS = "Invalid credentials";

    /** Keycloak's own clients, hidden from {@code clients} in userinfo (reference list). */
    static final Set<String> INTERNAL_CLIENTS = Set.of("account", "account-console", "admin-cli", "broker",
            "realm-management", "security-admin-console");

    private final ClientAuthenticator clients;
    private final KeycloakOidc oidc;
    private final TokenGates gates;
    private final GatewayDirectory directory;
    private final AuditEmitter audit;

    public AuthGatewayService(ClientAuthenticator clients, KeycloakOidc oidc, TokenGates gates,
                              GatewayDirectory directory, AuditEmitter audit) {
        this.clients = clients;
        this.oidc = oidc;
        this.gates = gates;
        this.directory = directory;
        this.audit = audit;
    }

    /** {@code POST /auth/login} answer ({@code TokenResponse}). */
    public record LoginResult(String accessToken, String refreshToken, String idToken, String tokenType,
                              String redirectUrl) {
    }

    // ---- login -----------------------------------------------------------------------------

    /**
     * Password login for {@code client}: the client authenticates (a confidential client presents its own
     * secret), Keycloak checks the password, and the minted token goes through every gate before it is
     * returned. A refused token's session is ended at once.
     */
    public LoginResult login(String username, String password, ClientAuthenticator.Presented presented,
                             String requestIp) {
        String clientId = presented.clientId();
        boolean gateAudited = false;
        try {
            RegisteredClient client = clients.authenticate(presented, false);
            KeycloakOidc.Answer a = oidc.passwordGrant(client.clientId(), presented.secret(), username, password);
            if (!a.ok() || a.str("access_token") == null) {
                throw loginRefusal(a);
            }
            try {
                gates.pass(Door.LOGIN, a.str("access_token"), client.clientId(), requestIp);
            } catch (ApiException e) {
                gateAudited = e.status() == HttpStatus.FORBIDDEN;
                oidc.revoke(client.clientId(), presented.secret(), a.str("refresh_token"));
                throw e;
            }
            audit.emit(AuditEvent.of(AuditEventType.LOGIN_SUCCESS).actor(username).clientId(client.clientId())
                    .requestIp(requestIp).put("grant_type", "password"));
            return new LoginResult(a.str("access_token"), a.str("refresh_token"), a.str("id_token"), TOKEN_TYPE,
                    client.mainRedirectUrl());
        } catch (ApiException e) {
            if (!gateAudited) {
                audit.emit(AuditEvent.of(AuditEventType.LOGIN_FAILURE).failure().actor(username).clientId(clientId)
                        .requestIp(requestIp).put("error", e.error()).put("status", e.status().value()));
            }
            throw e;
        }
    }

    private static ApiException loginRefusal(KeycloakOidc.Answer a) {
        String error = a.error();
        if ("invalid_grant".equals(error) || a.status() == 401 && !"invalid_client".equals(error)) {
            return new ApiException(HttpStatus.UNAUTHORIZED, GatewayErrors.INVALID_CREDENTIALS, MSG_INVALID_CREDENTIALS);
        }
        if ("unauthorized_client".equals(error) || "invalid_client".equals(error)) {
            return new ApiException(HttpStatus.BAD_REQUEST, GatewayErrors.UNAUTHORIZED_CLIENT,
                    a.errorDescription() == null ? "This client may not use password sign-in." : a.errorDescription());
        }
        if (a.status() >= 500) {
            return GatewayErrors.upstream("Identity provider error");
        }
        return new ApiException(HttpStatus.UNAUTHORIZED, GatewayErrors.INVALID_CREDENTIALS, MSG_INVALID_CREDENTIALS);
    }

    // ---- logout ----------------------------------------------------------------------------

    /**
     * {@code POST /auth/logout}: end the SSO session behind the caller's own (verified) token. The reference
     * looked up the client's secret to call Keycloak's logout; this ends the session through the Admin API
     * as the wrapper's service account instead, so no secret is ever read on the caller's behalf.
     */
    public Map<String, String> logout(CallerPrincipal caller, String requestIp) {
        if (caller == null) {
            throw ApiException.unauthorized("Bearer token required");
        }
        try {
            if (caller.sessionId() == null || caller.sessionId().isBlank()) {
                throw GatewayErrors.invalidRequest("The token carries no session to end.");
            }
            directory.endSession(caller.sessionId());
        } catch (ApiException e) {
            audit.emit(AuditEvent.of(AuditEventType.LOGOUT).failure().actor(caller.username()).clientId(caller.azp())
                    .session(caller.sessionId()).requestIp(requestIp).put("error", e.error()));
            throw e;
        }
        audit.emit(AuditEvent.of(AuditEventType.LOGOUT).actor(caller.username()).clientId(caller.azp())
                .session(caller.sessionId()).requestIp(requestIp));
        return Map.of("message", MSG_LOGOUT_SUCCESS);
    }

    // ---- verify ----------------------------------------------------------------------------

    /**
     * {@code GET /auth/verify}: the bearer has already been verified by the resource server (an invalid one
     * never reaches here: 401). The session behind it must still be live in Keycloak. FAILS CLOSED: any
     * error reading the session answers {@code false}.
     */
    public boolean verify(CallerPrincipal caller) {
        if (caller == null) {
            return false;
        }
        String sid = caller.sessionId();
        if (sid == null || sid.isBlank()) {
            // A token without a session (e.g. a lightweight service token) is only as good as its signature.
            return caller.userId() != null;
        }
        try {
            return directory.sessionActive(caller.userId(), sid);
        } catch (RuntimeException e) {
            log.warn("gateway: /auth/verify could not read the session; answering invalid ({})", e.getMessage());
            return false;
        }
    }

    // ---- userinfo --------------------------------------------------------------------------

    /** One client in the user context. */
    public record AuthClientInfo(@JsonProperty("clientId") String clientId, String name, List<String> roles) {
    }

    /** One agency (group). */
    public record AuthAgencyInfo(String id, String name) {
    }

    /** {@code GET /auth/userinfo} ({@code UserInfo} in the reference). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UserInfo(String sub, String username, String email, String firstName, String lastName,
                           Boolean emailVerified, List<String> realmRoles, List<String> clientRoles,
                           List<AuthAgencyInfo> agencies, List<AuthClientInfo> clients, AuthClientInfo currentClient,
                           String clientId, List<String> allowedOrigins, String scope, String sessionState,
                           String sessionId) {
    }

    public UserInfo userInfo(CallerPrincipal caller) {
        Map<String, Object> claims = caller.claims();
        List<AuthAgencyInfo> agencies = new ArrayList<>();
        for (GroupRepresentation g : directory.userGroups(caller.userId())) {
            agencies.add(new AuthAgencyInfo(g.getId(), g.getName()));
        }
        List<String> realmRoles = new ArrayList<>();
        for (String r : caller.realmRoles()) {
            if (!r.startsWith("default-roles-")) {
                realmRoles.add(r);
            }
        }
        String current = caller.azp();
        List<String> currentRoles = new ArrayList<>(caller.clientRoles(current));
        List<AuthClientInfo> clientList = new ArrayList<>();
        AuthClientInfo currentInfo = null;
        for (ClientRepresentation c : directory.clients()) {
            String id = c.getClientId();
            if (id == null || c.getId() == null) {
                continue;
            }
            String name = c.getName() == null ? id : c.getName();
            if (id.equals(current)) {
                currentInfo = new AuthClientInfo(id, name, currentRoles);
            }
            if (INTERNAL_CLIENTS.contains(id)) {
                continue;
            }
            Set<String> roles = caller.clientRoles(id);
            if (!roles.isEmpty()) {
                clientList.add(new AuthClientInfo(id, name, new ArrayList<>(roles)));
            }
        }
        return new UserInfo(caller.userId(), caller.username(), caller.email(), str(claims.get("given_name")),
                str(claims.get("family_name")), Boolean.TRUE.equals(claims.get("email_verified")), realmRoles,
                currentRoles, agencies, clientList, currentInfo, current, strings(claims.get("allowed-origins")),
                str(claims.get("scope")), str(claims.get("session_state")), str(claims.get("sid")));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof Collection<?> c) {
            c.forEach(v -> out.add(String.valueOf(v)));
        }
        return out;
    }
}
