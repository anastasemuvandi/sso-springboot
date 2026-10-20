package rw.gov.risa.sso.seeder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against the real committed configs/ (two levels up from tools/seeder). */
class SourcesTest {

    private final ObjectMapper json = new ObjectMapper();

    private Sources sources() throws Exception {
        return Sources.load(json, Path.of("../../configs"));
    }

    @Test
    void rolesAreRenderedFromTheTemplates() throws Exception {
        Sources s = sources();
        assertEquals(List.of("inst:minagri:admin", "inst:minagri:hr", "inst:minagri:staff", "inst:minagri:auditor"),
                List.copyOf(s.rolesFor("minagri").keySet()));
        assertEquals("inst:minisante:admin", s.grantRole("institution-admin", "minisante"));
    }

    @Test
    void registryProvidesTheDemoInstitutionsAndTheirApps() throws Exception {
        Sources s = sources();
        assertEquals("minagri-kubaka-web", s.institutions.get("minagri").apps().get(0).clientId("minagri"));
        assertEquals("minisante-hmis", s.institutions.get("minisante").apps().get(0).clientId("minisante"));
        assertEquals(List.of("planning", "extension"), s.institutions.get("minagri").departments());
    }

    @Test
    void peopleCoverEveryTierPlusTheProbes() throws Exception {
        Sources s = sources();
        List<Seeder.Person> people = Seeder.peopleFor(s, s.institutions.get("minagri"), "planning");
        assertEquals(List.of("minagri.admin", "minagri.hr", "minagri.auditor", "minagri.staff", "minagri.planner",
                "minagri.unassigned"), people.stream().map(Seeder.Person::username).toList());
        assertEquals(List.of("inst:minagri:admin"), people.get(0).realmRoles());
        assertEquals("/minagri/planning", people.get(4).groupPath());
        assertEquals(null, people.get(5).groupPath());
    }

    @Test
    void fgapPlanIsFilteredToTheSeededInstitutions() throws Exception {
        Sources s = sources();
        Set<String> seeded = Set.of("minagri", "minisante");
        long kept = 0;
        for (var p : s.fgapPlan.path("permissions")) {
            if (Seeder.inScope(p, seeded)) {
                kept++;
                String inst = p.path("institution").asText();
                assertTrue(seeded.contains(inst) || inst.equals("_shared") || inst.isEmpty(), inst);
            }
        }
        assertTrue(kept > 0);
        assertFalse(Seeder.inScope(json.createObjectNode().put("institution", "rra"), seeded));
    }

    @Test
    void demoPasswordMustSatisfyTheRealmPolicy() {
        Seeder.checkPassword("GorSsoDev#2026");
        assertThrows(IllegalStateException.class, () -> Seeder.checkPassword("short1A"));
        assertThrows(IllegalStateException.class, () -> Seeder.checkPassword("nouppercase123"));
    }

    @Test
    void longDescriptionsAreTrimmedForKeycloak() {
        assertTrue(Seeder.shorten("x".repeat(400)).length() <= 250);
        assertEquals("a b", Seeder.shorten("a \n  b"));
    }
}
