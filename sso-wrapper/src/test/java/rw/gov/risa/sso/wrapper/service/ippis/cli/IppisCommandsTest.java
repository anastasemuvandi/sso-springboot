package rw.gov.risa.sso.wrapper.service.ippis.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.javacrumbs.shedlock.core.LockProvider;

import rw.gov.risa.sso.wrapper.service.ippis.DevMirrorSeeder;
import rw.gov.risa.sso.wrapper.service.ippis.IdentityMirrorWriter;
import rw.gov.risa.sso.wrapper.service.ippis.IppisReconcileService;

class IppisCommandsTest {

    IppisReconcileService reconcile = mock(IppisReconcileService.class);
    IdentityMirrorWriter mirror = mock(IdentityMirrorWriter.class);
    DevMirrorSeeder seeder = mock(DevMirrorSeeder.class);
    IppisCommands commands = new IppisCommands(reconcile, mirror, seeder, mock(LockProvider.class));
    ByteArrayOutputStream buf = new ByteArrayOutputStream();

    IppisCommandsTest() {
        commands.printTo(new PrintStream(buf));
    }

    @Test
    void unknownOptionIsAUsageError() {
        assertThat(commands.run("ippis-reconcile", List.of("--fulll"))).isEqualTo(64);
        verify(reconcile, never()).runDelta(any());
    }

    @Test
    void requireMirrorWithoutAMirrorExits2BeforeTouchingIppis() {
        when(mirror.recorder()).thenReturn(Optional.empty());
        assertThat(commands.run("ippis-reconcile", List.of("--require-mirror", "--no-lock"))).isEqualTo(2);
        verify(reconcile, never()).runDelta(any());
        assertThat(buf.toString()).contains("IDENTITY_DATABASE_URL");
    }

    @Test
    void failedRunExits1() {
        when(reconcile.runFull(any())).thenThrow(new IllegalStateException("ippis down"));
        assertThat(commands.run("ippis-reconcile", List.of("--full", "--no-lock"))).isEqualTo(1);
    }

    @Test
    void seedDelegatesToTheSeeder() {
        when(seeder.seed(any(Boolean.class), any())).thenReturn(0);
        assertThat(commands.run("seed-identity-mirror", List.of("--dry-run"))).isZero();
        verify(seeder).seed(org.mockito.ArgumentMatchers.eq(true), any());
    }
}
