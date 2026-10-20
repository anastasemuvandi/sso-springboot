package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

/**
 * Per-row / per-cell trouble found while reading a file. {@code row} is the 1-based spreadsheet row.
 */
public record Problem(String reason, Integer row, String employeeNumber, String clientId, String value) {

    public String message() {
        return AccessMatrixSheet.PROBLEM_MESSAGES.getOrDefault(reason, reason);
    }
}
