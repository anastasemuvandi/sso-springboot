package rw.gov.risa.sso.wrapper.policy.lifecycle;

/** A Keycloak group as the lifecycle plans see it (id + display name; snapshot for rollback). */
public record GroupRef(String id, String name) {
    public GroupRef {
        name = name == null ? "" : name;
    }
}
