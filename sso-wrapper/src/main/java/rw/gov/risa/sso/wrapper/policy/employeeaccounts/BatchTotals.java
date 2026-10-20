package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one-line summary above the per-employee report.
 *
 * @param alreadyHeld             (employee, client) pairs needing nothing (already reached)
 * @param accountsExisting        employees who already had an account
 * @param clientsDenyingEveryone  clients that reached (granted or held) nobody in the batch
 */
public record BatchTotals(int selected, int created, int linked, int skipped, int grants, int denials, int failed,
                          int alreadyHeld, int accountsExisting, List<String> clientsDenyingEveryone) {

    public BatchTotals {
        clientsDenyingEveryone = List.copyOf(clientsDenyingEveryone);
    }

    /**
     * {@code selected, created, linked, skipped, grants, denials, failed, already_held,
     * accounts_existing, clients_denying_everyone}.
     */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("selected", selected);
        out.put("created", created);
        out.put("linked", linked);
        out.put("skipped", skipped);
        out.put("grants", grants);
        out.put("denials", denials);
        out.put("failed", failed);
        out.put("already_held", alreadyHeld);
        out.put("accounts_existing", accountsExisting);
        out.put("clients_denying_everyone", List.copyOf(clientsDenyingEveryone));
        return out;
    }
}
