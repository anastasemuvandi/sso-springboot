package rw.gov.risa.sso.wrapper.service.institution;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistry;
import rw.gov.risa.sso.wrapper.policy.registry.InstitutionRegistryLoader;
import rw.gov.risa.sso.wrapper.policy.registry.RegistryFindings;
import rw.gov.risa.sso.wrapper.policy.registry.RegistryValidator;

/**
 * The institution registry ({@code configs/institutions.yaml}), loaded and validated at startup.
 * <b>Never fails closed</b>: a missing/unreadable file yields an empty registry (every IPPIS code
 * unmapped, no slug known) and the validator's findings are logged, not fatal.
 */
@Component
public class InstitutionRegistryHolder {

    private static final Logger log = LoggerFactory.getLogger(InstitutionRegistryHolder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DelegationProperties props;
    private volatile InstitutionRegistry registry = InstitutionRegistry.empty("<not loaded>");
    private volatile RegistryFindings findings = new RegistryFindings(java.util.List.of(), java.util.List.of());

    public InstitutionRegistryHolder(DelegationProperties props) {
        this.props = props;
    }

    @PostConstruct
    public synchronized InstitutionRegistry reload() {
        Optional<Path> path = ConfigPaths.locate(props.institutionRegistryConfig());
        InstitutionRegistry loaded = InstitutionRegistryLoader.loadOrEmpty(path.orElse(null));
        registry = loaded;
        loaded.warnings().forEach(w -> log.warn("{}", w));
        if (path.isEmpty()) {
            log.warn("institution registry: {} not found (looked in {}); IPPIS institution codes will not be "
                    + "normalised", props.institutionRegistryConfig(), ConfigPaths.candidates(props.institutionRegistryConfig()));
            return loaded;
        }
        try {
            Object doc = InstitutionRegistryLoader.readRaw(path.get());
            Map<String, ?> templates = templatesDoc();
            RegistryFindings f = RegistryValidator.validate(doc, templates);
            findings = f;
            f.errors().forEach(e -> log.error("institution registry: {}", e));
            f.warnings().forEach(w -> log.warn("institution registry: {}", w));
        } catch (Exception e) {
            log.warn("institution registry: could not validate {}: {}", path.get(), e.getMessage());
        }
        log.info("institution registry: {} institution(s) from {}", loaded.size(), path.get());
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private Map<String, ?> templatesDoc() {
        try {
            Optional<Path> p = ConfigPaths.locate(props.permissionsConfig());
            return p.isPresent() ? JSON.readValue(p.get().toFile(), Map.class) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public InstitutionRegistry current() {
        return registry;
    }

    /** Known slugs (empty when the registry is missing). */
    public Set<String> knownSlugs() {
        return registry.slugs();
    }

    /** The last validator findings (errors are logged at startup, never fatal). */
    public RegistryFindings findings() {
        return findings;
    }
}
