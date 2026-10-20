package rw.gov.risa.sso.wrapper.policy.clientscope;

/** The three kinds of client a scope resolves to. {@link #code()} is the wire value. */
public enum ClientScopeKind {
    /** Owned by one institution (attribute, or inferred from a {@code <slug>-} prefix). */
    INSTITUTION("institution"),
    /** Platform-owned, usable by the institutions in {@code sso.client.institutions}. */
    CROSS_CUTTING("cross-cutting"),
    /** Nothing recorded: the control plane, Keycloak built-ins, pre-existing clients. */
    PLATFORM("platform");

    private final String code;

    ClientScopeKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
