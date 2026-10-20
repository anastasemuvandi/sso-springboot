package rw.gov.risa.sso.wrapper.service.gateway;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Test double with the same contract as Redis GETDEL: {@link #consume} removes atomically. */
public class InMemoryAuthCodeStore implements AuthCodeStore {

    private final Map<String, StoredCode> codes = new ConcurrentHashMap<>();

    @Override
    public String issue(StoredCode code) {
        String raw = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        codes.put(raw, code);
        return raw;
    }

    @Override
    public Optional<StoredCode> consume(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(codes.remove(code));
    }

    public void clear() {
        codes.clear();
    }
}
