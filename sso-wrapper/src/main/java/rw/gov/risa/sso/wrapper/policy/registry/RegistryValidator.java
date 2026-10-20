package rw.gov.risa.sso.wrapper.policy.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The strict registry gate (port of {@code scripts/validate_institution_registry.py}).
 *
 * <p>Checks slug rules (§5.1), duplicate slugs, IPPIS-code uniqueness (one code → one slug),
 * realm-global client-id collisions, the hyphen-prefix slug collision rule, app/redirect URI
 * rules, and that every assigned delegation tier exists in {@code templates.json}.
 * Operates on already-parsed documents.
 */
public final class RegistryValidator {

    public static final Set<String> VALID_STATUSES = Set.of("active", "suspended", "decommissioned");
    public static final List<String> ENV_SPECIFIC_HOSTS = List.of("localhost", "127.0.0.1", "0.0.0.0");
    private static final List<String> OPEN_REDIRECTS = List.of("*", "http://*", "https://*");
    private static final String SORTED_STATUSES = "['active', 'decommissioned', 'suspended']";

    private RegistryValidator() {
    }

    /**
     * Validate a parsed registry document against a parsed templates document
     * ({@code templatesDoc} may be {@code null} to skip the tier check).
     */
    public static RegistryFindings validate(Object registryDoc, Map<String, ?> templatesDoc) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Findings f = new Findings(errors, warnings);

        if (!(registryDoc instanceof Map<?, ?> doc) || !doc.containsKey("institutions")) {
            f.error("registry has no top-level `institutions:` key.");
            return new RegistryFindings(errors, warnings);
        }
        Object list = doc.get("institutions");
        if (!(list instanceof List<?> entries) || entries.isEmpty()) {
            f.error("registry lists no institutions.");
            return new RegistryFindings(errors, warnings);
        }
        for (int i = 0; i < entries.size(); i++) {
            checkEntry(entries.get(i), i, f);
        }
        checkCrossEntry(entries, f);
        if (templatesDoc != null) {
            checkTiers(entries, templatesDoc, f);
        }
        return new RegistryFindings(errors, warnings);
    }

    /** Tier resolution the wrapper uses: {@code by_institution[slug]} wins, else {@code default}. */
    public static List<String> tiersFor(String slug, Map<String, ?> templatesDoc) {
        Object assignments = templatesDoc == null ? null : templatesDoc.get("assignments");
        if (!(assignments instanceof Map<?, ?> a)) {
            return List.of();
        }
        Object byInst = a.get("by_institution");
        Object chosen = byInst instanceof Map<?, ?> m && m.containsKey(slug) ? m.get(slug) : a.get("default");
        List<String> out = new ArrayList<>();
        if (chosen instanceof List<?> l) {
            l.forEach(t -> out.add(String.valueOf(t)));
        }
        return out;
    }

    private record Findings(List<String> errors, List<String> warnings) {
        void error(String m) {
            errors.add(m);
        }

        void warn(String m) {
            warnings.add(m);
        }
    }

    private static String statusOf(Map<?, ?> entry) {
        return entry.containsKey("status") ? (entry.get("status") instanceof String s ? s : null) : "active";
    }

    private static Object statusRaw(Map<?, ?> entry) {
        return entry.containsKey("status") ? entry.get("status") : "active";
    }

    private static void checkEntry(Object raw, int index, Findings f) {
        String where = "institutions[" + index + "]";
        if (!(raw instanceof Map<?, ?> entry)) {
            f.error(where + " is not a mapping.");
            return;
        }
        Object slugRaw = entry.get("slug");
        if (!PyLike.truthy(slugRaw) || !(slugRaw instanceof String slug)) {
            f.error(where + " has no `slug`.");
            return;
        }
        where = slug;

        if (!Slugs.isValidSlug(slug)) {
            f.error(where + ": invalid slug. ADR-0001 §5.1 requires lowercase [a-z0-9-] "
                    + "starting alphanumeric — no spaces, dots or underscores.");
        }
        if (!PyLike.truthy(entry.get("display_name"))) {
            f.error(where + ": `display_name` is required.");
        }
        Object status = statusRaw(entry);
        if (!(status instanceof String s) || !VALID_STATUSES.contains(s)) {
            f.error(where + ": status " + PyLike.repr(status) + " is not one of " + SORTED_STATUSES + ".");
        }

        Object codes = entry.get("ippis_codes");
        if (PyLike.truthy(codes) && !(codes instanceof List<?>)) {
            f.error(where + ": `ippis_codes` must be a list.");
        } else if (!PyLike.truthy(codes) && "active".equals(status)) {
            f.warn(where + ": no `ippis_codes`; IPPIS inbound sync cannot map anyone here.");
        }

        if (PyLike.truthy(entry.get("separate_realm")) && !PyLike.truthy(entry.get("justification"))) {
            f.error(where + ": separate_realm requires a `justification` citing the legal "
                    + "mandate (ADR-0001 §6). The exception list is otherwise empty.");
        }

        Object departments = entry.get("departments");
        if (departments instanceof List<?> depts) {
            for (Object dept : depts) {
                if (!(dept instanceof String d) || !Slugs.isValidSlug(d)) {
                    f.error(where + ": department " + PyLike.repr(dept) + " must be lowercase [a-z0-9-].");
                }
            }
        }
        Object apps = entry.get("apps");
        checkApps(slug, PyLike.truthy(apps) ? apps : List.of(), f);
    }

    private static void checkApps(String slug, Object appsRaw, Findings f) {
        if (!(appsRaw instanceof List<?> apps)) {
            f.error(slug + ": `apps` must be a list.");
            return;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (Object appRaw : apps) {
            if (!(appRaw instanceof Map<?, ?> app) || !PyLike.truthy(app.get("name"))) {
                f.error(slug + ": every app needs a `name`.");
                continue;
            }
            String name = String.valueOf(app.get("name"));
            if (!Slugs.isValidAppName(name)) {
                f.error(slug + ": app name " + PyLike.repr(name) + " must be lowercase [a-z0-9-].");
            }
            if (seen.contains(name)) {
                f.error(slug + ": duplicate app " + PyLike.repr(name) + ".");
            }
            seen.add(name);

            if (name.startsWith(slug + "-")) {
                f.warn(slug + ": app " + PyLike.repr(name) + " already starts with the slug; the client-id "
                        + "becomes '" + slug + "-" + name + "'. Drop the prefix — it is added for you "
                        + "(ADR-0001 §5.3).");
            }

            Object uris = app.get("redirect_uris");
            if (uris instanceof List<?> uriList) {
                for (Object uriRaw : uriList) {
                    String uri = String.valueOf(uriRaw);
                    if (ENV_SPECIFIC_HOSTS.stream().anyMatch(uri::contains)) {
                        f.error(slug + "/" + name + ": redirect URI " + PyLike.repr(uri) + " is environment-specific. "
                                + "Put it in infra/terraform/envs/<env>.tfvars "
                                + "(`extra_redirect_uris`), not in the registry.");
                    }
                    if (OPEN_REDIRECTS.contains(uri.strip())) {
                        f.error(slug + "/" + name + ": redirect URI " + PyLike.repr(uri) + " is an open redirect.");
                    }
                }
            }

            boolean isPublic = PyLike.truthy(app.get("public"));
            if (isPublic && PyLike.truthy(app.get("service_accounts"))) {
                f.error(slug + "/" + name + ": a public client cannot have service accounts.");
            }
            boolean enforce = app.containsKey("enforce_access") ? PyLike.truthy(app.get("enforce_access")) : true;
            if (isPublic && enforce) {
                f.warn(slug + "/" + name + ": public clients carry no access policy, so this one "
                        + "is exempt from per-client enforcement regardless of "
                        + "`enforce_access`. Under ACCESS_DEFAULT_DENY it is unreachable "
                        + "until access is assigned via /authorization.");
            }
            if (PyLike.truthy(app.get("direct_access_grants"))) {
                f.warn(slug + "/" + name + ": direct access grants (password grant) enabled — "
                        + "acceptable in dev, not outside it.");
            }
        }
    }

    private static void checkCrossEntry(List<?> entries, Findings f) {
        Map<String, Integer> slugs = new LinkedHashMap<>();
        Map<String, List<String>> ippis = new TreeMap<>();
        Map<String, List<String>> clientIds = new TreeMap<>();

        for (Object raw : entries) {
            if (!(raw instanceof Map<?, ?> entry)) {
                continue;
            }
            if (!(entry.get("slug") instanceof String slug) || slug.isEmpty()) {
                continue;
            }
            slugs.merge(slug, 1, Integer::sum);

            if (entry.get("ippis_codes") instanceof List<?> codes) {
                for (Object code : codes) {
                    if (code instanceof String c) {
                        ippis.computeIfAbsent(c.toUpperCase(Locale.ROOT), k -> new ArrayList<>()).add(slug);
                    }
                }
            }
            if (!"active".equals(statusOf(entry))) {
                continue;
            }
            if (entry.get("apps") instanceof List<?> apps) {
                for (Object a : apps) {
                    if (a instanceof Map<?, ?> app && PyLike.truthy(app.get("name"))) {
                        clientIds.computeIfAbsent(slug + "-" + app.get("name"), k -> new ArrayList<>()).add(slug);
                    }
                }
            }
        }

        new TreeMap<>(slugs).forEach((slug, count) -> {
            if (count > 1) {
                f.error("slug '" + slug + "' appears " + count + " times; it identifies one institution.");
            }
        });
        ippis.forEach((code, owners) -> {
            Set<String> distinct = new TreeSet<>(owners);
            if (distinct.size() > 1) {
                f.error("IPPIS code '" + code + "' maps to " + PyLike.repr(new ArrayList<>(distinct))
                        + ". Many codes may point at one slug, never the reverse (ADR-0001 §5.2).");
            }
        });
        clientIds.forEach((cid, owners) -> {
            if (owners.size() > 1) {
                f.error("client-id '" + cid + "' claimed by " + PyLike.repr(new ArrayList<>(new TreeSet<>(owners)))
                        + "; client-ids are realm-global.");
            }
        });
        checkSlugPrefixCollisions(new ArrayList<>(new TreeSet<>(slugs.keySet())), f);
    }

    /**
     * No slug may be a hyphen-prefix of another: client scoping is the glob
     * {@code <slug>-*}, so {@code local}'s admins would match {@code local-govt-*}.
     */
    private static void checkSlugPrefixCollisions(List<String> slugs, Findings f) {
        for (String outer : slugs) {
            for (String inner : slugs) {
                if (Slugs.isHyphenExtension(outer, inner)) {
                    f.error("slug '" + inner + "' starts with '" + outer + "' + '-'. Client scoping is "
                            + "a glob ('" + outer + "-*'), so '" + outer + "''s admins would match "
                            + "'" + inner + "''s clients. Choose a slug that is not an extension "
                            + "of an existing one.");
                }
            }
        }
    }

    private static void checkTiers(List<?> entries, Map<String, ?> templatesDoc, Findings f) {
        Object templates = templatesDoc.get("templates");
        Set<String> known = new java.util.HashSet<>();
        if (templates instanceof Map<?, ?> t) {
            t.keySet().forEach(k -> known.add(String.valueOf(k)));
        }
        if (known.isEmpty()) {
            f.error("templates.json defines no templates.");
            return;
        }
        for (Object raw : entries) {
            if (!(raw instanceof Map<?, ?> entry)) {
                continue;
            }
            if (!(entry.get("slug") instanceof String slug) || slug.isEmpty()) {
                continue;
            }
            List<String> tiers = tiersFor(slug, templatesDoc);
            for (String tier : tiers) {
                if (!known.contains(tier)) {
                    f.error(slug + ": delegation tier '" + tier + "' is not defined in templates.json.");
                }
            }
            if ("active".equals(statusOf(entry)) && tiers.isEmpty()) {
                f.warn(slug + ": no delegation tiers assigned, so it gets no inst:" + slug + ":* "
                        + "roles and no local admin can ever be appointed.");
            }
        }
    }
}
