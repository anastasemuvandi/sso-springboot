package rw.gov.risa.sso.wrapper.service.provisioning;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import rw.gov.risa.sso.wrapper.config.SsoProperties;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.delegated.ResolvedGrant;
import rw.gov.risa.sso.wrapper.policy.employeeaccounts.EmployeeRecord;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeys;

/** Shared builders for the provisioning / access-matrix unit tests. */
public final class ProvisioningFixtures {

    private ProvisioningFixtures() {
    }

    public static final Set<Capability> ADMIN = EnumSet.allOf(Capability.class);

    /** A delegated admin of {@code slug} ({@code <slug>-*} clients) with {@code caps}. */
    public static InstitutionContext ctx(String slug, Set<Capability> caps) {
        ResolvedGrant grant = new ResolvedGrant(slug, List.of("institution-admin"),
                caps.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(caps), "/" + slug, true,
                List.of("inst:" + slug + ":*"), List.of(), List.of(slug + "-*"), List.of());
        return new InstitutionContext(slug + ".admin", "uid-" + slug + ".admin", slug, grant, List.of(slug), false,
                List.of("inst:" + slug + ":admin"));
    }

    public static InstitutionContext minagri() {
        return ctx("minagri", ADMIN);
    }

    public static SsoProperties sso(boolean debug) {
        return new SsoProperties(debug, "test", "http://wrapper.test",
                new SsoProperties.Keycloak("http://kc", "http://kc", "http://kc", "government-internal", "sso-client",
                        "s", List.of("sso-client", "sso-admin-ui")),
                new SsoProperties.Cors(List.of()));
    }

    /** A client; {@code joinColumn} null = unconfigured (denies everybody). */
    public static ClientRepresentation client(String clientId, String joinColumn) {
        ClientRepresentation c = new ClientRepresentation();
        c.setId("uuid-" + clientId);
        c.setClientId(clientId);
        c.setName(clientId.toUpperCase());
        Map<String, String> attrs = new HashMap<>();
        if (joinColumn != null) {
            attrs.put(JoinKeys.ATTR_JOIN_COLUMN, joinColumn);
        }
        c.setAttributes(attrs);
        return c;
    }

    public static EmployeeRecord employee(String number, String email, String nid, String linkedUserId) {
        return new EmployeeRecord(number, email, "0788000" + number.replaceAll("\\D", "").substring(0, 3), nid,
                "First" + number, "Last" + number, "minagri", "active", linkedUserId);
    }

    public static UserRepresentation user(String id, String username, String institution, String... requiredActions) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(username.contains("@") ? username : username + "@minagri.gov.rw");
        u.setFirstName("F-" + id);
        u.setLastName("L-" + id);
        u.setEnabled(true);
        Map<String, List<String>> attrs = new HashMap<>();
        if (institution != null) {
            attrs.put("institution", List.of(institution));
        }
        u.setAttributes(attrs);
        u.setRequiredActions(new ArrayList<>(List.of(requiredActions)));
        return u;
    }
}
