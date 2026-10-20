package rw.gov.risa.sso.wrapper.policy.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditTest {

    @Test
    void eventWireStringsAreUniqueAndMatchTheReference() {
        List<String> wires = Arrays.stream(AuditEventType.values()).map(AuditEventType::wire).toList();
        assertThat(new HashSet<>(wires)).hasSameSizeAs(wires);
        assertThat(AuditEventType.LOGIN_DENIED.wire()).isEqualTo("login_denied");
        assertThat(AuditEventType.SYNC_ATTRIBUTES.wire()).isEqualTo("ippis.sync.attributes");
        assertThat(AuditEventType.SETUP_LINK_ADMIN_RESEND.wire()).isEqualTo("admin.setup_link.resend");
        assertThat(AuditEventType.EMPLOYEE_NID_REVEALED.wire()).isEqualTo("admin.employee.nid_revealed");
        assertThat(AuditEventType.fromWire("admin.access_matrix.imported")).contains(AuditEventType.ACCESS_MATRIX_IMPORTED);
        assertThat(AuditEventType.fromWire("nope")).isEmpty();
    }

    @Test
    void auditServiceMapping() {
        assertThat(AuditEventType.LOGIN_SUCCESS.auditServiceType()).isEqualTo("auth.login.success");
        assertThat(AuditEventType.LOGIN_FAILURE.auditServiceType()).isEqualTo("auth.login.failed");
        assertThat(AuditEventType.LOGIN_DENIED.auditServiceType()).isEqualTo("authz.access.denied");
        assertThat(AuditEventType.TOKEN_ISSUED.auditServiceType()).isEqualTo("authz.token.validated");
        assertThat(AuditEventType.TOKEN_REFRESHED.auditServiceType()).isEqualTo("session.refreshed");
        assertThat(AuditEventType.TOKEN_REJECTED.auditServiceType()).isEqualTo("authz.token.invalid");
        assertThat(AuditEventType.AUTHORIZE_START.auditServiceType()).isEqualTo("auth.session.created");
        assertThat(AuditEventType.LOGOUT_REDIRECT_DENIED.auditServiceType()).isEqualTo("authz.access.denied");
        // Unmapped types pass through unchanged.
        assertThat(AuditEventType.ADMIN_USER_CREATED.auditServiceType()).isEqualTo("admin.user.created");
        assertThat(AuditEventType.LIFECYCLE_REHIRE.auditServiceType()).isEqualTo("lifecycle.rehire");
    }

    @Test
    void severityRule() {
        assertThat(AuditOutcome.severityFor("failure")).isEqualTo("WARNING");
        assertThat(AuditOutcome.severityFor("denied")).isEqualTo("WARNING");
        assertThat(AuditOutcome.severityFor("success")).isEqualTo("INFO");
        assertThat(AuditOutcome.severityFor(null)).isEqualTo("INFO");
    }

    @Test
    void payloadPromotesInstitutionAndTargetButKeepsThemInDetails() {
        Map<String, Object> p = AuditPayloads.build(AuditEventType.LOGIN_DENIED, "denied", "alice", "minagri-web",
                "sess", "10.0.0.1", Map.of("institution", " minagri ", "target_client", "minagri-web", "reason", "x"));
        assertThat(p).containsEntry("event_type", "authz.access.denied").containsEntry("user_id", "alice")
                .containsEntry("performed_by", "alice").containsEntry("app_id", "minagri-web")
                .containsEntry("ip_address", "10.0.0.1").containsEntry("institution", "minagri")
                .containsEntry("target", "minagri-web").containsEntry("severity", "WARNING");
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) p.get("details");
        assertThat(details).containsEntry("outcome", "denied").containsEntry("session", "sess")
                .containsEntry("institution", " minagri ").containsEntry("reason", "x");
    }

    @Test
    void targetAliasPrecedence() {
        assertThat(AuditPayloads.firstStr(Map.of("target", "t", "target_user", "u"), "target_user", "target", "target_client"))
                .isEqualTo("u");
        assertThat(AuditPayloads.firstStr(Map.of("target_user", "  ", "target", "t"), "target_user", "target")).isEqualTo("t");
        assertThat(AuditPayloads.firstStr(Map.of("target_user", 5), "target_user")).isNull();
        Map<String, Object> p = AuditPayloads.build(AuditEventType.LOGOUT, "success", null, null, null, null, null);
        assertThat(p.get("institution")).isNull();
        assertThat(p.get("target")).isNull();
    }

    @Test
    void scopeOfUserRoleRolesGroup() {
        assertThat(AuditScope.institutionOfUser(Map.of("institution", List.of(" MINAGRI ")))).isEqualTo("minagri");
        assertThat(AuditScope.institutionOfUser(Map.of("institution", List.of(" ")))).isNull();
        assertThat(AuditScope.institutionOfUser(Map.of())).isNull();
        assertThat(AuditScope.institutionOfUser(null)).isNull();

        assertThat(AuditScope.institutionOfRole("inst:minagri:admin")).isEqualTo("minagri");
        assertThat(AuditScope.institutionOfRole("inst:MINAGRI")).isEqualTo("minagri");
        assertThat(AuditScope.institutionOfRole("inst::x")).isNull();
        assertThat(AuditScope.institutionOfRole("admin")).isNull();
        assertThat(AuditScope.institutionOfRole(null)).isNull();

        assertThat(AuditScope.institutionOfRoles(List.of("inst:minagri:admin", "inst:minagri:viewer", "admin"))).isEqualTo("minagri");
        assertThat(AuditScope.institutionOfRoles(List.of("inst:minagri:admin", "inst:rdb:viewer"))).isNull();
        assertThat(AuditScope.institutionOfRoles(List.of("admin"))).isNull();

        Set<String> slugs = Set.of("minagri");
        assertThat(AuditScope.institutionOfGroup("/minagri/planning", null, slugs)).isEqualTo("minagri");
        assertThat(AuditScope.institutionOfGroup(null, "MINAGRI", slugs)).isEqualTo("minagri");
        assertThat(AuditScope.institutionOfGroup("/admins", null, slugs)).isNull();
        assertThat(AuditScope.institutionOfGroup(null, null, slugs)).isNull();

        assertThat(AuditScope.institutionField("minagri")).containsExactly(Map.entry("institution", "minagri"));
        assertThat(AuditScope.institutionField(null)).isEmpty();
    }
}
