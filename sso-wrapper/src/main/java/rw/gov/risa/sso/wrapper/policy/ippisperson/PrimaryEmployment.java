package rw.gov.risa.sso.wrapper.policy.ippisperson;

/**
 * {@code (employment, ambiguousReason)}: at most one is set. Both null means the person has no
 * employment at all, which is not ambiguity.
 */
public record PrimaryEmployment(IppisEmployment employment, String ambiguousReason) {
}
