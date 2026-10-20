package rw.gov.risa.sso.wrapper.policy.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InstitutionRegistryTest {

    private static Map<String, Object> inst(String slug, Object codes) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("slug", slug);
        if (codes != null) {
            m.put("ippis_codes", codes);
        }
        return m;
    }

    private static InstitutionRegistry registry(Object... entries) {
        return InstitutionRegistry.fromRaw(Map.of("institutions", List.of(entries)), "<test>");
    }

    private static final InstitutionRegistry SMALL = registry(
            inst("mineduc", List.of("MINEDUC")),
            inst("minisante", List.of("MINISANTE", "MOH")));

    @Nested
    class CodeLookup {
        @Test
        void mapsCodesCaseInsensitively() {
            assertThat(SMALL.slugForIppisCode("MINEDUC")).contains("mineduc");
            assertThat(SMALL.slugForIppisCode("mineduc")).contains("mineduc");
            assertThat(SMALL.slugForIppisCode("  MOH  ")).contains("minisante");
            assertThat(SMALL.slugForIppisCode("moh")).contains("minisante");
            assertThat(SMALL.slugForIppisCode("MINISANTE")).contains("minisante");
        }

        @Test
        void unknownBlankAndNullAreEmpty() {
            assertThat(SMALL.slugForIppisCode("NOPE")).isEmpty();
            assertThat(SMALL.slugForIppisCode(null)).isEmpty();
            assertThat(SMALL.slugForIppisCode("   ")).isEmpty();
            assertThat(SMALL.slugForIppisCode("")).isEmpty();
        }

        @Test
        void aKnownSlugResolvesToItselfButOnlyExactly() {
            InstitutionRegistry r = registry(inst("rdb", List.of("RDB-HQ")));
            assertThat(r.slugForIppisCode("rdb")).contains("rdb");
            // Not a case-fold shortcut: "RDB" is neither a code nor the exact slug.
            assertThat(r.slugForIppisCode("RDB")).isEmpty();
            assertThat(r.slugForIppisCode("rdb-hq")).contains("rdb");
        }

        @Test
        void aCodeClaimedByTwoSlugsIsUnmappedNeverAnArbitraryWinner() {
            InstitutionRegistry r = registry(inst("minagri", List.of("SHARED")), inst("minisante", List.of("SHARED")));
            assertThat(r.slugForIppisCode("SHARED")).isEmpty();
            assertThat(r.codes()).doesNotContainKey("SHARED");
            assertThat(r.warnings()).anyMatch(w -> w.contains("'SHARED' maps to both 'minagri' and 'minisante'"));
        }

        @Test
        void aContestedCodeStaysUnmappedEvenIfClaimedAgainByTheFirstOwner() {
            InstitutionRegistry r = registry(inst("a", List.of("X")), inst("b", List.of("X")), inst("a2", List.of("X")));
            assertThat(r.slugForIppisCode("X")).isEmpty();
        }

        @Test
        void sameCodeTwiceForOneSlugIsFine() {
            InstitutionRegistry r = registry(inst("a", List.of("X", "x")));
            assertThat(r.slugForIppisCode("X")).contains("a");
        }

        @Test
        void manyToOne() {
            assertThat(SMALL.codes()).containsEntry("MOH", "minisante").containsEntry("MINISANTE", "minisante");
        }

        @Test
        void slugIsReturnedAsTheRegistrySpellsIt() {
            InstitutionRegistry r = registry(inst(" mixed ", List.of("MIX")));
            assertThat(r.slugForIppisCode("mix")).contains("mixed");
        }
    }

    @Nested
    class Tolerance {
        @Test
        void missingListYieldsEmptyRegistryWithWarning() {
            InstitutionRegistry r = InstitutionRegistry.fromRaw(Map.of("version", 1), "f.yaml");
            assertThat(r.isEmpty()).isTrue();
            assertThat(r.warnings()).singleElement().asString().contains("no 'institutions' list");
            assertThat(InstitutionRegistry.fromRaw(null, "x").isEmpty()).isTrue();
            assertThat(InstitutionRegistry.fromRaw(List.of(), "x").isEmpty()).isTrue();
        }

        @Test
        void nonsenseEntriesAreSkipped() {
            Map<String, Object> noSlug = new java.util.HashMap<>();
            noSlug.put("display_name", "x");
            InstitutionRegistry r = registry("a string", noSlug, inst("  ", List.of("A")), inst("ok",
                    java.util.Arrays.asList("", null, 5, "OK")));
            assertThat(r.slugs()).containsExactly("ok");
            assertThat(r.codes()).containsExactly(Map.entry("OK", "ok"));
        }

        @Test
        void nonListCodesAreIgnored() {
            InstitutionRegistry r = registry(inst("ok", "OK"));
            assertThat(r.slugs()).containsExactly("ok");
            assertThat(r.codes()).isEmpty();
        }

        @Test
        void entryDefaults() {
            Map<String, Object> full = inst("minagri", List.of());
            full.put("display_name", "  Ministry  ");
            full.put("status", "suspended");
            InstitutionRegistry r = registry(full, inst("rdb", null));
            assertThat(r.entryFor("minagri")).contains(new RegistryEntry("minagri", "Ministry", "suspended"));
            assertThat(r.entryFor("rdb")).contains(new RegistryEntry("rdb", "rdb", "active"));
            assertThat(r.entryFor("rdb").orElseThrow().isActive()).isTrue();
            assertThat(r.entryFor("nope")).isEmpty();
            assertThat(r.size()).isEqualTo(2);
        }

        @Test
        void decommissionedInstitutionsStillMapTheirCodes() {
            Map<String, Object> e = inst("old", List.of("OLD"));
            e.put("status", "decommissioned");
            assertThat(registry(e).slugForIppisCode("OLD")).contains("old");
        }

        @Test
        void emptyFactory() {
            InstitutionRegistry r = InstitutionRegistry.empty("<missing>");
            assertThat(r.source()).isEqualTo("<missing>");
            assertThat(r.slugForIppisCode("MINEDUC")).isEmpty();
        }
    }

    @Nested
    class LegacyNormalisation {
        @Test
        void uppercaseGroupNamesNormaliseToTheSlug() {
            assertThat(SMALL.slugForLegacyName("MINEDUC")).contains("mineduc");
            assertThat(SMALL.slugForLegacyName(" MiniSante ")).contains("minisante");
            assertThat(SMALL.slugForLegacyName("MOH")).isEmpty(); // a code is not a group name
            assertThat(SMALL.slugForLegacyName(null)).isEmpty();
            assertThat(SMALL.slugForLegacyName(" ")).isEmpty();
        }

        @Test
        void normaliseLowercasesAndTrims() {
            assertThat(Slugs.normalise(" MINAGRI ")).isEqualTo("minagri");
            assertThat(Slugs.normalise("")).isNull();
            assertThat(Slugs.normalise(null)).isNull();
        }
    }

    @Test
    void slugRules() {
        assertThat(Slugs.isValidSlug("minagri")).isTrue();
        assertThat(Slugs.isValidSlug("local-govt")).isTrue();
        assertThat(Slugs.isValidSlug("0abc")).isTrue();
        assertThat(Slugs.isValidSlug("-abc")).isFalse();
        assertThat(Slugs.isValidSlug("MINAGRI")).isFalse();
        assertThat(Slugs.isValidSlug("min_agri")).isFalse();
        assertThat(Slugs.isValidSlug("min.agri")).isFalse();
        assertThat(Slugs.isValidSlug("min agri")).isFalse();
        assertThat(Slugs.isValidSlug("inst:x")).isFalse();
        assertThat(Slugs.isValidSlug("")).isFalse();
        assertThat(Slugs.isValidSlug(null)).isFalse();
        assertThat(Slugs.isHyphenExtension("local", "local-govt")).isTrue();
        assertThat(Slugs.isHyphenExtension("local", "localgovt")).isFalse();
        assertThat(Slugs.isHyphenExtension("local", "local")).isFalse();
    }
}
