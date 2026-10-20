package rw.gov.risa.sso.wrapper.service.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import rw.gov.risa.sso.wrapper.service.mail.DeliveryResult;
import rw.gov.risa.sso.wrapper.service.mail.MailSettings;
import rw.gov.risa.sso.wrapper.service.mail.Mailer;
import rw.gov.risa.sso.wrapper.service.provisioning.AccessChangeNotifier.Kind;
import rw.gov.risa.sso.wrapper.service.provisioning.AccessChangeNotifier.Recipient;

/** One email per person per request, applied changes only, NOTIFY_ACCESS_CHANGES honoured. */
class AccessChangeNotifierTest {

    Mailer mailer = mock(Mailer.class);
    AccessChangeNotifier notifier = new AccessChangeNotifier(mailer);
    Recipient alice = new Recipient("u-a", "alice@gov.rw", "alice", "Alice A");
    Recipient bob = new Recipient("u-b", "bob@gov.rw", "bob", "Bob B");

    @BeforeEach
    void setUp() {
        when(mailer.settings()).thenReturn(new MailSettings("from@gov.rw", "http://login", true));
        when(mailer.configured()).thenReturn(true);
        when(mailer.sendAccessGranted(any())).thenReturn(DeliveryResult.sent());
        when(mailer.sendAccessRevoked(any())).thenReturn(DeliveryResult.failed("relay down"));
    }

    @Test
    void onePersonGetsOneEmailListingEveryApplication() {
        AccessChangeNotifier.Batch b = notifier.begin(Kind.GRANT, "minagri");
        b.add(alice, "minagri-a", "App A (minagri-a)").add(alice, "minagri-b", null).add(bob, "minagri-a", null)
                .add(alice, "minagri-a", "App A (minagri-a)");

        Map<String, String> out = b.flush();

        assertThat(out).containsEntry("u-a", "sent").containsEntry("u-b", "sent").hasSize(2);
        ArgumentCaptor<Mailer.AccessChangeMail> m = ArgumentCaptor.forClass(Mailer.AccessChangeMail.class);
        verify(mailer, times(2)).sendAccessGranted(m.capture());
        Mailer.AccessChangeMail toAlice = m.getAllValues().stream().filter(x -> x.to().equals("alice@gov.rw"))
                .findFirst().orElseThrow();
        assertThat(toAlice.applications()).containsExactly("App A (minagri-a)", "minagri-b");
        assertThat(toAlice.institution()).isEqualTo("minagri");
        // flushing again sends nothing (one email per person per request)
        assertThat(b.flush()).isEmpty();
        verify(mailer, times(2)).sendAccessGranted(any());
    }

    @Test
    void revokeUsesTheRevokedTemplateAndReportsFailures() {
        Map<String, String> out = notifier.begin(Kind.REVOKE, "minagri").add(alice, "minagri-a", null).flush();
        assertThat(out).containsEntry("u-a", "failed: relay down");
        assertThat(AccessChangeNotifier.summarize(out)).containsEntry("failed", 1);
        verify(mailer, never()).sendAccessGranted(any());
    }

    @Test
    void disabledNotConfiguredAndNoAddress() {
        when(mailer.settings()).thenReturn(new MailSettings("from@gov.rw", "http://login", false));
        assertThat(notifier.begin(Kind.GRANT, "minagri").add(alice, "x", null).flush()).containsEntry("u-a", "disabled");

        when(mailer.settings()).thenReturn(new MailSettings("from@gov.rw", "http://login", true));
        when(mailer.configured()).thenReturn(false);
        assertThat(notifier.begin(Kind.GRANT, "minagri").add(alice, "x", null).flush())
                .containsEntry("u-a", "not_configured");

        when(mailer.configured()).thenReturn(true);
        assertThat(notifier.begin(Kind.GRANT, "minagri").add(new Recipient("u-c", null, "c", "C"), "x", null).flush())
                .containsEntry("u-c", "no_address");
        verify(mailer, never()).sendAccessGranted(any());
    }

    @Test
    void aPreviewThatNeverFlushesSendsNothing() {
        notifier.begin(Kind.GRANT, "minagri").add(alice, "x", null);
        verify(mailer, never()).sendAccessGranted(any());
        assertThat(notifier.begin(Kind.GRANT, "minagri").flush()).isEmpty();
    }
}
