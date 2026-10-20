package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/** One requested (employee, client) change. */
public record BulkItem(String employeeNumber, String clientId) {
}
