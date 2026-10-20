package rw.gov.risa.sso.wrapper.service.directory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonProperty;

import rw.gov.risa.sso.wrapper.identity.IdentityStore;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.PermissionTemplate;
import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistry;
import rw.gov.risa.sso.wrapper.policy.registry.RegistryEntry;
import rw.gov.risa.sso.wrapper.service.institution.DelegatedPermissionsHolder;
import rw.gov.risa.sso.wrapper.service.ippis.IppisRegistryProvider;

/**
 * {@code GET /institutions}: the three-way reconciliation (port of
 * {@code services/institution_overview.py}) of what is authorised to exist (the registry), what is
 * provisioned (Keycloak top-level groups + {@code inst:<slug>:*} roles) and what IPPIS sends (the
 * mirror). Read-only; every row carries the command that fixes it. Platform admins only.
 *
 * <p>Legacy UPPERCASE groups ({@code MINAGRI}) are matched case-insensitively against the slug,
 * and the row keeps the group's real name so the legacy casing stays visible.
 */
@Service
public class InstitutionOverviewService {

    private static final Logger log = LoggerFactory.getLogger(InstitutionOverviewService.class);

    public static final String UNKNOWN_CODE = "unknown_code";
    public static final String NOT_ONBOARDED = "not_onboarded";
    public static final String UNREGISTERED_GROUP = "unregistered_group";
    public static final String PARTIAL = "partial";
    public static final String ONBOARDED = "onboarded";
    /** Worst-first: the console's sort order. */
    public static final List<String> STATUS_ORDER = List.of(UNKNOWN_CODE, NOT_ONBOARDED, PARTIAL, UNREGISTERED_GROUP,
            ONBOARDED);
    static final Map<String, String> REMEDY = Map.of(
            PARTIAL, "The group exists but its inst:<slug>:* roles do not. Run `make delegated-apply`, then "
                    + "`make delegated-check` to confirm.",
            NOT_ONBOARDED, "Registered in configs/institutions.yaml but not provisioned in Keycloak. Run `make tf-apply` "
                    + "(docs/runbooks/onboard-institution.md).",
            UNREGISTERED_GROUP, "A top-level Keycloak group with no entry in configs/institutions.yaml. Either add the "
                    + "entry and import the group, or remove it — Terraform does not own it and `make delegated-check` "
                    + "reports it as unmanaged.",
            UNKNOWN_CODE, "IPPIS is sending this institution code and no entry in configs/institutions.yaml claims it, "
                    + "so these employees are filed under no institution. Add the code to the owning institution's "
                    + "`ippis_codes`, or onboard the institution.");
    static final int MAX_MISSING_ROLES = 12;

    private final KeycloakAdminFactory keycloak;
    private final IppisRegistryProvider registry;
    private final DelegatedPermissionsHolder templates;
    private final ObjectProvider<IdentityStore> store;

    public InstitutionOverviewService(KeycloakAdminFactory keycloak, IppisRegistryProvider registry,
                                      DelegatedPermissionsHolder templates, ObjectProvider<IdentityStore> store) {
        this.keycloak = keycloak;
        this.registry = registry;
        this.templates = templates;
        this.store = store;
    }

    public record Row(
            String status,
            String remedy,
            String slug,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("institution_code") String institutionCode,
            @JsonProperty("registry_status") String registryStatus,
            @JsonProperty("in_registry") boolean inRegistry,
            @JsonProperty("in_keycloak") boolean inKeycloak,
            @JsonProperty("group_id") String groupId,
            @JsonProperty("group_name") String groupName,
            @JsonProperty("group_path") String groupPath,
            @JsonProperty("roles_expected") int rolesExpected,
            @JsonProperty("roles_present") int rolesPresent,
            @JsonProperty("missing_roles") List<String> missingRoles,
            long employees,
            @JsonProperty("employees_linked") long employeesLinked,
            @JsonProperty("employees_unlinked") long employeesUnlinked) {
    }

    public record Overview(
            boolean mirror,
            @JsonProperty("registry_source") String registrySource,
            int total,
            @JsonProperty("needs_attention") int needsAttention,
            Map<String, Integer> counts,
            List<Row> items) {
    }

    /** {total, linked} per slug and total per unclaimed code. */
    public record MirrorCounts(Map<String, long[]> bySlug, Map<String, Long> byCode) {
    }

    public Overview overview() {
        InstitutionRegistry reg = registry.registry();
        Map<String, GroupRepresentation> groups = new LinkedHashMap<>();
        Set<String> roles = new LinkedHashSet<>();
        keycloak.asServiceAccount(realm -> {
            for (GroupRepresentation g : realm.groups().groups(0, 10_000)) {
                String name = g.getName() == null ? "" : g.getName().strip();
                if (name.isEmpty()) {
                    continue;
                }
                String key = name.toLowerCase(java.util.Locale.ROOT);
                if (groups.containsKey(key)) {
                    log.warn("institution overview: two top-level groups collapse to '{}' ('{}' and '{}'); reporting "
                            + "the first", key, groups.get(key).getName(), name);
                    continue;
                }
                groups.put(key, g);
            }
            for (RoleRepresentation r : realm.roles().list()) {
                if (r.getName() != null) {
                    roles.add(r.getName());
                }
            }
            return null;
        });
        IdentityStore s = store.getIfAvailable();
        MirrorCounts counts = s == null ? new MirrorCounts(Map.of(), Map.of()) : mirrorCounts(s);
        List<Row> rows = buildRows(reg.entries(), groups, roles, counts, this::expectedRoles);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        STATUS_ORDER.forEach(st -> byStatus.put(st, 0));
        rows.forEach(r -> byStatus.merge(r.status(), 1, Integer::sum));
        return new Overview(s != null, reg.source(), rows.size(),
                (int) rows.stream().filter(r -> !ONBOARDED.equals(r.status())).count(), byStatus, rows);
    }

    MirrorCounts mirrorCounts(IdentityStore s) {
        Map<String, long[]> bySlug = new TreeMap<>();
        Map<String, Long> byCode = new TreeMap<>();
        s.jdbc().query("""
                SELECT i.short_code, i.ippis_institution_id, count(*) AS total,
                       count(*) FILTER (WHERE l.keycloak_user_id IS NOT NULL) AS linked
                FROM employment em JOIN employee e ON em.employee_id = e.id
                JOIN institution i ON em.institution_id = i.id
                LEFT JOIN employee_sso_link l ON l.employee_id = e.id
                GROUP BY i.short_code, i.ippis_institution_id
                """, rs -> {
            String slug = rs.getString(1);
            String code = rs.getString(2);
            long total = rs.getLong(3);
            long linked = rs.getLong(4);
            if (slug != null) {
                long[] prev = bySlug.computeIfAbsent(slug, k -> new long[2]);
                prev[0] += total;
                prev[1] += linked;
            } else if (code != null) {
                byCode.merge(code, total, Long::sum);
            }
        });
        return new MirrorCounts(bySlug, byCode);
    }

    /** The inst:<slug>:* roles the templates require; empty (presence check) when they are unavailable. */
    List<String> expectedRoles(String slug) {
        DelegatedPermissionRegistry r;
        try {
            r = templates.current();
        } catch (RuntimeException e) {
            log.warn("institution overview: delegated templates unavailable ({}); falling back to a presence check",
                    e.getMessage());
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (PermissionTemplate t : r.enabledTemplates(slug)) {
            out.addAll(t.rolesToProvision(slug));
        }
        return List.copyOf(out);
    }

    /** Pure merge of the three sources, sorted worst-first. */
    static List<Row> buildRows(Map<String, RegistryEntry> entries, Map<String, GroupRepresentation> groupsByLowerName,
                               Set<String> realmRoles, MirrorCounts counts,
                               java.util.function.Function<String, List<String>> expected) {
        List<Row> rows = new ArrayList<>();
        Set<String> claimed = new LinkedHashSet<>();
        for (String slug : new TreeMap<>(entries).keySet()) {
            RegistryEntry entry = entries.get(slug);
            GroupRepresentation g = groupsByLowerName.get(slug.toLowerCase(java.util.Locale.ROOT));
            if (g != null) {
                claimed.add(slug.toLowerCase(java.util.Locale.ROOT));
            }
            List<String> exp = expected.apply(slug);
            String status;
            List<String> missing;
            if (g == null) {
                status = NOT_ONBOARDED;
                missing = exp;
            } else if (!exp.isEmpty()) {
                missing = exp.stream().filter(r -> !realmRoles.contains(r)).toList();
                status = missing.isEmpty() ? ONBOARDED : PARTIAL;
            } else {
                String prefix = "inst:" + slug + ":";
                status = realmRoles.stream().anyMatch(r -> r.startsWith(prefix)) ? ONBOARDED : PARTIAL;
                missing = List.of();
            }
            long[] c = counts.bySlug().getOrDefault(slug, new long[2]);
            rows.add(new Row(status, REMEDY.get(status), slug, entry.displayName(), null, entry.status(), true,
                    g != null, g == null ? null : g.getId(), g == null ? null : g.getName(),
                    g == null ? null : g.getPath(), exp.size(), exp.size() - missing.size(),
                    missing.subList(0, Math.min(MAX_MISSING_ROLES, missing.size())), c[0], c[1], c[0] - c[1]));
        }
        for (Map.Entry<String, GroupRepresentation> e : new TreeMap<>(groupsByLowerName).entrySet()) {
            if (claimed.contains(e.getKey())) {
                continue;
            }
            GroupRepresentation g = e.getValue();
            String name = g.getName() == null ? e.getKey() : g.getName();
            rows.add(new Row(UNREGISTERED_GROUP, REMEDY.get(UNREGISTERED_GROUP), null, name, null, null, false, true,
                    g.getId(), name, g.getPath(), 0, 0, List.of(), 0, 0, 0));
        }
        for (Map.Entry<String, Long> e : new TreeMap<>(counts.byCode()).entrySet()) {
            rows.add(new Row(UNKNOWN_CODE, REMEDY.get(UNKNOWN_CODE), null, null, e.getKey(), null, false, false, null,
                    null, null, 0, 0, List.of(), e.getValue(), 0, e.getValue()));
        }
        rows.sort(Comparator.<Row>comparingInt(r -> STATUS_ORDER.indexOf(r.status()))
                .thenComparing(Comparator.comparingLong(Row::employees).reversed())
                .thenComparing(r -> firstNonNull(r.slug(), r.institutionCode(), r.displayName()).toLowerCase(
                        java.util.Locale.ROOT)));
        return rows;
    }

    private static String firstNonNull(String... v) {
        for (String s : v) {
            if (s != null) {
                return s;
            }
        }
        return "";
    }
}
