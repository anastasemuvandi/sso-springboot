package rw.gov.risa.sso.ippismock;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rw.gov.risa.sso.ippismock.config.IppisMockProperties;
import rw.gov.risa.sso.ippismock.store.EmployeeStore;
import rw.gov.risa.sso.ippismock.store.StoredPerson;

class EmployeeStorePersistenceTest {

    private static IppisMockProperties props(Path file) {
        return new IppisMockProperties("k", file == null ? null : file.toString(),
                new IppisMockProperties.Webhook(null, null, java.time.Duration.ofSeconds(1), "timestamped"));
    }

    @Test
    void writesPersistAcrossRestart(@TempDir Path dir) {
        Path file = dir.resolve("data/employees.json");
        EmployeeStore first = new EmployeeStore(props(file), new ObjectMapper());
        StoredPerson p = new StoredPerson();
        p.setEmployeeNumber("EMP950");
        p.setFirstName("P");
        assertThat(first.create(p)).isTrue();
        assertThat(Files.exists(file)).isTrue();

        EmployeeStore reloaded = new EmployeeStore(props(file), new ObjectMapper());
        assertThat(reloaded.get("EMP950")).isPresent();
        // Nested people survive the round trip with their employments.
        assertThat(reloaded.get("EMP2009").orElseThrow().getEmployments()).hasSize(2);
    }

    @Test
    void corruptFileIsReseeded(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("employees.json");
        Files.writeString(file, "{not json");
        EmployeeStore store = new EmployeeStore(props(file), new ObjectMapper());
        assertThat(store.get("EMP001")).isPresent();
    }

    @Test
    void withoutAFileTheStoreIsInMemory() {
        EmployeeStore store = new EmployeeStore(props(null), new ObjectMapper());
        assertThat(store.all()).hasSizeGreaterThan(40);
    }
}
