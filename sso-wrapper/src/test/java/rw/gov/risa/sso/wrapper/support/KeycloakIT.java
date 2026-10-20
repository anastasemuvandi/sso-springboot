package rw.gov.risa.sso.wrapper.support;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.CreatedResponseUtil;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPConfig;

import com.fasterxml.jackson.databind.ObjectMapper;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.serviceaccount.ServiceAccountRoleReconciler;

/**
 * The team's shared Keycloak 26.2 Testcontainer (features {@code organization,admin-fine-grained-authz:v2}).
 * ONE container per test JVM, started lazily on first use and seeded once:
 *
 * <ul>
 *   <li>realm {@value #REALM} imported from {@code configs/realms/government-internal-realm.json}
 *       (stripped: {@code loginTheme}, legacy UPPERCASE {@code MINAGRI}/{@code MINISANTE} groups;
 *       user profile switched to {@code unmanagedAttributePolicy=ENABLED} so custom attributes stick;
 *       realm attribute {@code mfa_totp_mode} reset to {@code off});</li>
 *   <li>{@code sso-client} (secret {@value #SSO_CLIENT_SECRET}) with a service account holding
 *       {@link ServiceAccountRoleReconciler#REQUIRED_REALM_MANAGEMENT_ROLES}; {@code sso-admin-ui};
 *       {@value #TEST_CLIENT} (public, direct access grants, {@code institution} claim mapper) for user tokens;</li>
 *   <li>per slug in {@link #SLUGS}: groups {@code /<slug>} (+ {@code /minagri/planning}), realm roles
 *       {@code inst:<slug>:admin|hr|staff|auditor}, client {@code <slug>-<app>} (minagri-kubaka-web,
 *       minisante-hmis), users {@code <slug>.admin} (inst:&lt;slug&gt;:admin), {@code <slug>.hr},
 *       {@code <slug>.staff} (in {@code /<slug>}), {@code minagri.planner} (in {@code /minagri/planning});
 *       all with {@code institution=<slug>}, email {@code <username>@<slug>.gov.rw}, password {@value #PASSWORD};</li>
 *   <li>{@code platform.super} with realm role {@code super_admin}.</li>
 * </ul>
 *
 * Tests that use it must be tagged {@code @Tag("it")} (run with {@code mvn -Pit verify}). Create your own
 * objects with {@link #unique(String)} names — the container is shared across test classes.
 */
public final class KeycloakIT {

    public static final String IMAGE = "quay.io/keycloak/keycloak:26.2";
    public static final String REALM = "government-internal";
    public static final String PASSWORD = "Gorsso-It-2026";
    public static final String SSO_CLIENT_SECRET = "it-sso-client-secret";
    public static final String TEST_CLIENT = "gorsso-it-cli";
    public static final List<String> SLUGS = List.of("minagri", "minisante");
    public static final Map<String, String> APPS = Map.of("minagri", "minagri-kubaka-web", "minisante", "minisante-hmis");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static volatile KeycloakContainer container;
    private static volatile Keycloak admin;

    private KeycloakIT() {
    }

    // ---- lifecycle -------------------------------------------------------------------------

    /** The running, seeded container. */
    public static synchronized KeycloakContainer container() {
        if (container == null) {
            KeycloakContainer c = new KeycloakContainer(IMAGE)
                    .withFeaturesEnabled("organization", "admin-fine-grained-authz:v2")
                    .withStartupTimeout(Duration.ofMinutes(5));
            c.start();
            container = c;
            admin = c.getKeycloakAdminClient();
            seed();
        }
        return container;
    }

    /** Base URL ({@code http://host:port}). */
    public static String serverUrl() {
        String url = container().getAuthServerUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Master-realm admin client (shared — do not close). */
    public static Keycloak masterAdmin() {
        container();
        return admin;
    }

    /** {@value #REALM} as the master admin. */
    public static RealmResource realm() {
        return masterAdmin().realm(REALM);
    }

    /** Core settings pointing at the container ({@code sso-client} + secret, default exempt list). */
    public static SsoProperties ssoProperties() {
        String url = serverUrl();
        return new SsoProperties(true, "test", "http://localhost:8000",
                new SsoProperties.Keycloak(url, url, url, REALM, "sso-client", SSO_CLIENT_SECRET,
                        List.of("sso-client", "sso-admin-ui", "account", "account-console", "admin-cli", "broker",
                                "realm-management", "security-admin-console")),
                new SsoProperties.Cors(List.of()));
    }

    public static KeycloakAdminFactory adminFactory() {
        return new KeycloakAdminFactory(ssoProperties());
    }

    public static KeycloakExecutors executors() {
        return new KeycloakExecutors(adminFactory());
    }

    /**
     * For {@code @SpringBootTest}: {@code @DynamicPropertySource static void kc(DynamicPropertyRegistry r)
     * { KeycloakIT.register(r); }}.
     */
    public static void register(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("sso.keycloak.server-url", KeycloakIT::serverUrl);
        r.add("sso.keycloak.public-url", KeycloakIT::serverUrl);
        r.add("sso.keycloak.local-issuer", KeycloakIT::serverUrl);
        r.add("sso.keycloak.realm", () -> REALM);
        r.add("sso.keycloak.client-id", () -> "sso-client");
        r.add("sso.keycloak.client-secret", () -> SSO_CLIENT_SECRET);
    }

    // ---- tokens ----------------------------------------------------------------------------

    /** A real user access token (password grant via {@value #TEST_CLIENT}), password {@value #PASSWORD}. */
    public static String token(String username) {
        return token(username, PASSWORD);
    }

    public static String token(String username, String password) {
        Map<String, Object> body = tokenResponse(TEST_CLIENT, username, password);
        Object t = body.get("access_token");
        if (t == null) {
            throw new IllegalStateException("no token for " + username + ": " + body);
        }
        return String.valueOf(t);
    }

    /** The raw token endpoint answer (also for asserting refusals, e.g. {@code error}). */
    public static Map<String, Object> tokenResponse(String clientId, String username, String password) {
        String form = "grant_type=password&client_id=" + enc(clientId) + "&username=" + enc(username)
                + "&password=" + enc(password) + "&scope=openid";
        HttpRequest req = HttpRequest.newBuilder(URI.create(serverUrl() + "/realms/" + REALM
                        + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        try {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            @SuppressWarnings("unchecked")
            Map<String, Object> m = JSON.readValue(res.body(), Map.class);
            return m;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The {@link CallerPrincipal} the wrapper would build from {@code username}'s real token. */
    public static CallerPrincipal caller(String username) {
        String token = token(username);
        Map<String, Object> claims = claims(token);
        Set<String> realmRoles = new LinkedHashSet<>();
        if (claims.get("realm_access") instanceof Map<?, ?> ra && ra.get("roles") instanceof List<?> l) {
            l.forEach(r -> realmRoles.add(String.valueOf(r)));
        }
        Map<String, Set<String>> clientRoles = new HashMap<>();
        if (claims.get("resource_access") instanceof Map<?, ?> res) {
            res.forEach((k, v) -> {
                Set<String> roles = new LinkedHashSet<>();
                if (v instanceof Map<?, ?> m && m.get("roles") instanceof List<?> l) {
                    l.forEach(r -> roles.add(String.valueOf(r)));
                }
                clientRoles.put(String.valueOf(k), roles);
            });
        }
        Object inst = claims.get("institution");
        return new CallerPrincipal(str(claims.get("sub")), str(claims.get("preferred_username")), str(claims.get("email")),
                str(claims.get("name")), str(claims.get("azp")), realmRoles, clientRoles,
                inst == null ? null : String.valueOf(inst).toLowerCase(Locale.ROOT), str(claims.get("sid")), token,
                claims);
    }

    /** Decoded JWT payload (no verification). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> claims(String jwt) {
        try {
            String payload = jwt.split("\\.")[1];
            return JSON.readValue(Base64.getUrlDecoder().decode(payload), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- fixtures --------------------------------------------------------------------------

    /** {@code prefix-<n>-<millis>} lower-case, unique across the JVM. */
    public static String unique(String prefix) {
        return (prefix + "-" + COUNTER.incrementAndGet() + "-" + (System.currentTimeMillis() % 1_000_000))
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Create an enabled user with password {@value #PASSWORD}, email {@code <username>@it.gov.rw}, attribute
     * {@code institution} (when non-null), member of {@code /<institution>} (when it exists) and the given
     * realm roles. Returns the id.
     */
    public static String createUser(String username, String institution, String... realmRoles) {
        return createUser(username, username + "@it.gov.rw", institution, Map.of(), List.of(realmRoles));
    }

    public static String createUser(String username, String email, String institution,
                                    Map<String, List<String>> attributes, List<String> realmRoles) {
        RealmResource realm = realm();
        UserRepresentation u = new UserRepresentation();
        u.setUsername(username);
        u.setEmail(email);
        u.setFirstName(username);
        u.setLastName("It");
        u.setEnabled(true);
        u.setEmailVerified(true);
        Map<String, List<String>> attrs = new HashMap<>(attributes);
        if (institution != null) {
            attrs.put("institution", List.of(institution));
        }
        u.setAttributes(attrs);
        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(PASSWORD);
        cred.setTemporary(false);
        u.setCredentials(List.of(cred));
        String id;
        try (Response r = realm.users().create(u)) {
            if (r.getStatus() != 201) {
                throw new IllegalStateException("create user " + username + ": HTTP " + r.getStatus() + " "
                        + r.readEntity(String.class));
            }
            id = CreatedResponseUtil.getCreatedId(r);
        }
        if (institution != null) {
            List<GroupRepresentation> g = realm.groups().groups(institution, true, 0, 10, true);
            g.stream().filter(x -> ("/" + institution).equals(x.getPath())).findFirst()
                    .ifPresent(x -> realm.users().get(id).joinGroup(x.getId()));
        }
        List<RoleRepresentation> roles = new ArrayList<>();
        for (String r : realmRoles) {
            roles.add(ensureRealmRole(r));
        }
        if (!roles.isEmpty()) {
            realm.users().get(id).roles().realmLevel().add(roles);
        }
        return id;
    }

    /** A confidential OIDC client (standard flow + direct grants). Returns its uuid. */
    public static String createClient(String clientId) {
        return createClient(clientId, Map.of());
    }

    public static String createClient(String clientId, Map<String, String> attributes) {
        ClientRepresentation c = new ClientRepresentation();
        c.setClientId(clientId);
        c.setName(clientId);
        c.setEnabled(true);
        c.setPublicClient(false);
        c.setSecret(clientId + "-secret");
        c.setStandardFlowEnabled(true);
        c.setDirectAccessGrantsEnabled(true);
        c.setRedirectUris(List.of("http://localhost:9999/*"));
        c.setRootUrl("http://localhost:9999");
        c.setBaseUrl("/home");
        c.setProtocol("openid-connect");
        c.setAttributes(new HashMap<>(attributes));
        try (Response r = realm().clients().create(c)) {
            if (r.getStatus() != 201) {
                throw new IllegalStateException("create client " + clientId + ": HTTP " + r.getStatus());
            }
            return CreatedResponseUtil.getCreatedId(r);
        }
    }

    public static String userId(String username) {
        return realm().users().searchByUsername(username, true).get(0).getId();
    }

    public static String groupId(String path) {
        return realm().getGroupByPath(path).getId();
    }

    public static String clientUuid(String clientId) {
        return realm().clients().findByClientId(clientId).get(0).getId();
    }

    public static RoleRepresentation ensureRealmRole(String name) {
        RealmResource realm = realm();
        try {
            return realm.roles().get(name).toRepresentation();
        } catch (jakarta.ws.rs.NotFoundException e) {
            RoleRepresentation r = new RoleRepresentation();
            r.setName(name);
            realm.roles().create(r);
            return realm.roles().get(name).toRepresentation();
        }
    }

    // ---- seeding ---------------------------------------------------------------------------

    private static void seed() {
        importRealm();
        RealmResource realm = realm();

        UPConfig profile = realm.users().userProfile().getConfiguration();
        profile.setUnmanagedAttributePolicy(UPConfig.UnmanagedAttributePolicy.ENABLED);
        realm.users().userProfile().update(profile);

        controlPlane(realm);
        for (String r : List.of("super_admin", "admin", "system_admin")) {
            ensureRealmRole(r);
        }
        for (String slug : SLUGS) {
            String root = group(realm, null, slug);
            if ("minagri".equals(slug)) {
                group(realm, root, "planning");
            }
            for (String tier : List.of("admin", "hr", "staff", "auditor")) {
                ensureRealmRole("inst:" + slug + ":" + tier);
            }
            createClient(APPS.get(slug));
            createUser(slug + ".admin", slug + ".admin@" + slug + ".gov.rw", slug, Map.of(), List.of("inst:" + slug + ":admin"));
            createUser(slug + ".hr", slug + ".hr@" + slug + ".gov.rw", slug, Map.of(), List.of("inst:" + slug + ":hr"));
            createUser(slug + ".staff", slug + ".staff@" + slug + ".gov.rw", slug, Map.of(), List.of("inst:" + slug + ":staff"));
        }
        String planner = createUser("minagri.planner", "minagri.planner@minagri.gov.rw", null,
                Map.of("institution", List.of("minagri")), List.of("inst:minagri:staff"));
        realm.users().get(planner).joinGroup(realm.getGroupByPath("/minagri/planning").getId());
        createUser("platform.super", "platform.super@gov.rw", null, Map.of(), List.of("super_admin"));
    }

    @SuppressWarnings("unchecked")
    private static void importRealm() {
        Path file = locate("configs/realms/government-internal-realm.json");
        try {
            Map<String, Object> rep = JSON.readValue(Files.readString(file), Map.class);
            rep.remove("loginTheme");
            List<Map<String, Object>> groups = (List<Map<String, Object>>) rep.getOrDefault("groups", List.of());
            List<Map<String, Object>> kept = new ArrayList<>();
            for (Map<String, Object> g : groups) {
                String name = String.valueOf(g.get("name")).toLowerCase(Locale.ROOT);
                if (!SLUGS.contains(name)) {
                    kept.add(g);
                }
            }
            rep.put("groups", kept);
            for (Map<String, Object> u : (List<Map<String, Object>>) rep.getOrDefault("users", List.of())) {
                if (u.get("groups") instanceof List<?> ug) {
                    u.put("groups", ug.stream().filter(n -> !SLUGS.contains(String.valueOf(n).toLowerCase(Locale.ROOT)))
                            .toList());
                }
            }
            Map<String, Object> attrs = new LinkedHashMap<>((Map<String, Object>) rep.getOrDefault("attributes", Map.of()));
            attrs.put("mfa_totp_mode", "off");
            rep.put("attributes", attrs);
            admin.realms().create(JSON.convertValue(rep, RealmRepresentation.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void controlPlane(RealmResource realm) {
        ClientRepresentation sso = realm.clients().findByClientId("sso-client").get(0);
        sso.setSecret(SSO_CLIENT_SECRET);
        sso.setServiceAccountsEnabled(true);
        sso.setPublicClient(false);
        realm.clients().get(sso.getId()).update(sso);
        ServiceAccountRoleReconciler.reconcile(realm, "sso-client");

        if (realm.clients().findByClientId("sso-admin-ui").isEmpty()) {
            ClientRepresentation ui = new ClientRepresentation();
            ui.setClientId("sso-admin-ui");
            ui.setPublicClient(false);
            ui.setSecret("it-admin-ui-secret");
            ui.setStandardFlowEnabled(true);
            ui.setRedirectUris(List.of("http://localhost:3000/*"));
            realm.clients().create(ui).close();
        }
        ClientRepresentation cli = new ClientRepresentation();
        cli.setClientId(TEST_CLIENT);
        cli.setPublicClient(true);
        cli.setDirectAccessGrantsEnabled(true);
        cli.setStandardFlowEnabled(false);
        realm.clients().create(cli).close();
        String cliId = realm.clients().findByClientId(TEST_CLIENT).get(0).getId();
        ProtocolMapperRepresentation m = new ProtocolMapperRepresentation();
        m.setName("institution");
        m.setProtocol("openid-connect");
        m.setProtocolMapper("oidc-usermodel-attribute-mapper");
        m.setConfig(Map.of("user.attribute", "institution", "claim.name", "institution", "jsonType.label", "String",
                "id.token.claim", "true", "access.token.claim", "true", "userinfo.token.claim", "true"));
        realm.clients().get(cliId).getProtocolMappers().createMapper(m).close();
    }

    private static String group(RealmResource realm, String parentId, String name) {
        GroupRepresentation g = new GroupRepresentation();
        g.setName(name);
        Response r = parentId == null ? realm.groups().add(g) : realm.groups().group(parentId).subGroup(g);
        try (r) {
            return CreatedResponseUtil.getCreatedId(r);
        }
    }

    /** {@code relative} against the working directory, then its parent (the repo root). */
    public static Path locate(String relative) {
        Path here = Path.of("").toAbsolutePath();
        for (Path base : List.of(here, here.getParent())) {
            Path p = base.resolve(relative);
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException(relative + " not found from " + here);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
