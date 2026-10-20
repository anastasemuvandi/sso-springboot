package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The provisioning response: {@code institution, dry_run, clients, totals, employees}.
 */
public record ProvisionReport(String institution, boolean dryRun, List<ClientTarget> clients, BatchTotals totals,
                              List<ReportRow> employees) {

    public ProvisionReport {
        clients = List.copyOf(clients);
        employees = List.copyOf(employees);
    }

    /**
     * Totals counted from the rows where they can differ from the plan (a failure is not a
     * creation; a grant that failed to write is not an assignment): {@code failed, created, linked,
     * skipped, accounts_existing, grants} from rows; the rest ({@code already_held, denials,
     * clients_denying_everyone}) as the plan computed.
     */
    public static BatchTotals summarise(List<EmployeePlan> plans, List<ClientTarget> targets, List<ReportRow> rows) {
        BatchTotals planned = EmployeeAccountPlanner.totals(plans, targets);
        int failed = 0;
        int created = 0;
        int linked = 0;
        int skipped = 0;
        int existing = 0;
        int grants = 0;
        for (ReportRow r : rows) {
            switch (r.outcome()) {
                case FAILED -> failed++;
                case CREATED -> created++;
                case LINKED -> linked++;
                case SKIPPED -> skipped++;
            }
            if (r.account() == AccountStatus.EXISTING) {
                existing++;
            }
            grants += r.clientsGranted().size();
        }
        return new BatchTotals(planned.selected(), created, linked, skipped, grants, planned.denials(), failed,
                planned.alreadyHeld(), existing, planned.clientsDenyingEveryone());
    }

    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institution", institution);
        out.put("dry_run", dryRun);
        List<Map<String, Object>> cs = new ArrayList<>();
        for (ClientTarget t : clients) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("client_id", t.clientId());
            c.put("name", t.name());
            c.put("join_column", t.column().value());
            c.put("employee_field", t.employeeField());
            c.put("configured", t.configured());
            cs.add(c);
        }
        out.put("clients", cs);
        out.put("totals", totals.describe());
        List<Map<String, Object>> es = new ArrayList<>();
        for (ReportRow r : employees) {
            es.add(r.describe());
        }
        out.put("employees", es);
        return out;
    }
}
