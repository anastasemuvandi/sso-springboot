package rw.gov.risa.sso.wrapper.service.platformops.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import rw.gov.risa.sso.wrapper.service.platformops.fgap.AdminRest;

/**
 * The Keycloak-direct acceptance test (port of {@code kc26_spike.py verify}, ADR-0002 §6 step 8; spec
 * §5.3): log in as a REAL institution admin and call the Keycloak Admin API DIRECTLY, with no wrapper in
 * the path. Every refusal must come from Keycloak itself (FGAP v2). Nine cases:
 *
 * <ol>
 *   <li>list members of own group {@code /<mine>} → 200</li>
 *   <li>list members of own department {@code /<mine>/<dept>} → 200 (subgroups are enumerated)</li>
 *   <li>list members of the OTHER institution's group → 403</li>
 *   <li>list the whole realm's users → 403</li>
 *   <li>assign {@code inst:<mine>:staff} to own staff → 204</li>
 *   <li>assign {@code inst:<mine>:admin} (withheld by roles.deny) → 403</li>
 *   <li>assign {@code super_admin} (the guardrail) → 403</li>
 *   <li>assign {@code inst:<mine>:staff} to the OTHER institution's staff → 403 (bounds the all-users
 *       Users/map-roles grant)</li>
 *   <li>assign {@code inst:<theirs>:staff} (another institution's role) → 403</li>
 * </ol>
 *
 * Unlike the reference, a case that cannot be set up (a missing group, role or user) is reported as
 * FAILED, not silently dropped: "9/9" means nine cases actually ran. Lookups use a separate admin identity
 * ({@code lookup}); only the calls under test use the institution admin's token. A successful
 * {@code inst:<mine>:staff} assignment (case 5) is undone afterwards with the lookup identity.
 */
public final class KeycloakDirectVerifier {

    /** One case: its name, the accepted statuses, what came back ({@code -1} = could not run), a note. */
    public record Case(String name, Set<Integer> expect, int got, String note) {
        public boolean passed() {
            return expect.contains(got);
        }

        public String line() {
            String exp = String.join("/", expect.stream().sorted().map(String::valueOf).toList());
            return "  [" + (passed() ? "PASS" : "FAIL") + "] " + name + "\n         expected " + exp + ", got "
                    + (got < 0 ? "nothing (could not run)" : String.valueOf(got))
                    + (note == null || note.isEmpty() ? "" : "  — " + note);
        }
    }

    public record Report(String actingAs, String baseUrl, List<Case> cases) {
        public long passed() {
            return cases.stream().filter(Case::passed).count();
        }

        public boolean allPassed() {
            return cases.size() == EXPECTED_CASES && passed() == cases.size();
        }

        public String summary() {
            return passed() + "/" + cases.size() + " passed";
        }
    }

    public static final int EXPECTED_CASES = 9;

    /**
     * @param mine        the institution whose admin logs in
     * @param theirs      another institution (isolation is proved against it)
     * @param department  a department of {@code mine} ({@code /<mine>/<department>}); null = the first child group
     * @param userToken   the institution admin's access token (password grant)
     */
    public record Options(String mine, String theirs, String department, String actingAs, String userToken) {
    }

    private final AdminRest lookup;

    /** @param lookup an admin identity used ONLY to find ids (never for the calls under test) */
    public KeycloakDirectVerifier(AdminRest lookup) {
        this.lookup = lookup;
    }

    public Report verify(Options o) {
        AdminRest asAdmin = lookup.as(o::userToken);
        String mine = o.mine().toLowerCase(Locale.ROOT);
        String theirs = o.theirs().toLowerCase(Locale.ROOT);
        JsonNode myGroup = lookup.findGroup("/" + mine);
        JsonNode theirGroup = lookup.findGroup("/" + theirs);
        JsonNode myDepartment = department(myGroup, mine, o.department());
        JsonNode target = lookup.findUser(mine + ".staff");
        JsonNode victim = lookup.findUser(theirs + ".staff");
        JsonNode ownRole = lookup.findRealmRole("inst:" + mine + ":staff");
        JsonNode adminRole = lookup.findRealmRole("inst:" + mine + ":admin");
        JsonNode broad = lookup.findRealmRole("super_admin");
        JsonNode theirRole = lookup.findRealmRole("inst:" + theirs + ":staff");

        List<Case> cases = new ArrayList<>();
        cases.add(call(asAdmin, "list members of own group /" + mine, Set.of(200), "GET",
                myGroup == null ? null : "groups/" + id(myGroup) + "/members", null, "", "/" + mine + " missing"));
        cases.add(call(asAdmin, "list members of own department "
                        + (myDepartment == null ? "/" + mine + "/<department>" : myDepartment.path("path").asText())
                        + "  [ADR-0002 §9.1]", Set.of(200), "GET",
                myDepartment == null ? null : "groups/" + id(myDepartment) + "/members", null,
                "if this 403s, subgroups do NOT propagate — use SubgroupMode.ENUMERATE", "no department of /" + mine));
        cases.add(call(asAdmin, "list members of OTHER institution /" + theirs, Set.of(403), "GET",
                theirGroup == null ? null : "groups/" + id(theirGroup) + "/members", null,
                "cross-institution read must be refused", "/" + theirs + " missing"));
        cases.add(call(asAdmin, "list the whole realm's users", Set.of(403), "GET", "users", null,
                "realm-wide read must be refused", null));

        String targetPath = target == null ? null : "users/" + id(target) + "/role-mappings/realm";
        Case assignOwn = call(asAdmin, "assign inst:" + mine + ":staff to own staff", Set.of(204), "POST",
                ownRole == null ? null : targetPath, ref(ownRole), "", mine + ".staff or inst:" + mine + ":staff missing");
        cases.add(assignOwn);
        cases.add(call(asAdmin, "assign inst:" + mine + ":admin (withheld by roles.deny)", Set.of(403), "POST",
                adminRole == null ? null : targetPath, ref(adminRole), "delegation must not be self-perpetuating",
                "inst:" + mine + ":admin missing"));
        cases.add(call(asAdmin, "assign super_admin (the guardrail's whole point)", Set.of(403), "POST",
                broad == null ? null : targetPath, ref(broad), "", "realm role super_admin missing"));
        cases.add(call(asAdmin, "assign inst:" + mine + ":staff to a " + theirs.toUpperCase(Locale.ROOT)
                        + " user (own role, other's person)", Set.of(403), "POST",
                victim == null || ownRole == null ? null : "users/" + id(victim) + "/role-mappings/realm", ref(ownRole),
                "bounds the all-users Users/map-roles grant", theirs + ".staff missing"));
        cases.add(call(asAdmin, "assign inst:" + theirs + ":staff (another institution's role)", Set.of(403), "POST",
                theirRole == null ? null : targetPath, ref(theirRole), "", "inst:" + theirs + ":staff missing"));

        // Leave the realm as found: undo the assignment case 5 made (and any a broken boundary let through).
        if (targetPath != null) {
            for (JsonNode role : new JsonNode[] {ownRole, adminRole, broad, theirRole}) {
                if (role != null) {
                    lookup.request("DELETE", targetPath, ref(role));
                }
            }
        }
        if (victim != null && ownRole != null) {
            lookup.request("DELETE", "users/" + id(victim) + "/role-mappings/realm", ref(ownRole));
        }
        return new Report(o.actingAs(), lookup.baseUrl(), cases);
    }

    private JsonNode department(JsonNode myGroup, String mine, String department) {
        if (department != null && !department.isBlank()) {
            return lookup.findGroup("/" + mine + "/" + department.strip());
        }
        if (myGroup == null) {
            return null;
        }
        JsonNode children = lookup.get("groups/" + id(myGroup) + "/children?first=0&max=10").body();
        return children != null && children.isArray() && !children.isEmpty() ? children.get(0) : null;
    }

    private static Case call(AdminRest as, String name, Set<Integer> expect, String method, String path, Object body,
                             String note, String missing) {
        if (path == null) {
            return new Case(name, expect, -1, missing == null ? "set-up failed" : missing);
        }
        return new Case(name, expect, as.request(method, path, body).status(), note);
    }

    private static List<Map<String, Object>> ref(JsonNode role) {
        return role == null ? null : List.of(Map.of("id", role.path("id").asText(), "name", role.path("name").asText()));
    }

    private static String id(JsonNode n) {
        return n.path("id").asText();
    }

    /** The printed report, as the reference prints it. */
    public static List<String> render(Report r) {
        List<String> out = new ArrayList<>();
        out.add("Acting as " + r.actingAs() + ", directly against " + r.baseUrl() + " (no wrapper)");
        out.add("");
        r.cases().forEach(c -> out.add(c.line()));
        out.add("");
        out.add(r.summary() + (r.cases().size() != EXPECTED_CASES ? " (expected " + EXPECTED_CASES + " cases)" : ""));
        if (r.allPassed()) {
            out.add("");
            out.add("Keycloak enforced every case with no wrapper in the path.");
        } else {
            out.add("");
            out.add("FAILURES ABOVE. A 403 where 200 was expected usually means a permission was not created (run "
                    + "fgap-apply / tf-apply); a 200 where 403 was expected means the boundary is NOT being "
                    + "enforced — stop and investigate.");
        }
        return out;
    }
}
