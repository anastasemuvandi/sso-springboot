package rw.gov.risa.sso.wrapper.policy.overview;

/** A user and a failure count (either may be null where unknown). */
public record UserCount(String sub, String username, Integer failures) {
}
