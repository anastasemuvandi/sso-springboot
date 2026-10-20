package rw.gov.risa.sso.wrapper.policy.overview;

/** Keycloak's {@code attack-detection/brute-force/users/{id}}: {@code disabled} = locked now. */
public record BruteForceStatus(boolean disabled, Integer numFailures) {
}
