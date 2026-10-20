package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import java.util.Optional;

/**
 * Why one (employee, client) change is not applied. {@link #STATE_CHANGED} is the concurrency
 * guard: the console sends the change it computed from what it showed, and an item whose target no
 * longer is in that state was changed by someone else since.
 */
public enum RejectionReason {
    STATE_CHANGED("state_changed",
            "This person already has access — it changed since the page was loaded. Reload to see the current state.",
            "This person no longer has access — it changed since the page was loaded. Reload to see the current state."),
    NOT_MANAGEABLE("not_manageable",
            "Your role may not change access to this application.",
            "Your role may not change access to this application."),
    DUPLICATE("duplicate",
            "The same person and application appear more than once in this request.",
            "The same person and application appear more than once in this request."),
    ACCOUNT_UNRESOLVED("account_unresolved",
            "This employee's SSO account could not be identified safely, so nothing was changed.",
            "This employee's SSO account could not be identified safely, so nothing was changed."),
    EMPLOYEE_LEFT("employee_left",
            "IPPIS reports no open post for this employee here; a leaver is not given access.",
            "IPPIS reports no open post for this employee here."),
    CANNOT_CREATE_ACCOUNT("cannot_create_account",
            "This employee has no SSO account, and your role may not create one "
                    + "(it needs manage-members and manage-membership).",
            "This employee has no SSO account.");

    private final String code;
    private final String grantMessage;
    private final String revokeMessage;

    RejectionReason(String code, String grantMessage, String revokeMessage) {
        this.code = code;
        this.grantMessage = grantMessage;
        this.revokeMessage = revokeMessage;
    }

    public String code() {
        return code;
    }

    /** The person-facing message for this reason under {@code action}. */
    public String message(Action action) {
        return action == Action.REVOKE ? revokeMessage : grantMessage;
    }

    public static Optional<RejectionReason> fromCode(String code) {
        for (RejectionReason r : values()) {
            if (r.code.equals(code)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
