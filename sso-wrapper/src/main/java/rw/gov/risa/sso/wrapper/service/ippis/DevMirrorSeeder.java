package rw.gov.risa.sso.wrapper.service.ippis;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.identity.MatchState;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisOutcome;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisReconcilePolicy;
import rw.gov.risa.sso.wrapper.policy.ippisperson.ReconcileOutcome;
import rw.gov.risa.sso.wrapper.service.lifecycle.KeycloakUserGateway;
import rw.gov.risa.sso.wrapper.service.lifecycle.UserOps;

/**
 * Seeds the DEV mirror with {@link DevRoster} THROUGH THE REAL WRITER (so the append-only rule,
 * reassignment refusal and short_code handling are exercised, not bypassed). Keycloak is READ only:
 * the linkage comes from each realm user's {@code employee_number}. Idempotent. Refuses to run in
 * staging/prod. Run with {@code seed-identity-mirror [--dry-run]} or the {@code seed-mirror} profile.
 */
@Component
public class DevMirrorSeeder {

    private final IdentityMirrorWriter writer;
    private final KeycloakUserGateway gateway;
    private final IppisProperties props;
    private final SsoProperties sso;

    public DevMirrorSeeder(IdentityMirrorWriter writer, KeycloakUserGateway gateway, IppisProperties props,
                           SsoProperties sso) {
        this.writer = writer;
        this.gateway = gateway;
        this.props = props;
        this.sso = sso;
    }

    /** @return exit code: 0 ok, 1 some writes failed, 2 not runnable here */
    public int seed(boolean dryRun, PrintStream out) {
        if (sso.isProductionLike()) {
            out.println("ERROR: refusing to seed invented employees into a '" + sso.environment() + "' mirror.");
            return 2;
        }
        if (!writer.configured() && !dryRun) {
            out.println("ERROR: no mirror database. Set IDENTITY_DATABASE_URL "
                    + "(docs/runbooks/create-sso-identity-db.md); the schema is migrated by Flyway at startup.");
            return 2;
        }
        Map<String, List<String>> index = keycloakIndex(out);
        List<IppisPerson> roster = DevRoster.roster();
        out.println(roster.size() + " employee(s) to record; "
                + roster.stream().filter(p -> index.containsKey(p.id())).count() + " already have an account.");
        if (dryRun) {
            for (IppisPerson p : roster) {
                List<String> hits = index.getOrDefault(p.id(), List.of());
                String state = hits.isEmpty() ? "no account" : hits.size() > 1 ? "AMBIGUOUS" : "linked";
                out.printf("  would record %-8s %s %s [%s] %s%n", p.id(), p.firstName(), p.lastName(),
                        String.join(", ", p.employments().stream().map(e -> e.employerId()).toList()), state);
            }
            out.println("DRY RUN — nothing written.");
            return 0;
        }
        IdentityMirrorWriter.Recorder rec = writer.recorder().orElseThrow();
        int linked = 0;
        String attr = props.employeeNumberAttribute();
        for (IppisPerson p : roster) {
            List<String> hits = index.getOrDefault(p.id(), List.of());
            String error;
            if (hits.isEmpty()) {
                error = rec.record(p, IppisReconcilePolicy.noUser(attr, p.id()), null, MatchState.UNMATCHED);
            } else {
                linked++;
                error = rec.record(p, ReconcileOutcome.of(hits.get(0), IppisOutcome.UPDATED, null), hits.get(0),
                        hits.size() > 1 ? MatchState.AMBIGUOUS : MatchState.MATCHED);
            }
            if (error != null) {
                out.println("  ! " + p.id() + ": " + error.lines().findFirst().orElse(error));
            }
        }
        Map<String, Object> s = rec.summary();
        out.println("recorded " + s.get("recorded") + "  linked " + linked);
        out.println("jobs inserted " + s.get("jobs_inserted") + "  jobs closed " + s.get("jobs_closed") + "  errors "
                + s.get("errors") + "  refused " + s.get("reassignments") + "  conflicts " + s.get("conflicts"));
        if (!((List<?>) s.get("unmapped_employers")).isEmpty()) {
            out.println("unmapped employer(s): " + s.get("unmapped_employers")
                    + " — their staff belong to no institution and are visible only to a platform admin.");
        }
        return rec.errors() > 0 ? 1 : 0;
    }

    /** {employee_number: [user id]} — read-only. Keycloak unreachable = seed everyone unlinked. */
    private Map<String, List<String>> keycloakIndex(PrintStream out) {
        Map<String, List<String>> index = new LinkedHashMap<>();
        try (UserOps ops = gateway.open()) {
            int first = 0;
            while (true) {
                List<UserRepresentation> page = ops.listUsers(first, 100);
                for (UserRepresentation u : page) {
                    Optional.ofNullable(u.getAttributes()).map(a -> a.get(props.employeeNumberAttribute()))
                            .ifPresent(nums -> nums.forEach(n -> index.computeIfAbsent(n, k -> new ArrayList<>())
                                    .add(u.getId())));
                }
                if (page.size() < 100) {
                    break;
                }
                first += 100;
            }
        } catch (RuntimeException e) {
            out.println("WARNING: could not read users from Keycloak (" + e.getClass().getSimpleName()
                    + "); everyone is recorded as having no account.");
        }
        return index;
    }
}
