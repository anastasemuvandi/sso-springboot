package rw.gov.risa.sso.wrapper.policy.institutioncontext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.delegated.DelegatedPermissionConfigException;
import rw.gov.risa.sso.wrapper.policy.delegated.GuardrailViolation;
import rw.gov.risa.sso.wrapper.policy.delegated.ResolvedGrant;

/**
 * Resolves a verified caller to the institution they act for (port of the pure parts of the
 * reference {@code institution_context.py}).
 *
 * <p>Authority comes from the caller's realm roles ({@code inst:<slug>:<role>}), never from a
 * claim: the {@code institution} claim, when present, only corroborates. Rules:
 * <ul>
 *   <li>templates unavailable → 503 (fail closed)</li>
 *   <li>a caller holding a grant (and not a platform admin) is subject to the guardrail</li>
 *   <li>a named institution outside the grant → 403 (platform admins may name any)</li>
 *   <li>exactly one administered institution → pinned to it; none → 403 (400 for a platform
 *       admin, who must name one); several → 400</li>
 *   <li>non-platform callers: claim must match the chosen slug (when present)</li>
 *   <li>platform admin acting for an institution they hold no role for → the full grant its
 *       templates define (400 if it has none)</li>
 * </ul>
 */
public final class InstitutionContextResolver {

    /** Platform admin tier: may act for any institution by naming it. */
    public static final Set<String> PLATFORM_ROLES = Set.of("super_admin", "admin", "system_admin");

    private final Supplier<GrantLookup> lookup;
    private final InstitutionContextSettings settings;

    /**
     * @param lookup supplies the loaded templates; it may throw
     *               {@link DelegatedPermissionConfigException} (missing/unsafe config) → 503
     */
    public InstitutionContextResolver(Supplier<GrantLookup> lookup, InstitutionContextSettings settings) {
        this.lookup = lookup;
        this.settings = settings;
    }

    public InstitutionContextResolver(GrantLookup lookup, InstitutionContextSettings settings) {
        this(() -> lookup, settings);
    }

    public static boolean isPlatformAdmin(List<String> realmRoles) {
        return realmRoles.stream().anyMatch(PLATFORM_ROLES::contains);
    }

    // ---- /institution-admin/* --------------------------------------------------------

    /**
     * @param requestedInstitution the {@code ?institution=} parameter, or {@code null}
     */
    public Resolution<InstitutionContext> resolveInstitutionAdmin(CallerIdentity caller,
                                                                  String requestedInstitution) {
        List<String> warnings = new ArrayList<>();
        GrantLookup registry;
        try {
            registry = lookup.get();
        } catch (DelegatedPermissionConfigException e) {
            return configUnavailable(e, warnings);
        }
        Map<String, ResolvedGrant> grants = registry.resolveCaller(caller.realmRoles());
        List<String> administered = sorted(grants.keySet());
        boolean platform = isPlatformAdmin(caller.realmRoles());

        if (!grants.isEmpty() && !platform) {
            Refusal r = guardrail(registry, caller, warnings);
            if (r != null) {
                return new Resolution.Refused<>(r, warnings);
            }
        }

        String chosen;
        if (requestedInstitution != null) {
            if (!platform && !grants.containsKey(requestedInstitution)) {
                return new Resolution.Refused<>(Refusal.forbidden(Refusal.Code.INSTITUTION_NOT_ADMINISTERED,
                        "You are not an administrator of institution " + PyText.repr(requestedInstitution)
                                + ". Your institutions: " + (administered.isEmpty() ? "none"
                                : PyText.list(administered)) + "."), warnings);
            }
            chosen = requestedInstitution;
        } else if (administered.size() == 1) {
            chosen = administered.get(0);
        } else if (administered.isEmpty()) {
            Refusal r = platform
                    ? Refusal.badRequest(Refusal.Code.INSTITUTION_REQUIRED,
                    "You administer no institution. Specify ?institution=<slug>.")
                    : Refusal.forbidden(Refusal.Code.NOT_INSTITUTION_ADMIN,
                    "You are not an institution administrator.");
            return new Resolution.Refused<>(r, warnings);
        } else {
            return new Resolution.Refused<>(multiple(administered), warnings);
        }

        if (!platform) {
            Refusal r = claimCorroborates(caller, chosen, warnings);
            if (r != null) {
                return new Resolution.Refused<>(r, warnings);
            }
        }

        ResolvedGrant grant = grants.get(chosen);
        if (grant == null) {
            try {
                grant = registry.grantFor(chosen);
            } catch (DelegatedPermissionConfigException e) {
                return new Resolution.Refused<>(
                        Refusal.badRequest(Refusal.Code.UNKNOWN_INSTITUTION, e.getMessage()), warnings);
            }
        }
        return new Resolution.Resolved<>(new InstitutionContext(caller.username(), caller.userId(), chosen, grant,
                administered, platform, caller.realmRoles()), warnings);
    }

    /** {@link #resolveInstitutionAdmin} that must also carry {@code capability}. */
    public Resolution<InstitutionContext> requireCapability(CallerIdentity caller, String requestedInstitution,
                                                            Capability capability) {
        Resolution<InstitutionContext> res = resolveInstitutionAdmin(caller, requestedInstitution);
        if (res instanceof Resolution.Resolved<InstitutionContext> ok) {
            var refusal = ok.value().checkCapability(capability);
            if (refusal.isPresent()) {
                return new Resolution.Refused<>(refusal.get(), ok.warnings());
            }
        }
        return res;
    }

    // ---- directory reads -------------------------------------------------------------

    /**
     * Who may read the employee directory. A platform admin gets every institution
     * ({@code institution == null}) or the one named (trimmed, lower-cased). A delegated admin
     * is pinned to their own and needs {@code view-members}. Anyone else → 403.
     */
    public Resolution<DirectoryScope> resolveDirectoryScope(CallerIdentity caller, String requestedInstitution) {
        List<String> warnings = new ArrayList<>();
        GrantLookup registry;
        try {
            registry = lookup.get();
        } catch (DelegatedPermissionConfigException e) {
            return configUnavailable(e, warnings);
        }
        Map<String, ResolvedGrant> grants = registry.resolveCaller(caller.realmRoles());
        List<String> administered = sorted(grants.keySet());
        boolean platform = isPlatformAdmin(caller.realmRoles());
        String named = requestedInstitution != null && !requestedInstitution.strip().isEmpty()
                ? requestedInstitution.strip().toLowerCase(Locale.ROOT) : null;

        if (platform) {
            return new Resolution.Resolved<>(
                    new DirectoryScope(caller.username(), caller.userId(), named, administered, true), warnings);
        }
        if (grants.isEmpty()) {
            return new Resolution.Refused<>(Refusal.forbidden(Refusal.Code.NOT_INSTITUTION_ADMIN,
                    "You are not an institution administrator, so you have no employee directory to read."),
                    warnings);
        }
        Refusal g = guardrail(registry, caller, warnings);
        if (g != null) {
            return new Resolution.Refused<>(g, warnings);
        }
        String chosen;
        if (named != null) {
            if (!grants.containsKey(named)) {
                return new Resolution.Refused<>(Refusal.forbidden(Refusal.Code.INSTITUTION_NOT_ADMINISTERED,
                        "You are not an administrator of institution " + PyText.repr(named)
                                + ". Your institutions: " + PyText.list(administered) + "."), warnings);
            }
            chosen = named;
        } else if (administered.size() == 1) {
            chosen = administered.get(0);
        } else {
            return new Resolution.Refused<>(multiple(administered), warnings);
        }
        Refusal c = claimCorroborates(caller, chosen, warnings);
        if (c != null) {
            return new Resolution.Refused<>(c, warnings);
        }
        if (!grants.get(chosen).has(Capability.VIEW_MEMBERS)) {
            return new Resolution.Refused<>(Refusal.forbidden(Refusal.Code.CAPABILITY_DENIED,
                    "Your delegated permissions for '" + chosen + "' do not include '"
                            + Capability.VIEW_MEMBERS.wire() + "', which the employee directory requires."),
                    warnings);
        }
        return new Resolution.Resolved<>(
                new DirectoryScope(caller.username(), caller.userId(), chosen, administered, false), warnings);
    }

    // ---- pure helpers (also usable on their own) -------------------------------------

    /**
     * Guardrail violations for a caller: forbidden realm roles plus ANY {@code realm-management}
     * client role (per the loaded guardrail). Empty = clean.
     */
    public static List<GuardrailViolation> guardrailViolations(GrantLookup registry, CallerIdentity caller) {
        return registry.guardrail().violations(caller.realmRoles(), caller.clientRoles());
    }

    /**
     * The claim drift rule: {@code true} when a claim is present and differs from the chosen
     * slug (no claim, or a matching one, corroborates).
     */
    public static boolean claimContradicts(String claim, String chosen) {
        return claim != null && !claim.isEmpty() && !claim.equals(chosen);
    }

    private Refusal guardrail(GrantLookup registry, CallerIdentity caller, List<String> warnings) {
        List<GuardrailViolation> violations = guardrailViolations(registry, caller);
        if (violations.isEmpty()) {
            return null;
        }
        String listed = violations.stream().map(GuardrailViolation::toString).collect(Collectors.joining(", "));
        warnings.add("delegated-permissions: guardrail violation for " + PyText.repr(caller.username())
                + " — holds broad admin grant(s) [" + listed + "] alongside an institution grant");
        if (!settings.guardrailEnforce()) {
            return null;
        }
        return Refusal.forbidden(Refusal.Code.GUARDRAIL_VIOLATION,
                "Guardrail: institution admins must not hold broad admin roles, but you hold [" + listed
                        + "]. Such a role bypasses per-institution scoping. Remove it (delegated-check reports"
                        + " these), or use the platform-admin endpoints instead.");
    }

    private Refusal claimCorroborates(CallerIdentity caller, String chosen, List<String> warnings) {
        String claimed = caller.institutionClaim();
        if (!claimContradicts(claimed, chosen)) {
            return null;
        }
        warnings.add("delegated-permissions: institution claim mismatch for " + PyText.repr(caller.username())
                + " — token claims " + PyText.repr(claimed) + " but grant role resolves to " + PyText.repr(chosen)
                + "; IPPIS attribute and delegated role have drifted apart");
        if (!settings.claimCrosscheck()) {
            return null;
        }
        return Refusal.forbidden(Refusal.Code.INSTITUTION_CLAIM_MISMATCH,
                "Your token's institution claim (" + PyText.repr(claimed) + ") does not match the institution "
                        + "your delegated role grants (" + PyText.repr(chosen) + "). This usually means your "
                        + "institution changed but the old delegated role was not revoked. A platform admin must "
                        + "reconcile the two (delegated-check).");
    }

    private static Refusal multiple(List<String> administered) {
        return Refusal.badRequest(Refusal.Code.MULTIPLE_INSTITUTIONS,
                "You administer multiple institutions " + PyText.list(administered)
                        + "; specify which with ?institution=<slug>.");
    }

    private static <T> Resolution<T> configUnavailable(DelegatedPermissionConfigException e, List<String> warnings) {
        warnings.add("delegated-permissions: configuration unusable — " + e.getMessage());
        return new Resolution.Refused<>(new Refusal(503, Refusal.Code.DELEGATED_CONFIG_UNAVAILABLE,
                "Delegated permission templates are not loaded, so institution admin operations are refused. "
                        + e.getMessage()), warnings);
    }

    private static List<String> sorted(Set<String> s) {
        return List.copyOf(new TreeSet<>(s));
    }
}
