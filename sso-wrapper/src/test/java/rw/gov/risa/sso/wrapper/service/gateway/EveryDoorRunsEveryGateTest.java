package rw.gov.risa.sso.wrapper.service.gateway;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.gateway.TokenGates.Door;

/**
 * Invariant §7.3: no user token leaves without the access, join-key and MFA gates — including on refresh.
 * Every door × every gate: make that one gate refuse and the door must answer its 403 code; with all gates
 * open, every applicable gate must have been consulted for that door's grant type.
 *
 * <p>The one deliberate exception is the password-grant MFA gate on a Keycloak browser code
 * ({@link Door#TOKEN_BROWSER_CODE}): that login already ran Keycloak's own TOTP step-up, so the gate is
 * NOT consulted there (spec §5.1, "password grants only") — asserted explicitly below.
 */
class EveryDoorRunsEveryGateTest extends GatewayMvcTestBase {

    enum Gate {
        ACCESS("access_denied"), JOIN_KEY("join_key_missing"), MFA("mfa_required");

        final String code;

        Gate(String code) {
            this.code = code;
        }
    }

    static Stream<Arguments> doorsAndGates() {
        List<Arguments> out = new ArrayList<>();
        for (Door d : Door.values()) {
            for (Gate g : Gate.values()) {
                out.add(Arguments.of(d, g));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} refuses when {1} refuses")
    @MethodSource("doorsAndGates")
    void eachGateCanStopEachDoor(Door door, Gate gate) throws Exception {
        refuse(gate);
        ResultActions r = knock(door);
        if (gate == Gate.MFA && !door.passwordMfa()) {
            r.andExpect(status().isOk());
            verify(mfa, never()).enforcePasswordGrant(any(), any(), any(), any(), any());
            return;
        }
        r.andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value(gate.code))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.authorization_code").doesNotExist());
    }

    @ParameterizedTest(name = "{0} consults every gate")
    @EnumSource(Door.class)
    void everyDoorConsultsEveryGate(Door door) throws Exception {
        knock(door).andExpect(status().isOk());
        verify(access, times(1)).enforceLogin(eq(USER), eq(APP), any(), eq(door.grantType()));
        verify(joinKeys, times(1)).enforceLogin(eq(USER), eq(APP), any(), eq(door.grantType()));
        if (door.passwordMfa()) {
            verify(mfa, times(1)).enforcePasswordGrant(any(), eq(USER), eq(APP), any(), eq(door.path()));
        } else {
            verify(mfa, never()).enforcePasswordGrant(any(), any(), any(), any(), any());
        }
    }

    @ParameterizedTest(name = "{0}: a token without preferred_username is 401")
    @EnumSource(Door.class)
    void noPreferredUsernameIs401AtEveryDoor(Door door) throws Exception {
        knock(door, token().claim("preferred_username", null).sign())
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_token"));
        verify(access, never()).enforceLogin(any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}: a token that fails verification is 401")
    @EnumSource(Door.class)
    void unverifiableTokenIs401AtEveryDoor(Door door) throws Exception {
        knock(door, token().signedByAnotherKey().sign()).andExpect(status().isUnauthorized());
        verify(access, never()).enforceLogin(any(), any(), any(), any());
    }

    // ---- fixture ---------------------------------------------------------------------------

    private void refuse(Gate gate) {
        switch (gate) {
            case ACCESS -> doThrow(ApiException.forbidden("access_denied", "Access denied."))
                    .when(access).enforceLogin(any(), any(), any(), any());
            case JOIN_KEY -> doThrow(new ApiException(HttpStatus.FORBIDDEN, "join_key_missing", "No NID on record.",
                    Map.of("join_column", "nid", "claim", "join_key")))
                    .when(joinKeys).enforceLogin(any(), any(), any(), any());
            case MFA -> doThrow(new ApiException(HttpStatus.FORBIDDEN, "mfa_required", "TOTP required."))
                    .when(mfa).enforcePasswordGrant(any(), any(), any(), any(), any());
        }
    }

    private ResultActions knock(Door door) throws Exception {
        return knock(door, userToken());
    }

    private ResultActions knock(Door door, String accessToken) throws Exception {
        return switch (door) {
            case LOGIN -> {
                keycloakIssues(accessToken);
                yield mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"username":"%s","password":"pw","client_id":"%s","client_secret":"%s"}"""
                        .formatted(USER, APP, APP_SECRET)));
            }
            case AUTHORIZE_INTERNAL -> mvc.perform(post("/oauth2/authorize-internal")
                    .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .content("""
                            {"client_id":"%s","redirect_uri":"%s","refresh_token":"r","id_token":"i",
                             "code_challenge":"%s","code_challenge_method":"S256"}""".formatted(APP, REDIRECT, CHALLENGE)));
            case TOKEN_WRAPPER_CODE -> mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET)).param("grant_type", "authorization_code")
                    .param("code", wrapperCode(accessToken)).param("redirect_uri", REDIRECT)
                    .param("code_verifier", VERIFIER));
            case TOKEN_BROWSER_CODE -> {
                keycloakIssues(accessToken);
                yield mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(APP, APP_SECRET))
                        .param("grant_type", "authorization_code").param("code", "kc-code")
                        .param("redirect_uri", REDIRECT).param("code_verifier", VERIFIER));
            }
            case TOKEN_REFRESH -> {
                keycloakIssues(accessToken);
                yield mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token").param("client_id", APP)
                        .param("client_secret", APP_SECRET).param("refresh_token", "kc-refresh-1"));
            }
        };
    }
}
