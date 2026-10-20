package rw.gov.risa.sso.wrapper.service.mfa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import org.keycloak.admin.client.resource.AuthenticationManagementResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
import org.keycloak.representations.idm.AuthenticatorConfigRepresentation;
import org.keycloak.representations.idm.RealmEventsConfigRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderSimpleRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.mfa.policy.MfaConstants;
import rw.gov.risa.sso.mfa.policy.MfaFlowDefinition;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakDirectory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * Applies the TOTP configuration through the Admin API — the no-Terraform twin of
 * {@code infra/terraform/mfa.tf} (port of {@code scripts/kc26_enable_mfa.py}). The flow comes from the
 * shared declarative definition {@code mfa-policy:/gorsso/mfa-flow.json} (kept equal to mfa.tf by tests
 * on both sides). Idempotent; {@code apply=false} only reports drift.
 *
 * <p>It NEVER turns enforcement on: {@code mfa_totp_mode} is written only when unset, and then as
 * {@code off}. It does not touch users, clients or other flows. Requires the gorsso-mfa provider JAR
 * and the {@code recovery-codes} feature on the server; when missing nothing is changed and the report
 * says which providers are absent. Needs {@code manage-realm} on the executing account.
 */
@Component
public class MfaFlowInstaller {

    private static final Logger log = LoggerFactory.getLogger(MfaFlowInstaller.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final KeycloakExecutors executors;
    private final KeycloakDirectory directory;
    private final JsonNode definition;

    public MfaFlowInstaller(KeycloakExecutors executors, KeycloakDirectory directory) {
        this.executors = executors;
        this.directory = directory;
        try {
            this.definition = JSON.readTree(MfaFlowDefinition.json());
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse " + MfaConstants.FLOW_DEFINITION_RESOURCE, e);
        }
    }

    // ---- the plan --------------------------------------------------------------------------

    /** One flow step as both sides compare it: {@code (level, exec|flow, providerId|alias, requirement)}. */
    public record FlowRow(int level, String kind, String key, String requirement) {
    }

    /** A node of the flow tree with its config ({@code alias → values}) and parent alias. */
    public record FlowNode(int level, String kind, String key, String requirement, String parent,
                           String configAlias, Map<String, String> config) {
    }

    /**
     * The flow to build, in order. {@code organizationRequirement} follows the realm's current
     * {@code browser} flow Organization step (null = the feature is absent → the Organization sub-flow is
     * omitted, like {@code enable_organization_login = false}).
     */
    public List<FlowNode> plan(String organizationRequirement) {
        List<FlowNode> out = new ArrayList<>();
        for (JsonNode e : definition.get("executions")) {
            boolean optionalOrg = "organization_login".equals(text(e, "optional"));
            if (optionalOrg && organizationRequirement == null) {
                continue;
            }
            String kind = "flow".equals(text(e, "kind")) ? "flow" : "exec";
            String key = "flow".equals(kind) ? text(e, "alias") : text(e, "provider_id");
            String requirement = text(e, "requirement");
            if ("flow".equals(kind) && (MfaConstants.MFA_FLOW_ALIAS + " Organization").equals(key)) {
                requirement = organizationRequirement;
            }
            String cfgAlias = null;
            Map<String, String> cfg = null;
            if (e.has("config")) {
                cfgAlias = e.get("config").get("alias").asText();
                cfg = new LinkedHashMap<>();
                var it = e.get("config").get("values").fields();
                while (it.hasNext()) {
                    var f = it.next();
                    cfg.put(f.getKey(), f.getValue().asText());
                }
            }
            out.add(new FlowNode(e.get("level").asInt(), kind, key, requirement, text(e, "parent"), cfgAlias, cfg));
        }
        return out;
    }

    /** {@link #plan} flattened for comparison. */
    public List<FlowRow> rows(String organizationRequirement) {
        return plan(organizationRequirement).stream().map(n -> new FlowRow(n.level(), n.kind(), n.key(), n.requirement()))
                .toList();
    }

    // ---- installing ------------------------------------------------------------------------

    /**
     * @param apply          false = report drift only
     * @param rebuildFlow    replace an existing {@code browser-totp} that differs (logins use {@code browser}
     *                       for the few seconds that takes)
     * @param bind           bind the flow as the realm's browser flow
     * @param defaultActions make CONFIGURE_TOTP / recovery codes default actions for NEW users
     */
    public record Options(boolean apply, boolean rebuildFlow, boolean bind, boolean defaultActions) {
        public static Options applyDefaults() {
            return new Options(true, false, true, false);
        }

        public static Options checkOnly() {
            return new Options(false, false, true, false);
        }
    }

    /**
     * @param findings         what differed (and was fixed when {@code applied})
     * @param missingProviders server providers the flow needs that are absent (nothing applied then)
     * @param blocked          why nothing was applied, or null
     */
    public record Report(boolean applied, List<String> findings, List<String> missingProviders, String blocked) {
        public boolean clean() {
            return findings.isEmpty() && blocked == null;
        }
    }

    public Report install(Options options) {
        return executors.serviceAccount().call(realm -> install(realm, options));
    }

    public Report install(RealmResource realm, Options options) {
        List<String> findings = new ArrayList<>();
        List<String> missing = missingProviders(realm);
        if (!missing.isEmpty()) {
            return new Report(false, findings, missing, "This Keycloak does not carry the gorsso-mfa provider JAR "
                    + "and/or the recovery-codes feature (missing: " + String.join(", ", missing) + ").");
        }
        realmSettings(realm, findings, options.apply());
        events(realm, findings, options.apply());
        exemptRole(realm, findings, options.apply());
        requiredActions(realm, findings, options.apply(), options.defaultActions());
        String blocked = flow(realm, findings, options);
        log.info("MFA installer: {} finding(s){}", findings.size(), options.apply() ? " applied" : " (check only)");
        return new Report(options.apply() && blocked == null, findings, List.of(), blocked);
    }

    List<String> missingProviders(RealmResource realm) {
        Set<String> authenticators = new HashSet<>();
        for (Map<String, Object> p : realm.flows().getAuthenticatorProviders()) {
            authenticators.add(String.valueOf(p.get("id")));
        }
        List<String> missing = new ArrayList<>();
        for (String id : MfaConstants.REQUIRED_AUTHENTICATORS) {
            if (!authenticators.contains(id)) {
                missing.add(id);
            }
        }
        if (!authenticators.contains(MfaConstants.RECOVERY_CODE_FORM)) {
            missing.add(MfaConstants.RECOVERY_CODE_FORM);
        }
        return missing;
    }

    @SuppressWarnings("unchecked")
    private void realmSettings(RealmResource realm, List<String> findings, boolean apply) {
        RealmRepresentation rep = realm.toRepresentation();
        Map<String, Object> m = JSON.convertValue(rep, Map.class);
        boolean changed = false;
        var it = definition.get("realm_settings").fields();
        while (it.hasNext()) {
            var f = it.next();
            Object want = JSON.convertValue(f.getValue(), Object.class);
            if (!Objects.equals(m.get(f.getKey()), want)) {
                findings.add("realm " + f.getKey() + ": " + m.get(f.getKey()) + " -> " + want);
                m.put(f.getKey(), want);
                changed = true;
            }
        }
        Map<String, Object> attrs = new HashMap<>(m.get("attributes") instanceof Map<?, ?> a
                ? (Map<String, Object>) a : Map.of());
        var at = definition.get("realm_attributes").fields();
        while (at.hasNext()) {
            var f = at.next();
            if (!Objects.equals(attrs.get(f.getKey()), f.getValue().asText())) {
                findings.add("realm attribute " + f.getKey() + ": " + attrs.get(f.getKey()) + " -> " + f.getValue().asText());
                attrs.put(f.getKey(), f.getValue().asText());
                changed = true;
            }
        }
        Object mode = attrs.get(MfaConstants.MODE_ATTRIBUTE);
        if (mode == null || String.valueOf(mode).isBlank()) {
            findings.add("realm attribute " + MfaConstants.MODE_ATTRIBUTE + ": unset -> 'off' (a super admin turns it on)");
            attrs.put(MfaConstants.MODE_ATTRIBUTE, definition.path("initial_mode_if_unset").asText("off"));
            changed = true;
        }
        m.put("attributes", attrs);
        List<String> locales = new ArrayList<>(m.get("supportedLocales") instanceof List<?> l
                ? (List<String>) l : List.of());
        if (!locales.contains("rw")) {
            findings.add("realm supportedLocales: add 'rw'");
            locales.add("rw");
            m.put("supportedLocales", locales);
            m.put("internationalizationEnabled", true);
            changed = true;
        }
        if (changed && apply) {
            realm.update(JSON.convertValue(m, RealmRepresentation.class));
        }
    }

    private void events(RealmResource realm, List<String> findings, boolean apply) {
        RealmEventsConfigRepresentation cfg = realm.getRealmEventsConfig();
        String listener = definition.get("events").get("listener").asText();
        List<String> listeners = new ArrayList<>(cfg.getEventsListeners() == null ? List.of() : cfg.getEventsListeners());
        List<String> types = new ArrayList<>(cfg.getEnabledEventTypes() == null ? List.of() : cfg.getEnabledEventTypes());
        boolean changed = false;
        if (!listeners.contains(listener)) {
            findings.add("events: add listener '" + listener + "'");
            listeners.add(listener);
            cfg.setEventsListeners(listeners);
            changed = true;
        }
        List<String> wanted = new ArrayList<>();
        definition.get("events").get("enabled_event_types").forEach(t -> wanted.add(t.asText()));
        List<String> missing = wanted.stream().filter(t -> !types.contains(t)).toList();
        if (!types.isEmpty() && !missing.isEmpty()) {
            // An EMPTY list means "all types" to Keycloak — adding to it would narrow it.
            findings.add("events: enable " + String.join(", ", missing));
            types.addAll(missing);
            cfg.setEnabledEventTypes(types);
            changed = true;
        }
        if (!Boolean.TRUE.equals(cfg.isEventsEnabled())) {
            findings.add("events: enable user events");
            cfg.setEventsEnabled(true);
            changed = true;
        }
        if (changed && apply) {
            realm.updateRealmEventsConfig(cfg);
        }
    }

    private void exemptRole(RealmResource realm, List<String> findings, boolean apply) {
        if (directory.realmRole(realm, MfaConstants.EXEMPT_ROLE).isEmpty()) {
            findings.add("realm role '" + MfaConstants.EXEMPT_ROLE + "': create");
            if (apply) {
                RoleRepresentation r = new RoleRepresentation();
                r.setName(MfaConstants.EXEMPT_ROLE);
                r.setDescription(MfaConstants.EXEMPT_ROLE_DESCRIPTION);
                realm.roles().create(r);
            }
        }
    }

    private void requiredActions(RealmResource realm, List<String> findings, boolean apply, boolean defaultActions) {
        AuthenticationManagementResource flows = realm.flows();
        Map<String, RequiredActionProviderRepresentation> registered = new HashMap<>();
        flows.getRequiredActions().forEach(a -> registered.put(a.getAlias(), a));
        for (String alias : MfaConstants.REQUIRED_ACTIONS) {
            RequiredActionProviderRepresentation action = registered.get(alias);
            if (action == null) {
                findings.add("required action " + alias + ": register");
                if (!apply) {
                    continue;
                }
                RequiredActionProviderSimpleRepresentation provider = flows.getUnregisteredRequiredActions().stream()
                        .filter(u -> alias.equals(u.getProviderId())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Keycloak offers no required action " + alias));
                flows.registerRequiredAction(provider);
                action = flows.getRequiredAction(alias);
            }
            if (!action.isEnabled() || action.isDefaultAction() != defaultActions) {
                findings.add("required action " + alias + ": enabled=true defaultAction=" + defaultActions);
                if (apply) {
                    action.setEnabled(true);
                    action.setDefaultAction(defaultActions);
                    flows.updateRequiredAction(alias, action);
                }
            }
        }
    }

    /** Returns why the flow step was not applied, or null. */
    private String flow(RealmResource realm, List<String> findings, Options options) {
        AuthenticationManagementResource flows = realm.flows();
        List<FlowNode> plan = plan(organizationRequirement(flows));
        AuthenticationFlowRepresentation existing = flows.getFlows().stream()
                .filter(f -> MfaConstants.MFA_FLOW_ALIAS.equals(f.getAlias())).findFirst().orElse(null);
        String blocked = null;
        if (existing == null) {
            findings.add("flow '" + MfaConstants.MFA_FLOW_ALIAS + "': create");
            if (options.apply()) {
                build(flows, plan);
            }
        } else {
            List<String> drift = drift(flows, plan);
            findings.addAll(drift);
            if (!drift.isEmpty() && options.apply()) {
                if (!options.rebuildFlow()) {
                    blocked = "'" + MfaConstants.MFA_FLOW_ALIAS + "' exists and differs from the definition; re-run "
                            + "with rebuildFlow to replace it.";
                } else {
                    RealmRepresentation rep = realm.toRepresentation();
                    if (MfaConstants.MFA_FLOW_ALIAS.equals(rep.getBrowserFlow())) {
                        rep.setBrowserFlow(MfaConstants.DEFAULT_BROWSER_FLOW);
                        realm.update(rep);
                    }
                    flows.deleteFlow(existing.getId());
                    build(flows, plan);
                }
            }
        }
        RealmRepresentation rep = realm.toRepresentation();
        if (options.bind() && !MfaConstants.MFA_FLOW_ALIAS.equals(rep.getBrowserFlow())) {
            findings.add("realm browserFlow: " + rep.getBrowserFlow() + " -> " + MfaConstants.MFA_FLOW_ALIAS);
            if (options.apply() && blocked == null) {
                rep.setBrowserFlow(MfaConstants.MFA_FLOW_ALIAS);
                realm.update(rep);
            }
        }
        return blocked;
    }

    /** The Organization step's requirement in the realm's {@code browser} flow (null when absent). */
    String organizationRequirement(AuthenticationManagementResource flows) {
        return flows.getExecutions(MfaConstants.DEFAULT_BROWSER_FLOW).stream()
                .filter(r -> r.getLevel() == 0 && "Organization".equals(r.getDisplayName()))
                .map(AuthenticationExecutionInfoRepresentation::getRequirement).findFirst().orElse(null);
    }

    private void build(AuthenticationManagementResource flows, List<FlowNode> plan) {
        AuthenticationFlowRepresentation top = new AuthenticationFlowRepresentation();
        top.setAlias(MfaConstants.MFA_FLOW_ALIAS);
        top.setProviderId("basic-flow");
        top.setTopLevel(true);
        top.setBuiltIn(false);
        top.setDescription(definition.get("flow").get("description").asText());
        try (Response r = flows.createFlow(top)) {
            if (r.getStatus() >= 300) {
                throw new IllegalStateException("could not create flow: HTTP " + r.getStatus());
            }
        }
        for (FlowNode node : plan) {
            add(flows, node);
        }
    }

    private void add(AuthenticationManagementResource flows, FlowNode node) {
        String parent = node.parent();
        Set<String> before = new HashSet<>();
        flows.getExecutions(parent).forEach(e -> before.add(e.getId()));
        if ("exec".equals(node.kind())) {
            flows.addExecution(parent, Map.of("provider", node.key()));
        } else {
            Map<String, Object> data = new HashMap<>();
            data.put("alias", node.key());
            data.put("type", "basic-flow");
            data.put("description", node.key());
            data.put("provider", "registration-page-form");
            flows.addExecutionFlow(parent, data);
        }
        AuthenticationExecutionInfoRepresentation row = flows.getExecutions(parent).stream()
                .filter(e -> !before.contains(e.getId()) && e.getLevel() == 0).findFirst()
                .orElseThrow(() -> new IllegalStateException("could not identify the execution added under " + parent));
        if (!Objects.equals(row.getRequirement(), node.requirement())) {
            row.setRequirement(node.requirement());
            flows.updateExecutions(parent, row);
        }
        if (node.configAlias() != null) {
            AuthenticatorConfigRepresentation cfg = new AuthenticatorConfigRepresentation();
            cfg.setAlias(node.configAlias());
            cfg.setConfig(new HashMap<>(node.config()));
            try (Response r = flows.newExecutionConfig(row.getId(), cfg)) {
                if (r.getStatus() >= 300) {
                    throw new IllegalStateException("could not configure " + node.configAlias() + ": HTTP " + r.getStatus());
                }
            }
        }
    }

    private List<String> drift(AuthenticationManagementResource flows, List<FlowNode> plan) {
        List<AuthenticationExecutionInfoRepresentation> live = flows.getExecutions(MfaConstants.MFA_FLOW_ALIAS);
        List<FlowRow> have = live.stream().map(e -> e.getAuthenticationFlow() != null && e.getAuthenticationFlow()
                ? new FlowRow(e.getLevel(), "flow", e.getDisplayName(), e.getRequirement())
                : new FlowRow(e.getLevel(), "exec", e.getProviderId(), e.getRequirement())).toList();
        List<FlowRow> want = plan.stream().map(n -> new FlowRow(n.level(), n.kind(), n.key(), n.requirement())).toList();
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < Math.max(have.size(), want.size()); i++) {
            FlowRow a = i < have.size() ? have.get(i) : null;
            FlowRow b = i < want.size() ? want.get(i) : null;
            if (!Objects.equals(a, b)) {
                problems.add("flow step " + i + ": is " + a + ", should be " + b);
                break;
            }
        }
        Map<String, Map<String, String>> wantedConfigs = new HashMap<>();
        plan.forEach(n -> {
            if (n.configAlias() != null) {
                wantedConfigs.put(n.configAlias(), n.config());
            }
        });
        for (AuthenticationExecutionInfoRepresentation e : live) {
            if (e.getAuthenticationConfig() == null) {
                continue;
            }
            AuthenticatorConfigRepresentation cfg = flows.getAuthenticatorConfig(e.getAuthenticationConfig());
            Map<String, String> wantCfg = wantedConfigs.get(cfg.getAlias());
            if (wantCfg != null && !wantCfg.equals(cfg.getConfig())) {
                problems.add("flow config '" + cfg.getAlias() + "': is " + cfg.getConfig() + ", should be " + wantCfg);
            }
        }
        return problems;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
