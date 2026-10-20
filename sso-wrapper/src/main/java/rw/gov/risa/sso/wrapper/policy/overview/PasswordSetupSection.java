package rw.gov.risa.sso.wrapper.policy.overview;

import java.util.List;

/** Section 2: waiting for a first password. {@code counts} is null when unavailable. */
public record PasswordSetupSection(boolean available, String error, PasswordSetupCounts counts,
                                   List<PasswordSetupRow> items) {

    public PasswordSetupSection {
        items = List.copyOf(items);
    }

    public static PasswordSetupSection unavailable(String error) {
        return new PasswordSetupSection(false, error, null, List.of());
    }

    public static PasswordSetupSection of(PasswordBacklog backlog) {
        return new PasswordSetupSection(true, null, backlog.counts(), backlog.items());
    }
}
