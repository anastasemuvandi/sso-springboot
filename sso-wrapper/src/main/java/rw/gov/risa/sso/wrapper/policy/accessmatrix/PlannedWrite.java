package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/** An account-holder whose cell will be written: the user id and the row's employee number. */
public record PlannedWrite(String userId, String employeeNumber) {
}
