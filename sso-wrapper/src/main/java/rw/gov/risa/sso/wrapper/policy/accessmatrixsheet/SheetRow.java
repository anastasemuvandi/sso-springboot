package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

import java.util.Map;
import rw.gov.risa.sso.wrapper.policy.accessmatrix.AccountState;

/**
 * One exported employee row. {@code hasNid} — only WHETHER a national ID is on file is exported,
 * never the NID. {@code cells}: client id -> has access today. {@code lockedReason}: why nothing in
 * this row may be granted (leaver, unvouched account), or null.
 */
public record SheetRow(
        String employeeNumber,
        String firstName,
        String lastName,
        String email,
        String status,
        AccountState account,
        boolean hasNid,
        Map<String, Boolean> cells,
        String lockedReason) {

    public SheetRow {
        cells = cells == null ? Map.of() : Map.copyOf(cells);
    }

    public SheetRow(String employeeNumber, String firstName, String lastName, String email, String status,
                    AccountState account, boolean hasNid, Map<String, Boolean> cells) {
        this(employeeNumber, firstName, lastName, email, status, account, hasNid, cells, null);
    }
}
