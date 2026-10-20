package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/**
 * The client types of the matrix: Type A (the institution's own {@code <slug>-*} client, "owned")
 * and Type C (a cross-cutting client whose scope includes it, "shared"). Type B (platform clients)
 * are never listed.
 */
public enum ColumnKind {
    OWNED("owned", "A"),
    SHARED("shared", "C");

    private final String code;
    private final String type;

    ColumnKind(String code, String type) {
        this.code = code;
        this.type = type;
    }

    public String code() {
        return code;
    }

    /** "A" for owned, "C" for shared. */
    public String type() {
        return type;
    }
}
