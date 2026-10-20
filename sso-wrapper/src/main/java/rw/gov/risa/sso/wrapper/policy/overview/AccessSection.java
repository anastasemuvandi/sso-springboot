package rw.gov.risa.sso.wrapper.policy.overview;

import java.util.List;

/** Section 4: access per application. */
public record AccessSection(boolean available, String error, List<AppAccessRow> items) {

    public AccessSection {
        items = List.copyOf(items);
    }
}
