package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/** Per-item outcome of a bulk change. */
public enum ItemStatus {
    APPLIED("applied"),
    REJECTED("rejected"),
    FAILED("failed");

    private final String code;

    ItemStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
