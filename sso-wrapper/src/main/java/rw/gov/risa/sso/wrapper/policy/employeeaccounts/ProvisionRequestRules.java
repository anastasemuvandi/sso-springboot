package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Request-level rules of {@code POST /institution-admin/employees/provision} — the ones that fail
 * the <b>whole request</b> (400), as opposed to per-(employee, client) denials.
 *
 * <p>Also: a client outside the caller's institution scope fails the whole request with 403 (the
 * service checks each target with the institution context); an unconfigured client is
 * <b>loaded</b> and denies everyone with {@code join_column_unset}; another institution's employee
 * comes back {@code not_found} (indistinguishable from nonexistent).
 */
public final class ProvisionRequestRules {

    private ProvisionRequestRules() {
    }

    /** A request-level refusal (HTTP 400). */
    public static final class ProvisionRequestException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public ProvisionRequestException(String message) {
            super(message);
        }

        public int status() {
            return 400;
        }
    }

    /** Selected client ids, trimmed and de-duplicated (order kept). @throws ProvisionRequestException when none */
    public static List<String> clientIds(List<String> clientIds) {
        List<String> wanted = dedupe(clientIds);
        if (wanted.isEmpty()) {
            throw new ProvisionRequestException("Select at least one application. With none selected no employee "
                    + "could qualify for anything, so every one of them would be skipped — which is a confusing way "
                    + "to say 'nothing was asked for'.");
        }
        return wanted;
    }

    /**
     * Selected employee numbers, trimmed and de-duplicated (order kept).
     *
     * @param maxBatch {@code EMPLOYEE_PROVISION_MAX_BATCH}
     * @throws ProvisionRequestException when none, or more than {@code maxBatch}
     */
    public static List<String> employeeNumbers(List<String> employeeNumbers, int maxBatch) {
        List<String> wanted = dedupe(employeeNumbers);
        if (wanted.isEmpty()) {
            throw new ProvisionRequestException("Select at least one employee.");
        }
        if (wanted.size() > maxBatch) {
            throw new ProvisionRequestException(wanted.size() + " employees selected; one request may provision at "
                    + "most " + maxBatch + ". Each account is several Keycloak writes and two emails, and a larger "
                    + "batch would time out with no way to tell what had already been created. Split the "
                    + "selection.");
        }
        return wanted;
    }

    /** The mirror lookup's result in the caller's order, plus the numbers it did not find. */
    public record Found(List<EmployeeRecord> records, List<String> missing) {
    }

    /** Order {@code found} (from the institution-scoped mirror query) as {@code wanted}; list the rest as missing. */
    public static Found order(List<String> wanted, List<EmployeeRecord> found) {
        Map<String, EmployeeRecord> byNumber = new HashMap<>();
        for (EmployeeRecord r : found) {
            byNumber.put(r.employeeNumber(), r);
        }
        List<EmployeeRecord> records = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String n : wanted) {
            EmployeeRecord r = byNumber.get(n);
            if (r != null) {
                records.add(r);
            } else {
                missing.add(n);
            }
        }
        return new Found(records, missing);
    }

    /** The {@code not_found} plan for a number not in the caller's institution's slice of the mirror. */
    public static EmployeePlan notFound(String employeeNumber, String institution) {
        return EmployeePlan.skipped(employeeNumber, SkipReason.NOT_FOUND, "No employee '" + employeeNumber + "' in "
                + institution + "'s employee list. Either the selection is stale, or IPPIS no longer reports them "
                + "here.");
    }

    /** Sort report rows back into the admin's selection order (unknown numbers last, stable). */
    public static <T> List<T> inRequestOrder(List<T> rows, Function<T, String> employeeNumber,
                                             List<String> requested) {
        List<String> order = dedupe(requested);
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            position.put(order.get(i), i);
        }
        List<T> out = new ArrayList<>(rows);
        out.sort(Comparator.comparingInt(r -> position.getOrDefault(employeeNumber.apply(r), order.size())));
        return out;
    }

    private static List<String> dedupe(List<String> values) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.strip().isEmpty()) {
                    out.add(v.strip());
                }
            }
        }
        return new ArrayList<>(out);
    }
}
