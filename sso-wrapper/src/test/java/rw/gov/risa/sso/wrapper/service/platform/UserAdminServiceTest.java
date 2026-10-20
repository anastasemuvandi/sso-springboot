package rw.gov.risa.sso.wrapper.service.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.UserSessionRepresentation;
import org.springframework.http.HttpStatus;

import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.service.joinkey.JoinKeyService;

class UserAdminServiceTest {

    final PlatformMocks m = new PlatformMocks();
    final JoinKeyService joinKeys = mock(JoinKeyService.class);
    final UserAdminService service = new UserAdminService(m.support, joinKeys);

    UserRepresentation user(String id) {
        UserRepresentation u = new UserRepresentation();
        u.setId(id);
        u.setUsername("jane");
        u.setEnabled(true);
        u.setAttributes(new HashMap<>(Map.of("institution", List.of("minagri"), "position", List.of("Officer"))));
        return u;
    }

    @Test
    void createValidatesIdentifiersBeforeWritingAnything() {
        doThrow(new ApiException(HttpStatus.BAD_REQUEST, JoinKeyService.IDENTIFIER_MALFORMED, "bad NID"))
                .when(joinKeys).validateIdentifierWrite(eq(true), eq("j@gov.rw"), anyMap(), eq(null));
        assertThatThrownBy(() -> service.create(new UserAdminService.NewUser("jane", "j@gov.rw", "J", "D", true, false,
                Map.of("national_id", List.of("12"))), PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.error()).isEqualTo("identifier_malformed"));
        verifyNoInteractions(m.realm);
        verifyNoInteractions(m.audit);
    }

    @Test
    void duplicateUsernameIs409() {
        when(m.realm.users().create(any(UserRepresentation.class))).thenReturn(Response.status(409).build());
        assertThatThrownBy(() -> service.create(new UserAdminService.NewUser("jane", "j@gov.rw", "J", "D", true, false,
                null), PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void patchValidatesWithSelfExcludedAndMergesAttributes() {
        UserRepresentation u = user("u1");
        when(m.directory.getUser(m.realm, "u1")).thenReturn(Optional.of(u));
        service.update("u1", new UserAdminService.UserChanges("new@gov.rw", null, null, false, null,
                Map.of("phone", List.of("0788000000"), "position", List.of())), PlatformMocks.ADMIN);
        verify(joinKeys).validateIdentifierWrite(eq(true), eq("new@gov.rw"), anyMap(), eq("u1"));
        ArgumentCaptor<UserRepresentation> rep = ArgumentCaptor.forClass(UserRepresentation.class);
        verify(m.realm.users().get("u1")).update(rep.capture());
        assertThat(rep.getValue().getAttributes())
                .containsEntry("institution", List.of("minagri"))      // preserved
                .containsEntry("phone", List.of("0788000000"))         // set
                .doesNotContainKey("position");                        // cleared by an empty value
        ArgumentCaptor<AuditEvent.Builder> a = ArgumentCaptor.forClass(AuditEvent.Builder.class);
        verify(m.audit, org.mockito.Mockito.atLeastOnce()).emit(a.capture());
        AuditEvent e = a.getAllValues().get(0).build();
        assertThat(e.type()).isEqualTo(AuditEventType.ADMIN_USER_DISABLED);
        assertThat(e.actor()).isEqualTo("platform.admin");
        assertThat(e.extra()).containsEntry("target_user", "u1").containsEntry("institution", "minagri");
    }

    @Test
    void identifierTakenOnPatchIs409AndNothingIsWritten() {
        doThrow(new ApiException(HttpStatus.CONFLICT, JoinKeyService.IDENTIFIER_TAKEN, "held"))
                .when(joinKeys).validateIdentifierWrite(eq(true), eq("taken@gov.rw"), any(), eq("u1"));
        assertThatThrownBy(() -> service.update("u1", new UserAdminService.UserChanges("taken@gov.rw", null, null, null,
                null, null), PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(m.realm);
    }

    @Test
    void mergeAttributes() {
        Map<String, List<String>> merged = UserAdminService.mergeAttributes(Map.of("a", List.of("1"), "b", List.of("2")),
                Map.of("b", List.of(""), "c", List.of("3")));
        assertThat(merged).containsOnlyKeys("a", "c");
    }

    @Test
    void passwordPolicyRefusalIs400AndNeverLeaksThePassword() {
        when(m.directory.getUser(m.realm, "u1")).thenReturn(Optional.of(user("u1")));
        var userResource = m.realm.users().get("u1");
        BadRequestException refused = new BadRequestException(Response.status(400)
                .entity("{\"error\":\"invalidPasswordMinLengthMessage\"}").build());
        doThrow(refused).when(userResource).resetPassword(any(CredentialRepresentation.class));
        assertThatThrownBy(() -> service.resetPassword("u1", "Secret-Value-1", false, PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).doesNotContain("Secret-Value-1");
                });
    }

    @Test
    void endingASessionOfAnotherUserIs404() {
        when(m.directory.getUser(m.realm, "u1")).thenReturn(Optional.of(user("u1")));
        UserSessionRepresentation mine = new UserSessionRepresentation();
        mine.setId("s-mine");
        when(m.realm.users().get("u1").getUserSessions()).thenReturn(List.of(mine));
        assertThatThrownBy(() -> service.logoutSession("u1", "s-other", PlatformMocks.SUPER))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(m.realm, never()).deleteSession(any(), org.mockito.ArgumentMatchers.anyBoolean());

        service.logoutSession("u1", "s-mine", PlatformMocks.SUPER);
        verify(m.realm).deleteSession("s-mine", false);
    }

    @Test
    void bulkReportsFailuresAfterAuditingWhatWasDone() {
        when(m.directory.getUser(m.realm, "u1")).thenReturn(Optional.of(user("u1")));
        GroupRepresentation g1 = new GroupRepresentation();
        g1.setId("g1");
        g1.setPath("/minagri");
        when(m.directory.getGroup(m.realm, "g1")).thenReturn(Optional.of(g1));
        when(m.directory.getGroup(m.realm, "g2")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.bulk("u1", List.of("g1", "g2"), true, PlatformMocks.SUPER))
                .hasMessageContaining("Some groups failed to be assigned").hasMessageContaining("g2");
        verify(m.realm.users().get("u1")).joinGroup("g1");
        ArgumentCaptor<AuditEvent.Builder> a = ArgumentCaptor.forClass(AuditEvent.Builder.class);
        verify(m.audit).emit(a.capture());
        assertThat(a.getValue().build().extra()).containsEntry("group_ids", List.of("g1"))
                .containsEntry("institution", "minagri");
    }

    @Test
    void provisionRollsBackWhenThePasswordIsRefused() {
        Response created = Response.status(201).location(java.net.URI.create("http://kc/users/new-id")).build();
        when(m.realm.users().create(any(UserRepresentation.class))).thenReturn(created);
        var created2 = m.realm.users().get("new-id");
        BadRequestException refused = new BadRequestException();
        doThrow(refused).when(created2).resetPassword(any());
        Response deleted = Response.noContent().build();
        var usersResource = m.realm.users();
        when(usersResource.delete("new-id")).thenReturn(deleted);
        assertThatThrownBy(() -> service.provision(new UserAdminService.NewUser("jane", "j@gov.rw", "J", "D", true,
                false, null), "weak", false, List.of("g1"), PlatformMocks.SUPER))
                .isInstanceOf(ApiException.class);
        verify(m.realm.users()).delete("new-id");
        verifyNoInteractions(m.audit);
    }
}
