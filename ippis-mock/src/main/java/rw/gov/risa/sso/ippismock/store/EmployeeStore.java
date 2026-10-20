package rw.gov.risa.sso.ippismock.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.ippismock.config.IppisMockProperties;

/**
 * The person store: in memory, in insertion (seed) order, keyed by employee number.
 *
 * <p>When {@code IPPIS_MOCK_DATA_FILE} is set the store is loaded from that JSON file (seeded on
 * first start) and rewritten atomically on every mutation, so enrolled employees survive a
 * restart — as in the reference. Unset, it is purely in-memory. Persistence is best-effort: the
 * in-memory copy stays authoritative.
 */
@Component
public class EmployeeStore {
    private static final Logger log = LoggerFactory.getLogger(EmployeeStore.class);
    private static final TypeReference<LinkedHashMap<String, StoredPerson>> FILE_TYPE = new TypeReference<>() { };

    private final Path dataFile;
    private final ObjectMapper mapper;
    private final Map<String, StoredPerson> people = new LinkedHashMap<>();

    public EmployeeStore(IppisMockProperties properties, ObjectMapper mapper) {
        this.dataFile = properties.dataFile() == null || properties.dataFile().isBlank()
                ? null : Path.of(properties.dataFile());
        this.mapper = mapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        load();
    }

    private synchronized void load() {
        people.clear();
        if (dataFile != null && Files.exists(dataFile)) {
            try {
                people.putAll(mapper.readValue(dataFile.toFile(), FILE_TYPE));
                return;
            } catch (IOException e) {
                log.warn("ippis-mock: could not read {} ({}); reseeding", dataFile, e.getMessage());
            }
        }
        seed();
    }

    private void seed() {
        people.clear();
        for (StoredPerson p : SeedData.roster()) {
            people.put(p.getEmployeeNumber(), p);
        }
        save();
    }

    /** Back to the seeded roster (mock control plane; also handy between e2e runs). */
    public synchronized void reset() {
        seed();
    }

    public synchronized List<StoredPerson> all() {
        List<StoredPerson> out = new ArrayList<>(people.size());
        people.values().forEach(p -> out.add(p.copy()));
        return out;
    }

    public synchronized Optional<StoredPerson> get(String employeeNumber) {
        return Optional.ofNullable(people.get(employeeNumber)).map(StoredPerson::copy);
    }

    /** Insert; false when the number already exists. */
    public synchronized boolean create(StoredPerson person) {
        if (people.containsKey(person.getEmployeeNumber())) {
            return false;
        }
        people.put(person.getEmployeeNumber(), person.copy());
        save();
        return true;
    }

    /** Apply a change to an existing person; empty when unknown. */
    public synchronized Optional<StoredPerson> update(String employeeNumber, UnaryOperator<StoredPerson> change) {
        StoredPerson current = people.get(employeeNumber);
        if (current == null) {
            return Optional.empty();
        }
        StoredPerson updated = change.apply(current.copy());
        people.put(employeeNumber, updated);
        save();
        return Optional.of(updated.copy());
    }

    /** Insert or replace; returns true when it was created. */
    public synchronized boolean put(StoredPerson person) {
        boolean created = !people.containsKey(person.getEmployeeNumber());
        people.put(person.getEmployeeNumber(), person.copy());
        save();
        return created;
    }

    private void save() {
        if (dataFile == null) {
            return;
        }
        try {
            Path parent = dataFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), people);
            Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("ippis-mock: could not persist {} ({})", dataFile, e.getMessage());
        }
    }
}
