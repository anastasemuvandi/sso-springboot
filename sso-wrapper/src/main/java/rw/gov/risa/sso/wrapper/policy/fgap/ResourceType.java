package rw.gov.risa.sso.wrapper.policy.fgap;

/** FGAP v2 resource types (Keycloak 26.2 identifiers). */
public enum ResourceType {
    GROUPS("Groups"),
    USERS("Users"),
    CLIENTS("Clients"),
    ROLES("Roles");

    private final String wire;

    ResourceType(String wire) {
        this.wire = wire;
    }

    /** {@code "Groups"}, {@code "Users"}, ... as Keycloak names them. */
    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }
}
