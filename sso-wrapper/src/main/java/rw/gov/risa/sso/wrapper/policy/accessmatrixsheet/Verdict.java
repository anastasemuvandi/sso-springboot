package rw.gov.risa.sso.wrapper.policy.accessmatrixsheet;

/** What importing one cell does: an outcome and, for rejected/invalid, a reason code. */
public record Verdict(String outcome, String reason) {
}
