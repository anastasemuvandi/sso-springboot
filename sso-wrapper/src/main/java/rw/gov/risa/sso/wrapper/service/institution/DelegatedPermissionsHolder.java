package rw.gov.risa.sso.wrapper.service.institution;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionConfigException;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionRegistry;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionsLoader;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.GrantLookup;

/**
 * The process-wide delegated-permission templates ({@code templates.json}), loaded at startup.
 *
 * <p>A missing or unsafe file does NOT stop the application: {@link #current()} throws
 * {@link DelegatedPermissionConfigException} and every {@code /institution-admin/*} request fails
 * closed with 503 {@code delegation_unavailable}. While unavailable, the file is re-tried at most every
 * 5 s (the reference re-reads on every request). {@link #reload()} parses first and swaps only on
 * success, so a bad edit cannot take the delegated surface down.
 */
@Component
public class DelegatedPermissionsHolder {

    private static final Logger log = LoggerFactory.getLogger(DelegatedPermissionsHolder.class);
    static final Duration RETRY_EVERY = Duration.ofSeconds(5);

    private final DelegationProperties props;
    private final Clock clock;
    private volatile DelegatedPermissionRegistry registry;
    private volatile String error = "not loaded yet";
    private volatile Path path;
    private volatile Instant lastAttempt = Instant.EPOCH;

    @Autowired
    public DelegatedPermissionsHolder(DelegationProperties props) {
        this(props, Clock.systemUTC());
    }

    DelegatedPermissionsHolder(DelegationProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    void loadAtStartup() {
        try {
            load();
        } catch (DelegatedPermissionConfigException e) {
            log.error("delegated-permissions: configuration unusable — /institution-admin/* will answer 503: {}",
                    e.getMessage());
        }
    }

    /** The loaded templates, or throws (the caller answers 503). */
    public DelegatedPermissionRegistry current() {
        DelegatedPermissionRegistry r = registry;
        if (r != null) {
            return r;
        }
        synchronized (this) {
            if (registry != null) {
                return registry;
            }
            if (lastAttempt.plus(RETRY_EVERY).isBefore(clock.instant())) {
                return load();
            }
            throw new DelegatedPermissionConfigException(error);
        }
    }

    public boolean available() {
        try {
            current();
            return true;
        } catch (DelegatedPermissionConfigException e) {
            return false;
        }
    }

    /** Why the templates are unavailable, when they are. */
    public Optional<String> unavailableReason() {
        return available() ? Optional.empty() : Optional.ofNullable(error);
    }

    /** Where the templates were read from (null before a successful load). */
    public Path source() {
        return path;
    }

    /** The {@link GrantLookup} the institution-context resolver needs. Throws when unavailable. */
    public GrantLookup grantLookup() {
        return GrantLookup.of(current());
    }

    /** Re-read the file (POST /delegated-permissions/reload). On failure the previous registry stays. */
    public synchronized DelegatedPermissionRegistry reload() {
        Path p = DelegatedPermissionsLoader.locate(props.permissionsConfig(), ConfigPaths.extraBases());
        DelegatedPermissionRegistry parsed = DelegatedPermissionsLoader.loadFrom(p);
        registry = parsed;
        path = p;
        error = null;
        log.info("delegated-permissions: reloaded {} template(s) from {}", parsed.templates().size(), p);
        return parsed;
    }

    private DelegatedPermissionRegistry load() {
        lastAttempt = clock.instant();
        try {
            Path p = DelegatedPermissionsLoader.locate(props.permissionsConfig(), ConfigPaths.extraBases());
            DelegatedPermissionRegistry parsed = DelegatedPermissionsLoader.loadFrom(p);
            registry = parsed;
            path = p;
            error = null;
            log.info("delegated-permissions: loaded {} template(s) from {}", parsed.templates().size(), p);
            return parsed;
        } catch (DelegatedPermissionConfigException e) {
            error = e.getMessage();
            throw e;
        }
    }
}
