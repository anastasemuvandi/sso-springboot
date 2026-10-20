package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import rw.gov.risa.sso.wrapper.policy.joinkey.ColumnSpec;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeys;

/**
 * Assigning applications from the employee list — the decisions, without the doing (port of
 * {@code employee_accounts.py}).
 *
 * <p>Rules: (1) a missing join value denies that client only; (2) one qualifying client is enough
 * to create an account; (3) no qualifying client ⇒ no account; (4) every refusal names the empty
 * field; (5) an existing account is never recreated or touched — apps are granted on top;
 * (6) "no email" and "no qualifying client" are <b>creation-only</b> rules; (7) an app already
 * held is skipped (reported held), not granted again. A leaver ({@code status} not active or
 * suspended) gets nothing, account or not.
 */
public final class EmployeeAccountPlanner {

    private EmployeeAccountPlanner() {
    }

    private static ColumnSpec emailSpec() {
        return JoinKeys.specFor(JoinColumn.EMAIL);
    }

    /** Normalised addresses carried by more than one employee in this batch. */
    public static Set<String> duplicateEmails(Collection<EmployeeRecord> records) {
        Map<String, Integer> seen = new HashMap<>();
        for (EmployeeRecord r : records) {
            if (r.email() == null || r.email().isEmpty()) {
                continue;
            }
            seen.merge(emailSpec().normalise(r.email()), 1, Integer::sum);
        }
        Set<String> out = new HashSet<>();
        seen.forEach((k, v) -> {
            if (v > 1) {
                out.add(k);
            }
        });
        return out;
    }

    private record EmailProblem(SkipReason reason, String message) {
    }

    private static Optional<EmailProblem> emailProblem(EmployeeRecord record) {
        ColumnSpec spec = emailSpec();
        if (spec.normalise(record.email()).isEmpty()) {
            return Optional.of(new EmailProblem(SkipReason.EMAIL_MISSING, record.displayName()
                    + " has no email in the employee record, and the account would be named after it. Add one in "
                    + "IPPIS (or to the employee's record) and try again."));
        }
        if (spec.validate(record.email()).isPresent()) {
            return Optional.of(new EmailProblem(SkipReason.EMAIL_INVALID, record.displayName() + " has '"
                    + record.email() + "' as their email, which is not an address (" + spec.shapeHint()
                    + "). An account cannot be named after it."));
        }
        return Optional.empty();
    }

    /** May this employee be given this client? Held first, then unconfigured, missing, invalid. */
    static ClientDecision decideClient(EmployeeRecord record, ClientTarget target, boolean validateFormat,
                                       boolean held) {
        JoinColumn column = target.column();
        if (held) {
            return new ClientDecision(target.clientId(), column, true, true, null, "");
        }
        if (!target.configured()) {
            return new ClientDecision(target.clientId(), column, false, false, DenyReason.COLUMN_UNSET,
                    target.clientId() + " has no join column configured, so there is no agreed identifier to give "
                            + "it. An admin must choose one (NID, phone or email) on the app before anyone can be "
                            + "provisioned into it.");
        }
        String value = record.valueFor(column);
        if (value.isEmpty()) {
            return new ClientDecision(target.clientId(), column, false, false, DenyReason.VALUE_MISSING,
                    target.clientId() + " identifies users by " + column.upper() + " and " + record.displayName()
                            + " has no " + target.employeeField() + " on record, so it could not find them.");
        }
        if (validateFormat && JoinKeys.specFor(column).validate(value).isPresent()) {
            return new ClientDecision(target.clientId(), column, false, false, DenyReason.VALUE_INVALID,
                    target.clientId() + " identifies users by " + column.upper() + ", and " + record.displayName()
                            + "'s " + target.employeeField() + " is not usable as one ("
                            + JoinKeys.specFor(column).shapeHint() + ").");
        }
        return new ClientDecision(target.clientId(), column, true, false, null, "");
    }

    /**
     * The outcome for one employee: create, link, or skip — and why.
     *
     * @param duplicateEmails normalised addresses shared within the batch ({@link #duplicateEmails})
     * @param held            client ids this employee already reaches ({@link HeldClients})
     */
    public static EmployeePlan planEmployee(EmployeeRecord record, List<ClientTarget> targets, boolean validateFormat,
                                            Collection<String> duplicateEmails, Collection<String> held) {
        String existing = record.hasAccount() ? record.keycloakUserId() : null;
        String name = record.displayName();

        if (!EmployeeRecord.PROVISIONABLE_STATUSES.contains(record.status())) {
            return new EmployeePlan(record.employeeNumber(), name, null, PlanAction.SKIP, List.of(),
                    SkipReason.EMPLOYMENT_ENDED,
                    "IPPIS reports " + name + " as " + record.status() + ". " + (existing != null
                            ? "Their existing account is left exactly as it is, and no application is added to it "
                            + "— widening a leaver's access is what offboarding exists to prevent."
                            : "No account is created for someone who has left."),
                    existing);
        }

        String username;
        if (!record.hasAccount()) {
            Optional<EmailProblem> problem = emailProblem(record);
            if (problem.isPresent()) {
                return new EmployeePlan(record.employeeNumber(), name, null, PlanAction.SKIP, List.of(),
                        problem.get().reason(), problem.get().message(), null);
            }
            username = record.email().strip();
            Set<String> dups = duplicateEmails == null ? Set.of() : new HashSet<>(duplicateEmails);
            if (dups.contains(emailSpec().normalise(username))) {
                return new EmployeePlan(record.employeeNumber(), name, username, PlanAction.SKIP, List.of(),
                        SkipReason.EMAIL_DUPLICATE,
                        username + " appears on more than one employee record in this selection. One account "
                                + "cannot stand for two people, so neither of them is provisioned until IPPIS is "
                                + "corrected.",
                        null);
            }
        } else {
            String e = record.email() == null ? "" : record.email().strip();
            username = e.isEmpty() ? null : e;
        }

        Set<String> heldIds = new LinkedHashSet<>();
        if (held != null) {
            for (String c : held) {
                if (c != null && !c.isEmpty()) {
                    heldIds.add(c);
                }
            }
        }
        List<ClientDecision> decisions = new ArrayList<>();
        for (ClientTarget t : targets) {
            decisions.add(decideClient(record, t, validateFormat, heldIds.contains(t.clientId())));
        }
        long assignable = decisions.stream().filter(ClientDecision::assignable).count();

        if (assignable == 0) {
            if (existing != null) {
                long already = decisions.stream().filter(ClientDecision::held).count();
                long refused = decisions.stream().filter(d -> !d.qualified()).count();
                List<String> parts = new ArrayList<>();
                if (already > 0) {
                    parts.add(already + " application(s) already theirs");
                }
                if (refused > 0) {
                    parts.add(refused + " could not identify them");
                }
                return new EmployeePlan(record.employeeNumber(), name, username, PlanAction.SKIP, decisions,
                        SkipReason.NOTHING_TO_ASSIGN,
                        "Nothing to add for " + name + ": " + String.join(" and ", parts)
                                + ". Their account is unchanged.",
                        existing);
            }
            return new EmployeePlan(record.employeeNumber(), name, username, PlanAction.SKIP, decisions,
                    SkipReason.NO_QUALIFYING_CLIENT,
                    "None of the selected applications can identify " + name + ", so an account would reach "
                            + "nothing. Fix one of the fields below, or select an application they have the "
                            + "identifier for.",
                    null);
        }

        long alreadyHeld = decisions.stream().filter(ClientDecision::held).count();
        String heldNote = alreadyHeld > 0 ? ", and already had " + alreadyHeld : "";
        return new EmployeePlan(record.employeeNumber(), name, username,
                existing != null ? PlanAction.LINK : PlanAction.CREATE, decisions, null,
                name + " is given " + assignable + " of " + decisions.size() + " selected application(s)"
                        + heldNote + ".",
                existing);
    }

    /** {@link #planEmployee} with format validation on and no duplicates / holdings. */
    public static EmployeePlan planEmployee(EmployeeRecord record, List<ClientTarget> targets) {
        return planEmployee(record, targets, true, Set.of(), Set.of());
    }

    /**
     * Plan a whole batch, in the order given, one outcome per employee.
     *
     * @param held keyed by employee number; an employee missing from it holds nothing
     */
    public static List<EmployeePlan> plan(List<EmployeeRecord> records, List<ClientTarget> targets,
                                          boolean validateFormat, Map<String, ? extends Collection<String>> held) {
        Set<String> duplicates = duplicateEmails(records);
        List<EmployeePlan> out = new ArrayList<>();
        for (EmployeeRecord r : records) {
            Collection<String> holdings = held == null ? null : held.get(r.employeeNumber());
            out.add(planEmployee(r, targets, validateFormat, duplicates, holdings == null ? Set.of() : holdings));
        }
        return out;
    }

    public static List<EmployeePlan> plan(List<EmployeeRecord> records, List<ClientTarget> targets) {
        return plan(records, targets, true, Map.of());
    }

    /** Roll a plan up. {@code failed} is 0 here — the service counts failures from its rows. */
    public static BatchTotals totals(List<EmployeePlan> plans, List<ClientTarget> targets) {
        int created = 0;
        int linked = 0;
        int skipped = 0;
        int grants = 0;
        int denials = 0;
        int alreadyHeld = 0;
        int existing = 0;
        for (EmployeePlan p : plans) {
            switch (p.action()) {
                case CREATE -> created++;
                case LINK -> linked++;
                default -> skipped++;
            }
            if (p.accountStatus() == AccountStatus.EXISTING) {
                existing++;
            }
            grants += p.granted().size();
            alreadyHeld += p.held().size();
            denials += p.denied().size();
        }
        List<String> denyingEveryone = new ArrayList<>();
        if (!plans.isEmpty()) {
            for (ClientTarget t : targets) {
                boolean reached = false;
                for (EmployeePlan p : plans) {
                    if (p.granted().contains(t.clientId()) || p.held().contains(t.clientId())) {
                        reached = true;
                        break;
                    }
                }
                if (!reached) {
                    denyingEveryone.add(t.clientId());
                }
            }
        }
        return new BatchTotals(plans.size(), created, linked, skipped, grants, denials, 0, alreadyHeld, existing,
                denyingEveryone);
    }
}
