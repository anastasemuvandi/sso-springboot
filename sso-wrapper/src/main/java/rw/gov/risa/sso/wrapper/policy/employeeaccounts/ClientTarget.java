package rw.gov.risa.sso.wrapper.policy.employeeaccounts;

import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumn;
import rw.gov.risa.sso.wrapper.policy.joinkey.JoinColumnSetting;

/**
 * One selected application with the column it keys on. {@code setting} comes from the client
 * itself ({@link JoinColumnSetting#parse}), never from the request.
 */
public record ClientTarget(String clientId, JoinColumnSetting setting, String name) {

    public JoinColumn column() {
        return setting.column();
    }

    /** Whether a join column was explicitly chosen; an unconfigured client denies everybody. */
    public boolean configured() {
        return setting.explicit();
    }

    /** The employee-record field this client's column reads. */
    public String employeeField() {
        return EmployeeRecord.EMPLOYEE_FIELDS.get(column());
    }
}
