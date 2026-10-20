package rw.gov.risa.sso.wrapper.policy.institutioncontext;

import java.util.List;
import java.util.Optional;
import rw.gov.risa.sso.wrapper.policy.clientscope.ClientScope;
import rw.gov.risa.sso.wrapper.policy.delegated.Capability;
import rw.gov.risa.sso.wrapper.policy.delegated.ResolvedGrant;

/**
 * The institution a verified caller is acting for, plus the scope guards every
 * {@code /institution-admin/*} operation applies. {@code check*} methods return the refusal;
 * {@code assert*} methods throw it as {@link InstitutionRefusedException}.
 *
 * <p>The caller's raw bearer token (for execute-as-caller) is deliberately NOT part of this
 * pure record; the service layer carries it alongside.
 */
public record InstitutionContext(String actor, String userId, String institution, ResolvedGrant grant,
                                 List<String> administered, boolean platformAdmin, List<String> realmRoles) {

    public InstitutionContext {
        administered = administered == null ? List.of() : List.copyOf(administered);
        realmRoles = realmRoles == null ? List.of() : List.copyOf(realmRoles);
    }

    public String groupPath() {
        return grant.groupPath();
    }

    /** {@code inst:<slug>:} */
    public String rolePrefix() {
        return "inst:" + institution + ":";
    }

    /** {@code <slug>-} */
    public String clientPrefix() {
        return institution + "-";
    }

    /** Capability wire names, sorted. */
    public List<String> capabilities() {
        return grant.capabilityNames();
    }

    // ---- capability ------------------------------------------------------------------

    public Optional<Refusal> checkCapability(Capability capability) {
        if (grant.has(capability)) {
            return Optional.empty();
        }
        return Optional.of(Refusal.forbidden(Refusal.Code.CAPABILITY_DENIED,
                "Your delegated permissions for '" + institution + "' do not include '" + capability.wire()
                        + "'. Granted: " + PyText.list(capabilities()) + " (templates: "
                        + PyText.list(grant.templates()) + ")."));
    }

    public void assertCapability(Capability capability) {
        checkCapability(capability).ifPresent(r -> {
            throw r.toException();
        });
    }

    // ---- map-roles scope -------------------------------------------------------------

    /** Requires {@code map-roles}, then the role must pass the grant's allow/deny patterns. */
    public Optional<Refusal> checkRoleInNamespace(String roleName) {
        Optional<Refusal> cap = checkCapability(Capability.MAP_ROLES);
        if (cap.isPresent()) {
            return cap;
        }
        if (grant.mayMapRole(roleName)) {
            return Optional.empty();
        }
        String msg = "Role " + PyText.repr(roleName) + " is not assignable by you. Institution '" + institution
                + "' allows " + PyText.list(grant.roleAllow())
                + (grant.roleDeny().isEmpty() ? "" : " excluding " + PyText.list(grant.roleDeny())) + ".";
        return Optional.of(Refusal.forbidden(Refusal.Code.ROLE_NOT_ASSIGNABLE, msg));
    }

    public void assertRoleInNamespace(String roleName) {
        checkRoleInNamespace(roleName).ifPresent(r -> {
            throw r.toException();
        });
    }

    // ---- client scope ----------------------------------------------------------------

    /**
     * True when a CROSS-CUTTING client's scope includes this institution. An institution-owned
     * client is never "shared", even by its owner: that case is the allow-pattern's, so a
     * template's client deny cannot be bypassed by ownership.
     */
    public boolean shares(ClientScope scope) {
        return scope != null && scope.crossCutting() && scope.covers(institution);
    }

    /** {@link #checkClientInScope(String, ClientScope, Capability)} with {@code manage-clients}. */
    public Optional<Refusal> checkClientInScope(String clientId, ClientScope scope) {
        return checkClientInScope(clientId, scope, Capability.MANAGE_CLIENTS);
    }

    /**
     * Reachable = in this institution's {@code <slug>-*} namespace (per the templates), or
     * cross-cutting with this institution in its scope. {@code scope == null} asks the first
     * question only. {@code capability} must be {@code manage-clients} or {@code view-clients}.
     */
    public Optional<Refusal> checkClientInScope(String clientId, ClientScope scope, Capability capability) {
        Optional<Refusal> cap = checkCapability(capability);
        if (cap.isPresent()) {
            return cap;
        }
        boolean shared = shares(scope);
        boolean permitted = capability == Capability.MANAGE_CLIENTS
                ? grant.mayManageClient(clientId, shared)
                : grant.mayViewClient(clientId, shared);
        if (permitted) {
            return Optional.empty();
        }
        String allow = grant.clientAllow().isEmpty() ? "none" : PyText.list(grant.clientAllow());
        String why;
        if (scope != null && scope.crossCutting()) {
            why = ", and it is a cross-cutting client whose institutions do not include '" + institution + "'";
        } else if (scope != null && scope.owner() != null && !scope.owner().isEmpty()) {
            why = ", and it belongs to institution '" + scope.owner() + "'";
        } else {
            why = "";
        }
        return Optional.of(Refusal.forbidden(Refusal.Code.CLIENT_OUT_OF_SCOPE,
                "Client " + PyText.repr(clientId) + " is not within your institution's client scope (" + allow
                        + ")" + why + "."));
    }

    public void assertClientInScope(String clientId, ClientScope scope, Capability capability) {
        checkClientInScope(clientId, scope, capability).ifPresent(r -> {
            throw r.toException();
        });
    }

    public void assertClientInScope(String clientId, ClientScope scope) {
        assertClientInScope(clientId, scope, Capability.MANAGE_CLIENTS);
    }
}
