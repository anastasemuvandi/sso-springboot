package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What will happen to one employee and what the admin is told. Total by construction.
 *
 * @param username        the account's (would-be) username: the email for a creation; for an
 *                        existing account a fallback the service replaces with the real one
 * @param reason          why skipped ({@code null} when proceeding)
 * @param existingUserId  the resolved account, if any
 */
public record EmployeePlan(String employeeNumber, String displayName, String username, PlanAction action,
                           List<ClientDecision> decisions, SkipReason reason, String message,
                           String existingUserId) {

    public EmployeePlan {
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        message = message == null ? "" : message;
    }

    /** A skip with no decisions (not_found / account_* refusals from the service). */
    public static EmployeePlan skipped(String employeeNumber, SkipReason reason, String message) {
        return new EmployeePlan(employeeNumber, employeeNumber, null, PlanAction.SKIP, List.of(), reason, message,
                null);
    }

    /** Clients this run will assign — never the ones already held. */
    public List<String> granted() {
        List<String> out = new ArrayList<>();
        for (ClientDecision d : decisions) {
            if (d.assignable()) {
                out.add(d.clientId());
            }
        }
        return out;
    }

    /** Clients they already hold ({@code clients_skipped}). */
    public List<String> held() {
        List<String> out = new ArrayList<>();
        for (ClientDecision d : decisions) {
            if (d.held()) {
                out.add(d.clientId());
            }
        }
        return out;
    }

    /** Refusals ({@code clients_denied}). */
    public List<ClientDecision> denied() {
        List<ClientDecision> out = new ArrayList<>();
        for (ClientDecision d : decisions) {
            if (!d.qualified()) {
                out.add(d);
            }
        }
        return out;
    }

    public boolean proceeds() {
        return action == PlanAction.CREATE || action == PlanAction.LINK;
    }

    /** existing (an account was resolved) / created (action create) / not_created. */
    public AccountStatus accountStatus() {
        if (existingUserId != null && !existingUserId.isEmpty()) {
            return AccountStatus.EXISTING;
        }
        return action == PlanAction.CREATE ? AccountStatus.CREATED : AccountStatus.NOT_CREATED;
    }

    /**
     * {@code employee_number, name, username, action, account, reason, message, clients_granted,
     * clients_skipped, clients_denied}.
     */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("employee_number", employeeNumber);
        out.put("name", displayName);
        out.put("username", username);
        out.put("action", action.code());
        out.put("account", accountStatus().code());
        out.put("reason", reason == null ? null : reason.code());
        out.put("message", message);
        out.put("clients_granted", granted());
        out.put("clients_skipped", held());
        List<Map<String, Object>> denied = new ArrayList<>();
        for (ClientDecision d : denied()) {
            denied.add(d.describe());
        }
        out.put("clients_denied", denied);
        return out;
    }
}
