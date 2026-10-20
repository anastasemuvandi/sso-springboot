package rw.gov.risa.sso.wrapper.api.realm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.keycloak.KeycloakAdminFactory;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExec;
import rw.gov.risa.sso.wrapper.service.keycloak.KeycloakExecutors;
import rw.gov.risa.sso.wrapper.service.mfa.MfaService;
import rw.gov.risa.sso.wrapper.support.TestCallers;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/** {@code /realm/*}: gates (reads = admin tier, writes = super admin), SMTP password never returned, logo validation. */
@WrapperTest
class RealmControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AuditEmitter audit;
    @MockitoBean KeycloakAdminFactory factory;
    @MockitoBean KeycloakExecutors executors;
    @MockitoBean MfaService mfa;

    RealmResource realm;
    RealmRepresentation rep;

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};

    @BeforeEach
    void realm() {
        realm = mock(RealmResource.class);
        rep = new RealmRepresentation();
        rep.setRealm("government-internal");
        rep.setDisplayName("GoR");
        rep.setLoginTheme("government-citizens");
        rep.setSmtpServer(new HashMap<>(Map.of("host", "smtp.gov.rw", "port", "587", "from", "sso@gov.rw",
                "auth", "true", "user", "mailer", "password", "**********")));
        rep.setAttributes(new HashMap<>(Map.of("primary_color", "#00a1de")));
        when(realm.toRepresentation()).thenReturn(rep);
        when(executors.serviceAccount()).thenReturn(KeycloakExec.of(realm));
    }

    // ---- gates -----------------------------------------------------------------------------

    @Test
    void superAdminReadsTheConfigWithoutTheSmtpPassword() throws Exception {
        mvc.perform(get("/realm/config").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.realm").value("government-internal"))
                .andExpect(jsonPath("$.smtpServer.host").value("smtp.gov.rw"))
                .andExpect(jsonPath("$.smtpServer.password").doesNotExist());
    }

    @Test
    void institutionAdminAndPlainUserAreRefused() throws Exception {
        mvc.perform(get("/realm/config").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/realm/overview").with(TestCallers.plainUser())).andExpect(status().isForbidden());
        mvc.perform(get("/realm/mfa").with(TestCallers.institutionAdmin("minagri", "admin")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/realm/config")).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminMayReadButOnlyASuperAdminWrites() throws Exception {
        mvc.perform(get("/realm/branding").with(TestCallers.admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.custom_attributes.primary_color").value("#00a1de"))
                .andExpect(jsonPath("$.themes.login").value("government-citizens"));
        mvc.perform(put("/realm/config").with(TestCallers.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"X\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/realm/branding").with(TestCallers.systemAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"primary_color\":\"#000\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/realm/email/test").with(TestCallers.admin())).andExpect(status().isForbidden());
        verify(realm, never()).update(any());
    }

    @Test
    void superAdminUpdatesOnlyTheFieldsSentAndIsAudited() throws Exception {
        mvc.perform(put("/realm/config").with(TestCallers.superAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Government of Rwanda\",\"failureFactor\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed[0]").value("displayName"));
        ArgumentCaptor<RealmRepresentation> saved = ArgumentCaptor.forClass(RealmRepresentation.class);
        verify(realm).update(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Government of Rwanda");
        assertThat(saved.getValue().getLoginTheme()).isEqualTo("government-citizens");
        verify(audit).emit(argThat((AuditEvent e) -> e.type() == AuditEventType.ADMIN_REALM_UPDATED
                && "platform.super".equals(e.actor()) && "config".equals(e.extra().get("section"))));
    }

    @Test
    void renamingTheRealmIsRefused() throws Exception {
        mvc.perform(put("/realm/config").with(TestCallers.superAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"realm\":\"other\"}")).andExpect(status().isBadRequest());
    }

    // ---- SMTP ------------------------------------------------------------------------------

    @Test
    void emailConfigNeverReturnsThePassword() throws Exception {
        mvc.perform(get("/realm/email/config").with(TestCallers.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("smtp.gov.rw"))
                .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(get("/realm/overview").with(TestCallers.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email_config.configured").value(true))
                .andExpect(jsonPath("$.email_config.password").doesNotExist());
    }

    @Test
    void emailUpdateWithoutAPasswordKeepsTheStoredOne() throws Exception {
        mvc.perform(put("/realm/email/config").with(TestCallers.superAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"host\":\"smtp2.gov.rw\",\"port\":465,\"from\":\"sso@gov.rw\",\"ssl\":true,"
                                + "\"auth\":true,\"user\":\"mailer\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<RealmRepresentation> saved = ArgumentCaptor.forClass(RealmRepresentation.class);
        verify(realm).update(saved.capture());
        assertThat(saved.getValue().getSmtpServer()).containsEntry("host", "smtp2.gov.rw")
                .containsEntry("port", "465").containsEntry("ssl", "true").containsEntry("password", "**********");
        verify(audit).emit(argThat((AuditEvent e) -> !e.extra().containsKey("password")
                && Boolean.FALSE.equals(e.extra().get("password_changed"))));
    }

    @Test
    void emailUpdateIsValidated() throws Exception {
        mvc.perform(put("/realm/email/config").with(TestCallers.superAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"host\":\"\",\"from\":\"not-an-email\"}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void smtpTestReportsKeycloaksAnswer() throws Exception {
        jakarta.ws.rs.core.Response ok = jakarta.ws.rs.core.Response.noContent().build();
        when(realm.testSMTPConnection(any(Map.class))).thenReturn(ok);
        when(factory.asServiceAccount(any())).thenAnswer(inv -> ((java.util.function.Function<RealmResource, Object>)
                inv.getArgument(0)).apply(realm));
        mvc.perform(post("/realm/email/test").with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ---- logo ------------------------------------------------------------------------------

    @Test
    void aValidPngLogoIsStoredAsADataUrl() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", PNG);
        mvc.perform(multipart("/realm/branding/logo").file(file).with(TestCallers.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("logo.png"))
                .andExpect(jsonPath("$.size").value(PNG.length))
                .andExpect(jsonPath("$.logo_url").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,")));
        ArgumentCaptor<RealmRepresentation> saved = ArgumentCaptor.forClass(RealmRepresentation.class);
        verify(realm).update(saved.capture());
        assertThat(saved.getValue().getAttributes().get("logo_url")).startsWith("data:image/png;base64,");
        assertThat(saved.getValue().getAttributes()).containsEntry("primary_color", "#00a1de");
    }

    @Test
    void aLogoOfTheWrongTypeIsRefused() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", "%PDF-1.4".getBytes());
        mvc.perform(multipart("/realm/branding/logo").file(file).with(TestCallers.superAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid file type")));
        verify(realm, never()).update(any());
    }

    @Test
    void aLogoWhoseBytesAreNotTheDeclaredTypeIsRefused() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", "GIF89a....".getBytes());
        mvc.perform(multipart("/realm/branding/logo").file(file).with(TestCallers.superAdmin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anSvgWithScriptIsRefused() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.svg", "image/svg+xml",
                "<svg xmlns='http://www.w3.org/2000/svg'><script>alert(1)</script></svg>"
                        .getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/realm/branding/logo").file(file).with(TestCallers.superAdmin()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aLogoOverFiveMegabytesIsRefused() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);
        mvc.perform(multipart("/realm/branding/logo").file(file).with(TestCallers.superAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("5MB")));
    }

    @Test
    void onlyASuperAdminUploadsALogo() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", PNG);
        mvc.perform(multipart("/realm/branding/logo").file(file).with(TestCallers.admin()))
                .andExpect(status().isForbidden());
    }

    // ---- TOTP ------------------------------------------------------------------------------

    @Test
    void onlyASuperAdminSetsTheMfaMode() throws Exception {
        mvc.perform(put("/realm/mfa").with(TestCallers.admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"all\"}")).andExpect(status().isForbidden());
        verify(mfa, never()).setMode(anyString(), anyString(), any());
        when(mfa.setMode(eq("per_client"), eq("platform.super"), any()))
                .thenReturn(new MfaService.ModeChange("per_client", "off", true, false, true, null));
        mvc.perform(put("/realm/mfa").with(TestCallers.superAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"per_client\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("per_client"))
                .andExpect(jsonPath("$.previous_mode").value("off"));
        mvc.perform(put("/realm/mfa").with(TestCallers.superAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void exemptionsAreListedUnderAnExemptionsKey() throws Exception {
        when(mfa.listExemptions()).thenReturn(List.of(new MfaService.Exemption("u1", "jdoe",
                "Medical reason documented in ticket 42", "platform.super", "2026-10-01T00:00:00Z")));
        mvc.perform(get("/realm/mfa/exemptions").with(TestCallers.admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exemptions[0].user_id").value("u1"))
                .andExpect(jsonPath("$.exemptions[0].granted_by").value("platform.super"));
    }

    @Test
    void grantingAndRevokingAnExemptionIsSuperAdminOnlyAndActorComesFromTheToken() throws Exception {
        String body = "{\"user_id\":\"u1\",\"reason\":\"Medical reason documented in ticket 42\"}";
        mvc.perform(post("/realm/mfa/exemptions").with(TestCallers.admin()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isForbidden());
        when(mfa.grantExemption(eq("u1"), anyString(), eq("platform.super"), any())).thenReturn(
                new MfaService.Exemption("u1", "jdoe", "Medical reason documented in ticket 42", "platform.super", "t"));
        mvc.perform(post("/realm/mfa/exemptions").with(TestCallers.superAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.username").value("jdoe"));
        mvc.perform(post("/realm/mfa/exemptions").with(TestCallers.superAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"u1\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/realm/mfa/exemptions/u1").with(TestCallers.systemAdmin())).andExpect(status().isForbidden());
        when(mfa.revokeExemption(eq("u1"), eq("platform.super"), any())).thenReturn(Map.of("revoked", true));
        mvc.perform(delete("/realm/mfa/exemptions/u1").with(TestCallers.superAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
    }
}
