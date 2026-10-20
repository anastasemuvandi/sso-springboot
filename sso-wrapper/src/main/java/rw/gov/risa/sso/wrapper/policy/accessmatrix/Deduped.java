package rw.gov.risa.sso.wrapper.policy.accessmatrix;

import java.util.List;

/** {@link AccessMatrix#dedupeItems}: first occurrence wins; the rest are duplicates. */
public record Deduped(List<BulkItem> unique, List<BulkItem> duplicates) {

    public Deduped {
        unique = List.copyOf(unique);
        duplicates = List.copyOf(duplicates);
    }
}
