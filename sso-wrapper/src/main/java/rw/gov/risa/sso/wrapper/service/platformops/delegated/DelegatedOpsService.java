package rw.gov.risa.sso.wrapper.service.platformops.delegated;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedAudit;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionConfigException;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.GuardrailViolation;
import rw.gov.risa.sso.wrapper.policy.delegated.InstitutionPreview;
import rw.gov.risa.sso.wrapper.policy.delegated.RoleProvisioningPlan;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContextResolver;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedPermissionsHolder;
import rw.gov.risa.sso.wrapper.service.institution.DelegationProperties;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;

/**
 * The Keycloak half of the delegated-permission templates (port of
 * {@code services/delegated_permissions_service.py}): describe / preview, reload, apply (create each
 * institution's {@code inst:<slug>:*} roles; additive, never deletes) and audit (guardrail violations,
 * console-made grants, missing roles). The pure halves are {@link RoleProvisioningPlan} and
 * {@link DelegatedAudit}. Platform-admin work: everything runs as the service account.
 *
 * <p>Methods that take a {@link DelegatedPermissionRegistry} are what the CLI commands use (they may
 * run against an explicit {@code --config} file); the HTTP routes use the process-wide one.
 */
@Service
public class DelegatedOpsService {

    private static final Logger log = LoggerFactory.getLogger(DelegatedOpsService.class);
    static final Pattern SLUG = Pattern.compile("^[a-z0-9-]+$");
    /** Top-level groups that are never institutions. */
    static final java.util.Set<String> NON_INSTITUTION_GROUPS = java.util.Set.of("administrators", "platform-admins");

    private final DelegatedPermissionsHolder holder;
    private final DelegationProperties props;
    private final KeycloakExecutors executors;
    private final String realmName;

    public DelegatedOpsService(DelegatedPermissionsHolder holder, DelegationProperties props,
                               KeycloakExecutors executors, SsoProperties sso) {
        this.holder = holder;
        this.props = props;
        this.executors = executors;
        this.realmName = sso.keycloak().realm();
    }

    // ---- read model ------------------------------------------------------------------------

    /** The process-wide registry; a missing/unsafe config is the operator's input → 400. */
    public DelegatedPermissionRegistry registry() {
        try {
            return holder.current();
        } catch (DelegatedPermissionConfigException e) {
            throw badConfig(e);
        }
    }

    /** {@code GET /delegated-permissions}: the whole configuration. */
    public Map<String, Object> describe() {
        return registry().describe();
    }

    /** {@code GET /delegated-permissions/institutions/{slug}}: one institution's effective grant. */
    public Map<String, Object> preview(String slug) {
        DelegatedPermissionRegistry r = registry();
        InstitutionPreview p;
        try {
            p = InstitutionPreview.of(r, slug);
        } catch (DelegatedPermissionConfigException e) {
            throw badConfig(e);
        }
        List<Map<String, Object>> templates = new ArrayList<>();
        for (InstitutionPreview.Tier t : p.templates()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("display_name", t.displayName());
            m.put("grant_role", t.grantRole());
            m.put("capabilities", t.capabilities());
            m.put("realm_roles", t.realmRoles());
            templates.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institution", p.institution());
        out.put("templates", templates);
        out.put("effective_grant", p.effectiveGrant().describe());
        return out;
    }

    /** {@code POST /delegated-permissions/reload}: parse first, swap only on success. */
    public Map<String, Object> reload() {
        DelegatedPermissionRegistry r;
        try {
            r = holder.reload();
        } catch (DelegatedPermissionConfigException e) {
            throw badConfig(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "reloaded");
        out.put("source", r.source());
        out.put("templates", r.templates().keySet().stream().sorted().toList());
        return out;
    }

    // ---- apply -----------------------------------------------------------------------------

    /** {@code POST /delegated-permissions/apply} with the process-wide registry. */
    public Map<String, Object> apply(List<String> slugs, boolean dryRun) {
        return describeApply(apply(registry(), slugs, dryRun), dryRun);
    }

    /** The applied plan and the roles actually created. */
    public record ApplyResult(RoleProvisioningPlan plan, List<String> created) {
    }

    /**
     * Create the realm roles each institution's enabled templates require. Idempotent and additive: an
     * existing role is left alone and nothing is ever deleted.
     */
    public ApplyResult apply(DelegatedPermissionRegistry registry, List<String> slugs, boolean dryRun) {
        return executors.serviceAccount().call(realm -> {
            List<String> existing = realm.roles().list().stream().map(RoleRepresentation::getName).toList();
            RoleProvisioningPlan plan;
            try {
                plan = RoleProvisioningPlan.plan(registry, slugs, existing);
            } catch (DelegatedPermissionConfigException e) {
                throw badConfig(e);
            }
            List<String> created = new ArrayList<>();
            if (!dryRun) {
                for (RoleProvisioningPlan.Item item : plan.items()) {
                    if (item.action() != RoleProvisioningPlan.Action.CREATE) {
                        continue;
                    }
                    RoleRepresentation role = new RoleRepresentation();
                    role.setName(item.role());
                    role.setDescription(RoleProvisioningPlan.roleDescription(item, props.permissionsConfig()));
                    try {
                        realm.roles().create(role);
                    } catch (jakarta.ws.rs.ClientErrorException e) {
                        if (e.getResponse().getStatus() != 409) { // created concurrently: fine
                            throw e;
                        }
                    }
                    created.add(item.role());
                }
            }
            return new ApplyResult(plan, created);
        });
    }

    public static Map<String, Object> describeApply(ApplyResult r, boolean dryRun) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (RoleProvisioningPlan.Item i : r.plan().items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("institution", i.institution());
            m.put("template", i.template());
            m.put("role", i.role());
            m.put("action", i.action().wire());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dry_run", dryRun);
        out.put("institutions", r.plan().institutions());
        out.put("plan", items);
        out.put("created", r.created());
        out.put("unchanged", r.plan().unchanged());
        return out;
    }

    /**
     * The slugs {@code delegated-apply} provisions when none are named: every configured institution plus
     * every top-level group whose name is a canonical slug. Returns {@code [slugs, skippedNonCanonical]}.
     */
    public List<List<String>> discoverInstitutions(DelegatedPermissionRegistry registry) {
        java.util.TreeSet<String> slugs = new java.util.TreeSet<>(registry.configuredInstitutions());
        java.util.TreeSet<String> skipped = new java.util.TreeSet<>();
        executors.serviceAccount().run(realm -> {
            for (GroupRepresentation g : realm.groups().groups(0, 10_000)) {
                String name = g.getName() == null ? "" : g.getName();
                if (NON_INSTITUTION_GROUPS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    continue;
                }
                if (SLUG.matcher(name).matches()) {
                    slugs.add(name);
                } else {
                    skipped.add(name);
                }
            }
        });
        return List.of(List.copyOf(slugs), List.copyOf(skipped));
    }

    // ---- audit -----------------------------------------------------------------------------

    /** {@code GET /delegated-permissions/audit} with the process-wide registry. */
    public Map<String, Object> audit(String institution) {
        return describeAudit(audit(registry(), institution == null || institution.isBlank() ? null
                : List.of(institution.strip())));
    }

    public DelegatedAudit.Report audit(DelegatedPermissionRegistry registry, Collection<String> institutions) {
        return executors.serviceAccount().call(realm -> DelegatedAudit.audit(registry,
                new KeycloakRealmView(realm, registry), institutions, InstitutionContextResolver.PLATFORM_ROLES));
    }

    public Map<String, Object> describeAudit(DelegatedAudit.Report r) {
        List<Map<String, Object>> institutions = new ArrayList<>();
        for (DelegatedAudit.InstitutionAudit ia : r.institutions()) {
            List<Map<String, Object>> tiers = new ArrayList<>();
            for (DelegatedAudit.Tier t : ia.tiers()) {
                List<Map<String, Object>> holders = new ArrayList<>();
                for (DelegatedAudit.TierHolder h : t.holders()) {
                    Map<String, Object> hm = new LinkedHashMap<>();
                    hm.put("user_id", h.userId());
                    hm.put("username", h.username());
                    hm.put("guardrail_ok", h.guardrailOk());
                    holders.add(hm);
                }
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("template", t.template());
                tm.put("grant_role", t.grantRole());
                tm.put("grant_role_exists", t.grantRoleExists());
                tm.put("capabilities", t.capabilities());
                tm.put("holders", holders);
                tiers.add(tm);
            }
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("institution", ia.institution());
            im.put("configured", ia.configured());
            im.put("tiers", tiers);
            institutions.add(im);
        }
        List<Map<String, Object>> violations = new ArrayList<>();
        for (DelegatedAudit.Violation v : r.guardrailViolations()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("institution", v.institution());
            m.put("user_id", v.userId());
            m.put("username", v.username());
            m.put("grant_roles", v.grantRoles());
            m.put("templates", v.templates());
            m.put("forbidden", v.forbidden().stream().map(DelegatedOpsService::violation).toList());
            m.put("is_platform_admin", v.isPlatformAdmin());
            violations.add(m);
        }
        List<Map<String, Object>> unmanaged = new ArrayList<>();
        for (DelegatedAudit.UnmanagedGrant u : r.unmanagedGrants()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("institution", u.institution());
            m.put("template", u.template());
            m.put("role", u.role());
            m.put("reason", u.reason());
            unmanaged.add(m);
        }
        List<Map<String, Object>> missing = new ArrayList<>();
        for (DelegatedAudit.MissingRole mr : r.missingRoles()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("institution", mr.institution());
            m.put("template", mr.template());
            m.put("role", mr.role());
            missing.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("realm", realmName);
        out.put("source", r.source());
        out.put("guardrail_enforced", props.guardrailEnforce());
        out.put("institutions", institutions);
        out.put("guardrail_violations", violations);
        out.put("unmanaged_grants", unmanaged);
        out.put("missing_roles", missing);
        out.put("clean", r.clean());
        return out;
    }

    static Map<String, Object> violation(GuardrailViolation v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", v.kind().wire());
        m.put("role", v.role());
        m.put("client", v.client());
        m.put("detail", v.toString());
        return m;
    }

    static ApiException badConfig(DelegatedPermissionConfigException e) {
        return ApiException.badRequest(ErrorCodes.INVALID_REQUEST, e.getMessage());
    }

    /** {@link DelegatedAudit.RealmView} over the Admin API (effective = composite-expanded roles). */
    static final class KeycloakRealmView implements DelegatedAudit.RealmView {
        private final RealmResource realm;
        private final Map<String, String> forbiddenClientUuids = new TreeMap<>();

        KeycloakRealmView(RealmResource realm, DelegatedPermissionRegistry registry) {
            this.realm = realm;
            for (String clientId : registry.guardrail().forbiddenClientRoles().keySet()) {
                try {
                    List<ClientRepresentation> found = realm.clients().findByClientId(clientId);
                    if (!found.isEmpty()) {
                        forbiddenClientUuids.put(clientId, found.get(0).getId());
                    }
                } catch (RuntimeException e) {
                    log.debug("delegated audit: client {} not found: {}", clientId, e.toString());
                }
            }
        }

        @Override
        public List<String> realmRoles() {
            return realm.roles().list().stream().map(RoleRepresentation::getName).toList();
        }

        @Override
        public List<DelegatedAudit.Holder> membersOf(String realmRole) {
            List<DelegatedAudit.Holder> out = new ArrayList<>();
            try {
                int first = 0;
                while (true) {
                    List<UserRepresentation> page = realm.roles().get(realmRole).getUserMembers(first, 100);
                    page.forEach(u -> out.add(new DelegatedAudit.Holder(u.getId(), u.getUsername())));
                    if (page.size() < 100) {
                        break;
                    }
                    first += 100;
                }
            } catch (RuntimeException e) {
                log.warn("delegated audit: cannot list members of {}: {}", realmRole, e.toString());
            }
            return out;
        }

        @Override
        public DelegatedAudit.HolderRoles rolesOf(String userId) {
            List<String> realmRoles = realm.users().get(userId).roles().realmLevel().listEffective().stream()
                    .map(RoleRepresentation::getName).toList();
            Map<String, List<String>> clientRoles = new LinkedHashMap<>();
            forbiddenClientUuids.forEach((clientId, uuid) -> {
                List<String> names = realm.users().get(userId).roles().clientLevel(uuid).listEffective().stream()
                        .map(RoleRepresentation::getName).toList();
                if (!names.isEmpty()) {
                    clientRoles.put(clientId, names);
                }
            });
            return new DelegatedAudit.HolderRoles(realmRoles, clientRoles);
        }
    }

}
