package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinKeys;

/**
 * Which selected employees already have an account — run for the whole batch <b>before</b>
 * planning.
 *
 * <p>Lookup order per employee: (1) the mirror link ({@code keycloakUserId}; a stale link falls
 * through), (2) username = email, then email (<b>only</b> when the address is not shared by
 * another employee in the batch — an inference, withheld for duplicates), (3) the
 * {@code employee_number} attribute. Then:
 * <ul>
 *   <li>an account whose {@code institution} names a <b>different</b> institution (case-insensitive)
 *       → {@code account_other_institution}; an account with none is not refused;</li>
 *   <li>two employees resolving to one account → {@code account_ambiguous} for all of them.</li>
 * </ul>
 */
public final class AccountResolver {

    /** The Keycloak user attribute an employee's account carries. */
    public static final String ATTR_EMPLOYEE_NUMBER = "employee_number";

    private AccountResolver() {
    }

    /**
     * Keycloak lookups, each answering empty on "none" <b>and on failure</b> (a failed lookup is
     * logged by the adapter and falls through to the next).
     */
    public interface AccountDirectory {
        Optional<Account> byId(String userId);

        Optional<Account> byUsername(String username);

        Optional<Account> byEmail(String email);

        Optional<Account> byEmployeeNumber(String employeeNumber);
    }

    /** A refused employee: reason + message for the report. */
    public record Refusal(SkipReason reason, String message) {
    }

    /**
     * @param resolved employee number → account, for every record (absent value ⇒ none); refused
     *                 employees map to nothing
     * @param refused  employee number → refusal (other institution / ambiguous), in record order
     */
    public record Resolution(Map<String, Account> resolved, Map<String, Refusal> refused) {

        /** The account for an employee, if resolved and not refused. */
        public Optional<Account> accountOf(String employeeNumber) {
            return Optional.ofNullable(resolved.get(employeeNumber));
        }

        /**
         * The records to plan: refused ones dropped, each carrying the resolved account id — or
         * {@code null}, overwriting a stale mirror link.
         */
        public List<EmployeeRecord> applyTo(List<EmployeeRecord> records) {
            List<EmployeeRecord> out = new ArrayList<>();
            for (EmployeeRecord r : records) {
                if (refused.containsKey(r.employeeNumber())) {
                    continue;
                }
                Account a = resolved.get(r.employeeNumber());
                out.add(r.withKeycloakUserId(a == null ? null : a.id()));
            }
            return out;
        }

        /** Plans for the refused employees (appended to the report by the service). */
        public List<EmployeePlan> refusalPlans() {
            List<EmployeePlan> out = new ArrayList<>();
            refused.forEach((n, r) -> out.add(EmployeePlan.skipped(n, r.reason(), r.message())));
            return out;
        }
    }

    /** One employee's existing account, by the three lookups. */
    public static Optional<Account> existingAccount(EmployeeRecord record, boolean byEmail, AccountDirectory dir) {
        if (record.hasAccount()) {
            Optional<Account> linked = dir.byId(record.keycloakUserId());
            if (linked.isPresent()) {
                return linked;
            }
        }
        String username = record.email() == null ? "" : record.email().strip();
        if (!username.isEmpty() && byEmail) {
            Optional<Account> found = dir.byUsername(username);
            if (found.isPresent()) {
                return found;
            }
            found = dir.byEmail(username);
            if (found.isPresent()) {
                return found;
            }
        }
        return dir.byEmployeeNumber(record.employeeNumber());
    }

    /** Resolve the whole batch for the caller's {@code institution}. */
    public static Resolution resolve(List<EmployeeRecord> records, String institution, AccountDirectory dir) {
        Set<String> duplicated = EmployeeAccountPlanner.duplicateEmails(records);
        Map<String, Account> resolved = new LinkedHashMap<>();
        for (EmployeeRecord r : records) {
            boolean byEmail = !duplicated.contains(JoinKeys.specFor(JoinColumn.EMAIL).normalise(r.email()));
            existingAccount(r, byEmail, dir).ifPresent(a -> resolved.put(r.employeeNumber(), a));
        }

        Map<String, Refusal> refused = new LinkedHashMap<>();
        String mine = institution == null ? "" : institution.strip().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Account> e : new ArrayList<>(resolved.entrySet())) {
            String owner = e.getValue().institution();
            if (owner != null && !owner.equals(mine)) {
                refused.put(e.getKey(), new Refusal(SkipReason.ACCOUNT_OTHER_INSTITUTION,
                        "The SSO account this employee record resolves to belongs to another institution (" + owner
                                + "), so nothing was assigned to it. If they have transferred, the IPPIS sync moves "
                                + "the account across; if not, the employee record's email is somebody else's."));
                resolved.remove(e.getKey());
            }
        }

        Map<String, List<String>> claimed = new LinkedHashMap<>();
        resolved.forEach((number, account) -> {
            if (account.id() != null && !account.id().isEmpty()) {
                claimed.computeIfAbsent(account.id(), k -> new ArrayList<>()).add(number);
            }
        });
        for (List<String> numbers : claimed.values()) {
            if (numbers.size() < 2) {
                continue;
            }
            for (String number : numbers) {
                List<String> others = new ArrayList<>(numbers);
                others.remove(number);
                refused.put(number, new Refusal(SkipReason.ACCOUNT_AMBIGUOUS,
                        "This employee resolves to the same SSO account as " + String.join(", ", others)
                                + ". One account cannot stand for two people, so nothing was assigned to any of "
                                + "them — the employee records need correcting in IPPIS first."));
                resolved.remove(number);
            }
        }
        return new Resolution(resolved, refused);
    }
}
