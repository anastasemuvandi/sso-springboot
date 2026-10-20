package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One employee's row of the provisioning report — the four facts: {@code account}
 * (existing/created/not_created), {@code clients_granted}, {@code clients_skipped} (already held),
 * {@code clients_denied}; plus the outcome.
 *
 * @param reason   the plan's reason code, or {@code "error"} on a failed row
 * @param userId   the account the row acted on (applied rows only)
 * @param extras   service-added keys (e.g. email delivery fields, {@code password} mode,
 *                 {@code grant_errors}, {@code mirror_error}) — merged into {@link #describe()}
 */
public record ReportRow(String employeeNumber, String name, String username, PlanAction action,
                        AccountStatus account, String reason, String message, List<String> clientsGranted,
                        List<String> clientsSkipped, List<ClientDecision> clientsDenied, Outcome outcome,
                        String userId, Map<String, Object> extras) {

    public ReportRow {
        clientsGranted = List.copyOf(clientsGranted);
        clientsSkipped = List.copyOf(clientsSkipped);
        clientsDenied = List.copyOf(clientsDenied);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /** What a dry run says would happen: the plan's action as an outcome. */
    public static Outcome previewOutcome(EmployeePlan plan) {
        return switch (plan.action()) {
            case CREATE -> Outcome.CREATED;
            case LINK -> Outcome.LINKED;
            case SKIP -> Outcome.SKIPPED;
        };
    }

    /**
     * The row for a plan before (or without) applying it. For an existing account the username is
     * the account's own (an account created by hand need not be named after the IPPIS address).
     */
    public static ReportRow of(EmployeePlan plan, Outcome outcome, Account existing) {
        String username = plan.username();
        if (existing != null && existing.username() != null && !existing.username().isEmpty()) {
            username = existing.username();
        }
        return new ReportRow(plan.employeeNumber(), plan.displayName(), username, plan.action(), plan.accountStatus(),
                plan.reason() == null ? null : plan.reason().code(), plan.message(), plan.granted(), plan.held(),
                plan.denied(), outcome, null, Map.of());
    }

    /** Dry run row. */
    public static ReportRow preview(EmployeePlan plan, Account existing) {
        return of(plan, previewOutcome(plan), existing);
    }

    /** The row after a successful apply: outcome created/linked, the grants actually written. */
    public ReportRow applied(boolean created, String appliedUserId, List<String> granted, Map<String, Object> more) {
        Map<String, Object> merged = new LinkedHashMap<>(extras);
        if (more != null) {
            merged.putAll(more);
        }
        return new ReportRow(employeeNumber, name, username, action, account, reason, message, granted,
                clientsSkipped, clientsDenied, created ? Outcome.CREATED : Outcome.LINKED, appliedUserId, merged);
    }

    /**
     * The row after a failed apply: outcome failed, reason {@code error}, and the account fact
     * corrected (a failed creation has no account).
     */
    public ReportRow failed(String errorMessage, boolean hadExistingAccount) {
        return new ReportRow(employeeNumber, name, username, action,
                hadExistingAccount ? AccountStatus.EXISTING : AccountStatus.NOT_CREATED, "error", errorMessage,
                clientsGranted, clientsSkipped, clientsDenied, Outcome.FAILED, userId, extras);
    }

    /** snake_case view: plan keys + {@code outcome}, {@code user_id} (when set) and extras. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("employee_number", employeeNumber);
        out.put("name", name);
        out.put("username", username);
        out.put("action", action.code());
        out.put("account", account.code());
        out.put("reason", reason);
        out.put("message", message);
        out.put("clients_granted", new ArrayList<>(clientsGranted));
        out.put("clients_skipped", new ArrayList<>(clientsSkipped));
        List<Map<String, Object>> denied = new ArrayList<>();
        for (ClientDecision d : clientsDenied) {
            denied.add(d.describe());
        }
        out.put("clients_denied", denied);
        out.put("outcome", outcome.code());
        if (userId != null) {
            out.put("user_id", userId);
        }
        out.putAll(extras);
        return out;
    }
}
