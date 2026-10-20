package rw.gov.risa.sso.wrapper.policy.registry;

/**
 * The registry's own description of one institution (ADR-0001 §5.4).
 *
 * <p>Only the fields a running wrapper has a use for. {@code status} is carried, never
 * filtered on: a decommissioned institution still has people and still has to be
 * explainable in the console.
 */
public record RegistryEntry(String slug, String displayName, String status) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_SUSPENDED = "suspended";
    public static final String STATUS_DECOMMISSIONED = "decommissioned";

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
