package rw.gov.risa.sso.wrapper.policy.accessmatrix;

/** A matrix column. {@code reason} says why it is not editable (null when it is). */
public record Column(String clientId, String name, ColumnKind kind, boolean editable, String reason) {

    /** "A" or "C". */
    public String type() {
        return kind.type();
    }
}
