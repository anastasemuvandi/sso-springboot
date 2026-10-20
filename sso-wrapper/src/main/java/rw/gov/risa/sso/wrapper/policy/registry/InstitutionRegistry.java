package rw.gov.risa.sso.wrapper.policy.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code ippis_codes -> slug} lookup plus the set of known slugs (port of
 * {@code institution_registry.py}).
 *
 * <p>Codes are matched case-insensitively; the slug is returned exactly as the registry spells
 * it. <b>Never fails closed</b>: a missing or malformed registry yields an empty lookup,
 * which the IPPIS sync reads as "map nothing" (it then leaves the institution attribute
 * alone rather than writing a raw code). Problems found while building are reported in
 * {@link #warnings()}; {@link RegistryValidator} is the strict gate.
 */
public final class InstitutionRegistry {

    private final Map<String, String> byCode;
    private final Set<String> slugs;
    private final Map<String, RegistryEntry> entries;
    private final String source;
    private final List<String> warnings;

    public InstitutionRegistry(Map<String, String> byCode, Set<String> slugs, String source,
                               Map<String, RegistryEntry> entries) {
        this(byCode, slugs, source, entries, List.of());
    }

    private InstitutionRegistry(Map<String, String> byCode, Set<String> slugs, String source,
                                Map<String, RegistryEntry> entries, List<String> warnings) {
        this.byCode = Collections.unmodifiableMap(new LinkedHashMap<>(byCode == null ? Map.of() : byCode));
        this.slugs = Collections.unmodifiableSet(new LinkedHashSet<>(slugs == null ? Set.of() : slugs));
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries == null ? Map.of() : entries));
        this.source = source == null ? "<none>" : source;
        this.warnings = List.copyOf(warnings);
    }

    /** An empty registry: every code is unmapped. */
    public static InstitutionRegistry empty(String source) {
        return new InstitutionRegistry(Map.of(), Set.of(), source, Map.of());
    }

    /**
     * Build the lookup from parsed registry YAML (maps/lists/scalars as Jackson or SnakeYAML
     * produce them). Tolerant by design, like {@code InstitutionRegistry.from_dict}:
     * nonsense entries are skipped with a warning; a code claimed by two different slugs is
     * dropped entirely (never an arbitrary winner).
     */
    public static InstitutionRegistry fromRaw(Object raw, String source) {
        String src = source == null ? "<dict>" : source;
        List<String> warnings = new ArrayList<>();
        Object list = raw instanceof Map<?, ?> m ? m.get("institutions") : null;
        if (!(list instanceof List<?> items)) {
            warnings.add("institution registry " + src + ": no 'institutions' list — "
                    + "IPPIS institution codes will not be normalised.");
            return new InstitutionRegistry(Map.of(), Set.of(), src, Map.of(), warnings);
        }

        Map<String, String> byCode = new LinkedHashMap<>();
        Set<String> slugs = new LinkedHashSet<>();
        Map<String, RegistryEntry> described = new LinkedHashMap<>();

        for (Object item : items) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            Object slugRaw = entry.get("slug");
            if (!(slugRaw instanceof String s) || s.isBlank()) {
                continue;
            }
            String slug = s.strip();
            slugs.add(slug);

            String displayName = PyLike.blankToNull(entry.get("display_name"));
            String status = PyLike.blankToNull(entry.get("status"));
            described.put(slug, new RegistryEntry(slug,
                    displayName != null ? displayName : slug,
                    status != null ? status : RegistryEntry.STATUS_ACTIVE));

            // Deliberately not filtered by status (a decommissioned institution's people
            // are still emitted by IPPIS).
            Object codes = entry.get("ippis_codes");
            if (!(codes instanceof List<?> codeList)) {
                continue;
            }
            for (Object codeRaw : codeList) {
                if (!(codeRaw instanceof String code) || code.isBlank()) {
                    continue;
                }
                String key = code.strip().toUpperCase(Locale.ROOT);
                String existing = byCode.get(key);
                if (existing != null && !existing.isEmpty() && !existing.equals(slug)) {
                    warnings.add("institution registry " + src + ": IPPIS code '" + key + "' maps to both '"
                            + existing + "' and '" + slug + "'; treating it as unmapped.");
                    byCode.put(key, "");
                    continue;
                }
                if (existing != null && existing.isEmpty()) {
                    continue;
                }
                byCode.put(key, slug);
            }
        }
        Map<String, String> clean = new LinkedHashMap<>();
        byCode.forEach((k, v) -> {
            if (!v.isEmpty()) {
                clean.put(k, v);
            }
        });
        return new InstitutionRegistry(clean, slugs, src, described, warnings);
    }

    public Set<String> slugs() {
        return slugs;
    }

    /** {@code {UPPERCASE code: slug}}. */
    public Map<String, String> codes() {
        return byCode;
    }

    public Map<String, RegistryEntry> entries() {
        return entries;
    }

    public Optional<RegistryEntry> entryFor(String slug) {
        return Optional.ofNullable(entries.get(slug));
    }

    public int size() {
        return slugs.size();
    }

    public boolean isEmpty() {
        return slugs.isEmpty();
    }

    public String source() {
        return source;
    }

    /** Non-fatal problems seen while building (unmapped-because-contested codes, no list). */
    public List<String> warnings() {
        return warnings;
    }

    public boolean isKnownSlug(String slug) {
        return slug != null && slugs.contains(slug);
    }

    /**
     * The canonical slug for an upstream IPPIS institution code; empty when the code is
     * null, blank or unknown. A code that already <i>is</i> a known slug resolves to itself
     * (idempotent re-sourcing), which is not a case-fold shortcut: {@code "MINEDUC"} resolves
     * only through {@code ippis_codes}.
     */
    public Optional<String> slugForIppisCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String key = code.strip();
        if (key.isEmpty()) {
            return Optional.empty();
        }
        String mapped = byCode.get(key.toUpperCase(Locale.ROOT));
        if (mapped != null && !mapped.isEmpty()) {
            return Optional.of(mapped);
        }
        return slugs.contains(key) ? Optional.of(key) : Optional.empty();
    }

    /**
     * Legacy UPPERCASE normalisation for realm groups / attributes: matches a value
     * case-insensitively against the known slugs ({@code MINAGRI -> minagri}) and returns the
     * registry's spelling. Empty for unknown or blank values.
     */
    public Optional<String> slugForLegacyName(String name) {
        String key = Slugs.normalise(name);
        if (key == null) {
            return Optional.empty();
        }
        for (String slug : slugs) {
            if (slug.toLowerCase(Locale.ROOT).equals(key)) {
                return Optional.of(slug);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "InstitutionRegistry[" + source + ", " + slugs.size() + " institution(s), "
                + byCode.size() + " code(s)]";
    }
}
