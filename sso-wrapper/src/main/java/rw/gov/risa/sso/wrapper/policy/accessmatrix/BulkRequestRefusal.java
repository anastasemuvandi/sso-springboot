package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/**
 * Why a whole bulk request is refused before any item is judged. {@code httpStatus} is the status
 * the reference answers with (400, or 403 for an employee/client outside the institution — nothing
 * written).
 */
public enum BulkRequestRefusal {
    INVALID_ACTION("invalid_action", 400, "action must be 'grant' or 'revoke'."),
    INVALID_CHANNEL("invalid_channel", 400, "channel must be 'ui' or 'bulk'."),
    NO_ITEMS("no_items", 400, "No changes were sent."),
    TOO_MANY_ITEMS("too_many_items", 400, "At most " + AccessMatrix.MAX_BULK_ITEMS + " changes per request."),
    EMPLOYEE_OUTSIDE_INSTITUTION("employee_outside_institution", 403,
            "Employee(s) are not in the institution's employee list. Nothing was changed."),
    CLIENT_OUTSIDE_SCOPE("client_outside_scope", 403,
            "Application(s) are not in the institution's scope. Nothing was changed.");

    private final String code;
    private final int httpStatus;
    private final String message;

    BulkRequestRefusal(String code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
