package rw.gov.risa.sso.wrapper.policy.clientscope;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code PUT /client/clients/{id}/scope} decision (platform admins only).
 *
 * <p>Narrowing is a two-step: any institution that loses the client <b>and has users on it</b>
 * (its group is on the access policy, or it has individually-assigned users) makes the change
 * answer <b>409</b> unless {@code confirm} is set. Once confirmed, the service withdraws that
 * institution's access for every institution in {@link Plan#removedInstitutions()}. Widening needs
 * no confirmation and takes nothing away.
 */
public final class ScopeChangePolicy {

    /** 409 message. */
    public static final String CONFIRM_MESSAGE = "This change removes institution(s) that currently have "
            + "access to this client; their users will lose it. Re-send with confirm=true to proceed.";

    private ScopeChangePolicy() {
    }

    /**
     * What an institution currently has on a client — the removal warning row
     * ({@code ClientScopeImpact}: {@code institution, group_assigned, users}).
     */
    public record InstitutionAccess(String institution, boolean groupAssigned, int users) {

        /** True when removing the institution takes access from somebody. */
        public boolean atRisk() {
            return groupAssigned || users > 0;
        }

        public Map<String, Object> describe() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("institution", institution);
            out.put("group_assigned", groupAssigned);
            out.put("users", users);
            return out;
        }
    }

    /**
     * The outcome of evaluating a scope change.
     *
     * @param before               the current scope
     * @param after                the requested scope
     * @param removedInstitutions  institutions losing the client (sorted) — the service withdraws
     *                             access for each of these once the change proceeds
     * @param accessAtRisk         removed institutions that have users on the client
     * @param confirmationRequired true ⇒ answer 409 with {@link #conflictDetails()} and change nothing
     */
    public record Plan(ClientScope before, ClientScope after, List<String> removedInstitutions,
                       List<InstitutionAccess> accessAtRisk, boolean confirmationRequired) {

        public Plan {
            removedInstitutions = List.copyOf(removedInstitutions);
            accessAtRisk = List.copyOf(accessAtRisk);
        }

        /** The 409 {@code details}: message, removed_institutions, access_at_risk, current/requested scope. */
        public Map<String, Object> conflictDetails() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", CONFIRM_MESSAGE);
            out.put("removed_institutions", new ArrayList<>(removedInstitutions));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (InstitutionAccess a : accessAtRisk) {
                rows.add(a.describe());
            }
            out.put("access_at_risk", rows);
            out.put("current_scope", before.describe());
            out.put("requested_scope", after.describe());
            return out;
        }
    }

    /**
     * Removed institutions — call this first, then read their access (only when non-empty) and
     * pass it to {@link #evaluate}.
     */
    public static List<String> removedInstitutions(ClientScope before, ClientScope after,
                                                   Collection<String> knownSlugs) {
        return ClientScopes.removedInstitutions(before, after, knownSlugs);
    }

    /**
     * Decide a scope change.
     *
     * @param impact per removed institution, what it has on the client (keyed by slug; missing ⇒
     *               nobody on it). Only consulted for removed institutions.
     * @param confirm the request's {@code confirm} flag
     */
    public static Plan evaluate(ClientScope before, ClientScope after, Collection<String> knownSlugs,
                                Map<String, InstitutionAccess> impact, boolean confirm) {
        List<String> losing = ClientScopes.removedInstitutions(before, after, knownSlugs);
        List<InstitutionAccess> atRisk = new ArrayList<>();
        if (impact != null) {
            for (String slug : losing) {
                InstitutionAccess row = impact.get(slug);
                if (row != null && row.atRisk()) {
                    atRisk.add(row);
                }
            }
        }
        return new Plan(before, after, losing, atRisk, !atRisk.isEmpty() && !confirm);
    }
}
