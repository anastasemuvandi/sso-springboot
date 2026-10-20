package rw.gov.risa.sso.wrapper.policy.overview;

import java.util.List;

/** {@link Overview#passwordBacklog}: complete counts, and the first 50 rows by urgency. */
public record PasswordBacklog(PasswordSetupCounts counts, List<PasswordSetupRow> items) {

    public PasswordBacklog {
        items = List.copyOf(items);
    }
}
