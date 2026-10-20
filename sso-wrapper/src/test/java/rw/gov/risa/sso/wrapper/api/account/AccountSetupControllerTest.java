package rw.gov.risa.sso.wrapper.api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService.AppLink;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService.Completion;
import rw.gov.risa.sso.wrapper.service.setuplink.SetupLinkService.LinkCheck;
import rw.gov.risa.sso.wrapper.support.WrapperTest;

/** Public account pages (spec §5.12): GET only checks, POST sets once, resend answers identically. */
@WrapperTest
class AccountSetupControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean SetupLinkService links;
    @MockitoBean AuditEmitter audit;

    static UserRepresentation user() {
        UserRepresentation u = new UserRepresentation();
        u.setId("u1");
        u.setUsername("jane@minagri.gov.rw");
        return u;
    }

    @Test
    void getChecksTheLinkWithoutSpendingIt() throws Exception {
        when(links.open("tok")).thenReturn(new LinkCheck(null, user()));
        mvc.perform(get("/account/setup").param("token", "tok"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("Choose your password")))
                .andExpect(content().string(containsString("jane@minagri.gov.rw")))
                .andExpect(content().string(containsString("name=\"token\" value=\"tok\"")))
                .andExpect(header().string("Content-Security-Policy", containsString("form-action 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("style-src 'self'")))
                .andExpect(header().string("Cache-Control", containsString("no-store")));
        verify(links, never()).complete(any(), any(), any(), any());
    }

    @Test
    void deadLinkShowsTheInvalidPageInKinyarwanda() throws Exception {
        when(links.open("old")).thenReturn(new LinkCheck("password_already_set", user()));
        mvc.perform(get("/account/setup").param("token", "old").param("lang", "rw"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Iri huza ntirigikora")))
                .andExpect(content().string(containsString("setup-link?lang=rw")))
                .andExpect(content().string(not(containsString("name=\"password\""))));
        mvc.perform(get("/account/setup")).andExpect(content().string(containsString("can no longer be used")));
    }

    @Test
    void postSetsThePasswordThenListsTheAppsOpenNow() throws Exception {
        when(links.complete(eq("tok"), eq("Str0ngPassw0rd"), eq("Str0ngPassw0rd"), any()))
                .thenReturn(new Completion("completed", null, user(), List.of(
                        new AppLink("minagri-kubaka-web", "Kubaka <Portal>", "https://kubaka.minagri.gov.rw/home"),
                        new AppLink("minagri-hr", "HR", null))));
        mvc.perform(post("/account/setup").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "tok").param("password", "Str0ngPassw0rd").param("confirm", "Str0ngPassw0rd"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Your password is set")))
                .andExpect(content().string(containsString("href=\"https://kubaka.minagri.gov.rw/home\"")))
                .andExpect(content().string(containsString("Kubaka &lt;Portal&gt;")))
                .andExpect(content().string(containsString("address not recorded")));
    }

    @Test
    void refusedPasswordReshowsTheFormWithTheMessage() throws Exception {
        when(links.complete(eq("tok"), any(), any(), any()))
                .thenReturn(new Completion("password_rejected", "Invalid password: minimum length 10.", user(), List.of()));
        mvc.perform(post("/account/setup").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "tok").param("password", "short").param("confirm", "short"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Invalid password: minimum length 10.")))
                .andExpect(content().string(containsString("name=\"password\"")));
        when(links.complete(eq("tok"), any(), any(), any()))
                .thenReturn(new Completion("passwords_differ", null, user(), List.of()));
        mvc.perform(post("/account/setup").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "tok").param("password", "a").param("confirm", "b"))
                .andExpect(content().string(containsString("The two passwords are not the same.")));
        when(links.complete(eq("tok"), any(), any(), any()))
                .thenReturn(new Completion("link_unknown", null, null, List.of()));
        mvc.perform(post("/account/setup").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", "tok").param("password", "a").param("confirm", "a"))
                .andExpect(content().string(containsString("can no longer be used")));
    }

    @Test
    void resendAnswersIdenticallyWhateverHappened() throws Exception {
        String first = null;
        for (String outcome : List.of("sent", "no_account", "password_already_set", "cooldown", "send_failed")) {
            when(links.requestResend(anyString(), any())).thenReturn(outcome);
            String body = mvc.perform(post("/account/setup-link").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("username", "someone@gov.rw"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).contains("Check your email");
            if (first == null) {
                first = body;
            }
            assertThat(body).as(outcome).isEqualTo(first);
        }
        verify(links, org.mockito.Mockito.times(5)).requestResend(eq("someone@gov.rw"), any());
    }

    @Test
    void resendFormPrefillsAndOffersTheOtherLanguage() throws Exception {
        mvc.perform(get("/account/setup-link").param("username", "jane@minagri.gov.rw")
                        .param("return_to", "/realms/government-internal/login-actions/restart?client_id=x"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"jane@minagri.gov.rw\"")))
                .andExpect(content().string(containsString("Kinyarwanda")))
                .andExpect(content().string(containsString(
                        "href=\"http://localhost:8080/realms/government-internal/login-actions/restart?client_id=x\"")));
    }

    @Test
    void absoluteOrOffSiteReturnToIsNeverLinked() throws Exception {
        for (String bad : List.of("https://evil.example/phish", "//evil.example/x", "http://localhost:8080/realms/x")) {
            mvc.perform(get("/account/setup-link").param("return_to", bad))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("evil.example"))))
                    .andExpect(content().string(not(containsString("Back to sign in"))));
            mvc.perform(post("/account/setup-link").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("username", "x@gov.rw").param("return_to", bad))
                    .andExpect(content().string(not(containsString("Back to sign in"))));
        }
    }

    @Test
    void stylesheetIsServed() throws Exception {
        mvc.perform(get("/account/account.css")).andExpect(status().isOk());
    }
}
