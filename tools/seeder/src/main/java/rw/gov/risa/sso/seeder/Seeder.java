package rw.gov.risa.sso.seeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * GoRSSO dev seeder — DEV CONVENIENCE ONLY. Terraform ({@code infra/terraform}) is the production
 * path for onboarding; this exists so that {@code docker compose up} gives a usable stack.
 *
 * <p>Idempotent: every step checks what exists and only creates or converges what is missing, so it
 * can run on every {@code up}. It ports, against the Keycloak Admin REST API:
 * <ul>
 *   <li>{@code kc26_spike.py seed}: realm switches (organizations, unmanaged attributes,
 *       single-page login), platform roles, institution groups + departments, {@code inst:<slug>:*}
 *       roles rendered from {@code templates.json}, {@code <slug>-<app>} clients from
 *       {@code institutions.yaml}, Organizations, demo people;</li>
 *   <li>{@code kc26_spike.py apply}: the FGAP v2 permissions in {@code fgap-v2-plan.json}, for the
 *       seeded institutions only;</li>
 *   <li>{@code kc26_spike.py seed-control-plane}: {@code sso-client} (service account + its
 *       realm-management roles) and {@code sso-admin-ui} (the console BFF), with fixed secrets;</li>
 *   <li>{@code seed_client_access.py}: an {@code sso-access-groups} policy per institution client
 *       admitting the institution group (so the client works under ACCESS_DEFAULT_DENY);</li>
 *   <li>{@code seed_delegated_permissions_test_data.py}: two mirrored institutions with an admin,
 *       hr, auditor, staff, a department member and a not-yet-member each, plus
 *       {@code dp-shared-portal}, a client owned by nobody (the DP-06 control).</li>
 * </ul>
 */
public final class Seeder {

    /** Mirrors ServiceAccountRoleReconciler.REQUIRED_REALM_MANAGEMENT_ROLES in the wrapper. */
    static final List<String> SERVICE_ACCOUNT_ROLES = List.of(
            "view-realm", "view-users", "view-clients", "view-events", "view-authorization",
            "query-users", "query-clients", "query-groups", "query-realms",
            "manage-users", "manage-clients", "manage-authorization", "manage-realm");

    static final List<String> PLATFORM_ROLES = List.of("super_admin", "admin", "system_admin", "employee");

    static final String SHARED_CONTROL_CLIENT = "dp-shared-portal";

    private final ObjectMapper json = new ObjectMapper();
    private final Settings s;
    private KeycloakAdmin kc;
    private Sources src;
    private final List<String> warnings = new ArrayList<>();
    private int changes;

    record Settings(String keycloakUrl, String realm, String adminUser, String adminPassword, Path configDir,
                    List<String> institutions, String ssoClientSecret, String adminUiSecret,
                    String wrapperUrl, List<String> consoleUrls, String demoPassword, boolean demoUsers,
                    boolean fgap, boolean organizations, String appEnv, Duration waitTimeout,
                    Map<String, String> sampleSpas) {
    }

    private Seeder(Settings s) {
        this.s = s;
    }

    public static void main(String[] args) {
        List<String> a = Arrays.asList(args);
        if (a.contains("--help") || a.contains("-h")) {
            System.out.println(HELP);
            return;
        }
        int code;
        try {
            Settings settings = settings(a);
            code = new Seeder(settings).run();
        } catch (Exception e) {
            System.err.println("SEED FAILED: " + e.getMessage());
            code = 1;
        }
        System.exit(code);
    }

    // ------------------------------------------------------------------------------------------
    // Settings (env, with <NAME>_FILE for every secret)
    // ------------------------------------------------------------------------------------------

    static String env(String name, String fallback) {
        String file = System.getenv(name + "_FILE");
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file)).strip();
            } catch (IOException e) {
                throw new IllegalStateException(name + "_FILE=" + file + " is unreadable: " + e.getMessage());
            }
        }
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    static List<String> csv(String v) {
        List<String> out = new ArrayList<>();
        for (String p : v.split(",")) {
            if (!p.isBlank() && !p.strip().equalsIgnoreCase("none")) {
                out.add(p.strip());
            }
        }
        return out;
    }

    static Settings settings(List<String> args) {
        String institutions = env("SEED_INSTITUTIONS", "minagri,minisante");
        boolean fgap = Boolean.parseBoolean(env("SEED_FGAP", "true"));
        boolean users = Boolean.parseBoolean(env("SEED_DEMO_USERS", "true"));
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--institutions" -> institutions = args.get(++i);
                case "--no-fgap" -> fgap = false;
                case "--no-demo-users" -> users = false;
                default -> throw new IllegalArgumentException("unknown argument " + args.get(i) + " (try --help)");
            }
        }
        String consoles = env("CONSOLE_PUBLIC_URLS", env("CONSOLE_PUBLIC_URL", "http://localhost:3001"));
        return new Settings(
                env("KEYCLOAK_URL", "http://localhost:8080"),
                env("KEYCLOAK_REALM", "government-internal"),
                env("KEYCLOAK_ADMIN_USERNAME", "admin"),
                env("KEYCLOAK_ADMIN_PASSWORD", "admin"),
                Path.of(env("SEED_CONFIG_DIR", "configs")),
                csv(institutions),
                env("SSO_CLIENT_SECRET", "gorsso-dev-sso-client"),
                env("ADMIN_UI_CLIENT_SECRET", "gorsso-dev-admin-ui"),
                env("WRAPPER_PUBLIC_URL", "http://localhost:8000").replaceAll("/+$", ""),
                csv(consoles).stream().map(u -> u.replaceAll("/+$", "")).toList(),
                env("SEED_DEMO_PASSWORD", "GorSsoDev#2026"),
                users, fgap,
                Boolean.parseBoolean(env("SEED_ORGANIZATIONS", "true")),
                env("APP_ENV", "dev").toLowerCase(Locale.ROOT),
                Duration.ofSeconds(Long.parseLong(env("SEED_WAIT_SECONDS", "300"))),
                pairs(env("SEED_SAMPLE_SPAS",
                        "minagri-kubaka-spa=http://localhost:5173,minisante-hmis-spa=http://localhost:5174")));
    }

    static Map<String, String> pairs(String v) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String p : csv(v)) {
            int eq = p.indexOf('=');
            if (eq > 0) {
                out.put(p.substring(0, eq).strip(), p.substring(eq + 1).strip());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Run
    // ------------------------------------------------------------------------------------------

    int run() throws Exception {
        if (s.demoUsers() && (s.appEnv().equals("staging") || s.appEnv().equals("prod"))) {
            throw new IllegalStateException("APP_ENV=" + s.appEnv() + ": refusing to create demo users with a"
                    + " published password outside dev. Onboard with Terraform (infra/terraform) instead,"
                    + " or set SEED_DEMO_USERS=false to seed only the control plane.");
        }
        checkPassword(s.demoPassword());
        src = Sources.load(json, s.configDir());
        for (String slug : s.institutions()) {
            if (!src.institutions.containsKey(slug)) {
                throw new IllegalStateException("institution '" + slug + "' is not in "
                        + s.configDir().resolve("institutions.yaml") + "; known: " + src.institutions.keySet());
            }
        }
        kc = new KeycloakAdmin(json, s.keycloakUrl(), s.realm(), s.adminUser(), s.adminPassword());
        System.out.println("GoRSSO seeder -> " + s.keycloakUrl() + " realm '" + s.realm() + "', institutions "
                + s.institutions());
        kc.waitUntilReady(s.waitTimeout());

        realm();
        platformRoles();
        for (String slug : s.institutions()) {
            institution(src.institutions.get(slug));
        }
        if (!s.institutions().isEmpty()) {
            sharedControlClient();
        }
        sampleSpaClients();
        controlPlane();
        if (s.fgap()) {
            fgap();
        }
        if (s.demoUsers()) {
            platformUsers();
        }

        System.out.println();
        System.out.println("Seed complete: " + changes + " change(s), " + warnings.size() + " warning(s).");
        warnings.forEach(w -> System.out.println("  WARN " + w));
        if (s.demoUsers()) {
            System.out.println("Demo password for every seeded person: " + s.demoPassword()
                    + "  (superadmin; " + String.join(", ", s.institutions().stream().map(x -> x + ".admin").toList())
                    + "; <slug>.hr/.auditor/.staff/.planner/.unassigned)");
        }
        return 0;
    }

    static void checkPassword(String p) {
        if (p.length() < 10 || p.chars().noneMatch(Character::isUpperCase)
                || p.chars().noneMatch(Character::isLowerCase) || p.chars().noneMatch(Character::isDigit)) {
            throw new IllegalStateException("SEED_DEMO_PASSWORD does not satisfy the realm policy "
                    + "length(10) and upperCase(1) and lowerCase(1) and digits(1)");
        }
    }

    private void step(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    private void did(String what) {
        changes++;
        System.out.println("   + " + what);
    }

    private void warn(String what) {
        warnings.add(what);
        System.out.println("   ! " + what);
    }

    // ------------------------------------------------------------------------------------------
    // Realm
    // ------------------------------------------------------------------------------------------

    private void realm() {
        step("realm " + s.realm());
        JsonNode realm = kc.get("");
        if (realm.isMissingNode()) {
            // The realm JSONs in configs/realms are imported by Keycloak (--import-realm). This is the
            // fallback for a Keycloak started without them.
            ObjectNode r = json.createObjectNode();
            r.put("realm", s.realm()).put("enabled", true)
                    .put("passwordPolicy", "length(10) and upperCase(1) and lowerCase(1) and digits(1)")
                    .put("bruteForceProtected", true).put("failureFactor", 5).put("permanentLockout", false)
                    .put("waitIncrementSeconds", 900).put("maxFailureWaitSeconds", 900)
                    .put("minimumQuickLoginWaitSeconds", 900).put("quickLoginCheckMilliSeconds", 0)
                    .put("internationalizationEnabled", true).put("defaultLocale", "en")
                    .put("loginTheme", "government-citizens");
            r.putArray("supportedLocales").add("en").add("rw");
            kc.expectOk("POST", "realms", r, false);
            did("realm " + s.realm() + " created (it was not imported from configs/realms)");
            realm = kc.get("");
        }
        ObjectNode patch = json.createObjectNode();
        if (s.organizations() && !realm.path("organizationsEnabled").asBoolean(false)) {
            patch.put("organizationsEnabled", true);
        }
        if (s.fgap() && !realm.path("adminPermissionsEnabled").asBoolean(false)) {
            // Materialises the admin-permissions client the FGAP v2 permissions live on.
            patch.put("adminPermissionsEnabled", true);
        }
        if (!patch.isEmpty()) {
            kc.put("", patch);
            did("realm switches " + patch.properties().stream().map(Map.Entry::getKey).toList());
        }
        unmanagedAttributes();
        singlePageLogin();
        realmSmtp();
        auditListener();
    }

    /**
     * DEV: add the gorsso-audit event listener (in the image) so Keycloak's own login events reach the
     * audit service (and the notification detector). SEED_AUDIT_LISTENER=false leaves the realm alone.
     */
    private void auditListener() {
        if (!Boolean.parseBoolean(env("SEED_AUDIT_LISTENER", "true"))) {
            return;
        }
        JsonNode cfg = kc.get("events/config");
        Set<String> listeners = new LinkedHashSet<>();
        cfg.path("eventsListeners").forEach(l -> listeners.add(l.asText()));
        if (listeners.contains("gorsso-audit")) {
            return;
        }
        listeners.add("gorsso-audit");
        ObjectNode upd = cfg.deepCopy();
        ArrayNode arr = upd.putArray("eventsListeners");
        listeners.forEach(arr::add);
        KeycloakAdmin.Result r = kc.call("PUT", "events/config", upd);
        if (r.ok()) {
            did("event listener gorsso-audit enabled");
        } else {
            warn("could not enable the gorsso-audit listener: HTTP " + r.status());
        }
    }

    /** DEV: point the realm's own mail (Keycloak's execute-actions / reset emails) at MailHog. */
    private void realmSmtp() {
        String host = env("SEED_REALM_SMTP_HOST", "");
        if (host.isEmpty()) {
            return;
        }
        String port = env("SEED_REALM_SMTP_PORT", "1025");
        JsonNode cur = kc.get("").path("smtpServer");
        if (host.equals(cur.path("host").asText()) && port.equals(cur.path("port").asText())) {
            return;
        }
        ObjectNode patch = json.createObjectNode();
        patch.putObject("smtpServer").put("host", host).put("port", port)
                .put("from", env("SEED_REALM_SMTP_FROM", "sso-noreply@dev.sso.gov.rw"))
                .put("fromDisplayName", "GoR SSO (dev)").put("auth", "false").put("ssl", "false")
                .put("starttls", "false");
        kc.put("", patch);
        did("realm SMTP -> " + host + ":" + port);
    }

    /**
     * The declarative user profile (always on since KC 24) drops unknown attributes silently, which
     * would lose {@code institution}, {@code employee_number}, {@code national_id}, ...
     */
    private void unmanagedAttributes() {
        JsonNode profile = kc.get("users/profile");
        if (profile.isMissingNode() || "ENABLED".equals(profile.path("unmanagedAttributePolicy").asText())) {
            return;
        }
        ((ObjectNode) profile).put("unmanagedAttributePolicy", "ENABLED");
        kc.put("users/profile", profile);
        did("user profile: unmanaged attributes enabled");
    }

    /**
     * Turning organizations on injects an {@code Organization} identity-first step into the browser
     * flow, which hides the password field. Disable that one execution (the feature stays on).
     */
    private void singlePageLogin() {
        JsonNode executions = kc.get("authentication/flows/browser/executions");
        for (JsonNode e : executions) {
            if ("Organization".equals(e.path("displayName").asText()) && e.path("level").asInt() == 0
                    && !"DISABLED".equals(e.path("requirement").asText())) {
                ObjectNode upd = e.deepCopy();
                upd.put("requirement", "DISABLED");
                kc.put("authentication/flows/browser/executions", upd);
                did("browser flow: identity-first Organization step disabled (single-page login)");
            }
        }
    }

    private void platformRoles() {
        step("platform realm roles");
        for (String role : PLATFORM_ROLES) {
            ensureRealmRole(role, "Platform role (" + role + ")");
        }
    }

    private void ensureRealmRole(String name, String description) {
        if (!kc.get("roles/" + KeycloakAdmin.enc(name)).isMissingNode()) {
            return;
        }
        ObjectNode r = json.createObjectNode().put("name", name).put("description", description);
        kc.post("roles", r);
        did("realm role " + name);
    }

    private Optional<JsonNode> realmRole(String name) {
        JsonNode r = kc.get("roles/" + KeycloakAdmin.enc(name));
        return r.isMissingNode() ? Optional.empty() : Optional.of(r);
    }

    // ------------------------------------------------------------------------------------------
    // Institutions
    // ------------------------------------------------------------------------------------------

    private void institution(Sources.Institution inst) {
        String slug = inst.slug();
        step("institution " + slug + " (" + inst.displayName() + ")");
        JsonNode group = ensureTopGroup(slug);
        List<String> departments = inst.departments().isEmpty() ? List.of("planning") : inst.departments();
        for (String d : departments) {
            ensureChildGroup(group, d);
        }
        src.rolesFor(slug).forEach(this::ensureRealmRole);
        for (Sources.App app : inst.apps()) {
            JsonNode client = ensureInstitutionClient(slug, app);
            ensureInstitutionMapper(client, "institution-attribute");
            ensureAccessGroupsPolicy(client, group);
        }
        Optional<String> orgId = s.organizations() ? ensureOrganization(slug, inst.displayName()) : Optional.empty();
        if (s.demoUsers()) {
            institutionPeople(inst, departments.get(0), orgId);
        }
    }

    private JsonNode groupByPath(String path) {
        return kc.get("group-by-path/" + Arrays.stream(path.split("/")).filter(p -> !p.isEmpty())
                .map(KeycloakAdmin::enc).reduce((x, y) -> x + "/" + y).orElse(""));
    }

    private JsonNode ensureTopGroup(String slug) {
        JsonNode g = groupByPath("/" + slug);
        if (g.isMissingNode()) {
            ObjectNode body = json.createObjectNode().put("name", slug);
            body.putObject("attributes").putArray("institution").add(slug);
            kc.post("groups", body);
            did("group /" + slug);
            g = groupByPath("/" + slug);
        }
        return g;
    }

    /** Children are read from /groups/{id}/children: a group's {@code subGroups} is always empty in 26. */
    private void ensureChildGroup(JsonNode parent, String name) {
        String id = parent.path("id").asText();
        JsonNode children = kc.get("groups/" + id + "/children?max=1000&briefRepresentation=true");
        for (JsonNode c : children) {
            if (name.equals(c.path("name").asText())) {
                return;
            }
        }
        kc.post("groups/" + id + "/children", json.createObjectNode().put("name", name));
        did("group " + parent.path("path").asText() + "/" + name);
    }

    private Optional<JsonNode> findClient(String clientId) {
        JsonNode list = kc.get("clients?clientId=" + KeycloakAdmin.enc(clientId));
        for (JsonNode c : list) {
            if (clientId.equals(c.path("clientId").asText())) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private JsonNode ensureInstitutionClient(String slug, Sources.App app) {
        String clientId = app.clientId(slug);
        Optional<JsonNode> existing = findClient(clientId);
        JsonNode client;
        if (existing.isEmpty()) {
            ObjectNode c = json.createObjectNode();
            c.put("clientId", clientId)
                    .put("name", app.displayName() == null ? clientId : app.displayName())
                    .put("enabled", true).put("protocol", "openid-connect")
                    .put("publicClient", false).put("standardFlowEnabled", true)
                    .put("implicitFlowEnabled", false)
                    // DEV ONLY: lets a tester get a token with the password grant.
                    .put("directAccessGrantsEnabled", true)
                    // Authorization services (needed for the sso-access-* policies) need a service account.
                    .put("serviceAccountsEnabled", true).put("authorizationServicesEnabled", true);
            c.putArray("redirectUris").add("http://localhost:*/*").add("http://127.0.0.1:*/*");
            c.putArray("webOrigins").add("+");
            ObjectNode attrs = c.putObject("attributes");
            attrs.put("sso.client.owner_institution", slug);
            attrs.put("institution", slug);
            attrs.put("post.logout.redirect.uris", "+");
            kc.post("clients", c);
            did("client " + clientId);
            client = findClient(clientId).orElseThrow();
        } else {
            client = existing.get();
            if (!client.path("authorizationServicesEnabled").asBoolean(false)
                    || !slug.equals(client.path("attributes").path("sso.client.owner_institution").asText())) {
                ObjectNode upd = client.deepCopy();
                upd.put("serviceAccountsEnabled", true).put("authorizationServicesEnabled", true);
                ObjectNode attrs = upd.has("attributes") ? (ObjectNode) upd.get("attributes") : upd.putObject("attributes");
                attrs.put("sso.client.owner_institution", slug);
                kc.put("clients/" + client.path("id").asText(), upd);
                did("client " + clientId + " (authorization services / owner reconciled)");
                client = findClient(clientId).orElseThrow();
            }
        }
        String id = client.path("id").asText();
        Set<String> have = new HashSet<>();
        kc.get("clients/" + id + "/roles").forEach(r -> have.add(r.path("name").asText()));
        for (String role : app.roles()) {
            if (!have.contains(role)) {
                kc.post("clients/" + id + "/roles", json.createObjectNode().put("name", role));
                did("client role " + clientId + ":" + role);
            }
        }
        return client;
    }

    /** {@code institution} user attribute → {@code institution} claim (id, access, userinfo). */
    private void ensureInstitutionMapper(JsonNode client, String mapperName) {
        String id = client.path("id").asText();
        for (JsonNode m : kc.get("clients/" + id + "/protocol-mappers/models")) {
            if ("oidc-usermodel-attribute-mapper".equals(m.path("protocolMapper").asText())
                    && "institution".equals(m.path("config").path("claim.name").asText())) {
                return;
            }
        }
        ObjectNode m = json.createObjectNode();
        m.put("name", mapperName).put("protocol", "openid-connect")
                .put("protocolMapper", "oidc-usermodel-attribute-mapper");
        m.putObject("config")
                .put("user.attribute", "institution").put("claim.name", "institution")
                .put("jsonType.label", "String").put("id.token.claim", "true")
                .put("access.token.claim", "true").put("userinfo.token.claim", "true");
        kc.post("clients/" + id + "/protocol-mappers/models", m);
        did("protocol mapper institution on " + client.path("clientId").asText());
    }

    /**
     * Port of seed_client_access.py / the Terraform module: an {@code sso-access-groups} group policy
     * admitting the institution group (direct members only), so the client works under
     * ACCESS_DEFAULT_DENY. Operational edits (through /authorization) are not reverted: an existing
     * policy is left as it is.
     */
    private void ensureAccessGroupsPolicy(JsonNode client, JsonNode group) {
        String id = client.path("id").asText();
        String base = "clients/" + id + "/authz/resource-server/policy";
        KeycloakAdmin.Result list = kc.call("GET", base + "?max=1000&permission=false", null);
        if (!list.ok()) {
            warn("cannot read policies of " + client.path("clientId").asText() + ": HTTP " + list.status());
            return;
        }
        for (JsonNode p : list.body()) {
            if ("sso-access-groups".equals(p.path("name").asText())) {
                return;
            }
        }
        ObjectNode body = json.createObjectNode();
        body.put("name", "sso-access-groups")
                .put("description", "Seeded (tools/seeder): members of " + group.path("path").asText()
                        + " may obtain tokens for " + client.path("clientId").asText() + ".")
                .put("type", "group").put("logic", "POSITIVE").put("decisionStrategy", "UNANIMOUS");
        body.putArray("groups").addObject().put("id", group.path("id").asText()).put("extendChildren", false);
        kc.post(base + "/group", body);
        did("policy sso-access-groups on " + client.path("clientId").asText() + " -> " + group.path("path").asText());
    }

    private Optional<String> ensureOrganization(String slug, String displayName) {
        KeycloakAdmin.Result r = kc.call("GET", "organizations?search=" + KeycloakAdmin.enc(slug) + "&exact=false", null);
        if (r.status() == 404 || r.status() == 400) {
            warn("Organizations API unavailable (HTTP " + r.status() + "); is `organization` in KC_FEATURES?");
            return Optional.empty();
        }
        if (r.ok()) {
            for (JsonNode o : r.body()) {
                if (slug.equals(o.path("alias").asText())) {
                    return Optional.of(o.path("id").asText());
                }
            }
        }
        ObjectNode body = json.createObjectNode();
        body.put("name", displayName).put("alias", slug).put("enabled", true);
        body.putArray("domains").addObject().put("name", slug + ".gov.rw").put("verified", false);
        KeycloakAdmin.Result c = kc.call("POST", "organizations", body);
        if (!c.ok()) {
            warn("organization " + slug + ": HTTP " + c.status() + " " + KeycloakAdmin.abbreviate(c.raw()));
            return Optional.empty();
        }
        did("organization " + slug);
        KeycloakAdmin.Result again = kc.call("GET", "organizations?search=" + KeycloakAdmin.enc(slug), null);
        for (JsonNode o : again.body()) {
            if (slug.equals(o.path("alias").asText())) {
                return Optional.of(o.path("id").asText());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------
    // People
    // ------------------------------------------------------------------------------------------

    /** One seeded person. {@code groupPath} null = deliberately not a member. */
    record Person(String username, String institution, String groupPath, List<String> realmRoles, String purpose) {
    }

    static List<Person> peopleFor(Sources src, Sources.Institution inst, String department) {
        String slug = inst.slug();
        List<String> tiers = src.tiersFor(slug);
        List<Person> out = new ArrayList<>();
        Map<String, String> byTier = new LinkedHashMap<>();
        byTier.put("institution-admin", "admin");
        byTier.put("institution-hr", "hr");
        byTier.put("institution-auditor", "auditor");
        byTier.forEach((tier, suffix) -> {
            if (tiers.contains(tier)) {
                out.add(new Person(slug + "." + suffix, slug, "/" + slug, List.of(src.grantRole(tier, slug)),
                        tier + " of " + slug));
            }
        });
        String staff = "inst:" + slug + ":staff";
        out.add(new Person(slug + ".staff", slug, "/" + slug, List.of(staff), "plain member"));
        out.add(new Person(slug + ".planner", slug, "/" + slug + "/" + department, List.of(staff),
                "department member (include_subgroups probe)"));
        out.add(new Person(slug + ".unassigned", slug, null, List.of(), "has the attribute, not the membership"));
        return out;
    }

    private void institutionPeople(Sources.Institution inst, String department, Optional<String> orgId) {
        Set<String> known = src.rolesFor(inst.slug()).keySet();
        for (Person p : peopleFor(src, inst, department)) {
            List<String> roles = p.realmRoles().stream().filter(r -> known.contains(r) || realmRole(r).isPresent()).toList();
            JsonNode user = ensureUser(p.username(), p.institution(), p.groupPath(), roles);
            orgId.ifPresent(org -> addOrgMember(org, user.path("id").asText()));
        }
    }

    private void platformUsers() {
        step("platform people");
        ensureUser("superadmin", null, null, List.of("super_admin"));
    }

    private JsonNode ensureUser(String username, String institution, String groupPath, List<String> realmRoles) {
        JsonNode user = findUser(username).orElse(null);
        if (user == null) {
            ObjectNode u = json.createObjectNode();
            u.put("username", username).put("enabled", true).put("emailVerified", true)
                    .put("email", username + "@dev.sso.gov.rw")
                    .put("firstName", cap(username.contains(".") ? username.substring(username.indexOf('.') + 1) : username))
                    .put("lastName", institution == null ? "Platform" : institution.toUpperCase(Locale.ROOT));
            if (institution != null) {
                u.putObject("attributes").putArray("institution").add(institution);
            }
            u.putArray("credentials").addObject().put("type", "password").put("value", s.demoPassword())
                    .put("temporary", false);
            kc.post("users", u);
            did("user " + username);
            user = findUser(username).orElseThrow();
        } else if (institution != null
                && !institution.equals(user.path("attributes").path("institution").path(0).asText(null))) {
            ObjectNode upd = user.deepCopy();
            ObjectNode attrs = upd.has("attributes") ? (ObjectNode) upd.get("attributes") : upd.putObject("attributes");
            attrs.putArray("institution").add(institution);
            kc.put("users/" + user.path("id").asText(), upd);
            did("user " + username + " (institution attribute backfilled)");
        }
        String id = user.path("id").asText();
        if (groupPath != null) {
            JsonNode g = groupByPath(groupPath);
            if (!g.isMissingNode()) {
                boolean member = false;
                for (JsonNode ug : kc.get("users/" + id + "/groups?briefRepresentation=true&max=1000")) {
                    if (groupPath.equals(ug.path("path").asText())) {
                        member = true;
                    }
                }
                if (!member) {
                    kc.put("users/" + id + "/groups/" + g.path("id").asText(), null);
                    did("membership " + username + " -> " + groupPath);
                }
            }
        }
        Set<String> held = new HashSet<>();
        kc.get("users/" + id + "/role-mappings/realm").forEach(r -> held.add(r.path("name").asText()));
        ArrayNode add = json.createArrayNode();
        for (String role : realmRoles) {
            if (held.contains(role)) {
                continue;
            }
            realmRole(role).ifPresentOrElse(r -> add.add(json.createObjectNode()
                            .put("id", r.path("id").asText()).put("name", r.path("name").asText())),
                    () -> warn("realm role " + role + " missing; not granted to " + username));
        }
        if (!add.isEmpty()) {
            kc.post("users/" + id + "/role-mappings/realm", add);
            did("roles " + username + " += " + add.findValuesAsText("name"));
        }
        return user;
    }

    private Optional<JsonNode> findUser(String username) {
        for (JsonNode u : kc.get("users?exact=true&briefRepresentation=false&username=" + KeycloakAdmin.enc(username))) {
            if (username.equalsIgnoreCase(u.path("username").asText())) {
                return Optional.of(u);
            }
        }
        return Optional.empty();
    }

    private void addOrgMember(String orgId, String userId) {
        KeycloakAdmin.Result r = kc.call("GET", "organizations/" + orgId + "/members/" + userId, null);
        if (r.ok()) {
            return;
        }
        KeycloakAdmin.Result add = kc.call("POST", "organizations/" + orgId + "/members", userId);
        if (!add.ok() && add.status() != 409) {
            warn("organization membership for " + userId + ": HTTP " + add.status());
        }
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------------------------------
    // DP-06 control client and control plane
    // ------------------------------------------------------------------------------------------

    /** A client owned by NO institution: a client filter that over-matches shows up against it. */
    private void sharedControlClient() {
        step("shared control client " + SHARED_CONTROL_CLIENT);
        if (findClient(SHARED_CONTROL_CLIENT).isPresent()) {
            return;
        }
        ObjectNode c = json.createObjectNode();
        c.put("clientId", SHARED_CONTROL_CLIENT).put("name", "Shared portal: owned by no institution (DP-06 control)")
                .put("enabled", true).put("publicClient", false).put("standardFlowEnabled", true)
                .put("directAccessGrantsEnabled", true);
        c.putArray("redirectUris").add("http://localhost:*/*");
        c.putArray("webOrigins").add("+");
        kc.post("clients", c);
        did("client " + SHARED_CONTROL_CLIENT);
        String id = findClient(SHARED_CONTROL_CLIENT).orElseThrow().path("id").asText();
        kc.post("clients/" + id + "/roles", json.createObjectNode().put("name", "viewer"));
    }

    /**
     * DEV ONLY: public PKCE clients for the sample React SPAs (sample-apps/web), which log in through
     * the wrapper. The registry's apps are confidential, so the samples get their own clients.
     * SEED_SAMPLE_SPAS = comma-separated {@code <clientId>=<origin>}; the owner institution is the
     * client-id prefix and must be a seeded institution. Public clients cannot carry authorization
     * services, so they get no sso-access-groups policy (the wrapper treats them as exempt from
     * per-client enforcement, like the Terraform module does).
     */
    private void sampleSpaClients() {
        if (s.sampleSpas().isEmpty()) {
            return;
        }
        step("sample SPA clients (public, PKCE S256; dev only)");
        for (Map.Entry<String, String> e : s.sampleSpas().entrySet()) {
            String clientId = e.getKey();
            String origin = e.getValue().replaceAll("/+$", "");
            String owner = s.institutions().stream().filter(slug -> clientId.startsWith(slug + "-")).findFirst()
                    .orElse(null);
            if (owner == null) {
                warn("sample SPA " + clientId + ": no seeded institution owns that prefix; skipped");
                continue;
            }
            ObjectNode c = json.createObjectNode();
            c.put("clientId", clientId).put("name", clientId + " (sample SPA, dev only)").put("enabled", true)
                    .put("protocol", "openid-connect").put("publicClient", true).put("standardFlowEnabled", true)
                    .put("implicitFlowEnabled", false).put("directAccessGrantsEnabled", false);
            c.putArray("redirectUris").add(origin + "/callback").add(origin + "/*");
            c.putArray("webOrigins").add(origin);
            c.putObject("attributes").put("sso.client.owner_institution", owner).put("institution", owner)
                    .put("pkce.code.challenge.method", "S256").put("post.logout.redirect.uris", origin + "/*");
            Optional<JsonNode> existing = findClient(clientId);
            JsonNode client;
            if (existing.isEmpty()) {
                kc.post("clients", c);
                did("public client " + clientId + " -> " + origin);
            } else if (!sameSet(existing.get().path("redirectUris"), List.of(origin + "/callback", origin + "/*"))
                    || !existing.get().path("publicClient").asBoolean()) {
                ObjectNode merged = existing.get().deepCopy();
                merged.setAll(c);
                kc.put("clients/" + existing.get().path("id").asText(), merged);
                did("public client " + clientId + " (reconciled)");
            }
            client = findClient(clientId).orElseThrow();
            ensureInstitutionMapper(client, "institution-attribute");
        }
    }

    private void controlPlane() {
        step("control plane (sso-client, sso-admin-ui)");
        JsonNode ssoClient = ensureControlPlaneClient("sso-client", s.ssoClientSecret(), true,
                List.of(s.wrapperUrl() + "/*"), List.of("+"), "+", true);
        ensureInstitutionMapper(ssoClient, "institution");
        grantServiceAccountRoles(ssoClient);

        List<String> redirects = new ArrayList<>();
        List<String> origins = new ArrayList<>();
        List<String> postLogout = new ArrayList<>();
        for (String console : s.consoleUrls()) {
            redirects.add(console + "/login/oauth2/code/sso-admin-ui");
            origins.add(console);
            postLogout.add(console + "/");
        }
        JsonNode adminUi = ensureControlPlaneClient("sso-admin-ui", s.adminUiSecret(), false,
                redirects, origins, String.join("##", postLogout), false);
        ensureInstitutionMapper(adminUi, "institution");
    }

    /** A confidential client whose secret is known (so compose can ship a matching default). */
    private JsonNode ensureControlPlaneClient(String clientId, String secret, boolean serviceAccount,
                                              List<String> redirectUris, List<String> webOrigins,
                                              String postLogoutUris, boolean directGrant) {
        ObjectNode desired = json.createObjectNode();
        desired.put("clientId", clientId).put("enabled", true).put("protocol", "openid-connect")
                .put("publicClient", false).put("clientAuthenticatorType", "client-secret")
                .put("secret", secret).put("serviceAccountsEnabled", serviceAccount)
                .put("standardFlowEnabled", true).put("implicitFlowEnabled", false)
                .put("directAccessGrantsEnabled", directGrant);
        ArrayNode r = desired.putArray("redirectUris");
        redirectUris.forEach(r::add);
        ArrayNode w = desired.putArray("webOrigins");
        webOrigins.forEach(w::add);
        Optional<JsonNode> existing = findClient(clientId);
        if (existing.isEmpty()) {
            desired.putObject("attributes").put("post.logout.redirect.uris", postLogoutUris)
                    .put("pkce.code.challenge.method", serviceAccount ? "" : "S256");
            kc.post("clients", desired);
            did("client " + clientId);
        } else {
            JsonNode cur = existing.get();
            ObjectNode merged = cur.deepCopy();
            merged.setAll(desired);
            ObjectNode attrs = cur.has("attributes") ? ((ObjectNode) cur.get("attributes")).deepCopy() : json.createObjectNode();
            attrs.put("post.logout.redirect.uris", postLogoutUris);
            if (!serviceAccount) {
                attrs.put("pkce.code.challenge.method", "S256");
            }
            merged.set("attributes", attrs);
            String secretNow = kc.get("clients/" + cur.path("id").asText() + "/client-secret").path("value").asText("");
            boolean same = secretNow.equals(secret)
                    && cur.path("serviceAccountsEnabled").asBoolean() == serviceAccount
                    && cur.path("directAccessGrantsEnabled").asBoolean() == directGrant
                    && sameSet(cur.path("redirectUris"), redirectUris)
                    && sameSet(cur.path("webOrigins"), webOrigins)
                    && postLogoutUris.equals(cur.path("attributes").path("post.logout.redirect.uris").asText());
            if (!same) {
                kc.put("clients/" + cur.path("id").asText(), merged);
                did("client " + clientId + " (reconciled: secret / redirect URIs / flags)");
            }
        }
        return findClient(clientId).orElseThrow();
    }

    private static boolean sameSet(JsonNode arr, List<String> want) {
        Set<String> have = new HashSet<>();
        arr.forEach(n -> have.add(n.asText()));
        return have.equals(new HashSet<>(want));
    }

    /** Not a guardrail violation: this is the platform's own client, not a delegated admin. */
    private void grantServiceAccountRoles(JsonNode client) {
        JsonNode rm = findClient("realm-management").orElseThrow(
                () -> new IllegalStateException("realm-management client not found; wrong realm?"));
        JsonNode account = kc.get("clients/" + client.path("id").asText() + "/service-account-user");
        if (account.isMissingNode()) {
            throw new IllegalStateException(client.path("clientId").asText() + " has no service account user");
        }
        String path = "users/" + account.path("id").asText() + "/role-mappings/clients/" + rm.path("id").asText();
        Set<String> held = new HashSet<>();
        kc.get(path).forEach(r -> held.add(r.path("name").asText()));
        Map<String, JsonNode> available = new HashMap<>();
        kc.get("clients/" + rm.path("id").asText() + "/roles?max=500").forEach(r -> available.put(r.path("name").asText(), r));
        ArrayNode add = json.createArrayNode();
        for (String role : SERVICE_ACCOUNT_ROLES) {
            if (!available.containsKey(role)) {
                warn("realm-management has no role " + role + " on this Keycloak version");
            } else if (!held.contains(role)) {
                add.add(available.get(role));
            }
        }
        if (!add.isEmpty()) {
            kc.post(path, add);
            did("service account " + account.path("username").asText() + " += " + add.findValuesAsText("name"));
        }
    }

    // ------------------------------------------------------------------------------------------
    // FGAP v2 (port of kc26_spike.py apply, restricted to the seeded institutions)
    // ------------------------------------------------------------------------------------------

    /** Plan entries this seed can apply: the seeded institutions plus the shared ({@code _shared}) ones. */
    static boolean inScope(JsonNode entry, Set<String> seeded) {
        String inst = entry.path("institution").asText("");
        return seeded.contains(inst) || inst.equals("_shared") || inst.isEmpty();
    }

    private void fgap() {
        step("FGAP v2 permissions (configs/generated/fgap-v2-plan.json, seeded institutions only)");
        JsonNode plan = src.fgapPlan;
        if (plan.isMissingNode()) {
            warn("no configs/generated/fgap-v2-plan.json; delegated calls as the caller will be refused by Keycloak");
            return;
        }
        Optional<JsonNode> rs = findClient("admin-permissions");
        if (rs.isEmpty()) {
            warn("no admin-permissions client: is admin-fine-grained-authz:v2 in KC_FEATURES? FGAP skipped");
            return;
        }
        String rsBase = "clients/" + rs.get().path("id").asText() + "/authz/resource-server";
        Set<String> seeded = new LinkedHashSet<>(s.institutions());

        Map<String, String> policyIds = new HashMap<>();
        kc.get(rsBase + "/policy?max=2000&permission=false").forEach(p -> policyIds.put(p.path("name").asText(), p.path("id").asText()));
        for (JsonNode p : plan.path("policies")) {
            if (!inScope(p, seeded) || policyIds.containsKey(p.path("name").asText())) {
                continue;
            }
            ArrayNode roles = json.createArrayNode();
            boolean complete = true;
            for (JsonNode rn : p.path("roles")) {
                Optional<JsonNode> role = realmRole(rn.asText());
                if (role.isEmpty()) {
                    complete = false;
                    continue;
                }
                roles.addObject().put("id", role.get().path("id").asText()).put("required", false);
            }
            if (!complete && !"_shared".equals(p.path("institution").asText())) {
                warn("policy " + p.path("name").asText() + ": some roles are missing; skipped");
                continue;
            }
            if (roles.isEmpty()) {
                continue;
            }
            ObjectNode body = json.createObjectNode();
            body.put("name", p.path("name").asText()).put("description", shorten(p.path("description").asText()))
                    .put("logic", "POSITIVE").put("decisionStrategy", "UNANIMOUS");
            body.set("roles", roles);
            KeycloakAdmin.Result r = kc.post(rsBase + "/policy/role", body);
            policyIds.put(p.path("name").asText(), r.body().path("id").asText(null));
            did("FGAP policy " + p.path("name").asText());
        }
        policyIds.clear();
        kc.get(rsBase + "/policy?max=2000&permission=false").forEach(p -> policyIds.put(p.path("name").asText(), p.path("id").asText()));

        Map<String, String> scopeIds = new HashMap<>();
        kc.get(rsBase + "/scope?max=1000").forEach(sc -> scopeIds.put(sc.path("name").asText(), sc.path("id").asText()));
        Map<String, JsonNode> permissions = new HashMap<>();
        kc.get(rsBase + "/permission?max=2000").forEach(p -> permissions.put(p.path("name").asText(), p));

        for (JsonNode perm : plan.path("permissions")) {
            if (!inScope(perm, seeded)) {
                continue;
            }
            String name = perm.path("name").asText();
            String type = perm.path("resource_type").asText();
            List<String> wantIds = new ArrayList<>();
            boolean allOfType = false;
            List<String> unresolved = new ArrayList<>();
            for (JsonNode r : perm.path("resources")) {
                String human = r.asText();
                if (human.equals("*")) {
                    allOfType = true;
                    continue;
                }
                Optional<String> oid = objectId(type, human);
                if (oid.isPresent()) {
                    wantIds.add(oid.get());
                } else {
                    unresolved.add(human);
                }
            }
            if (wantIds.isEmpty() && !allOfType) {
                warn("FGAP permission " + name + ": no resources resolved " + unresolved + "; skipped (an empty"
                        + " list would mean EVERY " + type + ")");
                continue;
            }
            List<String> scopes = new ArrayList<>();
            List<String> missingScopes = new ArrayList<>();
            perm.path("scopes").forEach(sc -> {
                String sid = scopeIds.get(sc.asText());
                if (sid == null) {
                    missingScopes.add(sc.asText());
                } else {
                    scopes.add(sid);
                }
            });
            if (!missingScopes.isEmpty()) {
                warn("FGAP permission " + name + ": unknown scope(s) " + missingScopes + "; skipped");
                continue;
            }
            List<String> policies = new ArrayList<>();
            perm.path("policies").forEach(pn -> {
                String pid = policyIds.get(pn.asText());
                if (pid != null) {
                    policies.add(pid);
                }
            });
            if (policies.isEmpty()) {
                warn("FGAP permission " + name + ": none of its policies exist; skipped");
                continue;
            }
            ObjectNode body = json.createObjectNode();
            body.put("name", name).put("description", shorten(perm.path("description").asText()))
                    .put("resourceType", type).put("decisionStrategy", "UNANIMOUS");
            ArrayNode res = body.putArray("resources");
            wantIds.forEach(res::add);
            ArrayNode sc = body.putArray("scopes");
            scopes.forEach(sc::add);
            ArrayNode pol = body.putArray("policies");
            policies.forEach(pol::add);

            JsonNode existing = permissions.get(name);
            if (existing == null) {
                kc.post(rsBase + "/permission/scope", body);
                did("FGAP permission " + name);
                continue;
            }
            // Converge, not just "exists": a client onboarded after the first apply must enter the permission.
            String pid = existing.path("id").asText();
            Set<String> haveIds = new HashSet<>();
            boolean haveAll = false;
            for (JsonNode r : kc.get(rsBase + "/policy/" + pid + "/resources")) {
                String dn = r.path("displayName").asText(r.path("name").asText());
                if (Set.of("Users", "Groups", "Roles", "Clients").contains(dn)) {
                    haveAll = true;
                } else {
                    haveIds.add(r.path("name").asText());
                }
            }
            Set<String> havePolicies = new HashSet<>();
            kc.get(rsBase + "/policy/" + pid + "/associatedPolicies").forEach(p -> havePolicies.add(p.path("id").asText()));
            if (haveIds.equals(new HashSet<>(wantIds)) && haveAll == allOfType
                    && havePolicies.equals(new HashSet<>(policies))) {
                continue;
            }
            body.put("id", pid);
            kc.put(rsBase + "/permission/scope/" + pid, body);
            did("FGAP permission " + name + " (resources/policies converged)");
        }
    }

    private Optional<String> objectId(String type, String human) {
        return switch (type) {
            case "Groups" -> {
                JsonNode g = groupByPath(human);
                yield g.isMissingNode() ? Optional.empty() : Optional.of(g.path("id").asText());
            }
            case "Roles" -> realmRole(human).map(r -> r.path("id").asText());
            case "Clients" -> findClient(human).map(c -> c.path("id").asText());
            default -> Optional.empty();
        };
    }

    /** RESOURCE_SERVER_POLICY.DESCRIPTION is varchar(255); an overrun is a bare 500. */
    static String shorten(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() <= 250 ? t : t.substring(0, 249).stripTrailing() + "…";
    }

    static final String HELP = """
            GoRSSO dev seeder (DEV CONVENIENCE; Terraform is the production onboarding path).

            Usage: java -jar gorsso-seeder.jar [--institutions minagri,minisante] [--no-fgap] [--no-demo-users]

            Environment (every secret also accepts <NAME>_FILE):
              KEYCLOAK_URL               http://localhost:8080
              KEYCLOAK_REALM             government-internal
              KEYCLOAK_ADMIN_USERNAME    admin            (master realm, admin-cli password grant)
              KEYCLOAK_ADMIN_PASSWORD    admin
              SEED_CONFIG_DIR            configs          (institutions.yaml, delegated-permissions/, generated/)
              SEED_INSTITUTIONS          minagri,minisante
              SSO_CLIENT_SECRET          gorsso-dev-sso-client   (must equal the wrapper's KEYCLOAK_CLIENT_SECRET)
              ADMIN_UI_CLIENT_SECRET     gorsso-dev-admin-ui     (must equal the console-bff's ADMIN_UI_CLIENT_SECRET)
              WRAPPER_PUBLIC_URL         http://localhost:8000
              CONSOLE_PUBLIC_URLS        http://localhost:3001   (comma-separated; each gets
                                         <url>/login/oauth2/code/sso-admin-ui and <url>/ on sso-admin-ui)
              SEED_DEMO_PASSWORD         GorSsoDev#2026   (must satisfy the realm password policy)
              SEED_DEMO_USERS            true             (refused when APP_ENV is staging/prod)
              SEED_FGAP                  true
              SEED_ORGANIZATIONS         true
              SEED_WAIT_SECONDS          300
              SEED_REALM_SMTP_HOST       (unset)          dev: realm smtpServer, e.g. mailhog (+ _PORT 1025, _FROM)
              SEED_INSTITUTIONS=none / SEED_SAMPLE_SPAS=none   seed the control plane only
              SEED_SAMPLE_SPAS           minagri-kubaka-spa=http://localhost:5173,minisante-hmis-spa=http://localhost:5174
                                         (public PKCE clients for sample-apps/web; empty = none)
            """;
}
