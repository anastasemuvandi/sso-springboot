package rw.gov.risa.sso.wrapper.policy.overview;

/** Accounts waiting for their first password, by how the set-password email went. */
public record PasswordSetupCounts(int total, int sent, int failed, int notConfigured, int unknown) {
}
