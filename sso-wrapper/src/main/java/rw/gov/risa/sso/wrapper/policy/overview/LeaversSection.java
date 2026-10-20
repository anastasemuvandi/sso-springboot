package rw.gov.risa.sso.wrapper.policy.overview;

import java.util.List;

/**
 * Section 1: leavers still holding access. {@code total} counts every leaver at risk; {@code items}
 * is the first {@link Overview#MAX_ROWS}. {@code applicationsChecked=false}: the apps they hold
 * could not be read, so only enabled accounts are flagged. {@code truncated}: platform only, more
 * leavers than were checked (the count is a floor).
 */
public record LeaversSection(
        boolean available, String error, boolean applicationsChecked, int total, boolean truncated,
        List<LeaverRow> items) {

    public LeaversSection {
        items = List.copyOf(items);
    }

    public static LeaversSection unavailable(String error) {
        return new LeaversSection(false, error, true, 0, false, List.of());
    }
}
