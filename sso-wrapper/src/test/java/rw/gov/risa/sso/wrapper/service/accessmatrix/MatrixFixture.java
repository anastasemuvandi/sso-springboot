package rw.gov.risa.sso.wrapper.service.accessmatrix;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.Holder;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.RosterEmployee;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.service.clientscope.ClientScopeService.VisibleClient;
import rw.gov.risa.sso.wrapper.service.mail.DeliveryResult;
import rw.gov.risa.sso.wrapper.service.mail.MailSettings;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.provisioning.AccessChangeNotifier;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeMirror;
import rw.gov.risa.sso.wrapper.service.provisioning.EmployeeProvisioningService;
import rw.gov.risa.sso.wrapper.service.provisioning.ProvisioningFixtures;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService;
import rw.gov.risa.sso.wrapper.support.TestCallers;

/**
 * A minagri institution for the matrix tests:
 * <pre>
 *   E1001 Alice  — account u-a (member), NID on file, holds minagri-a by name
 *   E1002 Bob    — no account
 *   E1003 Carol  — LEAVER, account u-c
 *   E1004 Dan    — mirror links an account OUTSIDE the institution (elsewhere: locked)
 *   E1005/E1006  — both linked to u-shared (ambiguous: locked)
 * columns: minagri-a (manageable), minagri-b (manageable), minagri-ro (view only)
 * </pre>
 */
class MatrixFixture {

    static final String NID = "1198780012345678";

    final MatrixKeycloak kc = mock(MatrixKeycloak.class);
    final EmployeeMirror mirror = mock(EmployeeMirror.class);
    final EmployeeProvisioningService provisioning = mock(EmployeeProvisioningService.class);
    final SetupLinkService setup = mock(SetupLinkService.class);
    final AuditEmitter audit = mock(AuditEmitter.class);
    final Mailer mailer = mock(Mailer.class);
    final AccessChangeNotifier notifier = new AccessChangeNotifier(mailer);
    final AccessMatrixService service = new AccessMatrixService(kc, mirror, provisioning, setup, notifier, audit);
    final InstitutionContext ctx = ProvisioningFixtures.minagri();
    final CallerPrincipal caller = TestCallers.caller("minagri.admin", java.util.Set.of("inst:minagri:admin"),
            "minagri");
    final Map<String, UserRepresentation> members = new LinkedHashMap<>();
    final List<RosterEmployee> roster = new ArrayList<>();
    final Map<String, Map<String, Holder>> holders = new LinkedHashMap<>();

    MatrixFixture() {
        roster.add(new RosterEmployee("E1001", "Alice", "Uwase", "alice@minagri.gov.rw", NID, "active", "F", "u-a",
                "minagri"));
        roster.add(new RosterEmployee("E1002", "Bob", "Habimana", "bob@minagri.gov.rw", null, "active", "M", null,
                "minagri"));
        roster.add(new RosterEmployee("E1003", "Carol", "Leaver", "carol@minagri.gov.rw", null, "terminated", null,
                "u-c", "minagri"));
        roster.add(new RosterEmployee("E1004", "Dan", "Moved", "dan@minagri.gov.rw", null, "active", null, "u-x",
                "minagri"));
        roster.add(new RosterEmployee("E1005", "Eve", "One", null, null, "active", null, "u-shared", "minagri"));
        roster.add(new RosterEmployee("E1006", "Fay", "Two", null, null, "active", null, "u-shared", "minagri"));
        members.put("u-a", ProvisioningFixtures.user("u-a", "alice@minagri.gov.rw", "minagri"));
        members.put("u-c", ProvisioningFixtures.user("u-c", "carol@minagri.gov.rw", "minagri"));
        members.put("u-shared", ProvisioningFixtures.user("u-shared", "shared", "minagri"));
        holders.put("minagri-a", new LinkedHashMap<>(Map.of("u-a", new Holder(true, List.of(), List.of(), false))));
        holders.put("minagri-b", new LinkedHashMap<>());
        holders.put("minagri-ro", new LinkedHashMap<>());

        when(mirror.roster("minagri")).thenReturn(roster);
        when(kc.members(eq(ctx), any())).thenReturn(members);
        when(kc.visibleClients(ctx)).thenReturn(List.of(app("minagri-a", true), app("minagri-b", true),
                app("minagri-ro", false)));
        when(kc.holders(eq(ctx), any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenAnswer(i -> holders.getOrDefault(i.getArgument(2, String.class), Map.of()));
        when(mailer.settings()).thenReturn(new MailSettings("from@gov.rw", "http://login", true));
        when(mailer.configured()).thenReturn(true);
        when(mailer.sendAccessGranted(any())).thenReturn(DeliveryResult.sent());
        when(mailer.sendAccessRevoked(any())).thenReturn(DeliveryResult.sent());
        when(provisioning.maxBatch()).thenReturn(100);
    }

    static VisibleClient app(String clientId, boolean manageable) {
        ClientRepresentation c = ProvisioningFixtures.client(clientId, "email");
        return new VisibleClient(c, ClientScope.forInstitution(clientId, "minagri"), false, manageable, true);
    }
}
