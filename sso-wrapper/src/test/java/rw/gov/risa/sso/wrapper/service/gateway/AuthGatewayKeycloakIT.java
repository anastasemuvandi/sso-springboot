package rw.gov.risa.sso.wrapper.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.service.clientaccess.ClientAccessService;
import rw.gov.risa.sso.wrapper.support.KeycloakIT;
import rw.gov.risa.sso.wrapper.support.RedisIT;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/**
 * The gateway against a REAL Keycloak 26.2 and a REAL Redis: password login through the gates, the
 * password-flow code bridge with PKCE, single use of the code, refresh re-gated after revocation, and the
 * per-client access refusal for an unassigned person.
 */
@Tag("it")
@WrapperTest
class AuthGatewayKeycloakIT {

    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";
    static final String REDIRECT = "http://localhost:9999/callback";
    static final ObjectMapper JSON = new ObjectMapper();

    static String client;
    static String secret;

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry r) {
        KeycloakIT.register(r);
        r.add("spring.data.redis.host", RedisIT::host);
        r.add("spring.data.redis.port", RedisIT::port);
    }

    @BeforeAll
    static void client() {
        KeycloakIT.container();
        client = KeycloakIT.unique("minagri-gw");
        String uuid = KeycloakIT.createClient(client);
        ClientRepresentation rep = KeycloakIT.realm().clients().get(uuid).toRepresentation();
        rep.setRedirectUris(List.of(REDIRECT));
        KeycloakIT.realm().clients().get(uuid).update(rep);
        secret = client + "-secret";
    }

    @Autowired MockMvc mvc;
    @Autowired ClientAccessService access;
    @MockitoBean AuditEmitter audit;

    String user;
    String userId;

    @BeforeEach
    void person() {
        user = KeycloakIT.unique("gw-user");
        userId = KeycloakIT.createUser(user, "minagri");
    }

    MvcResult login(String clientSecret) throws Exception {
        String s = clientSecret == null ? "" : ",\"client_secret\":\"" + clientSecret + "\"";
        return mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(
                "{\"username\":\"" + user + "\",\"password\":\"" + KeycloakIT.PASSWORD + "\",\"client_id\":\""
                        + client + "\"" + s + "}")).andReturn();
    }

    static JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    String basic() {
        return "Basic " + Base64.getEncoder().encodeToString((client + ":" + secret).getBytes());
    }

    @Test
    void unassignedPersonIsRefusedAtLogin() throws Exception {
        MvcResult r = login(secret);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(json(r).get("error").asText()).isEqualTo("access_denied");
    }

    @Test
    void confidentialClientWithoutSecretIsRefused() throws Exception {
        access.admitUser(access.serviceAccount(), client, userId);
        MvcResult r = login(null);
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(r).get("error").asText()).isEqualTo("invalid_client");
    }

    @Test
    void passwordLoginCodeExchangeRefreshAndRevocation() throws Exception {
        access.admitUser(access.serviceAccount(), client, userId);

        MvcResult loggedIn = login(secret);
        assertThat(loggedIn.getResponse().getStatus()).as(loggedIn.getResponse().getContentAsString()).isEqualTo(200);
        JsonNode tokens = json(loggedIn);
        assertThat(tokens.get("token_type").asText()).isEqualTo("bearer");
        assertThat(tokens.get("redirect_url").asText()).isEqualTo(REDIRECT);
        String access1 = tokens.get("access_token").asText();

        mvc.perform(get("/auth/verify").header(HttpHeaders.AUTHORIZATION, "Bearer " + access1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));

        MvcResult issued = mvc.perform(post("/oauth2/authorize-internal").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access1)
                        .content("""
                                {"client_id":"%s","redirect_uri":"%s","refresh_token":"%s","id_token":"%s",
                                 "code_challenge":"%s","code_challenge_method":"S256"}""".formatted(client, REDIRECT,
                                tokens.get("refresh_token").asText(), tokens.get("id_token").asText(), CHALLENGE)))
                .andExpect(status().isOk()).andReturn();
        String code = json(issued).get("authorization_code").asText();

        mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic()).param("grant_type", "authorization_code")
                        .param("code", code).param("redirect_uri", REDIRECT).param("code_verifier", VERIFIER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.access_token").value(access1));
        // single use (Redis GETDEL), and Keycloak never knew the code either
        mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic()).param("grant_type", "authorization_code")
                        .param("code", code).param("redirect_uri", REDIRECT).param("code_verifier", VERIFIER))
                .andExpect(status().isBadRequest());

        MvcResult refreshed = mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token").param("client_id", client).param("client_secret", secret)
                        .param("refresh_token", tokens.get("refresh_token").asText()))
                .andExpect(status().isOk()).andReturn();
        String refresh2 = json(refreshed).get("refresh_token").asText();

        // Revoke the person's access: the next refresh must not hand out a token.
        access.revokeUserIds(access.serviceAccount(), client, List.of(userId));
        mvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token").param("client_id", client).param("client_secret", secret)
                        .param("refresh_token", refresh2))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("access_denied"))
                .andExpect(jsonPath("$.access_token").doesNotExist());
    }

    @Test
    void logoutEndsTheSessionSoVerifyFailsClosedAfterwards() throws Exception {
        access.admitUser(access.serviceAccount(), client, userId);
        String token = json(login(secret)).get("access_token").asText();
        mvc.perform(post("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/auth/verify").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false));
    }

    @Test
    void codeStoreIsAtomicOnRealRedis() {
        RedisAuthCodeStore store = new RedisAuthCodeStore(RedisIT.template(), JSON,
                new GatewayProperties(false, java.time.Duration.ofSeconds(300), java.time.Duration.ofSeconds(5), false));
        String code = store.issue(new AuthCodeStore.StoredCode(java.util.Map.of("access_token", "a"), client, REDIRECT,
                CHALLENGE, "S256", user));
        assertThat(RedisIT.template().getExpire(RedisAuthCodeStore.key(code))).isBetween(1L, 300L);
        assertThat(RedisIT.template().hasKey(RedisAuthCodeStore.PREFIX + code)).isFalse(); // only the hash is a key
        assertThat(store.consume(code)).isPresent();
        assertThat(store.consume(code)).isEmpty();
    }
}
