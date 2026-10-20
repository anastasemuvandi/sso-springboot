package rw.gov.risa.sso.wrapper.service.lifecycle;

/** Error-envelope codes of the lifecycle and IPPIS features (kept with the feature). */
public final class LifecycleErrors {
    private LifecycleErrors() {
    }

    /** 409: an offboarding/reassignment step failed and the completed steps were rolled back. */
    public static final String LIFECYCLE_FAILED = "lifecycle_failed";
    /** 422: reassigning a disabled account (use /offboarding/terminate). */
    public static final String ACCOUNT_DISABLED = "account_disabled";
    /** 503: the audit service behind /lifecycle/audit is unreachable. */
    public static final String AUDIT_UNAVAILABLE = "audit_unavailable";
}
