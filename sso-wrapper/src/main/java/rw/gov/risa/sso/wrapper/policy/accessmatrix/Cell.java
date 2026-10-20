package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import java.util.List;

/** What one (employee, client) cell shows. */
public record Cell(boolean hasAccess, boolean direct, List<String> viaGroups) {

    public static final Cell NONE = new Cell(false, false, List.of());

    public Cell {
        viaGroups = viaGroups == null ? List.of() : List.copyOf(viaGroups);
    }
}
