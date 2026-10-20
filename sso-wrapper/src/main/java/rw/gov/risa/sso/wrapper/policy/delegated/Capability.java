package rw.gov.risa.sso.wrapper.policy.delegated;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** What a delegated grant permits, in Keycloak FGAP's own vocabulary. */
public enum Capability {
    VIEW_MEMBERS("view-members"),
    MANAGE_MEMBERS("manage-members"),
    MANAGE_MEMBERSHIP("manage-membership"),
    MAP_ROLES("map-roles"),
    VIEW_CLIENTS("view-clients"),
    MANAGE_CLIENTS("manage-clients");

    private final String wire;

    Capability(String wire) {
        this.wire = wire;
    }

    /** The FGAP / templates.json name, e.g. {@code "map-roles"}. */
    public String wire() {
        return wire;
    }

    /** Parse a wire name; empty for an unknown one. */
    public static Optional<Capability> fromWire(String value) {
        return Arrays.stream(values()).filter(c -> c.wire.equals(value)).findFirst();
    }

    /** {@code "view-members, manage-members, ..."} in declaration order (used in error messages). */
    public static String knownList() {
        return Arrays.stream(values()).map(Capability::wire).collect(Collectors.joining(", "));
    }

    @Override
    public String toString() {
        return wire;
    }
}
