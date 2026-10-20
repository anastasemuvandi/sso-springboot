package rw.gov.risa.sso.wrapper.service.institution;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import rw.gov.risa.sso.wrapper.audit.AuditEmitter;
import rw.gov.risa.sso.wrapper.audit.AuditEvent;
import rw.gov.risa.sso.wrapper.audit.AuditEventType;
import rw.gov.risa.sso.wrapper.error.ApiException;
import rw.gov.risa.sso.wrapper.error.ErrorCodes;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionConfigException;
import rw.gov.risa.sso.wrapper.policy.delegated.GuardrailViolation;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.CallerIdentity;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.DirectoryScope;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.GrantLookup;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContext;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContextResolver;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionContextSettings;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.InstitutionRefusedException;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.Refusal;
import rw.gov.risa.sso.wrapper.policy.institutioncontext.Resolution;
import rw.gov.risa.sso.wrapper.security.CallerPrincipal;
import rw.gov.risa.sso.wrapper.web.RequestInfo;

/**
 * Resolves a verified caller to the {@link InstitutionContext} they act for (rules:
 * {@code policy.institutioncontext}). Refusals become {@link ApiException}s; the 503 for missing or
 * unsafe templates carries {@code delegation_unavailable}. Warnings (guardrail violation, claim drift)
 * are logged; a guardrail violation is audited {@code admin.delegated.guardrail_violation} (outcome
 * {@code denied} when refused, {@code warning} when {@code DELEGATED_GUARDRAIL_ENFORCE=false}).
 */
@Service
public class InstitutionContextService {

    private static final Logger log = LoggerFactory.getLogger(InstitutionContextService.class);

    private final DelegatedPermissionsHolder templates;
    private final DelegationProperties props;
    private final AuditEmitter audit;

    public InstitutionContextService(DelegatedPermissionsHolder templates, DelegationProperties props,
                                     AuditEmitter audit) {
        this.templates = templates;
        this.props = props;
        this.audit = audit;
    }

    /** {@code /institution-admin/*}: the context for {@code ?institution=} ({@code null} = not given). */
    public InstitutionContext resolve(CallerPrincipal caller, String requestedInstitution) {
        CallerIdentity identity = identity(caller);
        Resolution<InstitutionContext> res = resolver().resolveInstitutionAdmin(identity, blankToNull(requestedInstitution));
        return unwrap(res, identity, requestedInstitution);
    }

    /** {@link #resolve} that must also carry every capability in {@code required} (403 otherwise). */
    public InstitutionContext resolve(CallerPrincipal caller, String requestedInstitution, Capability... required) {
        InstitutionContext ctx = resolve(caller, requestedInstitution);
        for (Capability c : required) {
            requireCapability(ctx, c);
        }
        return ctx;
    }

    /** The employee-directory read scope (platform admin: everything or the named institution). */
    public DirectoryScope resolveDirectoryScope(CallerPrincipal caller, String requestedInstitution) {
        CallerIdentity identity = identity(caller);
        Resolution<DirectoryScope> res = resolver().resolveDirectoryScope(identity, requestedInstitution);
        return unwrap(res, identity, requestedInstitution);
    }

    /** 403 {@code capability_denied} unless the context's grant has {@code capability}. */
    public static void requireCapability(InstitutionContext ctx, Capability capability) {
        ctx.checkCapability(capability).ifPresent(r -> {
            throw toApiException(r);
        });
    }

    /** A policy refusal as the error envelope ({@code delegation_unavailable} for the 503). */
    public static ApiException toApiException(Refusal r) {
        String code = r.code() == Refusal.Code.DELEGATED_CONFIG_UNAVAILABLE ? ErrorCodes.DELEGATION_UNAVAILABLE
                : r.code().wire();
        HttpStatus status = HttpStatus.resolve(r.status());
        return new ApiException(status == null ? HttpStatus.FORBIDDEN : status, code, r.message());
    }

    public static ApiException toApiException(InstitutionRefusedException e) {
        return toApiException(e.refusal());
    }

    /** The caller as the policy module sees it ({@code DELEGATED_INSTITUTION_CLAIM} honoured). */
    public CallerIdentity identity(CallerPrincipal caller) {
        Map<String, List<String>> clientRoles = new java.util.LinkedHashMap<>();
        caller.clientRoles().forEach((k, v) -> clientRoles.put(k, List.copyOf(v)));
        return new CallerIdentity(caller.username(), caller.userId(), List.copyOf(caller.realmRoles()), clientRoles,
                institutionClaim(caller));
    }

    private String institutionClaim(CallerPrincipal caller) {
        String name = props.institutionClaim();
        Object raw;
        if (name == null || name.isBlank() || "institution".equals(name)) {
            return caller.institutionClaim();
        }
        raw = caller.claims() == null ? null : caller.claims().get(name);
        if (raw instanceof List<?> l) {
            raw = l.isEmpty() ? null : l.get(0);
        }
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).strip().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private InstitutionContextResolver resolver() {
        return new InstitutionContextResolver(templates::grantLookup,
                new InstitutionContextSettings(props.guardrailEnforce(), props.claimCrosscheck()));
    }

    private <T> T unwrap(Resolution<T> res, CallerIdentity identity, String requested) {
        boolean guardrail = false;
        for (String w : res.warnings()) {
            if (w.contains("configuration unusable")) {
                log.error("{}", w);
            } else {
                log.warn("{}", w);
            }
            if (w.startsWith("delegated-permissions: guardrail violation")) {
                guardrail = true;
            }
        }
        if (res instanceof Resolution.Refused<T> refused) {
            Refusal r = refused.refusal();
            if (r.code() == Refusal.Code.GUARDRAIL_VIOLATION) {
                auditGuardrail(identity, requested, true);
            }
            throw toApiException(r);
        }
        if (guardrail) {
            auditGuardrail(identity, requested, false);
        }
        return ((Resolution.Resolved<T>) res).value();
    }

    private void auditGuardrail(CallerIdentity identity, String requested, boolean refused) {
        List<String> violations = new ArrayList<>();
        try {
            GrantLookup lookup = templates.grantLookup();
            for (GuardrailViolation v : InstitutionContextResolver.guardrailViolations(lookup, identity)) {
                violations.add(v.toString());
            }
        } catch (DelegatedPermissionConfigException e) {
            // cannot happen once a guardrail warning was produced; keep the event anyway
        }
        Set<String> administered = Set.of();
        try {
            administered = templates.current().resolveCaller(identity.realmRoles()).keySet();
        } catch (DelegatedPermissionConfigException e) {
            // ignore
        }
        audit.emit(AuditEvent.of(AuditEventType.DELEGATED_GUARDRAIL_VIOLATION)
                .outcome(refused ? "denied" : "warning")
                .actor(identity.username())
                .requestIp(RequestInfo.clientIp())
                .institution(blankToNull(requested) != null ? requested
                        : administered.size() == 1 ? administered.iterator().next() : null)
                .targetUser(identity.userId())
                .put("violations", violations)
                .put("administered", List.copyOf(administered))
                .put("enforced", props.guardrailEnforce()).build());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
