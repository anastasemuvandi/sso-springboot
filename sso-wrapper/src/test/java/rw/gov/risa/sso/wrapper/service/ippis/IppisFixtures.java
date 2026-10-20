package rw.gov.risa.sso.wrapper.service.ippis;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.keycloak.representations.idm.UserRepresentation;

import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisEmployment;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisJob;
import rw.gov.risa.sso.wrapper.policy.ippisperson.IppisPerson;
import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistry;

/** Shared test data for the IPPIS feature. */
public final class IppisFixtures {
    private IppisFixtures() {
    }

    public static InstitutionRegistry registry() {
        return InstitutionRegistry.fromRaw(Map.of("institutions", List.of(
                Map.of("slug", "minagri", "display_name", "Ministry of Agriculture", "ippis_codes", List.of("MINAGRI")),
                Map.of("slug", "minisante", "display_name", "Ministry of Health", "ippis_codes",
                        List.of("MINISANTE")),
                Map.of("slug", "mineduc", "ippis_codes", List.of("MINEDUC")))), "test");
    }

    public static IppisProperties props(String webhookSecret) {
        return new IppisProperties("http://ippis.test", "k", Duration.ofSeconds(2), "employee_number", 4, 2,
                Duration.ZERO, 100, false, "test:watermark",
                new IppisProperties.Auth("api_key", "", "", "", "", "", "", ""),
                new IppisProperties.Webhook(webhookSecret, Duration.ofSeconds(300), "test:nonce:"),
                new IppisProperties.Schedule(false, "0 */5 * * * *", "0 30 2 * * *"));
    }

    public static IppisJob open(String id, String start, String position) {
        return IppisJob.of(id, position, LocalDate.parse(start), null, false);
    }

    public static IppisJob closed(String id, String start, String end, String position) {
        return IppisJob.of(id, position, LocalDate.parse(start), LocalDate.parse(end), false);
    }

    public static IppisPerson person(String number, String nid, IppisEmployment... employments) {
        return new IppisPerson(number, nid, null, "0788000" + number.replaceAll("\\D", ""), number.toLowerCase() + "@gov.rw",
                "First", "Last", "F", "S", null, null, java.time.Instant.parse("2026-09-01T08:00:00Z"),
                List.of(employments));
    }

    public static IppisEmployment at(String employer, IppisJob... jobs) {
        return new IppisEmployment(employer, employer + " name", null, null, List.of(jobs));
    }

    public static UserRepresentation user(String id, String employeeNumber, boolean enabled, Map<String, String> attrs) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(id + "@gov.rw");
        u.setEmail(id + "@gov.rw");
        u.setEnabled(enabled);
        Map<String, List<String>> a = new LinkedHashMap<>();
        if (employeeNumber != null) {
            a.put("employee_number", List.of(employeeNumber));
        }
        attrs.forEach((k, v) -> a.put(k, List.of(v)));
        u.setAttributes(a);
        return u;
    }
}
